/**
 * Lot 15 (v0.48.1) : interpellation des enseignants en retard sur leur progression, et armoiries du pays en filigrane.
 *
 * Préfixes : IPL- (interpellations), ARM- (armoiries).
 *
 * Sources vérifiées : progression/InterpellationsService, InterpellationsController, Retards, ProgressionService
 * (retard dans le suivi) ; territoire/ArmoiriesService, ArmoiriesController ; docs/API_PROGRESSION.md,
 * docs/API_TERRITOIRE.md.
 *
 * Données : lycée technique Les Bâtisseurs (une matière générale avec enseignant). Il reste dans la base, à chaque
 * passage : une demande d'explication close et un rappel ouvert (numérotation DE-AAAA-NNNN qui avance).
 * Armoiries : si le Burkina Faso en a déjà, la recette les relit sans y toucher ; sinon elle dépose une image de test
 * et la retire à la fin.
 */
import { ajouterJours, appel, egal, exiger, fichierPng, ignorer, refuse, scenario, statut, vrai } from '../lib.mjs';

const D = 'Interpellations et armoiries';

function partage(ctx) {
  ctx.partage.interpellations ??= {};
  return ctx.partage.interpellations;
}

/** Une matière générale avec enseignant au lycée technique, vue du suivi du censeur. */
async function cible(ctx) {
  const p = partage(ctx);
  if (p.cible) return p.cible;
  const e = ctx.ecole('lt-batisseurs') ?? ignorer('lycée technique absent de la base de démonstration');
  const censeur = e.session('CENSEUR') ?? ignorer('pas de censeur');
  const annees = await exiger(censeur, 'GET', '/annees');
  const annee = annees.find((a) => a.etat === 'ACTIVE') ?? annees[0] ?? ignorer('aucune année');
  const suivi = await exiger(censeur, 'GET', `/annees/${annee.id}/progressions`);
  const ligne = suivi.find((l) => l.domaine === 'GENERAL' && l.engagementId) ?? ignorer('aucune matière générale avec enseignant');
  const classe = e.classes.find((c) => c.id === ligne.classeId);
  const tel = classe?.matieres.find((m) => m.id === ligne.matiereId)?.enseignant ?? ignorer('téléphone de l’enseignant introuvable');
  p.cible = {
    e, annee, ligne, censeur,
    chef: e.session('CHEF_TRAVAUX'),
    proviseur: e.session('ADMIN_ECOLE'),
    enseignant: ctx.compte(tel, e.id, `enseignant ${ligne.matiereCode} ${e.code}`),
  };
  return p.cible;
}

// ===================================================================== interpellations

scenario('IPL-01', D, 'Le suivi des progressions donne le retard de chaque matière ; seule la direction du domaine prépare une interpellation', async (ctx) => {
  const { ligne, censeur, chef, enseignant } = await cible(ctx);
  vrai(ligne.retard && typeof ligne.retard.enRetard === 'boolean', 'Retard calculé dans le suivi');
  vrai(typeof ligne.interpellations === 'number', 'Nombre d’interpellations en cours dans le suivi');
  const base = `/classes/${ligne.classeId}/matieres/${ligne.matiereId}/interpellations`;
  if (chef) refuse(await appel(chef, 'GET', `${base}/proposition`), 'Le chef des travaux ne suit pas une matière générale');
  refuse(await appel(enseignant, 'GET', `${base}/proposition`), 'Un enseignant n’interpelle pas');
  const p = await exiger(censeur, 'GET', `${base}/proposition`);
  vrai(p.constat.includes(ligne.classeCode), `Constat : ${p.constat.slice(0, 80)}`);
  vrai(p.delaiReponse > ctx.aujourdhui, 'Délai proposé dans le futur');
  partage(ctx).proposition = p;
});

scenario('IPL-02', D, 'Demande d’explication numérotée : l’enseignant la reçoit, l’ouvre (lue) et répond une seule fois ; le censeur reçoit la réponse ; la lettre s’imprime', async (ctx) => {
  const { ligne, censeur, enseignant } = await cible(ctx);
  const p = partage(ctx).proposition ?? ignorer('IPL-01 en échec');
  const base = `/classes/${ligne.classeId}/matieres/${ligne.matiereId}/interpellations`;
  statut(await appel(censeur, 'POST', base, { type: 'DEMANDE_EXPLICATION', objet: p.objet, constat: p.constat }), 400);
  const d = await exiger(censeur, 'POST', base, {
    type: 'DEMANDE_EXPLICATION', objet: `[Recette] ${p.objet}`, constat: p.constat, demande: p.demandeExplication,
    delaiReponse: ajouterJours(ctx.aujourdhui, 7),
  });
  vrai(/^DE-\d{4}-\d{4}$/.test(d.numero ?? ''), `Numéro : ${d.numero}`);
  egal(d.statut, 'ENVOYEE', 'Statut à l’envoi');
  const boite = await exiger(enseignant, 'GET', '/messages');
  vrai(JSON.stringify(boite).includes(d.numero), 'Demande reçue dans la messagerie de l’enseignant');
  const miennes = await exiger(enseignant, 'GET', '/espace-enseignant/interpellations');
  vrai(miennes.some((x) => x.id === d.id), 'Dans « Mes interpellations »');
  egal((await exiger(enseignant, 'GET', `/interpellations/${d.id}`)).statut, 'LUE', 'Ouverte par l’enseignant');
  egal((await exiger(enseignant, 'POST', `/interpellations/${d.id}/reponse`,
    { reponse: '[Recette] Absence pour formation ; rattrapage prévu le samedi.' })).statut, 'REPONDUE', 'Réponse');
  statut(await appel(enseignant, 'POST', `/interpellations/${d.id}/reponse`, { reponse: 'Encore' }), 409, 'DEJA_REPONDUE');
  const recus = await exiger(censeur, 'GET', '/messages');
  vrai(JSON.stringify(recus).includes('Réponse de'), 'Réponse reçue par le censeur');
  const pdf = await exiger(censeur, 'GET', `/interpellations/${d.id}/lettre`, undefined, { fichier: true });
  egal(pdf.octets.subarray(0, 5).toString(), '%PDF-', 'Lettre en PDF');
  partage(ctx).demande = d;
});

scenario('IPL-03', D, 'Le censeur clôt avec la suite donnée (obligatoire pour une demande d’explication) ; le chef d’établissement suit tout ; un rappel n’a ni numéro ni délai', async (ctx) => {
  const { ligne, annee, censeur, proviseur, enseignant } = await cible(ctx);
  const d = partage(ctx).demande ?? ignorer('IPL-02 en échec');
  refuse(await appel(enseignant, 'POST', `/interpellations/${d.id}/cloture`, { suite: 'x' }));
  statut(await appel(censeur, 'POST', `/interpellations/${d.id}/cloture`, {}), 400);
  egal((await exiger(censeur, 'POST', `/interpellations/${d.id}/cloture`,
    { suite: '[Recette] Explication recevable ; suivi hebdomadaire du rattrapage.' })).statut, 'CLOSE', 'Close');
  if (proviseur) {
    const toutes = await exiger(proviseur, 'GET', '/interpellations', undefined, { params: { anneeId: annee.id } });
    vrai(toutes.some((x) => x.id === d.id), 'Le chef d’établissement voit l’interpellation');
  }
  const r = await exiger(censeur, 'POST', `/classes/${ligne.classeId}/matieres/${ligne.matiereId}/interpellations`, {
    type: 'RAPPEL', objet: '[Recette] Tenue du cahier de textes', constat: 'Le cahier de textes doit être rempli après chaque cours.',
  });
  vrai(r.numero === null && r.delaiReponse === null, 'Rappel sans numéro ni délai');
});

// ===================================================================== armoiries

scenario('ARM-01', D, 'Armoiries du pays : déposées par l’administrateur du pays, servies en filigrane aux comptes du pays, jamais au super administrateur', async (ctx) => {
  const t = ctx.partage.territoire;
  if (!t?.adminPaysBF || !t?.paysBF) ignorer('prérequis absent : lot 02 (administrateur pays BF)');
  const e = ctx.ecole('lt-batisseurs') ?? ignorer('lycée technique absent');
  const proviseur = e.session('ADMIN_ECOLE');
  const chemin = `/plateforme/territoire/pays/${t.paysBF}/armoiries`;
  const etat = await exiger(t.adminPaysBF, 'GET', `${chemin}/etat`);
  let deposees = false;
  try {
    if (!etat.presentes) {
      const f = new FormData();
      f.append('fichier', new Blob([Buffer.from('pas une image')], { type: 'image/png' }), 'a.png');
      statut(await appel(t.adminPaysBF, 'POST', chemin, f), 400);
      const g = new FormData();
      g.append('fichier', fichierPng(), 'armoiries.png');
      egal((await exiger(t.adminPaysBF, 'POST', chemin, g)).presentes, true, 'Armoiries déposées');
      deposees = true;
    }
    const img = await exiger(proviseur, 'GET', '/armoiries', undefined, { fichier: true });
    vrai(img.type.startsWith('image/'), `Type : ${img.type}`);
    if (t.comptesDirection?.['DP-KADIOGO']) {
      statut(await appel(t.comptesDirection['DP-KADIOGO'], 'GET', '/armoiries', undefined, { fichier: true }), 200);
    }
    statut(await appel(ctx.sa, 'GET', '/armoiries'), 404);
    if (t.adminPaysNE) {
      refuse(await appel(t.adminPaysNE, 'DELETE', chemin), 'Un administrateur d’un autre pays ne touche pas aux armoiries');
    }
  } finally {
    if (deposees) {
      await exiger(t.adminPaysBF, 'DELETE', chemin);
    }
  }
});
