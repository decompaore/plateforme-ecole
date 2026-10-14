/**
 * Lot 01 : sécurité, comptes, isolation, droits par rôle et modules de l'établissement.
 *
 * Préfixes : SEC- (authentification, sessions, sécurité transverse), CPT- (comptes, membres, réinitialisation,
 * verrouillage, appareils), ISO- (isolation entre établissements et entre familles), ROL- (matrice des droits),
 * MOD- (modules désactivables).
 *
 * Sources vérifiées : utilisateurs/AuthController, AuthService, AuthDtos, SessionsAppareils, JetonRafraichissement,
 * MoiController, MembresController/Service, ComptesController/Service, socle/securite/SecuriteConfig,
 * MotDePasseTemporaireFilter, SecuriteProperties (5 échecs, grâce de 20 s), socle/tenant/TenantFilter,
 * socle/modules/Module, ModulesEtablissement, ModulesFilter, socle/audit/AuditController,
 * socle/erreurs/GestionnaireErreurs, docs/API_SOCLE.md.
 *
 * Sans effet durable sur les comptes de démonstration : les connexions ouvertes ici sont refermées
 * (déconnexion), le verrouillage, la réinitialisation et la désactivation portent sur un compte créé par la
 * recette (numéro unique), les modules modifiés sont rétablis dans un `finally`, les paramètres de scolarité
 * sont réécrits à l'identique.
 */
import { randomUUID } from 'node:crypto';

import {
  API, appel, appelBrut, cookieDe, Echec, egal, exiger, ignorer, refuse, sansTexte, scenario, statut, suffixe,
  telephoneUnique, vrai,
} from '../lib.mjs';

const D_SEC = 'Sécurité et authentification';
const D_CPT = 'Comptes et sessions';
const D_ISO = 'Isolation des données';
const D_ROL = 'Droits par rôle';
const D_MOD = "Modules de l'établissement";

/** Rôles d'établissement (utilisateurs/Role.java, sans ELEVE qui n'a pas de compte de démonstration). */
const ROLES = ['ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'SECRETARIAT', 'INTENDANT', 'SURVEILLANT', 'ENSEIGNANT', 'PARENT'];

/** app.securite.max-echecs-connexion (application.yml, défaut de SecuriteProperties) */
const MAX_ECHECS = 5;

const BASE = API.replace(/\/api\/v1$/, '');

// ===================================================================== aides

function partage(ctx) {
  ctx.partage.securite ??= {};
  return ctx.partage.securite;
}

/** Charge utile d'un JWT (sans vérification). */
function chargeUtile(jeton) {
  const p = String(jeton ?? '').split('.')[1];
  if (!p) return null;
  try {
    return JSON.parse(Buffer.from(p, 'base64url').toString('utf8'));
  } catch {
    return null;
  }
}

/** En-têtes Set-Cookie bruts d'une réponse. */
function setCookies(r) {
  return typeof r.entetes.getSetCookie === 'function' ? r.entetes.getSetCookie() : [r.entetes.get('set-cookie')].filter(Boolean);
}

async function connecter(telephone, motDePasse) {
  const r = await appelBrut('POST', '/auth/connexion', { telephone, motDePasse });
  return { r, d: r.donnees, cookie: cookieDe(r) };
}

/** Ouvre une session « à la main » (jeton + cookie), choix de l'établissement compris. */
async function ouvrir(telephone, motDePasse, etablissementId = null) {
  const { r, d, cookie } = await connecter(telephone, motDePasse);
  if (!r.ok) throw new Echec(`Connexion impossible (${telephone}) : ${r.resume()}`);
  if (d.selectionRequise) {
    const choix = d.etablissements.find((x) => x.id === etablissementId) ?? d.etablissements[0];
    const r2 = await appelBrut('POST', '/auth/etablissement', { etablissementId: choix.id }, { jeton: d.jetonSelection });
    if (!r2.ok) throw new Echec(`Choix de l'établissement impossible (${telephone}) : ${r2.resume()}`);
    return { jeton: r2.donnees.jetonAcces, cookie: cookieDe(r2) ?? cookie, donnees: r2.donnees };
  }
  return { jeton: d.jetonAcces, cookie, donnees: d };
}

async function rafraichir(cookie) {
  return appelBrut('POST', '/auth/rafraichir', undefined, { cookie });
}

/** Referme une session ouverte par la recette (révoque la famille de jetons). */
async function fermer(cookie) {
  if (cookie) await appelBrut('POST', '/auth/deconnexion', undefined, { cookie });
}

/** Enseignant du premier établissement qui n'est pas partagé, avec une classe où il enseigne et une où il n'enseigne pas. */
function enseignantDeReference(ctx, e = ctx.ecole(1)) {
  const partages = new Set(ctx.ecoles.flatMap((x) => x.enseignants.filter((p) => p.partage).map((p) => p.telephone)));
  for (const p of e.enseignants.filter((x) => !x.partage && !partages.has(x.telephone))) {
    const sienne = e.classes.find((c) => c.matieres.some((m) => m.enseignant === p.telephone));
    const autre = e.classes.find((c) => !c.matieres.some((m) => m.enseignant === p.telephone));
    if (sienne && autre) return { ...p, sienne, autre };
  }
  return null;
}

/** Session d'un rôle de l'établissement (enseignant de référence, premier parent local). */
function sessionDuRole(ctx, e, role) {
  if (role === 'SUPER_ADMIN') return ctx.sa;
  if (role === 'ENSEIGNANT') {
    const p = enseignantDeReference(ctx, e);
    return p ? ctx.compte(p.telephone, e.id, `enseignant ${p.matiere} ${e.code}`) : null;
  }
  if (role === 'PARENT') return e.parent(0);
  return e.session(role);
}

/** Famille commune aux établissements 1 et 3 (un enfant dans chacun). */
function familleCommune(ctx) {
  const e3 = ctx.ecole(3);
  return e3?.familles.find((f) => f.enfants.some((x) => x.ecole === 1) && f.enfants.some((x) => x.ecole === 3)) ?? null;
}

/** Enseignant du lycée technique qui enseigne aussi au lycée Horizon. */
function enseignantPartage(ctx) {
  return ctx.ecole(3)?.enseignants.find((p) => p.partage) ?? null;
}

/** Premier élève inscrit d'un établissement (dans l'état du peuplement). */
function premierEleve(e) {
  return e.familles.flatMap((f) => f.enfants).find((x) => x.ecole === e.n && x.eleveId && x.inscriptionId) ?? null;
}

/** Compte de l'établissement correspondant à un téléphone (format stocké : +226…). */
function compteParTelephone(comptes, telephone) {
  return comptes.find((c) => String(c.telephone ?? '').endsWith(telephone)) ?? null;
}

/** Compte de test créé par CPT-01 (sinon le scénario est ignoré). */
function compteTest(ctx) {
  const t = partage(ctx).compte;
  if (!t?.utilisateurId) ignorer('compte de test absent (CPT-01 en échec)');
  return t;
}

/**
 * Matrice des droits : appelle `requete(session, role)` pour chaque rôle de l'établissement 1 et compare à la
 * liste des rôles autorisés (lue dans les @PreAuthorize). `ecriture` : un rôle autorisé peut recevoir une erreur
 * métier (404, 409, 400) sur des identifiants fictifs ; seul compte qu'il ne reçoive ni 401 ni 403.
 */
async function matrice(ctx, { autorises, requete, ecriture = false, roles = ROLES }) {
  const e = ctx.ecole(1);
  const ecarts = [];
  const vus = [];
  for (const role of roles) {
    const s = sessionDuRole(ctx, e, role);
    if (!s) {
      vus.push(`${role} absent`);
      continue;
    }
    let r;
    try {
      r = await requete(s, role);
    } catch (err) {
      if (err instanceof Echec) {
        ecarts.push(`${role} : ${err.message}`);
        continue;
      }
      throw err;
    }
    vus.push(`${role} ${r.statut}`);
    if (r.code === 'MOT_DE_PASSE_A_CHANGER') {
      ecarts.push(`${role} : mot de passe à renouveler, droit non vérifiable (${r.resume()})`);
      continue;
    }
    const permis = autorises.includes(role);
    if (permis) {
      const ok = ecriture ? ![401, 403].includes(r.statut) && r.statut < 500 : r.ok;
      if (!ok) ecarts.push(`${role} devrait pouvoir : ${r.resume()}`);
    } else if (r.statut !== 403) {
      ecarts.push(`${role} ne devrait pas pouvoir (403 attendu) : ${r.resume()}`);
    }
  }
  if (ecarts.length) throw new Echec(`${ecarts.length} écart(s) — ${ecarts.join(' ; ')}`);
  return `autorisés : ${autorises.join(', ')} · ${vus.join(', ')}`;
}

// ===================================================================== SEC : authentification

scenario('SEC-01', D_SEC, 'Chaque rôle d’un établissement se connecte et reçoit son établissement actif, ses rôles et un cookie de session protégé', async (ctx) => {
  const e = ctx.ecole(1);
  const ref = enseignantDeReference(ctx, e);
  const comptes = [
    ['ADMIN_ECOLE', e.admin],
    ...['CENSEUR', 'CHEF_TRAVAUX', 'SECRETARIAT', 'INTENDANT', 'SURVEILLANT'].map((r) => [r, e.personnel?.[r]]),
    ['ENSEIGNANT', ref?.telephone],
    ['PARENT', e.famille(0)?.telephone],
  ];
  const notes = [];
  const renouveler = [];
  for (const [role, tel] of comptes) {
    if (!tel) {
      notes.push(`${role} absent`);
      continue;
    }
    const { r, d, cookie } = await connecter(tel, ctx.motDePasse);
    try {
      statut(r, 200);
      vrai(!d.selectionRequise, `${role} : pas de choix d'établissement attendu`);
      vrai(d.jetonAcces, `${role} : jeton d'accès absent`);
      egal(d.superAdmin, false, `${role} : superAdmin`);
      egal(d.etablissementActif?.id, e.id, `${role} : établissement actif`);
      vrai(d.etablissementActif?.roles?.includes(role), `${role} : rôle absent de etablissementActif.roles (${JSON.stringify(d.etablissementActif?.roles)})`);
      vrai(!('jetonRafraichissement' in d), `${role} : le jeton de rafraîchissement ne doit jamais être dans le corps`);
      const charge = chargeUtile(d.jetonAcces);
      egal(charge?.tenant_id, e.id, `${role} : tenant_id du jeton`);
      egal(charge?.typ_jeton, 'ACCES', `${role} : type de jeton`);
      vrai(charge?.roles?.includes(role), `${role} : rôle absent du jeton`);
      vrai(cookie?.startsWith('plateforme_rt='), `${role} : cookie de rafraîchissement absent`);
      const brut = setCookies(r).find((c) => c.startsWith('plateforme_rt=')) ?? '';
      vrai(/HttpOnly/i.test(brut) && /SameSite=Strict/i.test(brut) && /Path=\/api\/v1\/auth/i.test(brut),
        `${role} : cookie sans HttpOnly, SameSite=Strict ou Path=/api/v1/auth (${brut.replace(/=[^;]+;/, '=…;')})`);
      if (d.doitChangerMotDePasse) renouveler.push(`${role} (${d.motifChangementMotDePasse})`);
      notes.push(role);
    } finally {
      await fermer(cookie);
    }
  }
  return `${notes.join(', ')}${renouveler.length ? ` — mot de passe à changer : ${renouveler.join(', ')}` : ''}`;
});

scenario('SEC-02', D_SEC, 'Le super administrateur se connecte sans établissement, avec l’indicateur superAdmin', async (ctx) => {
  const { r, d, cookie } = await connecter(ctx.etat.superAdmin.telephone, ctx.etat.superAdmin.motDePasse);
  try {
    statut(r, 200);
    egal(d.superAdmin, true, 'superAdmin');
    egal(d.etablissementActif, null, 'établissement actif');
    egal(d.selectionRequise, false, 'selectionRequise');
    const charge = chargeUtile(d.jetonAcces);
    vrai(charge && !charge.tenant_id, 'le jeton du super administrateur ne doit pas porter de tenant_id');
    const moi = await exiger(d.jetonAcces, 'GET', '/moi');
    egal(moi.superAdmin, true, 'GET /moi superAdmin');
    egal(moi.etablissementId, null, 'GET /moi etablissementId');
  } finally {
    await fermer(cookie);
  }
});

scenario('SEC-03', D_SEC, 'Un mauvais mot de passe, un compte inconnu ou un numéro invalide reçoivent la même réponse 401, sans dire si le compte existe', async (ctx) => {
  const tel = ctx.ecole(2)?.personnel?.SURVEILLANT;
  if (!tel) ignorer('surveillant de l’établissement 2 absent');
  const faux = await connecter(tel, `Faux-${suffixe()}9`);
  // Un seul échec sur ce compte de démonstration, remis à zéro tout de suite par une connexion réussie
  const ok = await connecter(tel, ctx.motDePasse);
  await fermer(ok.cookie);
  const inconnu = await connecter(telephoneUnique('57'), `Faux-${suffixe()}9`);
  const invalide = await connecter('pas-un-numero', 'nimporte1');
  statut(faux.r, 401);
  statut(inconnu.r, 401);
  statut(invalide.r, 401);
  egal(inconnu.r.detail, faux.r.detail, 'Message du compte inconnu et du mauvais mot de passe');
  egal(invalide.r.detail, faux.r.detail, 'Message du numéro invalide et du mauvais mot de passe');
  vrai(!faux.cookie && !inconnu.cookie, 'aucun cookie ne doit être posé après un échec');
  statut(ok.r, 200);
  // Corps incomplet : validation
  statut(await appelBrut('POST', '/auth/connexion', { telephone: tel }), 400);
  return `« ${faux.r.detail} »`;
});

scenario('SEC-04', D_SEC, 'L’enseignant partagé choisit son établissement ; le jeton de sélection n’ouvre pas l’API et un établissement qui n’est pas le sien est refusé', async (ctx) => {
  const p = enseignantPartage(ctx);
  if (!p) ignorer('aucun enseignant partagé dans le peuplement');
  const e1 = ctx.ecole(1);
  const e2 = ctx.ecole(2);
  const e3 = ctx.ecole(3);
  const { r, d } = await connecter(p.telephone, ctx.motDePasse);
  statut(r, 200);
  egal(d.selectionRequise, true, 'selectionRequise');
  vrai(d.jetonSelection && !d.jetonAcces, 'jeton de sélection seul attendu');
  vrai(!cookieDe(r), 'pas de cookie avant le choix de l’établissement');
  const ids = d.etablissements.map((x) => x.id);
  vrai(ids.includes(e1.id) && ids.includes(e3.id), `établissements proposés : ${d.etablissements.map((x) => x.code).join(', ')}`);
  vrai(d.etablissements.every((x) => x.roles.includes('ENSEIGNANT')), 'rôle ENSEIGNANT attendu dans chaque établissement');
  egal(chargeUtile(d.jetonSelection)?.typ_jeton, 'SELECTION', 'type du jeton de sélection');

  // Le jeton de sélection ne sert qu'à /auth/etablissement
  statut(await appel(d.jetonSelection, 'GET', '/moi'), [401, 403]);
  statut(await appel(d.jetonSelection, 'GET', `/classes/${e3.classes[0].id}`), [401, 403]);
  // Établissement où il n'a aucun rôle : AccesRefuseException
  statut(await appelBrut('POST', '/auth/etablissement', { etablissementId: e2.id }, { jeton: d.jetonSelection }), 403);
  statut(await appelBrut('POST', '/auth/etablissement', { etablissementId: randomUUID() }, { jeton: d.jetonSelection }), 403);

  const r3 = await appelBrut('POST', '/auth/etablissement', { etablissementId: e3.id }, { jeton: d.jetonSelection });
  statut(r3, 200);
  egal(r3.donnees.etablissementActif?.id, e3.id, 'établissement actif après le choix');
  egal(chargeUtile(r3.donnees.jetonAcces)?.tenant_id, e3.id, 'tenant_id du jeton');
  const c3 = cookieDe(r3);
  vrai(c3, 'cookie posé après le choix');
  // Changement d'établissement avec le jeton d'accès : l'ancien cookie est révoqué
  const r1 = await appelBrut('POST', '/auth/etablissement', { etablissementId: e1.id }, { jeton: r3.donnees.jetonAcces, cookie: c3 });
  statut(r1, 200);
  egal(r1.donnees.etablissementActif?.id, e1.id, 'établissement actif après le changement');
  const moi = await exiger(r1.donnees.jetonAcces, 'GET', '/moi');
  egal(moi.etablissementId, e1.id, 'GET /moi après le changement');
  statut(await rafraichir(c3), 401);
  await fermer(cookieDe(r1));
});

scenario('SEC-05', D_SEC, 'Le parent qui a des enfants dans deux établissements doit choisir l’établissement à la connexion', async (ctx) => {
  const f = familleCommune(ctx);
  if (!f) ignorer('famille commune aux établissements 1 et 3 absente du peuplement');
  const { r, d, cookie } = await connecter(f.telephone, ctx.motDePasse);
  statut(r, 200);
  if (!d.selectionRequise) {
    await fermer(cookie);
    ignorer(`le parent commun n'a un espace parent que dans ${d.etablissementActif?.code}`);
  }
  const ids = d.etablissements.map((x) => x.id);
  vrai(ids.includes(ctx.ecole(1).id) && ids.includes(ctx.ecole(3).id), `établissements proposés : ${d.etablissements.map((x) => x.code).join(', ')}`);
  vrai(d.etablissements.every((x) => x.roles.includes('PARENT')), 'rôle PARENT attendu dans chaque établissement');
  statut(await appel(d.jetonSelection, 'GET', '/espace-parent/enfants'), [401, 403]);
  statut(await appelBrut('POST', '/auth/etablissement', { etablissementId: ctx.ecole(2).id }, { jeton: d.jetonSelection }), 403);
});

scenario('SEC-06', D_SEC, 'Sans jeton, avec un jeton bidon ou un jeton dont on a modifié les rôles, l’API répond 401', async (ctx) => {
  const e = ctx.ecole(1);
  statut(await appel(null, 'GET', '/moi'), 401);
  statut(await appel(null, 'GET', `/classes/${e.classes[0].id}`), 401);
  statut(await appel('abc.def.ghi', 'GET', '/moi'), 401);
  // Jeton d'un surveillant dont la charge utile est réécrite (ADMIN_ECOLE) : la signature ne correspond plus
  const s = e.session('SURVEILLANT');
  if (!s) ignorer('surveillant absent');
  const jeton = await s.jeton();
  const [entete, charge, signature] = jeton.split('.');
  const modifiee = { ...JSON.parse(Buffer.from(charge, 'base64url').toString('utf8')), roles: ['ADMIN_ECOLE'] };
  const falsifie = `${entete}.${Buffer.from(JSON.stringify(modifiee)).toString('base64url')}.${signature}`;
  statut(await appel(falsifie, 'GET', '/membres'), 401);
  // Signature altérée
  const altere = `${entete}.${charge}.${signature.slice(0, -2)}${signature.endsWith('AA') ? 'BB' : 'AA'}`;
  statut(await appel(altere, 'GET', '/moi'), 401);
  // Le vrai jeton fonctionne
  await exiger(s, 'GET', '/moi');
});

scenario('SEC-07', D_SEC, 'Les routes publiques répondent sans connexion : santé, vérification d’un bulletin ou d’un reçu (code inconnu : 404)', async () => {
  let sante;
  try {
    sante = await fetch(`${BASE}/actuator/health`, { headers: { Accept: 'application/json' } });
  } catch (e) {
    throw new Echec(`/actuator/health injoignable : ${e.message}`);
  }
  vrai(sante.status === 200, `/actuator/health → ${sante.status}`);
  const corps = await sante.json().catch(() => null);
  egal(corps?.status, 'UP', 'état de santé');
  statut(await appel(null, 'GET', '/verification/bulletins/ZZZZ9ZZZZ9'), 404);
  statut(await appel(null, 'GET', '/verification/recus/ZZZZ9ZZZZ9'), 404);
  statut(await appel(null, 'GET', '/verification/recus/court'), 404);
  // Les autres routes restent fermées
  statut(await appel(null, 'GET', '/plateforme/etablissements'), 401);
  statut(await appel(null, 'GET', '/audit'), 401);
});

scenario('SEC-08', D_SEC, 'Les réponses de l’API portent les en-têtes de sécurité (CSP stricte, pas d’encadrement)', async (ctx) => {
  const r = await appel(ctx.ecole(1).session('ADMIN_ECOLE'), 'GET', '/moi');
  statut(r, 200);
  const csp = r.entetes.get('content-security-policy') ?? '';
  vrai(csp.includes("default-src 'none'") && csp.includes("frame-ancestors 'none'"), `Content-Security-Policy : « ${csp} »`);
  egal((r.entetes.get('x-frame-options') ?? '').toUpperCase(), 'DENY', 'X-Frame-Options');
  egal((r.entetes.get('x-content-type-options') ?? '').toLowerCase(), 'nosniff', 'X-Content-Type-Options');
});

scenario('SEC-09', D_SEC, 'Le cookie renouvelle la session : nouveau jeton d’accès, nouveau cookie, même établissement', async (ctx) => {
  const e = ctx.ecole(2);
  const tel = e?.personnel?.SURVEILLANT;
  if (!tel) ignorer('surveillant de l’établissement 2 absent');
  const s = await ouvrir(tel, ctx.motDePasse, e.id);
  let dernier = s.cookie;
  try {
    vrai(s.cookie, 'cookie absent après la connexion');
    statut(await rafraichir(null), 401);
    statut(await rafraichir('plateforme_rt=inconnu'), 401);
    const r = await rafraichir(s.cookie);
    statut(r, 200);
    const c1 = cookieDe(r);
    vrai(c1 && c1 !== s.cookie, 'le cookie doit changer à chaque renouvellement (rotation)');
    dernier = c1;
    egal(r.donnees.etablissementActif?.id, e.id, 'établissement du jeton renouvelé');
    vrai(r.donnees.jetonAcces && r.donnees.jetonAcces !== s.jeton, 'nouveau jeton d’accès attendu');
    const moi = await exiger(r.donnees.jetonAcces, 'GET', '/moi');
    egal(moi.etablissementId, e.id, 'GET /moi avec le jeton renouvelé');
  } finally {
    await fermer(dernier);
  }
});

scenario('SEC-10', D_SEC, 'Un ancien cookie rejoué juste après un renouvellement perdu est accepté une fois (grâce de réseau faible)', async (ctx) => {
  const e = ctx.ecole(2);
  const tel = e?.personnel?.SURVEILLANT;
  if (!tel) ignorer('surveillant de l’établissement 2 absent');
  const s = await ouvrir(tel, ctx.motDePasse, e.id);
  let dernier = s.cookie;
  try {
    const r1 = await rafraichir(s.cookie);
    statut(r1, 200);
    // La réponse r1 est « perdue » : le téléphone rejoue l'ancien cookie dans les 20 secondes
    const rejoue = await rafraichir(s.cookie);
    statut(rejoue, 200);
    const c1bis = cookieDe(rejoue);
    vrai(c1bis, 'nouveau cookie attendu après le rejeu toléré');
    dernier = c1bis;
    const suite = await rafraichir(c1bis);
    statut(suite, 200);
    dernier = cookieDe(suite) ?? dernier;
  } finally {
    await fermer(dernier);
  }
  return 'grâce app.securite.grace-rafraichissement (20 s par défaut)';
});

scenario('SEC-11', D_SEC, 'Un ancien cookie rejoué après usage de son successeur est traité comme un vol : toute la session est révoquée', async (ctx) => {
  const e = ctx.ecole(2);
  const tel = e?.personnel?.SURVEILLANT;
  if (!tel) ignorer('surveillant de l’établissement 2 absent');
  const s = await ouvrir(tel, ctx.motDePasse, e.id);
  const r1 = await rafraichir(s.cookie);
  statut(r1, 200);
  const c1 = cookieDe(r1);
  const r2 = await rafraichir(c1);
  statut(r2, 200);
  const c2 = cookieDe(r2);
  // Rejeu du premier cookie : son successeur a déjà servi
  statut(await rafraichir(s.cookie), 401);
  // Toute la famille est révoquée, y compris le cookie le plus récent
  const apres = await rafraichir(c2);
  if (apres.ok) {
    await fermer(cookieDe(apres));
    throw new Echec('le dernier cookie de la session fonctionne encore après la détection du rejeu');
  }
  statut(apres, 401);
});

scenario('SEC-12', D_SEC, 'La déconnexion efface le cookie et le rend inutilisable', async (ctx) => {
  const e = ctx.ecole(2);
  const tel = e?.personnel?.CENSEUR;
  if (!tel) ignorer('censeur de l’établissement 2 absent');
  const s = await ouvrir(tel, ctx.motDePasse, e.id);
  const r = await appelBrut('POST', '/auth/deconnexion', undefined, { cookie: s.cookie });
  statut(r, 204);
  const efface = setCookies(r).find((c) => c.startsWith('plateforme_rt='));
  vrai(efface && /Max-Age=0/i.test(efface), `cookie non effacé (${efface ?? 'aucun Set-Cookie'})`);
  statut(await rafraichir(s.cookie), 401);
  // Sans cookie : sans effet
  statut(await appelBrut('POST', '/auth/deconnexion'), 204);
});

scenario('SEC-13', D_SEC, 'Chacun voit ses appareils connectés, peut en fermer un à distance, mais pas celui d’un autre compte', async (ctx) => {
  const e = ctx.ecole(2);
  const tels = e?.personnel;
  if (!tels?.SURVEILLANT || !tels?.CENSEUR) ignorer('surveillant ou censeur de l’établissement 2 absent');
  const a = await ouvrir(tels.SURVEILLANT, ctx.motDePasse, e.id);
  const b = await ouvrir(tels.CENSEUR, ctx.motDePasse, e.id);
  try {
    const liste = await exiger(a.jeton, 'GET', '/auth/appareils', undefined, { cookie: a.cookie });
    vrai(Array.isArray(liste) && liste.length >= 1, 'liste des appareils vide');
    const courant = liste.find((x) => x.courant);
    vrai(courant, 'l’appareil courant n’est pas signalé (courant=true)');
    vrai(courant.appareil && courant.ouverteLe, 'appareil sans description ni date d’ouverture');
    egal(courant.etablissement, e.nom, 'établissement de la session');
    // Un autre compte ne peut pas fermer cet appareil
    statut(await appel(b.jeton, 'POST', `/auth/appareils/${courant.id}/fermeture`, { effacer: false }), 404);
    statut(await rafraichir(a.cookie).then(async (r) => {
      // Le cookie fonctionne toujours : on garde le nouveau pour la suite
      a.cookie = cookieDe(r) ?? a.cookie;
      return r;
    }), 200);
    // Le titulaire ferme sa session à distance (depuis un autre appareil, ici avec son jeton)
    statut(await appel(a.jeton, 'POST', `/auth/appareils/${courant.id}/fermeture`, { effacer: false }), 204);
    statut(await rafraichir(a.cookie), 401);
    const apres = await exiger(a.jeton, 'GET', '/auth/appareils');
    vrai(!apres.some((x) => x.id === courant.id), 'la session fermée figure encore parmi les appareils connectés');
    return 'le jeton d’accès déjà émis reste valable 15 min au plus (documenté)';
  } finally {
    await fermer(a.cookie);
    await fermer(b.cookie);
  }
});

// ===================================================================== CPT : comptes et membres

scenario('CPT-01', D_CPT, 'L’administrateur ajoute une secrétaire : le mot de passe provisoire n’est affiché qu’une fois', async (ctx) => {
  const e = ctx.ecole(3);
  const admin = e.session('ADMIN_ECOLE');
  const telephone = telephoneUnique('58');
  const nom = `RECETTE${suffixe().toUpperCase()}`;
  const r = await appel(admin, 'POST', '/membres', { telephone, nom, prenoms: 'Awa', role: 'SECRETARIAT' });
  statut(r, 201);
  const { membre, motDePasseTemporaire } = r.donnees;
  vrai(typeof motDePasseTemporaire === 'string' && motDePasseTemporaire.length >= 8, 'mot de passe provisoire absent');
  egal(membre.role, 'SECRETARIAT', 'rôle');
  egal(membre.actif, true, 'membre actif');
  vrai(membre.telephone?.endsWith(telephone), `téléphone normalisé : ${membre.telephone}`);
  partage(ctx).compte = { telephone, nom, utilisateurId: membre.utilisateurId, membreId: membre.id, provisoire: motDePasseTemporaire };

  const comptes = await appel(admin, 'GET', '/comptes');
  statut(comptes, 200);
  const c = comptes.donnees.find((x) => x.utilisateurId === membre.utilisateurId);
  vrai(c, 'le nouveau compte n’apparaît pas dans GET /comptes');
  egal(c.motDePasseProvisoire, true, 'motDePasseProvisoire');
  egal(c.roles, ['SECRETARIAT'], 'rôles du compte');
  sansTexte(comptes, [motDePasseTemporaire], 'Mot de passe provisoire réaffiché');
  const membres = await appel(admin, 'GET', '/membres');
  statut(membres, 200);
  vrai(membres.donnees.some((m) => m.id === membre.id), 'le membre n’apparaît pas dans GET /membres');
  sansTexte(membres, [motDePasseTemporaire], 'Mot de passe provisoire réaffiché');
  return `compte ${telephone}`;
});

scenario('CPT-02', D_CPT, 'Tant que le mot de passe provisoire n’est pas changé, seuls le profil et le changement de mot de passe sont permis', async (ctx) => {
  const t = compteTest(ctx);
  const e = ctx.ecole(3);
  const eleve = premierEleve(e);
  const { r, d, cookie } = await connecter(t.telephone, t.provisoire);
  try {
    statut(r, 200);
    egal(d.doitChangerMotDePasse, true, 'doitChangerMotDePasse');
    egal(d.motifChangementMotDePasse, 'PROVISOIRE', 'motif');
    egal(d.etablissementActif?.id, e.id, 'établissement actif');
    egal(chargeUtile(d.jetonAcces)?.mdp_a_changer, true, 'revendication mdp_a_changer');
    const moi = await exiger(d.jetonAcces, 'GET', '/moi');
    egal(moi.doitChangerMotDePasse, true, 'GET /moi doitChangerMotDePasse');
    await exiger(d.jetonAcces, 'GET', '/moi/etablissements');
    await exiger(d.jetonAcces, 'GET', '/auth/appareils', undefined, { cookie });
    if (eleve) statut(await appel(d.jetonAcces, 'GET', `/eleves/${eleve.eleveId}`), 403, 'MOT_DE_PASSE_A_CHANGER');
    statut(await appel(d.jetonAcces, 'GET', `/classes/${e.classes[0].id}`), 403, 'MOT_DE_PASSE_A_CHANGER');
  } finally {
    await fermer(cookie);
  }
});

scenario('CPT-03', D_CPT, 'Le nouveau mot de passe doit être assez fort ; une fois changé, la secrétaire travaille normalement', async (ctx) => {
  const t = compteTest(ctx);
  const e = ctx.ecole(3);
  const eleve = premierEleve(e);
  const s = await ouvrir(t.telephone, t.provisoire, e.id);
  let cookie = s.cookie;
  try {
    const changer = (actuel, nouveau) => appel(s.jeton, 'POST', '/moi/mot-de-passe', { motDePasseActuel: actuel, nouveauMotDePasse: nouveau });
    statut(await changer(t.provisoire, 'ab1'), 400); // moins de 8 caractères (validation)
    statut(await changer(t.provisoire, 'abcdefghij'), 409, 'MOT_DE_PASSE_FAIBLE'); // sans chiffre
    statut(await changer(t.provisoire, '1234567890'), 409, 'MOT_DE_PASSE_FAIBLE'); // sans lettre
    statut(await changer(t.provisoire, t.provisoire), 409, 'MOT_DE_PASSE_IDENTIQUE');
    statut(await changer(`Faux-${suffixe()}1`, `Recette-${suffixe()}7`), 401); // mot de passe actuel incorrect
    const mdp = `Recette-${suffixe()}7`;
    statut(await changer(t.provisoire, mdp), 204);
    t.motDePasse = mdp;
    const ancienProvisoire = t.provisoire;
    t.provisoire = null;
    // Le jeton déjà émis porte encore mdp_a_changer : /auth/rafraichir en donne un à jour
    const r = await rafraichir(cookie);
    statut(r, 200);
    cookie = cookieDe(r) ?? cookie;
    egal(r.donnees.doitChangerMotDePasse, false, 'doitChangerMotDePasse après renouvellement');
    if (eleve) await exiger(r.donnees.jetonAcces, 'GET', `/eleves/${eleve.eleveId}`);
    await exiger(r.donnees.jetonAcces, 'GET', `/classes/${e.classes[0].id}`);
    // L'ancien mot de passe provisoire ne fonctionne plus (un échec compté, remis à zéro par la connexion suivante)
    statut((await connecter(t.telephone, ancienProvisoire)).r, 401);
    const ok = await connecter(t.telephone, mdp);
    await fermer(ok.cookie);
    statut(ok.r, 200);
  } finally {
    await fermer(cookie);
  }
});

scenario('CPT-04', D_CPT, 'Téléphone perdu : l’administrateur ferme et fait effacer les appareils du compte ; un autre établissement ne le peut pas', async (ctx) => {
  const t = compteTest(ctx);
  if (!t.motDePasse) ignorer('mot de passe du compte de test non choisi (CPT-03 en échec)');
  const e = ctx.ecole(3);
  const s = await ouvrir(t.telephone, t.motDePasse, e.id);
  try {
    const liste = await exiger(s.jeton, 'GET', '/auth/appareils', undefined, { cookie: s.cookie });
    vrai(liste.some((x) => x.courant), 'session courante absente de la liste des appareils');
    const comptes = await exiger(e.session('ADMIN_ECOLE'), 'GET', '/comptes');
    const c = comptes.find((x) => x.utilisateurId === t.utilisateurId);
    vrai(c && c.appareilsConnectes >= 1, `appareilsConnectes : ${c?.appareilsConnectes}`);

    // L'administrateur d'un autre établissement ne voit pas ce compte
    refuse(await appel(ctx.ecole(1).session('ADMIN_ECOLE'), 'POST', `/comptes/${t.utilisateurId}/appareils/fermeture`, { effacer: true }),
      'Fermeture par l’administrateur d’un autre établissement');
    // Son propre compte : MON_PROPRE_COMPTE (vérifié sur le déverrouillage, sans effet)
    const moi = await exiger(e.session('ADMIN_ECOLE'), 'GET', '/moi');
    statut(await appel(e.session('ADMIN_ECOLE'), 'POST', `/comptes/${moi.id}/deverrouillage`), 409, 'MON_PROPRE_COMPTE');

    const r = await appel(e.session('ADMIN_ECOLE'), 'POST', `/comptes/${t.utilisateurId}/appareils/fermeture`, { effacer: true });
    statut(r, 200);
    vrai(r.donnees.appareils >= 1, `appareils fermés : ${r.donnees.appareils}`);
    // Au prochain contact, l'application doit tout effacer
    statut(await rafraichir(s.cookie), 401, 'APPAREIL_A_EFFACER');
  } finally {
    await fermer(s.cookie);
  }
});

scenario('CPT-05', D_CPT, `Après ${MAX_ECHECS} mots de passe faux, le compte est verrouillé, même avec le bon mot de passe, sans le dire`, async (ctx) => {
  const t = compteTest(ctx);
  if (!t.motDePasse) ignorer('mot de passe du compte de test non choisi (CPT-03 en échec)');
  const details = new Set();
  for (let i = 0; i < MAX_ECHECS; i++) {
    const { r } = await connecter(t.telephone, `Faux-${suffixe()}${i}`);
    statut(r, 401);
    details.add(r.detail);
  }
  const { r, cookie } = await connecter(t.telephone, t.motDePasse);
  if (r.ok) {
    await fermer(cookie);
    throw new Echec(`le compte n'est pas verrouillé après ${MAX_ECHECS} échecs`);
  }
  statut(r, 401);
  details.add(r.detail);
  egal(details.size, 1, 'Le message du compte verrouillé doit être celui d’un mauvais mot de passe');
  const comptes = await exiger(ctx.ecole(3).session('ADMIN_ECOLE'), 'GET', '/comptes');
  const c = comptes.find((x) => x.utilisateurId === t.utilisateurId);
  vrai(c?.verrouilleJusqua, 'verrouilleJusqua absent dans GET /comptes');
  t.verrouille = true;
  return `verrouillé jusqu’à ${c.verrouilleJusqua}`;
});

scenario('CPT-06', D_CPT, 'Seul l’administrateur de l’établissement déverrouille le compte, qui se reconnecte aussitôt', async (ctx) => {
  const t = compteTest(ctx);
  if (!t.verrouille) ignorer('compte de test non verrouillé (CPT-05 en échec)');
  const autre = ctx.ecole(1).session('ADMIN_ECOLE');
  refuse(await appel(autre, 'POST', `/comptes/${t.utilisateurId}/deverrouillage`), 'Déverrouillage par un autre établissement');
  refuse(await appel(autre, 'POST', `/comptes/${t.utilisateurId}/reinitialisation`), 'Réinitialisation par un autre établissement');
  // Un rôle autre qu'administrateur ne le peut pas non plus
  statut(await appel(ctx.ecole(3).session('SECRETARIAT'), 'POST', `/comptes/${t.utilisateurId}/deverrouillage`), 403);
  // Toujours verrouillé
  statut((await connecter(t.telephone, t.motDePasse)).r, 401);

  const c = await exiger(ctx.ecole(3).session('ADMIN_ECOLE'), 'POST', `/comptes/${t.utilisateurId}/deverrouillage`);
  egal(c.utilisateurId, t.utilisateurId, 'compte déverrouillé');
  egal(c.verrouilleJusqua, null, 'verrouilleJusqua');
  t.verrouille = false;
  const { r, cookie } = await connecter(t.telephone, t.motDePasse);
  await fermer(cookie);
  statut(r, 200);
});

scenario('CPT-07', D_CPT, 'Mot de passe oublié : l’administrateur réinitialise, toutes les sessions sont fermées et un changement est exigé', async (ctx) => {
  const t = compteTest(ctx);
  if (!t.motDePasse) ignorer('mot de passe du compte de test non choisi (CPT-03 en échec)');
  const e = ctx.ecole(3);
  const avant = await ouvrir(t.telephone, t.motDePasse, e.id);
  const r = await appel(e.session('ADMIN_ECOLE'), 'POST', `/comptes/${t.utilisateurId}/reinitialisation`);
  statut(r, 200);
  const provisoire = r.donnees.motDePasseTemporaire;
  vrai(typeof provisoire === 'string' && provisoire.length >= 8, 'nouveau mot de passe provisoire absent');
  egal(r.donnees.utilisateurId, t.utilisateurId, 'compte réinitialisé');
  egal(r.donnees.autresEtablissements, 0, 'autresEtablissements');
  // Sessions fermées, ancien mot de passe refusé
  statut(await rafraichir(avant.cookie), 401);
  statut((await connecter(t.telephone, t.motDePasse)).r, 401);
  // Le provisoire impose un changement avant tout
  const s = await ouvrir(t.telephone, provisoire, e.id);
  try {
    egal(s.donnees.doitChangerMotDePasse, true, 'doitChangerMotDePasse');
    egal(s.donnees.motifChangementMotDePasse, 'PROVISOIRE', 'motif');
    statut(await appel(s.jeton, 'GET', `/classes/${e.classes[0].id}`), 403, 'MOT_DE_PASSE_A_CHANGER');
    const mdp = `Recette-${suffixe()}8`;
    statut(await appel(s.jeton, 'POST', '/moi/mot-de-passe', { motDePasseActuel: provisoire, nouveauMotDePasse: mdp }), 204);
    t.motDePasse = mdp;
  } finally {
    await fermer(s.cookie);
  }
  const { r: r2, cookie } = await connecter(t.telephone, t.motDePasse);
  await fermer(cookie);
  statut(r2, 200);
  egal(r2.donnees.doitChangerMotDePasse, false, 'doitChangerMotDePasse après le changement');
});

scenario('CPT-08', D_CPT, 'L’ajout d’un membre refuse un numéro mal formé, un compte de la plateforme et un rôle déjà attribué', async (ctx) => {
  const t = compteTest(ctx);
  const admin = ctx.ecole(3).session('ADMIN_ECOLE');
  statut(await appel(admin, 'POST', '/membres', { telephone: '12ab', nom: 'TEST', prenoms: 'Numéro', role: 'SECRETARIAT' }), 400);
  statut(await appel(admin, 'POST', '/membres', { telephone: '123', nom: 'TEST', prenoms: 'Court', role: 'SECRETARIAT' }), 400);
  statut(await appel(admin, 'POST', '/membres', { telephone: telephoneUnique('58'), nom: 'TEST', prenoms: 'Sans rôle' }), 400);
  statut(await appel(admin, 'POST', '/membres', { telephone: ctx.etat.superAdmin.telephone, nom: 'TEST', prenoms: 'Plateforme', role: 'SECRETARIAT' }),
    409, 'COMPTE_PLATEFORME');
  statut(await appel(admin, 'POST', '/membres', { telephone: t.telephone, nom: t.nom, prenoms: 'Awa', role: 'SECRETARIAT' }), 409, 'DEJA_MEMBRE');
  // Rôle réservé à l'administrateur
  statut(await appel(ctx.ecole(3).session('SECRETARIAT'), 'POST', '/membres', { telephone: telephoneUnique('58'), nom: 'TEST', prenoms: 'Refus', role: 'SECRETARIAT' }), 403);
});

scenario('CPT-09', D_CPT, 'Un membre désactivé ne peut plus se connecter à l’établissement ; un autre établissement ne peut pas le désactiver', async (ctx) => {
  const t = compteTest(ctx);
  if (!t.motDePasse) ignorer('mot de passe du compte de test non choisi');
  const admin = ctx.ecole(3).session('ADMIN_ECOLE');
  refuse(await appel(ctx.ecole(1).session('ADMIN_ECOLE'), 'POST', `/membres/${t.membreId}/desactivation`), 'Désactivation par un autre établissement');
  statut((await connecter(t.telephone, t.motDePasse)).r, 200);

  statut(await appel(admin, 'POST', `/membres/${t.membreId}/desactivation`), 204);
  statut(await appel(admin, 'POST', `/membres/${t.membreId}/desactivation`), 204); // déjà inactif : sans effet
  const { r, cookie } = await connecter(t.telephone, t.motDePasse);
  await fermer(cookie);
  statut(r, 401);
  const comptes = await exiger(admin, 'GET', '/comptes');
  const c = comptes.find((x) => x.utilisateurId === t.utilisateurId);
  vrai(c, 'le compte doit rester visible dans GET /comptes');
  egal(c.actif, false, 'compte actif');
  vrai(c.rolesRetires.includes('SECRETARIAT'), `rôles retirés : ${JSON.stringify(c.rolesRetires)}`);
  // Les actions de compte ne s'appliquent plus (aucun rôle actif)
  statut(await appel(admin, 'POST', `/comptes/${t.utilisateurId}/reinitialisation`), 404);
  t.desactive = true;
  return `« ${r.detail} »`;
});

scenario('CPT-10', D_CPT, 'L’administrateur retrouve au journal d’audit les actions faites sur le compte ; les autres rôles et établissements non', async (ctx) => {
  const t = compteTest(ctx);
  const e = ctx.ecole(3);
  const r = await appel(e.session('ADMIN_ECOLE'), 'GET', '/audit', undefined, { params: { page: 0, taille: 200 } });
  statut(r, 200);
  const entrees = r.donnees.elements ?? [];
  const miennes = entrees.filter((x) => JSON.stringify(x).includes(t.utilisateurId) || JSON.stringify(x).includes(t.membreId));
  const actions = [...new Set(miennes.map((x) => x.action))];
  vrai(actions.includes('MEMBRE_AJOUTE'), `MEMBRE_AJOUTE absent du journal (trouvé : ${actions.join(', ') || 'rien'})`);
  vrai(actions.some((a) => ['COMPTE_DEVERROUILLE', 'MOT_DE_PASSE_REINITIALISE', 'APPAREILS_A_EFFACER', 'MEMBRE_DESACTIVE'].includes(a)),
    `aucune action d'administration du compte au journal (trouvé : ${actions.join(', ')})`);
  vrai(entrees.every((x) => !('adresseIp' in x)), 'l’adresse IP ne doit pas être exposée à l’établissement');
  statut(await appel(e.session('SECRETARIAT'), 'GET', '/audit'), 403);
  const autre = await appel(ctx.ecole(1).session('ADMIN_ECOLE'), 'GET', '/audit', undefined, { params: { page: 0, taille: 200 } });
  statut(autre, 200);
  sansTexte(autre, [t.utilisateurId, t.membreId], 'Journal d’audit d’un autre établissement');
  return actions.join(', ');
});

// ===================================================================== ISO : isolation

scenario('ISO-01', D_ISO, 'L’administrateur d’un établissement ne lit ni les élèves, ni les inscriptions, ni les classes d’un autre', async (ctx) => {
  const e3 = ctx.ecole(3);
  const eleve = premierEleve(e3);
  if (!eleve) ignorer('aucun élève dans l’établissement 3');
  const a1 = ctx.ecole(1).session('ADMIN_ECOLE');
  const a3 = e3.session('ADMIN_ECOLE');
  // Contrôle : les identifiants sont valides dans leur établissement
  await exiger(a3, 'GET', `/eleves/${eleve.eleveId}`);
  await exiger(a3, 'GET', `/inscriptions/${eleve.inscriptionId}`);
  await exiger(a3, 'GET', `/classes/${e3.classes[0].id}`);
  refuse(await appel(a1, 'GET', `/eleves/${eleve.eleveId}`), 'Élève d’un autre établissement');
  refuse(await appel(a1, 'GET', `/inscriptions/${eleve.inscriptionId}`), 'Inscription d’un autre établissement');
  refuse(await appel(a1, 'GET', `/classes/${e3.classes[0].id}`), 'Classe d’un autre établissement');
  refuse(await appel(a1, 'GET', `/classes/${e3.classes[0].id}/evaluations`, undefined, { params: { periodeId: e3.periodes[0]?.id } }),
    'Évaluations d’un autre établissement');
  refuse(await appel(a1, 'GET', `/annees/${e3.anneeId}/statistiques`), 'Statistiques d’un autre établissement');
});

scenario('ISO-02', D_ISO, 'Ni la situation financière ni un reçu d’un autre établissement ne sont accessibles', async (ctx) => {
  const e3 = ctx.ecole(3);
  const intendant = e3.session('INTENDANT');
  const eleves = e3.familles.flatMap((f) => f.enfants).filter((x) => x.ecole === 3 && x.inscriptionId).slice(0, 8);
  let paiement = null;
  let inscription = null;
  for (const x of eleves) {
    const s = await appel(intendant, 'GET', `/inscriptions/${x.inscriptionId}/scolarite`);
    if (s.ok && s.donnees.paiements?.length) {
      paiement = s.donnees.paiements[0];
      inscription = x.inscriptionId;
      break;
    }
  }
  if (!paiement) ignorer('aucun paiement trouvé parmi les premiers élèves de l’établissement 3');
  const a1 = ctx.ecole(1).session('ADMIN_ECOLE');
  refuse(await appel(a1, 'GET', `/inscriptions/${inscription}/scolarite`), 'Situation financière d’un autre établissement');
  refuse(await appel(a1, 'GET', `/paiements/${paiement.id}/recu`), 'Reçu d’un autre établissement');
  refuse(await appel(ctx.ecole(1).session('INTENDANT'), 'POST', `/paiements/${paiement.id}/annulation`, { motif: 'Recette : isolation' }),
    'Annulation d’un paiement d’un autre établissement');
  // Le paiement est intact
  const apres = await exiger(intendant, 'GET', `/inscriptions/${inscription}/scolarite`);
  const p = apres.paiements.find((x) => x.id === paiement.id);
  egal(p?.annule, paiement.annule, 'état du paiement après la tentative');
});

scenario('ISO-03', D_ISO, 'La liste des membres et des comptes ne montre que l’établissement actif', async (ctx) => {
  const e1 = ctx.ecole(1);
  const e3 = ctx.ecole(3);
  const a1 = e1.session('ADMIN_ECOLE');
  const interdits = [e3.admin, ...Object.values(e3.personnel ?? {})];
  const membres = await appel(a1, 'GET', '/membres');
  statut(membres, 200);
  sansTexte(membres, interdits, 'Membre d’un autre établissement');
  const comptes = await appel(a1, 'GET', '/comptes');
  statut(comptes, 200);
  sansTexte(comptes, interdits, 'Compte d’un autre établissement');
  vrai(compteParTelephone(comptes.donnees, e1.admin), 'l’administrateur ne se voit pas dans ses comptes');
  // Action sur un compte de l'autre établissement : introuvable
  const c3 = compteParTelephone(await exiger(e3.session('ADMIN_ECOLE'), 'GET', '/comptes'), e3.personnel.SECRETARIAT);
  if (c3) refuse(await appel(a1, 'POST', `/comptes/${c3.utilisateurId}/deverrouillage`), 'Déverrouillage d’un compte d’un autre établissement');
});

scenario('ISO-04', D_ISO, 'L’établissement du jeton fait foi : un en-tête X-Tenant-Id ajouté par le client est ignoré', async (ctx) => {
  const e3 = ctx.ecole(3);
  const jeton = await ctx.ecole(1).session('ADMIN_ECOLE').jeton();
  const r = await fetch(`${API}/classes/${e3.classes[0].id}`, {
    headers: { Accept: 'application/json', Authorization: `Bearer ${jeton}`, 'X-Tenant-Id': e3.id },
  });
  vrai([401, 403, 404].includes(r.status), `GET /classes/… avec X-Tenant-Id d’un autre établissement → ${r.status}`);
});

scenario('ISO-05', D_ISO, 'Un enseignant consulte les notes et les appels de ses classes, pas ceux d’une classe où il n’enseigne pas', async (ctx) => {
  const e = ctx.ecole(1);
  const p = enseignantDeReference(ctx, e);
  if (!p) ignorer('aucun enseignant avec une classe où il n’enseigne pas');
  const s = ctx.compte(p.telephone, e.id, `enseignant ${p.matiere}`);
  const periodeId = e.periodes[0]?.id;
  if (!periodeId) ignorer('périodes absentes');
  await exiger(s, 'GET', `/classes/${p.sienne.id}/evaluations`, undefined, { params: { periodeId } });
  await exiger(s, 'GET', `/classes/${p.sienne.id}/appels`, undefined, { params: { date: ctx.aujourdhui } });
  statut(await appel(s, 'GET', `/classes/${p.autre.id}/evaluations`, undefined, { params: { periodeId } }), 403);
  statut(await appel(s, 'GET', `/classes/${p.autre.id}/appels`, undefined, { params: { date: ctx.aujourdhui } }), 403);
  statut(await appel(s, 'GET', `/classes/${p.autre.id}/resultats`, undefined, { params: { periodeId } }), [400, 403]);
  // Saisie dans une matière d'une autre classe : refusée
  const m = p.autre.matieres.find((x) => x.type !== 'MODULE') ?? p.autre.matieres[0];
  if (m) {
    const r = await appel(s, 'POST', `/classes/${p.autre.id}/evaluations`, {
      matiereId: m.id, periodeId, libelle: 'Recette isolation', type: 'DEVOIR', date: ctx.aujourdhui,
    });
    vrai([403, 409].includes(r.statut), `évaluation dans une classe où il n’enseigne pas : ${r.resume()}`);
  }
  return `${p.matiere} : ${p.sienne.code} oui, ${p.autre.code} non`;
});

scenario('ISO-06', D_ISO, 'Un parent ne voit que ses propres enfants', async (ctx) => {
  const e = ctx.ecole(1);
  const f = e.famille(0);
  const s = e.parent(0);
  if (!s) ignorer('aucune famille locale dans l’établissement 1');
  const enfants = await exiger(s, 'GET', '/espace-parent/enfants');
  const attendus = f.enfants.filter((x) => x.ecole === 1).map((x) => x.eleveId).sort();
  egal(enfants.map((x) => x.eleveId).sort(), attendus, 'enfants du parent');
  const autres = e.familles.filter((x) => x !== f).flatMap((x) => x.enfants).map((x) => x.eleveId);
  vrai(!enfants.some((x) => autres.includes(x.eleveId)), 'un enfant d’une autre famille apparaît');
  await exiger(s, 'GET', `/espace-parent/enfants/${attendus[0]}/absences`);
  return `${enfants.length} enfant(s)`;
});

scenario('ISO-07', D_ISO, 'Un parent n’accède ni aux absences, ni à la scolarité, ni aux bulletins de l’enfant d’une autre famille', async (ctx) => {
  const e = ctx.ecole(1);
  const s = e.parent(0);
  const autre = e.famille(1)?.enfants.find((x) => x.ecole === 1);
  if (!s || !autre) ignorer('deux familles locales nécessaires');
  for (const suffixeChemin of ['absences', 'scolarite', 'bulletins', 'vie-scolaire']) {
    refuse(await appel(s, 'GET', `/espace-parent/enfants/${autre.eleveId}/${suffixeChemin}`), `${suffixeChemin} d’un autre enfant`);
  }
  // Enfant d'un autre établissement
  const ailleurs = premierEleve(ctx.ecole(3));
  if (ailleurs) {
    refuse(await appel(s, 'GET', `/espace-parent/enfants/${ailleurs.eleveId}/absences`), 'Enfant d’un autre établissement');
  }
  // Les écrans du personnel lui sont fermés
  statut(await appel(s, 'GET', `/eleves/${autre.eleveId}`), 403);
  statut(await appel(s, 'GET', `/inscriptions/${autre.inscriptionId}/scolarite`), 403);
});

scenario('ISO-08', D_ISO, 'Le parent présent dans deux établissements ne voit que l’enfant de l’établissement choisi', async (ctx) => {
  const f = familleCommune(ctx);
  if (!f) ignorer('famille commune absente du peuplement');
  const e1 = ctx.ecole(1);
  const e3 = ctx.ecole(3);
  const enfant1 = f.enfants.find((x) => x.ecole === 1);
  const enfant3 = f.enfants.find((x) => x.ecole === 3);
  const s3 = await ouvrir(f.telephone, ctx.motDePasse, e3.id);
  try {
    if (s3.donnees.etablissementActif?.id !== e3.id) ignorer('le parent commun n’a pas d’espace dans l’établissement 3');
    const l3 = await exiger(s3.jeton, 'GET', '/espace-parent/enfants');
    vrai(l3.some((x) => x.eleveId === enfant3.eleveId), 'l’enfant de l’établissement 3 est absent');
    vrai(!l3.some((x) => x.eleveId === enfant1.eleveId), 'l’enfant de l’établissement 1 apparaît dans l’établissement 3');
    refuse(await appel(s3.jeton, 'GET', `/espace-parent/enfants/${enfant1.eleveId}/absences`), 'Absences de l’enfant de l’autre établissement');
  } finally {
    await fermer(s3.cookie);
  }
  const s1 = await ouvrir(f.telephone, ctx.motDePasse, e1.id);
  try {
    if (s1.donnees.etablissementActif?.id !== e1.id) ignorer('le parent commun n’a pas d’espace dans l’établissement 1');
    const l1 = await exiger(s1.jeton, 'GET', '/espace-parent/enfants');
    vrai(l1.some((x) => x.eleveId === enfant1.eleveId), 'l’enfant de l’établissement 1 est absent');
    vrai(!l1.some((x) => x.eleveId === enfant3.eleveId), 'l’enfant de l’établissement 3 apparaît dans l’établissement 1');
  } finally {
    await fermer(s1.cookie);
  }
});

scenario('ISO-09', D_ISO, 'L’enseignant partagé ne voit les classes du second établissement qu’après avoir changé d’établissement', async (ctx) => {
  const p = enseignantPartage(ctx);
  if (!p) ignorer('aucun enseignant partagé');
  const e1 = ctx.ecole(1);
  const e3 = ctx.ecole(3);
  const classe3 = e3.classes.find((c) => c.matieres.some((m) => m.enseignant === p.telephone));
  const periodeId = e3.periodes[0]?.id;
  if (!classe3 || !periodeId) ignorer('classe de l’enseignant partagé introuvable');
  const s1 = ctx.compte(p.telephone, e1.id, 'enseignant partagé (établissement 1)');
  const s3 = ctx.compte(p.telephone, e3.id, 'enseignant partagé (établissement 3)');
  refuse(await appel(s1, 'GET', `/classes/${classe3.id}/evaluations`, undefined, { params: { periodeId } }),
    'Classe du second établissement avec le jeton du premier');
  await exiger(s3, 'GET', `/classes/${classe3.id}/evaluations`, undefined, { params: { periodeId } });
  const moi = await exiger(s3, 'GET', '/moi');
  egal(moi.etablissementId, e3.id, 'établissement de la session');
});

// ===================================================================== ROL : matrice des droits

scenario('ROL-01', D_ROL, 'Journal de caisse : l’intendant et l’administrateur seulement', async (ctx) =>
  matrice(ctx, {
    autorises: ['ADMIN_ECOLE', 'INTENDANT'],
    requete: (s) => appel(s, 'GET', '/paiements', undefined, { params: { du: ctx.etat.annee.rentree, au: ctx.aujourdhui } }),
  }));

scenario('ROL-02', D_ROL, 'Paramètres de scolarité en écriture : l’administrateur seulement (réécrits à l’identique)', async (ctx) => {
  const p = await exiger(ctx.ecole(1).session('ADMIN_ECOLE'), 'GET', '/parametres/scolarite');
  const corps = {
    tauxBoursier: p.tauxBoursier,
    tauxSemiBoursier: p.tauxSemiBoursier,
    delaiRelanceJours: p.delaiRelanceJours,
    relancesAutomatiques: p.relancesAutomatiques,
  };
  const note = await matrice(ctx, { autorises: ['ADMIN_ECOLE'], requete: (s) => appel(s, 'PUT', '/parametres/scolarite', corps) });
  const apres = await exiger(ctx.ecole(1).session('ADMIN_ECOLE'), 'GET', '/parametres/scolarite');
  egal(apres.delaiRelanceJours, p.delaiRelanceJours, 'Délai de relance inchangé');
  egal(apres.relancesAutomatiques, p.relancesAutomatiques, 'Relances automatiques inchangées');
  return note;
});

scenario('ROL-03', D_ROL, 'Liste des membres : l’administrateur seulement', async (ctx) =>
  matrice(ctx, { autorises: ['ADMIN_ECOLE'], requete: (s) => appel(s, 'GET', '/membres') }));

scenario('ROL-04', D_ROL, 'Contrôle des comptes : l’administrateur seulement', async (ctx) =>
  matrice(ctx, { autorises: ['ADMIN_ECOLE'], requete: (s) => appel(s, 'GET', '/comptes') }));

scenario('ROL-05', D_ROL, 'Statistiques de l’année : administrateur, censeur, secrétariat, intendant', async (ctx) =>
  matrice(ctx, {
    autorises: ['ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT', 'INTENDANT'],
    requete: (s) => appel(s, 'GET', `/annees/${ctx.ecole(1).anneeId}/statistiques`),
  }));

scenario('ROL-06', D_ROL, 'Dossier d’un élève : personnel administratif, vie scolaire et chef des travaux (v0.47) ; ni enseignant, ni parent', async (ctx) => {
  const eleve = premierEleve(ctx.ecole(1));
  if (!eleve) ignorer('aucun élève');
  return matrice(ctx, {
    autorises: ['ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'SECRETARIAT', 'INTENDANT', 'SURVEILLANT'],
    requete: (s) => appel(s, 'GET', `/eleves/${eleve.eleveId}`),
  });
});

scenario('ROL-07', D_ROL, 'Création d’une évaluation : enseignant, censeur, administrateur (période fictive, rien n’est créé)', async (ctx) => {
  const e = ctx.ecole(1);
  const ref = enseignantDeReference(ctx, e);
  const classe = ref?.sienne ?? e.classes[0];
  const matiere = classe.matieres.find((m) => m.enseignant === ref?.telephone) ?? classe.matieres[0];
  return matrice(ctx, {
    autorises: ['ADMIN_ECOLE', 'CENSEUR', 'ENSEIGNANT'],
    ecriture: true,
    requete: (s) => appel(s, 'POST', `/classes/${classe.id}/evaluations`, {
      matiereId: matiere.id, periodeId: randomUUID(), libelle: 'Recette droits', type: 'DEVOIR', date: ctx.aujourdhui,
    }),
  });
});

scenario('ROL-08', D_ROL, 'Appels d’une classe : enseignant de la classe, vie scolaire, administration et chef des travaux (v0.47, en lecture) ; pas le parent', async (ctx) => {
  const e = ctx.ecole(1);
  const ref = enseignantDeReference(ctx, e);
  if (!ref) ignorer('enseignant de référence absent');
  return matrice(ctx, {
    autorises: ['ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'SECRETARIAT', 'INTENDANT', 'SURVEILLANT', 'ENSEIGNANT'],
    requete: (s) => appel(s, 'GET', `/classes/${ref.sienne.id}/appels`, undefined, { params: { date: ctx.aujourdhui } }),
  });
});

scenario('ROL-09', D_ROL, 'Publication des bulletins : censeur et administrateur (période fictive, rien n’est publié)', async (ctx) =>
  matrice(ctx, {
    autorises: ['ADMIN_ECOLE', 'CENSEUR'],
    ecriture: true,
    requete: (s) => appel(s, 'POST', `/classes/${ctx.ecole(1).classes[0].id}/periodes/${randomUUID()}/bulletins/publication`),
  }));

scenario('ROL-10', D_ROL, 'Administration de la plateforme : super administrateur seulement, aucun rôle d’établissement', async (ctx) =>
  matrice(ctx, {
    autorises: ['SUPER_ADMIN'],
    roles: ['SUPER_ADMIN', ...ROLES],
    requete: (s) => appel(s, 'GET', '/plateforme/etablissements'),
  }));

scenario('ROL-11', D_ROL, 'Journal d’audit : l’administrateur seulement', async (ctx) =>
  matrice(ctx, { autorises: ['ADMIN_ECOLE'], requete: (s) => appel(s, 'GET', '/audit', undefined, { params: { taille: 1 } }) }));

scenario('ROL-12', D_ROL, 'Exports complets des données : l’administrateur seulement', async (ctx) =>
  matrice(ctx, { autorises: ['ADMIN_ECOLE'], requete: (s) => appel(s, 'GET', '/exports') }));

scenario('ROL-13', D_ROL, 'Saisie de la vie scolaire (incident) : le CPE seul (v0.47) ; inscription fictive, rien n’est créé', async (ctx) =>
  matrice(ctx, {
    autorises: ['SURVEILLANT'],
    ecriture: true,
    requete: (s) => appel(s, 'POST', `/inscriptions/${randomUUID()}/incidents`, { type: 'AVERTISSEMENT', motif: 'Recette droits' }),
  }));

scenario('ROL-14', D_ROL, 'Saisie des inscriptions (modèle d’import des élèves) : l’intendance et le chef d’établissement (v0.47)', async (ctx) =>
  matrice(ctx, {
    autorises: ['ADMIN_ECOLE', 'INTENDANT'],
    requete: (s) => appel(s, 'GET', `/annees/${ctx.ecole(1).anneeId}/eleves/import/modele`, undefined, { fichier: true }),
  }));

scenario('ROL-15', D_ROL, 'Répartition dans une classe : le censeur et le chef d’établissement (v0.47) ; inscription fictive', async (ctx) =>
  matrice(ctx, {
    autorises: ['ADMIN_ECOLE', 'CENSEUR'],
    ecriture: true,
    requete: (s) => appel(s, 'PATCH', `/inscriptions/${randomUUID()}/classe`, { classeId: ctx.ecole(1).classes[0].id }),
  }));

// ===================================================================== MOD : modules

/** Modules de lp-horizon vus par le super administrateur : { liste, actifs }. */
async function lireModules(ctx, e) {
  const liste = await exiger(ctx.sa, 'GET', `/plateforme/etablissements/${e.id}/modules`);
  return { liste, actifs: liste.filter((m) => m.actif).map((m) => m.code).sort() };
}

async function retablirModules(ctx, e, actifs) {
  const r = await appel(ctx.sa, 'PUT', `/plateforme/etablissements/${e.id}/modules`, { actifs });
  if (!r.ok) throw new Echec(`RÉTABLISSEMENT DES MODULES DE ${e.code} IMPOSSIBLE (${actifs.join(', ')}) : ${r.resume()}`);
  const apres = (await lireModules(ctx, e)).actifs;
  egal(apres, [...actifs].sort(), `Modules de ${e.code} après rétablissement`);
}

scenario('MOD-01', D_MOD, 'Le module Ateliers fermé au lycée Horizon refuse ses chemins ; seul le super administrateur gère les modules', async (ctx) => {
  const e = ctx.ecole(3);
  const { liste } = await lireModules(ctx, e);
  vrai(liste.length >= 7 && liste.every((m) => m.code && m.libelle && 'actif' in m), 'liste des modules incomplète');
  egal(liste.find((m) => m.code === 'MOBILE_MONEY')?.requis, 'SCOLARITE', 'Dépendance de Mobile Money');
  const admin = e.session('ADMIN_ECOLE');
  statut(await appel(admin, 'GET', `/plateforme/etablissements/${e.id}/modules`), 403);
  const ateliers = liste.find((m) => m.code === 'ATELIERS');
  if (ateliers?.actif) return 'module Ateliers actif au lycée Horizon : refus non vérifiable';
  const r = await appel(admin, 'GET', '/ateliers');
  statut(r, 403, 'MODULE_DESACTIVE');
  egal(r.donnees.module, 'ATELIERS', 'module signalé');
  // Le même chemin reste ouvert dans un établissement qui a des ateliers
  statut(await appel(ctx.ecole(1).session('ADMIN_ECOLE'), 'GET', '/ateliers'), 200);
});

scenario('MOD-02', D_MOD, 'Vie scolaire désactivée : ses chemins sont refusés, les autres modules et les autres établissements continuent ; puis réactivée', async (ctx) => {
  const e = ctx.ecole(3);
  const { actifs } = await lireModules(ctx, e);
  if (!actifs.includes('VIE_SCOLAIRE')) ignorer('module Vie scolaire déjà désactivé au lycée Horizon');
  const admin = e.session('ADMIN_ECOLE');
  const classe = e.classes[0];
  const periode = { du: ctx.etat.annee.rentree, au: ctx.aujourdhui };
  const eleve = premierEleve(e);
  await exiger(admin, 'GET', `/classes/${classe.id}/vie-scolaire`, undefined, { params: periode });
  try {
    const vue = await exiger(ctx.sa, 'PUT', `/plateforme/etablissements/${e.id}/modules`, { actifs: actifs.filter((m) => m !== 'VIE_SCOLAIRE') });
    egal(vue.find((m) => m.code === 'VIE_SCOLAIRE')?.actif, false, 'Vie scolaire dans la réponse');

    const r = await appel(admin, 'GET', `/classes/${classe.id}/vie-scolaire`, undefined, { params: periode });
    statut(r, 403, 'MODULE_DESACTIVE');
    egal(r.donnees.module, 'VIE_SCOLAIRE', 'module signalé');
    if (eleve) statut(await appel(admin, 'GET', `/inscriptions/${eleve.inscriptionId}/vie-scolaire`), 403, 'MODULE_DESACTIVE');
    const parent = e.parent(0);
    const enfant = e.famille(0)?.enfants.find((x) => x.ecole === 3);
    if (parent && enfant) statut(await appel(parent, 'GET', `/espace-parent/enfants/${enfant.eleveId}/vie-scolaire`), 403, 'MODULE_DESACTIVE');

    // Autres modules et socle : ouverts
    if (eleve) await exiger(admin, 'GET', `/inscriptions/${eleve.inscriptionId}/scolarite`);
    await exiger(admin, 'GET', `/classes/${classe.id}`);
    // Autre établissement : non concerné
    const e1 = ctx.ecole(1);
    await exiger(e1.session('ADMIN_ECOLE'), 'GET', `/classes/${e1.classes[0].id}/vie-scolaire`, undefined, { params: periode });
    // La connexion annonce le module désactivé à l'application
    const s = await ouvrir(e.admin, ctx.motDePasse, e.id);
    await fermer(s.cookie);
    vrai(s.donnees.etablissementActif?.modulesDesactives?.includes('VIE_SCOLAIRE'),
      `modulesDesactives à la connexion : ${JSON.stringify(s.donnees.etablissementActif?.modulesDesactives)}`);
  } finally {
    await retablirModules(ctx, e, actifs);
  }
  await exiger(admin, 'GET', `/classes/${classe.id}/vie-scolaire`, undefined, { params: periode });
});

scenario('MOD-03', D_MOD, 'Désactiver la scolarité désactive aussi Mobile Money ; la liste d’origine est ensuite rétablie à l’identique', async (ctx) => {
  const e = ctx.ecole(3);
  const { actifs } = await lireModules(ctx, e);
  if (!actifs.includes('SCOLARITE')) ignorer('module Scolarité déjà désactivé au lycée Horizon');
  const admin = e.session('ADMIN_ECOLE');
  try {
    // Mobile Money reste demandé actif : la règle de dépendance doit le fermer quand même
    const vue = await exiger(ctx.sa, 'PUT', `/plateforme/etablissements/${e.id}/modules`, { actifs: actifs.filter((m) => m !== 'SCOLARITE') });
    egal(vue.find((m) => m.code === 'SCOLARITE')?.actif, false, 'Scolarité');
    egal(vue.find((m) => m.code === 'MOBILE_MONEY')?.actif, false, 'Mobile Money (dépend de la scolarité)');
    const r1 = await appel(admin, 'GET', '/parametres/scolarite');
    statut(r1, 403, 'MODULE_DESACTIVE');
    egal(r1.donnees.module, 'SCOLARITE', 'module signalé');
    const r2 = await appel(admin, 'GET', '/mobile-money/transactions/a-verifier');
    statut(r2, 403, 'MODULE_DESACTIVE');
    egal(r2.donnees.module, 'MOBILE_MONEY', 'module signalé');
    await exiger(admin, 'GET', '/membres');
  } finally {
    await retablirModules(ctx, e, actifs);
  }
  await exiger(admin, 'GET', '/parametres/scolarite');
  if (actifs.includes('MOBILE_MONEY')) await exiger(admin, 'GET', '/mobile-money/transactions/a-verifier');
  return `rétabli : ${actifs.join(', ')}`;
});
