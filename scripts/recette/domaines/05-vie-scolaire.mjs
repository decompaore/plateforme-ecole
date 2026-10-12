/**
 * Lot 05 : vie scolaire — appels et absences, justificatifs, incidents et sanctions, convocations, suivi par
 * la famille.
 *
 * Préfixes : VIE- (mise en place et fin), APP- (appels, absences, justificatifs), INC- (incidents et sanctions),
 * CNV- (convocations et historiques).
 *
 * Sources vérifiées : absences/AbsencesController, AppelsService, AbsencesService, Autorisations, Vues,
 * TypeAbsence, TypeJustificatif, StatutAccuse ; viescolaire/VieScolaireController, VieScolaireService (records
 * SaisieIncident, IncidentVue, ConvocationVue, HistoriqueVue, LigneClasseVue), TypeIncident, StatutConvocation,
 * Convocation, Incident ; notifications/NotificationsController, NotificationsService (clés « absence: »,
 * « incident: », « convocation: », destinataire masqué) ; eleves/ElevesController, EspaceParentService.
 *
 * Données : au lycée Horizon (n° 3), un élève de test créé à chaque passage (avec l'espace parent de sa mère),
 * inscrit dans une classe de démonstration puis sorti à la fin. Les appels de test ne marquent que lui (les
 * autres élèves sont présents) sur un créneau tôt le matin choisi au hasard ; ils restent dans l'historique de la
 * classe, comme les incidents (annulés pour la plupart) et la convocation (annulée).
 */
import { randomUUID } from 'node:crypto';

import {
  ajouterJours, appel, Echec, egal, exiger, ignorer, refuse, scenario, statut, suffixe, telephoneUnique, vrai,
} from '../lib.mjs';

const D_VIE = 'Vie scolaire : mise en place';
const D_APP = 'Appels et absences';
const D_INC = 'Incidents et sanctions';
const D_CNV = 'Convocations et suivi';

// ===================================================================== aides

function etat(ctx) {
  ctx.partage.vieScolaire ??= {};
  return ctx.partage.vieScolaire;
}

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

function eleve(ctx) {
  const x = etat(ctx).eleve;
  if (!x) ignorer(`Élève de test non installé (VIE-01)${etat(ctx).echec ? ` : ${etat(ctx).echec}` : ''}`);
  return x;
}

function lettres(texte) {
  return texte.replace(/[0-9]/g, (d) => 'ABCDEFGHIJ'[Number(d)]);
}

function heure(h, m) {
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/** Envoie un appel seul dans un lot et renvoie son accusé. */
async function envoyer(qui, donnees) {
  const r = await appel(qui, 'POST', '/appels/lot', { appels: [donnees] });
  if (!r.ok) throw new Echec(`Lot refusé : ${r.resume()}`);
  return r.donnees[0];
}

/** Appel sur un créneau libre tôt le matin (05 h 00 à 05 h 59), en évitant ceux déjà faits. */
async function appelSurCreneauLibre(qui, x, date, marques) {
  for (let essai = 0; essai < 8; essai++) {
    const minute = Math.floor(Math.random() * 30);
    const donnees = {
      idClient: randomUUID(), classeId: x.classeId, matiereId: x.matiereId, date,
      heureDebut: heure(5, minute), heureFin: heure(5, minute + 25), saisiLe: new Date().toISOString(), marques,
    };
    const accuse = await envoyer(qui, donnees);
    if (accuse.statut === 'ENREGISTRE') return { donnees, accuse };
    if (accuse.code !== 'CRENEAU_DEJA_FAIT') throw new Echec(`Appel refusé : ${accuse.code} ${accuse.message}`);
  }
  throw new Echec('Aucun créneau libre trouvé pour l’appel de test');
}

/** Dernier SMS planifié dont la clé commence ainsi et qui cite l'élève de test. */
async function sms(e, x, prefixeCle) {
  const liste = await exiger(role(e, 'SURVEILLANT'), 'GET', '/notifications', undefined, { params: { limite: 500 } });
  return liste.find((n) => n.cle?.startsWith(prefixeCle) && n.message?.includes(x.prenoms)) ?? null;
}

// ===================================================================== mise en place

scenario('VIE-01', D_VIE, 'Un élève de test est inscrit dans une classe avec l’espace parent de sa mère ; son enseignant est identifié', async (ctx) => {
  const s = etat(ctx);
  try {
    const e = horizon(ctx);
    const sec = role(e, 'SECRETARIAT');
    // Une classe et une matière dont l'enseignant (non partagé) n'enseigne pas partout
    let choix = null;
    for (const c of e.classes) {
      for (const m of c.matieres.filter((y) => y.enseignant)) {
        const prof = e.enseignants.find((p) => p.telephone === m.enseignant && !p.partage);
        if (!prof) continue;
        const autre = e.classes.find((k) => k.id !== c.id && !k.matieres.some((y) => y.enseignant === m.enseignant)) ?? null;
        if (!choix || (autre && !choix.autre)) choix = { classe: c, matiere: m, prof, autre };
      }
    }
    if (!choix) ignorer('Aucune classe avec enseignant au lycée Horizon');
    const suf = lettres(suffixe());
    const telephone = telephoneUnique('7');
    const d = await exiger(sec, 'POST', '/eleves', {
      nom: 'RECETTE', prenoms: `Vie ${suf}`, sexe: 'M', dateNaissance: '2010-09-09',
      responsables: [{ nom: 'RECETTE', prenoms: `Mère ${suf}`, telephone, lien: 'MERE', responsableLegal: true, contactPrioritaire: true }],
    });
    const ins = await exiger(sec, 'POST', '/inscriptions', { eleveId: d.eleve.id, classeId: choix.classe.id });
    const ouverture = await exiger(sec, 'POST', `/responsables/${d.responsables[0].responsableId}/espace-parent`);
    const mere = await ctx.activer(telephone, ouverture.motDePasseTemporaire, e.id);
    s.eleve = {
      e, eleveId: d.eleve.id, inscriptionId: ins.id, matricule: ins.matricule, prenoms: `Vie ${suf}`, classeId: choix.classe.id,
      classeCode: choix.classe.code, matiereId: choix.matiere.id, autreClasse: choix.autre,
      prof: ctx.compte(choix.prof.telephone, e.id, `enseignant ${choix.prof.matiere}`), mere, telephone,
    };
    return `${ins.matricule} en ${choix.classe.code}, enseignant de ${choix.matiere.code}`;
  } catch (err) {
    if (err instanceof Echec) s.echec = err.message;
    throw err;
  }
});

// ===================================================================== appels

scenario('APP-01', D_APP, 'L’enseignant fait l’appel de sa classe : seul l’élève de test est absent, la famille est prévenue par SMS', async (ctx) => {
  const x = eleve(ctx);
  const { donnees, accuse } = await appelSurCreneauLibre(x.prof, x, ctx.aujourdhui, [{ inscriptionId: x.inscriptionId, type: 'ABSENCE' }]);
  egal([accuse.absents, accuse.retards], [1, 0], 'Un absent, aucun retard');
  x.appel = donnees;
  const vue = await exiger(x.prof, 'GET', `/appels/${donnees.idClient}`);
  egal(vue.marques.map((mq) => [mq.inscriptionId, mq.type]), [[x.inscriptionId, 'ABSENCE']], 'Marques de l’appel');
  const message = await sms(x.e, x, 'absence:');
  vrai(message, 'SMS d’absence planifié pour la famille');
  vrai(!message.destinataire.includes(x.telephone.slice(-6)), 'Le numéro du destinataire est masqué dans le suivi des SMS');
  return `${donnees.heureDebut}–${donnees.heureFin}`;
});

scenario('APP-02', D_APP, 'Un appel renvoyé par l’appareil n’est enregistré qu’une fois ; le même créneau depuis un autre appareil est refusé', async (ctx) => {
  const x = eleve(ctx);
  if (!x.appel) ignorer('Appel de test non enregistré (APP-01)');
  const renvoi = await envoyer(x.prof, x.appel);
  egal(renvoi.statut, 'DEJA_RECU', 'Renvoi identique');
  const doublon = await envoyer(x.prof, { ...x.appel, idClient: randomUUID() });
  egal([doublon.statut, doublon.code], ['REFUSE', 'CRENEAU_DEJA_FAIT'], 'Même créneau, autre appareil');
  const autreHeure = await envoyer(x.prof, { ...x.appel, heureDebut: '05:59', heureFin: '06:20' });
  egal([autreHeure.statut, autreHeure.code], ['REFUSE', 'IDENTIFIANT_DEJA_UTILISE'], 'Identifiant réutilisé pour un autre créneau');
});

scenario('APP-03', D_APP, 'Les appels incohérents sont refusés un par un dans le lot : date future, hors de l’année, classe ou matière étrangères, retard invalide', async (ctx) => {
  const x = eleve(ctx);
  const base = { classeId: x.classeId, matiereId: x.matiereId, date: ctx.aujourdhui, heureDebut: '05:40', heureFin: '05:55', marques: [] };
  const lot = [
    { ...base, idClient: randomUUID(), date: ajouterJours(ctx.aujourdhui, 3) },
    { ...base, idClient: randomUUID(), date: ajouterJours(ctx.etat.annee.rentree, -60) },
    { ...base, idClient: randomUUID(), matiereId: randomUUID() },
    { ...base, idClient: randomUUID(), marques: [{ inscriptionId: x.inscriptionId, type: 'RETARD', minutesRetard: 0 }] },
    { ...base, idClient: randomUUID(), heureFin: '05:00' },
  ];
  const attendus = ['DATE_FUTURE', 'HORS_ANNEE', 'MATIERE_HORS_CLASSE', 'DONNEES_INVALIDES', 'DONNEES_INVALIDES'];
  if (x.autreClasse) {
    lot.push({ ...base, idClient: randomUUID(), classeId: x.autreClasse.id, matiereId: null });
    attendus.push('NON_AFFECTE');
  }
  const accuses = await exiger(x.prof, 'POST', '/appels/lot', { appels: lot });
  egal(accuses.map((a) => a.statut), lot.map(() => 'REFUSE'), 'Tous refusés');
  egal(accuses.map((a) => a.code), attendus, 'Motifs de refus');
  statut(await appel(x.prof, 'POST', '/appels/lot', { appels: [] }), 400);
  statut(await appel(x.mere, 'POST', '/appels/lot', { appels: [{ ...base, idClient: randomUUID() }] }), 403);
  statut(await appel(role(x.e, 'SECRETARIAT'), 'POST', '/appels/lot', { appels: [{ ...base, idClient: randomUUID() }] }), 403);
});

scenario('APP-04', D_APP, 'L’enseignant corrige son appel du jour (absence devenue retard, puis absence) ; la vie scolaire corrige un appel passé, pas l’enseignant', async (ctx) => {
  const x = eleve(ctx);
  if (!x.appel) ignorer('Appel de test non enregistré (APP-01)');
  const retard = await exiger(x.prof, 'PUT', `/appels/${x.appel.idClient}`, { marques: [{ inscriptionId: x.inscriptionId, type: 'RETARD', minutesRetard: 15 }] });
  egal([retard.statut, retard.absents, retard.retards], ['MODIFIE', 0, 1], 'Absence changée en retard');
  const absence = await exiger(x.prof, 'PUT', `/appels/${x.appel.idClient}`, { marques: [{ inscriptionId: x.inscriptionId, type: 'ABSENCE' }] });
  egal([absence.statut, absence.absents], ['MODIFIE', 1], 'De nouveau absent');
  const hier = ajouterJours(ctx.aujourdhui, -1);
  if (hier < ctx.etat.annee.rentree) return 'pas de veille dans l’année : correction d’un appel passé non jouée';
  const passe = await appelSurCreneauLibre(x.prof, x, hier, []);
  x.appelHier = passe.donnees;
  // L'enseignant l'a fait aujourd'hui, mais il porte sur hier : la correction lui est fermée
  statut(await appel(x.prof, 'PUT', `/appels/${passe.donnees.idClient}`, { marques: [{ inscriptionId: x.inscriptionId, type: 'ABSENCE' }] }), 409, 'APPEL_CLOS');
  const surv = await exiger(role(x.e, 'SURVEILLANT'), 'PUT', `/appels/${passe.donnees.idClient}`, { marques: [{ inscriptionId: x.inscriptionId, type: 'ABSENCE' }] });
  egal([surv.statut, surv.absents], ['MODIFIE', 1], 'Corrigé par le surveillant');
});

scenario('APP-05', D_APP, 'Absences du jour, synthèse de la classe et absences par matière font apparaître l’élève ; l’enseignant n’a pas la liste du jour', async (ctx) => {
  const x = eleve(ctx);
  if (!x.appel) ignorer('Appel de test non enregistré (APP-01)');
  const surv = role(x.e, 'SURVEILLANT');
  const jour = await exiger(surv, 'GET', '/absences/jour');
  const ligne = jour.find((l) => l.inscriptionId === x.inscriptionId);
  vrai(ligne && ligne.absences >= 1 && ligne.classeCode === x.classeCode, 'Élève dans la liste du jour');
  egal(ligne.justifiee, false, 'Pas encore justifiée');
  const synthese = await exiger(x.prof, 'GET', `/classes/${x.classeId}/absences/synthese`, undefined, { params: { du: ctx.etat.annee.rentree, au: ctx.aujourdhui } });
  vrai((synthese.find((l) => l.inscriptionId === x.inscriptionId)?.absences ?? 0) >= 1, 'Absence dans la synthèse de la classe');
  const parMatiere = await exiger(role(x.e, 'CENSEUR'), 'GET', `/classes/${x.classeId}/absences/matieres`);
  vrai(parMatiere.some((l) => l.matiereId === x.matiereId && l.absences >= 1), 'Absence comptée dans la matière');
  const duJour = await exiger(surv, 'GET', `/classes/${x.classeId}/appels`, undefined, { params: { date: ctx.aujourdhui } });
  vrai(duJour.some((a) => a.id === x.appel.idClient), 'Appel de test parmi ceux du jour');
  statut(await appel(x.prof, 'GET', '/absences/jour'), 403);
  if (x.autreClasse) statut(await appel(x.prof, 'GET', `/classes/${x.autreClasse.id}/absences/synthese`), 403);
  refuse(await appel(ctx.ecole(1).session('SURVEILLANT'), 'GET', `/appels/${x.appel.idClient}`), 'Appel lu par un autre établissement');
  return `${jour.length} élève(s) absent(s) ou en retard aujourd’hui`;
});

scenario('APP-06', D_APP, 'La vie scolaire enregistre un justificatif : les absences couvertes deviennent justifiées, y compris pour la mère ; saisies invalides refusées', async (ctx) => {
  const x = eleve(ctx);
  if (!x.appel) ignorer('Appel de test non enregistré (APP-01)');
  const chemin = `/inscriptions/${x.inscriptionId}/justificatifs`;
  const du = x.appelHier ? x.appelHier.date : ctx.aujourdhui;
  statut(await appel(role(x.e, 'SURVEILLANT'), 'POST', chemin, { du: ctx.aujourdhui, au: du === ctx.aujourdhui ? ajouterJours(du, -1) : du, type: 'MALADIE' }), 400);
  statut(await appel(role(x.e, 'SURVEILLANT'), 'POST', chemin, { du: ajouterJours(ctx.aujourdhui, -400), au: ctx.aujourdhui, type: 'MALADIE' }), 400);
  statut(await appel(role(x.e, 'SURVEILLANT'), 'POST', chemin, { du, au: ctx.aujourdhui, type: 'GRIPPE' }), 400);
  statut(await appel(role(x.e, 'INTENDANT'), 'POST', chemin, { du, au: ctx.aujourdhui, type: 'MALADIE' }), 403);
  statut(await appel(x.prof, 'POST', chemin, { du, au: ctx.aujourdhui, type: 'MALADIE' }), 403);
  const j = await exiger(role(x.e, 'SURVEILLANT'), 'POST', chemin, { du, au: ctx.aujourdhui, type: 'MALADIE', motif: 'Recette : certificat médical' });
  x.justificatif = j.id;
  const absences = await exiger(role(x.e, 'INTENDANT'), 'GET', `/inscriptions/${x.inscriptionId}/absences`);
  vrai(absences.length >= 1 && absences.every((a) => a.justifiee), 'Toutes les absences couvertes sont justifiées');
  vrai((await exiger(role(x.e, 'SECRETARIAT'), 'GET', chemin)).some((y) => y.id === j.id), 'Justificatif listé');
  const vuesMere = await exiger(x.mere, 'GET', `/espace-parent/enfants/${x.eleveId}/absences`);
  vrai(vuesMere.length >= 1 && vuesMere.every((a) => a.justifiee), 'La mère voit les absences justifiées');
  return `${absences.length} absence(s) justifiée(s)`;
});

scenario('APP-07', D_APP, 'Le justificatif supprimé, les absences redeviennent à justifier ; une seconde suppression répond 404', async (ctx) => {
  const x = eleve(ctx);
  if (!x.justificatif) ignorer('Justificatif non créé (APP-06)');
  statut(await appel(role(x.e, 'INTENDANT'), 'DELETE', `/justificatifs/${x.justificatif}`), 403);
  statut(await appel(role(x.e, 'SURVEILLANT'), 'DELETE', `/justificatifs/${x.justificatif}`), 204);
  statut(await appel(role(x.e, 'SURVEILLANT'), 'DELETE', `/justificatifs/${x.justificatif}`), 404);
  const absences = await exiger(role(x.e, 'SURVEILLANT'), 'GET', `/inscriptions/${x.inscriptionId}/absences`);
  vrai(absences.some((a) => !a.justifiee), 'Absences de nouveau à justifier');
});

scenario('APP-08', D_APP, 'La mère suit les absences de son enfant seulement ; elle ne lit ni l’appel ni les absences d’un autre élève', async (ctx) => {
  const x = eleve(ctx);
  const siennes = await exiger(x.mere, 'GET', `/espace-parent/enfants/${x.eleveId}/absences`);
  vrai(siennes.every((a) => a.inscriptionId === x.inscriptionId), 'Seulement les absences de son enfant');
  const autre = x.e.familles.flatMap((f) => f.enfants).find((y) => y.ecole === x.e.n);
  if (autre) refuse(await appel(x.mere, 'GET', `/espace-parent/enfants/${autre.eleveId}/absences`), 'Absences d’un autre enfant');
  if (x.appel) statut(await appel(x.mere, 'GET', `/appels/${x.appel.idClient}`), 403);
  statut(await appel(x.mere, 'GET', `/inscriptions/${x.inscriptionId}/absences`), 403);
});

// ===================================================================== incidents

scenario('INC-01', D_INC, 'L’enseignant signale un retard dans sa classe et prévient la famille ; durée manquante, date future et sanction réservée refusées', async (ctx) => {
  const x = eleve(ctx);
  const chemin = `/inscriptions/${x.inscriptionId}/incidents`;
  statut(await appel(x.prof, 'POST', chemin, { type: 'RETARD', motif: 'Arrivé en retard' }), 400);
  statut(await appel(x.prof, 'POST', chemin, { type: 'AVERTISSEMENT', motif: 'Bavardages', minutesRetard: 10 }), 400);
  statut(await appel(x.prof, 'POST', chemin, { type: 'RETARD', minutesRetard: 10, motif: 'Futur', date: ajouterJours(ctx.aujourdhui, 2) }), 400);
  statut(await appel(x.prof, 'POST', chemin, { type: 'RETARD', minutesRetard: 10 }), 400);
  statut(await appel(x.prof, 'POST', chemin, { motif: 'Sans type' }), 400);
  statut(await appel(x.prof, 'POST', chemin, { type: 'BLAME', motif: 'Insolence' }), 403);
  statut(await appel(role(x.e, 'SECRETARIAT'), 'POST', chemin, { type: 'RETARD', minutesRetard: 5, motif: 'X' }), 403);
  const r = await exiger(x.prof, 'POST', chemin, { type: 'RETARD', minutesRetard: 20, motif: 'Recette : arrivé à 7 h 20', prevenirFamille: true });
  egal([r.type, r.minutesRetard, r.famillePrevenue, r.annule], ['RETARD', 20, true, false], 'Retard enregistré, famille prévenue');
  vrai(await sms(x.e, x, `incident:${r.id}`), 'SMS du retard planifié');
  x.retard = r.id;
});

scenario('INC-02', D_INC, 'Le surveillant donne un avertissement (famille prévenue d’office) ; le censeur prononce un blâme et une exclusion temporaire bornée', async (ctx) => {
  const x = eleve(ctx);
  const chemin = `/inscriptions/${x.inscriptionId}/incidents`;
  const av = await exiger(role(x.e, 'SURVEILLANT'), 'POST', chemin, { type: 'AVERTISSEMENT', motif: 'Recette : bavardages répétés' });
  egal(av.famillePrevenue, true, 'Famille prévenue par défaut pour une sanction');
  x.avertissement = av.id;
  statut(await appel(role(x.e, 'SURVEILLANT'), 'POST', chemin, { type: 'BLAME', motif: 'Insolence' }), 403);
  const bl = await exiger(role(x.e, 'CENSEUR'), 'POST', chemin, { type: 'BLAME', motif: 'Recette : insolence', prevenirFamille: false });
  egal(bl.famillePrevenue, false, 'SMS non demandé');
  x.blame = bl.id;
  const cens = role(x.e, 'CENSEUR');
  statut(await appel(cens, 'POST', chemin, { type: 'EXCLUSION_TEMPORAIRE', motif: 'Bagarre', joursExclusion: 0 }), 400);
  statut(await appel(cens, 'POST', chemin, { type: 'EXCLUSION_TEMPORAIRE', motif: 'Bagarre', joursExclusion: 31 }), 400);
  statut(await appel(cens, 'POST', chemin, { type: 'EXCLUSION_TEMPORAIRE', motif: 'Bagarre', joursExclusion: 2, debutExclusion: ajouterJours(ctx.aujourdhui, 45) }), 400);
  statut(await appel(cens, 'POST', chemin, { type: 'AVERTISSEMENT', motif: 'X', joursExclusion: 2 }), 400);
  const debut = ajouterJours(ctx.aujourdhui, 1);
  const ex = await appel(cens, 'POST', chemin, { type: 'EXCLUSION_TEMPORAIRE', motif: 'Recette : bagarre', joursExclusion: 3, debutExclusion: debut, prevenirFamille: false });
  if (ex.statut === 400 && ajouterJours(debut, 2) > ctx.etat.annee.fin) return 'fin d’année trop proche pour une exclusion de 3 jours';
  statut(ex, 201);
  egal([ex.donnees.debutExclusion, ex.donnees.finExclusion, ex.donnees.joursExclusion], [debut, ajouterJours(debut, 2), 3], 'Dates de l’exclusion');
  x.exclusion = ex.donnees.id;
});

scenario('INC-03', D_INC, 'Annulation : l’auteur annule son propre incident, la direction ceux des autres ; un surveillant ne peut pas annuler le blâme du censeur', async (ctx) => {
  const x = eleve(ctx);
  if (!x.retard || !x.blame) ignorer('Incidents non créés (INC-01, INC-02)');
  statut(await appel(x.prof, 'POST', `/incidents/${x.retard}/annulation`, { motif: '' }), 400);
  const annule = await exiger(x.prof, 'POST', `/incidents/${x.retard}/annulation`, { motif: 'Recette : saisi sur le mauvais élève' });
  egal([annule.annule, annule.motifAnnulation], [true, 'Recette : saisi sur le mauvais élève'], 'Retard annulé par son auteur');
  statut(await appel(x.prof, 'POST', `/incidents/${x.retard}/annulation`, { motif: 'Encore' }), 409, 'INCIDENT_DEJA_ANNULE');
  statut(await appel(role(x.e, 'SURVEILLANT'), 'POST', `/incidents/${x.blame}/annulation`, { motif: 'Recette' }), 403);
  egal((await exiger(role(x.e, 'CENSEUR'), 'POST', `/incidents/${x.blame}/annulation`, { motif: 'Recette : fin du test' })).annule, true, 'Blâme annulé par la direction');
  if (x.exclusion) await exiger(role(x.e, 'ADMIN_ECOLE'), 'POST', `/incidents/${x.exclusion}/annulation`, { motif: 'Recette : fin du test' });
  refuse(await appel(ctx.ecole(1).session('CENSEUR'), 'POST', `/incidents/${x.blame}/annulation`, { motif: 'X' }), 'Annulation par un autre établissement');
});

// ===================================================================== convocations

scenario('CNV-01', D_CNV, 'Le surveillant convoque la mère au sujet de l’avertissement ; date passée ou trop lointaine, incident annulé et enseignant refusés', async (ctx) => {
  const x = eleve(ctx);
  if (!x.avertissement) ignorer('Avertissement non créé (INC-02)');
  const chemin = `/inscriptions/${x.inscriptionId}/convocations`;
  const demain = `${ajouterJours(ctx.aujourdhui, 1)}T10:00`;
  statut(await appel(role(x.e, 'SURVEILLANT'), 'POST', chemin, { rendezVous: `${ajouterJours(ctx.aujourdhui, -1)}T10:00`, motif: 'Passé' }), 400);
  statut(await appel(role(x.e, 'SURVEILLANT'), 'POST', chemin, { rendezVous: `${ajouterJours(ctx.aujourdhui, 120)}T10:00`, motif: 'Lointain' }), 400);
  statut(await appel(role(x.e, 'SURVEILLANT'), 'POST', chemin, { rendezVous: demain, motif: '' }), 400);
  if (x.retard) statut(await appel(role(x.e, 'SURVEILLANT'), 'POST', chemin, { rendezVous: demain, motif: 'Retard', incidentId: x.retard }), 400);
  statut(await appel(x.prof, 'POST', chemin, { rendezVous: demain, motif: 'Comportement' }), 403);
  const c = await exiger(role(x.e, 'SURVEILLANT'), 'POST', chemin, { rendezVous: demain, motif: 'Recette : comportement en classe', incidentId: x.avertissement });
  egal([c.statut, c.incidentId, c.classe], ['PREVUE', x.avertissement, x.classeCode], 'Convocation prévue');
  vrai(await sms(x.e, x, `convocation:${c.id}`), 'SMS de convocation planifié');
  x.convocation = c.id;
});

scenario('CNV-02', D_CNV, 'Un incident convoqué ne s’annule pas ; un rendez-vous à venir ne se clôt que par une annulation, une seule fois ; l’agenda le retrouve', async (ctx) => {
  const x = eleve(ctx);
  if (!x.convocation) ignorer('Convocation non créée (CNV-01)');
  const cens = role(x.e, 'CENSEUR');
  statut(await appel(cens, 'POST', `/incidents/${x.avertissement}/annulation`, { motif: 'Recette' }), 409, 'CONVOCATION_EN_COURS');
  const agenda = await exiger(role(x.e, 'SECRETARIAT'), 'GET', '/convocations', undefined, { params: { du: ctx.aujourdhui, au: ajouterJours(ctx.aujourdhui, 7) } });
  vrai(agenda.some((c) => c.id === x.convocation), 'Convocation dans l’agenda de la semaine');
  statut(await appel(cens, 'GET', '/convocations', undefined, { params: { du: ctx.aujourdhui, au: ajouterJours(ctx.aujourdhui, -1) } }), 400);
  statut(await appel(cens, 'POST', `/convocations/${x.convocation}/cloture`, { statut: 'HONOREE', compteRendu: 'Trop tôt' }), 409, 'RENDEZ_VOUS_A_VENIR');
  statut(await appel(cens, 'POST', `/convocations/${x.convocation}/cloture`, {}), 400);
  const close = await exiger(role(x.e, 'SURVEILLANT'), 'POST', `/convocations/${x.convocation}/cloture`, { statut: 'ANNULEE', compteRendu: 'Recette : fin du test' });
  egal(close.statut, 'ANNULEE', 'Convocation annulée');
  statut(await appel(cens, 'POST', `/convocations/${x.convocation}/cloture`, { statut: 'ANNULEE' }), 409, 'CONVOCATION_CLOSE');
  egal((await exiger(cens, 'POST', `/incidents/${x.avertissement}/annulation`, { motif: 'Recette : fin du test' })).annule, true, 'Avertissement annulable après la convocation');
});

scenario('CNV-03', D_CNV, 'Historique de vie scolaire : synthèse de l’élève et de la classe à la direction ; la mère ne voit pas les incidents annulés', async (ctx) => {
  const x = eleve(ctx);
  const sec = role(x.e, 'SECRETARIAT');
  const h = await exiger(sec, 'GET', `/inscriptions/${x.inscriptionId}/vie-scolaire`);
  egal(h.inscriptionId, x.inscriptionId, 'Historique de l’inscription');
  vrai(h.incidents.length >= 2, 'Incidents (annulés compris) visibles du personnel');
  const parAnnee = await exiger(sec, 'GET', `/eleves/${x.eleveId}/vie-scolaire`);
  vrai(parAnnee.length >= 1, 'Historique par année');
  const classe = await exiger(role(x.e, 'CENSEUR'), 'GET', `/classes/${x.classeId}/vie-scolaire`, undefined, { params: { du: ctx.etat.annee.rentree, au: ctx.aujourdhui } });
  vrai(classe.some((l) => l.inscriptionId === x.inscriptionId), 'Élève dans la synthèse de la classe');
  const mere = await exiger(x.mere, 'GET', `/espace-parent/enfants/${x.eleveId}/vie-scolaire`);
  const incidentsMere = mere.flatMap((y) => y.incidents);
  vrai(incidentsMere.every((i) => !i.annule), 'La mère ne voit aucun incident annulé');
  statut(await appel(role(x.e, 'INTENDANT'), 'GET', `/inscriptions/${x.inscriptionId}/vie-scolaire`), 403);
  statut(await appel(x.prof, 'GET', `/inscriptions/${x.inscriptionId}/vie-scolaire`), 403);
  const autre = x.e.familles.flatMap((f) => f.enfants).find((y) => y.ecole === x.e.n);
  if (autre) refuse(await appel(x.mere, 'GET', `/espace-parent/enfants/${autre.eleveId}/vie-scolaire`), 'Vie scolaire d’un autre enfant');
  return `${h.incidents.length} incident(s) au total, ${incidentsMere.length} visible(s) par la mère`;
});

// ===================================================================== fin

scenario('VIE-02', D_VIE, 'Fin du lot : l’élève de test quitte l’établissement ; plus aucun incident ni convocation ne peut le concerner', async (ctx) => {
  const x = eleve(ctx);
  const s = await exiger(role(x.e, 'SECRETARIAT'), 'POST', `/inscriptions/${x.inscriptionId}/sortie`, { statut: 'ABANDON', date: ctx.aujourdhui, motif: 'Recette : fin du test' });
  egal(s.statut, 'ABANDON', 'Élève sorti');
  statut(await appel(role(x.e, 'SURVEILLANT'), 'POST', `/inscriptions/${x.inscriptionId}/incidents`, { type: 'AVERTISSEMENT', motif: 'Après départ' }), 409, 'INSCRIPTION_TERMINEE');
  statut(await appel(role(x.e, 'SURVEILLANT'), 'POST', `/inscriptions/${x.inscriptionId}/convocations`, {
    rendezVous: `${ajouterJours(ctx.aujourdhui, 1)}T09:00`, motif: 'Après départ',
  }), 409, 'INSCRIPTION_TERMINEE');
});
