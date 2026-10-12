/**
 * Lot 04 : pédagogie — emploi du temps, progressions, cahier de textes, évaluations et notes, compétences,
 * résultats et bulletins.
 *
 * Préfixes : PED- (mise en place et nettoyage), EDT- (emploi du temps), PRO- (fiches de progression),
 * CAH- (cahier de textes), EVA- (évaluations, notes, compétences, résultats), BUL- (bulletins).
 *
 * Sources vérifiées : emploidutemps/EmploiDuTempsController/Service, Responsabilites, ExportsEmploi, Vues ;
 * progression/ProgressionController/Service, CahierTextesService, FicheProgression, AccesMatiere, Supervision, Vues ;
 * evaluations/EvaluationsController/Service, CompetencesService, ContexteClasse, AutorisationsEvaluations, Vues ;
 * bulletins/BulletinsController/Service, ConseilService, ParametresBulletinsService, GenerationBulletins, Vues ;
 * etablissement/ClassesController/Service, PeriodesController ; socle/export/ExportTableaux.
 *
 * Pour ne pas toucher aux notes, fiches et séances des enseignants de démonstration, tout ce qui s'écrit le fait
 * sur deux matières de test (REC-G-…, générale, et REC-T-…, pratique) ajoutées pour le temps du lot à une classe
 * du lycée technique et confiées à deux enseignants de démonstration. À la fin (PED-02), évaluations, séances
 * du cahier et de l'emploi du temps sont supprimées, les matières retirées de la classe puis désactivées. Les
 * bulletins se vérifient sur les relevés de compétences du Module 1 publiés par le peuplement au centre de
 * formation ; aucune période n'est verrouillée ni déverrouillée.
 */
import { randomUUID } from 'node:crypto';

import {
  ajouterJours, appel, appelBrut, Echec, egal, exiger, ignorer, refuse, scenario, statut, suffixe, vrai,
} from '../lib.mjs';

const D_PED = 'Pédagogie : mise en place';
const D_EDT = 'Emploi du temps';
const D_PRO = 'Progressions';
const D_CAH = 'Cahier de textes';
const D_EVA = 'Évaluations et résultats';
const D_BUL = 'Bulletins';

// ===================================================================== aides

function etat(ctx) {
  ctx.partage.pedagogie ??= {};
  return ctx.partage.pedagogie;
}

function technique(ctx) {
  const e = ctx.ecole('lt-batisseurs') ?? ctx.ecole(1);
  if (!e) ignorer('Lycée technique absent de la base peuplée');
  return e;
}

function centre(ctx) {
  const e = ctx.ecole('cfp-atelier-faso') ?? ctx.ecole(2);
  if (!e) ignorer('Centre de formation absent de la base peuplée');
  return e;
}

function role(e, r) {
  const s = e.session(r);
  if (!s) ignorer(`Pas de compte ${r} au ${e.nom}`);
  return s;
}

/** Mise en place faite par PED-01 (sinon le scénario est ignoré). */
function montage(ctx) {
  const m = etat(ctx).montage;
  if (!m) ignorer(`Matières de test non installées (PED-01)${etat(ctx).echecMontage ? ` : ${etat(ctx).echecMontage}` : ''}`);
  return m;
}

/** Période qui contient aujourd'hui (pour le profil des classes de l'établissement). */
async function periodeCourante(ctx, e, qui) {
  const periodes = await exiger(qui, 'GET', `/annees/${e.anneeId}/periodes`);
  return periodes.find((p) => p.debut <= ctx.aujourdhui && ctx.aujourdhui <= p.fin) ?? null;
}

function matiereDeClasse() {
  return { coefficient: 1, groupe: 'Recette', volumeHebdo: 2, volumeTotal: 40 };
}

/** Un enseignant de démonstration (non partagé) d'une matière d'un type donné, et sa classe. */
function enseignantDe(e, filtreType) {
  for (const c of e.classes) {
    for (const m of c.matieres.filter((x) => x.enseignant && filtreType(x.type))) {
      const p = e.enseignants.find((x) => x.telephone === m.enseignant && !x.partage);
      if (p) return { prof: p, classe: c };
    }
  }
  return null;
}

function seance(m, date, debut, fin, extra = {}) {
  return { classeId: m.classe.id, matiereId: m.general.id, date, heureDebut: debut, heureFin: fin, contenu: 'Recette : cours de méthodologie', ...extra };
}

// ===================================================================== mise en place

scenario('PED-01', D_PED, 'Le censeur installe une matière générale et une matière pratique de test dans une classe et les confie à deux enseignants', async (ctx) => {
  const s = etat(ctx);
  try {
    const e = technique(ctx);
    const cens = role(e, 'CENSEUR');
    const general = enseignantDe(e, (t) => t === 'GENERALE');
    const tech = enseignantDe(e, (t) => t === 'PRATIQUE' || t === 'TECHNIQUE');
    if (!general || !tech) ignorer('Enseignants général et technique introuvables dans l’état du peuplement');
    const classe = general.classe;
    const suf = suffixe().toUpperCase();
    const g = await exiger(cens, 'POST', '/matieres', { code: `REC-G-${suf}`, libelle: 'Recette : méthodologie', type: 'GENERALE' });
    const t = await exiger(cens, 'POST', '/matieres', { code: `REC-T-${suf}`, libelle: 'Recette : travaux pratiques', type: 'PRATIQUE' });
    for (const m of [g, t]) await exiger(cens, 'PUT', `/classes/${classe.id}/matieres/${m.id}`, matiereDeClasse());
    s.montage = {
      e, classe, general: g, pratique: t,
      profG: { ...general.prof, session: ctx.compte(general.prof.telephone, e.id, `enseignant ${general.prof.matiere}`) },
      profT: { ...tech.prof, session: ctx.compte(tech.prof.telephone, e.id, `enseignant ${tech.prof.matiere}`) },
      evaluations: [], seancesCahier: [], seancesEmploi: [],
    };
    await exiger(cens, 'PUT', `/classes/${classe.id}/matieres/${g.id}/enseignant`, { engagementId: general.prof.engagementId });
    await exiger(cens, 'PUT', `/classes/${classe.id}/matieres/${t.id}/enseignant`, { engagementId: tech.prof.engagementId });
    return `${classe.code} : ${g.code} (${general.prof.nom}), ${t.code} (${tech.prof.nom})`;
  } catch (err) {
    if (err instanceof Echec) s.echecMontage = err.message;
    throw err;
  }
});

// ===================================================================== emploi du temps

scenario('EDT-01', D_EDT, 'L’emploi du temps publié se consulte à la direction et à la surveillance, pas par un enseignant ni un parent', async (ctx) => {
  const e = technique(ctx);
  const edt = await exiger(role(e, 'CENSEUR'), 'GET', `/annees/${e.anneeId}/emploi-du-temps`);
  vrai(edt.publieLe, 'L’emploi du temps devrait être publié par le peuplement');
  vrai(edt.creneaux.length > 0 && edt.seances.length > 0, 'Grille et séances attendues');
  egal(edt.droits.grille, true, 'Le censeur fixe la grille');
  vrai(edt.droits.domaines.includes('GENERAL'), 'Le censeur place les matières générales');
  for (const r of ['ADMIN_ECOLE', 'CHEF_TRAVAUX', 'SURVEILLANT']) await exiger(role(e, r), 'GET', `/annees/${e.anneeId}/emploi-du-temps`);
  const chef = await exiger(role(e, 'CHEF_TRAVAUX'), 'GET', `/annees/${e.anneeId}/emploi-du-temps`);
  egal([chef.droits.grille, chef.droits.domaines], [false, ['TECHNIQUE']], 'Droits du chef des travaux');
  const prof = e.enseignant(e.enseignants.find((p) => !p.partage)?.matiere);
  if (prof) statut(await appel(prof, 'GET', `/annees/${e.anneeId}/emploi-du-temps`), 403);
  const parent = e.parent(0);
  if (parent) statut(await appel(parent, 'GET', `/annees/${e.anneeId}/emploi-du-temps`), 403);
  return `${edt.seances.length} séances, ${edt.conflits.length} conflit(s)`;
});

scenario('EDT-02', D_EDT, 'Chaque enseignant retrouve son emploi du temps publié et l’exporte en PDF', async (ctx) => {
  const e = technique(ctx);
  const prof = e.enseignants.find((p) => !p.partage);
  const s = e.enseignant(prof?.matiere);
  if (!s) ignorer('Pas d’enseignant');
  const mon = await exiger(s, 'GET', '/espace-enseignant/emploi-du-temps');
  vrai(mon.publieLe && mon.seances.length > 0, 'Séances de l’enseignant');
  const pdf = await exiger(s, 'GET', '/espace-enseignant/emploi-du-temps/export', undefined, { params: { format: 'pdf' }, fichier: true });
  vrai(pdf.octets.subarray(0, 4).toString() === '%PDF', 'Export PDF');
  statut(await appel(role(e, 'SECRETARIAT'), 'GET', '/espace-enseignant/emploi-du-temps'), 403);
  return `${mon.seances.length} séances par semaine`;
});

scenario('EDT-03', D_EDT, 'Exports de l’emploi du temps d’une classe en Excel et en PDF ; un format inconnu est refusé', async (ctx) => {
  const e = technique(ctx);
  const cens = role(e, 'CENSEUR');
  const classe = e.classes[0];
  const xlsx = await exiger(cens, 'GET', `/annees/${e.anneeId}/emploi-du-temps/export`, undefined, { params: { classe: classe.id }, fichier: true });
  vrai(xlsx.octets.subarray(0, 2).toString() === 'PK', 'Classeur Excel');
  const pdf = await exiger(role(e, 'SURVEILLANT'), 'GET', `/annees/${e.anneeId}/emploi-du-temps/export`, undefined, { params: { format: 'pdf' }, fichier: true });
  vrai(pdf.octets.subarray(0, 4).toString() === '%PDF', 'PDF de tout l’établissement');
  statut(await appel(cens, 'GET', `/annees/${e.anneeId}/emploi-du-temps/export`, undefined, { params: { format: 'odt' } }), 400);
});

scenario('EDT-04', D_EDT, 'Le censeur place la matière générale de test sur un créneau libre ; un renvoi ne crée pas de doublon ; le chef des travaux ne la déplace pas', async (ctx) => {
  const m = montage(ctx);
  const e = m.e;
  const cens = role(e, 'CENSEUR');
  const edt = await exiger(cens, 'GET', `/annees/${e.anneeId}/emploi-du-temps`);
  const occupes = new Set(edt.seances.filter((x) => x.classeId === m.classe.id).map((x) => `${x.jour}|${x.creneauId}`));
  const id = randomUUID();
  let place = null;
  let derniere = null;
  for (const c of edt.creneaux) {
    for (const jour of c.jours) {
      if (occupes.has(`${jour}|${c.id}`)) continue;
      const corps = { anneeId: e.anneeId, classeId: m.classe.id, matiereId: m.general.id, jour, creneauId: c.id, salle: 'Salle recette' };
      const r = await appel(cens, 'PUT', `/seances-emploi/${id}`, corps);
      if (r.ok) {
        place = { corps, vue: r.donnees };
        break;
      }
      derniere = r;
      if (r.code !== 'CONFLIT_EMPLOI_DU_TEMPS') throw new Echec(`Placement refusé : ${r.resume()}`);
    }
    if (place) break;
  }
  if (!place) ignorer(`Aucun créneau libre pour la classe et l’enseignant (${derniere?.resume() ?? 'grille pleine'})`);
  m.seancesEmploi.push(id);
  m.creneauLibre = place.corps;
  const nb = place.vue.seances.filter((x) => x.matiereId === m.general.id && x.classeId === m.classe.id).length;
  const encore = await exiger(cens, 'PUT', `/seances-emploi/${id}`, place.corps);
  egal(encore.seances.filter((x) => x.matiereId === m.general.id && x.classeId === m.classe.id).length, nb, 'Renvoi sans doublon');
  statut(await appel(role(e, 'CHEF_TRAVAUX'), 'PUT', `/seances-emploi/${id}`, place.corps), 403);
  statut(await appel(role(e, 'CHEF_TRAVAUX'), 'DELETE', `/seances-emploi/${id}`), 403);
  const prof = m.profG.session;
  statut(await appel(prof, 'PUT', `/seances-emploi/${randomUUID()}`, place.corps), 403);
  return `jour ${place.corps.jour}`;
});

scenario('EDT-05', D_EDT, 'Une seconde matière au même créneau de la même classe est refusée (conflit) ; jour et créneau invalides refusés', async (ctx) => {
  const m = montage(ctx);
  if (!m.creneauLibre) ignorer('Séance de test non placée (EDT-04)');
  const e = m.e;
  const chef = role(e, 'CHEF_TRAVAUX');
  const corps = { ...m.creneauLibre, matiereId: m.pratique.id };
  statut(await appel(role(e, 'CENSEUR'), 'PUT', `/seances-emploi/${randomUUID()}`, corps), 403);
  statut(await appel(chef, 'PUT', `/seances-emploi/${randomUUID()}`, corps), 409, 'CONFLIT_EMPLOI_DU_TEMPS');
  statut(await appel(chef, 'PUT', `/seances-emploi/${randomUUID()}`, { ...corps, jour: 9 }), 400);
  statut(await appel(chef, 'PUT', `/seances-emploi/${randomUUID()}`, { ...corps, creneauId: randomUUID() }), 404);
  statut(await appel(chef, 'PUT', `/seances-emploi/${randomUUID()}`, { ...corps, jour: undefined }), 400);
});

scenario('EDT-06', D_EDT, 'La grille horaire ne se modifie pas par le chef des travaux ; une grille incohérente est refusée sans rien changer', async (ctx) => {
  const e = technique(ctx);
  const cens = role(e, 'CENSEUR');
  const avant = await exiger(cens, 'GET', `/annees/${e.anneeId}/creneaux`);
  const corps = { creneaux: avant.map((c) => ({ id: c.id, heureDebut: c.heureDebut, heureFin: c.heureFin, jours: c.jours })) };
  statut(await appel(role(e, 'CHEF_TRAVAUX'), 'PUT', `/annees/${e.anneeId}/creneaux`, corps), 403);
  statut(await appel(cens, 'PUT', `/annees/${e.anneeId}/creneaux`, { creneaux: [{ heureDebut: '05:00', heureFin: '06:00', jours: [1] }] }), [400, 409]);
  statut(await appel(cens, 'PUT', `/annees/${e.anneeId}/creneaux`, { creneaux: [{ heureDebut: '08:00', heureFin: '07:00', jours: [1] }] }), [400, 409]);
  const apres = await exiger(cens, 'GET', `/annees/${e.anneeId}/creneaux`);
  egal(apres.map((c) => c.id).sort(), avant.map((c) => c.id).sort(), 'Grille inchangée');
});

// ===================================================================== progressions

scenario('PRO-01', D_PRO, 'L’enseignant prépare la fiche de progression de la matière de test ; une fiche vide ne se soumet pas et les saisies invalides sont refusées', async (ctx) => {
  const m = montage(ctx);
  const prof = m.profG.session;
  const base = `/classes/${m.classe.id}/matieres/${m.general.id}/progression`;
  const vide = await exiger(prof, 'GET', base);
  egal([vide.id, vide.statut, vide.auteur], [null, null, true], 'Fiche pas encore commencée, l’enseignant en est l’auteur');
  statut(await appel(prof, 'POST', `${base}/soumission`), 409, 'FICHE_VIDE');
  statut(await appel(prof, 'PUT', base, { sequences: [{ titre: '', heuresPrevues: 4 }] }), 400);
  statut(await appel(prof, 'PUT', base, { sequences: [{ titre: 'Séquence', heuresPrevues: 0 }] }), 400);
  statut(await appel(m.profT.session, 'PUT', base, { sequences: [{ titre: 'Intrus', heuresPrevues: 2 }] }), 403);
  statut(await appel(role(m.e, 'CENSEUR'), 'PUT', base, { sequences: [{ titre: 'Censeur', heuresPrevues: 2 }] }), 403);
  const fiche = await exiger(prof, 'PUT', base, {
    sequences: [
      { titre: 'Prendre des notes', contenu: 'Méthodes de prise de notes', heuresPrevues: 4, semaineDebut: ctx.aujourdhui },
      { titre: 'Faire une fiche de révision', heuresPrevues: 6 },
    ],
  });
  egal(fiche.statut, 'BROUILLON', 'Statut');
  egal(fiche.sequences.map((x) => x.ordre), [1, 2], 'Séquences numérotées');
  egal(Number(fiche.heuresPrevues), 10, 'Heures prévues');
  egal(fiche.domaine, 'GENERAL', 'Domaine');
});

scenario('PRO-02', D_PRO, 'Fiche soumise : figée jusqu’au visa ; le chef des travaux ne vise pas une matière générale ; un renvoi exige un commentaire', async (ctx) => {
  const m = montage(ctx);
  const prof = m.profG.session;
  const base = `/classes/${m.classe.id}/matieres/${m.general.id}/progression`;
  egal((await exiger(prof, 'POST', `${base}/soumission`)).statut, 'SOUMISE', 'Fiche soumise');
  statut(await appel(prof, 'POST', `${base}/soumission`), 409, 'FICHE_DEJA_SOUMISE');
  statut(await appel(prof, 'PUT', base, { sequences: [{ titre: 'Changement', heuresPrevues: 2 }] }), 409, 'FICHE_SOUMISE');
  statut(await appel(prof, 'POST', `${base}/visa`, { accepte: true }), 403);
  statut(await appel(role(m.e, 'CHEF_TRAVAUX'), 'POST', `${base}/visa`, { accepte: true }), 403);
  const cens = role(m.e, 'CENSEUR');
  statut(await appel(cens, 'POST', `${base}/visa`, { accepte: false }), 409, 'COMMENTAIRE_OBLIGATOIRE');
  statut(await appel(cens, 'POST', `${base}/visa`, {}), 400);
  const renvoyee = await exiger(cens, 'POST', `${base}/visa`, { accepte: false, commentaire: 'Recette : ajoutez une évaluation' });
  egal(renvoyee.statut, 'A_REVOIR', 'Fiche à revoir');
  egal(renvoyee.commentaireVisa, 'Recette : ajoutez une évaluation', 'Commentaire transmis');
});

scenario('PRO-03', D_PRO, 'L’enseignant corrige et soumet de nouveau ; le censeur vise ; une fiche visée qu’on modifie repasse en brouillon', async (ctx) => {
  const m = montage(ctx);
  const prof = m.profG.session;
  const base = `/classes/${m.classe.id}/matieres/${m.general.id}/progression`;
  const sequences = [
    { titre: 'Prendre des notes', heuresPrevues: 4 },
    { titre: 'Faire une fiche de révision', heuresPrevues: 6 },
    { titre: 'Évaluation de méthodologie', heuresPrevues: 2 },
  ];
  await exiger(prof, 'PUT', base, { sequences });
  await exiger(prof, 'POST', `${base}/soumission`);
  const cens = role(m.e, 'CENSEUR');
  const visee = await exiger(cens, 'POST', `${base}/visa`, { accepte: true, commentaire: null });
  egal(visee.statut, 'VISEE', 'Fiche visée');
  vrai(visee.viseLe && visee.visePar, 'Date et auteur du visa');
  statut(await appel(cens, 'POST', `${base}/visa`, { accepte: true }), 409, 'FICHE_NON_SOUMISE');
  const modifiee = await exiger(prof, 'PUT', base, { sequences: [...sequences, { titre: 'Remédiation', heuresPrevues: 2 }] });
  egal(modifiee.statut, 'BROUILLON', 'La modification devra être visée à son tour');
  egal(modifiee.viseLe, null, 'Visa effacé');
});

scenario('PRO-04', D_PRO, 'La matière pratique de test est visée par le chef des travaux, pas par le censeur', async (ctx) => {
  const m = montage(ctx);
  const prof = m.profT.session;
  const base = `/classes/${m.classe.id}/matieres/${m.pratique.id}/progression`;
  await exiger(prof, 'PUT', base, { sequences: [{ titre: 'Sécurité à l’atelier', heuresPrevues: 3 }] });
  await exiger(prof, 'POST', `${base}/soumission`);
  statut(await appel(role(m.e, 'CENSEUR'), 'POST', `${base}/visa`, { accepte: true }), 403);
  egal((await exiger(role(m.e, 'CHEF_TRAVAUX'), 'POST', `${base}/visa`, { accepte: true })).statut, 'VISEE', 'Visée par le chef des travaux');
  // Lecture : l'enseignant de la matière générale ne lit pas cette fiche
  statut(await appel(m.profG.session, 'GET', base), 403);
});

scenario('PRO-05', D_PRO, 'Le suivi des progressions montre les fiches de test à la direction ; l’enseignant voit les siennes ; pas d’accès pour la vie scolaire', async (ctx) => {
  const m = montage(ctx);
  const suivi = await exiger(role(m.e, 'CENSEUR'), 'GET', `/annees/${m.e.anneeId}/progressions`);
  const ligne = (id) => suivi.find((x) => x.classeId === m.classe.id && x.matiereId === id);
  egal(ligne(m.general.id)?.statut, 'BROUILLON', 'Fiche générale dans le suivi');
  egal(ligne(m.pratique.id)?.statut, 'VISEE', 'Fiche pratique dans le suivi');
  const miennes = await exiger(m.profG.session, 'GET', '/espace-enseignant/progressions');
  vrai(miennes.some((x) => x.matiereId === m.general.id), 'Fiche de test parmi celles de l’enseignant');
  vrai(!miennes.some((x) => x.matiereId === m.pratique.id), 'Pas la fiche d’un autre enseignant');
  statut(await appel(role(m.e, 'SURVEILLANT'), 'GET', `/annees/${m.e.anneeId}/progressions`), 403);
  statut(await appel(m.profG.session, 'GET', `/annees/${m.e.anneeId}/progressions`), 403);
  return `${suivi.length} lignes de suivi`;
});

// ===================================================================== cahier de textes

scenario('CAH-01', D_CAH, 'L’enseignant note une séance rattachée à une séquence ; un renvoi avec le même identifiant ne crée pas de doublon', async (ctx) => {
  const m = montage(ctx);
  const prof = m.profG.session;
  const id = randomUUID();
  const corps = seance(m, ctx.aujourdhui, '07:00', '09:00', { sequenceOrdre: 1, travailAFaire: 'Relire le cours' });
  const v = await exiger(prof, 'PUT', `/cahier-textes/${id}`, corps);
  egal([v.sequenceOrdre, v.sequenceTitre, Number(v.heures)], [1, 'Prendre des notes', 2], 'Séance rattachée à la séquence 1');
  m.seancesCahier.push(id);
  await exiger(prof, 'PUT', `/cahier-textes/${id}`, { ...corps, contenu: 'Recette : cours de méthodologie (corrigé)' });
  const liste = await exiger(prof, 'GET', `/classes/${m.classe.id}/matieres/${m.general.id}/cahier-textes`);
  egal(liste.filter((x) => x.id === id).length, 1, 'Une seule séance');
  egal(liste.find((x) => x.id === id).contenu, 'Recette : cours de méthodologie (corrigé)', 'Séance corrigée');
  const fiche = await exiger(prof, 'GET', `/classes/${m.classe.id}/matieres/${m.general.id}/progression`);
  egal(Number(fiche.avancement.heuresRealisees), 2, 'Avancement de la progression');
  egal(Number(fiche.sequences[0].heuresRealisees), 2, 'Heures faites sur la séquence 1');
});

scenario('CAH-02', D_CAH, 'Le cahier refuse une séance future, des heures incohérentes, plus de 8 h, un contenu vide et un doublon au même horaire', async (ctx) => {
  const m = montage(ctx);
  const prof = m.profG.session;
  const nouveau = () => `/cahier-textes/${randomUUID()}`;
  statut(await appel(prof, 'PUT', nouveau(), seance(m, ajouterJours(ctx.aujourdhui, 2), '07:00', '08:00')), 409, 'DATE_FUTURE');
  statut(await appel(prof, 'PUT', nouveau(), seance(m, ctx.aujourdhui, '10:00', '09:00')), 400);
  statut(await appel(prof, 'PUT', nouveau(), seance(m, ctx.aujourdhui, '06:00', '15:00')), 400);
  statut(await appel(prof, 'PUT', nouveau(), seance(m, ctx.aujourdhui, '10:00', '11:00', { contenu: '  ' })), 400);
  statut(await appel(prof, 'PUT', nouveau(), { ...seance(m, ctx.aujourdhui, '10:00', '11:00'), matiereId: undefined }), 400);
  if (m.seancesCahier.length) {
    statut(await appel(prof, 'PUT', nouveau(), seance(m, ctx.aujourdhui, '07:00', '08:00')), 409, 'SEANCE_EXISTANTE');
    // Même identifiant pour un autre cours
    statut(await appel(m.profT.session, 'PUT', `/cahier-textes/${m.seancesCahier[0]}`, {
      ...seance(m, ctx.aujourdhui, '14:00', '15:00'), matiereId: m.pratique.id,
    }), 409, 'IDENTIFIANT_DEJA_UTILISE');
  }
});

scenario('CAH-03', D_CAH, 'Le censeur lit le cahier sans l’écrire ; un autre enseignant ne l’écrit ni ne le supprime ; l’auteur supprime sa séance', async (ctx) => {
  const m = montage(ctx);
  const cens = role(m.e, 'CENSEUR');
  const chemin = `/classes/${m.classe.id}/matieres/${m.general.id}/cahier-textes`;
  await exiger(cens, 'GET', chemin);
  statut(await appel(cens, 'PUT', `/cahier-textes/${randomUUID()}`, seance(m, ctx.aujourdhui, '15:00', '16:00')), 403);
  statut(await appel(m.profT.session, 'PUT', `/cahier-textes/${randomUUID()}`, seance(m, ctx.aujourdhui, '15:00', '16:00')), 403);
  statut(await appel(m.profT.session, 'GET', chemin), 403);
  const id = randomUUID();
  await exiger(m.profG.session, 'PUT', `/cahier-textes/${id}`, seance(m, ajouterJours(ctx.aujourdhui, -1), '15:00', '16:00'));
  if (m.seancesCahier.length) statut(await appel(m.profT.session, 'DELETE', `/cahier-textes/${m.seancesCahier[0]}`), 403);
  statut(await appel(m.profG.session, 'DELETE', `/cahier-textes/${id}`), 204);
  statut(await appel(m.profG.session, 'DELETE', `/cahier-textes/${id}`), 404);
});

// ===================================================================== évaluations

scenario('EVA-01', D_EVA, 'L’enseignant crée un devoir dans la période en cours ; renvoyé avec le même identifiant (hors connexion), il n’est pas créé deux fois', async (ctx) => {
  const m = montage(ctx);
  const prof = m.profG.session;
  const periode = await periodeCourante(ctx, m.e, prof);
  if (!periode) ignorer('Aucune période en cours aujourd’hui');
  if (periode.verrouillee) ignorer(`La période ${periode.libelle} est verrouillée`);
  m.periode = periode;
  const idClient = randomUUID();
  const corps = { matiereId: m.general.id, periodeId: periode.id, libelle: 'Recette : devoir n° 1', type: 'DEVOIR', date: ctx.aujourdhui, bareme: 20, poids: 1, idClient };
  const ev = await exiger(prof, 'POST', `/classes/${m.classe.id}/evaluations`, corps);
  egal(ev.id, idClient, 'Identifiant choisi par l’appareil');
  m.evaluations.push(ev.id);
  const encore = await exiger(prof, 'POST', `/classes/${m.classe.id}/evaluations`, corps);
  egal(encore.id, ev.id, 'Renvoi : même évaluation');
  const liste = await exiger(prof, 'GET', `/classes/${m.classe.id}/evaluations`, undefined, { params: { periodeId: periode.id } });
  egal(liste.filter((x) => x.id === ev.id).length, 1, 'Une seule évaluation');
  vrai(ev.effectif > 0, 'Effectif de la classe');
  // Le même identifiant pour une autre matière est refusé
  statut(await appel(m.profT.session, 'POST', `/classes/${m.classe.id}/evaluations`, { ...corps, matiereId: m.pratique.id }), 409, 'IDENTIFIANT_DEJA_UTILISE');
  return `${periode.libelle}, ${ev.effectif} élèves`;
});

scenario('EVA-02', D_EVA, 'Une évaluation mal saisie est refusée : date hors période, barème ou poids invalides, libellé vide, matière d’un autre enseignant', async (ctx) => {
  const m = montage(ctx);
  if (!m.periode) ignorer('Période en cours inconnue (EVA-01)');
  const prof = m.profG.session;
  const base = { matiereId: m.general.id, periodeId: m.periode.id, libelle: 'Recette : invalide', type: 'INTERROGATION', date: ctx.aujourdhui };
  const creer = (corps, qui = prof) => appel(qui, 'POST', `/classes/${m.classe.id}/evaluations`, corps);
  statut(await creer({ ...base, date: ajouterJours(m.periode.fin, 1) }), 409, 'DATE_HORS_PERIODE');
  statut(await creer({ ...base, bareme: 0 }), 400);
  statut(await creer({ ...base, bareme: 150 }), 400);
  statut(await creer({ ...base, bareme: 20.555 }), 400);
  statut(await creer({ ...base, poids: 11 }), 400);
  statut(await creer({ ...base, libelle: '' }), 400);
  statut(await creer({ ...base, type: 'QUIZ' }), 400);
  statut(await creer({ ...base, matiereId: m.pratique.id }), 403);
  statut(await creer(base, role(m.e, 'SECRETARIAT')), 403);
  const parent = m.e.parent(0);
  if (parent) statut(await creer(base, parent), 403);
});

scenario('EVA-03', D_EVA, 'Saisie des notes : valeurs, absence, effacement ; note hors barème, trois décimales et élève en double refusés ; élève étranger ignoré', async (ctx) => {
  const m = montage(ctx);
  const evId = m.evaluations[0];
  if (!evId) ignorer('Évaluation non créée (EVA-01)');
  const prof = m.profG.session;
  const feuille = await exiger(prof, 'GET', `/evaluations/${evId}/notes`);
  const eleves = feuille.lignes.map((l) => l.inscriptionId);
  if (eleves.length < 3) ignorer('Moins de trois élèves dans la classe');
  const [a, b, c] = eleves;
  const saisir = (notes) => appel(prof, 'PUT', `/evaluations/${evId}/notes`, { notes });
  statut(await saisir([{ inscriptionId: a, valeur: 21 }]), 400);
  statut(await saisir([{ inscriptionId: a, valeur: -1 }]), 400);
  statut(await saisir([{ inscriptionId: a, valeur: 12.555 }]), 400);
  statut(await saisir([{ inscriptionId: a, valeur: 12 }, { inscriptionId: a, valeur: 13 }]), 400);
  statut(await saisir([{ valeur: 12 }]), 400);
  const etranger = ctx.ecole(2)?.famille(0)?.enfants[0]?.inscriptionId ?? randomUUID();
  const r = await exiger(prof, 'PUT', `/evaluations/${evId}/notes`, {
    notes: [{ inscriptionId: a, valeur: 15.5 }, { inscriptionId: b, absent: true }, { inscriptionId: c, valeur: 8.25 }, { inscriptionId: etranger, valeur: 10 }],
  });
  vrai(r.ignorees.includes(etranger), 'L’inscription étrangère à la classe est ignorée et signalée');
  const ligne = (id) => r.lignes.find((l) => l.inscriptionId === id);
  egal([Number(ligne(a).valeur), ligne(b).absent, Number(ligne(c).valeur)], [15.5, true, 8.25], 'Notes enregistrées');
  // Renvoi identique (hors connexion) : même résultat ; puis effacement de la note de c
  await exiger(prof, 'PUT', `/evaluations/${evId}/notes`, { notes: [{ inscriptionId: a, valeur: 15.5 }] });
  const apres = await exiger(prof, 'PUT', `/evaluations/${evId}/notes`, { notes: [{ inscriptionId: c }] });
  egal(apres.lignes.find((l) => l.inscriptionId === c).valeur, null, 'Note effacée');
  egal(apres.evaluation.notesSaisies, 2, 'Deux notes saisies (une valeur, une absence)');
  m.notees = { a, b };
});

scenario('EVA-04', D_EVA, 'Le barème ne descend pas sous une note déjà saisie ; la modification valide est acceptée ; la feuille se lit au secrétariat', async (ctx) => {
  const m = montage(ctx);
  const evId = m.evaluations[0];
  if (!evId || !m.notees) ignorer('Notes non saisies (EVA-03)');
  const prof = m.profG.session;
  const corps = { libelle: 'Recette : devoir n° 1', type: 'DEVOIR', date: ctx.aujourdhui, bareme: 10, poids: 2 };
  statut(await appel(prof, 'PUT', `/evaluations/${evId}`, corps), 409, 'BAREME_INFERIEUR_AUX_NOTES');
  const v = await exiger(prof, 'PUT', `/evaluations/${evId}`, { ...corps, bareme: 40 });
  egal([Number(v.bareme), Number(v.poids)], [40, 2], 'Barème et poids modifiés');
  const feuille = await exiger(role(m.e, 'SECRETARIAT'), 'GET', `/evaluations/${evId}/notes`);
  egal(Number(feuille.lignes.find((l) => l.inscriptionId === m.notees.a).valeur), 15.5, 'Note lue au secrétariat');
  statut(await appel(role(m.e, 'SURVEILLANT'), 'GET', `/evaluations/${evId}/notes`), 403);
  // (l'enseignant de la matière pratique enseigne dans la classe : il lit la feuille, c'est voulu pour le conseil de classe)
  refuse(await appel(ctx.ecole(3).session('ADMIN_ECOLE'), 'GET', `/evaluations/${evId}/notes`), 'Feuille lue par un autre établissement');
});

scenario('EVA-05', D_EVA, 'Le suivi des évaluations et les résultats de la classe intègrent la matière de test, avec moyennes et rangs', async (ctx) => {
  const m = montage(ctx);
  if (!m.periode || !m.notees) ignorer('Notes non saisies (EVA-03)');
  const suivi = await exiger(role(m.e, 'CENSEUR'), 'GET', `/annees/${m.e.anneeId}/suivi-evaluations`, undefined, { params: { ordre: m.periode.ordre } });
  const ligne = suivi.find((x) => x.classeId === m.classe.id && x.matiereId === m.general.id);
  vrai(ligne, 'Matière de test dans le suivi');
  egal([ligne.evaluations, ligne.notesSaisies, ligne.parType.DEVOIR], [1, 2, 1], 'Évaluations et notes du suivi');
  const leSien = await exiger(m.profG.session, 'GET', `/annees/${m.e.anneeId}/suivi-evaluations`);
  vrai(leSien.every((x) => x.engagementId === m.profG.engagementId), 'L’enseignant ne voit que ses matières');
  const res = await exiger(role(m.e, 'CENSEUR'), 'GET', `/classes/${m.classe.id}/resultats`, undefined, { params: { periodeId: m.periode.id } });
  egal(res.periodeId, m.periode.id, 'Période des résultats');
  const eleve = res.eleves.find((x) => x.inscriptionId === m.notees.a);
  const matiere = eleve?.matieres?.find((x) => x.matiereId === m.general.id);
  vrai(matiere, 'Moyenne de la matière de test pour l’élève noté');
  // Une seule note pour cet élève : 15,5 sur un barème de 40, soit 7,75 sur 20
  vrai(Math.abs(Number(matiere.moyenne) - 7.75) < 0.01, `Moyenne de la matière sur 20 : attendu 7,75, obtenu ${matiere.moyenne}`);
  return `modèle ${res.modele}, moyenne de classe ${res.moyenneClasse ?? '—'}`;
});

scenario('EVA-06', D_EVA, 'Une période verrouillée refuse toute nouvelle évaluation ou saisie de compétences (Module 1 du centre de formation)', async (ctx) => {
  const e = centre(ctx);
  const periodes = await exiger(role(e, 'CENSEUR'), 'GET', `/annees/${e.anneeId}/periodes`);
  const m1 = periodes.find((p) => p.verrouillee);
  if (!m1) ignorer('Aucune période verrouillée au centre de formation');
  const c = e.classes.find((x) => x.matieres.some((y) => y.type === 'MODULE_COMPETENCES' && y.enseignant));
  if (!c) ignorer('Pas de module avec enseignant');
  const mod = c.matieres.find((y) => y.type === 'MODULE_COMPETENCES' && y.enseignant);
  const prof = ctx.compte(mod.enseignant, e.id);
  statut(await appel(prof, 'POST', `/classes/${c.id}/evaluations`, {
    matiereId: mod.id, periodeId: m1.id, libelle: 'Recette', type: 'TP', date: m1.debut,
  }), 409, 'PERIODE_VERROUILLEE');
  const grille = await exiger(prof, 'GET', `/classes/${c.id}/competences`, undefined, { params: { periodeId: m1.id, matiereId: mod.id } });
  vrai(grille.competences.length > 0, 'Référentiel du module');
  const premier = grille.lignes[0];
  if (premier) {
    statut(await appel(prof, 'PUT', `/classes/${c.id}/competences`, {
      resultats: [{ inscriptionId: premier.inscriptionId, competenceId: grille.competences[0].id, niveau: 'ACQUIS' }],
    }, { params: { periodeId: m1.id, matiereId: mod.id } }), 409, 'PERIODE_VERROUILLEE');
  }
  return `${m1.libelle} verrouillé, ${grille.competences.length} compétences, ${grille.lignes.length} apprenants`;
});

scenario('EVA-07', D_EVA, 'Compétences : un module ne se note pas ; une compétence d’un autre module est refusée ; un code en double aussi ; une matière générale n’a pas de compétences', async (ctx) => {
  const e = centre(ctx);
  const c = e.classes.find((x) => x.matieres.filter((y) => y.type === 'MODULE_COMPETENCES' && y.enseignant).length >= 1);
  if (!c) ignorer('Pas de module avec enseignant au centre de formation');
  const modules = c.matieres.filter((y) => y.type === 'MODULE_COMPETENCES');
  const mod = modules.find((y) => y.enseignant);
  const prof = ctx.compte(mod.enseignant, e.id);
  const periode = await periodeCourante(ctx, e, prof);
  const ouverte = periode && !periode.verrouillee ? periode : null;
  if (ouverte) {
    statut(await appel(prof, 'POST', `/classes/${c.id}/evaluations`, {
      matiereId: mod.id, periodeId: ouverte.id, libelle: 'Recette', type: 'TP', date: ctx.aujourdhui,
    }), 409, 'MODULE_EN_COMPETENCES');
    const autre = e.classes.flatMap((x) => x.matieres).find((y) => y.type === 'MODULE_COMPETENCES' && y.id !== mod.id);
    if (autre) {
      const compAutre = (await exiger(prof, 'GET', `/matieres/${autre.id}/competences`))[0];
      const ins = (await exiger(prof, 'GET', `/classes/${c.id}/inscriptions`))[0];
      if (compAutre && ins) {
        statut(await appel(prof, 'PUT', `/classes/${c.id}/competences`, {
          resultats: [{ inscriptionId: ins.id, competenceId: compAutre.id, niveau: 'ACQUIS' }],
        }, { params: { periodeId: ouverte.id, matiereId: mod.id } }), 409, 'COMPETENCE_HORS_MODULE');
      }
    }
  }
  const ref = await exiger(role(e, 'CENSEUR'), 'GET', `/matieres/${mod.id}/competences`);
  if (ref[0]) {
    statut(await appel(role(e, 'CENSEUR'), 'POST', `/matieres/${mod.id}/competences`, { code: ref[0].code, libelle: 'Doublon' }), 409, 'CODE_EXISTANT');
  }
  statut(await appel(prof, 'POST', `/matieres/${mod.id}/competences`, { code: 'X1', libelle: 'Refusée' }), 403);
  const t = technique(ctx);
  const generale = t.classes.flatMap((x) => x.matieres).find((y) => y.type === 'GENERALE');
  if (generale) {
    statut(await appel(role(t, 'CENSEUR'), 'POST', `/matieres/${generale.id}/competences`, { code: 'X1', libelle: 'Refusée' }), 409, 'PAS_UN_MODULE');
  }
  return ouverte ? `période ouverte : ${ouverte.libelle}` : 'pas de période ouverte : contrôles de saisie non joués';
});

scenario('EVA-08', D_EVA, 'Les résultats du Module 1 donnent, pour chaque apprenant, le taux de maîtrise de ses modules', async (ctx) => {
  const e = centre(ctx);
  const periodes = await exiger(role(e, 'CENSEUR'), 'GET', `/annees/${e.anneeId}/periodes`);
  const m1 = periodes.find((p) => p.verrouillee);
  if (!m1) ignorer('Aucune période verrouillée au centre de formation');
  const c = e.classes[0];
  const res = await exiger(role(e, 'SECRETARIAT'), 'GET', `/classes/${c.id}/resultats`, undefined, { params: { periodeId: m1.id } });
  vrai(res.eleves.length > 0, 'Apprenants dans les résultats');
  vrai(res.eleves.every((x) => Array.isArray(x.modules)), 'Modules par apprenant');
  vrai(res.eleves.some((x) => x.tauxMaitrise !== null), 'Taux de maîtrise calculé');
  statut(await appel(role(e, 'SURVEILLANT'), 'GET', `/classes/${c.id}/resultats`, undefined, { params: { periodeId: m1.id } }), 403);
  return `${c.code} : modèle ${res.modele}, ${res.eleves.length} apprenants`;
});

// ===================================================================== bulletins

scenario('BUL-01', D_BUL, 'Les relevés publiés du Module 1 se consultent avec un code de vérification par apprenant ; ils ne se régénèrent ni ne se republient', async (ctx) => {
  const e = centre(ctx);
  const cens = role(e, 'CENSEUR');
  const periodes = await exiger(cens, 'GET', `/annees/${e.anneeId}/periodes`);
  const m1 = periodes.find((p) => p.verrouillee);
  if (!m1) ignorer('Aucune période verrouillée au centre de formation');
  const c = e.classes[0];
  const g = await exiger(role(e, 'SECRETARIAT'), 'GET', `/classes/${c.id}/periodes/${m1.id}/bulletins`);
  egal(g.statut, 'PUBLIEE', 'Relevés publiés par le peuplement');
  vrai(g.bulletins.length > 0 && g.bulletins.every((b) => b.codeVerification), 'Un code de vérification par relevé');
  statut(await appel(cens, 'POST', `/classes/${c.id}/periodes/${m1.id}/bulletins`), 409, 'BULLETINS_PUBLIES');
  statut(await appel(cens, 'POST', `/classes/${c.id}/periodes/${m1.id}/bulletins/publication`), 409, 'BULLETINS_PUBLIES');
  statut(await appel(role(e, 'SECRETARIAT'), 'POST', `/classes/${c.id}/periodes/${m1.id}/bulletins`), 403);
  const prof = e.enseignant(e.enseignants.find((p) => !p.partage)?.matiere);
  if (prof) statut(await appel(prof, 'GET', `/classes/${c.id}/periodes/${m1.id}/bulletins`), 403);
  etat(ctx).bulletins = { e, classe: c, periode: m1, generation: g };
  return `${c.code} : ${g.bulletins.length} relevés`;
});

scenario('BUL-02', D_BUL, 'Les relevés s’impriment en PDF (classe entière et un par un) ; le code se vérifie sans connexion, sans donnée de contact', async (ctx) => {
  const b = etat(ctx).bulletins;
  if (!b) ignorer('Relevés non trouvés (BUL-01)');
  const sec = role(b.e, 'SECRETARIAT');
  const classe = await exiger(sec, 'GET', `/classes/${b.classe.id}/periodes/${b.periode.id}/bulletins/pdf`, undefined, { fichier: true });
  vrai(classe.octets.subarray(0, 4).toString() === '%PDF', 'PDF de la classe');
  const premier = b.generation.bulletins[0];
  const un = await exiger(sec, 'GET', `/bulletins/${premier.id}/pdf`, undefined, { fichier: true });
  vrai(un.octets.subarray(0, 4).toString() === '%PDF', 'PDF d’un relevé');
  const v = await appelBrut('GET', `/verification/bulletins/${encodeURIComponent(premier.codeVerification)}`);
  statut(v, 200);
  egal([v.donnees.matricule, v.donnees.periode], [premier.matricule, b.periode.libelle], 'Vérification publique');
  vrai(!JSON.stringify(v.donnees).match(/\+?226\d{8}/), 'Aucun numéro de téléphone dans la vérification');
  refuse(await appel(ctx.ecole(1).session('ADMIN_ECOLE'), 'GET', `/bulletins/${premier.id}/pdf`), 'Relevé lu par un autre établissement');
});

scenario('BUL-03', D_BUL, 'Le parent retrouve le relevé publié de son enfant et le télécharge ; pas celui d’un autre enfant', async (ctx) => {
  const b = etat(ctx).bulletins;
  if (!b) ignorer('Relevés non trouvés (BUL-01)');
  const e = b.e;
  let parent = null;
  let enfant = null;
  for (let i = 0; i < 10 && !enfant; i++) {
    const f = e.famille(i);
    if (!f) break;
    const x = f.enfants.find((y) => y.ecole === e.n);
    if (x) {
      parent = e.parent(i);
      enfant = x;
    }
  }
  if (!parent) ignorer('Aucune famille avec espace parent au centre de formation');
  const liste = await exiger(parent, 'GET', `/espace-parent/enfants/${enfant.eleveId}/bulletins`);
  vrai(liste.length > 0, 'Au moins un relevé publié');
  const pdf = await exiger(parent, 'GET', `/espace-parent/bulletins/${liste[0].id}/pdf`, undefined, { fichier: true });
  vrai(pdf.octets.subarray(0, 4).toString() === '%PDF', 'PDF téléchargé par le parent');
  const autre = b.generation.bulletins.find((x) => x.inscriptionId !== enfant.inscriptionId);
  if (autre) refuse(await appel(parent, 'GET', `/espace-parent/bulletins/${autre.id}/pdf`), 'Relevé d’un autre enfant');
  const autreEleve = e.familles.flatMap((f) => f.enfants).find((x) => x.ecole === e.n && x.eleveId !== enfant.eleveId);
  if (autreEleve) refuse(await appel(parent, 'GET', `/espace-parent/enfants/${autreEleve.eleveId}/bulletins`), 'Relevés d’un autre enfant');
  return `${liste.length} relevé(s) : ${liste.map((x) => x.periode).join(', ')}`;
});

scenario('BUL-04', D_BUL, 'Sur une période publiée, appréciations et avis du conseil ne se modifient plus', async (ctx) => {
  const b = etat(ctx).bulletins;
  if (!b) ignorer('Relevés non trouvés (BUL-01)');
  const premier = b.generation.bulletins[0];
  const mod = b.classe.matieres.find((m) => m.enseignant);
  const cens = role(b.e, 'CENSEUR');
  if (mod) {
    statut(await appel(cens, 'PUT', `/classes/${b.classe.id}/periodes/${b.periode.id}/appreciations`, {
      matiereId: mod.id, appreciations: [{ inscriptionId: premier.inscriptionId, texte: 'Recette' }],
    }), 409, 'BULLETINS_PUBLIES');
  }
  statut(await appel(cens, 'PUT', `/classes/${b.classe.id}/periodes/${b.periode.id}/conseil`, {
    avis: [{ inscriptionId: premier.inscriptionId, distinction: 'ENCOURAGEMENTS', appreciation: 'Recette' }],
  }), 409, 'BULLETINS_PUBLIES');
});

scenario('BUL-05', D_BUL, 'Lycée : les bulletins de la période en cours ne se génèrent pas tant qu’elle n’est pas verrouillée', async (ctx) => {
  const e = technique(ctx);
  const cens = role(e, 'CENSEUR');
  const periode = await periodeCourante(ctx, e, cens);
  if (!periode || periode.verrouillee) ignorer('Pas de période ouverte en cours au lycée technique');
  statut(await appel(cens, 'POST', `/classes/${e.classes[0].id}/periodes/${periode.id}/bulletins`), 409, 'PERIODE_NON_VERROUILLEE');
  statut(await appel(cens, 'POST', `/classes/${e.classes[0].id}/periodes/${periode.id}/bulletins/publication`), 409);
  return periode.libelle;
});

scenario('BUL-06', D_BUL, 'L’enseignant saisit l’appréciation de sa matière pour la période en cours, la corrige puis l’efface ; un autre enseignant ne le peut pas', async (ctx) => {
  const m = montage(ctx);
  if (!m.periode) ignorer('Période en cours inconnue (EVA-01)');
  const chemin = `/classes/${m.classe.id}/periodes/${m.periode.id}/appreciations`;
  const liste = await exiger(m.profG.session, 'GET', chemin, undefined, { params: { matiereId: m.general.id } });
  const ins = liste[0]?.inscriptionId;
  if (!ins) ignorer('Pas d’élève dans la classe');
  const saisir = (texte, qui = m.profG.session) => appel(qui, 'PUT', chemin, { matiereId: m.general.id, appreciations: [{ inscriptionId: ins, texte }] });
  statut(await saisir('x'.repeat(201)), 400);
  statut(await saisir('Intrus', m.profT.session), 403);
  const r = await exiger(m.profG.session, 'PUT', chemin, { matiereId: m.general.id, appreciations: [{ inscriptionId: ins, texte: 'Recette : bon travail' }] });
  egal(r.find((x) => x.inscriptionId === ins)?.texte, 'Recette : bon travail', 'Appréciation enregistrée');
  const efface = await exiger(m.profG.session, 'PUT', chemin, { matiereId: m.general.id, appreciations: [{ inscriptionId: ins, texte: '' }] });
  egal(efface.find((x) => x.inscriptionId === ins)?.texte ?? null, null, 'Appréciation effacée');
});

scenario('BUL-07', D_BUL, 'Le conseil de classe enregistre un avis et une distinction, puis l’avis d’origine est rétabli ; un enseignant n’y a pas accès', async (ctx) => {
  const e = technique(ctx);
  const cens = role(e, 'CENSEUR');
  const periode = await periodeCourante(ctx, e, cens);
  if (!periode || periode.verrouillee) ignorer('Pas de période ouverte en cours au lycée technique');
  const classe = e.classes[0];
  const chemin = `/classes/${classe.id}/periodes/${periode.id}/conseil`;
  const avis = await exiger(cens, 'GET', chemin);
  const premier = avis[0];
  if (!premier) ignorer('Pas d’élève dans la classe');
  const prof = e.enseignant(e.enseignants.find((p) => !p.partage)?.matiere);
  if (prof) statut(await appel(prof, 'GET', chemin), 403);
  statut(await appel(cens, 'PUT', chemin, { avis: [{ inscriptionId: premier.inscriptionId, appreciation: 'x'.repeat(301) }] }), 400);
  try {
    const r = await exiger(cens, 'PUT', chemin, { avis: [{ inscriptionId: premier.inscriptionId, distinction: 'ENCOURAGEMENTS', appreciation: 'Recette : élève sérieux' }] });
    const v = r.find((x) => x.inscriptionId === premier.inscriptionId);
    egal([v.distinction, v.appreciation], ['ENCOURAGEMENTS', 'Recette : élève sérieux'], 'Avis du conseil');
  } finally {
    await appel(cens, 'PUT', chemin, { avis: [{ inscriptionId: premier.inscriptionId, distinction: premier.distinction, appreciation: premier.appreciation }] });
  }
  const retabli = (await exiger(cens, 'GET', chemin)).find((x) => x.inscriptionId === premier.inscriptionId);
  egal([retabli.distinction, retabli.appreciation], [premier.distinction, premier.appreciation], 'Avis d’origine rétabli');
});

scenario('BUL-08', D_BUL, 'Paramètres des bulletins : lus par la direction et le secrétariat ; seuils incohérents refusés ; réécrits à l’identique par l’administrateur', async (ctx) => {
  const e = technique(ctx);
  const p = await exiger(role(e, 'SECRETARIAT'), 'GET', '/parametres/bulletins');
  vrai(p.entetePays, 'En-tête du pays');
  statut(await appel(role(e, 'CENSEUR'), 'PUT', '/parametres/bulletins', p), 403);
  const adm = role(e, 'ADMIN_ECOLE');
  statut(await appel(adm, 'PUT', '/parametres/bulletins', { ...p, seuilAvertissement: 15, seuilTableauHonneur: 12 }), 400);
  statut(await appel(adm, 'PUT', '/parametres/bulletins', { ...p, entetePays: '' }), 400);
  statut(await appel(adm, 'PUT', '/parametres/bulletins', { ...p, seuilFelicitations: 25 }), 400);
  const r = await exiger(adm, 'PUT', '/parametres/bulletins', p);
  egal(r, p, 'Paramètres inchangés');
});

// ===================================================================== nettoyage

scenario('PED-02', D_PED, 'Fin du lot : évaluations, séances et matières de test retirées ; une matière désactivée ne se replace plus', async (ctx) => {
  const m = montage(ctx);
  const cens = role(m.e, 'CENSEUR');
  const erreurs = [];
  const tenter = async (quoi, r) => {
    if (!r.ok && r.statut !== 404) erreurs.push(`${quoi} : ${r.resume()}`);
  };
  for (const id of m.evaluations) await tenter('évaluation', await appel(m.profG.session, 'DELETE', `/evaluations/${id}`));
  if (m.evaluations[0]) statut(await appel(m.profG.session, 'GET', `/evaluations/${m.evaluations[0]}/notes`), 404);
  for (const id of m.seancesCahier) await tenter('séance du cahier', await appel(m.profG.session, 'DELETE', `/cahier-textes/${id}`));
  for (const id of m.seancesEmploi) await tenter('séance de l’emploi du temps', await appel(cens, 'DELETE', `/seances-emploi/${id}`));
  for (const mat of [m.general, m.pratique]) {
    await tenter(`retrait de ${mat.code}`, await appel(cens, 'DELETE', `/classes/${m.classe.id}/matieres/${mat.id}`));
    await tenter(`désactivation de ${mat.code}`, await appel(cens, 'PUT', `/matieres/${mat.id}`, { libelle: `${mat.libelle} (désactivée)`, type: mat.type, actif: false }));
  }
  if (erreurs.length) throw new Echec(erreurs.join(' ; '));
  statut(await appel(cens, 'PUT', `/classes/${m.classe.id}/matieres/${m.general.id}`, matiereDeClasse()), 409, 'MATIERE_INACTIVE');
  const matieres = await exiger(cens, 'GET', `/classes/${m.classe.id}/matieres`);
  vrai(!matieres.some((x) => [m.general.id, m.pratique.id].includes(x.matiereId)), 'Matières de test retirées de la classe');
});
