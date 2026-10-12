/**
 * Lot 03 : structure pédagogique, élèves et inscriptions, enseignants.
 *
 * Préfixes : STR- (année, classes, matières), ELV- (dossiers, responsables, inscriptions, espace parent, import),
 * ENS- (engagements, invitations, affectations, catégorie générale ou technique, fin d'engagement).
 *
 * Sources vérifiées : etablissement/AnneesController, ClassesController/Service, FilieresEtMatieresController,
 * MatieresService, Vues ; eleves/ElevesController/Service, RegistreEleves, InscriptionsController/Service,
 * RegistreInscriptions, Inscription, EspaceParentController/Service, ImportElevesController/Service, Roles, Vues ;
 * enseignants/EnseignantsController/Service, Engagement, EspaceEnseignantController/Service, Vues ;
 * ateliers/AteliersController (GET /ateliers/acces, v0.40) ; socle/persistance/PageResultat.
 *
 * Données : tout se passe au lycée Horizon (n° 3) sauf l'invitation (un titulaire du lycée technique n° 1) et
 * l'onglet Ateliers (n° 1). La recette crée à chaque passage deux élèves (sortis à la fin), un enseignant
 * (dont l'engagement se termine à la fin), une matière REC-… (retirée de la classe puis désactivée) et des
 * invitations refusées ou annulées. Les comptes de démonstration ne sont pas modifiés.
 */
import {
  appel, appelBrut, Echec, egal, exiger, fichierPng, ignorer, refuse, scenario, statut, suffixe, telephoneUnique, vrai,
  ajouterJours,
} from '../lib.mjs';

const D_STR = 'Structure pédagogique';
const D_ELV = 'Élèves et inscriptions';
const D_ENS = 'Enseignants';

// ===================================================================== aides

function partage(ctx) {
  ctx.partage.eleves ??= {};
  return ctx.partage.eleves;
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

/** Une classe de l'établissement qui a au moins une matière avec un enseignant (la première trouvée). */
function classeAvecEnseignant(e) {
  const c = e.classes.find((x) => x.matieres.some((m) => m.enseignant));
  if (!c) ignorer(`Aucune classe avec enseignant au ${e.nom}`);
  return c;
}

/** Session d'un enseignant qui enseigne dans la classe, et une classe où il n'enseigne pas. */
function enseignantEtAutreClasse(e) {
  for (const c of e.classes) {
    for (const m of c.matieres.filter((x) => x.enseignant)) {
      const autre = e.classes.find((x) => x.id !== c.id && !x.matieres.some((y) => y.enseignant === m.enseignant));
      const prof = e.enseignants.find((p) => p.telephone === m.enseignant);
      if (autre && prof && !prof.partage) {
        return { session: ctx0(e).compte(prof.telephone, e.id, `enseignant ${prof.matiere} ${e.code}`), classe: c, autre };
      }
    }
  }
  return null;
}

function ctx0(e) {
  return e.ctx;
}

function lettres(texte) {
  return texte.replace(/[0-9]/g, (d) => 'ABCDEFGHIJ'[Number(d)]);
}

function dossierEleve(prenoms, telephoneResponsable, extra = {}) {
  return {
    nom: 'RECETTE',
    prenoms,
    sexe: 'F',
    dateNaissance: '2011-05-20',
    lieuNaissance: 'Bobo-Dioulasso',
    responsables: [{
      nom: 'RECETTE', prenoms: `Mère ${prenoms}`, telephone: telephoneResponsable, lien: 'MERE',
      responsableLegal: true, contactPrioritaire: true,
    }],
    ...extra,
  };
}

/** Élève A créé par ELV-01 (ignore le scénario s'il manque). */
function eleveA(ctx) {
  const A = partage(ctx).A;
  if (!A) ignorer('Élève de test non créé (ELV-01)');
  return A;
}

function inscriptionA(ctx) {
  const A = eleveA(ctx);
  if (!A.inscriptionId) ignorer('Élève de test non inscrit (ELV-07)');
  return A;
}

function enseignantsP(ctx) {
  ctx.partage.enseignants ??= {};
  return ctx.partage.enseignants;
}

function nouveauProf(ctx) {
  const p = enseignantsP(ctx).nouveau;
  if (!p) ignorer('Enseignant de test non engagé (ENS-03)');
  return p;
}

function matiereTest(ctx) {
  const m = partage(ctx).matiere;
  if (!m?.classeId) ignorer('Matière de test non ajoutée à une classe (STR-04)');
  return m;
}

/** Corps complet d'une matière de classe (le groupe et la durée totale satisfont tous les profils). */
function matiereDeClasse(coefficient = 1) {
  return { coefficient, groupe: 'Recette', volumeHebdo: 1, volumeTotal: 30 };
}

// ===================================================================== structure pédagogique

scenario('STR-01', D_STR, 'Le personnel et les enseignants retrouvent l’année active et les classes de leur établissement', async (ctx) => {
  const e = horizon(ctx);
  for (const qui of [role(e, 'ADMIN_ECOLE'), role(e, 'SURVEILLANT'), e.enseignant(e.enseignants.find((p) => !p.partage)?.matiere)]) {
    if (!qui) continue;
    const a = await exiger(qui, 'GET', '/annees/active');
    egal(a.id, e.anneeId, `Année active vue par ${qui.libelle}`);
    egal(a.etat, 'ACTIVE', 'État de l’année');
  }
  const classes = await exiger(role(e, 'ADMIN_ECOLE'), 'GET', `/annees/${e.anneeId}/classes`);
  for (const c of e.classes) {
    vrai(classes.some((x) => x.id === c.id && x.code === c.code), `Classe ${c.code} absente de la liste`);
  }
  // Isolation : l'année du lycée Horizon n'existe pas pour le lycée technique
  refuse(await appel(ctx.ecole(1).session('ADMIN_ECOLE'), 'GET', `/annees/${e.anneeId}`), 'Année d’un autre établissement');
  return `${classes.length} classes en ${ctx.etat.annee.libelle}`;
});

scenario('STR-02', D_STR, 'Les matières d’une classe portent leur coefficient et l’enseignant affecté au peuplement', async (ctx) => {
  const e = horizon(ctx);
  const c = classeAvecEnseignant(e);
  const matieres = await exiger(role(e, 'CENSEUR'), 'GET', `/classes/${c.id}/matieres`);
  let verifiees = 0;
  for (const m of c.matieres) {
    const vue = matieres.find((x) => x.matiereId === m.id || x.matiereCode === m.code);
    vrai(vue, `Matière ${m.code} absente de la classe ${c.code}`);
    vrai(Number(vue.coefficient) > 0, `Coefficient de ${m.code} manquant`);
    if (m.enseignant) {
      const prof = e.enseignants.find((p) => p.telephone === m.enseignant);
      if (prof?.engagementId) {
        egal(vue.engagementId, prof.engagementId, `Enseignant de ${m.code} en ${c.code}`);
        verifiees++;
      }
    }
  }
  return `${matieres.length} matières en ${c.code}, ${verifiees} affectations contrôlées`;
});

scenario('STR-03', D_STR, 'Le censeur crée une matière (code en majuscules) ; code en double, code mal formé et secrétariat refusés', async (ctx) => {
  const e = horizon(ctx);
  const code = `rec-${suffixe()}`;
  statut(await appel(role(e, 'SECRETARIAT'), 'POST', '/matieres', { code, libelle: 'Matière refusée', type: 'GENERALE' }), 403);
  statut(await appel(role(e, 'CENSEUR'), 'POST', '/matieres', { code: 'avec espace', libelle: 'X', type: 'GENERALE' }), 400);
  statut(await appel(role(e, 'CENSEUR'), 'POST', '/matieres', { code, libelle: 'Sans type' }), 400);
  const m = await exiger(role(e, 'CENSEUR'), 'POST', '/matieres', { code, libelle: 'Recette : méthodologie', type: 'GENERALE' });
  egal(m.code, code.toUpperCase(), 'Code de la matière');
  egal(m.actif, true, 'Matière active');
  statut(await appel(role(e, 'ADMIN_ECOLE'), 'POST', '/matieres', { code: code.toUpperCase(), libelle: 'Doublon', type: 'GENERALE' }), 409, 'CODE_EXISTANT');
  partage(ctx).matiere = { id: m.id, code: m.code };
  return m.code;
});

scenario('STR-04', D_STR, 'La matière de test s’ajoute à une classe ; un coefficient nul et un enseignant sont refusés ; elle apparaît « sans enseignant »', async (ctx) => {
  const e = horizon(ctx);
  const m = partage(ctx).matiere;
  if (!m) ignorer('Matière de test non créée (STR-03)');
  const c = e.classes[0];
  const chemin = `/classes/${c.id}/matieres/${m.id}`;
  statut(await appel(role(e, 'CENSEUR'), 'PUT', chemin, { ...matiereDeClasse(), coefficient: 0 }), 400);
  const prof = e.enseignant(e.enseignants.find((p) => !p.partage)?.matiere);
  if (prof) statut(await appel(prof, 'PUT', chemin, matiereDeClasse()), 403);
  const vue = await exiger(role(e, 'CENSEUR'), 'PUT', chemin, matiereDeClasse(1));
  egal(vue.engagementId, null, 'Pas encore d’enseignant');
  m.classeId = c.id;
  m.classeCode = c.code;
  const sans = await exiger(role(e, 'ADMIN_ECOLE'), 'GET', `/annees/${e.anneeId}/matieres-sans-enseignant`);
  vrai(sans.some((a) => a.classeId === c.id && a.matiereId === m.id), 'La matière de test devrait être signalée sans enseignant');
  return `${m.code} en ${c.code}`;
});

scenario('STR-05', D_STR, 'Pendant l’année, une classe ne se supprime pas, ne change pas de filière et son code reste unique', async (ctx) => {
  const e = horizon(ctx);
  const adm = role(e, 'ADMIN_ECOLE');
  const c = await exiger(adm, 'GET', `/classes/${e.classes[0].id}`);
  statut(await appel(adm, 'POST', `/annees/${e.anneeId}/classes`, { filiereId: c.filiereId, code: c.code, niveau: c.niveau }), 409, 'CODE_EXISTANT');
  statut(await appel(adm, 'DELETE', `/classes/${c.id}`), 409, 'ANNEE_EN_COURS');
  const autre = (await exiger(adm, 'GET', `/annees/${e.anneeId}/classes`)).find((x) => x.filiereId !== c.filiereId);
  if (autre) {
    statut(await appel(adm, 'PUT', `/classes/${c.id}`, {
      filiereId: autre.filiereId, code: c.code, niveau: c.niveau, effectifMax: c.effectifMax,
    }), 409, 'ANNEE_EN_COURS');
  }
  statut(await appel(role(e, 'SECRETARIAT'), 'POST', `/annees/${e.anneeId}/classes`, { filiereId: c.filiereId, code: 'X', niveau: 'X' }), 403);
  return autre ? 'filière figée vérifiée' : 'une seule filière : changement de filière non vérifié';
});

// ===================================================================== élèves et inscriptions

scenario('ELV-01', D_ELV, 'La secrétaire crée le dossier d’une élève avec sa mère : matricule généré, contact prioritaire, espace parent fermé', async (ctx) => {
  const e = horizon(ctx);
  const suf = lettres(suffixe());
  const telephone = telephoneUnique('7');
  const d = await exiger(role(e, 'SECRETARIAT'), 'POST', '/eleves', dossierEleve(`Awa ${suf}`, telephone));
  vrai(/^\d{4}-\d{5}$/.test(d.eleve.matricule), `Matricule généré inattendu : ${d.eleve.matricule}`);
  egal(d.eleve.nom, 'RECETTE', 'Nom en majuscules');
  egal(d.responsables.length, 1, 'Un responsable');
  const r = d.responsables[0];
  vrai(r.contactPrioritaire && r.responsableLegal, 'Le premier responsable est légal et contact prioritaire');
  egal(r.espaceParentOuvert, false, 'Espace parent encore fermé');
  egal(d.inscriptions, [], 'Pas encore inscrite');
  partage(ctx).A = { eleveId: d.eleve.id, matricule: d.eleve.matricule, prenoms: d.eleve.prenoms, telephone, responsableId: r.responsableId, suf };
  return d.eleve.matricule;
});

scenario('ELV-02', D_ELV, 'Un dossier mal saisi est refusé : sexe ou date manquants, naissance future, matricule mal formé ou déjà attribué', async (ctx) => {
  const e = horizon(ctx);
  const A = eleveA(ctx);
  const sec = role(e, 'SECRETARIAT');
  const base = { nom: 'RECETTE', prenoms: 'Invalide', sexe: 'M', dateNaissance: '2011-01-01' };
  statut(await appel(sec, 'POST', '/eleves', { ...base, sexe: undefined }), 400);
  statut(await appel(sec, 'POST', '/eleves', { ...base, dateNaissance: undefined }), 400);
  statut(await appel(sec, 'POST', '/eleves', { ...base, dateNaissance: ajouterJours(ctx.aujourdhui, 30) }), 400);
  statut(await appel(sec, 'POST', '/eleves', { ...base, matricule: 'avec espace !' }), 400);
  statut(await appel(sec, 'POST', '/eleves', { ...base, nom: '' }), 400);
  statut(await appel(sec, 'POST', '/eleves', { ...base, matricule: A.matricule }), 409, 'MATRICULE_EXISTANT');
  // Un responsable sans lien de parenté
  statut(await appel(sec, 'POST', '/eleves', { ...base, responsables: [{ nom: 'X', prenoms: 'Y', telephone: telephoneUnique('7') }] }), 400);
});

scenario('ELV-03', D_ELV, 'L’identifiant national est unique : un second élève avec le même numéro est refusé', async (ctx) => {
  const e = horizon(ctx);
  const A = eleveA(ctx);
  const sec = role(e, 'SECRETARIAT');
  const identifiant = `REC${suffixe().toUpperCase()}`;
  const actuel = await exiger(sec, 'GET', `/eleves/${A.eleveId}`);
  const identite = { ...actuel.eleve, identifiantNational: identifiant };
  delete identite.id;
  await exiger(sec, 'PUT', `/eleves/${A.eleveId}`, identite);
  statut(await appel(sec, 'POST', '/eleves', { nom: 'RECETTE', prenoms: 'Doublon', sexe: 'M', dateNaissance: '2011-01-01', identifiantNational: identifiant }),
    409, 'IDENTIFIANT_EXISTANT');
  return identifiant;
});

scenario('ELV-04', D_ELV, 'Dossiers : le personnel administratif les consulte, seuls l’administration et le secrétariat les créent', async (ctx) => {
  const e = horizon(ctx);
  const A = eleveA(ctx);
  for (const r of ['ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT', 'INTENDANT', 'SURVEILLANT']) {
    await exiger(role(e, r), 'GET', `/eleves/${A.eleveId}`);
  }
  const corps = { nom: 'RECETTE', prenoms: 'Refus', sexe: 'M', dateNaissance: '2011-01-01' };
  for (const r of ['CENSEUR', 'INTENDANT', 'SURVEILLANT']) {
    statut(await appel(role(e, r), 'POST', '/eleves', corps), 403);
  }
  const prof = e.enseignant(e.enseignants.find((p) => !p.partage)?.matiere);
  if (prof) refuse(await appel(prof, 'GET', `/eleves/${A.eleveId}`), 'Dossier lu par un enseignant');
  const parent = e.parent(0);
  if (parent) refuse(await appel(parent, 'GET', `/eleves/${A.eleveId}`), 'Dossier lu par un parent');
  refuse(await appel(ctx.ecole(1).session('ADMIN_ECOLE'), 'GET', `/eleves/${A.eleveId}`), 'Dossier lu par un autre établissement');
});

scenario('ELV-05', D_ELV, 'La recherche retrouve l’élève par matricule ou par nom, page par page (100 élèves au plus)', async (ctx) => {
  const e = horizon(ctx);
  const A = eleveA(ctx);
  const sur = role(e, 'SURVEILLANT');
  const parMatricule = await exiger(sur, 'GET', '/eleves', undefined, { params: { q: A.matricule } });
  vrai(parMatricule.elements.some((x) => x.id === A.eleveId), 'Recherche par matricule');
  const parNom = await exiger(sur, 'GET', '/eleves', undefined, { params: { q: A.suf.toLowerCase() } });
  vrai(parNom.elements.some((x) => x.id === A.eleveId), 'Recherche par prénom, sans tenir compte de la casse');
  const page = await exiger(sur, 'GET', '/eleves', undefined, { params: { taille: 500 } });
  vrai(page.elements.length <= 100 && page.taille <= 100, `Page de ${page.elements.length} élèves (100 au plus)`);
  vrai(page.total >= page.elements.length, 'Total cohérent');
  const autre = await exiger(ctx.ecole(1).session('SECRETARIAT'), 'GET', '/eleves', undefined, { params: { q: A.matricule } });
  vrai(!autre.elements.some((x) => x.id === A.eleveId), 'L’élève du lycée Horizon ne doit pas apparaître ailleurs');
  return `${page.total} élèves au lycée Horizon`;
});

scenario('ELV-06', D_ELV, 'Fratrie : le même téléphone retrouve la même mère ; un rattachement en double est refusé ; le nouveau contact prioritaire remplace l’ancien', async (ctx) => {
  const e = horizon(ctx);
  const A = eleveA(ctx);
  const sec = role(e, 'SECRETARIAT');
  const B = await exiger(sec, 'POST', '/eleves', dossierEleve(`Issa ${A.suf}`, A.telephone, { sexe: 'M' }));
  egal(B.responsables[0].responsableId, A.responsableId, 'Même responsable pour la fratrie');
  partage(ctx).B = { eleveId: B.eleve.id, matricule: B.eleve.matricule };
  statut(await appel(sec, 'POST', `/eleves/${A.eleveId}/responsables`, {
    nom: 'RECETTE', prenoms: 'Doublon', telephone: A.telephone, lien: 'MERE',
  }), 409, 'RESPONSABLE_DEJA_LIE');
  const telPere = telephoneUnique('7');
  const d = await exiger(sec, 'POST', `/eleves/${A.eleveId}/responsables`, {
    nom: 'RECETTE', prenoms: `Père ${A.suf}`, telephone: telPere, lien: 'PERE', responsableLegal: true, contactPrioritaire: true,
  });
  egal(d.responsables.length, 2, 'Deux responsables');
  const prioritaires = d.responsables.filter((r) => r.contactPrioritaire);
  egal(prioritaires.length, 1, 'Un seul contact prioritaire');
  egal(prioritaires[0].telephone.endsWith(telPere.slice(-8)), true, 'Le père devient le contact prioritaire');
  A.pere = { responsableId: prioritaires[0].responsableId, telephone: telPere };
});

scenario('ELV-07', D_ELV, 'L’élève est inscrite dans une classe : inscription active, visible dans la liste de la classe ; une seconde inscription est refusée', async (ctx) => {
  const e = horizon(ctx);
  const A = eleveA(ctx);
  const sec = role(e, 'SECRETARIAT');
  let ins = null;
  let derniere = null;
  for (const c of e.classes) {
    const r = await appel(sec, 'POST', '/inscriptions', { eleveId: A.eleveId, classeId: c.id, statutBourse: 'NON_BOURSIER' });
    if (r.ok) {
      ins = r.donnees;
      break;
    }
    derniere = r;
    if (r.code !== 'CLASSE_COMPLETE') break;
  }
  if (!ins) throw new Echec(`Inscription impossible : ${derniere?.resume()}`);
  egal(ins.statut, 'ACTIVE', 'Statut');
  egal(ins.anneeId, e.anneeId, 'Année de l’inscription');
  egal(ins.redoublant, false, 'Non redoublante par défaut');
  A.inscriptionId = ins.id;
  A.classeId = ins.classeId;
  const liste = await exiger(role(e, 'CENSEUR'), 'GET', `/classes/${ins.classeId}/inscriptions`);
  vrai(liste.some((x) => x.id === ins.id), 'L’élève figure dans la liste de la classe');
  const autreClasse = e.classes.find((c) => c.id !== ins.classeId) ?? e.classes[0];
  statut(await appel(sec, 'POST', '/inscriptions', { eleveId: A.eleveId, classeId: autreClasse.id }), 409, 'DEJA_INSCRIT');
  // Une classe d'un autre établissement n'existe pas ici
  refuse(await appel(sec, 'POST', '/inscriptions', { eleveId: A.eleveId, classeId: ctx.ecole(1).classes[0].id }), 'Classe d’un autre établissement');
  statut(await appel(role(e, 'INTENDANT'), 'POST', '/inscriptions', { eleveId: A.eleveId, classeId: autreClasse.id }), 403);
  // Le frère est inscrit aussi (il sert à la sortie en fin de lot)
  const B = partage(ctx).B;
  if (B) {
    const rb = await appel(sec, 'POST', '/inscriptions', { eleveId: B.eleveId, classeId: ins.classeId });
    if (rb.ok) B.inscriptionId = rb.donnees.id;
  }
  return `${ins.classeCode}`;
});

scenario('ELV-08', D_ELV, 'L’élève change de classe dans la même année et la liste de chaque classe suit', async (ctx) => {
  const e = horizon(ctx);
  const A = inscriptionA(ctx);
  const sec = role(e, 'SECRETARIAT');
  const cible = e.classes.find((c) => c.id !== A.classeId);
  if (!cible) ignorer('Une seule classe au lycée Horizon');
  const r = await appel(sec, 'PATCH', `/inscriptions/${A.inscriptionId}/classe`, { classeId: cible.id });
  if (r.code === 'CLASSE_COMPLETE') ignorer(`La classe ${cible.code} est complète`);
  statut(r, 200);
  egal(r.donnees.classeId, cible.id, 'Nouvelle classe');
  const avant = await exiger(sec, 'GET', `/classes/${A.classeId}/inscriptions`);
  const apres = await exiger(sec, 'GET', `/classes/${cible.id}/inscriptions`);
  vrai(!avant.some((x) => x.id === A.inscriptionId), 'Retirée de l’ancienne classe');
  vrai(apres.some((x) => x.id === A.inscriptionId), 'Présente dans la nouvelle classe');
  // Retour dans la classe d'origine
  await exiger(sec, 'PATCH', `/inscriptions/${A.inscriptionId}/classe`, { classeId: A.classeId });
  return `${cible.code} puis retour`;
});

scenario('ELV-09', D_ELV, 'Le statut de bourse se change à l’intendance ; le surveillant ne le peut pas et une valeur inconnue est refusée', async (ctx) => {
  const e = horizon(ctx);
  const A = inscriptionA(ctx);
  const chemin = `/inscriptions/${A.inscriptionId}/bourse`;
  statut(await appel(role(e, 'SURVEILLANT'), 'PATCH', chemin, { statutBourse: 'BOURSIER' }), 403);
  statut(await appel(role(e, 'INTENDANT'), 'PATCH', chemin, { statutBourse: 'MIRACLE' }), 400);
  const r = await exiger(role(e, 'INTENDANT'), 'PATCH', chemin, { statutBourse: 'SEMI_BOURSIER' });
  egal(r.statutBourse, 'SEMI_BOURSIER', 'Statut de bourse');
  await exiger(role(e, 'SECRETARIAT'), 'PATCH', chemin, { statutBourse: 'NON_BOURSIER' });
});

scenario('ELV-10', D_ELV, 'L’espace parent s’ouvre : mot de passe provisoire une seule fois, la mère voit ses deux enfants, son téléphone ne se modifie plus', async (ctx) => {
  const e = horizon(ctx);
  const A = inscriptionA(ctx);
  const sec = role(e, 'SECRETARIAT');
  statut(await appel(role(e, 'SURVEILLANT'), 'POST', `/responsables/${A.responsableId}/espace-parent`), 403);
  const ouverture = await exiger(sec, 'POST', `/responsables/${A.responsableId}/espace-parent`);
  vrai(ouverture.motDePasseTemporaire, 'Mot de passe provisoire attendu pour un nouveau compte');
  const encore = await exiger(sec, 'POST', `/responsables/${A.responsableId}/espace-parent`);
  egal(encore.motDePasseTemporaire, null, 'Le mot de passe n’est renvoyé qu’une fois');
  egal(encore.utilisateurId, ouverture.utilisateurId, 'Même compte');
  const mere = await ctx.activer(A.telephone, ouverture.motDePasseTemporaire, e.id);
  const enfants = await exiger(mere, 'GET', '/espace-parent/enfants');
  vrai(enfants.some((x) => x.eleveId === A.eleveId), 'L’élève figure parmi ses enfants');
  const B = partage(ctx).B;
  if (B?.inscriptionId) vrai(enfants.some((x) => x.eleveId === B.eleveId), 'Le frère figure aussi');
  vrai(enfants.every((x) => [A.eleveId, B?.eleveId].includes(x.eleveId)), 'Aucun autre enfant');
  A.mere = mere;
  // Le téléphone sert désormais à la connexion
  statut(await appel(sec, 'PUT', `/responsables/${A.responsableId}`, {
    nom: 'RECETTE', prenoms: `Mère ${A.prenoms}`, telephone: telephoneUnique('7'),
  }), 409, 'ESPACE_PARENT_OUVERT');
  // Le père ne peut pas prendre un numéro déjà utilisé par un autre responsable
  if (A.pere) {
    statut(await appel(sec, 'PUT', `/responsables/${A.pere.responsableId}`, {
      nom: 'RECETTE', prenoms: 'Père', telephone: A.telephone,
    }), 409, 'TELEPHONE_EXISTANT');
    statut(await appel(sec, 'PUT', `/responsables/${A.pere.responsableId}`, {
      nom: 'RECETTE', prenoms: 'Père modifié', telephone: A.pere.telephone, profession: 'Mécanicien',
    }), 204);
  }
});

scenario('ELV-11', D_ELV, 'Un responsable se retire du dossier ; retirer quelqu’un qui n’y est pas rattaché répond 404', async (ctx) => {
  const e = horizon(ctx);
  const A = eleveA(ctx);
  if (!A.pere) ignorer('Père non rattaché (ELV-06)');
  const sec = role(e, 'SECRETARIAT');
  const d = await exiger(sec, 'DELETE', `/eleves/${A.eleveId}/responsables/${A.pere.responsableId}`);
  vrai(!d.responsables.some((r) => r.responsableId === A.pere.responsableId), 'Le père n’est plus rattaché');
  statut(await appel(sec, 'DELETE', `/eleves/${A.eleveId}/responsables/${A.pere.responsableId}`), 404);
});

scenario('ELV-12', D_ELV, 'La fiche des accès parents d’une classe refuse un format inconnu et les rôles non autorisés (sans rien ouvrir)', async (ctx) => {
  const e = horizon(ctx);
  const c = e.classes[0];
  // Le format est lu avant toute ouverture de compte : aucun effet sur les familles de démonstration
  statut(await appel(role(e, 'SECRETARIAT'), 'POST', `/classes/${c.id}/espaces-parents`, undefined, { params: { format: 'docx' } }), 400);
  statut(await appel(role(e, 'SURVEILLANT'), 'POST', `/classes/${c.id}/espaces-parents`), 403);
  statut(await appel(role(e, 'INTENDANT'), 'POST', `/classes/${c.id}/espaces-parents`), 403);
  return 'génération réelle de la fiche : recette manuelle (elle ouvre les comptes des familles)';
});

scenario('ELV-13', D_ELV, 'Import des élèves : le modèle Excel se télécharge, se contrôle sans rien importer ; fichier vide ou illisible refusés', async (ctx) => {
  const e = horizon(ctx);
  const sec = role(e, 'SECRETARIAT');
  const modele = await exiger(sec, 'GET', `/annees/${e.anneeId}/eleves/import/modele`, undefined, { fichier: true });
  vrai(modele.octets?.length > 0 && modele.octets.subarray(0, 2).toString() === 'PK', 'Le modèle doit être un classeur Excel (.xlsx)');
  const envoyer = (contenu, nom, type, simulation = true) => {
    const f = new FormData();
    f.append('fichier', new Blob([contenu], { type }), nom);
    return appel(sec, 'POST', `/annees/${e.anneeId}/eleves/import`, f, { params: { simulation } });
  };
  const xlsx = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
  const r = await envoyer(modele.octets, 'modele.xlsx', xlsx);
  statut(r, 200);
  egal(r.donnees.simulation, true, 'Contrôle seulement');
  egal(r.donnees.importees, 0, 'Rien n’est importé en simulation');
  statut(await envoyer(Buffer.alloc(0), 'vide.xlsx', xlsx), 400);
  statut(await envoyer(Buffer.from('nom;prenoms\nX;Y\n'), 'eleves.csv', 'text/csv', false), 400);
  const prof = e.enseignant(e.enseignants.find((p) => !p.partage)?.matiere);
  if (prof) statut(await appel(prof, 'GET', `/annees/${e.anneeId}/eleves/import/modele`), 403);
  return `modèle de ${modele.octets.length} octets, ${r.donnees.lignes} ligne(s) d’exemple contrôlée(s)`;
});

scenario('ELV-14', D_ELV, 'Un enseignant consulte la liste de ses classes, pas celle d’une classe où il n’enseigne pas', async (ctx) => {
  const e = horizon(ctx);
  const t = enseignantEtAutreClasse(e);
  if (!t) ignorer('Pas d’enseignant limité à une partie des classes');
  const liste = await exiger(t.session, 'GET', `/classes/${t.classe.id}/inscriptions`);
  vrai(Array.isArray(liste) && liste.length > 0, 'Liste de sa classe');
  statut(await appel(t.session, 'GET', `/classes/${t.autre.id}/inscriptions`), 403);
  const parent = e.parent(0);
  if (parent) statut(await appel(parent, 'GET', `/classes/${t.classe.id}/inscriptions`), 403);
  return `${t.classe.code} oui, ${t.autre.code} non`;
});

scenario('ELV-16', D_ELV, 'Photo d’identité (v0.44) : déposée par le secrétariat, vue par les enseignants de la classe seulement, imprimée sur la carte scolaire, puis retirée', async (ctx) => {
  const e = horizon(ctx);
  const A = inscriptionA(ctx);
  const sec = role(e, 'SECRETARIAT');
  const chemin = `/eleves/${A.eleveId}/photo`;
  const deposer = (qui, fichier) => {
    const f = new FormData();
    f.append('fichier', fichier, 'photo.png');
    return appel(qui, 'POST', chemin, f);
  };
  statut(await appel(sec, 'GET', chemin), 404);
  statut(await deposer(sec, new Blob([Buffer.from('pas une image')], { type: 'image/png' })), 400);
  statut(await deposer(role(e, 'SURVEILLANT'), fichierPng()), 403);
  statut(await deposer(sec, fichierPng()), 204);
  const photo = await exiger(role(e, 'SURVEILLANT'), 'GET', chemin, undefined, { fichier: true });
  egal(photo.type, 'image/jpeg', 'Photo ré-encodée en JPEG');
  vrai(photo.octets[0] === 0xff && photo.octets[1] === 0xd8, 'JPEG');
  await exiger(sec, 'GET', chemin, undefined, { fichier: true, params: { miniature: true } });
  const avec = await exiger(role(e, 'CENSEUR'), 'GET', `/classes/${A.classeId}/photos`);
  vrai(avec.includes(A.eleveId), 'L’élève figure parmi ceux qui ont une photo');
  // Enseignants : ceux de la classe la voient, les autres non
  const classe = e.classes.find((c) => c.id === A.classeId);
  const telProf = classe?.matieres.find((m) => m.enseignant)?.enseignant;
  const prof = e.enseignants.find((p) => p.telephone === telProf && !p.partage);
  if (prof) {
    await exiger(ctx.compte(prof.telephone, e.id, `enseignant ${prof.matiere}`), 'GET', chemin, undefined, { fichier: true, params: { miniature: true } });
  }
  const etranger = e.enseignants.find((p) => !p.partage && !classe?.matieres.some((m) => m.enseignant === p.telephone));
  if (etranger) statut(await appel(ctx.compte(etranger.telephone, e.id, `enseignant ${etranger.matiere}`), 'GET', chemin), 403);
  const parent = e.parent(0);
  if (parent) statut(await appel(parent, 'GET', chemin), 403);
  refuse(await appel(ctx.ecole(1).session('ADMIN_ECOLE'), 'GET', chemin), 'Photo lue par un autre établissement');
  // Carte scolaire (élève et classe)
  const carte = await exiger(sec, 'GET', `/eleves/${A.eleveId}/carte`, undefined, { fichier: true });
  vrai(carte.octets.subarray(0, 4).toString() === '%PDF', 'Carte scolaire en PDF');
  const cartes = await exiger(sec, 'GET', `/classes/${A.classeId}/cartes`, undefined, { fichier: true });
  vrai(cartes.octets.subarray(0, 4).toString() === '%PDF', 'Cartes de la classe en PDF');
  statut(await appel(role(e, 'CENSEUR'), 'GET', `/classes/${A.classeId}/cartes`), 403);
  statut(await appel(sec, 'DELETE', chemin), 204);
  statut(await appel(sec, 'GET', chemin), 404);
  return `${classe?.code ?? ''} : photo ${photo.octets.length} octets`;
});

scenario('ELV-15', D_ELV, 'Sortie de l’élève (abandon) : inscription figée, absente de la liste sauf à demander les sorties ; saisies invalides refusées', async (ctx) => {
  const e = horizon(ctx);
  const A = inscriptionA(ctx);
  const sec = role(e, 'SECRETARIAT');
  const chemin = `/inscriptions/${A.inscriptionId}/sortie`;
  statut(await appel(sec, 'POST', chemin, { statut: 'ACTIVE', date: ctx.aujourdhui }), 400);
  statut(await appel(sec, 'POST', chemin, { statut: 'ABANDON', date: '2000-01-01' }), 400);
  statut(await appel(sec, 'POST', chemin, { statut: 'ABANDON' }), 400);
  const s = await exiger(sec, 'POST', chemin, { statut: 'ABANDON', date: ctx.aujourdhui, motif: 'Recette : départ de la famille' });
  egal(s.statut, 'ABANDON', 'Statut de sortie');
  egal(s.dateSortie, ctx.aujourdhui, 'Date de sortie');
  statut(await appel(sec, 'PATCH', `/inscriptions/${A.inscriptionId}/classe`, { classeId: A.classeId }), 409, 'INSCRIPTION_TERMINEE');
  const actives = await exiger(sec, 'GET', `/classes/${A.classeId}/inscriptions`);
  vrai(!actives.some((x) => x.id === A.inscriptionId), 'Absente de la liste par défaut');
  const toutes = await exiger(sec, 'GET', `/classes/${A.classeId}/inscriptions`, undefined, { params: { sorties: true } });
  vrai(toutes.some((x) => x.id === A.inscriptionId && x.statut === 'ABANDON'), 'Présente avec les sorties');
  // L'espace parent ne montre plus l'élève comme active
  if (A.mere) {
    const enfants = await exiger(A.mere, 'GET', '/espace-parent/enfants');
    const vue = enfants.find((x) => x.eleveId === A.eleveId);
    if (vue) egal(vue.statut, 'ABANDON', 'Statut vu par la mère');
  }
  // Le frère est transféré
  const B = partage(ctx).B;
  if (B?.inscriptionId) {
    const t = await exiger(sec, 'POST', `/inscriptions/${B.inscriptionId}/sortie`, { statut: 'TRANSFEREE', date: ctx.aujourdhui, motif: 'Recette' });
    egal(t.statut, 'TRANSFEREE', 'Transfert du frère');
  }
});

// ===================================================================== enseignants

scenario('ENS-01', D_ENS, 'La liste des enseignants classe chacun en général ou technique selon ses matières (v0.40) ; enseignant et parent n’y ont pas accès', async (ctx) => {
  let controles = 0;
  for (const e of ctx.ecoles) {
    const liste = await exiger(e.session('ADMIN_ECOLE'), 'GET', '/enseignants');
    const types = new Map(e.classes.flatMap((c) => c.matieres.map((m) => [m.code, m.type])));
    for (const p of e.enseignants) {
      const vue = liste.find((x) => x.engagementId === p.engagementId);
      vrai(vue, `${p.nom} (${p.matiere}) absent de la liste du ${e.nom}`);
      vrai(['GENERAL', 'TECHNIQUE'].includes(vue.categorie), `Catégorie de ${p.nom} : ${vue.categorie}`);
      const type = types.get(p.matiere);
      if (type) {
        egal(vue.categorie, type === 'GENERALE' ? 'GENERAL' : 'TECHNIQUE', `Catégorie de ${p.nom} (${p.matiere}, ${type})`);
        controles++;
      }
    }
  }
  const e = horizon(ctx);
  await exiger(role(e, 'SURVEILLANT'), 'GET', '/enseignants');
  const prof = e.enseignant(e.enseignants.find((p) => !p.partage)?.matiere);
  if (prof) statut(await appel(prof, 'GET', '/enseignants'), 403);
  const parent = e.parent(0);
  if (parent) statut(await appel(parent, 'GET', '/enseignants'), 403);
  return `${controles} catégories contrôlées dans ${ctx.ecoles.length} établissements`;
});

scenario('ENS-02', D_ENS, 'La recherche d’un enseignant répond seulement « trouvé » ou « non trouvé » ; sans critère 400 ; réservée à l’administrateur', async (ctx) => {
  const e = horizon(ctx);
  const adm = role(e, 'ADMIN_ECOLE');
  const ailleurs = ctx.ecole(1).enseignants[0];
  const r = await exiger(adm, 'POST', '/enseignants/recherche', { telephone: ailleurs.telephone });
  egal(r, { trouve: true }, 'Enseignant d’un autre établissement (oui/non seulement)');
  egal(await exiger(adm, 'POST', '/enseignants/recherche', { telephone: telephoneUnique('7') }), { trouve: false }, 'Numéro inconnu');
  statut(await appel(adm, 'POST', '/enseignants/recherche', {}), 400);
  statut(await appel(role(e, 'CENSEUR'), 'POST', '/enseignants/recherche', { telephone: ailleurs.telephone }), 403);
});

scenario('ENS-03', D_ENS, 'L’administrateur engage un nouveau titulaire : compte créé, mot de passe provisoire une fois, catégorie générale par défaut ; saisies incohérentes refusées', async (ctx) => {
  const e = horizon(ctx);
  const adm = role(e, 'ADMIN_ECOLE');
  const telephone = telephoneUnique('7');
  const debut = ajouterJours(ctx.aujourdhui, -20);
  const corps = { telephone, nom: 'Recette', prenoms: `Prof ${lettres(suffixe())}`, sexe: 'M', specialite: 'Méthodologie', type: 'TITULAIRE', debut };
  statut(await appel(role(e, 'CENSEUR'), 'POST', '/enseignants', corps), 403);
  statut(await appel(adm, 'POST', '/enseignants', { ...corps, sexe: undefined }), 400);
  statut(await appel(adm, 'POST', '/enseignants', { ...corps, tauxHoraire: 3000 }), 400);
  statut(await appel(adm, 'POST', '/enseignants', { ...corps, fin: ajouterJours(debut, -1) }), 400);
  statut(await appel(adm, 'POST', '/enseignants', { ...corps, type: undefined }), 400);
  const r = await exiger(adm, 'POST', '/enseignants', corps);
  egal(r.invitation, false, 'Création directe, pas une invitation');
  vrai(r.motDePasseTemporaire, 'Mot de passe provisoire attendu');
  egal(r.enseignant.statut, 'ACTIF', 'Engagement actif');
  egal(r.enseignant.categorie, 'GENERAL', 'Catégorie par défaut');
  egal(r.enseignant.nom, 'RECETTE', 'Nom en majuscules');
  statut(await appel(adm, 'POST', '/enseignants', corps), 409, 'DEJA_ENGAGE');
  enseignantsP(ctx).nouveau = { telephone, engagementId: r.enseignant.engagementId, provisoire: r.motDePasseTemporaire, corps };
  return r.enseignant.engagementId;
});

scenario('ENS-04', D_ENS, 'Le nouvel enseignant se connecte ; le censeur l’affecte à la matière de test : il la voit et consulte la liste de la classe', async (ctx) => {
  const e = horizon(ctx);
  const p = nouveauProf(ctx);
  const m = matiereTest(ctx);
  p.session = await ctx.activer(p.telephone, p.provisoire, e.id);
  const vide = await exiger(p.session, 'GET', '/espace-enseignant/affectations');
  egal(vide.affectations.length, 0, 'Aucune matière avant l’affectation');
  refuse(await appel(p.session, 'GET', `/classes/${m.classeId}/inscriptions`), 'Liste avant affectation');
  statut(await appel(role(e, 'SECRETARIAT'), 'PUT', `/classes/${m.classeId}/matieres/${m.id}/enseignant`, { engagementId: p.engagementId }), 403);
  const vue = await exiger(role(e, 'CENSEUR'), 'PUT', `/classes/${m.classeId}/matieres/${m.id}/enseignant`, { engagementId: p.engagementId });
  egal(vue.engagementId, p.engagementId, 'Affectation enregistrée');
  const fiche = await exiger(p.session, 'GET', '/espace-enseignant/affectations');
  vrai(fiche.affectations.some((a) => a.matiereId === m.id && a.classeId === m.classeId), 'La matière de test figure dans ses affectations');
  await exiger(p.session, 'GET', `/classes/${m.classeId}/inscriptions`);
  const sans = await exiger(role(e, 'ADMIN_ECOLE'), 'GET', `/annees/${e.anneeId}/matieres-sans-enseignant`);
  vrai(!sans.some((a) => a.classeId === m.classeId && a.matiereId === m.id), 'La matière n’est plus signalée sans enseignant');
});

scenario('ENS-05', D_ENS, 'L’identité d’un titulaire se corrige dans son établissement ; le sexe reste obligatoire', async (ctx) => {
  const e = horizon(ctx);
  const p = nouveauProf(ctx);
  const adm = role(e, 'ADMIN_ECOLE');
  const chemin = `/engagements/${p.engagementId}/identite`;
  statut(await appel(adm, 'PUT', chemin, { nom: 'Recette', prenoms: p.corps.prenoms }), 400);
  const matricule = `fp${suffixe()}`;
  const v = await exiger(adm, 'PUT', chemin, { matriculeFp: matricule, nom: 'Recette', prenoms: `${p.corps.prenoms} Junior`, sexe: 'M', specialite: 'Français' });
  egal(v.matriculeFp, matricule.toUpperCase(), 'Matricule de la fonction publique');
  egal(v.specialite, 'Français', 'Spécialité');
  statut(await appel(role(e, 'CENSEUR'), 'PUT', chemin, { nom: 'X', prenoms: 'Y', sexe: 'M' }), 403);
  p.matricule = v.matriculeFp;
  const fiche = await exiger(role(e, 'SECRETARIAT'), 'GET', `/engagements/${p.engagementId}`);
  egal(fiche.enseignant.prenoms, `${p.corps.prenoms} Junior`, 'Fiche à jour');
  // Isolation : un autre établissement ne lit pas cet engagement
  refuse(await appel(ctx.ecole(1).session('ADMIN_ECOLE'), 'GET', `/engagements/${p.engagementId}`), 'Engagement lu par un autre établissement');
});

scenario('ENS-06', D_ENS, 'La catégorie se change par l’administrateur seulement (technique puis général) ; une valeur absente est refusée', async (ctx) => {
  const e = horizon(ctx);
  const p = nouveauProf(ctx);
  const chemin = `/engagements/${p.engagementId}/categorie`;
  statut(await appel(role(e, 'CENSEUR'), 'PUT', chemin, { categorie: 'TECHNIQUE' }), 403);
  statut(await appel(role(e, 'ADMIN_ECOLE'), 'PUT', chemin, {}), 400);
  statut(await appel(role(e, 'ADMIN_ECOLE'), 'PUT', chemin, { categorie: 'SPORT' }), 400);
  egal((await exiger(role(e, 'ADMIN_ECOLE'), 'PUT', chemin, { categorie: 'TECHNIQUE' })).categorie, 'TECHNIQUE', 'Classé technique');
  egal((await exiger(role(e, 'ADMIN_ECOLE'), 'PUT', chemin, { categorie: 'GENERAL' })).categorie, 'GENERAL', 'Revenu en général');
});

scenario('ENS-07', D_ENS, 'Onglet Ateliers (v0.40) : visible pour un enseignant technique d’une filière servie par un atelier, pas pour un enseignant général', async (ctx) => {
  const e = ctx.ecole('lt-batisseurs') ?? ctx.ecole(1);
  if (!e?.ateliers?.length) ignorer('Pas d’atelier au lycée technique');
  const filieres = new Set(e.ateliers.map((a) => a.filiere));
  let technique = null;
  let general = null;
  for (const c of e.classes) {
    for (const m of c.matieres.filter((x) => x.enseignant)) {
      const prof = e.enseignants.find((p) => p.telephone === m.enseignant && !p.partage);
      if (!prof) continue;
      if (m.type === 'GENERALE' && !general && !c.matieres.some((x) => x.type !== 'GENERALE' && x.enseignant === m.enseignant)) general = prof;
      if (m.type !== 'GENERALE' && filieres.has(c.filiere) && !technique) technique = prof;
    }
  }
  if (!technique || !general) ignorer('Enseignants technique et général introuvables dans l’état du peuplement');
  const voir = async (p) => (await exiger(ctx.compte(p.telephone, e.id), 'GET', '/ateliers/acces')).visible;
  egal(await voir(technique), true, `Onglet pour ${technique.nom} (${technique.matiere})`);
  egal(await voir(general), false, `Onglet pour ${general.nom} (${general.matiere})`);
  egal((await exiger(e.session('INTENDANT'), 'GET', '/ateliers/acces')).visible, true, 'Onglet pour l’intendant');
  return `${technique.matiere} oui, ${general.matiere} non`;
});

scenario('ENS-08', D_ENS, 'Un titulaire du lycée technique est invité comme vacataire : identité masquée tant qu’il n’a pas répondu, invitation en double refusée, pas d’affectation', async (ctx) => {
  const e = horizon(ctx);
  const source = ctx.ecole(1);
  const partages = new Set(ctx.ecoles.flatMap((x) => x.enseignants.filter((p) => p.partage).map((p) => p.telephone)));
  const prof = source.enseignants.find((p) => p.type === 'titulaire' && !partages.has(p.telephone));
  if (!prof) ignorer('Pas de titulaire non partagé au lycée technique');
  const adm = role(e, 'ADMIN_ECOLE');
  const corps = { telephone: prof.telephone, type: 'VACATAIRE', debut: ctx.aujourdhui, tauxHoraire: 3000 };
  const r = await exiger(adm, 'POST', '/enseignants', corps);
  egal(r.invitation, true, 'Invitation (enseignant déjà connu)');
  egal(r.motDePasseTemporaire, null, 'Pas de mot de passe pour un compte existant');
  egal(r.enseignant.statut, 'INVITE', 'Statut');
  egal([r.enseignant.nom, r.enseignant.telephone, r.enseignant.enseignantId], [null, null, null], 'Identité masquée');
  statut(await appel(adm, 'POST', '/enseignants', corps), 409, 'DEJA_ENGAGE');
  const m = partage(ctx).matiere;
  if (m?.classeId) {
    statut(await appel(role(e, 'CENSEUR'), 'PUT', `/classes/${m.classeId}/matieres/${m.id}/enseignant`, { engagementId: r.enseignant.engagementId }),
      409, 'ENGAGEMENT_INACTIF');
  }
  enseignantsP(ctx).invite = { prof, engagementId: r.enseignant.engagementId };
  return prof.matiere;
});

scenario('ENS-09', D_ENS, 'L’enseignant voit l’invitation depuis son établissement et la refuse ; elle disparaît et ne se traite pas deux fois', async (ctx) => {
  const e = horizon(ctx);
  const inv = enseignantsP(ctx).invite;
  if (!inv) ignorer('Invitation non envoyée (ENS-08)');
  const lui = ctx.compte(inv.prof.telephone, ctx.ecole(1).id);
  const liste = await exiger(lui, 'GET', '/moi/invitations');
  const vue = liste.find((x) => x.engagementId === inv.engagementId);
  vrai(vue, 'Invitation visible de l’enseignant');
  egal(vue.etablissementId, e.id, 'Établissement qui invite');
  egal(vue.type, 'VACATAIRE', 'Type proposé');
  // Un autre enseignant ne peut pas y répondre
  const autre = ctx.ecole(1).enseignants.find((p) => p.telephone !== inv.prof.telephone && !p.partage);
  if (autre) refuse(await appel(ctx.compte(autre.telephone, ctx.ecole(1).id), 'POST', `/moi/invitations/${inv.engagementId}/acceptation`),
    'Réponse par un autre enseignant');
  const rep = await exiger(lui, 'POST', `/moi/invitations/${inv.engagementId}/refus`);
  egal(rep.statut, 'REFUSE', 'Invitation refusée');
  vrai(!(await exiger(lui, 'GET', '/moi/invitations')).some((x) => x.engagementId === inv.engagementId), 'L’invitation n’est plus listée');
  statut(await appel(lui, 'POST', `/moi/invitations/${inv.engagementId}/acceptation`), 404);
  const cote = (await exiger(role(e, 'ADMIN_ECOLE'), 'GET', '/enseignants')).find((x) => x.engagementId === inv.engagementId);
  egal(cote?.statut, 'REFUSE', 'Statut vu par l’établissement');
  egal(cote?.nom ?? null, null, 'Identité toujours masquée après un refus');
});

scenario('ENS-10', D_ENS, 'Une nouvelle invitation s’annule par l’établissement ; un engagement actif ne s’annule pas, il se termine', async (ctx) => {
  const e = horizon(ctx);
  const inv = enseignantsP(ctx).invite;
  if (!inv) ignorer('Invitation non envoyée (ENS-08)');
  const adm = role(e, 'ADMIN_ECOLE');
  const r = await exiger(adm, 'POST', '/enseignants', { telephone: inv.prof.telephone, type: 'VACATAIRE', debut: ctx.aujourdhui, tauxHoraire: 2500 });
  egal(r.invitation, true, 'Nouvelle invitation après un refus');
  statut(await appel(role(e, 'CENSEUR'), 'DELETE', `/engagements/${r.enseignant.engagementId}`), 403);
  statut(await appel(adm, 'DELETE', `/engagements/${r.enseignant.engagementId}`), 204);
  statut(await appel(adm, 'DELETE', `/engagements/${r.enseignant.engagementId}`), 404);
  const lui = ctx.compte(inv.prof.telephone, ctx.ecole(1).id);
  vrai(!(await exiger(lui, 'GET', '/moi/invitations')).some((x) => x.engagementId === r.enseignant.engagementId), 'Invitation annulée invisible');
  const p = enseignantsP(ctx).nouveau;
  if (p) statut(await appel(adm, 'DELETE', `/engagements/${p.engagementId}`), 409, 'STATUT_ENGAGEMENT');
});

scenario('ENS-11', D_ENS, 'Un titulaire ailleurs ne peut pas être engagé comme titulaire, et l’enseignant partagé déjà engagé ne se réinvite pas', async (ctx) => {
  const e = horizon(ctx);
  const inv = enseignantsP(ctx).invite;
  const adm = role(e, 'ADMIN_ECOLE');
  if (inv) {
    statut(await appel(adm, 'POST', '/enseignants', { telephone: inv.prof.telephone, type: 'TITULAIRE', debut: ctx.aujourdhui }),
      409, 'POSTE_TITULAIRE_OCCUPE');
  }
  const partageHorizon = e.enseignants.find((p) => p.partage);
  if (partageHorizon) {
    statut(await appel(adm, 'POST', '/enseignants', { telephone: partageHorizon.telephone, type: 'VACATAIRE', debut: ctx.aujourdhui, tauxHoraire: 2000 }),
      409, 'DEJA_ENGAGE');
  }
  if (!inv && !partageHorizon) ignorer('Ni titulaire invité ni enseignant partagé');
});

scenario('ENS-12', D_ENS, 'Une fin d’engagement programmée laisse l’enseignant actif et s’annule ; annuler sans fin programmée est refusé', async (ctx) => {
  const e = horizon(ctx);
  const p = nouveauProf(ctx);
  const adm = role(e, 'ADMIN_ECOLE');
  const date = ajouterJours(ctx.aujourdhui, 15);
  const v = await exiger(adm, 'POST', `/engagements/${p.engagementId}/fin`, { date, motif: 'Recette : mutation' });
  egal(v.statut, 'ACTIF', 'Toujours actif');
  egal(v.finProgrammee, true, 'Fin programmée');
  egal(v.fin, date, 'Date de fin');
  await exiger(p.session ?? ctx.compte(p.telephone, e.id), 'GET', '/espace-enseignant/affectations');
  const annulee = await exiger(adm, 'DELETE', `/engagements/${p.engagementId}/fin`);
  egal([annulee.finProgrammee, annulee.fin], [false, null], 'Fin annulée');
  statut(await appel(adm, 'DELETE', `/engagements/${p.engagementId}/fin`), 409, 'AUCUNE_FIN_PROGRAMMEE');
  statut(await appel(adm, 'POST', `/engagements/${p.engagementId}/fin`, { date: ajouterJours(ctx.aujourdhui, -40) }), 400);
  statut(await appel(adm, 'POST', `/engagements/${p.engagementId}/fin`, {}), 400);
});

scenario('ENS-13', D_ENS, 'Fin immédiate : l’engagement est terminé, la matière libérée et le compte ne se connecte plus à l’établissement', async (ctx) => {
  const e = horizon(ctx);
  const p = nouveauProf(ctx);
  const adm = role(e, 'ADMIN_ECOLE');
  const v = await exiger(adm, 'POST', `/engagements/${p.engagementId}/fin`, { date: ajouterJours(ctx.aujourdhui, -1), motif: 'Recette : départ' });
  egal(v.statut, 'TERMINE', 'Engagement terminé');
  const m = partage(ctx).matiere;
  if (m?.classeId) {
    const matieres = await exiger(adm, 'GET', `/classes/${m.classeId}/matieres`);
    egal(matieres.find((x) => x.matiereId === m.id)?.engagementId ?? null, null, 'Matière libérée');
  }
  const r = await appelBrut('POST', '/auth/connexion', { telephone: p.telephone, motDePasse: ctx.motDePasse });
  statut(r, 401);
  statut(await appel(adm, 'POST', `/engagements/${p.engagementId}/fin`, { date: ctx.aujourdhui }), 409, 'STATUT_ENGAGEMENT');
  statut(await appel(adm, 'PUT', `/engagements/${p.engagementId}/identite`, { nom: 'X', prenoms: 'Y', sexe: 'M' }), 409, 'IDENTITE_NON_MODIFIABLE');
  const liste = await exiger(adm, 'GET', '/enseignants', undefined, { params: { statut: 'TERMINE' } });
  vrai(liste.some((x) => x.engagementId === p.engagementId && x.nom === 'RECETTE'), 'L’ancien enseignant reste dans l’historique avec son identité');
});

scenario('ENS-14', D_ENS, 'Chaque enseignant de démonstration retrouve ses affectations et sa charge hebdomadaire', async (ctx) => {
  const e = horizon(ctx);
  const prof = e.enseignants.find((p) => !p.partage);
  const s = e.enseignant(prof?.matiere);
  if (!s) ignorer('Pas d’enseignant');
  const fiche = await exiger(s, 'GET', '/espace-enseignant/affectations');
  vrai(fiche.affectations.length > 0, 'Au moins une affectation');
  vrai(fiche.affectations.every((a) => a.engagementId === prof.engagementId), 'Seulement ses propres matières');
  vrai(Number(fiche.chargeHebdomadaire) > 0, 'Charge hebdomadaire calculée');
  statut(await appel(role(e, 'SECRETARIAT'), 'GET', '/espace-enseignant/affectations'), 403);
  return `${fiche.affectations.length} matière(s), ${fiche.chargeHebdomadaire} h par semaine`;
});

scenario('STR-06', D_STR, 'Fin du lot : la matière de test se retire de la classe puis se désactive ; une matière désactivée ne s’ajoute plus', async (ctx) => {
  const e = horizon(ctx);
  const m = partage(ctx).matiere;
  if (!m) ignorer('Matière de test non créée (STR-03)');
  const cens = role(e, 'CENSEUR');
  if (m.classeId) {
    statut(await appel(cens, 'DELETE', `/classes/${m.classeId}/matieres/${m.id}`), 204);
    statut(await appel(cens, 'DELETE', `/classes/${m.classeId}/matieres/${m.id}`), 404);
  }
  const v = await exiger(cens, 'PUT', `/matieres/${m.id}`, { libelle: 'Recette : méthodologie (désactivée)', type: 'GENERALE', actif: false });
  egal(v.actif, false, 'Matière désactivée');
  statut(await appel(cens, 'PUT', `/classes/${e.classes[0].id}/matieres/${m.id}`, matiereDeClasse()), 409, 'MATIERE_INACTIVE');
});
