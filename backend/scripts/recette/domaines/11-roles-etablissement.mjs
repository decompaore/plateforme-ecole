/**
 * Lot 11 : organisation des rôles vue avec les établissements (v0.47).
 *
 * Préfixes : CAH- (visa mensuel des cahiers de textes, suite du lot 04), INT- (intérim du chef d'établissement).
 *
 * Sources vérifiées : progression/VisasCahierService, ProgressionController ; utilisateurs/InterimsService,
 * InterimsController ; fonction SQL auth_etablissements_utilisateur (V34).
 *
 * Données : lycée Horizon (n° 3). Le visa d'un cahier fige le mois pour l'enseignant : la recette n'en pose pas
 * sur les données de démonstration (le visa réel est couvert par ProgressionIntegrationTest) et ne contrôle que les
 * droits et les refus. L'intérim de recette est prévu dans un mois puis annulé aussitôt : il ne donne aucun droit.
 */
import { ajouterJours, appel, egal, exiger, ignorer, scenario, statut, vrai } from '../lib.mjs';

const D_CAH = 'Cahiers de textes';
const D_INT = 'Intérim de direction';

function horizon(ctx) {
  const e = ctx.ecole('lp-horizon') ?? ctx.ecole(3);
  if (!e) ignorer('Lycée Horizon absent de la base peuplée');
  return e;
}

function role(e, r) {
  const s = e.session(r);
  if (!s) ignorer(`Pas de compte ${r} au ${e.nom}`);
  return s;
}

function moisDe(isoDate, decalage = 0) {
  const [a, m] = isoDate.split('-').map(Number);
  const d = new Date(Date.UTC(a, m - 1 + decalage, 1));
  return d.toISOString().slice(0, 7);
}

// ===================================================================== visa mensuel

scenario('CAH-04', D_CAH, 'Visa mensuel des cahiers de textes : tableau du mois pour le censeur ; l’enseignant ne vise pas, un mois à venir ne se vise pas', async (ctx) => {
  const e = horizon(ctx);
  const cens = role(e, 'CENSEUR');
  const mois = moisDe(ctx.aujourdhui);
  const tableau = await exiger(cens, 'GET', `/annees/${e.anneeId}/cahiers-textes`, undefined, { params: { mois } });
  vrai(Array.isArray(tableau) && tableau.every((l) => l.mois === mois), 'Une ligne par matière du domaine, pour le mois');
  const classe = e.classes.find((c) => c.matieres.some((m) => m.enseignant && m.type === 'GENERALE')) ?? e.classes[0];
  const matiere = classe.matieres.find((m) => m.enseignant && m.type === 'GENERALE') ?? classe.matieres[0];
  const chemin = `/classes/${classe.id}/matieres/${matiere.id}/cahier-textes/visas`;
  statut(await appel(cens, 'POST', chemin, { mois: moisDe(ctx.aujourdhui, 1) }), 409, 'MOIS_A_VENIR');
  const prof = e.enseignants.find((p) => p.telephone === matiere.enseignant && !p.partage);
  if (prof) {
    const s = ctx.compte(prof.telephone, e.id, `enseignant ${prof.matiere}`);
    statut(await appel(s, 'POST', chemin, { mois }), 403);
    await exiger(s, 'GET', chemin);
    statut(await appel(s, 'GET', `/annees/${e.anneeId}/cahiers-textes`), 403);
  }
  statut(await appel(role(e, 'SECRETARIAT'), 'GET', `/annees/${e.anneeId}/cahiers-textes`), 403);
  const vises = tableau.filter((l) => l.vise).length;
  return `${tableau.length} cahier(s) suivis en ${mois}, ${vises} visé(s) ; visa réel : test d’intégration (il fige le mois)`;
});

// ===================================================================== intérim

scenario('INT-01', D_INT, 'Intérim du chef d’établissement : le censeur est désigné pour une période, l’intendant n’est pas éligible, le censeur ne se désigne pas ; l’intérim prévu s’annule', async (ctx) => {
  const e = horizon(ctx);
  const chef = role(e, 'ADMIN_ECOLE');
  const cens = role(e, 'CENSEUR');
  const membres = await exiger(chef, 'GET', '/membres');
  const censeur = membres.find((m) => m.role === 'CENSEUR' && m.actif);
  const intendant = membres.find((m) => m.role === 'INTENDANT' && m.actif);
  if (!censeur) ignorer('Pas de censeur actif');
  const du = ajouterJours(ctx.aujourdhui, 30);
  const au = ajouterJours(ctx.aujourdhui, 35);
  // Reprise d'un passage précédent interrompu : un intérim de recette encore prévu est annulé
  for (const i of await exiger(chef, 'GET', '/interims')) {
    if (i.motif.startsWith('[Recette]') && !i.termine) await exiger(chef, 'POST', `/interims/${i.id}/fin`);
  }
  if (intendant) {
    statut(await appel(chef, 'POST', '/interims', { utilisateurId: intendant.utilisateurId, du, au, motif: '[Recette] Mission' }), 409, 'INTERIMAIRE_NON_ELIGIBLE');
  }
  statut(await appel(cens, 'POST', '/interims', { utilisateurId: censeur.utilisateurId, du, au, motif: '[Recette] Mission' }), 403);
  statut(await appel(chef, 'POST', '/interims', { utilisateurId: censeur.utilisateurId, du: ajouterJours(ctx.aujourdhui, -1), au, motif: '[Recette] Mission' }), 400);
  const i = await exiger(chef, 'POST', '/interims', { utilisateurId: censeur.utilisateurId, du, au, motif: '[Recette] Mission à Ouagadougou' });
  egal([i.enCours, i.termine], [false, false], 'Intérim prévu');
  statut(await appel(chef, 'POST', '/interims', { utilisateurId: censeur.utilisateurId, du: au, au: ajouterJours(au, 2), motif: '[Recette] Chevauchement' }), 409, 'INTERIM_EXISTANT');
  vrai((await exiger(cens, 'GET', '/interims')).some((x) => x.id === i.id), 'Le censeur voit l’intérim prévu');
  const fin = await exiger(chef, 'POST', `/interims/${i.id}/fin`);
  egal([fin.enCours, fin.termine], [false, true], 'Intérim annulé avant de commencer');
  statut(await appel(chef, 'POST', `/interims/${i.id}/fin`), 409, 'INTERIM_TERMINE');
  return `${censeur.prenoms} ${censeur.nom} : du ${du} au ${au}, puis annulé`;
});
