/**
 * Lot 16 (v0.49) : commission de recrutement (complément d'effectif).
 *
 * Préfixe : REC-.
 *
 * Sources vérifiées : recrutement/RecrutementService, RecrutementController, DocumentsRecrutement, Vues ;
 * docs/API_RECRUTEMENT.md.
 *
 * Données : lycée technique Les Bâtisseurs, année active. Chaque passage crée une commission (CR-AAAA-NN) avec
 * une place sur un niveau, quatre candidatures « RECETTE » et inscrit le candidat repêché (mode COMPLEMENT_EFFECTIF) :
 * un élève RECETTE de plus dans la classe à chaque passage. Si toutes les classes du niveau sont complètes, REC-05
 * est ignoré.
 */
import { ajouterJours, appel, egal, exiger, ignorer, refuse, scenario, statut, telephoneUnique, vrai } from '../lib.mjs';

const D = 'Commission de recrutement';

function partage(ctx) {
  ctx.partage.recrutement ??= {};
  return ctx.partage.recrutement;
}

async function lycee(ctx) {
  const p = partage(ctx);
  if (p.e) return p;
  const e = ctx.ecole('lt-batisseurs') ?? ignorer('lycée technique absent de la base de démonstration');
  const proviseur = e.session('ADMIN_ECOLE') ?? ignorer('pas de chef d’établissement');
  const annees = await exiger(proviseur, 'GET', '/annees');
  const annee = annees.find((a) => a.etat === 'ACTIVE') ?? ignorer('aucune année active');
  Object.assign(p, {
    e, annee, proviseur,
    censeur: e.session('CENSEUR'),
    secretariat: e.session('SECRETARIAT'),
    intendant: e.session('INTENDANT'),
    cpe: e.session('SURVEILLANT'),
    enseignant: e.enseignants?.[0] ? e.enseignant(e.enseignants[0].matiere) : null,
  });
  return p;
}

function commissionCourante(ctx) {
  return partage(ctx).commission ?? ignorer('REC-01 en échec');
}

/** Date de naissance tirée au hasard (évite les doublons d'élèves d'un passage à l'autre). */
function naissance() {
  return ajouterJours('2009-01-01', Math.floor(Math.random() * 700));
}

function candidat(offreId, nom, prenoms, moyenne, autres = {}) {
  return {
    offreId, nom, prenoms, sexe: 'F', dateNaissance: naissance(), lieuNaissance: 'Koudougou',
    responsableNom: nom, responsablePrenoms: 'Responsable', responsableTelephone: telephoneUnique('6'),
    responsableLien: 'MERE', etablissementOrigine: 'CEG de recette', classeOrigine: '3e', moyenneAnnuelle: moyenne,
    noteExamen: null, redoublant: false, observations: '[Recette]', ...autres,
  };
}

scenario('REC-01', D, 'Le chef d’établissement crée la commission : président, vice-président (censeur), rapporteur (CPE), intendant et représentant de l’APE proposés ; un enseignant non membre n’y a pas accès', async (ctx) => {
  const { annee, proviseur, censeur, enseignant } = await lycee(ctx);
  const corps = {
    intitule: '[Recette] Complément d’effectif', dateSession: ctx.aujourdhui, lieu: 'Salle des professeurs',
    dateLimiteDepot: null, criteres: 'Dossier complet', moyenneMinimale: 10, poidsExamen: 0, tailleListeAttente: 2,
  };
  const chemin = `/annees/${annee.id}/commissions-recrutement`;
  if (censeur) refuse(await appel(censeur, 'POST', chemin, corps), 'Seul le président crée la commission');
  const c = await exiger(proviseur, 'POST', chemin, corps);
  vrai(/^CR-\d{4}-\d{2,}$/.test(c.numero ?? ''), `Numéro : ${c.numero}`);
  egal(c.statut, 'PREPARATION', 'Statut initial');
  const qualites = c.membres.map((m) => m.qualite);
  vrai(qualites.includes('PRESIDENT'), 'Président proposé');
  if (censeur) vrai(qualites.includes('VICE_PRESIDENT'), 'Vice-président proposé (censeur)');
  if (ctx.ecole('lt-batisseurs').session('SURVEILLANT')) vrai(qualites.includes('RAPPORTEUR'), 'Rapporteur proposé (CPE)');
  vrai(c.membres.some((m) => m.fonction.includes('APE')), 'Représentant de l’APE à désigner');
  vrai(c.offres.length > 0, `${c.offres.length} niveau(x) proposé(s) d’après les classes`);
  vrai(c.droits.deliberer, 'Le président délibère');
  if (enseignant) refuse(await appel(enseignant, 'GET', `/commissions-recrutement/${c.id}`), 'Enseignant non membre');
  const liste = await exiger(proviseur, 'GET', chemin);
  vrai(liste.some((x) => x.id === c.id), 'Dans la liste de l’année');
  partage(ctx).commission = c;
});

scenario('REC-02', D, 'Places offertes : au plus les places disponibles des classes du niveau ; une place sur un niveau', async (ctx) => {
  const { proviseur } = await lycee(ctx);
  const c = commissionCourante(ctx);
  const o = c.offres.find((x) => x.disponibles === null || x.disponibles >= 1) ?? ignorer('aucun niveau avec une place disponible');
  if (o.disponibles !== null) {
    statut(await appel(proviseur, 'PUT', `/commissions-recrutement/${c.id}/offres`,
      { offres: [{ offreId: o.id, places: o.disponibles + 1 }] }), 409, 'PLACES_INSUFFISANTES');
  }
  const v = await exiger(proviseur, 'PUT', `/commissions-recrutement/${c.id}/offres`,
    { offres: c.offres.map((x) => ({ offreId: x.id, places: x.id === o.id ? 1 : 0 })) });
  egal(v.offres.find((x) => x.id === o.id).places, 1, `Une place en ${o.niveau} ${o.filiere}`);
  partage(ctx).offre = o;
});

scenario('REC-03', D, 'Candidatures saisies par le secrétariat ; l’intendant ne saisit pas ; le censeur déclare une candidature irrecevable ; aperçu du classement au mérite', async (ctx) => {
  const { proviseur, censeur, secretariat, intendant } = await lycee(ctx);
  const c = commissionCourante(ctx);
  const o = partage(ctx).offre ?? ignorer('REC-02 en échec');
  const saisie = secretariat ?? proviseur;
  const chemin = `/commissions-recrutement/${c.id}/candidatures`;
  statut(await appel(saisie, 'POST', chemin, candidat(o.id, 'RECETTE', 'Erreur', 21)), 400);
  if (intendant) refuse(await appel(intendant, 'POST', chemin, candidat(o.id, 'RECETTE', 'Intendance', 12)));
  const a = await exiger(saisie, 'POST', chemin, candidat(o.id, 'RECETTE', 'Alpha', 15));
  const b = await exiger(saisie, 'POST', chemin, candidat(o.id, 'RECETTE', 'Bravo', 13));
  const k = await exiger(saisie, 'POST', chemin, candidat(o.id, 'RECETTE', 'Charlie', 9));
  const x = await exiger(saisie, 'POST', chemin, candidat(o.id, 'RECETTE', 'Xray', 17));
  vrai(/^CAND-\d{4}-\d{4}$/.test(a.numero ?? ''), `Numéro : ${a.numero}`);
  const juge = censeur ?? proviseur;
  statut(await appel(juge, 'POST', `/candidatures/${x.id}/recevabilite`, { recevable: false }), 400);
  egal((await exiger(juge, 'POST', `/candidatures/${x.id}/recevabilite`,
    { recevable: false, motif: '[Recette] Dossier incomplet' })).statut, 'IRRECEVABLE', 'Irrecevable');
  const cl = (await exiger(proviseur, 'GET', `/commissions-recrutement/${c.id}/classement`)).find((l) => l.offreId === o.id);
  const ordre = cl.lignes.filter((l) => l.rang !== null).map((l) => l.prenoms);
  egal(ordre.slice(0, 2).join(','), 'Alpha,Bravo', 'Ordre au mérite');
  egal(cl.lignes.find((l) => l.prenoms === 'Charlie').decision, 'NON_RETENUE', 'Sous la moyenne minimale');
  Object.assign(partage(ctx), { a, b, k, x });
});

scenario('REC-04', D, 'Le président délibère (pas le censeur) : retenu, liste d’attente, non retenu figés ; procès-verbal PDF et résultats Excel ; plus de saisie ensuite', async (ctx) => {
  const { proviseur, censeur, secretariat } = await lycee(ctx);
  const c = commissionCourante(ctx);
  const { a, b, k, x } = partage(ctx);
  if (!a) ignorer('REC-03 en échec');
  statut(await appel(proviseur, 'GET', `/commissions-recrutement/${c.id}/proces-verbal`, undefined, { fichier: true }), 409, 'COMMISSION_NON_DELIBEREE');
  if (censeur) refuse(await appel(censeur, 'POST', `/commissions-recrutement/${c.id}/deliberation`, { observations: null }));
  const v = await exiger(proviseur, 'POST', `/commissions-recrutement/${c.id}/deliberation`,
    { observations: '[Recette] Délibération sans réserve.' });
  egal(v.statut, 'DELIBEREE', 'Délibérée');
  const l = await exiger(proviseur, 'GET', `/commissions-recrutement/${c.id}/candidatures`);
  const s = (id) => l.find((y) => y.id === id)?.statut;
  egal(s(a.id), 'RETENUE', 'Alpha retenue');
  egal(s(b.id), 'LISTE_ATTENTE', 'Bravo sur la liste d’attente');
  egal(s(k.id), 'NON_RETENUE', 'Charlie non retenue');
  egal(s(x.id), 'IRRECEVABLE', 'Xray irrecevable');
  const pdf = await exiger(proviseur, 'GET', `/commissions-recrutement/${c.id}/proces-verbal`, undefined, { fichier: true });
  egal(pdf.octets.subarray(0, 5).toString(), '%PDF-', 'Procès-verbal en PDF');
  const xlsx = await exiger(proviseur, 'GET', `/commissions-recrutement/${c.id}/resultats`, undefined,
    { fichier: true, params: { format: 'xlsx' } });
  egal(xlsx.octets.subarray(0, 2).toString(), 'PK', 'Résultats en Excel');
  statut(await appel(secretariat ?? proviseur, 'PUT', `/candidatures/${b.id}`, candidat(b.offreId, 'RECETTE', 'Bravo', 18)), 409, 'COMMISSION_DELIBEREE');
});

scenario('REC-05', D, 'Désistement du retenu : le premier de la liste d’attente est repêché ; l’intendance l’inscrit (complément d’effectif) ; la commission close n’inscrit plus', async (ctx) => {
  const { proviseur, intendant, secretariat } = await lycee(ctx);
  const c = commissionCourante(ctx);
  const { a, b, k } = partage(ctx);
  if (!a) ignorer('REC-03 en échec');
  const inscription = intendant ?? proviseur;
  const r = await exiger(inscription, 'POST', `/candidatures/${a.id}/desistement`, { motif: '[Recette] Inscrit ailleurs' });
  egal(r.desistee.statut, 'DESISTEE', 'Désistement');
  egal(r.repechee?.id, b.id, 'Bravo repêchée');
  vrai(r.repechee.repechee === true, 'Marquée repêchée');
  const v = await exiger(proviseur, 'GET', `/commissions-recrutement/${c.id}`);
  const offre = v.offres.find((o) => o.id === b.offreId);
  const classe = offre.classes.find((x) => x.effectifMax === null || x.inscrits < x.effectifMax) ?? ignorer('classes du niveau complètes');
  if (secretariat) refuse(await appel(secretariat, 'POST', `/candidatures/${b.id}/inscription`, { classeId: classe.id }));
  statut(await appel(inscription, 'POST', `/candidatures/${k.id}/inscription`, { classeId: classe.id }), 409, 'CANDIDAT_NON_RETENU');
  const ins = await exiger(inscription, 'POST', `/candidatures/${b.id}/inscription`, { classeId: classe.id });
  egal(ins.statut, 'INSCRITE', 'Inscrite');
  vrai(!!ins.inscriptionId, `Inscription en ${ins.classeInscription}`);
  egal((await exiger(proviseur, 'POST', `/commissions-recrutement/${c.id}/cloture`, {})).statut, 'CLOSE', 'Commission close');
  statut(await appel(inscription, 'POST', `/candidatures/${a.id}/desistement`, {}), 409);
});
