/**
 * Recette grandeur nature : territoire (pays, ministères, directions, rattachement des établissements,
 * administrateurs pays) et pilotage des directions (comptes des directions, tableau de bord agrégé).
 *
 * Sources vérifiées : territoire/TerritoireController, TerritoireService, Vues, PorteeTerritoire ;
 * utilisateurs/AdministrateursPays*, AdministrateursDirection*, MembresService, AuthService ;
 * plateforme/EtablissementsController, EtablissementsService ; pilotage/PilotageController,
 * PilotageService, Vues ; socle/securite/Portee, SecuriteConfig ; docs/API_TERRITOIRE.md, API_PILOTAGE.md.
 *
 * Dépose pour les autres lots :
 *   ctx.partage.territoire = { paysBF, paysNE, ministereBF, directions: { code: id }, rattachement: { codeEtab: idDirection },
 *                              adminPaysBF: Session, adminPaysNE: Session, comptesDirection: { 'DR-CENTRE': Session, 'DP-KADIOGO': Session },
 *                              ministereNE, directionNE, etablissementTestPays }
 *
 * Rejouable : le pays NE, le ministère MESFPT et les directions sont « trouvés ou créés » (l'import CSV met à jour
 * l'existant) ; les administrateurs pays, comptes de direction et établissements de test ont des numéros et codes
 * uniques à chaque passage ; les établissements de test créés ici finissent résiliés.
 */
import { API, appel, appelBrut, exiger, ignorer, refuse, sansTexte, scenario, statut, suffixe, telephoneUnique, vrai, egal, Echec } from '../lib.mjs';

const TER = 'Territoire et administration des pays';
const PIL = 'Pilotage des directions';

const T = '/plateforme/territoire';

const MINISTERE_BF = {
  sigle: 'MESFPT',
  nom: 'Ministère de l’Enseignement secondaire, de la Formation professionnelle et technique',
  niveaux: ['Direction régionale', 'Direction provinciale'],
};

/** Directions importées par CSV (code;nom;code_parent). */
const DIRECTIONS = [
  ['DR-CENTRE', 'Direction régionale de l’enseignement secondaire, de la formation professionnelle et technique du Centre', ''],
  ['DP-KADIOGO', 'Direction provinciale de l’enseignement secondaire, de la formation professionnelle et technique du Kadiogo', 'DR-CENTRE'],
  ['DP-OUBRITENGA', 'Direction provinciale de l’enseignement secondaire, de la formation professionnelle et technique de l’Oubritenga', 'DR-CENTRE'],
  ['DR-HAUTS-BASSINS', 'Direction régionale de l’enseignement secondaire, de la formation professionnelle et technique des Hauts-Bassins', ''],
  ['DP-HOUET', 'Direction provinciale de l’enseignement secondaire, de la formation professionnelle et technique du Houet', 'DR-HAUTS-BASSINS'],
];

const RATTACHEMENTS = { 'lt-batisseurs': 'DP-KADIOGO', 'cfp-atelier-faso': 'DP-OUBRITENGA', 'lp-horizon': 'DP-HOUET' };

const NIGER = {
  code: 'NE',
  nom: 'Niger',
  deviseNationale: 'Fraternité – Travail – Progrès',
  indicatifTelephone: '+227',
  longueurNumero: 8,
  fuseauHoraire: 'Africa/Niamey',
  monnaie: 'XOF',
  langue: 'fr',
};

const MINISTERE_NE = { sigle: 'MEN-NE', nom: 'Ministère de l’Éducation nationale (Niger)', niveaux: ['Direction régionale'] };
const DIRECTION_NE = { code: 'DR-NIAMEY', nom: 'Direction régionale de l’éducation de Niamey' };

// ===================================================================== outils du lot

/** Partage du lot, créé à la demande. */
function terr(ctx) {
  ctx.partage.territoire ??= { directions: {}, rattachement: {}, comptesDirection: {} };
  return ctx.partage.territoire;
}

/** Exige des clés du partage, sinon le scénario est ignoré. */
function exigerPartage(ctx, ...cles) {
  const t = ctx.partage.territoire;
  for (const c of cles) {
    const v = c.split('.').reduce((o, k) => (o == null ? o : o[k]), t);
    if (v == null) ignorer(`prérequis absent : territoire.${c} (scénario précédent en échec ou ignoré)`);
  }
  return t;
}

function csv(lignes, entete = true) {
  return (entete ? ['code;nom;code_parent'] : []).concat(lignes.map((l) => l.join(';'))).join('\n');
}

async function directionsBF(ctx) {
  const t = exigerPartage(ctx, 'ministereBF');
  return exiger(ctx.sa, 'GET', `${T}/ministeres/${t.ministereBF}/directions`);
}

/** Nom des élèves, parents et enseignants d'un établissement (données personnelles à ne jamais voir en pilotage). */
function nomsPersonnels(e) {
  const noms = [];
  for (const f of e.familles ?? []) {
    noms.push(f.nom, f.telephone);
    for (const x of f.enfants ?? []) noms.push(`${x.nom} ${x.prenoms}`);
  }
  for (const p of e.enseignants ?? []) noms.push(p.nom, p.telephone);
  return noms.filter((x) => x && x.length >= 6);
}

const codesEtab = (liste) => (liste ?? []).map((x) => x.code);

// ===================================================================== pays

scenario('TER-01', TER, 'Le super administrateur retrouve le Burkina Faso créé à l’installation et y saisit la devise nationale', async (ctx) => {
  const pays = await exiger(ctx.sa, 'GET', `${T}/pays`);
  const bf = pays.find((p) => p.code === 'BF');
  vrai(bf, 'Le pays BF (migration V26) est absent de la liste');
  egal([bf.indicatifTelephone, bf.longueurNumero, bf.monnaie], ['+226', 8, 'XOF'], 'Paramètres du Burkina Faso');
  const maj = await exiger(ctx.sa, 'PUT', `${T}/pays/${bf.id}`, {
    code: bf.code,
    nom: bf.nom,
    deviseNationale: 'Unité – Progrès – Justice',
    indicatifTelephone: bf.indicatifTelephone,
    longueurNumero: bf.longueurNumero,
    fuseauHoraire: bf.fuseauHoraire,
    monnaie: bf.monnaie,
    langue: bf.langue ?? 'fr',
  });
  egal(maj.deviseNationale, 'Unité – Progrès – Justice', 'Devise nationale enregistrée');
  terr(ctx).paysBF = bf.id;
  return `${bf.nom} : ${bf.ministeres} ministère(s), ${bf.etablissements} établissement(s) rattaché(s)`;
});

scenario('TER-02', TER, 'Le super administrateur ajoute le Niger (indicatif +227, Africa/Niamey) ; un second ajout est refusé', async (ctx) => {
  let ne = (await exiger(ctx.sa, 'GET', `${T}/pays`)).find((p) => p.code === 'NE');
  let note = 'pays retrouvé (recette rejouée)';
  if (!ne) {
    const r = await appel(ctx.sa, 'POST', `${T}/pays`, NIGER);
    statut(r, 201);
    ne = r.donnees;
    note = 'pays créé';
  }
  egal([ne.nom, ne.indicatifTelephone, ne.longueurNumero, ne.fuseauHoraire, ne.monnaie], ['Niger', '+227', 8, 'Africa/Niamey', 'XOF'], 'Paramètres du Niger');
  statut(await appel(ctx.sa, 'POST', `${T}/pays`, { ...NIGER, code: 'ne' }), 409, 'PAYS_EXISTANT');
  terr(ctx).paysNE = ne.id;
  return note;
});

scenario('TER-03', TER, 'Un pays mal saisi est refusé (fuseau inconnu, indicatif sans +, code ISO à 3 lettres, longueur de numéro hors bornes)', async (ctx) => {
  const base = { ...NIGER, code: 'ZZ', nom: `Pays de recette ${suffixe()}` };
  statut(await appel(ctx.sa, 'POST', `${T}/pays`, { ...base, fuseauHoraire: 'Afrique/Nulle-Part' }), 400);
  statut(await appel(ctx.sa, 'POST', `${T}/pays`, { ...base, indicatifTelephone: '227' }), 400);
  statut(await appel(ctx.sa, 'POST', `${T}/pays`, { ...base, code: 'NER' }), 400);
  statut(await appel(ctx.sa, 'POST', `${T}/pays`, { ...base, longueurNumero: 3 }), 400);
  statut(await appel(ctx.sa, 'POST', `${T}/pays`, { ...base, monnaie: 'FCFA' }), 400);
  const apres = await exiger(ctx.sa, 'GET', `${T}/pays`);
  vrai(!apres.some((p) => p.code === 'ZZ'), 'Un pays invalide a été créé');
});

// ===================================================================== ministère et directions

scenario('TER-04', TER, 'Le ministère de tutelle MESFPT du Burkina Faso a deux niveaux : Direction régionale puis Direction provinciale', async (ctx) => {
  const t = exigerPartage(ctx, 'paysBF');
  const liste = await exiger(ctx.sa, 'GET', `${T}/pays/${t.paysBF}/ministeres`);
  let m = liste.find((x) => x.sigle === MINISTERE_BF.sigle);
  let note = 'ministère retrouvé';
  if (!m) {
    const r = await appel(ctx.sa, 'POST', `${T}/pays/${t.paysBF}/ministeres`, { ...MINISTERE_BF, actif: true });
    statut(r, 201);
    m = r.donnees;
    note = 'ministère créé';
  } else if (JSON.stringify(m.niveaux) !== JSON.stringify(MINISTERE_BF.niveaux) && m.directions === 0) {
    m = await exiger(ctx.sa, 'PUT', `${T}/ministeres/${m.id}`, { ...MINISTERE_BF, actif: true });
    note = 'niveaux du ministère corrigés';
  }
  egal(m.niveaux, MINISTERE_BF.niveaux, 'Niveaux de directions du MESFPT');
  vrai(m.actif, 'Le ministère doit être actif');
  // Le même sigle une seconde fois dans le même pays
  statut(await appel(ctx.sa, 'POST', `${T}/pays/${t.paysBF}/ministeres`, MINISTERE_BF), 409, 'MINISTERE_EXISTANT');
  // Niveaux : de 1 à 5
  statut(await appel(ctx.sa, 'POST', `${T}/pays/${t.paysBF}/ministeres`, { ...MINISTERE_BF, sigle: `X${suffixe()}`, niveaux: [] }), 400);
  statut(await appel(ctx.sa, 'POST', `${T}/pays/${t.paysBF}/ministeres`,
    { ...MINISTERE_BF, sigle: `X${suffixe()}`, niveaux: ['1', '2', '3', '4', '5', '6'] }), 400);
  terr(ctx).ministereBF = m.id;
  return note;
});

scenario('TER-05', TER, 'Le bouton « Vérifier » simule l’import CSV des cinq directions sans rien enregistrer', async (ctx) => {
  const t = exigerPartage(ctx, 'ministereBF');
  const avant = await directionsBF(ctx);
  const rapport = await exiger(ctx.sa, 'POST', `${T}/ministeres/${t.ministereBF}/directions/import`, {
    contenu: csv(DIRECTIONS),
    simulation: true,
  });
  vrai(rapport.simulation === true, 'Le rapport doit indiquer une simulation');
  egal(rapport.erreurs, [], 'Erreurs de la simulation');
  egal(rapport.lignes, 5, 'Lignes lues (en-tête exclu)');
  egal(rapport.creees + rapport.modifiees + rapport.inchangees, 5, 'Créées + modifiées + inchangées');
  const apres = await directionsBF(ctx);
  egal(apres.length, avant.length, 'Nombre de directions après simulation');
  return `${rapport.creees} à créer, ${rapport.modifiees} à modifier, ${rapport.inchangees} inchangée(s)`;
});

scenario('TER-06', TER, 'Un fichier dont une ligne cite un parent inconnu est rejeté en entier, en simulation comme à l’import', async (ctx) => {
  const t = exigerPartage(ctx, 'ministereBF');
  const s = suffixe().toUpperCase();
  const contenu = csv([
    [`DP-NOUVELLE-${s}`, 'Direction provinciale de recette (valide)', 'DR-CENTRE'],
    [`DP-ORPHELINE-${s}`, 'Direction provinciale de recette (parent inconnu)', `DR-INCONNUE-${s}`],
  ]);
  const avant = await directionsBF(ctx);
  for (const simulation of [true, false]) {
    const r = await exiger(ctx.sa, 'POST', `${T}/ministeres/${t.ministereBF}/directions/import`, { contenu, simulation });
    vrai(r.erreurs.length >= 1, `Le rapport (simulation=${simulation}) doit signaler l’erreur`);
    vrai(r.erreurs.some((e) => e.ligne === 3 && /introuvable/i.test(e.message)),
      `L’erreur doit viser la ligne 3 (parent introuvable) : ${JSON.stringify(r.erreurs)}`);
  }
  const apres = await directionsBF(ctx);
  egal(apres.length, avant.length, 'Tout ou rien : aucune direction ne doit avoir été créée');
  vrai(!apres.some((d) => d.code.endsWith(s)), 'La ligne valide du fichier fautif a été enregistrée');
});

scenario('TER-07', TER, 'L’import CSV enregistre les directions régionales et provinciales avec leur niveau et leur parent', async (ctx) => {
  const t = exigerPartage(ctx, 'ministereBF');
  const r = await exiger(ctx.sa, 'POST', `${T}/ministeres/${t.ministereBF}/directions/import`, { contenu: csv(DIRECTIONS), simulation: false });
  vrai(r.simulation === false, 'Le rapport ne doit pas être une simulation');
  egal(r.erreurs, [], 'Erreurs de l’import');
  const liste = await directionsBF(ctx);
  const parCode = Object.fromEntries(liste.map((d) => [d.code, d]));
  for (const [code, nom, parent] of DIRECTIONS) {
    const d = parCode[code];
    vrai(d, `Direction ${code} absente après import`);
    egal(d.nom, nom, `Nom de ${code}`);
    egal(d.rang, parent ? 2 : 1, `Niveau de ${code}`);
    egal(d.parentId ?? null, parent ? parCode[parent].id : null, `Parent de ${code}`);
    terr(ctx).directions[code] = d.id;
  }
  return `${r.creees} créée(s), ${r.modifiees} modifiée(s), ${r.inchangees} inchangée(s)`;
});

scenario('TER-08', TER, 'Réimporter un fichier met à jour le nom d’une direction existante (sans doublon), puis le rétablit', async (ctx) => {
  const t = exigerPartage(ctx, 'ministereBF', 'directions.DP-OUBRITENGA');
  const modifie = DIRECTIONS.map((l) => (l[0] === 'DP-OUBRITENGA' ? [l[0], `${l[1]} (renommée)`, l[2]] : l));
  try {
    const r = await exiger(ctx.sa, 'POST', `${T}/ministeres/${t.ministereBF}/directions/import`, { contenu: csv(modifie), simulation: false });
    egal([r.creees, r.modifiees, r.inchangees], [0, 1, 4], 'Créées, modifiées, inchangées');
    const d = (await directionsBF(ctx)).filter((x) => x.code === 'DP-OUBRITENGA');
    egal(d.length, 1, 'Une seule direction DP-OUBRITENGA');
    vrai(d[0].nom.endsWith('(renommée)'), 'Le nom doit avoir été mis à jour');
    egal(d[0].id, t.directions['DP-OUBRITENGA'], 'La direction garde son identifiant');
  } finally {
    await appel(ctx.sa, 'POST', `${T}/ministeres/${t.ministereBF}/directions/import`, { contenu: csv(DIRECTIONS), simulation: false });
  }
  const d = (await directionsBF(ctx)).find((x) => x.code === 'DP-OUBRITENGA');
  egal(d.nom, DIRECTIONS[2][1], 'Nom rétabli');
  // Un niveau de trop : sous une direction provinciale (dernier niveau)
  const profond = await exiger(ctx.sa, 'POST', `${T}/ministeres/${t.ministereBF}/directions/import`, {
    contenu: csv([[`DX-${suffixe()}`, 'Trop profonde', 'DP-KADIOGO']]),
    simulation: true,
  });
  vrai(profond.erreurs.some((e) => /niveau/i.test(e.message)), `Erreur de niveau attendue : ${JSON.stringify(profond.erreurs)}`);
});

scenario('TER-09', TER, 'Les règles de l’arbre sont tenues : code unique, niveau figé, nombre de niveaux figé, rien sous le dernier niveau', async (ctx) => {
  const t = exigerPartage(ctx, 'ministereBF', 'directions.DP-KADIOGO', 'directions.DR-CENTRE');
  const kad = DIRECTIONS[1];
  statut(await appel(ctx.sa, 'POST', `${T}/ministeres/${t.ministereBF}/directions`, { parentId: null, code: 'DR-CENTRE', nom: 'Doublon' }),
    409, 'DIRECTION_EXISTANTE');
  statut(await appel(ctx.sa, 'PUT', `${T}/directions/${t.directions['DP-KADIOGO']}`, { parentId: null, code: kad[0], nom: kad[1] }),
    409, 'NIVEAU_DIRECTION');
  statut(await appel(ctx.sa, 'POST', `${T}/ministeres/${t.ministereBF}/directions`,
    { parentId: t.directions['DP-KADIOGO'], code: `DC-${suffixe()}`, nom: 'Direction communale' }), 409, 'NIVEAU_DIRECTION');
  statut(await appel(ctx.sa, 'POST', `${T}/ministeres/${t.ministereBF}/directions`, { parentId: null, code: 'code invalide!', nom: 'X' }), 400);
  statut(await appel(ctx.sa, 'PUT', `${T}/ministeres/${t.ministereBF}`,
    { ...MINISTERE_BF, actif: true, niveaux: [...MINISTERE_BF.niveaux, 'Direction communale'] }), 409, 'NIVEAUX_FIGES');
  // Les chemins complets servent au choix du rattachement
  const chemins = await exiger(ctx.sa, 'GET', `${T}/directions`);
  const dp = chemins.find((d) => d.id === t.directions['DP-KADIOGO']);
  const dr = chemins.find((d) => d.id === t.directions['DR-CENTRE']);
  vrai(dp && dr, 'Directions absentes de la liste avec chemins');
  vrai(dp.terminale && !dr.terminale, 'Seule la direction provinciale est du dernier niveau');
  vrai(dp.chemin.startsWith('Burkina Faso · MESFPT'), `Chemin inattendu : ${dp.chemin}`);
  vrai(dp.chemin.includes('Kadiogo') && dp.chemin.includes('Centre'), `Chemin incomplet : ${dp.chemin}`);
  egal(dp.paysId, t.paysBF, 'Pays de la direction');
});

scenario('TER-10', TER, 'Le Niger reçoit son propre ministère à un seul niveau et une direction régionale', async (ctx) => {
  const t = exigerPartage(ctx, 'paysNE');
  let m = (await exiger(ctx.sa, 'GET', `${T}/pays/${t.paysNE}/ministeres`)).find((x) => x.sigle === MINISTERE_NE.sigle);
  if (!m) m = await exiger(ctx.sa, 'POST', `${T}/pays/${t.paysNE}/ministeres`, { ...MINISTERE_NE, actif: true });
  let d = (await exiger(ctx.sa, 'GET', `${T}/ministeres/${m.id}/directions`)).find((x) => x.code === DIRECTION_NE.code);
  if (!d) d = await exiger(ctx.sa, 'POST', `${T}/ministeres/${m.id}/directions`, { parentId: null, ...DIRECTION_NE, actif: true });
  egal(d.rang, 1, 'Niveau de la direction du Niger');
  const chemin = (await exiger(ctx.sa, 'GET', `${T}/directions`)).find((x) => x.id === d.id);
  vrai(chemin?.terminale, 'Avec un seul niveau, la direction régionale est du dernier niveau');
  terr(ctx).ministereNE = m.id;
  terr(ctx).directionNE = d.id;
});

// ===================================================================== rattachement

scenario('TER-11', TER, 'Les trois établissements de démonstration sont rattachés à leur direction provinciale ; une direction régionale est refusée', async (ctx) => {
  const t = exigerPartage(ctx, 'directions.DP-KADIOGO', 'directions.DP-OUBRITENGA', 'directions.DP-HOUET', 'directions.DR-CENTRE');
  const e1 = ctx.ecole('lt-batisseurs');
  statut(await appel(ctx.sa, 'PUT', `/plateforme/etablissements/${e1.id}/rattachement`, { directionId: t.directions['DR-CENTRE'] }),
    409, 'RATTACHEMENT_INVALIDE');
  statut(await appel(ctx.sa, 'PUT', `/plateforme/etablissements/${e1.id}/rattachement`, {}), 400);
  for (const [code, dir] of Object.entries(RATTACHEMENTS)) {
    const e = ctx.ecole(code);
    vrai(e, `Établissement ${code} absent de l’état de démonstration`);
    const v = await exiger(ctx.sa, 'PUT', `/plateforme/etablissements/${e.id}/rattachement`, { directionId: t.directions[dir] });
    egal(v.directionId, t.directions[dir], `Direction de ${code}`);
    vrai(v.rattachement?.startsWith('Burkina Faso · MESFPT'), `Rattachement de ${code} : ${v.rattachement}`);
    terr(ctx).rattachement[code] = t.directions[dir];
  }
  // Filtre par direction, à tout niveau
  const centre = await exiger(ctx.sa, 'GET', '/plateforme/etablissements', undefined, { params: { direction: t.directions['DR-CENTRE'] } });
  const codes = codesEtab(centre);
  vrai(codes.includes('lt-batisseurs') && codes.includes('cfp-atelier-faso'), `DR-CENTRE doit couvrir 1 et 2 : ${codes}`);
  vrai(!codes.includes('lp-horizon'), 'lp-horizon ne dépend pas de la DR-CENTRE');
});

// ===================================================================== administrateurs pays

scenario('TER-12', TER, 'Le super administrateur nomme un administrateur pays pour le Burkina Faso, qui change son mot de passe provisoire', async (ctx) => {
  const t = exigerPartage(ctx, 'paysBF');
  const tel = telephoneUnique('75');
  const r = await appel(ctx.sa, 'POST', `${T}/pays/${t.paysBF}/administrateurs`, { telephone: tel, nom: 'Recette', prenoms: `Admin BF ${suffixe()}` });
  statut(r, 201);
  vrai(r.donnees.motDePasseTemporaire, 'Le mot de passe provisoire doit être affiché une fois');
  egal(r.donnees.administrateur.telephone, `+226${tel}`, 'Le numéro reçoit l’indicatif du pays');
  const s = await ctx.activer(r.donnees.administrateur.telephone, r.donnees.motDePasseTemporaire);
  await s.jeton();
  egal(s.connexion.adminPays?.code, 'BF', 'La session doit porter le pays administré');
  vrai(!s.connexion.etablissementActif, 'Un administrateur pays n’a pas d’établissement');
  const liste = await exiger(ctx.sa, 'GET', `${T}/pays/${t.paysBF}/administrateurs`);
  vrai(liste.some((a) => a.utilisateurId === r.donnees.administrateur.utilisateurId && a.actif && !a.motDePasseProvisoire),
    'L’administrateur doit être listé, actif, mot de passe changé');
  s.libelle = 'administrateur pays BF';
  terr(ctx).adminPaysBF = s;
  terr(ctx).adminPaysBFId = r.donnees.administrateur.utilisateurId;
});

scenario('TER-13', TER, 'Le super administrateur nomme un administrateur pour le Niger ; un numéro déjà pris ailleurs est refusé', async (ctx) => {
  const t = exigerPartage(ctx, 'paysNE', 'paysBF', 'adminPaysBF');
  const tel = telephoneUnique('9');
  const r = await appel(ctx.sa, 'POST', `${T}/pays/${t.paysNE}/administrateurs`, { telephone: tel, nom: 'Recette', prenoms: `Admin NE ${suffixe()}` });
  statut(r, 201);
  egal(r.donnees.administrateur.telephone, `+227${tel}`, 'Le numéro reçoit l’indicatif du Niger');
  // Connexion avec l'indicatif du pays (la connexion utilise sinon celui de la plateforme)
  const s = await ctx.activer(r.donnees.administrateur.telephone, r.donnees.motDePasseTemporaire);
  await s.jeton();
  egal(s.connexion.adminPays?.code, 'NE', 'Pays administré');
  s.libelle = 'administrateur pays NE';
  terr(ctx).adminPaysNE = s;
  // Un administrateur du Niger ne peut pas devenir administrateur du Burkina
  statut(await appel(ctx.sa, 'POST', `${T}/pays/${t.paysBF}/administrateurs`, { telephone: s.telephone, nom: 'X', prenoms: 'Y' }),
    409, 'ADMIN_AUTRE_PAYS');
  // Un membre d'un établissement non plus
  const admin1 = ctx.ecole('lt-batisseurs').personnel?.SECRETARIAT ?? ctx.ecole('lt-batisseurs').admin;
  statut(await appel(ctx.sa, 'POST', `${T}/pays/${t.paysBF}/administrateurs`, { telephone: admin1, nom: 'X', prenoms: 'Y' }),
    409, 'COMPTE_ETABLISSEMENT');
  // Ni le super administrateur
  statut(await appel(ctx.sa, 'POST', `${T}/pays/${t.paysBF}/administrateurs`, { telephone: ctx.sa.telephone, nom: 'X', prenoms: 'Y' }),
    409, 'COMPTE_PLATEFORME');
  // Pays inconnu
  statut(await appel(ctx.sa, 'POST', `${T}/pays/00000000-0000-0000-0000-000000000000/administrateurs`,
    { telephone: telephoneUnique('75'), nom: 'X', prenoms: 'Y' }), 404);
});

scenario('TER-14', TER, 'L’administrateur pays ne nomme pas d’administrateurs pays et ne crée ni ne modifie de pays', async (ctx) => {
  const t = exigerPartage(ctx, 'adminPaysBF', 'paysBF');
  const a = t.adminPaysBF;
  refuse(await appel(a, 'GET', `${T}/pays/${t.paysBF}/administrateurs`), 'Liste des administrateurs pays');
  refuse(await appel(a, 'POST', `${T}/pays/${t.paysBF}/administrateurs`, { telephone: telephoneUnique('75'), nom: 'X', prenoms: 'Y' }),
    'Nomination par un administrateur pays');
  statut(await appel(a, 'POST', `${T}/pays`, { ...NIGER, code: 'ML', nom: 'Mali' }), 403);
  const bf = (await exiger(ctx.sa, 'GET', `${T}/pays`)).find((p) => p.id === t.paysBF);
  statut(await appel(a, 'PUT', `${T}/pays/${t.paysBF}`, { ...bf, nom: 'Burkina Faso' }), 403);
});

scenario('TER-15', TER, 'L’administrateur pays du Burkina voit son pays, ses ministères, ses directions et ses établissements, rien du Niger', async (ctx) => {
  const t = exigerPartage(ctx, 'adminPaysBF', 'paysBF', 'paysNE', 'ministereBF', 'ministereNE');
  const a = t.adminPaysBF;
  const pays = await exiger(a, 'GET', `${T}/pays`);
  egal(pays.map((p) => p.code), ['BF'], 'Pays visibles');
  const min = await exiger(a, 'GET', `${T}/pays/${t.paysBF}/ministeres`);
  vrai(min.some((m) => m.id === t.ministereBF), 'Le MESFPT doit être visible');
  statut(await appel(a, 'GET', `${T}/pays/${t.paysNE}/ministeres`), 403);
  statut(await appel(a, 'GET', `${T}/ministeres/${t.ministereNE}/directions`), 403);
  const dirs = await exiger(a, 'GET', `${T}/directions`);
  vrai(dirs.length >= 5 && dirs.every((d) => d.paysId === t.paysBF), 'Seules les directions du Burkina doivent être listées');
  const etabs = await exiger(a, 'GET', '/plateforme/etablissements');
  const codes = codesEtab(etabs);
  for (const c of Object.keys(RATTACHEMENTS)) vrai(codes.includes(c), `${c} doit être visible de l’administrateur pays`);
  vrai(etabs.every((e) => e.directionId && e.rattachement?.startsWith('Burkina Faso')),
    'Un établissement non rattaché ou hors du Burkina est visible de l’administrateur pays');
  return `${etabs.length} établissement(s) visibles`;
});

scenario('TER-16', TER, 'L’administrateur pays gère le territoire de son pays (vérification d’import) mais pas celui d’un autre', async (ctx) => {
  const t = exigerPartage(ctx, 'adminPaysBF', 'ministereBF', 'ministereNE', 'paysNE');
  const a = t.adminPaysBF;
  const r = await exiger(a, 'POST', `${T}/ministeres/${t.ministereBF}/directions/import`, { contenu: csv(DIRECTIONS), simulation: true });
  egal(r.erreurs, [], 'Simulation par l’administrateur pays');
  statut(await appel(a, 'POST', `${T}/ministeres/${t.ministereNE}/directions/import`,
    { contenu: csv([[`DR-${suffixe()}`, 'Intrusion', '']]), simulation: true }), 403);
  statut(await appel(a, 'POST', `${T}/pays/${t.paysNE}/ministeres`, { sigle: `X${suffixe()}`, nom: 'Intrusion', niveaux: ['A'] }), 403);
  statut(await appel(a, 'POST', `${T}/ministeres/${t.ministereNE}/directions`, { parentId: null, code: `X${suffixe()}`, nom: 'Intrusion' }), 403);
});

scenario('TER-17', TER, 'L’administrateur pays du Niger ne voit ni le Burkina, ni ses directions, ni ses établissements', async (ctx) => {
  const t = exigerPartage(ctx, 'adminPaysNE', 'paysBF', 'ministereBF', 'directions.DP-KADIOGO');
  const a = t.adminPaysNE;
  egal((await exiger(a, 'GET', `${T}/pays`)).map((p) => p.code), ['NE'], 'Pays visibles');
  statut(await appel(a, 'GET', `${T}/pays/${t.paysBF}/ministeres`), 403);
  statut(await appel(a, 'GET', `${T}/ministeres/${t.ministereBF}/directions`), 403);
  vrai((await exiger(a, 'GET', `${T}/directions`)).every((d) => d.paysId !== t.paysBF), 'Directions du Burkina visibles');
  const codes = codesEtab(await exiger(a, 'GET', '/plateforme/etablissements'));
  vrai(!codes.some((c) => c in RATTACHEMENTS), `Établissements du Burkina visibles : ${codes}`);
  const e1 = ctx.ecole('lt-batisseurs');
  statut(await appel(a, 'PUT', `/plateforme/etablissements/${e1.id}/rattachement`, { directionId: t.directions['DP-KADIOGO'] }), 403);
  statut(await appel(a, 'PATCH', `/plateforme/etablissements/${e1.id}/statut`, { statut: 'SUSPENDU' }), 403);
  statut(await appel(a, 'GET', `/plateforme/etablissements/${e1.id}/administrateurs`), 403);
});

scenario('TER-18', TER, 'L’administrateur pays crée un établissement de test : la direction de rattachement est obligatoire et doit être de son pays', async (ctx) => {
  const t = exigerPartage(ctx, 'adminPaysBF', 'directions.DP-HOUET', 'directionNE');
  const a = t.adminPaysBF;
  const s = suffixe();
  const corps = {
    code: `recette-pays-${s}`,
    nom: `Établissement de recette (pays) ${s}`,
    telephoneAdministrateur: telephoneUnique('76'),
    nomAdministrateur: 'RECETTE',
    prenomsAdministrateur: 'Directeur',
  };
  statut(await appel(a, 'POST', '/plateforme/etablissements', corps), 400);
  statut(await appel(a, 'POST', '/plateforme/etablissements', { ...corps, directionId: t.directionNE }), 403);
  const r = await appel(a, 'POST', '/plateforme/etablissements', { ...corps, directionId: t.directions['DP-HOUET'] });
  statut(r, 201);
  egal(r.donnees.etablissement.directionId, t.directions['DP-HOUET'], 'Direction de rattachement');
  vrai(r.donnees.etablissement.rattachement?.includes('Houet'), `Rattachement : ${r.donnees.etablissement.rattachement}`);
  egal(r.donnees.administrateur.telephone, `+226${corps.telephoneAdministrateur}`, 'Indicatif du pays de la direction');
  vrai(r.donnees.motDePasseTemporaire, 'Mot de passe provisoire de l’administrateur');
  terr(ctx).etablissementTestPays = { id: r.donnees.etablissement.id, code: corps.code, admin: corps.telephoneAdministrateur,
    provisoire: r.donnees.motDePasseTemporaire };
  return corps.code;
});

scenario('TER-19', TER, 'L’administrateur pays suspend puis réactive l’établissement de test, mais ne peut pas le résilier', async (ctx) => {
  const t = exigerPartage(ctx, 'adminPaysBF', 'etablissementTestPays');
  const a = t.adminPaysBF;
  const id = t.etablissementTestPays.id;
  try {
    egal((await exiger(a, 'PATCH', `/plateforme/etablissements/${id}/statut`, { statut: 'SUSPENDU' })).statut, 'SUSPENDU', 'Suspension');
    egal((await exiger(a, 'PATCH', `/plateforme/etablissements/${id}/statut`, { statut: 'ACTIF' })).statut, 'ACTIF', 'Réactivation');
    statut(await appel(a, 'PATCH', `/plateforme/etablissements/${id}/statut`, { statut: 'RESILIE' }), 403);
    const v = (await exiger(ctx.sa, 'GET', '/plateforme/etablissements')).find((e) => e.id === id);
    egal(v?.statut, 'ACTIF', 'La résiliation refusée ne doit rien changer');
    // Un établissement non rattaché n'est géré que par le super administrateur
    const libre = (await exiger(ctx.sa, 'GET', '/plateforme/etablissements')).find((e) => !e.directionId);
    if (libre) statut(await appel(a, 'PATCH', `/plateforme/etablissements/${libre.id}/statut`, { statut: 'SUSPENDU' }), 403);
  } finally {
    // Nettoyage : l'établissement de test sort du pilotage (les résiliés en sont exclus)
    await appel(ctx.sa, 'PATCH', `/plateforme/etablissements/${id}/statut`, { statut: 'RESILIE' });
  }
  const fin = (await exiger(ctx.sa, 'GET', '/plateforme/etablissements')).find((e) => e.id === id);
  egal(fin?.statut, 'RESILIE', 'Le super administrateur résilie l’établissement de test');
});

scenario('TER-20', TER, 'L’administrateur pays dépanne les établissements de son pays : il consulte leurs administrateurs et leurs modules', async (ctx) => {
  const t = exigerPartage(ctx, 'adminPaysBF');
  const e1 = ctx.ecole('lt-batisseurs');
  const admins = await exiger(t.adminPaysBF, 'GET', `/plateforme/etablissements/${e1.id}/administrateurs`);
  vrai(admins.some((x) => x.telephone?.endsWith(e1.admin)), 'L’administrateur de l’établissement doit être listé');
  const modules = await exiger(t.adminPaysBF, 'GET', `/plateforme/etablissements/${e1.id}/modules`);
  vrai(Array.isArray(modules) && modules.some((m) => m.code === 'ATELIERS'), 'Liste des modules attendue');
  // Les modules de lp-horizon (ATELIERS désactivé par le peuplement)
  const m3 = await exiger(t.adminPaysBF, 'GET', `/plateforme/etablissements/${ctx.ecole('lp-horizon').id}/modules`);
  vrai(m3.find((m) => m.code === 'ATELIERS')?.actif === false, 'ATELIERS doit être désactivé à lp-horizon');
});

scenario('TER-21', TER, 'Un compte d’administrateur pays ne peut pas devenir membre d’un établissement', async (ctx) => {
  const t = exigerPartage(ctx, 'adminPaysBF');
  const admin = ctx.ecole('lt-batisseurs').session('ADMIN_ECOLE');
  const r = await appel(admin, 'POST', '/membres', { telephone: t.adminPaysBF.telephone, nom: 'RECETTE', prenoms: 'Intrus', role: 'SECRETARIAT' });
  if (r.ok && r.donnees?.membre?.id) {
    await appel(admin, 'POST', `/membres/${r.donnees.membre.id}/desactivation`);
  }
  statut(r, 409, 'COMPTE_PLATEFORME');
});

scenario('TER-22', TER, 'Un administrateur pays dépanné reçoit un nouveau mot de passe provisoire ; retiré, il ne peut plus se connecter', async (ctx) => {
  const t = exigerPartage(ctx, 'paysBF');
  const tel = telephoneUnique('75');
  const n = await exiger(ctx.sa, 'POST', `${T}/pays/${t.paysBF}/administrateurs`, { telephone: tel, nom: 'Recette', prenoms: `Admin jetable ${suffixe()}` });
  const uid = n.administrateur.utilisateurId;
  await ctx.activer(tel, n.motDePasseTemporaire);
  // Réinitialisation : l'ancien mot de passe ne marche plus
  const re = await exiger(ctx.sa, 'POST', `${T}/pays/${t.paysBF}/administrateurs/${uid}/reinitialisation`);
  vrai(re.motDePasseTemporaire, 'Nouveau mot de passe provisoire attendu');
  statut(await appelBrut('POST', '/auth/connexion', { telephone: tel, motDePasse: ctx.motDePasse }), 401);
  const s = await ctx.activer(tel, re.motDePasseTemporaire);
  await s.jeton();
  egal(s.connexion.adminPays?.code, 'BF', 'Connexion après dépannage');
  // Retrait
  statut(await appel(ctx.sa, 'DELETE', `${T}/pays/${t.paysBF}/administrateurs/${uid}`), 204);
  statut(await appelBrut('POST', '/auth/connexion', { telephone: tel, motDePasse: ctx.motDePasse }), 401);
  if (s.cookie) statut(await appelBrut('POST', '/auth/rafraichir', undefined, { cookie: s.cookie }), 401);
  const liste = await exiger(ctx.sa, 'GET', `${T}/pays/${t.paysBF}/administrateurs`);
  vrai(liste.some((a) => a.utilisateurId === uid && a.actif === false), 'L’administrateur retiré doit apparaître inactif');
  statut(await appel(ctx.sa, 'DELETE', `${T}/pays/${t.paysBF}/administrateurs/${uid}/x`.replace('/x', '')), [204, 404]);
});

// ===================================================================== comptes des directions

/** Nomme un compte pour une direction et l'active (mot de passe de démonstration). */
async function compteDirection(ctx, qui, directionId, libelle) {
  const tel = telephoneUnique('77');
  const r = await appel(qui, 'POST', `${T}/directions/${directionId}/comptes`, { telephone: tel, nom: 'RECETTE', prenoms: libelle });
  statut(r, 201);
  vrai(r.donnees.motDePasseTemporaire, 'Mot de passe provisoire attendu');
  egal(r.donnees.compte.telephone, `+226${tel}`, 'Indicatif du pays de la direction');
  const s = await ctx.activer(tel, r.donnees.motDePasseTemporaire);
  s.libelle = libelle;
  await s.jeton();
  return { session: s, utilisateurId: r.donnees.compte.utilisateurId, telephone: tel };
}

scenario('PIL-01', PIL, 'L’administrateur pays crée le compte de la direction régionale du Centre, qui se connecte sur son tableau de bord', async (ctx) => {
  const t = exigerPartage(ctx, 'adminPaysBF', 'directions.DR-CENTRE');
  const c = await compteDirection(ctx, t.adminPaysBF, t.directions['DR-CENTRE'], 'Compte DR-CENTRE');
  egal(c.session.connexion.direction?.code, 'DR-CENTRE', 'La connexion doit porter la direction');
  egal(c.session.connexion.direction?.paysId, t.paysBF, 'Pays de la direction');
  vrai(!c.session.connexion.etablissementActif && !c.session.connexion.adminPays, 'Session sans établissement ni pays administré');
  const liste = await exiger(t.adminPaysBF, 'GET', `${T}/directions/${t.directions['DR-CENTRE']}/comptes`);
  vrai(liste.some((x) => x.utilisateurId === c.utilisateurId && x.actif), 'Le compte doit être listé');
  terr(ctx).comptesDirection['DR-CENTRE'] = c.session;
});

scenario('PIL-02', PIL, 'L’administrateur pays crée le compte de la direction provinciale du Kadiogo', async (ctx) => {
  const t = exigerPartage(ctx, 'adminPaysBF', 'directions.DP-KADIOGO');
  const c = await compteDirection(ctx, t.adminPaysBF, t.directions['DP-KADIOGO'], 'Compte DP-KADIOGO');
  egal(c.session.connexion.direction?.code, 'DP-KADIOGO', 'Direction du compte');
  terr(ctx).comptesDirection['DP-KADIOGO'] = c.session;
});

scenario('PIL-03', PIL, 'Les comptes de direction sont refusés hors du pays, pour un numéro déjà utilisé et aux rôles d’établissement', async (ctx) => {
  const t = exigerPartage(ctx, 'adminPaysNE', 'adminPaysBF', 'directions.DR-CENTRE', 'directions.DP-HOUET', 'comptesDirection.DR-CENTRE');
  const dr = t.directions['DR-CENTRE'];
  const corps = (telephone) => ({ telephone, nom: 'RECETTE', prenoms: 'Refus' });
  statut(await appel(t.adminPaysNE, 'POST', `${T}/directions/${dr}/comptes`, corps(telephoneUnique('77'))), 403);
  statut(await appel(t.adminPaysNE, 'GET', `${T}/directions/${dr}/comptes`), 403);
  statut(await appel(t.adminPaysBF, 'POST', `${T}/directions/${dr}/comptes`, corps(ctx.ecole('lt-batisseurs').admin)), 409, 'COMPTE_ETABLISSEMENT');
  statut(await appel(t.adminPaysBF, 'POST', `${T}/directions/${dr}/comptes`, corps(t.adminPaysBF.telephone)), 409, 'COMPTE_PLATEFORME');
  statut(await appel(t.adminPaysBF, 'POST', `${T}/directions/${t.directions['DP-HOUET']}/comptes`, corps(t.comptesDirection['DR-CENTRE'].telephone)),
    409, 'COMPTE_AUTRE_DIRECTION');
  statut(await appel(t.adminPaysBF, 'POST', `${T}/directions/00000000-0000-0000-0000-000000000000/comptes`, corps(telephoneUnique('77'))), 404);
  refuse(await appel(ctx.ecole('lt-batisseurs').session('ADMIN_ECOLE'), 'POST', `${T}/directions/${dr}/comptes`, corps(telephoneUnique('77'))),
    'Création d’un compte de direction par un administrateur d’établissement');
  refuse(await appel(t.comptesDirection['DR-CENTRE'], 'POST', `${T}/directions/${dr}/comptes`, corps(telephoneUnique('77'))),
    'Création d’un compte de direction par une direction');
});

scenario('PIL-04', PIL, 'La direction régionale du Centre voit ses deux directions provinciales et les établissements 1 et 2, pas le 3', async (ctx) => {
  const t = exigerPartage(ctx, 'comptesDirection.DR-CENTRE', 'directions.DP-KADIOGO', 'directions.DP-OUBRITENGA');
  const p = await exiger(t.comptesDirection['DR-CENTRE'], 'GET', '/pilotage');
  egal([p.perimetre.type, p.perimetre.id], ['DIRECTION', t.directions['DR-CENTRE']], 'Périmètre');
  egal(p.perimetre.niveau, 'Direction régionale', 'Niveau du périmètre');
  egal(p.niveauDirections, 'Direction provinciale', 'Niveau des directions du tableau');
  egal(p.parent, null, 'Une direction ne remonte pas au-dessus d’elle-même');
  const dirs = p.directions.map((d) => d.code);
  vrai(dirs.includes('DP-KADIOGO') && dirs.includes('DP-OUBRITENGA'), `Directions : ${dirs}`);
  vrai(!dirs.includes('DP-HOUET'), 'DP-HOUET ne dépend pas de la DR-CENTRE');
  const codes = codesEtab(p.etablissements);
  vrai(codes.includes('lt-batisseurs') && codes.includes('cfp-atelier-faso'), `Établissements : ${codes}`);
  vrai(!codes.includes('lp-horizon'), 'lp-horizon ne doit pas apparaître');
  const lt = p.etablissements.find((e) => e.code === 'lt-batisseurs');
  egal(lt.sousDirectionId, t.directions['DP-KADIOGO'], 'Ligne de cumul de lt-batisseurs');
  return `année ${p.annee} · ${p.synthese.eleves.total} élèves, ${p.synthese.classes} classes`;
});

scenario('PIL-05', PIL, 'La synthèse est la somme des établissements (élèves, classes, enseignants) et des directions provinciales', async (ctx) => {
  const t = exigerPartage(ctx, 'comptesDirection.DR-CENTRE');
  const p = await exiger(t.comptesDirection['DR-CENTRE'], 'GET', '/pilotage');
  const somme = (liste, f) => liste.reduce((s, x) => s + f(x.indicateurs), 0);
  const s = p.synthese;
  egal(s.etablissements, p.etablissements.length, 'Nombre d’établissements');
  egal(s.eleves.total, somme(p.etablissements, (i) => i.eleves.total), 'Élèves');
  egal(s.eleves.garcons + s.eleves.filles, s.eleves.total, 'Garçons + filles');
  egal(s.classes, somme(p.etablissements, (i) => i.classes), 'Classes');
  egal(s.enseignants.total, somme(p.etablissements, (i) => i.enseignants.total), 'Enseignants');
  egal(s.enseignants.total, s.titulaires.total + s.vacataires.total, 'Titulaires + vacataires');
  egal(s.eleves.total, somme(p.directions, (i) => i.eleves.total), 'Élèves cumulés par direction provinciale');
  vrai(s.eleves.total > 0 && s.classes >= 6, `La base peuplée doit donner des élèves et au moins 6 classes : ${s.eleves.total} élèves, ${s.classes} classes`);
  vrai(s.enseignants.total > 0, 'Des enseignants doivent être comptés');
  return `${s.eleves.total} élèves (${s.eleves.filles} filles), ${s.classes} classes, ${s.enseignants.total} enseignants`;
});

scenario('PIL-06', PIL, 'Le tableau de bord ne contient aucune donnée personnelle (ni nom d’élève, de parent ou d’enseignant, ni téléphone)', async (ctx) => {
  const t = exigerPartage(ctx, 'comptesDirection.DR-CENTRE');
  const r = await appel(t.comptesDirection['DR-CENTRE'], 'GET', '/pilotage');
  statut(r, 200);
  const interdits = [...nomsPersonnels(ctx.ecole('lt-batisseurs')), ...nomsPersonnels(ctx.ecole('cfp-atelier-faso'))];
  vrai(interdits.length > 50, 'L’état de démonstration doit fournir des noms à rechercher');
  sansTexte(r, interdits, 'Donnée personnelle dans le pilotage');
  return `${interdits.length} noms et numéros recherchés`;
});

scenario('PIL-07', PIL, 'La direction régionale descend vers la DP-KADIOGO et peut remonter vers elle-même', async (ctx) => {
  const t = exigerPartage(ctx, 'comptesDirection.DR-CENTRE', 'directions.DP-KADIOGO');
  const p = await exiger(t.comptesDirection['DR-CENTRE'], 'GET', '/pilotage', undefined, { params: { direction: t.directions['DP-KADIOGO'] } });
  egal(p.perimetre.id, t.directions['DP-KADIOGO'], 'Périmètre après descente');
  egal([p.parent?.type, p.parent?.id], ['DIRECTION', t.directions['DR-CENTRE']], 'Lien vers la direction régionale');
  const codes = codesEtab(p.etablissements);
  vrai(codes.includes('lt-batisseurs') && !codes.includes('cfp-atelier-faso'), `Établissements du Kadiogo : ${codes}`);
});

scenario('PIL-08', PIL, 'La DP-KADIOGO ne remonte pas à la direction régionale et ne voit pas la DP-HOUET', async (ctx) => {
  const t = exigerPartage(ctx, 'comptesDirection.DP-KADIOGO', 'comptesDirection.DR-CENTRE', 'directions.DR-CENTRE', 'directions.DP-HOUET');
  const k = t.comptesDirection['DP-KADIOGO'];
  const p = await exiger(k, 'GET', '/pilotage');
  egal(p.perimetre.id, t.directions['DP-KADIOGO'], 'Périmètre par défaut');
  egal(p.parent, null, 'Pas de remontée au-dessus de sa direction');
  vrai(!codesEtab(p.etablissements).includes('cfp-atelier-faso'), 'cfp-atelier-faso ne dépend pas du Kadiogo');
  statut(await appel(k, 'GET', '/pilotage', undefined, { params: { direction: t.directions['DR-CENTRE'] } }), 403);
  statut(await appel(k, 'GET', '/pilotage', undefined, { params: { direction: t.directions['DP-HOUET'] } }), 403);
  statut(await appel(k, 'GET', '/pilotage/export', undefined, { params: { direction: t.directions['DR-CENTRE'], format: 'pdf' } }), 403);
  statut(await appel(t.comptesDirection['DR-CENTRE'], 'GET', '/pilotage', undefined, { params: { direction: t.directions['DP-HOUET'] } }), 403);
});

scenario('PIL-09', PIL, 'L’administrateur pays voit tout le Burkina (périmètre PAYS), descend vers une direction, mais pas vers le Niger', async (ctx) => {
  const t = exigerPartage(ctx, 'adminPaysBF', 'paysBF', 'paysNE', 'directionNE', 'directions.DR-CENTRE');
  const a = t.adminPaysBF;
  const p = await exiger(a, 'GET', '/pilotage');
  egal([p.perimetre.type, p.perimetre.id], ['PAYS', t.paysBF], 'Périmètre de l’administrateur pays');
  const codes = codesEtab(p.etablissements);
  for (const c of Object.keys(RATTACHEMENTS)) vrai(codes.includes(c), `${c} absent du pilotage du pays`);
  const dirs = p.directions.map((d) => d.code);
  vrai(dirs.includes('DR-CENTRE') && dirs.includes('DR-HAUTS-BASSINS'), `Directions régionales : ${dirs}`);
  const d = await exiger(a, 'GET', '/pilotage', undefined, { params: { direction: t.directions['DR-CENTRE'] } });
  egal([d.parent?.type, d.parent?.id], ['PAYS', t.paysBF], 'Remontée vers le pays');
  statut(await appel(a, 'GET', '/pilotage', undefined, { params: { pays: t.paysNE } }), 403);
  statut(await appel(a, 'GET', '/pilotage', undefined, { params: { direction: t.directionNE } }), 403);
});

scenario('PIL-10', PIL, 'Le super administrateur choisit le pays du tableau de bord ; le Niger, sans établissement, est vide', async (ctx) => {
  const t = exigerPartage(ctx, 'paysBF', 'paysNE');
  const bf = await exiger(ctx.sa, 'GET', '/pilotage', undefined, { params: { pays: t.paysBF } });
  egal([bf.perimetre.type, bf.perimetre.id], ['PAYS', t.paysBF], 'Périmètre Burkina');
  vrai(codesEtab(bf.etablissements).includes('lp-horizon'), 'lp-horizon doit être compté au Burkina');
  const ne = await exiger(ctx.sa, 'GET', '/pilotage', undefined, { params: { pays: t.paysNE } });
  egal([ne.perimetre.type, ne.perimetre.id], ['PAYS', t.paysNE], 'Périmètre Niger');
  vrai(!codesEtab(ne.etablissements).some((c) => c in RATTACHEMENTS), 'Établissements du Burkina dans le pilotage du Niger');
  const defaut = await exiger(ctx.sa, 'GET', '/pilotage');
  egal(defaut.perimetre.type, 'PAYS', 'Sans paramètre : un pays');
});

scenario('PIL-11', PIL, 'Une année scolaire inconnue est remplacée par l’année par défaut', async (ctx) => {
  const t = exigerPartage(ctx, 'comptesDirection.DR-CENTRE');
  const d = t.comptesDirection['DR-CENTRE'];
  const defaut = await exiger(d, 'GET', '/pilotage');
  vrai(Array.isArray(defaut.annees) && defaut.annees.length >= 1, 'Années disponibles attendues');
  if (defaut.annees.includes(ctx.etat.annee.libelle)) {
    egal(defaut.annee, ctx.etat.annee.libelle, 'Année par défaut = année active des établissements');
  }
  const inconnue = await exiger(d, 'GET', '/pilotage', undefined, { params: { annee: '1999-2000' } });
  egal(inconnue.annee, defaut.annee, 'Année affichée pour une année inconnue');
  egal(inconnue.synthese.eleves.total, defaut.synthese.eleves.total, 'Mêmes nombres que l’année par défaut');
  const explicite = await exiger(d, 'GET', '/pilotage', undefined, { params: { annee: defaut.annee } });
  egal(explicite.annee, defaut.annee, 'Année demandée explicitement');
  return `années : ${defaut.annees.join(', ')}`;
});

scenario('PIL-12', PIL, 'Le tableau de bord s’exporte en Excel et en PDF ; un format inconnu est refusé', async (ctx) => {
  const t = exigerPartage(ctx, 'comptesDirection.DR-CENTRE');
  const d = t.comptesDirection['DR-CENTRE'];
  const x = await exiger(d, 'GET', '/pilotage/export', undefined, { params: { format: 'xlsx' }, fichier: true });
  vrai(x.type.includes('spreadsheetml'), `Type Excel attendu : ${x.type}`);
  vrai(x.octets.length > 0 && x.octets.subarray(0, 2).toString('latin1') === 'PK', 'Un classeur .xlsx commence par PK');
  const p = await exiger(d, 'GET', '/pilotage/export', undefined, { params: { format: 'pdf' }, fichier: true });
  vrai(p.type.includes('application/pdf'), `Type PDF attendu : ${p.type}`);
  vrai(p.octets.subarray(0, 4).toString('latin1') === '%PDF', 'Un PDF commence par %PDF');
  statut(await appel(d, 'GET', '/pilotage/export', undefined, { params: { format: 'doc' } }), 400);
  const a = ctx.partage.territoire?.adminPaysBF;
  if (a) {
    const xa = await exiger(a, 'GET', '/pilotage/export', undefined, { fichier: true });
    vrai(xa.type.includes('spreadsheetml'), 'Excel par défaut pour l’administrateur pays');
  }
  return `xlsx ${x.octets.length} octets, pdf ${p.octets.length} octets`;
});

scenario('PIL-13', PIL, 'Un compte de direction n’accède à aucune route d’établissement ni d’administration de la plateforme', async (ctx) => {
  const t = exigerPartage(ctx, 'comptesDirection.DR-CENTRE');
  const d = t.comptesDirection['DR-CENTRE'];
  const e1 = ctx.ecole('lt-batisseurs');
  const routes = [
    ['GET', '/plateforme/etablissements'],
    ['GET', `${T}/pays`],
    ['GET', '/plateforme/adoption'],
    ['GET', `/plateforme/etablissements/${e1.id}/exports`],
    ['GET', '/membres'],
    ['GET', '/identite'],
    ['GET', '/adoption'],
    ['GET', '/exports'],
    ['GET', '/organismes'],
    ['GET', `/classes/${e1.classes[0].id}`],
  ];
  for (const [m, c] of routes) refuse(await appel(d, m, c), `${m} ${c} par un compte de direction`);
  // Aucune donnée d'établissement : refus, ou liste vide (pas d'établissement dans le jeton)
  const annees = await appel(d, 'GET', '/annees');
  vrai(!annees.ok || (Array.isArray(annees.donnees) && annees.donnees.length === 0), `GET /annees doit être refusé ou vide : ${annees.resume()}`);
  // Son compte reste accessible
  const moi = await exiger(d, 'GET', '/moi');
  egal(moi.etablissementId, null, 'Pas d’établissement');
  egal(moi.roles, ['DIRECTION'], 'Rôle du jeton');
  return annees.ok ? 'GET /annees : 200 avec liste vide' : `GET /annees : ${annees.statut}`;
});

scenario('PIL-14', PIL, 'Les comptes des établissements n’ont pas accès au pilotage, et un compte de direction ne devient pas membre', async (ctx) => {
  const t = exigerPartage(ctx, 'comptesDirection.DR-CENTRE');
  const e1 = ctx.ecole('lt-batisseurs');
  for (const role of ['ADMIN_ECOLE', 'CENSEUR', 'INTENDANT']) {
    const s = e1.session(role);
    if (s) statut(await appel(s, 'GET', '/pilotage'), 403);
  }
  const ens = e1.enseignants[0] && e1.enseignant(e1.enseignants[0].matiere);
  if (ens) statut(await appel(ens, 'GET', '/pilotage/export'), 403);
  const admin = e1.session('ADMIN_ECOLE');
  const r = await appel(admin, 'POST', '/membres', { telephone: t.comptesDirection['DR-CENTRE'].telephone, nom: 'RECETTE', prenoms: 'Intrus', role: 'CENSEUR' });
  if (r.ok && r.donnees?.membre?.id) await appel(admin, 'POST', `/membres/${r.donnees.membre.id}/desactivation`);
  statut(r, 409, 'COMPTE_PLATEFORME');
});

scenario('PIL-15', PIL, 'Un compte de direction dépanné puis retiré ne peut plus se connecter', async (ctx) => {
  const t = exigerPartage(ctx, 'adminPaysBF', 'directions.DP-OUBRITENGA');
  const dir = t.directions['DP-OUBRITENGA'];
  const c = await compteDirection(ctx, t.adminPaysBF, dir, 'Compte DP-OUBRITENGA jetable');
  const re = await exiger(t.adminPaysBF, 'POST', `${T}/directions/${dir}/comptes/${c.utilisateurId}/reinitialisation`);
  vrai(re.motDePasseTemporaire, 'Nouveau mot de passe provisoire');
  statut(await appelBrut('POST', '/auth/connexion', { telephone: c.telephone, motDePasse: ctx.motDePasse }), 401);
  await ctx.activer(c.telephone, re.motDePasseTemporaire);
  statut(await appel(t.adminPaysBF, 'DELETE', `${T}/directions/${dir}/comptes/${c.utilisateurId}`), 204);
  statut(await appelBrut('POST', '/auth/connexion', { telephone: c.telephone, motDePasse: ctx.motDePasse }), 401);
  statut(await appel(t.adminPaysBF, 'DELETE', `${T}/directions/${dir}/comptes/${c.utilisateurId}`), 404);
  const liste = await exiger(t.adminPaysBF, 'GET', `${T}/directions/${dir}/comptes`);
  vrai(liste.some((x) => x.utilisateurId === c.utilisateurId && x.actif === false), 'Le compte retiré doit être listé inactif');
});

// Garde-fou : API est importée pour les lots qui voudraient l'afficher ; Echec reste disponible pour les aides.
void API;
void Echec;
