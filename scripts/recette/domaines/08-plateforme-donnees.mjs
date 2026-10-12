/**
 * Lot 08 : plateforme et données — établissements gérés par le super administrateur, identité et logo,
 * réversibilité (exports complets), statistiques officielles, mesure de l'adoption (v0.34, regroupement par
 * territoire v0.39) et espaces des comptes d'administration qui sont aussi parents (v0.41).
 *
 * Préfixes : PLA- (établissements de la plateforme), DON- (identité, exports, statistiques), ADO- (adoption),
 * ESP- (choix d'espace à la connexion, v0.41).
 *
 * Sources vérifiées : plateforme/EtablissementsController/Service, StatutTenant ; V2__socle.sql
 * (auth_etablissements_utilisateur : seuls les établissements ACTIF ouvrent une session) ;
 * etablissement/IdentiteController/Service ; reversibilite/ExportsController, ExportsPlateformeController,
 * TelechargementController, ExportsService (StatutExport, Lien) ; statistiques/StatistiquesController/Service ;
 * adoption/AdoptionController, AdoptionPlateformeController, AdoptionTerritoireController, AdoptionService ;
 * utilisateurs/AuthController, AuthDtos (administration), AuthService, AdministrateursPaysController/Service,
 * ComptesService, MembresService, MoiController ; socle/securite/SecuriteConfig.
 *
 * Données : un « collège de recette » est créé à chaque passage par le super administrateur ; il sert ici puis
 * au lot 09 (année complète) qui le résilie à la fin. Les espaces v0.41 utilisent un élève de test du lycée
 * Horizon et un administrateur pays nommé puis retiré. Les logos ne sont modifiés que sur le collège de recette.
 */
import {
  appel, appelBrut, attendreQue, cookieDe, Echec, egal, exiger, fichierPng, ignorer, refuse, scenario, statut, suffixe,
  telephoneUnique, vrai,
} from '../lib.mjs';

const D_PLA = 'Établissements de la plateforme';
const D_DON = 'Identité, exports et statistiques';
const D_ADO = 'Mesure de l’adoption';
const D_ESP = 'Espaces des comptes d’administration (v0.41)';

// ===================================================================== aides

function etat(ctx) {
  ctx.partage.plateforme ??= {};
  return ctx.partage.plateforme;
}

/** Collège de recette créé par PLA-01 (partagé avec le lot 09 sous ctx.partage.ecoleRecette). */
function college(ctx) {
  const c = ctx.partage.ecoleRecette;
  if (!c?.admin) ignorer(`Collège de recette non créé (PLA-01)${etat(ctx).echec ? ` : ${etat(ctx).echec}` : ''}`);
  return c;
}

function role(e, r) {
  const s = e.session(r);
  if (!s) ignorer(`Pas de compte ${r} au ${e.nom}`);
  return s;
}

function lettres(texte) {
  return texte.replace(/[0-9]/g, (d) => 'ABCDEFGHIJ'[Number(d)]);
}

async function connexion(telephone, motDePasse) {
  return appelBrut('POST', '/auth/connexion', { telephone, motDePasse });
}

// ===================================================================== établissements de la plateforme

scenario('PLA-01', D_PLA, 'Le super administrateur crée un collège : son administrateur reçoit un mot de passe provisoire affiché une seule fois', async (ctx) => {
  const s = etat(ctx);
  try {
    const suf = suffixe();
    const code = `recette-${suf}`;
    const telephone = telephoneUnique('7');
    const corps = { code, nom: `Collège de recette ${suf.toUpperCase()}`, telephoneAdministrateur: telephone, nomAdministrateur: 'Recette', prenomsAdministrateur: 'Directeur' };
    statut(await appel(ctx.sa, 'POST', '/plateforme/etablissements', { ...corps, code: '-mauvais code-' }), 400);
    statut(await appel(ctx.sa, 'POST', '/plateforme/etablissements', { ...corps, code: 'lt-batisseurs' }), 409, 'CODE_EXISTANT');
    statut(await appel(ctx.sa, 'POST', '/plateforme/etablissements', { ...corps, telephoneAdministrateur: '' }), 400);
    statut(await appel(ctx.ecole(1).session('ADMIN_ECOLE'), 'POST', '/plateforme/etablissements', corps), 403);
    const r = await exiger(ctx.sa, 'POST', '/plateforme/etablissements', { ...corps, code: code.toUpperCase() });
    egal([r.etablissement.code, r.etablissement.statut], [code, 'ACTIF'], 'Code en minuscules, établissement actif');
    vrai(r.motDePasseTemporaire, 'Mot de passe provisoire de l’administrateur');
    const admin = await ctx.activer(telephone, r.motDePasseTemporaire, r.etablissement.id);
    const moi = await exiger(admin, 'GET', '/moi');
    egal([moi.roles, moi.superAdmin], [['ADMIN_ECOLE'], false], 'Rôles de l’administrateur');
    ctx.partage.ecoleRecette = { id: r.etablissement.id, code, nom: corps.nom, telephone, admin, utilisateurId: r.administrateur.utilisateurId };
    return code;
  } catch (err) {
    if (err instanceof Echec) s.echec = err.message;
    throw err;
  }
});

scenario('PLA-02', D_PLA, 'Le nouvel établissement est vide et isolé : aucune année, aucun élève, rien des autres établissements', async (ctx) => {
  const c = college(ctx);
  egal(await exiger(c.admin, 'GET', '/annees'), [], 'Aucune année');
  statut(await appel(c.admin, 'GET', '/annees/active'), 404);
  egal((await exiger(c.admin, 'GET', '/eleves')).total, 0, 'Aucun élève');
  egal((await exiger(c.admin, 'GET', '/membres')).length, 1, 'L’administrateur seul');
  refuse(await appel(c.admin, 'GET', `/annees/${ctx.ecole(1).anneeId}/classes`), 'Classes d’un autre établissement');
  const liste = await exiger(ctx.sa, 'GET', '/plateforme/etablissements');
  vrai(liste.some((x) => x.id === c.id && x.statut === 'ACTIF'), 'Le collège figure dans la liste du super administrateur');
});

scenario('PLA-03', D_PLA, 'Le super administrateur ferme les ateliers du collège (enseignement général) : chemins refusés, puis les autres modules restent ouverts', async (ctx) => {
  const c = college(ctx);
  const liste = await exiger(ctx.sa, 'GET', `/plateforme/etablissements/${c.id}/modules`);
  vrai(liste.every((m) => m.actif), 'Tous les modules ouverts à la création');
  const actifs = liste.map((m) => m.code).filter((m) => m !== 'ATELIERS');
  const apres = await exiger(ctx.sa, 'PUT', `/plateforme/etablissements/${c.id}/modules`, { actifs });
  egal(apres.find((m) => m.code === 'ATELIERS')?.actif, false, 'Ateliers fermés');
  const r = await appel(c.admin, 'GET', '/ateliers');
  statut(r, 403, 'MODULE_DESACTIVE');
  statut(await appel(c.admin, 'PUT', `/plateforme/etablissements/${c.id}/modules`, { actifs: liste.map((m) => m.code) }), 403);
  statut(await appel(ctx.sa, 'PUT', `/plateforme/etablissements/${c.id}/modules`, {}), 400);
});

scenario('PLA-04', D_PLA, 'Administrateur dépanné : nouveau mot de passe provisoire, ses sessions fermées ; un compte qui n’est pas administrateur ne se dépanne pas ici', async (ctx) => {
  const c = college(ctx);
  const admins = await exiger(ctx.sa, 'GET', `/plateforme/etablissements/${c.id}/administrateurs`);
  const lui = admins.find((a) => a.utilisateurId === c.utilisateurId);
  vrai(lui, 'Administrateur listé pour le dépannage');
  statut(await appel(c.admin, 'GET', `/plateforme/etablissements/${c.id}/administrateurs`), 403);
  refuse(await appel(ctx.sa, 'POST', `/plateforme/etablissements/${c.id}/administrateurs/${ctx.ecole(1).id}/reinitialisation`), 'Compte inconnu');
  const r = await exiger(ctx.sa, 'POST', `/plateforme/etablissements/${c.id}/administrateurs/${c.utilisateurId}/reinitialisation`);
  vrai(r.motDePasseTemporaire, 'Nouveau mot de passe provisoire');
  statut((await connexion(c.telephone, ctx.motDePasse)), 401);
  c.admin = await ctx.activer(c.telephone, r.motDePasseTemporaire, c.id);
  await exiger(c.admin, 'GET', '/moi');
});

scenario('PLA-05', D_PLA, 'Un établissement suspendu ne s’ouvre plus à ses membres ; réactivé, tout revient ; seul le super administrateur change le statut', async (ctx) => {
  const c = college(ctx);
  statut(await appel(c.admin, 'PATCH', `/plateforme/etablissements/${c.id}/statut`, { statut: 'SUSPENDU' }), 403);
  try {
    egal((await exiger(ctx.sa, 'PATCH', `/plateforme/etablissements/${c.id}/statut`, { statut: 'SUSPENDU' })).statut, 'SUSPENDU', 'Suspendu');
    statut(await connexion(c.telephone, ctx.motDePasse), 401);
    statut(await appel(ctx.sa, 'PATCH', `/plateforme/etablissements/${c.id}/statut`, { statut: 'EN_PAUSE' }), 400);
  } finally {
    await appel(ctx.sa, 'PATCH', `/plateforme/etablissements/${c.id}/statut`, { statut: 'ACTIF' });
  }
  statut(await connexion(c.telephone, ctx.motDePasse), 200);
  c.admin.oublier();
  await exiger(c.admin, 'GET', '/moi');
});

// ===================================================================== identité, exports, statistiques

scenario('DON-01', D_DON, 'Identité du collège : l’administrateur dépose un logo PNG, le relit, prévisualise l’en-tête puis le retire ; un fichier non image est refusé', async (ctx) => {
  const c = college(ctx);
  const id = await exiger(c.admin, 'GET', '/identite');
  egal(id.nom, c.nom, 'Nom de l’établissement');
  egal(id.logo, null, 'Pas encore de logo');
  const f = new FormData();
  f.append('fichier', fichierPng(), 'logo.png');
  const avec = await exiger(c.admin, 'POST', '/identite/logo', f);
  vrai(avec.logo?.type?.includes('png'), 'Logo PNG enregistré');
  const logo = await exiger(c.admin, 'GET', '/identite/logo', undefined, { fichier: true });
  vrai(logo.octets.length > 0, 'Logo relu');
  const pdf = await exiger(c.admin, 'GET', '/identite/apercu', undefined, { fichier: true });
  vrai(pdf.octets.subarray(0, 4).toString() === '%PDF', 'Aperçu de l’en-tête officiel');
  const texte = new FormData();
  texte.append('fichier', new Blob(['pas une image'], { type: 'text/plain' }), 'logo.txt');
  statut(await appel(c.admin, 'POST', '/identite/logo', texte), 400);
  egal((await exiger(c.admin, 'DELETE', '/identite/logo')).logo, null, 'Logo retiré');
  statut(await appel(role(ctx.ecole(1), 'CENSEUR'), 'GET', '/identite'), 403);
});

scenario('DON-02', D_DON, 'Export complet des données : mot de passe redemandé, archive préparée puis téléchargée par un lien signé ; un lien altéré est refusé', async (ctx) => {
  const c = college(ctx);
  statut(await appel(c.admin, 'POST', '/exports', { motDePasse: 'mauvais-mot-de-passe' }), [401, 403]);
  statut(await appel(c.admin, 'POST', '/exports', {}), 400);
  const demande = await appel(c.admin, 'POST', '/exports', { motDePasse: ctx.motDePasse });
  if (demande.code === 'EXPORTS_TROP_NOMBREUX') ignorer('Limite quotidienne d’exports atteinte');
  statut(demande, 202);
  const pret = await attendreQue(async () => {
    const liste = await exiger(c.admin, 'GET', '/exports');
    const e = liste.find((x) => x.id === demande.donnees.id);
    if (e?.statut === 'ECHEC') throw new Echec(`Export en échec : ${e.erreur}`);
    return e?.statut === 'PRET' ? e : null;
  }, { delai: 120_000, pas: 2000, message: 'Export non prêt' });
  vrai(pret.taille > 0 && pret.empreinte && pret.nombreTables > 0, 'Taille, empreinte et nombre de tables');
  const lien = await exiger(c.admin, 'POST', `/exports/${pret.id}/lien`);
  vrai(lien.chemin.startsWith('/telechargements/exports/'), `Chemin du lien : ${lien.chemin}`);
  const zip = await appelBrut('GET', lien.chemin, undefined, { fichier: true });
  statut(zip, 200);
  vrai(zip.octets.subarray(0, 2).toString() === 'PK', 'Archive ZIP');
  const altere = lien.chemin.replace(/jeton=([^&]+)/, (m, j) => `jeton=${j.slice(0, -2)}xx`);
  statut(await appelBrut('GET', altere, undefined, { fichier: true }), 403);
  statut(await appelBrut('GET', lien.chemin.replace(/\?.*$/, ''), undefined, { fichier: true }), [400, 403]);
  return `${pret.nombreTables} tables, ${pret.nombreLignes} lignes, ${Math.round(pret.taille / 1024)} Ko`;
});

scenario('DON-03', D_DON, 'Le super administrateur exporte les données d’un établissement (avec son propre mot de passe) ; un administrateur d’établissement ne le peut pas', async (ctx) => {
  const c = college(ctx);
  statut(await appel(c.admin, 'GET', `/plateforme/etablissements/${c.id}/exports`), 403);
  const r = await appel(ctx.sa, 'POST', `/plateforme/etablissements/${c.id}/exports`, { motDePasse: ctx.etat.superAdmin.motDePasse });
  if (r.code === 'EXPORT_EN_COURS' || r.code === 'EXPORTS_TROP_NOMBREUX') ignorer(`Export non relancé : ${r.code}`);
  statut(r, 202);
  egal(r.donnees.parPlateforme, true, 'Export demandé par la plateforme');
  const liste = await exiger(ctx.sa, 'GET', `/plateforme/etablissements/${c.id}/exports`);
  vrai(liste.some((x) => x.id === r.donnees.id), 'Export listé côté plateforme');
  const vuParEcole = await exiger(c.admin, 'GET', '/exports');
  vrai(vuParEcole.some((x) => x.id === r.donnees.id && x.parPlateforme), 'L’établissement voit l’export fait par la plateforme');
});

scenario('DON-04', D_DON, 'Les statistiques officielles comptent exactement les élèves inscrits dans chaque classe et se téléchargent en Excel', async (ctx) => {
  const e = ctx.ecole(3);
  const adm = role(e, 'ADMIN_ECOLE');
  const r = await exiger(role(e, 'SECRETARIAT'), 'GET', `/annees/${e.anneeId}/statistiques`);
  const classes = await exiger(adm, 'GET', `/annees/${e.anneeId}/classes`);
  let total = 0;
  for (const c of classes) total += (await exiger(adm, 'GET', `/classes/${c.id}/inscriptions`)).length;
  egal(r.classes, classes.length, 'Nombre de classes');
  egal(r.effectifTotal.garcons + r.effectifTotal.filles, total, 'Effectif total');
  const parNiveau = r.effectifs.reduce((s, n) => s + n.effectif.garcons + n.effectif.filles, 0);
  egal(parNiveau, total, 'Somme des effectifs par niveau');
  const xlsx = await exiger(adm, 'GET', `/annees/${e.anneeId}/statistiques/excel`, undefined, { fichier: true });
  vrai(xlsx.octets.subarray(0, 2).toString() === 'PK', 'Classeur Excel');
  statut(await appel(role(e, 'SURVEILLANT'), 'GET', `/annees/${e.anneeId}/statistiques`), 403);
  return `${total} élèves dans ${classes.length} classes`;
});

// ===================================================================== adoption

scenario('ADO-01', D_ADO, 'L’administrateur mesure l’utilisation de son établissement : comptes actifs, enseignants, parents et activité de chacun', async (ctx) => {
  const e = ctx.ecole(1);
  const v = await exiger(role(e, 'ADMIN_ECOLE'), 'GET', '/adoption', undefined, { params: { jours: 7 } });
  vrai(v.comptes > 0 && v.actifs >= 0 && v.actifs <= v.comptes, 'Comptes et comptes actifs cohérents');
  vrai(v.enseignants.total >= e.enseignants.length - 1, 'Enseignants comptés');
  vrai(Array.isArray(v.personnes) && v.personnes.length > 0, 'Activité par personne');
  vrai(v.quotidien.length <= 7, 'Une valeur par jour sur 7 jours au plus');
  statut(await appel(role(e, 'CENSEUR'), 'GET', '/adoption'), 403);
  return `${v.actifs}/${v.comptes} comptes actifs sur 7 jours`;
});

scenario('ADO-02', D_ADO, 'Le super administrateur voit l’adoption de tous les établissements avec leur rattachement territorial (v0.39) et peut la recalculer', async (ctx) => {
  const v = await exiger(ctx.sa, 'GET', '/plateforme/adoption', undefined, { params: { jours: 30 } });
  for (const e of ctx.ecoles) {
    const d = v.details.find((x) => x.id === e.id);
    vrai(d, `${e.nom} absent de l’adoption`);
    if (ctx.partage.territoire?.rattachement?.[e.code]) vrai(d.rattachement && d.directionId, `Rattachement de ${e.code} attendu (lot 02)`);
  }
  vrai(v.etablissements >= ctx.ecoles.length, 'Nombre d’établissements');
  const recalcul = await exiger(ctx.sa, 'POST', '/plateforme/adoption/calcul', undefined, { params: { jours: 7 } });
  vrai(recalcul.calculeLe, 'Date du calcul');
  const t = ctx.partage.territoire;
  if (t?.adminPaysBF) statut(await appel(t.adminPaysBF, 'POST', '/plateforme/adoption/calcul'), 403);
  statut(await appel(ctx.ecole(1).session('ADMIN_ECOLE'), 'GET', '/plateforme/adoption'), 403);
  return `${v.details.length} établissements`;
});

scenario('ADO-03', D_ADO, 'Un compte de direction voit en lecture seule l’adoption de son ressort (v0.39), jamais au-dessus ni à côté', async (ctx) => {
  const t = ctx.partage.territoire;
  const dr = t?.comptesDirection?.['DR-CENTRE'];
  if (!dr) ignorer('Compte de la DR du Centre non créé (lot 02)');
  const v = await exiger(dr, 'GET', '/pilotage/adoption');
  const codes = v.details.map((d) => d.code);
  for (const [code, dp] of Object.entries(t.rattachement ?? {})) {
    const dansLeRessort = ['DP-KADIOGO', 'DP-OUBRITENGA'].includes(Object.keys(t.directions).find((k) => t.directions[k] === dp) ?? dp);
    if (dansLeRessort) vrai(codes.includes(code), `${code} devrait être dans le ressort de la DR du Centre`);
  }
  vrai(!codes.includes('lp-horizon'), 'Le lycée Horizon (Houet) n’est pas dans le ressort');
  if (t.directions['DP-KADIOGO']) {
    const dp = await exiger(dr, 'GET', '/pilotage/adoption', undefined, { params: { direction: t.directions['DP-KADIOGO'] } });
    vrai(dp.details.every((d) => d.directionId === t.directions['DP-KADIOGO']), 'Descente vers la DP du Kadiogo');
  }
  if (t.directions['DP-HOUET']) {
    statut(await appel(dr, 'GET', '/pilotage/adoption', undefined, { params: { direction: t.directions['DP-HOUET'] } }), 403);
  }
  statut(await appel(dr, 'POST', '/plateforme/adoption/calcul'), 403);
  statut(await appel(dr, 'GET', '/plateforme/adoption'), 403);
  return codes.join(', ');
});

// ===================================================================== espaces v0.41

scenario('ESP-01', D_ESP, 'Une mère d’élève du lycée Horizon est nommée administratrice du Burkina Faso : son compte existant est repris, sans nouveau mot de passe', async (ctx) => {
  const e = ctx.ecole(3);
  const sec = role(e, 'SECRETARIAT');
  const suf = lettres(suffixe());
  const telephone = telephoneUnique('7');
  const d = await exiger(sec, 'POST', '/eleves', {
    nom: 'RECETTE', prenoms: `Espace ${suf}`, sexe: 'F', dateNaissance: '2011-03-03',
    responsables: [{ nom: 'RECETTE', prenoms: `Mariam ${suf}`, telephone, lien: 'MERE', responsableLegal: true, contactPrioritaire: true }],
  });
  const ins = await exiger(sec, 'POST', '/inscriptions', { eleveId: d.eleve.id, classeId: e.classes[0].id });
  const ouverture = await exiger(sec, 'POST', `/responsables/${d.responsables[0].responsableId}/espace-parent`);
  await ctx.activer(telephone, ouverture.motDePasseTemporaire, e.id);
  const pays = await exiger(ctx.sa, 'GET', '/plateforme/territoire/pays');
  const bf = pays.find((p) => p.code === 'BF');
  if (!bf) ignorer('Burkina Faso absent du territoire');
  const n = await exiger(ctx.sa, 'POST', `/plateforme/territoire/pays/${bf.id}/administrateurs`, { telephone, nom: 'Recette', prenoms: `Mariam ${suf}` });
  egal(n.motDePasseTemporaire, null, 'Compte existant : pas de mot de passe provisoire');
  etat(ctx).espace = { e, telephone, eleveId: d.eleve.id, inscriptionId: ins.id, utilisateurId: n.administrateur.utilisateurId, pays: bf };
  return `${ins.classeCode}`;
});

scenario('ESP-02', D_ESP, 'À la connexion, elle choisit entre l’administration du pays et l’établissement de son enfant, où elle n’est que parent', async (ctx) => {
  const x = etat(ctx).espace;
  if (!x) ignorer('Administratrice parent non nommée (ESP-01)');
  const c = await connexion(x.telephone, ctx.motDePasse);
  statut(c, 200);
  egal(c.donnees.selectionRequise, true, 'Choix d’espace demandé');
  egal(c.donnees.jetonAcces, null, 'Pas de jeton d’accès avant le choix');
  egal(c.donnees.administration?.type, 'PAYS', 'Espace d’administration proposé');
  egal(c.donnees.etablissements.map((y) => [y.id, y.roles]), [[x.e.id, ['PARENT']]], 'Établissement de son enfant, en parent');
  const adm = await appelBrut('POST', '/auth/administration', undefined, { jeton: c.donnees.jetonSelection });
  statut(adm, 200);
  egal([adm.donnees.adminPays?.code, adm.donnees.etablissementActif], ['BF', null], 'Session d’administration du pays');
  await exiger(adm.donnees.jetonAcces, 'GET', '/plateforme/etablissements');
  const par = await appelBrut('POST', '/auth/etablissement', { etablissementId: x.e.id }, { jeton: c.donnees.jetonSelection });
  statut(par, 200);
  egal([par.donnees.etablissementActif?.roles, par.donnees.adminPays, par.donnees.superAdmin], [['PARENT'], null, false], 'Dans l’établissement : parent seulement');
  vrai(par.donnees.administration, 'L’espace d’administration reste proposé (« Changer d’espace »)');
  statut(await appel(par.donnees.jetonAcces, 'GET', '/plateforme/etablissements'), 403);
  const enfants = await exiger(par.donnees.jetonAcces, 'GET', '/espace-parent/enfants');
  vrai(enfants.some((y) => y.eleveId === x.eleveId), 'Son enfant dans l’espace parent');
  // Changer d'espace depuis la session de parent : le cookie de session ouvre l'administration
  const cookie = cookieDe(par);
  if (cookie) {
    const bascule = await appelBrut('POST', '/auth/administration', undefined, { jeton: par.donnees.jetonAcces, cookie });
    if (bascule.ok) egal(bascule.donnees.adminPays?.code, 'BF', 'Bascule vers l’administration du pays');
  }
});

scenario('ESP-03', D_ESP, 'L’établissement de son enfant ne gère pas ce compte : ni réinitialisation, ni déverrouillage, ni rôle du personnel', async (ctx) => {
  const x = etat(ctx).espace;
  if (!x) ignorer('Administratrice parent non nommée (ESP-01)');
  const adm = role(x.e, 'ADMIN_ECOLE');
  statut(await appel(adm, 'POST', `/comptes/${x.utilisateurId}/reinitialisation`), 403);
  statut(await appel(adm, 'POST', `/comptes/${x.utilisateurId}/deverrouillage`), 403);
  statut(await appel(adm, 'POST', '/membres', { telephone: x.telephone, nom: 'Recette', prenoms: 'Mariam', role: 'SECRETARIAT' }), 409, 'COMPTE_PLATEFORME');
  statut((await connexion(x.telephone, ctx.motDePasse)), 200);
});

scenario('ESP-04', D_ESP, 'Retirée de l’administration du pays, elle se connecte directement à l’établissement de son enfant, comme tout parent', async (ctx) => {
  const x = etat(ctx).espace;
  if (!x) ignorer('Administratrice parent non nommée (ESP-01)');
  statut(await appel(ctx.sa, 'DELETE', `/plateforme/territoire/pays/${x.pays.id}/administrateurs/${x.utilisateurId}`), 204);
  const c = await connexion(x.telephone, ctx.motDePasse);
  statut(c, 200);
  egal([c.donnees.selectionRequise, c.donnees.adminPays ?? null, c.donnees.administration ?? null], [false, null, null], 'Plus d’espace d’administration');
  egal(c.donnees.etablissementActif?.roles, ['PARENT'], 'Session de parent');
  statut(await appelBrut('POST', '/auth/administration', undefined, { jeton: c.donnees.jetonAcces }), [401, 403]);
  await exiger(role(x.e, 'SECRETARIAT'), 'POST', `/inscriptions/${x.inscriptionId}/sortie`, { statut: 'ABANDON', date: ctx.aujourdhui, motif: 'Recette : fin du test' });
});
