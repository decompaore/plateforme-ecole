/**
 * Recette : ateliers (paramètres, catalogue des prix, ateliers et responsables, équipements et pannes,
 * matière d'œuvre, inventaires, exports) puis circuit des besoins (fiche d'un atelier, arbitrage,
 * validation, commande, réception, répartition, exports).
 *
 * Sources : ateliers/AteliersController, BesoinsController, AccesAteliers, *Service, Vues, VuesBesoins,
 * ExportsAteliers, socle/modules/ModulesFilter, docs/API_ATELIERS.md.
 *
 * Tout se passe au lycée technique Les Bâtisseurs, dans un atelier de test créé par la recette (code
 * unique), avec des articles de catalogue propres à la recette : les ateliers de démonstration ne sont
 * pas modifiés. Les campagnes de besoins existent déjà (une par type et par année, créées par le
 * peuplement) : la recette les réutilise. L'atelier de test est fermé et son mandat terminé à la fin.
 */
import {
  scenario, appel, exiger, statut, refuse, vrai, egal, ignorer, suffixe, cle, fichierPng, ajouterJours,
} from '../lib.mjs';

const ATELIERS = 'Ateliers';
const BESOINS = 'Besoins et commandes';

// ===================================================================== outils

/** Données partagées entre les scénarios de ce fichier. */
function partage(ctx) {
  ctx.partage.ateliers ??= {};
  return ctx.partage.ateliers;
}

/** Valeur déposée par un scénario précédent, sinon le scénario est ignoré. */
function requis(ctx, cleValeur, raison) {
  const v = partage(ctx)[cleValeur];
  if (v === undefined || v === null) ignorer(raison ?? `prérequis absent (${cleValeur}) : scénario précédent en échec`);
  return v;
}

function num(x) {
  return x === null || x === undefined ? null : Number(x);
}

/** Le lycée technique et ses comptes utiles aux ateliers. */
function lycee(ctx) {
  const e = ctx.ecole('lt-batisseurs');
  if (!e) ignorer('lycée technique absent de la base de démonstration');
  if (!e.ateliers?.length) ignorer('le peuplement n’a créé aucun atelier au lycée technique');
  return e;
}

function chefDe(e) {
  return e.session('CHEF_TRAVAUX') ?? ignorer(`pas de chef des travaux à ${e.code}`);
}

/**
 * Enseignants de la filière F3 (atelier de test) : un futur responsable (matière technique), un
 * enseignant technique ordinaire (signale les pannes, propose des besoins) et un enseignant d'une matière
 * générale (sans accès à l'atelier). Repérés dans l'état du peuplement.
 */
function acteurs(ctx) {
  const p = partage(ctx);
  if (p.acteurs) return p.acteurs;
  const e = lycee(ctx);
  const classe = e.classes.find((c) => c.filiere === 'F3') ?? ignorer('classe de la filière F3 absente');
  const techniques = classe.matieres.filter((m) => m.type !== 'GENERALE' && m.enseignant);
  const generales = classe.matieres.filter((m) => m.type === 'GENERALE' && m.enseignant
    && !techniques.some((t) => t.enseignant === m.enseignant));
  const telResp = (techniques.find((m) => m.code === 'DESSIN-EL') ?? techniques[0])?.enseignant;
  const telTech = (techniques.find((m) => m.code === 'TP-ELEC' && m.enseignant !== telResp)
    ?? techniques.find((m) => m.enseignant !== telResp))?.enseignant;
  const telGen = (generales.find((m) => m.code === 'MATH') ?? generales[0])?.enseignant;
  if (!telResp || !telTech || !telGen) ignorer('enseignants techniques ou généraux de la F3 introuvables dans l’état');
  const engagement = (tel) => e.enseignants.find((x) => x.telephone === tel)?.engagementId ?? null;
  p.acteurs = {
    e,
    classe,
    chef: chefDe(e),
    intendant: e.session('INTENDANT') ?? ignorer('pas d’intendant'),
    resp: { session: ctx.compte(telResp, e.id, `responsable d’atelier ${e.code}`), engagementId: engagement(telResp) },
    tech: { session: ctx.compte(telTech, e.id, `enseignant technique ${e.code}`), engagementId: engagement(telTech) },
    general: { session: ctx.compte(telGen, e.id, `enseignant de matière générale ${e.code}`), engagementId: engagement(telGen) },
  };
  return p.acteurs;
}

/** Vérifie qu'un export est un fichier Excel (zip « PK ») ou PDF (« %PDF ») en pièce jointe. */
function verifierFichier(r, format, quoi) {
  if (!r.ok) throw new (class extends Error {})(`Export ${quoi} : ${r.resume()}`);
  const o = r.octets;
  vrai(o && o.length > 100, `Export ${quoi} (${format}) vide`);
  if (format === 'pdf') {
    vrai(o.subarray(0, 4).toString('latin1') === '%PDF', `Export ${quoi} : PDF attendu (type ${r.type})`);
  } else {
    vrai(o[0] === 0x50 && o[1] === 0x4b, `Export ${quoi} : classeur Excel attendu (type ${r.type})`);
  }
  vrai(/attachment/i.test(r.entetes.get('content-disposition') ?? ''), `Export ${quoi} : pièce jointe attendue`);
}

async function exporter(qui, chemin, format) {
  return appel(qui, 'GET', chemin, undefined, { fichier: true, params: { format } });
}

async function ligneStock(qui, atelierId, articleId) {
  const stock = await exiger(qui, 'GET', `/ateliers/${atelierId}/stock`);
  return stock.find((l) => l.articleId === articleId) ?? null;
}

async function resumeAtelier(qui, atelierId) {
  return (await exiger(qui, 'GET', '/ateliers')).find((a) => a.id === atelierId) ?? null;
}

// ===================================================================== paramètres et accès

scenario('ATE-01', ATELIERS, 'Le chef des travaux lit et réenregistre les paramètres des ateliers ; une durée de mandat hors bornes est refusée et l’intendant ne les modifie pas', async (ctx) => {
  const { chef, intendant, tech } = acteurs(ctx);
  const p = await exiger(chef, 'GET', '/parametres/ateliers');
  vrai('dureeMandatMois' in p && ['SEMESTRIELLE', 'ANNUELLE'].includes(p.frequenceInventaire), 'paramètres incomplets');
  partage(ctx).parametres = p;
  // Les enseignants du module les consultent
  await exiger(tech.session, 'GET', '/parametres/ateliers');
  statut(await appel(chef, 'PUT', '/parametres/ateliers', { dureeMandatMois: 3, frequenceInventaire: p.frequenceInventaire }), 400);
  statut(await appel(chef, 'PUT', '/parametres/ateliers', { dureeMandatMois: 121, frequenceInventaire: p.frequenceInventaire }), 400);
  refuse(await appel(intendant, 'PUT', '/parametres/ateliers', p), 'L’intendant ne modifie pas les paramètres');
  // Réenregistrement à l'identique : aucun effet durable
  const r = await exiger(chef, 'PUT', '/parametres/ateliers', p);
  egal(r, p, 'paramètres réenregistrés');
  return `mandat ${p.dureeMandatMois ?? 'sans limite'} mois, inventaire ${p.frequenceInventaire}`;
});

scenario('ATE-02', ATELIERS, 'Au lycée Horizon, le module Ateliers est désactivé : ateliers, catalogue et campagnes répondent 403 MODULE_DESACTIVE', async (ctx) => {
  const h = ctx.ecole('lp-horizon') ?? ignorer('lycée Horizon absent');
  const admin = h.session('ADMIN_ECOLE');
  for (const chemin of ['/ateliers', '/catalogue', '/campagnes-besoins', '/parametres/ateliers']) {
    statut(await appel(admin, 'GET', chemin), 403, 'MODULE_DESACTIVE');
  }
  statut(await appel(admin, 'POST', '/ateliers', { code: `X${suffixe()}`, nom: 'Atelier', filieres: [] }), 403, 'MODULE_DESACTIVE');
});

scenario('ATE-03', ATELIERS, 'Le censeur, le surveillant et les parents n’ont pas accès aux ateliers', async (ctx) => {
  const { e } = acteurs(ctx);
  const testes = [];
  for (const role of ['CENSEUR', 'SURVEILLANT', 'SECRETARIAT']) {
    const s = e.session(role);
    if (!s) continue;
    refuse(await appel(s, 'GET', '/ateliers'), `${role} : accès aux ateliers refusé attendu`);
    refuse(await appel(s, 'GET', '/catalogue'), `${role} : accès au catalogue refusé attendu`);
    testes.push(role);
  }
  const parent = e.parent(0);
  if (parent) {
    refuse(await appel(parent, 'GET', '/ateliers'), 'Parent : accès aux ateliers refusé attendu');
    testes.push('PARENT');
  }
  vrai(testes.length, 'aucun rôle à tester');
  return testes.join(', ');
});

// ===================================================================== catalogue des prix

scenario('ATE-04', ATELIERS, 'L’intendant crée une matière d’œuvre et un équipement au catalogue (code mis en majuscules, spécifications, normes, prix)', async (ctx) => {
  const { intendant } = acteurs(ctx);
  const s = suffixe();
  const matiere = await exiger(intendant, 'POST', '/catalogue', {
    code: `rec-m-${s}`, designation: `Câble souple 1,5 mm² (recette ${s})`, nature: 'MATIERE_OEUVRE', unite: 'mètre',
    filiereId: null, specifications: 'Cuivre multibrin, isolant PVC', normes: 'NF C 32-201', prixReference: 450,
  });
  egal(matiere.code, `REC-M-${s.toUpperCase()}`, 'code mis en majuscules');
  egal(matiere.nature, 'MATIERE_OEUVRE', 'nature');
  egal(num(matiere.prixReference), 450, 'prix de référence');
  vrai(matiere.actif === true && matiere.photo === false, 'article actif, sans photo');
  const equipement = await exiger(intendant, 'POST', '/catalogue', {
    code: `REC-E-${s}`, designation: `Pince à dénuder (recette ${s})`, nature: 'EQUIPEMENT', unite: 'pièce',
    specifications: 'Réglable de 0,5 à 6 mm²', normes: 'NF EN 60900', prixReference: 12000,
  });
  egal(equipement.nature, 'EQUIPEMENT', 'nature de l’équipement');
  const liste = await exiger(intendant, 'GET', '/catalogue');
  vrai(liste.some((a) => a.id === matiere.id) && liste.some((a) => a.id === equipement.id), 'articles absents du catalogue');
  Object.assign(partage(ctx), { suffixe: s, matiere, equipement });
  return `${matiere.code}, ${equipement.code}`;
});

scenario('ATE-05', ATELIERS, 'Le catalogue refuse un code déjà pris (409 CODE_EXISTANT), une nature ou une désignation manquante (400)', async (ctx) => {
  const { intendant } = acteurs(ctx);
  const m = requis(ctx, 'matiere');
  statut(await appel(intendant, 'POST', '/catalogue', {
    code: m.code.toLowerCase(), designation: 'Doublon', nature: 'MATIERE_OEUVRE', unite: 'pièce',
  }), 409, 'CODE_EXISTANT');
  statut(await appel(intendant, 'POST', '/catalogue', { code: `REC-N-${suffixe()}`, designation: 'Sans nature', unite: 'pièce' }), 400);
  statut(await appel(intendant, 'POST', '/catalogue', { code: `REC-D-${suffixe()}`, nature: 'MATIERE_OEUVRE', unite: 'pièce' }), 400);
  statut(await appel(intendant, 'POST', '/catalogue', { code: 'é invalide', designation: 'Code invalide', nature: 'MATIERE_OEUVRE', unite: 'pièce' }), 400);
  statut(await appel(intendant, 'POST', '/catalogue', {
    code: `REC-P-${suffixe()}`, designation: 'Prix négatif', nature: 'MATIERE_OEUVRE', unite: 'pièce', prixReference: -5,
  }), 400);
});

scenario('ATE-06', ATELIERS, 'Un enseignant qui n’est responsable d’aucun atelier consulte le catalogue mais ne le modifie pas', async (ctx) => {
  const { tech } = acteurs(ctx);
  const m = requis(ctx, 'matiere');
  const liste = await exiger(tech.session, 'GET', '/catalogue');
  vrai(liste.some((a) => a.id === m.id), 'catalogue incomplet pour l’enseignant');
  // L'enseignant technique ordinaire n'est responsable d'aucun atelier dans la démonstration (sinon : ignoré)
  const r = await appel(tech.session, 'POST', '/catalogue', { code: `REC-X-${suffixe()}`, designation: 'Essai', nature: 'MATIERE_OEUVRE', unite: 'pièce' });
  if (r.ok) ignorer('cet enseignant est responsable d’un atelier : il tient le catalogue');
  statut(r, 403);
  statut(await appel(tech.session, 'PUT', `/catalogue/${m.id}`, { ...m, prixReference: 1 }), 403);
});

scenario('ATE-07', ATELIERS, 'L’intendant change le prix de référence d’un article : le nouveau prix est daté, le code et la nature ne changent pas', async (ctx) => {
  const { intendant } = acteurs(ctx);
  const m = requis(ctx, 'matiere');
  const r = await exiger(intendant, 'PUT', `/catalogue/${m.id}`, {
    code: 'AUTRE-CODE', designation: m.designation, nature: 'EQUIPEMENT', unite: m.unite, filiereId: null,
    specifications: m.specifications, normes: m.normes, prixReference: 500, actif: true,
  });
  egal(num(r.prixReference), 500, 'nouveau prix');
  egal(r.code, m.code, 'le code ne change pas');
  egal(r.nature, 'MATIERE_OEUVRE', 'la nature ne change pas');
  vrai(r.prixModifieLe, 'date du changement de prix absente');
  partage(ctx).matiere = r;
  statut(await appel(intendant, 'PUT', `/catalogue/${m.id}`, { ...r, designation: ' ' }), 400);
  statut(await appel(intendant, 'PUT', `/catalogue/${cle()}`, r), 404);
  return `prix modifié le ${r.prixModifieLe}`;
});

scenario('ATE-08', ATELIERS, 'La photo d’un article : un PNG est accepté et relu, un fichier texte est refusé (400)', async (ctx) => {
  const { intendant, tech } = acteurs(ctx);
  const m = requis(ctx, 'matiere');
  const f = new FormData();
  f.append('fichier', fichierPng(), 'cable.png');
  const r = await exiger(intendant, 'POST', `/catalogue/${m.id}/photo`, f);
  egal(r.photo, true, 'photo enregistrée');
  const photo = await appel(tech.session, 'GET', `/catalogue/${m.id}/photo`, undefined, { fichier: true });
  statut(photo, 200);
  vrai(photo.type.startsWith('image/png'), `type de la photo : ${photo.type}`);
  vrai(photo.octets?.subarray(1, 4).toString('latin1') === 'PNG', 'contenu PNG attendu');
  const texte = new FormData();
  texte.append('fichier', new Blob(['pas une image'], { type: 'text/plain' }), 'notes.txt');
  statut(await appel(intendant, 'POST', `/catalogue/${m.id}/photo`, texte), 400);
  // L'équipement n'a pas de photo
  statut(await appel(intendant, 'GET', `/catalogue/${requis(ctx, 'equipement').id}/photo`), 404);
});

// ===================================================================== ateliers et responsables

scenario('ATE-09', ATELIERS, 'Le chef des travaux crée un atelier pour la filière F3 ; sans filière (400), code déjà pris (409) ou par l’intendant (403), c’est refusé', async (ctx) => {
  const { e, classe, chef, intendant } = acteurs(ctx);
  const filiereId = (await exiger(chef, 'GET', `/classes/${classe.id}`)).filiereId;
  vrai(filiereId, 'filière de la classe introuvable');
  const code = `REC-${suffixe().toUpperCase()}`;
  const corps = { code, nom: `Atelier de recette ${code}`, emplacement: 'Bâtiment B, salle 4', postes: 16, filieres: [filiereId] };
  statut(await appel(intendant, 'POST', '/ateliers', corps), 403);
  statut(await appel(chef, 'POST', '/ateliers', { ...corps, filieres: [] }), 400);
  statut(await appel(chef, 'POST', '/ateliers', { ...corps, postes: 0 }), 400);
  const a = await exiger(chef, 'POST', '/ateliers', corps);
  egal(a.code, code, 'code de l’atelier');
  vrai(a.ouvert === true, 'atelier ouvert attendu');
  vrai(a.filieres.some((f) => f.id === filiereId), 'filière F3 attendue');
  vrai(a.responsable === null && a.alertes.sansResponsable === true, 'un nouvel atelier est signalé sans responsable');
  vrai(a.droits.gerer === true, 'le chef des travaux gère l’atelier');
  statut(await appel(chef, 'POST', '/ateliers', { ...corps, code: code.toLowerCase() }), 409, 'CODE_EXISTANT');
  Object.assign(partage(ctx), { atelier: a, filiereId });
  return `${code} (${e.code})`;
});

scenario('ATE-10', ATELIERS, 'Les candidats au poste de responsable sont les enseignants techniques de la filière ; un enseignant de matière générale est refusé (409 RESPONSABLE_NON_ELIGIBLE)', async (ctx) => {
  const { chef, resp, general, tech } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const candidats = await exiger(chef, 'GET', `/ateliers/${a.id}/candidats`);
  vrai(candidats.some((c) => c.engagementId === resp.engagementId), 'le futur responsable devrait être candidat');
  vrai(!candidats.some((c) => c.engagementId === general.engagementId), 'un enseignant de matière générale ne doit pas être candidat');
  vrai(candidats.every((c) => Array.isArray(c.matieres) && c.matieres.length), 'matières enseignées attendues');
  refuse(await appel(tech.session, 'GET', `/ateliers/${a.id}/candidats`), 'Un enseignant ne voit pas les candidats');
  statut(await appel(chef, 'POST', `/ateliers/${a.id}/responsable`, { engagementId: general.engagementId, debut: ctx.aujourdhui }), 409, 'RESPONSABLE_NON_ELIGIBLE');
  statut(await appel(chef, 'POST', `/ateliers/${a.id}/responsable`, { debut: ctx.aujourdhui }), 400);
  return `${candidats.length} candidat(s)`;
});

scenario('ATE-11', ATELIERS, 'Le chef des travaux désigne le responsable : le mandat dure ce que fixe l’établissement ; désigner le même enseignant une seconde fois est refusé', async (ctx) => {
  const { chef, resp, tech } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const debut = `${ctx.aujourdhui.slice(0, 8)}01`;
  statut(await appel(tech.session, 'POST', `/ateliers/${a.id}/responsable`, { engagementId: resp.engagementId, debut }), 403);
  const r = await exiger(chef, 'POST', `/ateliers/${a.id}/responsable`, { engagementId: resp.engagementId, debut, finPrevue: null });
  vrai(r.responsable, 'responsable absent');
  egal(r.responsable.engagementId, resp.engagementId, 'responsable désigné');
  egal(r.responsable.debut, debut, 'début du mandat');
  const duree = partage(ctx).parametres?.dureeMandatMois;
  if (duree) {
    const d = new Date(`${debut}T12:00:00Z`);
    d.setUTCMonth(d.getUTCMonth() + duree);
    egal(r.responsable.finPrevue, d.toISOString().slice(0, 10), 'fin prévue = début + durée de l’établissement');
  }
  egal(r.alertes.sansResponsable, false, 'l’atelier a désormais un responsable');
  statut(await appel(chef, 'POST', `/ateliers/${a.id}/responsable`, { engagementId: resp.engagementId, debut: ctx.aujourdhui }), 409, 'DEJA_RESPONSABLE');
  partage(ctx).responsableDesigne = true;
  return `mandat du ${r.responsable.debut} au ${r.responsable.finPrevue ?? '—'}`;
});

scenario('ATE-12', ATELIERS, 'Visibilité : le responsable et l’enseignant technique voient l’atelier, l’enseignant de matière générale non, un autre établissement non plus', async (ctx) => {
  const { resp, tech, general, intendant } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  requis(ctx, 'responsableDesigne');
  const vuResp = await resumeAtelier(resp.session, a.id);
  vrai(vuResp && vuResp.droits.responsable === true, 'le responsable doit voir son atelier avec ses droits');
  const vuTech = await resumeAtelier(tech.session, a.id);
  vrai(vuTech && vuTech.droits.signaler === true && vuTech.droits.responsable === false && vuTech.droits.gerer === false,
    'l’enseignant technique voit l’atelier et peut seulement signaler');
  vrai(await resumeAtelier(intendant, a.id), 'l’intendant voit tous les ateliers');
  egal(await resumeAtelier(general.session, a.id), null, 'l’enseignant de matière générale ne doit pas voir l’atelier dans sa liste');
  statut(await appel(general.session, 'GET', `/ateliers/${a.id}`), 403);
  const cfp = ctx.ecole('cfp-atelier-faso');
  if (cfp?.session('CHEF_TRAVAUX')) {
    refuse(await appel(cfp.session('CHEF_TRAVAUX'), 'GET', `/ateliers/${a.id}`), 'Atelier d’un autre établissement');
    vrai(!(await exiger(cfp.session('CHEF_TRAVAUX'), 'GET', '/ateliers')).some((x) => x.id === a.id), 'atelier visible depuis le centre de formation');
  }
});

// ===================================================================== équipements et pannes

scenario('ATE-13', ATELIERS, 'Le responsable inventorie deux équipements (numéro CODE-ANNÉE-RANG, désignation du catalogue) ; doublon de numéro, matière d’œuvre ou enseignant ordinaire refusés', async (ctx) => {
  const { resp, tech } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const art = requis(ctx, 'equipement');
  const corps = { articleId: art.id, designation: null, numeroInventaire: null, marque: 'Facom', numeroSerie: null,
    dateAcquisition: ajouterJours(ctx.aujourdhui, -120), valeur: 12000 };
  statut(await appel(tech.session, 'POST', `/ateliers/${a.id}/equipements`, corps), 403);
  statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/equipements`, { ...corps, articleId: requis(ctx, 'matiere').id }), 400);
  statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/equipements`, { ...corps, dateAcquisition: ajouterJours(ctx.aujourdhui, 5) }), 400);
  const e1 = await exiger(resp.session, 'POST', `/ateliers/${a.id}/equipements`, corps);
  const e2 = await exiger(resp.session, 'POST', `/ateliers/${a.id}/equipements`, { ...corps, marque: 'Knipex' });
  vrai(e1.numeroInventaire.startsWith(`${a.code}-`) && /-\d{4}-\d{3}$/.test(e1.numeroInventaire), `numéro automatique : ${e1.numeroInventaire}`);
  vrai(e1.numeroInventaire !== e2.numeroInventaire, 'deux numéros distincts attendus');
  egal(e1.designation, art.designation, 'désignation reprise du catalogue');
  egal(e1.etat, 'BON', 'état initial');
  statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/equipements`, { ...corps, numeroInventaire: e1.numeroInventaire.toLowerCase() }), 409, 'NUMERO_EXISTANT');
  const liste = await exiger(tech.session, 'GET', `/ateliers/${a.id}/equipements`);
  egal(liste.filter((x) => [e1.id, e2.id].includes(x.id)).length, 2, 'équipements visibles par l’enseignant de l’atelier');
  partage(ctx).equipements = [e1, e2];
  return `${e1.numeroInventaire}, ${e2.numeroInventaire}`;
});

scenario('ATE-14', ATELIERS, 'Un enseignant de l’atelier signale une panne : l’équipement passe EN_PANNE ; une seconde panne ouverte et un enseignant étranger à l’atelier sont refusés', async (ctx) => {
  const { tech, general, chef } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const [eq] = requis(ctx, 'equipements');
  statut(await appel(general.session, 'POST', `/equipements/${eq.id}/pannes`, { description: 'Essai' }), 403);
  statut(await appel(tech.session, 'POST', `/equipements/${eq.id}/pannes`, { description: '  ' }), 400);
  const panne = await exiger(tech.session, 'POST', `/equipements/${eq.id}/pannes`, { description: 'Mâchoires désalignées, ne coupe plus' });
  egal(panne.statut, 'OUVERTE', 'panne ouverte');
  vrai(panne.signaleePar, 'auteur du signalement absent');
  statut(await appel(tech.session, 'POST', `/equipements/${eq.id}/pannes`, { description: 'Encore' }), 409, 'PANNE_DEJA_SIGNALEE');
  const liste = await exiger(tech.session, 'GET', `/ateliers/${a.id}/equipements`);
  const vu = liste.find((x) => x.id === eq.id);
  egal(vu?.etat, 'EN_PANNE', 'état de l’équipement');
  egal(vu?.panneOuverte?.id, panne.id, 'panne ouverte rattachée à l’équipement');
  const resume = await resumeAtelier(chef, a.id);
  vrai(resume.alertes.equipementsEnPanne >= 1, 'alerte « équipements en panne » attendue');
  partage(ctx).panne = panne;
});

scenario('ATE-15', ATELIERS, 'Le responsable clôture la panne (réparée) : l’équipement revient en service ; l’enseignant ordinaire ne clôture pas et une panne close ne se clôt pas deux fois', async (ctx) => {
  const { tech, resp } = acteurs(ctx);
  const [eq] = requis(ctx, 'equipements');
  const panne = requis(ctx, 'panne');
  statut(await appel(tech.session, 'POST', `/pannes/${panne.id}/cloture`, { statut: 'REPAREE', intervention: 'x', cout: 0 }), 403);
  statut(await appel(resp.session, 'POST', `/pannes/${panne.id}/cloture`, { statut: 'OUVERTE' }), 400);
  const r = await exiger(resp.session, 'POST', `/pannes/${panne.id}/cloture`, { statut: 'REPAREE', intervention: 'Mâchoires réalignées', cout: 2500 });
  egal(r.statut, 'REPAREE', 'statut de la panne');
  egal(num(r.cout), 2500, 'coût de l’intervention');
  vrai(r.clotureePar && r.clotureeLe, 'auteur et date de clôture attendus');
  statut(await appel(resp.session, 'POST', `/pannes/${panne.id}/cloture`, { statut: 'REPAREE' }), 409, 'PANNE_CLOTUREE');
  const historique = await exiger(tech.session, 'GET', `/equipements/${eq.id}/pannes`);
  vrai(historique.some((p) => p.id === panne.id && p.statut === 'REPAREE'), 'historique des pannes');
  const vu = (await exiger(resp.session, 'GET', `/ateliers/${requis(ctx, 'atelier').id}/equipements`)).find((x) => x.id === eq.id);
  egal(vu?.etat, 'BON', 'équipement remis en service');
  egal(vu?.panneOuverte ?? null, null, 'plus de panne ouverte');
});

// ===================================================================== matière d'œuvre

scenario('ATE-16', ATELIERS, 'Le responsable enregistre une entrée puis une sortie de matière d’œuvre : le stock et l’historique suivent', async (ctx) => {
  const { resp, tech } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const m = requis(ctx, 'matiere');
  const entree = await exiger(resp.session, 'POST', `/ateliers/${a.id}/mouvements`,
    { articleId: m.id, type: 'ENTREE', quantite: 20, date: ctx.aujourdhui, motif: 'Dotation de la recette' });
  egal(num(entree.stockApres), 20, 'stock après l’entrée');
  const sortie = await exiger(resp.session, 'POST', `/ateliers/${a.id}/mouvements`,
    { articleId: m.id, type: 'SORTIE', quantite: 6.5, date: ctx.aujourdhui, motif: 'TP câblage 2nde F3' });
  egal(num(sortie.stockApres), 13.5, 'stock après la sortie');
  egal(num(sortie.quantite), -6.5, 'une sortie est enregistrée en négatif');
  const ligne = await ligneStock(tech.session, a.id, m.id);
  egal(num(ligne?.quantite), 13.5, 'stock lu par l’enseignant de l’atelier');
  egal(num(ligne?.prixReference), num(m.prixReference), 'prix du catalogue dans le stock');
  const mvts = await exiger(tech.session, 'GET', `/ateliers/${a.id}/mouvements`, undefined, { params: { articleId: m.id } });
  vrai(mvts.some((x) => x.id === entree.id) && mvts.some((x) => x.id === sortie.id), 'mouvements absents de l’historique');
  partage(ctx).stockInitial = 13.5;
});

scenario('ATE-17', ATELIERS, 'Une sortie supérieure au stock est refusée (409 STOCK_INSUFFISANT) ; quantité négative, date future, équipement en quantité, enseignant ordinaire : refusés', async (ctx) => {
  const { resp, tech } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const m = requis(ctx, 'matiere');
  const avant = requis(ctx, 'stockInitial');
  const base = { articleId: m.id, type: 'SORTIE', date: ctx.aujourdhui, motif: 'TP' };
  statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/mouvements`, { ...base, quantite: avant + 100 }), 409, 'STOCK_INSUFFISANT');
  statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/mouvements`, { ...base, quantite: -2 }), 400);
  statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/mouvements`, { ...base, quantite: 1.234 }), 400);
  statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/mouvements`, { ...base, quantite: 1, date: ajouterJours(ctx.aujourdhui, 3) }), 400);
  statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/mouvements`, { ...base, type: 'INVENTAIRE', quantite: 1 }), 400);
  statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/mouvements`, { ...base, type: 'ENTREE', articleId: requis(ctx, 'equipement').id, quantite: 1 }), 400);
  statut(await appel(tech.session, 'POST', `/ateliers/${a.id}/mouvements`, { ...base, quantite: 1 }), 403);
  egal(num((await ligneStock(resp.session, a.id, m.id))?.quantite), avant, 'le stock ne doit pas avoir bougé');
});

scenario('ATE-18', ATELIERS, 'Le seuil d’alerte : sous le seuil, l’article est signalé dans le stock et dans les alertes de l’atelier', async (ctx) => {
  const { resp, chef, tech } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const m = requis(ctx, 'matiere');
  statut(await appel(tech.session, 'PUT', `/ateliers/${a.id}/stock/${m.id}/seuil`, { seuil: 15 }), 403);
  statut(await appel(resp.session, 'PUT', `/ateliers/${a.id}/stock/${m.id}/seuil`, { seuil: -1 }), 400);
  statut(await appel(resp.session, 'PUT', `/ateliers/${a.id}/stock/${requis(ctx, 'equipement').id}/seuil`, { seuil: 1 }), 400);
  const l = await exiger(resp.session, 'PUT', `/ateliers/${a.id}/stock/${m.id}/seuil`, { seuil: 15 });
  egal(num(l.seuilAlerte), 15, 'seuil enregistré');
  egal(l.sousLeSeuil, true, `13,5 ≤ 15 : sous le seuil (stock ${l.quantite})`);
  const resume = await resumeAtelier(chef, a.id);
  vrai(resume.alertes.articlesSousSeuil >= 1, 'alerte « articles sous le seuil » attendue');
  vrai(resume.articles >= 1, 'compteur d’articles');
});

// ===================================================================== inventaire

scenario('ATE-19', ATELIERS, 'Le responsable ouvre un inventaire : il recopie le stock et les équipements, fige les mouvements et ne peut pas être ouvert deux fois', async (ctx) => {
  const { resp, chef, tech } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const m = requis(ctx, 'matiere');
  const eqs = requis(ctx, 'equipements');
  statut(await appel(tech.session, 'POST', `/ateliers/${a.id}/inventaires`, {}), 403);
  const inv = await exiger(resp.session, 'POST', `/ateliers/${a.id}/inventaires`, { libelle: `Inventaire de recette ${partage(ctx).suffixe}` });
  egal(inv.statut, 'EN_COURS', 'inventaire en cours');
  vrai(inv.modifiable === true, 'inventaire modifiable par le responsable');
  const lm = inv.matieres.find((x) => x.articleId === m.id);
  egal(num(lm?.quantiteTheorique), 13.5, 'quantité théorique recopiée du stock');
  vrai(eqs.every((x) => inv.equipements.some((l) => l.equipementId === x.id)), 'équipements recopiés');
  egal(inv.restantes, inv.matieres.length + inv.equipements.length, 'toutes les lignes restent à renseigner');
  statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/inventaires`, {}), 409, 'INVENTAIRE_EN_COURS');
  statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/mouvements`, { articleId: m.id, type: 'ENTREE', quantite: 1, date: ctx.aujourdhui }), 409, 'INVENTAIRE_EN_COURS');
  const resume = await resumeAtelier(chef, a.id);
  egal(resume.alertes.inventaireEnCours, true, 'alerte « inventaire en cours »');
  partage(ctx).inventaire = inv;
  return inv.libelle;
});

scenario('ATE-20', ATELIERS, 'Un inventaire saisi en partie ne peut pas être clos (409 INVENTAIRE_INCOMPLET) ; une ligne étrangère est refusée (400)', async (ctx) => {
  const { resp, tech } = acteurs(ctx);
  const inv = requis(ctx, 'inventaire');
  const m = requis(ctx, 'matiere');
  statut(await appel(tech.session, 'PUT', `/inventaires/${inv.id}/lignes`, { matieres: [], equipements: [] }), 403);
  statut(await appel(resp.session, 'PUT', `/inventaires/${inv.id}/lignes`, { matieres: [{ articleId: cle(), quantiteConstatee: 1 }] }), 400);
  const partielle = await exiger(resp.session, 'PUT', `/inventaires/${inv.id}/lignes`,
    { matieres: [{ articleId: m.id, quantiteConstatee: 12 }], equipements: [], observations: null });
  vrai(partielle.restantes > 0, 'des lignes doivent rester à renseigner');
  statut(await appel(resp.session, 'POST', `/inventaires/${inv.id}/cloture`), 409, 'INVENTAIRE_INCOMPLET');
  return `${partielle.restantes} ligne(s) restantes`;
});

scenario('ATE-21', ATELIERS, 'L’inventaire complet est clos : le stock est corrigé par un mouvement INVENTAIRE, l’état constaté des équipements est repris, puis l’inventaire est figé', async (ctx) => {
  const { resp, chef, tech } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const inv = requis(ctx, 'inventaire');
  const m = requis(ctx, 'matiere');
  const [e1, e2] = requis(ctx, 'equipements');
  const fiche = await exiger(resp.session, 'GET', `/inventaires/${inv.id}`);
  const matieres = fiche.matieres.map((l) => ({ articleId: l.articleId, quantiteConstatee: l.articleId === m.id ? 12 : num(l.quantiteTheorique) }));
  const equipements = fiche.equipements.map((l) => ({
    equipementId: l.equipementId,
    etatConstate: l.equipementId === e2.id ? 'MANQUANT' : l.etatTheorique,
    observation: l.equipementId === e2.id ? 'Introuvable lors du comptage' : null,
  }));
  const saisie = await exiger(resp.session, 'PUT', `/inventaires/${inv.id}/lignes`, { matieres, equipements, observations: 'Inventaire de recette' });
  egal(saisie.restantes, 0, 'toutes les lignes sont renseignées');
  egal(num(saisie.matieres.find((l) => l.articleId === m.id)?.ecart), -1.5, 'écart affiché');
  const clos = await exiger(resp.session, 'POST', `/inventaires/${inv.id}/cloture`);
  egal(clos.statut, 'CLOS', 'inventaire clos');
  egal(clos.modifiable, false, 'un inventaire clos n’est plus modifiable');
  egal(num((await ligneStock(tech.session, a.id, m.id))?.quantite), 12, 'stock corrigé par l’inventaire');
  const mvts = await exiger(resp.session, 'GET', `/ateliers/${a.id}/mouvements`, undefined, { params: { articleId: m.id } });
  vrai(mvts.some((x) => x.type === 'INVENTAIRE' && num(x.quantite) === -1.5 && num(x.stockApres) === 12), 'mouvement INVENTAIRE de -1,5 attendu');
  const eqs = await exiger(resp.session, 'GET', `/ateliers/${a.id}/equipements`);
  egal(eqs.find((x) => x.id === e2.id)?.etat, 'MANQUANT', 'état constaté repris');
  egal(eqs.find((x) => x.id === e1.id)?.etat, 'BON', 'équipement en service');
  const liste = await exiger(tech.session, 'GET', `/ateliers/${a.id}/inventaires`);
  const r = liste.find((x) => x.id === inv.id);
  vrai(r?.statut === 'CLOS' && r.ecarts >= 2, `inventaire clos avec ses écarts (${r?.ecarts})`);
  statut(await appel(resp.session, 'PUT', `/inventaires/${inv.id}/lignes`, { matieres, equipements }), 409, 'INVENTAIRE_CLOS');
  statut(await appel(resp.session, 'POST', `/inventaires/${inv.id}/cloture`), 409, 'INVENTAIRE_CLOS');
  const resume = await resumeAtelier(chef, a.id);
  vrai(resume.alertes.inventaireEnCours === false && resume.alertes.dernierInventaire === ctx.aujourdhui, 'dernier inventaire du jour attendu');
  vrai(resume.alertes.equipementsManquants >= 1, 'alerte « équipements manquants » attendue');
  partage(ctx).inventaireClos = true;
});

scenario('ATE-22', ATELIERS, 'Exports Excel et PDF des ateliers : tableau de bord, catalogue, stock par filière, équipements, stock et inventaire ; format inconnu et lecteur non autorisé refusés', async (ctx) => {
  const { chef, intendant, resp, general } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const inv = requis(ctx, 'inventaire');
  const exports = [
    [chef, '/ateliers/export', 'xlsx'], [chef, '/ateliers/export', 'pdf'],
    [intendant, '/catalogue/export', 'xlsx'], [intendant, '/catalogue/export', 'pdf'],
    [chef, '/ateliers/stock-par-filiere/export', 'xlsx'], [intendant, '/ateliers/stock-par-filiere/export', 'pdf'],
    [resp.session, `/ateliers/${a.id}/equipements/export`, 'pdf'], [resp.session, `/ateliers/${a.id}/stock/export`, 'xlsx'],
    [resp.session, `/inventaires/${inv.id}/export`, 'pdf'], [chef, `/inventaires/${inv.id}/export`, 'xlsx'],
  ];
  for (const [qui, chemin, format] of exports) {
    const r = await exporter(qui, chemin, format);
    if (!r.ok) statut(r, 200);
    verifierFichier(r, format, chemin);
  }
  statut(await exporter(chef, '/ateliers/export', 'doc'), 400);
  statut(await exporter(general.session, `/ateliers/${a.id}/stock/export`, 'xlsx'), 403);
  return `${exports.length} fichiers`;
});

// ===================================================================== circuit des besoins

scenario('BES-01', BESOINS, 'Le chef des travaux et l’intendant voient les campagnes de besoins de l’année ; un enseignant non', async (ctx) => {
  const { e, chef, intendant, resp } = acteurs(ctx);
  const liste = await exiger(chef, 'GET', '/campagnes-besoins');
  const annee = liste.filter((c) => c.anneeId === e.anneeId);
  const enCours = annee.find((c) => c.type === 'ANNEE_EN_COURS');
  const examens = annee.find((c) => c.type === 'EXAMENS');
  vrai(enCours || examens, 'aucune campagne de besoins pour l’année active (peuplement incomplet ?)');
  vrai((await exiger(intendant, 'GET', '/campagnes-besoins')).length === liste.length, 'l’intendant voit les mêmes campagnes');
  statut(await appel(resp.session, 'GET', '/campagnes-besoins'), 403);
  Object.assign(partage(ctx), { campagneAnnee: enCours ?? null, campagneExamens: examens ?? null });
  return annee.map((c) => `${c.type} ${c.statut}`).join(', ');
});

scenario('BES-02', BESOINS, 'Une seule campagne par type et par année : une seconde est refusée (409 CAMPAGNE_EXISTANTE) ; type manquant (400), intendant (403)', async (ctx) => {
  const { chef, intendant } = acteurs(ctx);
  const p = partage(ctx);
  const existante = p.campagneAnnee ?? p.campagneExamens ?? ignorer('aucune campagne existante à doubler');
  statut(await appel(intendant, 'POST', '/campagnes-besoins', { type: existante.type }), 403);
  statut(await appel(chef, 'POST', '/campagnes-besoins', { observations: 'Sans type' }), 400);
  statut(await appel(chef, 'POST', '/campagnes-besoins', { type: existante.type, dateLimite: ajouterJours(ctx.aujourdhui, 30) }), 409, 'CAMPAGNE_EXISTANTE');
});

scenario('BES-03', BESOINS, 'L’atelier de test reçoit sa fiche de besoins dans la campagne des examens ouverte ; une fiche vide ne se transmet pas (409 BESOINS_VIDES)', async (ctx) => {
  const { resp } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  requis(ctx, 'responsableDesigne');
  const fiches = await exiger(resp.session, 'GET', `/ateliers/${a.id}/besoins`);
  const fiche = fiches.find((f) => f.type === 'EXAMENS' && f.statutCampagne === 'OUVERTE');
  if (!fiche) ignorer('pas de campagne des examens ouverte au lycée technique');
  egal(fiche.statut, 'BROUILLON', 'fiche en préparation');
  egal(fiche.lignes, 0, 'fiche vide');
  statut(await appel(resp.session, 'POST', `/besoins-ateliers/${fiche.id}/transmission`), 409, 'BESOINS_VIDES');
  partage(ctx).fiche = fiche;
  return fiche.campagne;
});

scenario('BES-04', BESOINS, 'Un enseignant technique de l’atelier ajoute une ligne de besoin (article du catalogue, quantité, justification)', async (ctx) => {
  const { tech } = acteurs(ctx);
  const fiche = requis(ctx, 'fiche');
  const m = requis(ctx, 'matiere');
  const b = await exiger(tech.session, 'PUT', `/besoins-ateliers/${fiche.id}/lignes/${m.id}`, { quantite: 12, justification: 'Épreuve pratique : câblage d’un tableau' });
  const l = b.lignes.find((x) => x.articleId === m.id);
  egal(num(l?.quantiteDemandee), 12, 'quantité demandée');
  vrai(l.proposePar, 'auteur de la ligne absent');
  egal(l.photo, true, 'la photo du catalogue est signalée');
  egal(num(l.montant), 12 * num(m.prixReference), 'montant au prix du catalogue');
  vrai(b.droits.proposer === true && b.droits.transmettre === false, 'l’enseignant propose mais ne transmet pas');
  statut(await appel(tech.session, 'PUT', `/besoins-ateliers/${fiche.id}/lignes/${m.id}`, { quantite: 0 }), 400);
  statut(await appel(tech.session, 'PUT', `/besoins-ateliers/${fiche.id}/lignes/${cle()}`, { quantite: 1 }), 404);
});

scenario('BES-05', BESOINS, 'Un enseignant de matière générale ne voit pas la fiche et ne peut pas y ajouter de ligne (403)', async (ctx) => {
  const { general, intendant } = acteurs(ctx);
  const fiche = requis(ctx, 'fiche');
  const m = requis(ctx, 'matiere');
  statut(await appel(general.session, 'PUT', `/besoins-ateliers/${fiche.id}/lignes/${m.id}`, { quantite: 3, justification: 'Essai' }), 403);
  statut(await appel(general.session, 'GET', `/besoins-ateliers/${fiche.id}`), 403);
  statut(await appel(general.session, 'DELETE', `/besoins-ateliers/${fiche.id}/lignes/${m.id}`), 403);
  // L'intendant consulte la fiche
  const b = await exiger(intendant, 'GET', `/besoins-ateliers/${fiche.id}`);
  vrai(b.lignes.some((l) => l.articleId === m.id), 'ligne absente pour l’intendant');
});

scenario('BES-06', BESOINS, 'Le responsable ajoute un équipement : quantité entière exigée ; il retire puis remet une ligne', async (ctx) => {
  const { resp } = acteurs(ctx);
  const fiche = requis(ctx, 'fiche');
  const eq = requis(ctx, 'equipement');
  statut(await appel(resp.session, 'PUT', `/besoins-ateliers/${fiche.id}/lignes/${eq.id}`, { quantite: 1.5 }), 400);
  let b = await exiger(resp.session, 'PUT', `/besoins-ateliers/${fiche.id}/lignes/${eq.id}`, { quantite: 2, justification: 'Postes supplémentaires' });
  vrai(b.lignes.some((l) => l.articleId === eq.id), 'ligne d’équipement absente');
  b = await exiger(resp.session, 'DELETE', `/besoins-ateliers/${fiche.id}/lignes/${eq.id}`);
  vrai(!b.lignes.some((l) => l.articleId === eq.id), 'ligne retirée');
  statut(await appel(resp.session, 'DELETE', `/besoins-ateliers/${fiche.id}/lignes/${eq.id}`), 404);
  b = await exiger(resp.session, 'PUT', `/besoins-ateliers/${fiche.id}/lignes/${eq.id}`, { quantite: 2, justification: 'Postes supplémentaires' });
  egal(b.lignes.length, 2, 'deux lignes attendues');
  vrai(b.droits.transmettre === true, 'le responsable peut transmettre');
});

scenario('BES-07', BESOINS, 'Le responsable transmet la fiche au chef des travaux ; l’enseignant ordinaire ne transmet pas et la fiche transmise ne se modifie plus', async (ctx) => {
  const { resp, tech } = acteurs(ctx);
  const fiche = requis(ctx, 'fiche');
  const m = requis(ctx, 'matiere');
  statut(await appel(tech.session, 'POST', `/besoins-ateliers/${fiche.id}/transmission`), 403);
  const b = await exiger(resp.session, 'POST', `/besoins-ateliers/${fiche.id}/transmission`);
  egal(b.statut, 'TRANSMIS', 'fiche transmise');
  vrai(b.transmisLe && b.transmisPar, 'date et auteur de la transmission');
  statut(await appel(tech.session, 'PUT', `/besoins-ateliers/${fiche.id}/lignes/${m.id}`, { quantite: 20 }), 409, 'BESOINS_TRANSMIS');
  statut(await appel(resp.session, 'POST', `/besoins-ateliers/${fiche.id}/transmission`), 409, 'BESOINS_TRANSMIS');
});

scenario('BES-08', BESOINS, 'Le chef des travaux renvoie la fiche avec un commentaire obligatoire : elle repasse en préparation, puis le responsable la retransmet', async (ctx) => {
  const { resp, chef } = acteurs(ctx);
  const fiche = requis(ctx, 'fiche');
  statut(await appel(resp.session, 'POST', `/besoins-ateliers/${fiche.id}/renvoi`, { commentaire: 'Essai' }), 403);
  statut(await appel(chef, 'POST', `/besoins-ateliers/${fiche.id}/renvoi`, { commentaire: '  ' }), 400);
  const b = await exiger(chef, 'POST', `/besoins-ateliers/${fiche.id}/renvoi`, { commentaire: 'Précisez la section du câble dans la justification.' });
  egal(b.statut, 'BROUILLON', 'fiche renvoyée en préparation');
  vrai(b.commentaire?.includes('section'), 'commentaire du chef des travaux attendu');
  statut(await appel(chef, 'POST', `/besoins-ateliers/${fiche.id}/renvoi`, { commentaire: 'Encore' }), 409, 'BESOINS_NON_TRANSMIS');
  statut(await appel(chef, 'POST', `/besoins-ateliers/${fiche.id}/validation`), 409, 'BESOINS_NON_TRANSMIS');
  await exiger(resp.session, 'PUT', `/besoins-ateliers/${fiche.id}/lignes/${requis(ctx, 'matiere').id}`, { quantite: 12, justification: 'Câble souple 1,5 mm² pour l’épreuve pratique' });
  egal((await exiger(resp.session, 'POST', `/besoins-ateliers/${fiche.id}/transmission`)).statut, 'TRANSMIS', 'fiche retransmise');
});

scenario('BES-09', BESOINS, 'Le chef des travaux arbitre (quantité retenue) puis valide : le prix est figé ; le responsable n’arbitre pas et une fiche validée ne se revalide pas', async (ctx) => {
  const { resp, chef } = acteurs(ctx);
  const fiche = requis(ctx, 'fiche');
  const m = requis(ctx, 'matiere');
  const eq = requis(ctx, 'equipement');
  statut(await appel(resp.session, 'PUT', `/besoins-ateliers/${fiche.id}/arbitrage`, { lignes: [{ articleId: m.id, quantiteRetenue: 10 }] }), 403);
  statut(await appel(chef, 'PUT', `/besoins-ateliers/${fiche.id}/arbitrage`, { lignes: [{ articleId: cle(), quantiteRetenue: 1 }] }), 400);
  statut(await appel(chef, 'PUT', `/besoins-ateliers/${fiche.id}/arbitrage`, { lignes: [{ articleId: eq.id, quantiteRetenue: 0.5 }] }), 400);
  const arb = await exiger(chef, 'PUT', `/besoins-ateliers/${fiche.id}/arbitrage`, { lignes: [{ articleId: m.id, quantiteRetenue: 10 }] });
  egal(num(arb.lignes.find((l) => l.articleId === m.id)?.quantiteRetenue), 10, 'quantité retenue');
  vrai(arb.droits.arbitrer === true, 'le chef des travaux arbitre');
  const v = await exiger(chef, 'POST', `/besoins-ateliers/${fiche.id}/validation`);
  egal(v.statut, 'VALIDE', 'fiche validée');
  vrai(v.valideLe, 'date de validation');
  const lm = v.lignes.find((l) => l.articleId === m.id);
  const le = v.lignes.find((l) => l.articleId === eq.id);
  egal(num(lm.quantiteRetenue), 10, 'quantité retenue conservée');
  egal(num(le.quantiteRetenue), 2, 'sans arbitrage, la quantité demandée est retenue');
  egal(num(lm.prixUnitaire), num(m.prixReference), 'prix figé au catalogue');
  egal(num(v.montant), 10 * num(m.prixReference) + 2 * num(eq.prixReference), 'montant de la fiche');
  statut(await appel(chef, 'POST', `/besoins-ateliers/${fiche.id}/validation`), 409, 'BESOINS_NON_TRANSMIS');
  statut(await appel(resp.session, 'PUT', `/besoins-ateliers/${fiche.id}/lignes/${m.id}`, { quantite: 1 }), 409, 'BESOINS_TRANSMIS');
  partage(ctx).ficheValidee = v;
  return `${v.montant} F CFA`;
});

scenario('BES-10', BESOINS, 'La campagne des examens consolide les besoins validés par filière ; l’intendant la consulte sans la gérer, un enseignant non', async (ctx) => {
  const { chef, intendant, resp } = acteurs(ctx);
  const fiche = requis(ctx, 'ficheValidee');
  const a = requis(ctx, 'atelier');
  const m = requis(ctx, 'matiere');
  const c = await exiger(intendant, 'GET', `/campagnes-besoins/${fiche.campagneId}`);
  egal(c.type, 'EXAMENS', 'type de campagne');
  const b = c.besoins.find((x) => x.atelierId === a.id);
  egal(b?.statut, 'VALIDE', 'fiche validée dans la campagne');
  vrai(c.filieres.some((f) => f.lignes.some((l) => l.articleId === m.id)), 'consolidation par filière');
  const t = c.totaux.find((x) => x.articleId === m.id);
  egal(num(t?.quantite), 10, 'total retenu');
  vrai(c.gerer === false && c.commander === false, 'l’intendant ne gère pas une campagne ouverte');
  vrai((await exiger(chef, 'GET', `/campagnes-besoins/${fiche.campagneId}`)).gerer === true, 'le chef des travaux gère la campagne');
  statut(await appel(resp.session, 'GET', `/campagnes-besoins/${fiche.campagneId}`), 403);
  partage(ctx).campagneExamensFiche = c;
});

scenario('BES-11', BESOINS, 'La campagne des examens ne se transmet pas tant qu’un atelier attend l’arbitrage (409 BESOINS_NON_VALIDES), et ne reçoit pas de commande (409 CAMPAGNE_NON_TRANSMISE)', async (ctx) => {
  const { chef, intendant } = acteurs(ctx);
  const c = requis(ctx, 'campagneExamensFiche');
  const a = requis(ctx, 'atelier');
  const m = requis(ctx, 'matiere');
  if (c.statut !== 'OUVERTE') ignorer(`campagne des examens ${c.statut}`);
  statut(await appel(intendant, 'POST', `/campagnes-besoins/${c.id}/commandes`, {
    reference: `REC-EX-${suffixe()}`, fournisseur: 'Essai', passeePar: 'ETABLISSEMENT', lignes: [{ articleId: m.id, quantite: 1 }],
  }), 409, 'CAMPAGNE_NON_TRANSMISE');
  statut(await appel(intendant, 'POST', `/campagnes-besoins/${c.id}/transmission`), 403);
  // Ne pas transmettre réellement la campagne de démonstration : il faut un atelier en attente
  const enAttente = c.besoins.filter((b) => b.atelierId !== a.id && b.statut !== 'VALIDE' && b.lignes > 0);
  if (!enAttente.length) ignorer('aucun atelier en attente d’arbitrage : la transmission modifierait la démonstration');
  const r = statut(await appel(chef, 'POST', `/campagnes-besoins/${c.id}/transmission`), 409, 'BESOINS_NON_VALIDES');
  vrai(!r.detail?.includes(a.code), 'l’atelier validé ne doit pas être cité');
  vrai(enAttente.some((b) => r.detail?.includes(b.atelierCode)), `ateliers en attente cités attendus : ${r.detail}`);
  return r.detail;
});

scenario('BES-12', BESOINS, 'L’intendant enregistre une commande sur la campagne transmise ; la référence est unique (409 REFERENCE_EXISTANTE) ; date future et enseignant refusés', async (ctx) => {
  const { intendant, resp } = acteurs(ctx);
  const camp = partage(ctx).campagneAnnee;
  if (!camp || camp.statut !== 'TRANSMISE') ignorer('pas de campagne de l’année transmise à la direction régionale');
  requis(ctx, 'inventaireClos');
  const m = requis(ctx, 'matiere');
  const eq = requis(ctx, 'equipement');
  const reference = `rec-bc-${suffixe()}`;
  const corps = {
    reference, fournisseur: 'Quincaillerie de la recette', passeePar: 'ETABLISSEMENT', dateCommande: ctx.aujourdhui,
    observations: 'Commande de recette', lignes: [{ articleId: m.id, quantite: 10, prixUnitaire: null }, { articleId: eq.id, quantite: 2, prixUnitaire: 11500 }],
  };
  statut(await appel(resp.session, 'POST', `/campagnes-besoins/${camp.id}/commandes`, corps), 403);
  statut(await appel(intendant, 'POST', `/campagnes-besoins/${camp.id}/commandes`, { ...corps, dateCommande: ajouterJours(ctx.aujourdhui, 2) }), 400);
  statut(await appel(intendant, 'POST', `/campagnes-besoins/${camp.id}/commandes`, { ...corps, passeePar: null }), 400);
  statut(await appel(intendant, 'POST', `/campagnes-besoins/${camp.id}/commandes`, { ...corps, lignes: [] }), 400);
  const co = await exiger(intendant, 'POST', `/campagnes-besoins/${camp.id}/commandes`, corps);
  egal(co.reference, reference.toUpperCase(), 'référence en majuscules');
  egal(co.statut, 'EN_COURS', 'commande en cours');
  egal(co.lignes.length, 2, 'deux lignes');
  egal(num(co.lignes.find((l) => l.articleId === m.id)?.prixUnitaire), num(m.prixReference), 'prix vide : prix du catalogue');
  egal(num(co.montant), 10 * num(m.prixReference) + 2 * 11500, 'montant de la commande');
  statut(await appel(intendant, 'POST', `/campagnes-besoins/${camp.id}/commandes`, { ...corps, reference: reference.toUpperCase() }), 409, 'REFERENCE_EXISTANTE');
  const c = await exiger(intendant, 'GET', `/campagnes-besoins/${camp.id}`);
  vrai(c.commandes.some((x) => x.id === co.id), 'commande absente de la campagne');
  partage(ctx).commande = co;
  return co.reference;
});

scenario('BES-13', BESOINS, 'Une commande sans livraison s’annule avec un motif obligatoire ; une commande annulée ne s’annule pas deux fois', async (ctx) => {
  const { intendant } = acteurs(ctx);
  const camp = partage(ctx).campagneAnnee;
  requis(ctx, 'commande');
  const co = await exiger(intendant, 'POST', `/campagnes-besoins/${camp.id}/commandes`, {
    reference: `REC-ANN-${suffixe()}`, fournisseur: 'Fournisseur à annuler', passeePar: 'DIRECTION_REGIONALE',
    lignes: [{ articleId: requis(ctx, 'matiere').id, quantite: 1 }],
  });
  statut(await appel(intendant, 'POST', `/commandes/${co.id}/annulation`, { motif: ' ' }), 400);
  const r = await exiger(intendant, 'POST', `/commandes/${co.id}/annulation`, { motif: 'Doublon de la commande principale' });
  egal(r.statut, 'ANNULEE', 'commande annulée');
  egal(r.motifAnnulation, 'Doublon de la commande principale', 'motif conservé');
  statut(await appel(intendant, 'POST', `/commandes/${co.id}/annulation`, { motif: 'Encore' }), 409, 'COMMANDE_ANNULEE');
  statut(await appel(requis(ctx, 'acteurs').chef, 'POST', `/commandes/${co.id}/livraisons`, {
    dateReception: ctx.aujourdhui, lignes: [{ articleId: requis(ctx, 'matiere').id, quantiteRecue: 1 }],
  }), 409, 'COMMANDE_CLOSE');
});

scenario('BES-14', BESOINS, 'Le chef des travaux réceptionne la livraison : une partie non conforme exige un motif, un excédent est refusé ; la commande devient partiellement livrée', async (ctx) => {
  const { chef, intendant } = acteurs(ctx);
  const co = requis(ctx, 'commande');
  const m = requis(ctx, 'matiere');
  const eq = requis(ctx, 'equipement');
  const base = { dateReception: ctx.aujourdhui, bonLivraison: `BL-${suffixe()}`, observations: 'Livraison de recette' };
  statut(await appel(intendant, 'POST', `/commandes/${co.id}/livraisons`, { ...base, lignes: [{ articleId: m.id, quantiteRecue: 10 }] }), 403);
  statut(await appel(chef, 'POST', `/commandes/${co.id}/livraisons`, { ...base, lignes: [{ articleId: m.id, quantiteRecue: 10, quantiteConforme: 8 }] }), 400);
  statut(await appel(chef, 'POST', `/commandes/${co.id}/livraisons`, { ...base, lignes: [{ articleId: m.id, quantiteRecue: 10, quantiteConforme: 11 }] }), 400);
  statut(await appel(chef, 'POST', `/commandes/${co.id}/livraisons`, { ...base, lignes: [{ articleId: m.id, quantiteRecue: 11 }] }), 409, 'LIVRAISON_EXCEDENTAIRE');
  statut(await appel(chef, 'POST', `/commandes/${co.id}/livraisons`, { ...base, dateReception: ajouterJours(ctx.aujourdhui, 1), lignes: [{ articleId: m.id, quantiteRecue: 1 }] }), 400);
  const l = await exiger(chef, 'POST', `/commandes/${co.id}/livraisons`, {
    ...base,
    lignes: [
      { articleId: m.id, quantiteRecue: 10, quantiteConforme: 8, motifNonConformite: 'Deux rouleaux d’une section inférieure à la norme' },
      { articleId: eq.id, quantiteRecue: 2, quantiteConforme: null, motifNonConformite: null },
    ],
  });
  egal(l.statut, 'A_REPARTIR', 'livraison à répartir');
  const lm = l.lignes.find((x) => x.articleId === m.id);
  egal(num(lm?.conforme), 8, 'quantité conforme');
  vrai(lm.motifNonConformite, 'motif de non-conformité conservé');
  egal(num(l.lignes.find((x) => x.articleId === eq.id)?.conforme), 2, 'conforme vide : tout est conforme');
  const apres = await exiger(intendant, 'GET', `/commandes/${co.id}`);
  egal(apres.statut, 'LIVREE_PARTIELLEMENT', 'statut de la commande');
  egal(num(apres.lignes.find((x) => x.articleId === m.id)?.reste), 2, 'reste à livrer');
  vrai(apres.livraisons.some((x) => x.id === l.id && x.nonConformes === 1), 'livraison et non-conformité dans la commande');
  statut(await appel(intendant, 'POST', `/commandes/${co.id}/annulation`, { motif: 'Trop tard' }), 409, 'COMMANDE_LIVREE');
  partage(ctx).livraison = l;
  return `${l.bonLivraison} : 8/10 conformes`;
});

scenario('BES-15', BESOINS, 'Le chef des travaux répartit la livraison : excédent et répartition incomplète refusés ; à la validation, matière et équipements entrent dans l’atelier', async (ctx) => {
  const { chef, intendant, resp } = acteurs(ctx);
  const l = requis(ctx, 'livraison');
  const a = requis(ctx, 'atelier');
  const m = requis(ctx, 'matiere');
  const eq = requis(ctx, 'equipement');
  const stockAvant = num((await ligneStock(resp.session, a.id, m.id))?.quantite) ?? 0;
  const equipementsAvant = (await exiger(resp.session, 'GET', `/ateliers/${a.id}/equipements`)).length;
  statut(await appel(intendant, 'PUT', `/livraisons/${l.id}/repartition`, { lignes: [{ articleId: m.id, atelierId: a.id, quantite: 8 }] }), 403);
  statut(await appel(chef, 'PUT', `/livraisons/${l.id}/repartition`, { lignes: [{ articleId: m.id, atelierId: a.id, quantite: 9 }] }), 409, 'REPARTITION_EXCEDENTAIRE');
  statut(await appel(chef, 'PUT', `/livraisons/${l.id}/repartition`, { lignes: [{ articleId: m.id, atelierId: cle(), quantite: 1 }] }), 400);
  await exiger(chef, 'PUT', `/livraisons/${l.id}/repartition`, { lignes: [{ articleId: m.id, atelierId: a.id, quantite: 5 }, { articleId: eq.id, atelierId: a.id, quantite: 2 }] });
  statut(await appel(chef, 'POST', `/livraisons/${l.id}/repartition/validation`), 409, 'REPARTITION_INCOMPLETE');
  const enreg = await exiger(chef, 'PUT', `/livraisons/${l.id}/repartition`, { lignes: [{ articleId: m.id, atelierId: a.id, quantite: 8 }, { articleId: eq.id, atelierId: a.id, quantite: 2 }] });
  egal(num(enreg.lignes.find((x) => x.articleId === m.id)?.repartition.find((p) => p.atelierId === a.id)?.quantite), 8, 'répartition enregistrée');
  const v = await exiger(chef, 'POST', `/livraisons/${l.id}/repartition/validation`);
  egal(v.statut, 'REPARTIE', 'livraison répartie');
  vrai(v.repartieLe, 'date de répartition');
  const ligne = await ligneStock(resp.session, a.id, m.id);
  egal(num(ligne?.quantite), stockAvant + 8, 'la matière conforme entre dans le stock de l’atelier');
  egal(ligne.sousLeSeuil, stockAvant + 8 <= 15, 'alerte de seuil recalculée');
  const eqs = await exiger(resp.session, 'GET', `/ateliers/${a.id}/equipements`);
  egal(eqs.length, equipementsAvant + 2, 'chaque équipement reçu a sa fiche');
  vrai(eqs.filter((x) => x.articleId === eq.id && x.observations?.includes(requis(ctx, 'commande').reference)).length === 2, 'équipements reçus rattachés à la commande');
  statut(await appel(chef, 'POST', `/livraisons/${l.id}/repartition/validation`), 409, 'LIVRAISON_REPARTIE');
  statut(await appel(chef, 'PUT', `/livraisons/${l.id}/repartition`, { lignes: [] }), 409, 'LIVRAISON_REPARTIE');
  return `stock ${stockAvant} → ${ligne.quantite}`;
});

scenario('BES-16', BESOINS, 'Exports Excel et PDF du circuit des besoins : fiche d’atelier, état pour la direction régionale, bon de commande, procès-verbaux de réception', async (ctx) => {
  const { chef, intendant, resp, general } = acteurs(ctx);
  const liste = [];
  const fiche = partage(ctx).ficheValidee;
  if (fiche) {
    liste.push([resp.session, `/besoins-ateliers/${fiche.id}/export`, 'xlsx'], [chef, `/besoins-ateliers/${fiche.id}/export`, 'pdf'],
      [chef, `/campagnes-besoins/${fiche.campagneId}/export`, 'pdf']);
  }
  const camp = partage(ctx).campagneAnnee;
  if (camp) liste.push([intendant, `/campagnes-besoins/${camp.id}/export`, 'xlsx']);
  const co = partage(ctx).commande;
  if (co) liste.push([intendant, `/commandes/${co.id}/export`, 'pdf'], [chef, `/commandes/${co.id}/export`, 'xlsx']);
  const l = partage(ctx).livraison;
  if (l) liste.push([chef, `/livraisons/${l.id}/export`, 'pdf'], [intendant, `/livraisons/${l.id}/export`, 'xlsx']);
  if (!liste.length) ignorer('aucune fiche, commande ni livraison créée');
  for (const [qui, chemin, format] of liste) {
    const r = await exporter(qui, chemin, format);
    if (!r.ok) statut(r, 200);
    verifierFichier(r, format, chemin);
  }
  if (fiche) statut(await exporter(general.session, `/besoins-ateliers/${fiche.id}/export`, 'pdf'), 403);
  if (co) statut(await exporter(resp.session, `/commandes/${co.id}/export`, 'pdf'), 403);
  return `${liste.length} fichiers`;
});

scenario('BES-17', BESOINS, 'Isolation : le centre de formation ne voit ni la fiche, ni la commande, ni la livraison du lycée technique', async (ctx) => {
  const cfp = ctx.ecole('cfp-atelier-faso');
  const chefCfp = cfp?.session('CHEF_TRAVAUX') ?? cfp?.session('ADMIN_ECOLE') ?? ignorer('centre de formation absent');
  const p = partage(ctx);
  const testes = [];
  if (p.fiche) {
    refuse(await appel(chefCfp, 'GET', `/besoins-ateliers/${p.fiche.id}`), 'Fiche d’un autre établissement');
    testes.push('fiche');
  }
  if (p.commande) {
    refuse(await appel(chefCfp, 'GET', `/commandes/${p.commande.id}`), 'Commande d’un autre établissement');
    refuse(await appel(chefCfp, 'POST', `/commandes/${p.commande.id}/annulation`, { motif: 'Intrusion' }), 'Annulation par un autre établissement');
    testes.push('commande');
  }
  if (p.livraison) {
    refuse(await appel(chefCfp, 'GET', `/livraisons/${p.livraison.id}`), 'Livraison d’un autre établissement');
    testes.push('livraison');
  }
  if (p.campagneAnnee) {
    refuse(await appel(chefCfp, 'GET', `/campagnes-besoins/${p.campagneAnnee.id}`), 'Campagne d’un autre établissement');
    testes.push('campagne');
  }
  if (!testes.length) ignorer('rien à tester');
  return testes.join(', ');
});

// ===================================================================== fin : l'atelier de test est rendu

scenario('ATE-23', ATELIERS, 'Le chef des travaux met fin au mandat du responsable puis ferme l’atelier de test : l’ancien responsable ne tient plus l’atelier', async (ctx) => {
  const { chef, resp } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const p = partage(ctx);
  if (p.responsableDesigne) {
    statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/responsable/fin`, { date: ctx.aujourdhui, motif: 'Essai' }), 403);
    const r = await exiger(chef, 'POST', `/ateliers/${a.id}/responsable/fin`, { date: ctx.aujourdhui, motif: 'Fin de la recette' });
    egal(r.responsable, null, 'plus de responsable');
    vrai(r.historique.some((h) => h.engagementId === resp.engagementId && h.fin === ctx.aujourdhui && h.motifFin === 'Fin de la recette'), 'mandat terminé dans l’historique');
    egal(r.alertes.sansResponsable, true, 'alerte « sans responsable »');
    statut(await appel(chef, 'POST', `/ateliers/${a.id}/responsable/fin`, {}), 409, 'SANS_RESPONSABLE');
    statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/mouvements`,
      { articleId: requis(ctx, 'matiere').id, type: 'ENTREE', quantite: 1, date: ctx.aujourdhui }), 403);
  }
  const ferme = await exiger(chef, 'PUT', `/ateliers/${a.id}`, {
    code: a.code, nom: a.nom, emplacement: a.emplacement, postes: a.postes, ouvert: false,
    observations: 'Atelier créé par la recette automatisée', filieres: a.filieres.map((f) => f.id),
  });
  egal(ferme.ouvert, false, 'atelier fermé');
});
