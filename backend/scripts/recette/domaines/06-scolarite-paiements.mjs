/**
 * Recette grandeur nature — Scolarité, guichet et reçus, moyens de paiement et virements, Mobile Money.
 *
 * Tout se passe au Lycée technique Les Bâtisseurs (établissement 1), sur deux élèves de test créés et inscrits
 * par la recette à chaque passage (A : non boursier, avec un espace parent de test ; B : boursier), pour
 * maîtriser le reste à payer. Les frais de démonstration ne sont jamais modifiés : un frais facultatif de test
 * est créé puis supprimé. L'organisme financeur de test est retrouvé par son nom (créé au premier passage).
 *
 * Réglages de l'établissement : les moyens de paiement (`/parametres/paiement`) sont lus avant chaque
 * modification et rétablis dans un `finally` (avecParametres) ; le compte bancaire de test (retrouvé par son
 * numéro, jamais supprimable) n'est actif que le temps d'un scénario. La configuration Mobile Money existante
 * n'est jamais remplacée ; si elle manque, elle est créée avec l'agrégateur SIMULATEUR (et le rapport le dit).
 * En fin de parcours, les élèves de test sortent (abandon) pour ne pas peser sur les effectifs et les relances.
 *
 * Sources : scolarite/ScolariteController, PaiementsController, EncaissementsService, MoyensPaiementService,
 * VirementsService, SituationsService, FraisService, BoursesService, RelancesService, CalculEcheancier ;
 * mobilemoney/MobileMoneyController, PaiementsMobileMoneyService, ConfigurationMobileMoneyService,
 * SimulateurController, AgregateurSimule, RapprochementService ; statistiques/StatistiquesController ;
 * notifications/NotificationsController ; docs/API_SCOLARITE.md, API_MOBILE_MONEY.md, API_STATISTIQUES.md.
 */
import {
  ajouterJours, appel, cle, Echec, egal, exiger, fichierPng, ignorer, refuse, sansTexte, scenario, statut,
  suffixe, telephoneUnique, vrai,
} from '../lib.mjs';

const SCO = 'Scolarité';
const GUI = 'Guichet et reçus';
const PAI = 'Moyens de paiement et virements';
const MMO = 'Mobile Money';

const ECOLE = 'lt-batisseurs';
const ORGANISME_TEST = 'Organisme de recette automatisée';
/** Compte bancaire de test : retrouvé par son numéro d'un passage à l'autre (un compte ne se supprime pas). */
const COMPTE_TEST = {
  banque: 'Banque de la recette',
  titulaire: 'Recette automatisée (ne pas utiliser)',
  numero: 'BF99 RECETTE 000001',
  codeSwift: 'RECEBFBF',
  agence: 'Agence de test',
};

// ===================================================================== outils du lot

/** État partagé entre les scénarios de ce fichier. */
function etat(ctx) {
  ctx.partage.scolarite ??= { erreursRetablissement: [] };
  return ctx.partage.scolarite;
}

function ecole(ctx) {
  const e = ctx.ecole(ECOLE) ?? ctx.ecole(1);
  if (!e) ignorer('Établissement de démonstration n° 1 absent de l’état du peuplement');
  return e;
}

function role(e, r) {
  const s = e.session(r);
  if (!s) ignorer(`Aucun compte ${r} dans ${e.code}`);
  return s;
}

function enseignant(e) {
  const p = e.enseignants?.[0];
  if (!p) ignorer(`Aucun enseignant dans ${e.code}`);
  return e.enseignant(p.matiere);
}

/** Un parent de démonstration de l'établissement et un de ses enfants (pour l'isolation entre familles). */
function familleDemo(e) {
  const f = e.famille(0);
  const enfant = f?.enfants.find((x) => x.ecole === e.n);
  if (!f || !enfant) ignorer(`Aucune famille de démonstration dans ${e.code}`);
  return { parent: e.parent(0), enfant };
}

function autreEcole(ctx) {
  const e3 = ctx.ecole('lp-horizon') ?? ctx.ecole(3);
  const s = e3?.session('INTENDANT');
  if (!s) ignorer('Intendant du lycée Horizon absent : isolation non vérifiable');
  return s;
}

/** Module fermé par le super administrateur : le scénario est ignoré (ce n'est pas un défaut). */
function siModuleFerme(r) {
  if (r.statut === 403 && r.code === 'MODULE_DESACTIVE') {
    ignorer(`Module désactivé pour l'établissement (${r.donnees?.module ?? '?'})`);
  }
}

const somme = (liste, f) => liste.reduce((a, x) => a + (f(x) ?? 0), 0);

/** Lettres seulement, pour les noms et prénoms des élèves de test. */
function lettres(texte) {
  return texte.replace(/[0-9]/g, (d) => 'ABCDEFGHIJ'[Number(d)]).toUpperCase();
}

async function situation(qui, inscriptionId) {
  return exiger(qui, 'GET', `/inscriptions/${inscriptionId}/scolarite`);
}

/** Vérifie les invariants d'une situation (CalculEcheancier) : total = exonéré + famille + organisme. */
function coherente(s, quoi) {
  egal(s.total, s.exonere + s.totalFamille + s.totalOrganisme, `${quoi} : total = exonéré + part famille + part organisme`);
  egal(s.resteFamille, s.totalFamille - (s.payeFamille - s.avanceFamille), `${quoi} : reste famille = dû − payé`);
  egal(somme(s.echeances, (x) => x.montant), s.total, `${quoi} : somme des échéances = total`);
}

/** Corps d'un encaissement au guichet (clé d'idempotence neuve par défaut). */
function paiement(montant, moyen = 'ESPECES', extra = {}) {
  return {
    montant,
    moyen,
    payeur: 'FAMILLE',
    organismeId: null,
    referenceExterne: moyen === 'ESPECES' ? null : `REC-${suffixe().toUpperCase()}`,
    deposant: 'M. RECETTE',
    cleIdempotence: cle(),
    ...extra,
  };
}

/** Moyens acceptés au guichet de l'établissement (réglage v0.38). */
async function moyensGuichet(ctx) {
  const e = ecole(ctx);
  const p = await exiger(role(e, 'INTENDANT'), 'GET', '/parametres/paiement');
  return p.moyensGuichet ?? [];
}

async function moyenAccepte(ctx, preferes) {
  const acceptes = await moyensGuichet(ctx);
  const m = preferes.find((x) => acceptes.includes(x));
  if (!m) ignorer(`Aucun des moyens ${preferes.join(', ')} n'est accepté au guichet`);
  return m;
}

/** Dernières notifications de l'établissement (administrateur) dont la clé commence ainsi. */
async function notification(ctx, prefixeCle) {
  const liste = await exiger(role(ecole(ctx), 'ADMIN_ECOLE'), 'GET', '/notifications', undefined, { params: { limite: 500 } });
  return liste.find((n) => n.cle?.startsWith(prefixeCle)) ?? null;
}

// --------------------------------------------------------------------- élèves de test

/**
 * Crée (une fois par passage) les deux élèves de test : A non boursier avec espace parent, B boursier.
 * Chaque passage crée de nouveaux élèves : leurs paiements ne s'effacent jamais.
 */
async function elevesDeTest(ctx) {
  const s = etat(ctx);
  if (s.eleves) return s.eleves;
  if (s.elevesEchec) ignorer(`Élèves de test non créés : ${s.elevesEchec}`);
  try {
    s.eleves = await creerEleves(ctx);
    return s.eleves;
  } catch (err) {
    if (err instanceof Echec) s.elevesEchec = err.message;
    throw err;
  }
}

async function creerEleves(ctx) {
  const e = ecole(ctx);
  const adm = role(e, 'ADMIN_ECOLE');
  siModuleFerme(await appel(role(e, 'INTENDANT'), 'GET', '/parametres/scolarite'));
  const suf = lettres(suffixe());
  const creer = async (prenoms, statutBourse) => {
    const telephone = telephoneUnique('7');
    const dossier = await exiger(adm, 'POST', '/eleves', {
      nom: 'RECETTE',
      prenoms: `${prenoms} ${suf}`,
      sexe: 'M',
      dateNaissance: '2010-03-15',
      lieuNaissance: 'Ouagadougou',
      responsables: [{
        nom: 'RECETTE', prenoms: `Parent ${suf}`, telephone, lien: 'PERE', responsableLegal: true, contactPrioritaire: true,
      }],
    });
    let inscription = null;
    let derniere = null;
    // La première classe qui a de la place (effectif maximal)
    for (const c of e.classes) {
      const r = await appel(adm, 'POST', '/inscriptions', { eleveId: dossier.eleve.id, classeId: c.id, redoublant: false, statutBourse });
      if (r.ok) {
        inscription = r.donnees;
        break;
      }
      derniere = r;
    }
    if (!inscription) throw new Echec(`Inscription de l'élève de test impossible : ${derniere?.resume()}`);
    return {
      eleveId: dossier.eleve.id,
      inscriptionId: inscription.id,
      classeId: inscription.classeId,
      classeCode: inscription.classeCode,
      matricule: inscription.matricule,
      nom: inscription.nom,
      prenoms: inscription.prenoms,
      telephone,
      responsableId: dossier.responsables?.[0]?.responsableId,
    };
  };
  const A = await creer('Paiement', 'NON_BOURSIER');
  const B = await creer('Bourse', 'BOURSIER');
  if (!A.responsableId) throw new Echec('Le dossier créé ne renvoie pas le responsable de l’élève');
  const espace = await exiger(adm, 'POST', `/responsables/${A.responsableId}/espace-parent`);
  A.parent = await ctx.activer(A.telephone, espace.motDePasseTemporaire, e.id);
  await A.parent.jeton();
  return { A, B };
}

// --------------------------------------------------------------------- réglages des moyens de paiement

function corpsParametres(p) {
  return {
    moyensGuichet: p.moyensGuichet,
    mobileMoneyParents: p.mobileMoneyParents,
    operateurs: p.operateurs,
    virementParents: p.virementParents,
    // null signifie « inchangé » pour le serveur : une chaîne vide efface la consigne
    consigneVirement: p.consigneVirement ?? '',
  };
}

function memesParametres(a, b) {
  const n = (p) => JSON.stringify({
    g: [...(p.moyensGuichet ?? [])].sort(),
    m: !!p.mobileMoneyParents,
    o: [...(p.operateurs ?? [])].sort(),
    v: !!p.virementParents,
    c: p.consigneVirement ?? null,
  });
  return n(a) === n(b);
}

/** Paramètres de paiement lus la première fois, avant toute modification par ce lot. */
async function parametresInitiaux(ctx) {
  const s = etat(ctx);
  if (!s.parametresInitiaux) {
    const r = await appel(role(ecole(ctx), 'ADMIN_ECOLE'), 'GET', '/parametres/paiement');
    siModuleFerme(r);
    if (!r.ok) throw new Echec(`Lecture des moyens de paiement impossible : ${r.resume()}`);
    s.parametresInitiaux = r.donnees;
  }
  return s.parametresInitiaux;
}

async function basculerCompte(adm, compte, actif) {
  const { banque, titulaire, numero, codeSwift, agence } = compte;
  return appel(adm, 'PUT', `/comptes-bancaires/${compte.id}`, { banque, titulaire, numero, codeSwift, agence, actif });
}

/** Le compte bancaire de test : retrouvé par son numéro, sinon créé inactif. */
async function compteDeTest(ctx) {
  const s = etat(ctx);
  if (s.compte) return s.compte;
  const adm = role(ecole(ctx), 'ADMIN_ECOLE');
  await parametresInitiaux(ctx);
  const p = await exiger(adm, 'GET', '/parametres/paiement');
  let c = (p.comptes ?? []).find((x) => x.numero === COMPTE_TEST.numero);
  if (!c) {
    // Saisi en minuscules et avec des espaces doublés : le serveur normalise le numéro
    c = await exiger(adm, 'POST', '/comptes-bancaires', { ...COMPTE_TEST, numero: 'bf99  recette 000001', actif: false });
    s.compteCree = c;
  }
  s.compte = c;
  return c;
}

/**
 * Applique des réglages de paiement le temps de `fn`, puis rétablit ceux lus juste avant (finally).
 * Avec `compte`, le compte bancaire de test est actif pendant `fn` et désactivé ensuite.
 */
async function avecParametres(ctx, modifs, fn, { compte = null } = {}) {
  const s = etat(ctx);
  const adm = role(ecole(ctx), 'ADMIN_ECOLE');
  await parametresInitiaux(ctx);
  const avant = await exiger(adm, 'GET', '/parametres/paiement');
  let compteActive = false;
  let reussi = false;
  const erreurs = [];
  try {
    if (compte) {
      const r = await basculerCompte(adm, compte, true);
      if (!r.ok) throw new Echec(`Activation du compte bancaire de test impossible : ${r.resume()}`);
      compteActive = true;
    }
    const r = await appel(adm, 'PUT', '/parametres/paiement', modifs);
    if (!r.ok) throw new Echec(`Réglage des moyens de paiement impossible : ${r.resume()}`);
    const resultat = await fn(r.donnees);
    reussi = true;
    return resultat;
  } finally {
    const r = await appel(adm, 'PUT', '/parametres/paiement', corpsParametres(avant));
    if (!r.ok) erreurs.push(`moyens de paiement non rétablis : ${r.resume()}`);
    if (compteActive) {
      const d = await basculerCompte(adm, compte, false);
      if (!d.ok) erreurs.push(`compte bancaire de test resté actif : ${d.resume()}`);
    }
    if (erreurs.length) {
      s.erreursRetablissement.push(...erreurs);
      console.warn(`    ⚠ ${erreurs.join(' ; ')}`);
      if (reussi) throw new Echec(`Remise en état impossible : ${erreurs.join(' ; ')}`);
    }
  }
}

// --------------------------------------------------------------------- virements et Mobile Money

function formulaireVirement({ compteId, montant, dateVirement, reference, donneurOrdre = 'RECETTE Parent', cleIdempotence, fichier, nom = 'avis.png' }) {
  const f = new FormData();
  if (compteId) f.set('compteId', compteId);
  if (montant !== undefined) f.set('montant', String(montant));
  if (dateVirement) f.set('dateVirement', dateVirement);
  if (reference !== undefined) f.set('reference', reference);
  if (donneurOrdre) f.set('donneurOrdre', donneurOrdre);
  if (cleIdempotence) f.set('cleIdempotence', cleIdempotence);
  if (fichier) f.set('fichier', fichier, nom);
  return f;
}

function declarer(parent, inscriptionId, champs) {
  return appel(parent, 'POST', `/espace-parent/inscriptions/${inscriptionId}/virements`, formulaireVirement(champs));
}

/** Configuration Mobile Money utilisable avec le simulateur, sinon le scénario est ignoré. */
async function exigerSimulateur(ctx) {
  const r = await appel(role(ecole(ctx), 'ADMIN_ECOLE'), 'GET', '/parametres/mobile-money');
  siModuleFerme(r);
  if (!r.ok) throw new Echec(`Lecture de la configuration Mobile Money impossible : ${r.resume()}`);
  if (r.donnees.agregateur !== 'SIMULATEUR' || !r.donnees.actif) {
    ignorer(`Mobile Money configuré avec ${r.donnees.agregateur ?? 'rien'}${r.donnees.actif ? '' : ' (inactif)'} : simulateur indisponible`);
  }
  return r.donnees;
}

const OPERATEURS_TEST = { mobileMoneyParents: true, operateurs: ['MOOV_MONEY', 'ORANGE_MONEY'] };

function payerMM(parent, eleve, montant, operateur = 'ORANGE_MONEY', cleIdempotence = cle()) {
  return appel(parent, 'POST', `/espace-parent/inscriptions/${eleve.inscriptionId}/mobile-money`, {
    montant, operateur, telephone: eleve.telephone, cleIdempotence,
  });
}

/** Le parent confirme (ou refuse) sur son téléphone : point d'accès du profil dev. */
async function simuler(qui, transactionId, params = {}) {
  const r = await appel(qui, 'POST', `/simulateur/mobile-money/${transactionId}`, undefined, { params });
  if ((r.statut === 404 && !String(r.detail ?? '').includes('Transaction')) || r.statut === 405) {
    ignorer('Simulateur Mobile Money absent : l’API ne tourne pas avec le profil dev');
  }
  if (!r.ok) throw new Echec(`Simulateur : ${r.resume()}`);
  return r.donnees;
}

function exigerMM(ctx, cleMM) {
  const t = etat(ctx).mm?.[cleMM];
  if (!t) ignorer(`Transaction « ${cleMM} » absente (scénario précédent en échec ou ignoré)`);
  return t;
}

// ===================================================================== Scolarité

scenario('SCO-01', SCO, 'Les paramètres de scolarité se consultent à l’intendance et au secrétariat ; seul l’administrateur les modifie, dans les bornes', async (ctx) => {
  const e = ecole(ctx);
  const lu = await appel(role(e, 'INTENDANT'), 'GET', '/parametres/scolarite');
  siModuleFerme(lu);
  statut(lu, 200);
  const p = lu.donnees;
  vrai(p.tauxBoursier != null && p.tauxSemiBoursier != null && p.delaiRelanceJours != null, 'Taux et délai de relance attendus');
  statut(await appel(role(e, 'SECRETARIAT'), 'GET', '/parametres/scolarite'), 200);
  refuse(await appel(enseignant(e), 'GET', '/parametres/scolarite'), 'Un enseignant ne lit pas les paramètres de scolarité');
  const corps = { tauxBoursier: p.tauxBoursier, tauxSemiBoursier: p.tauxSemiBoursier, delaiRelanceJours: p.delaiRelanceJours, relancesAutomatiques: p.relancesAutomatiques };
  refuse(await appel(role(e, 'INTENDANT'), 'PUT', '/parametres/scolarite', corps), 'L’intendant ne modifie pas les paramètres');
  const adm = role(e, 'ADMIN_ECOLE');
  statut(await appel(adm, 'PUT', '/parametres/scolarite', { ...corps, delaiRelanceJours: 0 }), 400);
  statut(await appel(adm, 'PUT', '/parametres/scolarite', { ...corps, tauxBoursier: 150 }), 400);
  // Réenregistrement à l'identique : aucun changement pour l'établissement
  const remis = await exiger(adm, 'PUT', '/parametres/scolarite', corps);
  egal(Number(remis.delaiRelanceJours), Number(p.delaiRelanceJours), 'Délai de relance inchangé');
  egal(Number(remis.tauxBoursier), Number(p.tauxBoursier), 'Taux boursier inchangé');
  etat(ctx).parametresScolarite = p;
  return `boursier ${p.tauxBoursier} %, semi-boursier ${p.tauxSemiBoursier} %, relance tous les ${p.delaiRelanceJours} j`;
});

scenario('SCO-02', SCO, 'L’intendance gère les organismes financeurs ; un nom en double est refusé et le secrétariat ne crée rien', async (ctx) => {
  const e = ecole(ctx);
  const intendant = role(e, 'INTENDANT');
  const liste = await exiger(role(e, 'SECRETARIAT'), 'GET', '/organismes');
  vrai(Array.isArray(liste) && liste.length >= 1, 'Les organismes du peuplement sont listés');
  let org = liste.find((o) => o.nom === ORGANISME_TEST);
  let note = 'organisme de test retrouvé';
  if (!org) {
    org = await exiger(intendant, 'POST', '/organismes', { nom: ORGANISME_TEST, type: 'ONG', telephone: null });
    egal(org.actif, true, 'Organisme créé actif');
    note = 'organisme de test créé';
  } else if (!org.actif) {
    org = await exiger(intendant, 'PUT', `/organismes/${org.id}`, { nom: org.nom, type: org.type, telephone: org.telephone, actif: true });
    egal(org.actif, true, 'Organisme de test réactivé');
  }
  statut(await appel(intendant, 'POST', '/organismes', { nom: ORGANISME_TEST.toUpperCase(), type: 'ONG' }), 409, 'ORGANISME_EXISTANT');
  statut(await appel(intendant, 'POST', '/organismes', { nom: '  ', type: 'ONG' }), 400);
  refuse(await appel(role(e, 'SECRETARIAT'), 'POST', '/organismes', { nom: `Autre ${suffixe()}`, type: 'ETAT' }), 'Le secrétariat ne crée pas d’organisme');
  etat(ctx).organisme = org;
  etat(ctx).autreOrganisme = liste.find((o) => o.id !== org.id && o.actif) ?? null;
  return note;
});

scenario('SCO-03', SCO, 'Les frais de l’année se consultent avec leurs tranches (somme = montant) ; enseignant, parent et autre établissement n’y ont pas accès', async (ctx) => {
  const e = ecole(ctx);
  const r = await appel(role(e, 'SECRETARIAT'), 'GET', `/annees/${e.anneeId}/frais`);
  siModuleFerme(r);
  statut(r, 200);
  const frais = r.donnees;
  vrai(frais.some((f) => f.obligatoire), 'Au moins un frais obligatoire (peuplement)');
  for (const f of frais) {
    egal(somme(f.tranches, (t) => t.montant), f.montant, `Tranches du frais « ${f.libelle} »`);
  }
  refuse(await appel(role(e, 'SECRETARIAT'), 'POST', `/annees/${e.anneeId}/frais`, { libelle: `Interdit ${suffixe()}`, montant: 1000 }),
    'Le secrétariat ne crée pas de frais');
  refuse(await appel(enseignant(e), 'GET', `/annees/${e.anneeId}/frais`), 'Un enseignant ne voit pas les frais');
  const { parent } = familleDemo(e);
  refuse(await appel(parent, 'GET', `/annees/${e.anneeId}/frais`), 'Un parent ne voit pas la liste des frais');
  refuse(await appel(autreEcole(ctx), 'GET', `/annees/${e.anneeId}/frais`), 'Un autre établissement ne voit pas ces frais');
  return `${frais.length} frais`;
});

scenario('SCO-04', SCO, 'L’intendance crée un frais facultatif de test avec ses tranches ; double libellé, tranches incohérentes et dates hors de l’année sont refusés', async (ctx) => {
  const e = ecole(ctx);
  const intendant = role(e, 'INTENDANT');
  const { rentree, fin } = ctx.etat.annee;
  const t2 = ajouterJours(fin, -30);
  const libelle = `Transport recette ${suffixe()}`;
  const corps = {
    libelle, montant: 6000, obligatoire: false, couvertParBourse: false, portee: 'TOUTES',
    tranches: [{ dateLimite: rentree, montant: 3000 }, { dateLimite: t2, montant: 3000 }],
  };
  const r = await appel(intendant, 'POST', `/annees/${e.anneeId}/frais`, corps);
  if (r.statut === 409 && r.code === 'ANNEE_CLOTUREE') ignorer('Année close : les frais ne changent plus');
  statut(r, 201);
  const f = r.donnees;
  egal(f.obligatoire, false, 'Frais facultatif');
  egal(f.tranches.map((t) => t.montant), [3000, 3000], 'Deux tranches');
  etat(ctx).frais = { ...f, corps };
  statut(await appel(intendant, 'POST', `/annees/${e.anneeId}/frais`, { ...corps, libelle: libelle.toUpperCase() }), 409, 'FRAIS_EXISTANT');
  statut(await appel(intendant, 'POST', `/annees/${e.anneeId}/frais`, {
    ...corps, libelle: `${libelle} bis`, tranches: [{ dateLimite: rentree, montant: 3000 }, { dateLimite: t2, montant: 2000 }],
  }), 409, 'TRANCHES_INCOHERENTES');
  statut(await appel(intendant, 'POST', `/annees/${e.anneeId}/frais`, {
    ...corps, libelle: `${libelle} ter`, tranches: [{ dateLimite: rentree, montant: 3000 }, { dateLimite: ajouterJours(fin, 10), montant: 3000 }],
  }), 400);
  statut(await appel(intendant, 'POST', `/annees/${e.anneeId}/frais`, { ...corps, libelle: `${libelle} quater`, portee: 'FILIERES', filieres: [] }), 400);
  statut(await appel(intendant, 'POST', `/annees/${e.anneeId}/frais`, { ...corps, libelle: `${libelle} cinq`, montant: 0, tranches: [] }), 400);
  return libelle;
});

scenario('SCO-05', SCO, 'Deux élèves de test sont inscrits : leur échéancier est cohérent, déjà en retard, et le boursier a une part organisme même sans prise en charge', async (ctx) => {
  const { A, B } = await elevesDeTest(ctx);
  const intendant = role(ecole(ctx), 'INTENDANT');
  const sA = await situation(intendant, A.inscriptionId);
  coherente(sA, 'Élève A');
  vrai(sA.total > 0 && sA.payeFamille === 0, 'Élève A : des frais dus, rien de payé');
  egal(sA.totalOrganisme, 0, 'Élève A non boursier : pas de part organisme');
  vrai(sA.retardFamille > 0, 'Élève A : les premières échéances (rentrée) sont déjà en retard');
  egal(sA.statutBourse, 'NON_BOURSIER', 'Statut de bourse de A');
  const sB = await situation(intendant, B.inscriptionId);
  coherente(sB, 'Élève B');
  vrai(sB.totalOrganisme > 0, 'Boursier sans prise en charge enregistrée : part organisme au taux par défaut');
  vrai(sB.totalFamille > 0, 'Boursier : les frais non couverts par la bourse restent à la famille');
  etat(ctx).totalInitialA = sA.total;
  return `${A.prenoms} (${A.classeCode}) : ${sA.total} FCFA dont ${sA.retardFamille} en retard ; ${B.prenoms} : famille ${sB.totalFamille}, organisme ${sB.totalOrganisme}`;
});

scenario('SCO-06', SCO, 'Un élève souscrit au frais facultatif : il s’ajoute à son échéancier, suit une modification du frais et disparaît à la résiliation', async (ctx) => {
  const f = etat(ctx).frais;
  if (!f) ignorer('Frais facultatif de test absent (SCO-04)');
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  const intendant = role(e, 'INTENDANT');
  const base = (await situation(intendant, A.inscriptionId)).total;
  refuse(await appel(role(e, 'SECRETARIAT'), 'PUT', `/inscriptions/${A.inscriptionId}/frais/${f.id}`), 'Le secrétariat ne souscrit pas');
  statut(await appel(intendant, 'PUT', `/inscriptions/${A.inscriptionId}/frais/${f.id}`), 204);
  statut(await appel(intendant, 'PUT', `/inscriptions/${A.inscriptionId}/frais/${f.id}`), 204);
  let s = await situation(intendant, A.inscriptionId);
  egal(s.total, base + 6000, 'Total après souscription (une seule fois malgré deux appels)');
  vrai(s.echeances.some((x) => x.fraisId === f.id), 'Échéances du frais facultatif présentes');
  // Modification du frais : l'échéancier est recalculé à la consultation
  const { rentree, fin } = ctx.etat.annee;
  await exiger(intendant, 'PUT', `/frais/${f.id}`, {
    ...f.corps, montant: 8000, tranches: [{ dateLimite: rentree, montant: 4000 }, { dateLimite: ajouterJours(fin, -30), montant: 4000 }],
  });
  s = await situation(intendant, A.inscriptionId);
  egal(s.total, base + 8000, 'Total après modification du frais');
  // Un frais obligatoire ne se souscrit pas
  const obligatoire = s.echeances.find((x) => x.fraisId !== f.id);
  if (obligatoire) {
    statut(await appel(intendant, 'PUT', `/inscriptions/${A.inscriptionId}/frais/${obligatoire.fraisId}`), 409, 'FRAIS_OBLIGATOIRE');
  }
  statut(await appel(intendant, 'DELETE', `/inscriptions/${A.inscriptionId}/frais/${f.id}`), 204);
  s = await situation(intendant, A.inscriptionId);
  egal(s.total, base, 'Total revenu à l’initial après résiliation');
  coherente(s, 'Élève A');
});

scenario('SCO-07', SCO, 'Une exonération réduit d’abord les dernières tranches, exige un motif et un montant borné, et se retire', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  const intendant = role(e, 'INTENDANT');
  const avant = await situation(intendant, A.inscriptionId);
  // Le frais aux tranches les plus nombreuses
  const cible = [...avant.echeances].sort((a, b) => b.nombreTranches - a.nombreTranches)[0];
  if (!cible) ignorer('Aucune échéance pour l’élève de test');
  const tranches = avant.echeances.filter((x) => x.fraisId === cible.fraisId).sort((a, b) => a.numero - b.numero);
  const derniere = tranches.at(-1);
  const montant = Math.min(9000, derniere.montant);
  const chemin = `/inscriptions/${A.inscriptionId}/exonerations/${cible.fraisId}`;
  refuse(await appel(role(e, 'SECRETARIAT'), 'PUT', chemin, { montant, motif: 'Enfant du personnel' }), 'Le secrétariat n’exonère pas');
  statut(await appel(intendant, 'PUT', chemin, { montant, motif: ' ' }), 400);
  statut(await appel(intendant, 'PUT', chemin, { montant: somme(tranches, (x) => x.montant) + 1, motif: 'Trop' }), 400);
  const exo = await exiger(intendant, 'PUT', chemin, { montant, motif: 'Enfant du personnel (recette)' });
  egal(exo.montant, montant, 'Montant de l’exonération');
  const s = await situation(intendant, A.inscriptionId);
  egal(s.exonere, montant, 'Total exonéré');
  egal(s.totalFamille, avant.totalFamille - montant, 'La part famille baisse d’autant');
  const apres = s.echeances.filter((x) => x.fraisId === cible.fraisId).sort((a, b) => a.numero - b.numero);
  egal(apres.at(-1).exoneration, montant, 'L’exonération porte sur la dernière tranche');
  if (apres.length > 1) egal(apres[0].exoneration, 0, 'La première tranche n’est pas exonérée');
  vrai(s.exonerations.some((x) => x.fraisId === cible.fraisId && x.motif.includes('Enfant du personnel')), 'Exonération listée avec son motif');
  statut(await appel(intendant, 'DELETE', chemin), 204);
  egal((await situation(intendant, A.inscriptionId)).exonere, 0, 'Exonération retirée');
  return `${montant} FCFA sur « ${cible.libelle} »`;
});

scenario('SCO-08', SCO, 'Une prise en charge s’enregistre pour un boursier seulement, au taux par défaut du statut, avec l’organisme et la décision', async (ctx) => {
  const { A, B } = await elevesDeTest(ctx);
  const org = etat(ctx).organisme;
  if (!org) ignorer('Organisme de test absent (SCO-02)');
  const e = ecole(ctx);
  const intendant = role(e, 'INTENDANT');
  const decision = { organismeId: org.id, taux: null, referenceDecision: `DEC-REC-${suffixe()}`, dateDecision: ctx.etat.annee.rentree };
  statut(await appel(intendant, 'PUT', `/inscriptions/${A.inscriptionId}/prise-en-charge`, decision), 409, 'ELEVE_NON_BOURSIER');
  statut(await appel(intendant, 'GET', `/inscriptions/${B.inscriptionId}/prise-en-charge`), 404);
  // Versement d'organisme sans prise en charge enregistrée
  const moyen = await moyenAccepte(ctx, ['VIREMENT', 'CHEQUE', 'ESPECES']);
  statut(await appel(intendant, 'POST', `/inscriptions/${B.inscriptionId}/paiements`, paiement(1000, moyen, { payeur: 'ORGANISME' })),
    409, 'SANS_PRISE_EN_CHARGE');
  statut(await appel(intendant, 'PUT', `/inscriptions/${B.inscriptionId}/prise-en-charge`, { ...decision, taux: 150 }), 400);
  refuse(await appel(role(e, 'SECRETARIAT'), 'PUT', `/inscriptions/${B.inscriptionId}/prise-en-charge`, decision), 'Le secrétariat n’enregistre pas de prise en charge');
  const pec = await exiger(intendant, 'PUT', `/inscriptions/${B.inscriptionId}/prise-en-charge`, decision);
  egal(pec.organismeId, org.id, 'Organisme de la prise en charge');
  const defaut = etat(ctx).parametresScolarite?.tauxBoursier;
  if (defaut != null) egal(Number(pec.taux), Number(defaut), 'Taux par défaut du statut boursier');
  const lue = await exiger(role(e, 'SECRETARIAT'), 'GET', `/inscriptions/${B.inscriptionId}/prise-en-charge`);
  egal(lue.referenceDecision, decision.referenceDecision, 'Référence de la décision');
  const s = await situation(intendant, B.inscriptionId);
  egal(s.organisme, org.nom, 'Organisme affiché dans la situation');
  vrai(s.totalOrganisme > 0, 'Part organisme');
  coherente(s, 'Élève B');
  return `taux ${pec.taux} %`;
});

scenario('SCO-09', SCO, 'Le versement de l’organisme réduit sa part seulement ; un organisme non financeur ou un montant excessif sont refusés ; un taux partiel se recalcule', async (ctx) => {
  const { B } = await elevesDeTest(ctx);
  const s0 = etat(ctx);
  if (!s0.organisme) ignorer('Organisme de test absent');
  const intendant = role(ecole(ctx), 'INTENDANT');
  const avant = await situation(intendant, B.inscriptionId);
  if (!avant.organisme) ignorer('Prise en charge de B absente (SCO-08)');
  const moyen = await moyenAccepte(ctx, ['VIREMENT', 'CHEQUE', 'ESPECES']);
  const chemin = `/inscriptions/${B.inscriptionId}/paiements`;
  const corps = paiement(5000, moyen, { payeur: 'ORGANISME', organismeId: s0.organisme.id, deposant: s0.organisme.nom });
  const p = await exiger(intendant, 'POST', chemin, corps);
  egal(p.payeur, 'ORGANISME', 'Payeur');
  let s = await situation(intendant, B.inscriptionId);
  egal(s.payeOrganisme, avant.payeOrganisme + 5000, 'Payé par l’organisme');
  egal(s.payeFamille, avant.payeFamille, 'Payé par la famille inchangé');
  statut(await appel(intendant, 'POST', chemin, paiement(s.resteOrganisme + 1, moyen, { payeur: 'ORGANISME' })), 409, 'MONTANT_SUPERIEUR_AU_RESTE');
  if (s0.autreOrganisme) {
    statut(await appel(intendant, 'POST', chemin, paiement(1000, moyen, { payeur: 'ORGANISME', organismeId: s0.autreOrganisme.id })),
      409, 'ORGANISME_NON_FINANCEUR');
  }
  // Taux partiel : la part organisme baisse, la part famille monte d'autant
  await exiger(intendant, 'PUT', `/inscriptions/${B.inscriptionId}/prise-en-charge`, { organismeId: s0.organisme.id, taux: 50 });
  const partiel = await situation(intendant, B.inscriptionId);
  vrai(partiel.totalOrganisme < s.totalOrganisme, 'Part organisme réduite par le taux de 50 %');
  egal(partiel.totalFamille + partiel.totalOrganisme, s.totalFamille + s.totalOrganisme, 'Le total à répartir ne change pas');
  coherente(partiel, 'Élève B (taux 50 %)');
  return `organisme ${s.totalOrganisme} → ${partiel.totalOrganisme} FCFA à 50 %`;
});

scenario('SCO-10', SCO, 'Un organisme désactivé ne finance plus : prise en charge et versement refusés ; la prise en charge se supprime', async (ctx) => {
  const { B } = await elevesDeTest(ctx);
  const org = etat(ctx).organisme;
  if (!org) ignorer('Organisme de test absent');
  const intendant = role(ecole(ctx), 'INTENDANT');
  const pec = await appel(intendant, 'GET', `/inscriptions/${B.inscriptionId}/prise-en-charge`);
  if (pec.statut === 404) ignorer('Prise en charge de B absente (SCO-08)');
  const desactive = await exiger(intendant, 'PUT', `/organismes/${org.id}`, { nom: org.nom, type: org.type, telephone: org.telephone, actif: false });
  egal(desactive.actif, false, 'Organisme désactivé');
  statut(await appel(intendant, 'PUT', `/inscriptions/${B.inscriptionId}/prise-en-charge`, { organismeId: org.id, taux: 100 }), 409, 'ORGANISME_INACTIF');
  const moyen = await moyenAccepte(ctx, ['VIREMENT', 'CHEQUE', 'ESPECES']);
  statut(await appel(intendant, 'POST', `/inscriptions/${B.inscriptionId}/paiements`, paiement(500, moyen, { payeur: 'ORGANISME' })),
    409, 'ORGANISME_INACTIF');
  statut(await appel(intendant, 'DELETE', `/inscriptions/${B.inscriptionId}/prise-en-charge`), 204);
  statut(await appel(intendant, 'GET', `/inscriptions/${B.inscriptionId}/prise-en-charge`), 404);
  return 'organisme de test laissé désactivé (réactivé au prochain passage)';
});

scenario('SCO-11', SCO, 'Le parent suit la scolarité de son enfant seulement ; l’écran du personnel lui reste fermé', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  const r = await appel(A.parent, 'GET', `/espace-parent/enfants/${A.eleveId}/scolarite`);
  siModuleFerme(r);
  statut(r, 200);
  const s = r.donnees.find((x) => x.inscriptionId === A.inscriptionId);
  vrai(s, 'La situation de l’année figure dans l’espace parent');
  const staff = await situation(role(e, 'INTENDANT'), A.inscriptionId);
  egal(s.resteFamille, staff.resteFamille, 'Même reste à payer que l’intendance');
  const demo = familleDemo(e);
  refuse(await appel(demo.parent, 'GET', `/espace-parent/enfants/${A.eleveId}/scolarite`), 'Un autre parent ne voit pas cet enfant');
  refuse(await appel(A.parent, 'GET', `/espace-parent/enfants/${demo.enfant.eleveId}/scolarite`), 'Le parent de test ne voit pas un autre enfant');
  refuse(await appel(A.parent, 'GET', `/inscriptions/${A.inscriptionId}/scolarite`), 'Le parent n’utilise pas l’écran du personnel');
});

scenario('SCO-12', SCO, 'La situation d’un élève : secrétariat en lecture, enseignant et surveillant refusés, invisible d’un autre établissement', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  const s = await situation(role(e, 'SECRETARIAT'), A.inscriptionId);
  egal(s.matricule, A.matricule, 'Matricule');
  refuse(await appel(enseignant(e), 'GET', `/inscriptions/${A.inscriptionId}/scolarite`), 'Un enseignant ne voit pas la scolarité');
  const surveillant = e.session('SURVEILLANT');
  if (surveillant) refuse(await appel(surveillant, 'GET', `/inscriptions/${A.inscriptionId}/scolarite`), 'Le surveillant ne voit pas la scolarité');
  refuse(await appel(autreEcole(ctx), 'GET', `/inscriptions/${A.inscriptionId}/scolarite`), 'Autre établissement');
});

scenario('SCO-13', SCO, 'L’état de la classe totalise ses élèves et la liste Excel des retards se télécharge ; enseignant et autre établissement refusés', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  const sec = role(e, 'SECRETARIAT');
  const etatClasse = await exiger(sec, 'GET', `/classes/${A.classeId}/scolarite`);
  const ligne = etatClasse.eleves.find((x) => x.inscriptionId === A.inscriptionId);
  vrai(ligne, 'L’élève de test figure dans l’état de sa classe');
  const s = await situation(sec, A.inscriptionId);
  egal(ligne.resteFamille, s.resteFamille, 'Reste famille de la ligne');
  egal(ligne.retardFamille, s.retardFamille, 'Retard famille de la ligne');
  egal(etatClasse.totalFamille, somme(etatClasse.eleves, (x) => x.totalFamille), 'Total famille de la classe');
  egal(etatClasse.retardFamille, somme(etatClasse.eleves, (x) => x.retardFamille), 'Retard de la classe');
  const xlsx = await exiger(sec, 'GET', `/classes/${A.classeId}/scolarite/retards`, undefined, { fichier: true });
  vrai(xlsx.type.includes('spreadsheetml'), `Classeur Excel attendu (${xlsx.type})`);
  egal(xlsx.octets.subarray(0, 2).toString('latin1'), 'PK', 'Fichier xlsx (zip)');
  refuse(await appel(enseignant(e), 'GET', `/classes/${A.classeId}/scolarite`), 'Enseignant');
  refuse(await appel(enseignant(e), 'GET', `/classes/${A.classeId}/scolarite/retards`, undefined, { fichier: true }), 'Enseignant (Excel)');
  refuse(await appel(autreEcole(ctx), 'GET', `/classes/${A.classeId}/scolarite`), 'Autre établissement');
  etat(ctx).etatClasse = etatClasse;
  return `${etatClasse.classeCode} : ${etatClasse.eleves.length} élèves, recouvrement familles ${etatClasse.tauxRecouvrementFamille ?? '—'} %`;
});

scenario('SCO-14', SCO, 'Les relances SMS partent vers les familles en retard, une seule fois pendant le délai ; le secrétariat ne relance pas', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  refuse(await appel(role(e, 'SECRETARIAT'), 'POST', '/scolarite/relances', undefined, { params: { classeId: A.classeId } }),
    'Le secrétariat ne relance pas');
  const intendant = role(e, 'INTENDANT');
  const r1 = await exiger(intendant, 'POST', '/scolarite/relances', undefined, { params: { classeId: A.classeId } });
  vrai(r1.envoyees >= 1, `Au moins l’élève de test (jamais relancé, en retard) : ${JSON.stringify(r1)}`);
  const r2 = await exiger(intendant, 'POST', '/scolarite/relances', undefined, { params: { classeId: A.classeId } });
  egal(r2.envoyees, 0, 'Aucune nouvelle relance pendant le délai');
  vrai(r2.dejaRelancees >= r1.envoyees, 'Les familles relancées sont comptées « déjà relancées »');
  const sms = await notification(ctx, `relance:${A.inscriptionId}:`);
  vrai(sms, 'SMS de relance de l’élève de test en file');
  vrai(sms.message.includes('retard'), 'Le SMS parle du retard');
  sansTexte({ donnees: sms, methode: 'GET', chemin: '/notifications' }, [A.telephone], 'Numéro masqué dans le suivi des SMS');
  refuse(await appel(intendant, 'GET', '/notifications'), 'Le suivi des SMS est réservé à l’administrateur et au surveillant');
  return `${r1.envoyees} SMS, ${r1.sansContact} sans contact, ${r1.dejaRelancees} déjà relancées`;
});

scenario('SCO-15', SCO, 'Les statistiques officielles reprennent le recouvrement de chaque classe et se téléchargent en Excel ; enseignant et parent refusés', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  const rapport = await exiger(role(e, 'INTENDANT'), 'GET', `/annees/${e.anneeId}/statistiques`);
  const etatClasse = await exiger(role(e, 'INTENDANT'), 'GET', `/classes/${A.classeId}/scolarite`);
  const ligne = rapport.recouvrement.find((x) => x.classe === etatClasse.classeCode);
  vrai(ligne, `Ligne de recouvrement de ${etatClasse.classeCode}`);
  egal(ligne.duFamilles, etatClasse.totalFamille, 'Dû familles = état de la classe');
  egal(ligne.payeFamilles, etatClasse.payeFamille, 'Payé familles = état de la classe');
  egal(rapport.recouvrementTotal.duFamilles, somme(rapport.recouvrement, (x) => x.duFamilles), 'Total de l’établissement');
  vrai(rapport.effectifTotal.garcons + rapport.effectifTotal.filles > 0, 'Effectif total');
  vrai(rapport.recouvrement.length === rapport.classes, 'Une ligne de recouvrement par classe');
  const xlsx = await exiger(role(e, 'SECRETARIAT'), 'GET', `/annees/${e.anneeId}/statistiques/excel`, undefined, { fichier: true });
  egal(xlsx.octets.subarray(0, 2).toString('latin1'), 'PK', 'Classeur Excel');
  refuse(await appel(enseignant(e), 'GET', `/annees/${e.anneeId}/statistiques`), 'Enseignant');
  refuse(await appel(A.parent, 'GET', `/annees/${e.anneeId}/statistiques`), 'Parent');
  return `familles ${rapport.recouvrementTotal.tauxFamilles ?? '—'} %, organismes ${rapport.recouvrementTotal.tauxOrganismes ?? '—'} %`;
});

scenario('SCO-16', SCO, 'Le frais facultatif de test se supprime (intendance seulement) et disparaît de la liste', async (ctx) => {
  const f = etat(ctx).frais;
  if (!f) ignorer('Frais facultatif de test absent (SCO-04)');
  const e = ecole(ctx);
  refuse(await appel(role(e, 'SECRETARIAT'), 'DELETE', `/frais/${f.id}`), 'Le secrétariat ne supprime pas de frais');
  statut(await appel(role(e, 'INTENDANT'), 'DELETE', `/frais/${f.id}`), 204);
  const liste = await exiger(role(e, 'INTENDANT'), 'GET', `/annees/${e.anneeId}/frais`);
  vrai(!liste.some((x) => x.id === f.id), 'Frais supprimé de la liste');
  statut(await appel(role(e, 'INTENDANT'), 'DELETE', `/frais/${f.id}`), 404);
  etat(ctx).frais = null;
});

// ===================================================================== Guichet et reçus

scenario('GUI-01', GUI, 'L’intendant encaisse en espèces : reçu numéroté avec code de vérification, situation à jour, SMS du reçu en file', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const intendant = role(ecole(ctx), 'INTENDANT');
  if (!(await moyensGuichet(ctx)).includes('ESPECES')) ignorer('Espèces non acceptées au guichet de l’établissement');
  const avant = await situation(intendant, A.inscriptionId);
  const corps = paiement(10000);
  const r = await appel(intendant, 'POST', `/inscriptions/${A.inscriptionId}/paiements`, corps);
  statut(r, 201);
  const p = r.donnees;
  vrai(/^\d{4}-\d{6}$/.test(p.recuNumero ?? ''), `Numéro de reçu AAAA-NNNNNN (${p.recuNumero})`);
  vrai(/^[A-Z0-9]{10}$/.test(p.recuCode ?? ''), 'Code de vérification de 10 caractères');
  egal(p.annule, false, 'Paiement valide');
  const s = await situation(intendant, A.inscriptionId);
  egal(s.payeFamille, avant.payeFamille + 10000, 'Payé famille');
  egal(s.resteFamille, avant.resteFamille - 10000, 'Reste famille');
  vrai(s.paiements.some((x) => x.id === p.id), 'Paiement dans la situation');
  const sms = await notification(ctx, `paiement:${p.id}`);
  vrai(sms && sms.message.includes(p.recuNumero), 'SMS du reçu au contact prioritaire');
  etat(ctx).gui = { p1: p, corps1: corps };
  return `reçu ${p.recuNumero}`;
});

scenario('GUI-02', GUI, 'Un double envoi avec la même clé d’idempotence ne crée qu’un paiement ; la clé ne sert pas pour un autre montant', async (ctx) => {
  const g = etat(ctx).gui;
  if (!g?.p1) ignorer('Premier encaissement absent (GUI-01)');
  const { A } = await elevesDeTest(ctx);
  const intendant = role(ecole(ctx), 'INTENDANT');
  const avant = await situation(intendant, A.inscriptionId);
  const r = await appel(intendant, 'POST', `/inscriptions/${A.inscriptionId}/paiements`, g.corps1);
  statut(r, [200, 201]);
  egal(r.donnees.id, g.p1.id, 'Même paiement renvoyé');
  egal(r.donnees.recuNumero, g.p1.recuNumero, 'Même reçu');
  const s = await situation(intendant, A.inscriptionId);
  egal(s.payeFamille, avant.payeFamille, 'Rien de plus encaissé');
  statut(await appel(intendant, 'POST', `/inscriptions/${A.inscriptionId}/paiements`, { ...g.corps1, montant: 9000 }), 409, 'CLE_DEJA_UTILISEE');
});

scenario('GUI-03', GUI, 'Le guichet refuse plus que le reste dû, un montant nul et un paiement sans moyen ni payeur', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const intendant = role(ecole(ctx), 'INTENDANT');
  const s = await situation(intendant, A.inscriptionId);
  const chemin = `/inscriptions/${A.inscriptionId}/paiements`;
  const moyen = await moyenAccepte(ctx, ['ESPECES', 'CHEQUE', 'VIREMENT']);
  statut(await appel(intendant, 'POST', chemin, paiement(s.resteFamille + 1, moyen)), 409, 'MONTANT_SUPERIEUR_AU_RESTE');
  statut(await appel(intendant, 'POST', chemin, paiement(0, moyen)), 400);
  statut(await appel(intendant, 'POST', chemin, paiement(-500, moyen)), 400);
  statut(await appel(intendant, 'POST', chemin, { ...paiement(1000, moyen), moyen: null }), 400);
  statut(await appel(intendant, 'POST', chemin, { ...paiement(1000, moyen), payeur: null }), 400);
  egal((await situation(intendant, A.inscriptionId)).payeFamille, s.payeFamille, 'Aucun paiement enregistré');
  return `reste ${s.resteFamille} FCFA`;
});

scenario('GUI-04', GUI, 'Hors espèces, la référence (chèque, transaction, virement) est obligatoire', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const intendant = role(ecole(ctx), 'INTENDANT');
  const acceptes = (await moyensGuichet(ctx)).filter((m) => m !== 'ESPECES');
  if (!acceptes.length) ignorer('Seules les espèces sont acceptées au guichet');
  for (const moyen of acceptes.slice(0, 3)) {
    statut(await appel(intendant, 'POST', `/inscriptions/${A.inscriptionId}/paiements`, paiement(1000, moyen, { referenceExterne: ' ' })), 400);
  }
  return acceptes.slice(0, 3).join(', ');
});

scenario('GUI-05', GUI, 'La date d’un paiement n’est jamais future ni antérieure à l’année scolaire', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const intendant = role(ecole(ctx), 'INTENDANT');
  const moyen = await moyenAccepte(ctx, ['ESPECES', 'CHEQUE', 'VIREMENT']);
  const chemin = `/inscriptions/${A.inscriptionId}/paiements`;
  statut(await appel(intendant, 'POST', chemin, paiement(1000, moyen, { datePaiement: ajouterJours(ctx.aujourdhui, 2) })), 400);
  statut(await appel(intendant, 'POST', chemin, paiement(1000, moyen, { datePaiement: ajouterJours(ctx.etat.annee.rentree, -200) })), 400);
});

scenario('GUI-06', GUI, 'Seules l’intendance et l’administration encaissent : secrétariat, enseignant, parent et autre établissement refusés', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  const chemin = `/inscriptions/${A.inscriptionId}/paiements`;
  refuse(await appel(role(e, 'SECRETARIAT'), 'POST', chemin, paiement(1000)), 'Secrétariat (lecture seule)');
  refuse(await appel(enseignant(e), 'POST', chemin, paiement(1000)), 'Enseignant');
  refuse(await appel(A.parent, 'POST', chemin, paiement(1000)), 'Parent');
  refuse(await appel(autreEcole(ctx), 'POST', chemin, paiement(1000)), 'Intendant d’un autre établissement');
});

scenario('GUI-07', GUI, 'Un paiement hors espèces s’enregistre avec sa référence', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const g = etat(ctx).gui ?? (etat(ctx).gui = {});
  const intendant = role(ecole(ctx), 'INTENDANT');
  const moyen = await moyenAccepte(ctx, ['CHEQUE', 'VIREMENT', 'ORANGE_MONEY', 'MOOV_MONEY']);
  const corps = paiement(3000, moyen, { referenceExterne: `CH-${suffixe().toUpperCase()}` });
  const p = await exiger(intendant, 'POST', `/inscriptions/${A.inscriptionId}/paiements`, corps);
  egal(p.moyen, moyen, 'Moyen');
  egal(p.referenceExterne, corps.referenceExterne, 'Référence conservée');
  g.p2 = p;
  return `${moyen} ${p.referenceExterne}`;
});

scenario('GUI-08', GUI, 'Le reçu PDF s’imprime à l’intendance et au secrétariat, et le parent télécharge celui de son enfant seulement', async (ctx) => {
  const p = etat(ctx).gui?.p1;
  if (!p) ignorer('Encaissement absent (GUI-01)');
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  for (const qui of [role(e, 'INTENDANT'), role(e, 'SECRETARIAT')]) {
    const r = await exiger(qui, 'GET', `/paiements/${p.id}/recu`, undefined, { fichier: true });
    vrai(r.type.includes('application/pdf'), `PDF attendu (${r.type})`);
    egal(r.octets.subarray(0, 4).toString('latin1'), '%PDF', 'Signature PDF');
  }
  refuse(await appel(enseignant(e), 'GET', `/paiements/${p.id}/recu`, undefined, { fichier: true }), 'Enseignant');
  const rp = await exiger(A.parent, 'GET', `/espace-parent/paiements/${p.id}/recu`, undefined, { fichier: true });
  egal(rp.octets.subarray(0, 4).toString('latin1'), '%PDF', 'Reçu PDF du parent');
  refuse(await appel(A.parent, 'GET', `/paiements/${p.id}/recu`, undefined, { fichier: true }), 'Le parent n’utilise pas l’écran du personnel');
  refuse(await appel(familleDemo(e).parent, 'GET', `/espace-parent/paiements/${p.id}/recu`, undefined, { fichier: true }),
    'Un autre parent ne télécharge pas ce reçu');
  refuse(await appel(autreEcole(ctx), 'GET', `/paiements/${p.id}/recu`, undefined, { fichier: true }), 'Autre établissement');
});

scenario('GUI-09', GUI, 'N’importe qui vérifie un reçu sans connexion grâce à son code : seulement ce qui figure sur le reçu, sans téléphone', async (ctx) => {
  const p = etat(ctx).gui?.p1;
  if (!p) ignorer('Encaissement absent (GUI-01)');
  const { A } = await elevesDeTest(ctx);
  const r = await appel(null, 'GET', `/verification/recus/${p.recuCode}`);
  statut(r, 200);
  const v = r.donnees;
  egal(v.numero, p.recuNumero, 'Numéro du reçu');
  egal(v.montant, p.montant, 'Montant');
  egal(v.matricule, A.matricule, 'Matricule');
  egal(v.payeur, 'FAMILLE', 'Payeur');
  egal(v.annule, false, 'Reçu valide');
  vrai(v.etablissement, 'Nom de l’établissement');
  const autorises = new Set(['etablissement', 'numero', 'eleve', 'matricule', 'montant', 'payeur', 'datePaiement', 'emisLe', 'annule']);
  const enTrop = Object.keys(v).filter((k) => !autorises.has(k));
  egal(enTrop, [], 'Champs hors du reçu');
  sansTexte(r, [A.telephone, `+226${A.telephone}`, p.id, A.inscriptionId], 'Données absentes du reçu papier');
  // Saisie approximative : minuscules et tiret
  const saisi = `${p.recuCode.slice(0, 5)}-${p.recuCode.slice(5)}`.toLowerCase();
  egal((await exiger(null, 'GET', `/verification/recus/${saisi}`)).numero, p.recuNumero, 'Code saisi en minuscules avec un tiret');
  statut(await appel(null, 'GET', '/verification/recus/ZZZZZZZZZZ'), 404);
  statut(await appel(null, 'GET', '/verification/recus/ABC'), 404);
});

scenario('GUI-10', GUI, 'Un paiement s’annule avec un motif : il ne compte plus, le reçu reste marqué annulé, une seconde annulation est refusée', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  const intendant = role(e, 'INTENDANT');
  const moyen = await moyenAccepte(ctx, ['ESPECES', 'CHEQUE', 'VIREMENT']);
  const corps = paiement(2000, moyen);
  const p = await exiger(intendant, 'POST', `/inscriptions/${A.inscriptionId}/paiements`, corps);
  const avant = await situation(intendant, A.inscriptionId);
  statut(await appel(intendant, 'POST', `/paiements/${p.id}/annulation`, { motif: ' ' }), 400);
  refuse(await appel(role(e, 'SECRETARIAT'), 'POST', `/paiements/${p.id}/annulation`, { motif: 'Saisi deux fois' }), 'Secrétariat');
  const annule = await exiger(intendant, 'POST', `/paiements/${p.id}/annulation`, { motif: 'Reçu saisi deux fois (recette)' });
  egal(annule.annule, true, 'Paiement annulé');
  egal(annule.motifAnnulation, 'Reçu saisi deux fois (recette)', 'Motif conservé');
  const s = await situation(intendant, A.inscriptionId);
  egal(s.payeFamille, avant.payeFamille - 2000, 'Le paiement annulé ne compte plus');
  statut(await appel(intendant, 'POST', `/paiements/${p.id}/annulation`, { motif: 'Encore' }), 409, 'PAIEMENT_DEJA_ANNULE');
  statut(await appel(intendant, 'POST', `/inscriptions/${A.inscriptionId}/paiements`, corps), 409, 'CLE_DEJA_UTILISEE');
  const pdf = await exiger(intendant, 'GET', `/paiements/${p.id}/recu`, undefined, { fichier: true });
  egal(pdf.octets.subarray(0, 4).toString('latin1'), '%PDF', 'Le reçu annulé reste imprimable');
  egal((await exiger(null, 'GET', `/verification/recus/${p.recuCode}`)).annule, true, 'La vérification publique le dit annulé');
  (etat(ctx).gui ??= {}).p3 = annule;
  const sms = await notification(ctx, `paiement:${p.id}`);
  return `reçu ${p.recuNumero} annulé${sms ? ` ; SMS du reçu ${sms.statut === 'ANNULE' ? 'retiré de la file' : `déjà ${sms.statut}`}` : ''}`;
});

scenario('GUI-11', GUI, 'Le journal de caisse d’une période liste les paiements non annulés, les totaux par moyen et l’élève de chaque paiement', async (ctx) => {
  const g = etat(ctx).gui;
  if (!g?.p1) ignorer('Encaissement absent (GUI-01)');
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  const intendant = role(e, 'INTENDANT');
  const params = { du: ajouterJours(ctx.aujourdhui, -1), au: ajouterJours(ctx.aujourdhui, 1) };
  const j = await exiger(intendant, 'GET', '/paiements', undefined, { params });
  const ids = j.paiements.map((x) => x.id);
  vrai(ids.includes(g.p1.id), 'Encaissement en espèces au journal');
  if (g.p2) vrai(ids.includes(g.p2.id), 'Paiement avec référence au journal');
  if (g.p3) vrai(!ids.includes(g.p3.id), 'Le paiement annulé n’est pas au journal');
  vrai(j.paiements.every((x) => !x.annule), 'Aucun paiement annulé');
  egal(j.total, somme(j.paiements, (x) => x.montant), 'Total = somme des paiements');
  egal(somme(j.parMoyen, (x) => x.montant), j.total, 'Somme des totaux par moyen = total');
  egal(somme(j.parMoyen, (x) => x.nombre), j.paiements.length, 'Nombre de paiements par moyen');
  vrai(j.parMoyen.some((x) => x.moyen === g.p1.moyen && x.nombre >= 1), 'Total des espèces');
  egal(j.eleves?.[A.inscriptionId]?.matricule, A.matricule, 'Élève du paiement (matricule)');
  statut(await appel(intendant, 'GET', '/paiements', undefined, { params: { du: ctx.aujourdhui, au: ajouterJours(ctx.aujourdhui, -1) } }), 400);
  statut(await appel(intendant, 'GET', '/paiements', undefined, { params: { du: ajouterJours(ctx.aujourdhui, -400), au: ctx.aujourdhui } }), 400);
  refuse(await appel(role(e, 'SECRETARIAT'), 'GET', '/paiements', undefined, { params }), 'Le secrétariat ne tient pas la caisse');
  const j3 = await exiger(autreEcole(ctx), 'GET', '/paiements', undefined, { params });
  vrai(!j3.paiements.some((x) => x.id === g.p1.id), 'Le journal d’un autre établissement ne contient pas ce paiement');
  return `${j.paiements.length} paiements, ${j.total} FCFA : ${j.parMoyen.map((x) => `${x.moyen} ${x.montant}`).join(', ')}`;
});

// ===================================================================== Moyens de paiement et virements

scenario('PAI-01', PAI, 'Les moyens de paiement de l’établissement se lisent à l’intendance, à la direction et au secrétariat ; ni enseignant ni parent', async (ctx) => {
  const e = ecole(ctx);
  const p = await parametresInitiaux(ctx);
  for (const champ of ['moyensGuichet', 'mobileMoneyParents', 'operateurs', 'virementParents', 'comptes', 'mobileMoneyDisponible']) {
    vrai(champ in p, `Champ ${champ}`);
  }
  statut(await appel(role(e, 'INTENDANT'), 'GET', '/parametres/paiement'), 200);
  statut(await appel(role(e, 'SECRETARIAT'), 'GET', '/parametres/paiement'), 200);
  refuse(await appel(enseignant(e), 'GET', '/parametres/paiement'), 'Enseignant');
  refuse(await appel(familleDemo(e).parent, 'GET', '/parametres/paiement'), 'Parent');
  return `guichet : ${p.moyensGuichet.join(', ')} ; Mobile Money parents ${p.mobileMoneyParents ? 'oui' : 'non'} ; virement ${p.virementParents ? 'oui' : 'non'} ; ${p.comptes.length} compte(s)`;
});

scenario('PAI-02', PAI, 'Seul l’administrateur règle les moyens de paiement, et les saisies incohérentes sont refusées sans rien changer', async (ctx) => {
  const e = ecole(ctx);
  const initial = await parametresInitiaux(ctx);
  const adm = role(e, 'ADMIN_ECOLE');
  refuse(await appel(role(e, 'INTENDANT'), 'PUT', '/parametres/paiement', { moyensGuichet: ['ESPECES'] }), 'Intendant');
  refuse(await appel(role(e, 'SECRETARIAT'), 'PUT', '/parametres/paiement', { moyensGuichet: ['ESPECES'] }), 'Secrétariat');
  statut(await appel(adm, 'PUT', '/parametres/paiement', { moyensGuichet: [] }), 400);
  statut(await appel(adm, 'PUT', '/parametres/paiement', { operateurs: ['ESPECES'] }), 400);
  statut(await appel(adm, 'PUT', '/parametres/paiement', { mobileMoneyParents: true, operateurs: [] }), 400);
  statut(await appel(adm, 'PUT', '/parametres/paiement', { consigneVirement: 'x'.repeat(301) }), 400);
  vrai(memesParametres(await exiger(adm, 'GET', '/parametres/paiement'), initial), 'Réglages inchangés après les refus');
});

scenario('PAI-03', PAI, 'Un moyen retiré du guichet est refusé à l’encaissement, puis le réglage d’origine revient', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  const intendant = role(e, 'INTENDANT');
  const avant = await exiger(intendant, 'GET', '/parametres/paiement');
  await avecParametres(ctx, { moyensGuichet: ['ESPECES', 'VIREMENT'] }, async (p) => {
    egal([...p.moyensGuichet].sort(), ['ESPECES', 'VIREMENT'], 'Moyens du guichet');
    statut(await appel(intendant, 'POST', `/inscriptions/${A.inscriptionId}/paiements`, paiement(1000, 'CHEQUE')), 409, 'MOYEN_NON_ACCEPTE');
    statut(await appel(intendant, 'POST', `/inscriptions/${A.inscriptionId}/paiements`, paiement(1000, 'MOOV_MONEY')), 409, 'MOYEN_NON_ACCEPTE');
  });
  vrai(memesParametres(await exiger(intendant, 'GET', '/parametres/paiement'), avant), 'Réglage d’origine rétabli');
});

scenario('PAI-04', PAI, 'L’administrateur enregistre un compte bancaire (numéro normalisé, unique) ; l’intendant ne le peut pas et les saisies invalides sont refusées', async (ctx) => {
  const e = ecole(ctx);
  const adm = role(e, 'ADMIN_ECOLE');
  refuse(await appel(role(e, 'INTENDANT'), 'POST', '/comptes-bancaires', { ...COMPTE_TEST, numero: `BF99 ${suffixe()}` }), 'Intendant');
  statut(await appel(adm, 'POST', '/comptes-bancaires', { ...COMPTE_TEST, numero: 'BF99#RECETTE' }), 400);
  statut(await appel(adm, 'POST', '/comptes-bancaires', { ...COMPTE_TEST, numero: `BF99 ${suffixe()}`, codeSwift: 'ABC' }), 400);
  statut(await appel(adm, 'POST', '/comptes-bancaires', { ...COMPTE_TEST, numero: `BF99 ${suffixe()}`, banque: ' ' }), 400);
  const c = await compteDeTest(ctx);
  const cree = etat(ctx).compteCree;
  if (cree) {
    egal(cree.numero, COMPTE_TEST.numero, 'Numéro normalisé (majuscules, espaces simples)');
    egal(cree.codeSwift, COMPTE_TEST.codeSwift, 'Code SWIFT');
    egal(cree.actif, false, 'Compte créé inactif à la demande');
  }
  statut(await appel(adm, 'POST', '/comptes-bancaires', { ...COMPTE_TEST, numero: 'bf99 recette 000001' }), 409, 'COMPTE_EXISTANT');
  return cree ? 'compte de test créé' : `compte de test retrouvé (${c.actif ? 'actif' : 'inactif'})`;
});

scenario('PAI-05', PAI, 'Tant que le virement n’est pas proposé, le parent ne peut pas en déclarer ; sans compte actif, il ne peut pas être proposé', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  const adm = role(e, 'ADMIN_ECOLE');
  await compteDeTest(ctx);
  const p = await exiger(adm, 'GET', '/parametres/paiement');
  if (p.virementParents) ignorer('L’établissement propose déjà le virement aux parents');
  const moyens = await exiger(A.parent, 'GET', `/espace-parent/inscriptions/${A.inscriptionId}/moyens-paiement`);
  egal(moyens.virement, false, 'Virement non proposé au parent');
  egal(moyens.comptes, [], 'Aucun compte affiché');
  statut(await declarer(A.parent, A.inscriptionId, {
    compteId: (p.comptes[0] ?? etat(ctx).compte).id, montant: 1000, dateVirement: ctx.aujourdhui, reference: `VIR-${suffixe()}`,
  }), 409, 'VIREMENT_NON_PROPOSE');
  if (!p.comptes.some((c) => c.actif)) {
    statut(await appel(adm, 'PUT', '/parametres/paiement', { virementParents: true }), 409, 'SANS_COMPTE_BANCAIRE');
    return 'SANS_COMPTE_BANCAIRE vérifié';
  }
  return 'des comptes actifs existent : SANS_COMPTE_BANCAIRE non vérifiable';
});

scenario('PAI-06', PAI, 'Quand le virement est proposé, le parent voit les comptes actifs, la consigne et la référence à indiquer ; pas pour l’enfant d’un autre', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  const compte = await compteDeTest(ctx);
  const consigne = 'Indiquez la référence ci-dessous dans le motif du virement (recette).';
  await avecParametres(ctx, { virementParents: true, consigneVirement: consigne }, async () => {
    const m = await exiger(A.parent, 'GET', `/espace-parent/inscriptions/${A.inscriptionId}/moyens-paiement`);
    egal(m.virement, true, 'Virement proposé');
    vrai(m.comptes.some((c) => c.id === compte.id), 'Le compte de test est affiché');
    vrai(m.comptes.every((c) => c.actif), 'Seulement des comptes actifs');
    egal(m.consigneVirement, consigne, 'Consigne');
    vrai(m.referenceAIndiquer?.startsWith(`SCOL ${A.matricule} `), `Référence SCOL <matricule> <NOM> (${m.referenceAIndiquer})`);
    refuse(await appel(familleDemo(e).parent, 'GET', `/espace-parent/inscriptions/${A.inscriptionId}/moyens-paiement`), 'Autre parent');
    refuse(await appel(enseignant(e), 'GET', `/espace-parent/inscriptions/${A.inscriptionId}/moyens-paiement`), 'Enseignant');
  }, { compte });
});

scenario('PAI-07', PAI, 'Le parent déclare un virement avec la photo de l’avis ; le doublon et le dépassement du reste (virements en attente déduits) sont refusés', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const compte = await compteDeTest(ctx);
  const s = etat(ctx);
  const ref1 = `VIR-${suffixe().toUpperCase()}`;
  const ref2 = `VIR-${suffixe().toUpperCase()}`;
  await avecParametres(ctx, { virementParents: true }, async () => {
    const cleV1 = cle();
    const champs = { compteId: compte.id, montant: 20000, dateVirement: ctx.aujourdhui, reference: ref1, cleIdempotence: cleV1, fichier: fichierPng() };
    const r = await declarer(A.parent, A.inscriptionId, champs);
    statut(r, 201);
    const v1 = r.donnees;
    egal(v1.statut, 'EN_ATTENTE', 'Statut');
    egal(v1.preuve, true, 'Preuve jointe');
    egal(v1.matricule, A.matricule, 'Élève');
    // Réseau coupé : le même envoi renvoie la même déclaration
    const bis = await declarer(A.parent, A.inscriptionId, { ...champs, fichier: fichierPng() });
    statut(bis, [200, 201]);
    egal(bis.donnees.id, v1.id, 'Même déclaration pour la même clé');
    statut(await declarer(A.parent, A.inscriptionId, { compteId: compte.id, montant: 1000, dateVirement: ctx.aujourdhui, reference: ref1.toLowerCase() }),
      409, 'VIREMENT_DEJA_DECLARE');
    const sit = await situation(role(ecole(ctx), 'INTENDANT'), A.inscriptionId);
    const mes = await exiger(A.parent, 'GET', `/espace-parent/inscriptions/${A.inscriptionId}/virements`);
    const enAttente = somme(mes.filter((x) => x.statut === 'EN_ATTENTE'), (x) => x.montant);
    vrai(enAttente >= 20000, 'La déclaration est en attente');
    const montant = sit.resteFamille - enAttente + 1;
    vrai(montant <= sit.resteFamille, 'Montant inférieur au reste brut : seul le virement en attente le rend excessif');
    statut(await declarer(A.parent, A.inscriptionId, { compteId: compte.id, montant, dateVirement: ctx.aujourdhui, reference: `VIR-${suffixe()}` }),
      409, 'MONTANT_SUPERIEUR_AU_RESTE');
    const v2 = await declarer(A.parent, A.inscriptionId, { compteId: compte.id, montant: 5000, dateVirement: ajouterJours(ctx.aujourdhui, -2), reference: ref2 });
    statut(v2, 201);
    egal(v2.donnees.preuve, false, 'Déclaration sans preuve');
    s.virements = { v1, v2: v2.donnees, ref2 };
  }, { compte });
  return `${ref1} (20 000 FCFA, avec preuve) et ${ref2} (5 000 FCFA)`;
});

scenario('PAI-08', PAI, 'Une déclaration de virement invalide est refusée : preuve qui n’est pas une image, date future ou trop ancienne, référence vide, montant nul, compte inconnu, enfant d’un autre', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const e = ecole(ctx);
  const compte = await compteDeTest(ctx);
  await avecParametres(ctx, { virementParents: true }, async () => {
    const base = { compteId: compte.id, montant: 1000, dateVirement: ctx.aujourdhui, reference: `VIR-${suffixe()}` };
    statut(await declarer(A.parent, A.inscriptionId, { ...base, fichier: new Blob(['pas une image'], { type: 'image/png' }) }), 400);
    statut(await declarer(A.parent, A.inscriptionId, { ...base, dateVirement: ajouterJours(ctx.aujourdhui, 1) }), 400);
    statut(await declarer(A.parent, A.inscriptionId, { ...base, dateVirement: ajouterJours(ctx.aujourdhui, -400) }), 400);
    statut(await declarer(A.parent, A.inscriptionId, { ...base, reference: ' ' }), 400);
    statut(await declarer(A.parent, A.inscriptionId, { ...base, montant: 0 }), 400);
    statut(await declarer(A.parent, A.inscriptionId, { ...base, compteId: cle() }), 404);
    refuse(await declarer(familleDemo(e).parent, A.inscriptionId, base), 'Un autre parent ne déclare pas pour cet enfant');
  }, { compte });
});

scenario('PAI-09', PAI, 'L’intendance retrouve les virements en attente avec leur preuve ; ni parent ni secrétariat ni autre établissement', async (ctx) => {
  const v = etat(ctx).virements;
  if (!v) ignorer('Déclarations absentes (PAI-07)');
  const e = ecole(ctx);
  const intendant = role(e, 'INTENDANT');
  const enAttente = await exiger(intendant, 'GET', '/virements');
  const l1 = enAttente.find((x) => x.id === v.v1.id);
  vrai(l1, 'Le virement déclaré est dans la liste « en attente »');
  vrai(l1.eleve && l1.matricule && l1.compte, 'Élève, matricule et compte crédité affichés');
  vrai((await exiger(intendant, 'GET', '/virements', undefined, { params: { statut: 'TOUS' } })).some((x) => x.id === v.v1.id), 'Statut TOUS');
  vrai(!(await exiger(intendant, 'GET', '/virements', undefined, { params: { statut: 'VALIDE' } })).some((x) => x.id === v.v1.id), 'Pas encore validé');
  const preuve = await exiger(intendant, 'GET', `/virements/${v.v1.id}/preuve`, undefined, { fichier: true });
  vrai(preuve.type.startsWith('image/png'), `Preuve PNG (${preuve.type})`);
  egal(preuve.octets.subarray(1, 4).toString('latin1'), 'PNG', 'Signature PNG');
  statut(await appel(intendant, 'GET', `/virements/${v.v2.id}/preuve`, undefined, { fichier: true }), 404);
  refuse(await appel(familleDemo(e).parent, 'GET', '/virements'), 'Parent');
  refuse(await appel(role(e, 'SECRETARIAT'), 'GET', '/virements'), 'Secrétariat');
  refuse(await appel(role(e, 'SECRETARIAT'), 'GET', `/virements/${v.v1.id}/preuve`, undefined, { fichier: true }), 'Secrétariat (preuve)');
  const autre = autreEcole(ctx);
  vrai(!(await exiger(autre, 'GET', '/virements', undefined, { params: { statut: 'TOUS' } })).some((x) => x.id === v.v1.id),
    'Invisible d’un autre établissement');
  refuse(await appel(autre, 'GET', `/virements/${v.v1.id}/preuve`, undefined, { fichier: true }), 'Preuve d’un autre établissement');
  refuse(await appel(autre, 'POST', `/virements/${v.v1.id}/validation`, {}), 'Validation par un autre établissement');
});

scenario('PAI-10', PAI, 'L’intendance valide un virement avec le montant réellement reçu : paiement VIREMENT, reçu et SMS ; un virement traité ne se retraite plus', async (ctx) => {
  const v = etat(ctx).virements;
  if (!v) ignorer('Déclarations absentes (PAI-07)');
  const { A } = await elevesDeTest(ctx);
  const intendant = role(ecole(ctx), 'INTENDANT');
  refuse(await appel(A.parent, 'POST', `/virements/${v.v1.id}/validation`, { montantRecu: 20000 }), 'Le parent ne valide pas');
  const avant = await situation(intendant, A.inscriptionId);
  statut(await appel(intendant, 'POST', `/virements/${v.v1.id}/validation`, { montantRecu: 0 }), 400);
  const valide = await exiger(intendant, 'POST', `/virements/${v.v1.id}/validation`, { montantRecu: 19500 });
  egal(valide.statut, 'VALIDE', 'Statut');
  egal(valide.montantValide, 19500, 'Montant reçu');
  vrai(valide.recuNumero && valide.paiementId, 'Reçu et paiement');
  const s = await situation(intendant, A.inscriptionId);
  egal(s.payeFamille, avant.payeFamille + 19500, 'Payé famille');
  const p = s.paiements.find((x) => x.id === valide.paiementId);
  vrai(p && p.moyen === 'VIREMENT' && p.referenceExterne === v.v1.reference, 'Paiement VIREMENT avec la référence déclarée');
  statut(await appel(intendant, 'POST', `/virements/${v.v1.id}/validation`, {}), 409, 'VIREMENT_DEJA_TRAITE');
  statut(await appel(intendant, 'POST', `/virements/${v.v1.id}/refus`, { motif: 'Trop tard' }), 409, 'VIREMENT_DEJA_TRAITE');
  const sms = await notification(ctx, `paiement:${valide.paiementId}`);
  vrai(sms && sms.message.includes(valide.recuNumero), 'SMS du reçu au parent');
  return `reçu ${valide.recuNumero}`;
});

scenario('PAI-11', PAI, 'Un virement refusé exige un motif transmis par SMS ; le parent voit le refus et peut déclarer à nouveau la même opération', async (ctx) => {
  const v = etat(ctx).virements;
  if (!v) ignorer('Déclarations absentes (PAI-07)');
  const { A } = await elevesDeTest(ctx);
  const intendant = role(ecole(ctx), 'INTENDANT');
  const compte = await compteDeTest(ctx);
  statut(await appel(intendant, 'POST', `/virements/${v.v2.id}/refus`, { motif: ' ' }), 400);
  const motif = 'Introuvable sur le relevé (recette)';
  const refuseV = await exiger(intendant, 'POST', `/virements/${v.v2.id}/refus`, { motif });
  egal(refuseV.statut, 'REFUSE', 'Statut');
  egal(refuseV.motifRefus, motif, 'Motif');
  const sms = await notification(ctx, `virement-refuse:${v.v2.id}`);
  vrai(sms && sms.message.includes(motif), 'SMS au parent avec le motif');
  const mes = await exiger(A.parent, 'GET', `/espace-parent/inscriptions/${A.inscriptionId}/virements`);
  egal(mes.find((x) => x.id === v.v2.id)?.motifRefus, motif, 'Le parent voit le motif');
  egal(mes.find((x) => x.id === v.v1.id)?.statut, 'VALIDE', 'Le parent voit la validation');
  refuse(await appel(familleDemo(ecole(ctx)).parent, 'GET', `/espace-parent/inscriptions/${A.inscriptionId}/virements`), 'Autre parent');
  await avecParametres(ctx, { virementParents: true }, async () => {
    const r = await declarer(A.parent, A.inscriptionId, { compteId: compte.id, montant: 5000, dateVirement: ajouterJours(ctx.aujourdhui, -2), reference: v.ref2 });
    statut(r, 201);
    // Nettoyage : la nouvelle déclaration de test est refusée aussitôt
    await exiger(intendant, 'POST', `/virements/${r.donnees.id}/refus`, { motif: 'Déclaration de test de la recette' });
  }, { compte });
});

scenario('PAI-12', PAI, 'Le dernier compte proposé aux parents ne se désactive pas tant que le virement est proposé', async (ctx) => {
  const compte = await compteDeTest(ctx);
  const adm = role(ecole(ctx), 'ADMIN_ECOLE');
  await avecParametres(ctx, { virementParents: true }, async (p) => {
    const actifs = p.comptes.filter((c) => c.actif);
    if (actifs.length !== 1) ignorer(`${actifs.length} comptes actifs : le compte de test n’est pas le dernier`);
    statut(await basculerCompte(adm, compte, false), 409, 'DERNIER_COMPTE');
  }, { compte });
});

scenario('PAI-13', PAI, 'Après les essais, les moyens de paiement sont ceux du début et le compte de test est inactif', async (ctx) => {
  const s = etat(ctx);
  const adm = role(ecole(ctx), 'ADMIN_ECOLE');
  const initial = await parametresInitiaux(ctx);
  const p = await exiger(adm, 'GET', '/parametres/paiement');
  vrai(memesParametres(p, initial), `Réglages rétablis : ${JSON.stringify(corpsParametres(p))}`);
  if (s.compte) egal(p.comptes.find((c) => c.id === s.compte.id)?.actif, false, 'Compte bancaire de test inactif');
  vrai(!s.erreursRetablissement.length, `Remises en état en échec : ${s.erreursRetablissement.join(' ; ')}`);
});

// ===================================================================== Mobile Money

scenario('MMO-01', MMO, 'La configuration Mobile Money se lit à l’intendance et à la direction sans jamais renvoyer les secrets ; une notification non signée est rejetée', async (ctx) => {
  const e = ecole(ctx);
  const r = await appel(role(e, 'INTENDANT'), 'GET', '/parametres/mobile-money');
  siModuleFerme(r);
  statut(r, 200);
  const c = r.donnees;
  egal(c.urlNotification, `/api/v1/webhooks/mobile-money/${e.id}`, 'URL de notification de l’école');
  for (const interdit of ['cleApi', 'secretWebhook', 'cle_api_chiffree', 'secret_webhook_chiffre']) {
    vrai(!(interdit in c), `Champ ${interdit} jamais renvoyé`);
  }
  // Secrets du peuplement (scripts/demo/peupler-mvp.mjs)
  sansTexte(r, ['demo-cle-api', 'demo-secret-webhook'], 'Secrets du compte marchand');
  statut(await appel(role(e, 'ADMIN_ECOLE'), 'GET', '/parametres/mobile-money'), 200);
  refuse(await appel(role(e, 'SECRETARIAT'), 'GET', '/parametres/mobile-money'), 'Secrétariat');
  refuse(await appel(enseignant(e), 'GET', '/parametres/mobile-money'), 'Enseignant');
  refuse(await appel(familleDemo(e).parent, 'GET', '/parametres/mobile-money'), 'Parent');
  statut(await appel(null, 'POST', `/webhooks/mobile-money/${e.id}`, { reference: 'MM-QUELCONQUE' }), 401);
  return c.agregateur ? `${c.agregateur}, ${c.actif ? 'actif' : 'inactif'}, clé ${c.cleApiDefinie ? 'définie' : 'absente'}` : 'non configuré';
});

scenario('MMO-02', MMO, 'Seul l’administrateur configure le compte marchand ; agrégateur inconnu et identifiant manquant refusés (configuration existante conservée)', async (ctx) => {
  const e = ecole(ctx);
  const adm = role(e, 'ADMIN_ECOLE');
  const avant = await exiger(adm, 'GET', '/parametres/mobile-money');
  refuse(await appel(role(e, 'INTENDANT'), 'PUT', '/parametres/mobile-money', { agregateur: 'SIMULATEUR', identifiantMarchand: 'M1', cleApi: 'a', secretWebhook: 'b' }),
    'L’intendant ne configure pas');
  statut(await appel(adm, 'PUT', '/parametres/mobile-money', { agregateur: 'INCONNU', identifiantMarchand: 'M1', cleApi: 'a', secretWebhook: 'b' }),
    409, 'AGREGATEUR_INCONNU');
  statut(await appel(adm, 'PUT', '/parametres/mobile-money', { agregateur: 'SIMULATEUR', identifiantMarchand: ' ', cleApi: 'a', secretWebhook: 'b' }), 400);
  if (avant.agregateur) {
    const apres = await exiger(adm, 'GET', '/parametres/mobile-money');
    egal([apres.agregateur, apres.identifiantMarchand, apres.actif], [avant.agregateur, avant.identifiantMarchand, avant.actif],
      'Configuration existante inchangée par les refus');
    return `configuration existante (${avant.agregateur}) vérifiée, non remplacée`;
  }
  if (!avant.agregateursDisponibles?.includes('SIMULATEUR')) ignorer('Simulateur non autorisé sur ce serveur : configuration non créée');
  // Pas encore de compte marchand : clés obligatoires, puis création en simulation
  statut(await appel(adm, 'PUT', '/parametres/mobile-money', { agregateur: 'SIMULATEUR', identifiantMarchand: 'RECETTE-LT' }), 400);
  const cleApi = `recette-cle-${suffixe()}`;
  const secret = `recette-secret-${suffixe()}`;
  const r = await appel(adm, 'PUT', '/parametres/mobile-money', { agregateur: 'SIMULATEUR', identifiantMarchand: 'RECETTE-LT', cleApi, secretWebhook: secret, actif: true });
  statut(r, 200);
  egal([r.donnees.cleApiDefinie, r.donnees.secretWebhookDefini, r.donnees.actif], [true, true, true], 'Clés enregistrées, compte actif');
  sansTexte(r, [cleApi, secret], 'Secrets renvoyés');
  sansTexte(await appel(role(e, 'INTENDANT'), 'GET', '/parametres/mobile-money'), [cleApi, secret], 'Secrets relus');
  etat(ctx).mmCreeParRecette = true;
  return 'configuration absente : créée par la recette avec l’agrégateur SIMULATEUR (identifiant RECETTE-LT)';
});

scenario('MMO-03', MMO, 'Le parent ne paie en ligne qu’avec les opérateurs retenus par l’établissement, et pas du tout si le Mobile Money n’est pas proposé', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const config = await exigerSimulateur(ctx);
  vrai(config.cleApiDefinie, 'Compte marchand configuré');
  await avecParametres(ctx, OPERATEURS_TEST, async (p) => {
    const m = await exiger(A.parent, 'GET', `/espace-parent/inscriptions/${A.inscriptionId}/moyens-paiement`);
    egal(m.mobileMoney, p.mobileMoneyDisponible, 'Mobile Money proposé si le module est actif et le compte configuré');
    if (m.mobileMoney) egal([...m.operateurs].sort(), OPERATEURS_TEST.operateurs, 'Opérateurs proposés');
    statut(await payerMM(A.parent, A, 1000, 'TELECEL_MONEY'), 409, 'OPERATEUR_NON_PROPOSE');
  });
  await avecParametres(ctx, { mobileMoneyParents: false }, async () => {
    const m = await exiger(A.parent, 'GET', `/espace-parent/inscriptions/${A.inscriptionId}/moyens-paiement`);
    egal(m.mobileMoney, false, 'Mobile Money non proposé');
    statut(await payerMM(A.parent, A, 1000, 'ORANGE_MONEY'), 409, 'MOBILE_MONEY_NON_PROPOSE');
  });
});

scenario('MMO-04', MMO, 'Le parent demande un paiement Orange Money : transaction en attente de confirmation, double clic sans effet, une seule demande à la fois', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  await exigerSimulateur(ctx);
  const e = ecole(ctx);
  const s = etat(ctx);
  await avecParametres(ctx, OPERATEURS_TEST, async () => {
    const cleT1 = cle();
    const r = await payerMM(A.parent, A, 1000, 'ORANGE_MONEY', cleT1);
    statut(r, 202);
    const t1 = r.donnees;
    egal(t1.statut, 'EN_ATTENTE', 'Statut « confirmez sur votre téléphone »');
    egal(t1.montant, 1000, 'Montant');
    vrai(t1.reference, 'Référence de la transaction');
    (s.mm ??= {}).t1 = t1;
    const bis = await payerMM(A.parent, A, 1000, 'ORANGE_MONEY', cleT1);
    statut(bis, 202);
    egal(bis.donnees.id, t1.id, 'Même transaction pour la même clé');
    statut(await payerMM(A.parent, A, 1000, 'ORANGE_MONEY'), 409, 'TRANSACTION_EN_COURS');
    statut(await payerMM(A.parent, A, 50, 'ORANGE_MONEY'), 400);
    refuse(await payerMM(familleDemo(e).parent, A, 1000, 'ORANGE_MONEY'), 'Un autre parent ne paie pas pour cet enfant');
    refuse(await payerMM(role(e, 'INTENDANT'), A, 1000, 'ORANGE_MONEY'), 'L’intendant n’utilise pas l’espace parent');
    // Suivi par le parent (l'écran interroge toutes les 5 secondes)
    egal((await exiger(A.parent, 'GET', `/espace-parent/mobile-money/${t1.id}`)).statut, 'EN_ATTENTE', 'Suivi par le parent');
    refuse(await appel(familleDemo(e).parent, 'GET', `/espace-parent/mobile-money/${t1.id}`), 'Suivi par un autre parent');
  });
});

scenario('MMO-05', MMO, 'Le parent confirme sur son téléphone (simulateur) : paiement Orange Money et reçu enregistrés, visibles du parent', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const t1 = exigerMM(ctx, 't1');
  const intendant = role(ecole(ctx), 'INTENDANT');
  const avant = await situation(intendant, A.inscriptionId);
  const t = await simuler(A.parent, t1.id);
  egal(t.statut, 'CONFIRMEE', 'Transaction confirmée');
  vrai(t.paiementId, 'Paiement créé');
  egal(t.montantRecu, 1000, 'Montant reçu');
  const s = await situation(intendant, A.inscriptionId);
  egal(s.payeFamille, avant.payeFamille + 1000, 'Payé famille');
  const p = s.paiements.find((x) => x.id === t.paiementId);
  vrai(p && p.moyen === 'ORANGE_MONEY' && p.payeur === 'FAMILLE' && p.recuNumero, 'Paiement Orange Money de la famille avec reçu');
  egal((await exiger(A.parent, 'GET', `/espace-parent/mobile-money/${t1.id}`)).statut, 'CONFIRMEE', 'Le parent voit la confirmation');
  const pdf = await exiger(A.parent, 'GET', `/espace-parent/paiements/${t.paiementId}/recu`, undefined, { fichier: true });
  egal(pdf.octets.subarray(0, 4).toString('latin1'), '%PDF', 'Reçu PDF pour le parent');
  etat(ctx).mm.t1 = t;
  return `reçu ${p.recuNumero}`;
});

scenario('MMO-06', MMO, 'Un paiement refusé par le parent sur son téléphone échoue sans rien encaisser', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  await exigerSimulateur(ctx);
  const intendant = role(ecole(ctx), 'INTENDANT');
  const avant = await situation(intendant, A.inscriptionId);
  await avecParametres(ctx, OPERATEURS_TEST, async () => {
    const r = await payerMM(A.parent, A, 1000, 'MOOV_MONEY');
    statut(r, 202);
    const t = await simuler(A.parent, r.donnees.id, { accepter: false });
    egal(t.statut, 'ECHOUEE', 'Transaction échouée');
    egal(t.paiementId, null, 'Aucun paiement');
    etat(ctx).mm.t2 = t;
  });
  egal((await situation(intendant, A.inscriptionId)).payeFamille, avant.payeFamille, 'Rien encaissé');
});

scenario('MMO-07', MMO, 'Un montant reçu différent du montant demandé met la transaction « à vérifier » sans l’encaisser', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  await exigerSimulateur(ctx);
  const e = ecole(ctx);
  const intendant = role(e, 'INTENDANT');
  const avant = await situation(intendant, A.inscriptionId);
  await avecParametres(ctx, OPERATEURS_TEST, async () => {
    const r = await payerMM(A.parent, A, 1500, 'ORANGE_MONEY');
    statut(r, 202);
    const t = await simuler(A.parent, r.donnees.id, { accepter: true, montant: 1400 });
    egal(t.statut, 'A_VERIFIER', 'Transaction à vérifier');
    egal(t.montantRecu, 1400, 'Montant reçu noté');
    etat(ctx).mm.t3 = t;
  });
  egal((await situation(intendant, A.inscriptionId)).payeFamille, avant.payeFamille, 'Rien encaissé automatiquement');
  const liste = await exiger(intendant, 'GET', '/mobile-money/transactions/a-verifier');
  vrai(liste.some((x) => x.id === etat(ctx).mm.t3.id), 'Dans la liste « à vérifier » de l’intendance');
  refuse(await appel(A.parent, 'GET', '/mobile-money/transactions/a-verifier'), 'Parent');
  vrai(!(await exiger(autreEcole(ctx), 'GET', '/mobile-money/transactions/a-verifier')).some((x) => x.id === etat(ctx).mm.t3.id),
    'Invisible d’un autre établissement');
});

scenario('MMO-08', MMO, 'Le parent ne peut pas payer en ligne plus que le reste dû', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  await exigerSimulateur(ctx);
  const s = await situation(role(ecole(ctx), 'INTENDANT'), A.inscriptionId);
  if (s.resteFamille + 1 > 2_000_000) ignorer('Reste supérieur au plafond Mobile Money');
  await avecParametres(ctx, OPERATEURS_TEST, async () => {
    statut(await payerMM(A.parent, A, s.resteFamille + 1, 'ORANGE_MONEY'), 409, 'MONTANT_SUPERIEUR_AU_RESTE');
  });
});

scenario('MMO-09', MMO, 'L’intendance suit les transactions du jour et peut redemander un statut sans double encaissement ; secrétariat et parent refusés', async (ctx) => {
  const { A } = await elevesDeTest(ctx);
  const t1 = exigerMM(ctx, 't1');
  const e = ecole(ctx);
  const intendant = role(e, 'INTENDANT');
  const params = { du: ajouterJours(ctx.aujourdhui, -1), au: ajouterJours(ctx.aujourdhui, 1) };
  const liste = await exiger(intendant, 'GET', '/mobile-money/transactions', undefined, { params });
  const statuts = Object.fromEntries(['t1', 't2', 't3'].map((k) => [k, liste.find((x) => x.id === etat(ctx).mm?.[k]?.id)?.statut ?? null]));
  egal(statuts.t1, 'CONFIRMEE', 'Transaction confirmée listée');
  if (etat(ctx).mm.t2) egal(statuts.t2, 'ECHOUEE', 'Transaction refusée listée');
  if (etat(ctx).mm.t3) egal(statuts.t3, 'A_VERIFIER', 'Transaction à vérifier listée');
  const avant = await situation(intendant, A.inscriptionId);
  const v = await exiger(intendant, 'POST', `/mobile-money/transactions/${t1.id}/verification`);
  egal(v.statut, 'CONFIRMEE', 'Statut inchangé après nouvelle vérification');
  const apres = await situation(intendant, A.inscriptionId);
  egal(apres.paiements.length, avant.paiements.length, 'Aucun paiement de plus');
  statut(await appel(intendant, 'GET', '/mobile-money/transactions', undefined, { params: { du: ctx.aujourdhui, au: ajouterJours(ctx.aujourdhui, -2) } }), 400);
  refuse(await appel(role(e, 'SECRETARIAT'), 'GET', '/mobile-money/transactions', undefined, { params }), 'Secrétariat');
  refuse(await appel(A.parent, 'GET', '/mobile-money/transactions', undefined, { params }), 'Parent');
  vrai(!(await exiger(autreEcole(ctx), 'GET', '/mobile-money/transactions', undefined, { params })).some((x) => x.id === t1.id),
    'Invisible d’un autre établissement');
  return `${liste.length} transaction(s) sur la période`;
});

scenario('MMO-10', MMO, 'Le rapprochement du jour compare le relevé de l’agrégateur : la transaction confirmée concorde, le montant différent ressort en écart', async (ctx) => {
  await exigerSimulateur(ctx);
  const t1 = exigerMM(ctx, 't1');
  const t3 = etat(ctx).mm?.t3;
  const e = ecole(ctx);
  const intendant = role(e, 'INTENDANT');
  statut(await appel(intendant, 'POST', '/mobile-money/rapprochements', undefined, { params: { date: ajouterJours(ctx.aujourdhui, 2) } }), 400);
  refuse(await appel(role(e, 'SECRETARIAT'), 'POST', '/mobile-money/rapprochements', undefined, { params: { date: ctx.aujourdhui } }), 'Secrétariat');
  const r = await appel(intendant, 'POST', '/mobile-money/rapprochements', undefined, { params: { date: ctx.aujourdhui } });
  const liste = await exiger(intendant, 'GET', '/mobile-money/rapprochements', undefined, { params: { du: ctx.aujourdhui, au: ctx.aujourdhui } });
  if (r.statut === 409 && r.code === 'RAPPROCHEMENT_TRAITE') {
    // Un passage précédent de la recette a déjà traité un écart aujourd'hui : la journée est figée
    vrai(liste.length === 1, 'Le rapprochement figé du jour reste consultable');
    etat(ctx).rapprochementFige = true;
    return 'journée déjà rapprochée et figée par un passage précédent (RAPPROCHEMENT_TRAITE)';
  }
  statut(r, 200);
  const rap = r.donnees;
  egal(rap.dateReleve, ctx.aujourdhui, 'Date du relevé');
  vrai(rap.lignes >= (t3 ? 2 : 1), `Le relevé contient nos transactions confirmées (${rap.lignes} lignes)`);
  vrai(liste.some((x) => x.id === rap.id), 'Rapprochement listé');
  const ecarts = await exiger(intendant, 'GET', `/mobile-money/rapprochements/${rap.id}/ecarts`);
  egal(ecarts.length, rap.ecarts, 'Nombre d’écarts');
  vrai(!ecarts.some((x) => x.reference === t1.reference), 'La transaction confirmée concorde avec le relevé');
  if (t3) {
    const ecart = ecarts.find((x) => x.reference === t3.reference);
    vrai(ecart && ecart.type === 'MONTANT_DIFFERENT', 'Écart MONTANT_DIFFERENT pour la transaction à vérifier');
    egal([ecart.montantAttendu, ecart.montantRecu], [1500, 1400], 'Montants attendu et reçu');
    etat(ctx).ecart = ecart;
  }
  return `${rap.lignes} ligne(s), ${rap.ecarts} écart(s), statut ${rap.statut}`;
});

scenario('MMO-11', MMO, 'L’intendance régularise la transaction à vérifier et traite l’écart, une seule fois ; la journée ne se rapproche plus', async (ctx) => {
  const t1 = exigerMM(ctx, 't1');
  const t3 = exigerMM(ctx, 't3');
  const intendant = role(ecole(ctx), 'INTENDANT');
  statut(await appel(intendant, 'POST', `/mobile-money/transactions/${t1.id}/regularisation`, { motif: 'Rien à régulariser' }), 409, 'TRANSACTION_NON_A_VERIFIER');
  statut(await appel(intendant, 'POST', `/mobile-money/transactions/${t3.id}/regularisation`, { motif: ' ' }), 400);
  const reg = await exiger(intendant, 'POST', `/mobile-money/transactions/${t3.id}/regularisation`, { motif: '1 400 FCFA remboursés au parent (recette)' });
  egal(reg.statut, 'REGULARISEE', 'Transaction régularisée');
  vrai(!(await exiger(intendant, 'GET', '/mobile-money/transactions/a-verifier')).some((x) => x.id === t3.id), 'Sortie de la liste « à vérifier »');
  statut(await appel(intendant, 'POST', `/mobile-money/transactions/${t3.id}/regularisation`, { motif: 'Encore' }), 409, 'TRANSACTION_NON_A_VERIFIER');
  const ecart = etat(ctx).ecart;
  if (!ecart) return etat(ctx).rapprochementFige ? 'écart non traité : journée déjà figée' : 'aucun écart à traiter';
  statut(await appel(intendant, 'POST', `/mobile-money/ecarts/${ecart.id}/traitement`, { motif: ' ' }), 400);
  const traite = await exiger(intendant, 'POST', `/mobile-money/ecarts/${ecart.id}/traitement`, { motif: 'Remboursé au parent (recette)' });
  egal(traite.traite, true, 'Écart traité');
  statut(await appel(intendant, 'POST', `/mobile-money/ecarts/${ecart.id}/traitement`, { motif: 'Encore' }), 409, 'ECART_DEJA_TRAITE');
  statut(await appel(intendant, 'POST', '/mobile-money/rapprochements', undefined, { params: { date: ctx.aujourdhui } }), 409, 'RAPPROCHEMENT_TRAITE');
  return 'journée figée : les prochains passages du jour le constateront';
});

scenario('MMO-12', MMO, 'Fin du parcours : réglages de paiement revenus à l’état initial et élèves de test sortis', async (ctx) => {
  const s = etat(ctx);
  const e = ecole(ctx);
  const adm = role(e, 'ADMIN_ECOLE');
  const notes = [];
  if (s.parametresInitiaux) {
    const p = await exiger(adm, 'GET', '/parametres/paiement');
    if (!memesParametres(p, s.parametresInitiaux)) {
      // Dernier recours : remise des réglages lus au tout début
      const r = await appel(adm, 'PUT', '/parametres/paiement', corpsParametres(s.parametresInitiaux));
      throw new Echec(`Réglages de paiement différents de l’état initial (${r.ok ? 'rétablis à l’instant' : `non rétablis : ${r.resume()}`})`);
    }
    if (s.compte) {
      const c = p.comptes.find((x) => x.id === s.compte.id);
      if (c?.actif) {
        const r = await basculerCompte(adm, s.compte, false);
        throw new Echec(`Compte bancaire de test resté actif (${r.ok ? 'désactivé à l’instant' : r.resume()})`);
      }
    }
    notes.push('réglages de paiement identiques au début');
  }
  if (s.eleves) {
    for (const x of [s.eleves.A, s.eleves.B]) {
      const r = await appel(adm, 'POST', `/inscriptions/${x.inscriptionId}/sortie`, { statut: 'ABANDON', date: ctx.aujourdhui, motif: 'Élève de test de la recette' });
      notes.push(`${x.prenoms} ${r.ok ? 'sorti' : `non sorti (${r.resume()})`}`);
    }
  }
  if (s.mmCreeParRecette) notes.push('configuration Mobile Money SIMULATEUR créée par la recette (conservée)');
  vrai(!s.erreursRetablissement.length, `Remises en état en échec : ${s.erreursRetablissement.join(' ; ')}`);
  return notes.join(' ; ') || 'rien à remettre en état';
});
