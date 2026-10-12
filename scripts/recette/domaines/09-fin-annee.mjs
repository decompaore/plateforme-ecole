/**
 * Lot 09 : une année scolaire complète, jusqu'au passage dans l'année suivante — joué en dernier, sur le
 * « collège de recette » créé par le lot 08 (ou créé ici si le lot 08 n'a pas tourné), pour ne jamais clôturer
 * l'année des établissements de démonstration.
 *
 * Préfixe : FIN-.
 *
 * Déroulé : profils, filière, matières, classes de 6e et de 5e, trimestres ; ouverture ; quatre élèves en 6e ;
 * notes des trois trimestres ; verrouillage ; bulletins du 1er trimestre ; décisions du conseil (admis, exclu
 * sous le seuil de 8,50, redoublants, une décision modifiée avec motif) ; validation ; clôture ; année suivante par
 * copie ; réinscriptions d'après les décisions ; ouverture de la nouvelle année et archivage de l'ancienne ;
 * résiliation du collège.
 *
 * Sources vérifiées : etablissement/AnneesController/Service, AnneeScolaire (TRANSITION_INVALIDE), PeriodesController/
 * Service, ClassesController, FilieresEtMatieresController ; pedagogie/ProfilsController (initialisation) ;
 * evaluations/EvaluationsController/Service, ContexteClasse, CalculNotes ; bulletins/BulletinsController/Service ;
 * passage/PassageController, DecisionsService, DecisionFinAnnee, MoteurPassage, ParametresPassageService (8,50 et 2
 * par défaut), PassageService ; eleves/InscriptionsService (réinscriptions) ; plateforme/EtablissementsService.
 */
import {
  ajouterJours, appel, appelBrut, Echec, egal, exiger, ignorer, scenario, statut, suffixe, telephoneUnique, vrai,
} from '../lib.mjs';

const D_FIN = 'Année complète et passage';

/** Notes identiques à chaque trimestre : [mathématiques (coef. 2), français (coef. 1)] et moyenne attendue. */
const ELEVES = [
  { prenoms: 'Admis', sexe: 'F', notes: [15, 14], moyenne: 14.67, proposition: 'ADMIS' },
  { prenoms: 'Exclu', sexe: 'M', notes: [6, 7], moyenne: 6.33, proposition: 'EXCLU' },
  { prenoms: 'Rachat', sexe: 'M', notes: [9, 9.5], moyenne: 9.17, proposition: 'REDOUBLE' },
  { prenoms: 'Redoublant', sexe: 'F', notes: [9, 9], moyenne: 9, proposition: 'REDOUBLE' },
];

// ===================================================================== aides

function etat(ctx) {
  ctx.partage.finAnnee ??= {};
  return ctx.partage.finAnnee;
}

/** Le collège de recette du lot 08, ou un nouveau si le lot 08 n'a pas tourné. */
async function college(ctx) {
  if (ctx.partage.ecoleRecette?.admin) return ctx.partage.ecoleRecette;
  const suf = suffixe();
  const telephone = telephoneUnique('7');
  const r = await exiger(ctx.sa, 'POST', '/plateforme/etablissements', {
    code: `recette-${suf}`, nom: `Collège de recette ${suf.toUpperCase()}`, telephoneAdministrateur: telephone,
    nomAdministrateur: 'Recette', prenomsAdministrateur: 'Directeur',
  });
  const admin = await ctx.activer(telephone, r.motDePasseTemporaire, r.etablissement.id);
  ctx.partage.ecoleRecette = { id: r.etablissement.id, code: r.etablissement.code, telephone, admin, utilisateurId: r.administrateur.utilisateurId };
  return ctx.partage.ecoleRecette;
}

/** Ce que FIN-01 a installé (sinon le scénario est ignoré). */
function annee(ctx) {
  const a = etat(ctx).a;
  if (!a) ignorer(`Année du collège non installée (FIN-01)${etat(ctx).echec ? ` : ${etat(ctx).echec}` : ''}`);
  return a;
}

function libelles(ctx) {
  const m = /^(\d{4})-(\d{4})$/.exec(ctx.etat.annee.libelle);
  const debut = m ? Number(m[1]) : new Date().getUTCFullYear();
  return { a: `${debut - 1}-${debut}`, b: `${debut}-${debut + 1}` };
}

function proche(obtenu, attendu, message) {
  if (obtenu === null || obtenu === undefined || Math.abs(Number(obtenu) - attendu) > 0.005) {
    throw new Echec(`${message} : attendu ${attendu}, obtenu ${obtenu}`);
  }
}

// ===================================================================== scénarios

scenario('FIN-01', D_FIN, 'Le collège prépare l’année écoulée : profils, filière, matières, 6e et 5e, trimestres ; l’ouverture exige classes et périodes', async (ctx) => {
  const s = etat(ctx);
  try {
    const c = await college(ctx);
    const adm = c.admin;
    const profils = await exiger(adm, 'POST', '/profils/initialisation');
    const general = profils.find((p) => p.code === 'GENERAL');
    if (!general) throw new Echec('Profil GENERAL absent après l’initialisation');
    const { a: libA, b: libB } = libelles(ctx);
    statut(await appel(adm, 'POST', '/annees', { libelle: '2025-2027', debut: ajouterJours(ctx.aujourdhui, -330), fin: ajouterJours(ctx.aujourdhui, -1) }), 400);
    statut(await appel(adm, 'POST', '/annees', { libelle: libA, debut: ajouterJours(ctx.aujourdhui, -1), fin: ajouterJours(ctx.aujourdhui, -330) }), 400);
    const anneeA = await exiger(adm, 'POST', '/annees', { libelle: libA, debut: ajouterJours(ctx.aujourdhui, -330), fin: ajouterJours(ctx.aujourdhui, -1) });
    egal(anneeA.etat, 'PREPARATION', 'Année en préparation');
    statut(await appel(adm, 'POST', '/annees', { libelle: libA, debut: anneeA.debut, fin: anneeA.fin }), 409, 'ANNEE_EXISTANTE');
    statut(await appel(adm, 'POST', `/annees/${anneeA.id}/ouverture`), 409, 'AUCUNE_CLASSE');
    const filiere = await exiger(adm, 'POST', '/filieres', { code: 'COLL', libelle: 'Enseignement général', cycle: 'Premier cycle', diplomeVise: 'BEPC', profilId: general.id });
    const math = await exiger(adm, 'POST', '/matieres', { code: 'MATH', libelle: 'Mathématiques', type: 'GENERALE' });
    const fr = await exiger(adm, 'POST', '/matieres', { code: 'FR', libelle: 'Français', type: 'GENERALE' });
    const classes = {};
    for (const [code, niveau] of [['6e A', '6e'], ['5e A', '5e']]) {
      const cl = await exiger(adm, 'POST', `/annees/${anneeA.id}/classes`, { filiereId: filiere.id, code, niveau, effectifMax: 40 });
      for (const [m, coef] of [[math, 2], [fr, 1]]) {
        await exiger(adm, 'PUT', `/classes/${cl.id}/matieres/${m.id}`, { coefficient: coef, groupe: 'Enseignement général', volumeHebdo: 4, volumeTotal: 120 });
      }
      classes[niveau] = cl;
    }
    statut(await appel(adm, 'POST', `/annees/${anneeA.id}/ouverture`), 409, 'PERIODES_MANQUANTES');
    const periodes = await exiger(adm, 'POST', `/annees/${anneeA.id}/periodes/generation`, { profilId: general.id });
    egal(periodes.length, 3, 'Trois trimestres');
    statut(await appel(adm, 'POST', `/annees/${anneeA.id}/periodes/generation`, { profilId: general.id }), 409, 'PERIODES_EXISTANTES');
    egal((await exiger(adm, 'POST', `/annees/${anneeA.id}/ouverture`)).etat, 'ACTIVE', 'Année ouverte');
    statut(await appel(adm, 'POST', `/annees/${anneeA.id}/ouverture`), 409);
    s.a = { c, adm, profil: general, annee: anneeA, libB, classes, matieres: { math, fr }, periodes: periodes.sort((x, y) => x.ordre - y.ordre), eleves: [] };
    return `${libA} : ${periodes.map((p) => `${p.libelle} ${p.debut}→${p.fin}`).join(' ; ')}`;
  } catch (err) {
    if (err instanceof Echec) s.echec = err.message;
    throw err;
  }
});

scenario('FIN-02', D_FIN, 'Quatre élèves sont inscrits en 6e avec un responsable chacun', async (ctx) => {
  const a = annee(ctx);
  const suf = suffixe().replace(/[0-9]/g, (d) => 'ABCDEFGHIJ'[Number(d)]);
  for (const x of ELEVES) {
    const d = await exiger(a.adm, 'POST', '/eleves', {
      nom: 'RECETTE', prenoms: `${x.prenoms} ${suf}`, sexe: x.sexe, dateNaissance: '2014-01-15',
      responsables: [{ nom: 'RECETTE', prenoms: `Parent ${x.prenoms}`, telephone: telephoneUnique('7'), lien: 'TUTEUR', responsableLegal: true, contactPrioritaire: true }],
    });
    const ins = await exiger(a.adm, 'POST', '/inscriptions', { eleveId: d.eleve.id, classeId: a.classes['6e'].id });
    a.eleves.push({ ...x, eleveId: d.eleve.id, inscriptionId: ins.id });
  }
  egal((await exiger(a.adm, 'GET', `/classes/${a.classes['6e'].id}/inscriptions`)).length, 4, 'Effectif de la 6e');
});

scenario('FIN-03', D_FIN, 'Les notes des trois trimestres sont saisies ; moyennes et rangs du 1er trimestre sont justes (coefficients 2 et 1)', async (ctx) => {
  const a = annee(ctx);
  if (a.eleves.length !== 4) ignorer('Élèves non inscrits (FIN-02)');
  const classe = a.classes['6e'];
  for (const p of a.periodes) {
    for (const [k, m] of [[0, a.matieres.math], [1, a.matieres.fr]]) {
      const ev = await exiger(a.adm, 'POST', `/classes/${classe.id}/evaluations`, {
        matiereId: m.id, periodeId: p.id, libelle: `Composition ${p.libelle} ${m.code}`, type: 'COMPOSITION', date: p.debut,
      });
      await exiger(a.adm, 'PUT', `/evaluations/${ev.id}/notes`, { notes: a.eleves.map((x) => ({ inscriptionId: x.inscriptionId, valeur: x.notes[k] })) });
    }
  }
  const r = await exiger(a.adm, 'GET', `/classes/${classe.id}/resultats`, undefined, { params: { periodeId: a.periodes[0].id } });
  for (const x of a.eleves) {
    const ligne = r.eleves.find((y) => y.inscriptionId === x.inscriptionId);
    proche(ligne?.moyenne, x.moyenne, `Moyenne du 1er trimestre de ${x.prenoms}`);
  }
  egal(r.eleves.find((y) => y.inscriptionId === a.eleves[0].inscriptionId).rang, 1, 'Premier de la classe');
  egal(r.eleves.find((y) => y.inscriptionId === a.eleves[1].inscriptionId).rang, 4, 'Dernier de la classe');
  return `moyenne de classe ${r.moyenneClasse}`;
});

scenario('FIN-04', D_FIN, 'Pas de décisions tant qu’un trimestre est ouvert ; une période verrouillée refuse les saisies et se déverrouille si besoin', async (ctx) => {
  const a = annee(ctx);
  const classe = a.classes['6e'];
  statut(await appel(a.adm, 'POST', `/classes/${classe.id}/decisions`), 409, 'PERIODES_NON_VERROUILLEES');
  statut(await appel(a.adm, 'POST', `/classes/${classe.id}/periodes/${a.periodes[0].id}/bulletins`), 409, 'PERIODE_NON_VERROUILLEE');
  for (const p of a.periodes) await exiger(a.adm, 'POST', `/periodes/${p.id}/verrouillage`);
  statut(await appel(a.adm, 'POST', `/classes/${classe.id}/evaluations`, {
    matiereId: a.matieres.math.id, periodeId: a.periodes[2].id, libelle: 'Trop tard', type: 'DEVOIR', date: a.periodes[2].debut,
  }), 409, 'PERIODE_VERROUILLEE');
  const dernier = a.periodes[2];
  await exiger(a.adm, 'POST', `/periodes/${dernier.id}/deverrouillage`);
  statut(await appel(a.adm, 'POST', `/classes/${classe.id}/decisions`), 409, 'PERIODES_NON_VERROUILLEES');
  await exiger(a.adm, 'POST', `/periodes/${dernier.id}/verrouillage`);
  const liste = await exiger(a.adm, 'GET', `/annees/${a.annee.id}/periodes`);
  vrai(liste.every((p) => p.verrouillee), 'Tous les trimestres verrouillés');
});

scenario('FIN-05', D_FIN, 'Les bulletins du 1er trimestre se génèrent avec rangs et codes de vérification, se publient une seule fois et se vérifient sans connexion', async (ctx) => {
  const a = annee(ctx);
  const classe = a.classes['6e'];
  const base = `/classes/${classe.id}/periodes/${a.periodes[0].id}/bulletins`;
  const g = await exiger(a.adm, 'POST', base);
  egal([g.statut, g.effectif, g.bulletins.length], ['GENEREE', 4, 4], 'Bulletins générés');
  const premier = g.bulletins.find((b) => b.inscriptionId === a.eleves[0].inscriptionId);
  egal(premier.rang, 1, 'Rang sur le bulletin');
  const regen = await exiger(a.adm, 'POST', base);
  egal(regen.bulletins.length, 4, 'Régénération possible avant publication');
  const pub = await exiger(a.adm, 'POST', `${base}/publication`);
  egal(pub.statut, 'PUBLIEE', 'Bulletins publiés');
  statut(await appel(a.adm, 'POST', `${base}/publication`), 409, 'BULLETINS_PUBLIES');
  statut(await appel(a.adm, 'POST', base), 409, 'BULLETINS_PUBLIES');
  const code = pub.bulletins.find((b) => b.inscriptionId === a.eleves[0].inscriptionId).codeVerification;
  const v = await appelBrut('GET', `/verification/bulletins/${encodeURIComponent(code)}`);
  statut(v, 200);
  proche(v.donnees.moyenne, a.eleves[0].moyenne, 'Moyenne vérifiée publiquement');
  a.codeBulletin = code;
});

scenario('FIN-06', D_FIN, 'Propositions de fin d’année : admis au-dessus de 10, exclu sous le seuil d’exclusion (8,50 par défaut), redoublants entre les deux', async (ctx) => {
  const a = annee(ctx);
  const regles = await exiger(a.adm, 'GET', '/parametres/passage');
  statut(await appel(a.adm, 'PUT', '/parametres/passage', { seuilExclusion: 25, redoublementsMax: 2 }), 400);
  statut(await appel(a.adm, 'PUT', '/parametres/passage', { seuilExclusion: 8.5, redoublementsMax: 9 }), 400);
  if (Number(regles.seuilExclusion) !== 8.5) await exiger(a.adm, 'PUT', '/parametres/passage', { seuilExclusion: 8.5, redoublementsMax: 2 });
  const d = await exiger(a.adm, 'POST', `/classes/${a.classes['6e'].id}/decisions`);
  egal(d.validees, false, 'Pas encore validées');
  for (const x of a.eleves) {
    const v = d.eleves.find((y) => y.inscriptionId === x.inscriptionId);
    vrai(v, `${x.prenoms} absent des décisions`);
    proche(v.moyenneAnnuelle, x.moyenne, `Moyenne annuelle de ${x.prenoms}`);
    egal(v.proposition, x.proposition, `Proposition pour ${x.prenoms}`);
    egal(v.decision, x.proposition, `Décision par défaut de ${x.prenoms}`);
  }
  egal(d.eleves.find((y) => y.inscriptionId === a.eleves[0].inscriptionId).rang, 1, 'Rang annuel');
  return Object.entries(d.repartition).map(([k, n]) => `${k} ${n}`).join(', ');
});

scenario('FIN-07', D_FIN, 'Le conseil rachète un élève avec un motif obligatoire ; « en attente d’examen » et un résultat d’examen sont refusés hors classe d’examen', async (ctx) => {
  const a = annee(ctx);
  const chemin = `/classes/${a.classes['6e'].id}/decisions`;
  const rachat = a.eleves[2];
  statut(await appel(a.adm, 'PUT', chemin, { decisions: [{ inscriptionId: rachat.inscriptionId, decision: 'ADMIS' }] }), 400);
  statut(await appel(a.adm, 'PUT', chemin, { decisions: [{ inscriptionId: rachat.inscriptionId, decision: 'EN_ATTENTE_EXAMEN', motif: 'X' }] }), 400);
  statut(await appel(a.adm, 'PUT', chemin, { decisions: [{ inscriptionId: rachat.inscriptionId, resultatExamen: 'ADMIS' }] }), 409, 'CLASSE_SANS_EXAMEN');
  statut(await appel(a.adm, 'PUT', chemin, { decisions: [{ inscriptionId: a.eleves[0].inscriptionId, decision: 'ADMIS' }, { inscriptionId: a.eleves[0].inscriptionId, decision: 'ADMIS' }] }), 400);
  statut(await appel(a.adm, 'PUT', chemin, { decisions: [{ inscriptionId: ctx.ecole(1).familles[0]?.enfants[0]?.inscriptionId ?? a.annee.id, decision: 'ADMIS', motif: 'X' }] }), 400);
  const d = await exiger(a.adm, 'PUT', chemin, { decisions: [{ inscriptionId: rachat.inscriptionId, decision: 'ADMIS', motif: 'Recette : rachat au conseil, 9,17 et bon comportement' }] });
  const v = d.eleves.find((y) => y.inscriptionId === rachat.inscriptionId);
  egal([v.proposition, v.decision], ['REDOUBLE', 'ADMIS'], 'Décision modifiée, proposition conservée');
  vrai(v.motif?.includes('rachat'), 'Motif enregistré');
  rachat.decision = 'ADMIS';
});

scenario('FIN-08', D_FIN, 'Validation : décisions définitives (ni recalcul ni modification) ; le tableau de passage le montre ; la réinscription attend la validation', async (ctx) => {
  const a = annee(ctx);
  statut(await appel(a.adm, 'POST', `/classes/${a.classes['5e'].id}/decisions/reinscriptions`, { classeAdmisId: a.classes['5e'].id }), 409, 'DECISIONS_NON_VALIDEES');
  statut(await appel(a.adm, 'POST', `/classes/${a.classes['5e'].id}/decisions/validation`), 409, 'DECISIONS_NON_CALCULEES');
  const d = await exiger(a.adm, 'POST', `/classes/${a.classes['6e'].id}/decisions/validation`);
  egal(d.validees, true, 'Décisions validées');
  vrai(d.eleves.every((y) => y.validee), 'Chaque décision validée');
  statut(await appel(a.adm, 'POST', `/classes/${a.classes['6e'].id}/decisions/validation`), 409, 'DECISIONS_VALIDEES');
  statut(await appel(a.adm, 'POST', `/classes/${a.classes['6e'].id}/decisions`), 409, 'DECISIONS_VALIDEES');
  statut(await appel(a.adm, 'PUT', `/classes/${a.classes['6e'].id}/decisions`, { decisions: [{ inscriptionId: a.eleves[3].inscriptionId, decision: 'ADMIS', motif: 'Trop tard' }] }), 409, 'DECISIONS_VALIDEES');
  const t = await exiger(a.adm, 'GET', `/annees/${a.annee.id}/passage`);
  egal([t.classes, t.classesValidees], [2, 1], 'Tableau de passage');
  egal(t.detail.find((x) => x.classeId === a.classes['6e'].id)?.repartition, { ADMIS: 2, EXCLU: 1, REDOUBLE: 1 }, 'Répartition validée');
});

scenario('FIN-09', D_FIN, 'Clôture de l’année : plus aucune évaluation ni inscription ; une seconde clôture est refusée', async (ctx) => {
  const a = annee(ctx);
  egal((await exiger(a.adm, 'POST', `/annees/${a.annee.id}/cloture`)).etat, 'CLOTUREE', 'Année clôturée');
  statut(await appel(a.adm, 'POST', `/annees/${a.annee.id}/cloture`), 409, 'TRANSITION_INVALIDE');
  statut(await appel(a.adm, 'POST', `/classes/${a.classes['6e'].id}/evaluations`, {
    matiereId: a.matieres.math.id, periodeId: a.periodes[0].id, libelle: 'Après clôture', type: 'DEVOIR', date: a.periodes[0].debut,
  }), 409, 'ANNEE_NON_ACTIVE');
  const d = await exiger(a.adm, 'POST', '/eleves', { nom: 'RECETTE', prenoms: 'Retardataire', sexe: 'M', dateNaissance: '2014-02-02' });
  statut(await appel(a.adm, 'POST', '/inscriptions', { eleveId: d.eleve.id, classeId: a.classes['6e'].id }), 409, 'ANNEE_FIGEE');
  statut(await appel(a.adm, 'POST', `/annees/${a.annee.id}/classes`, { filiereId: a.classes['6e'].filiereId, code: '4e A', niveau: '4e' }), 409, 'ANNEE_FIGEE');
  statut(await appel(a.adm, 'GET', '/annees/active'), 404);
});

scenario('FIN-10', D_FIN, 'L’année suivante se crée par copie (classes, programmes, périodes) ; elle doit commencer après la fin de l’année écoulée', async (ctx) => {
  const a = annee(ctx);
  const chemin = `/annees/${a.annee.id}/annee-suivante`;
  statut(await appel(a.adm, 'POST', chemin, { libelle: a.libB, debut: a.annee.fin, fin: ajouterJours(ctx.aujourdhui, 300) }), 400);
  statut(await appel(a.adm, 'POST', chemin, { libelle: a.libB }), 400);
  const s = await exiger(a.adm, 'POST', chemin, { libelle: a.libB, debut: ctx.aujourdhui, fin: ajouterJours(ctx.aujourdhui, 300) });
  egal([s.annee.etat, s.classesCopiees, s.matieresCopiees], ['PREPARATION', 2, 4], 'Structure copiée');
  const classesB = await exiger(a.adm, 'GET', `/annees/${s.annee.id}/classes`);
  a.b = {
    annee: s.annee,
    classes: { '6e': classesB.find((x) => x.code === '6e A'), '5e': classesB.find((x) => x.code === '5e A') },
  };
  vrai(a.b.classes['6e'] && a.b.classes['5e'], 'Classes 6e A et 5e A dans la nouvelle année');
  const matieres = await exiger(a.adm, 'GET', `/classes/${a.b.classes['6e'].id}/matieres`);
  egal(matieres.map((m) => [m.matiereCode, Number(m.coefficient)]).sort(), [['FR', 1], ['MATH', 2]], 'Programme et coefficients copiés');
  if (s.periodesCopiees === 0) await exiger(a.adm, 'POST', `/annees/${s.annee.id}/periodes/generation`, { profilId: a.profil.id });
  return `${s.annee.libelle} : ${s.classesCopiees} classes, ${s.periodesCopiees} périodes copiées`;
});

scenario('FIN-11', D_FIN, 'Réinscriptions d’après les décisions : les deux admis en 5e, le redoublant en 6e (marqué redoublant), l’exclu n’est pas réinscrit ; un second envoi ne double rien', async (ctx) => {
  const a = annee(ctx);
  if (!a.b) ignorer('Année suivante non créée (FIN-10)');
  const chemin = `/classes/${a.classes['6e'].id}/decisions/reinscriptions`;
  statut(await appel(a.adm, 'POST', chemin, {}), 400);
  const r = await exiger(a.adm, 'POST', chemin, { classeAdmisId: a.b.classes['5e'].id, classeRedoublantsId: a.b.classes['6e'].id });
  egal([r.admis?.reinscrits, r.redoublants?.reinscrits, r.nonReinscrits], [2, 1, 1], 'Admis, redoublants, non concernés');
  const en5e = await exiger(a.adm, 'GET', `/classes/${a.b.classes['5e'].id}/inscriptions`);
  const en6e = await exiger(a.adm, 'GET', `/classes/${a.b.classes['6e'].id}/inscriptions`);
  egal(en5e.map((i) => i.eleveId).sort(), [a.eleves[0].eleveId, a.eleves[2].eleveId].sort(), 'Admis en 5e A');
  vrai(en5e.every((i) => !i.redoublant), 'Admis non redoublants');
  egal(en6e.map((i) => [i.eleveId, i.redoublant]), [[a.eleves[3].eleveId, true]], 'Redoublant en 6e A');
  const encore = await exiger(a.adm, 'POST', chemin, { classeAdmisId: a.b.classes['5e'].id, classeRedoublantsId: a.b.classes['6e'].id });
  egal([encore.admis?.reinscrits ?? 0, encore.redoublants?.reinscrits ?? 0], [0, 0], 'Second envoi sans effet');
  vrai((encore.admis?.nonReinscrits ?? []).every((n) => n.motif?.startsWith('Déjà inscrit')), 'Motif « déjà inscrit »');
});

scenario('FIN-12', D_FIN, 'La nouvelle année s’ouvre, l’ancienne s’archive ; les statistiques de la nouvelle année comptent les trois élèves réinscrits', async (ctx) => {
  const a = annee(ctx);
  if (!a.b) ignorer('Année suivante non créée (FIN-10)');
  egal((await exiger(a.adm, 'POST', `/annees/${a.b.annee.id}/ouverture`)).etat, 'ACTIVE', 'Nouvelle année ouverte');
  egal((await exiger(a.adm, 'GET', '/annees/active')).id, a.b.annee.id, 'Année active');
  egal((await exiger(a.adm, 'POST', `/annees/${a.annee.id}/archivage`)).etat, 'ARCHIVEE', 'Ancienne année archivée');
  statut(await appel(a.adm, 'POST', `/annees/${a.annee.id}/archivage`), 409, 'TRANSITION_INVALIDE');
  const st = await exiger(a.adm, 'GET', `/annees/${a.b.annee.id}/statistiques`);
  egal(st.effectifTotal.garcons + st.effectifTotal.filles, 3, 'Effectif de la nouvelle année');
  // L'historique reste consultable : les décisions de l'année écoulée
  const d = await exiger(a.adm, 'GET', `/classes/${a.classes['6e'].id}/decisions`);
  egal(d.validees, true, 'Décisions de l’année archivée toujours consultables');
});

scenario('FIN-13', D_FIN, 'Fin de la recette : le collège est résilié ; son administrateur ne se connecte plus, ses bulletins ne se vérifient plus et il sort du pilotage', async (ctx) => {
  const c = ctx.partage.ecoleRecette;
  if (!c) ignorer('Collège de recette absent');
  const t = ctx.partage.territoire;
  if (t?.adminPaysBF) statut(await appel(t.adminPaysBF, 'PATCH', `/plateforme/etablissements/${c.id}/statut`, { statut: 'RESILIE' }), 403);
  egal((await exiger(ctx.sa, 'PATCH', `/plateforme/etablissements/${c.id}/statut`, { statut: 'RESILIE' })).statut, 'RESILIE', 'Collège résilié');
  statut(await appelBrut('POST', '/auth/connexion', { telephone: c.telephone, motDePasse: ctx.motDePasse }), 401);
  const code = etat(ctx).a?.codeBulletin;
  if (code) statut(await appelBrut('GET', `/verification/bulletins/${encodeURIComponent(code)}`), 404);
  const liste = await exiger(ctx.sa, 'GET', '/plateforme/etablissements');
  egal(liste.find((x) => x.id === c.id)?.statut, 'RESILIE', 'Statut dans la liste');
});
