/**
 * Lot 12 : secrétariat de l'établissement (v0.47.1).
 *
 * Préfixes : COU- (courrier), ADM- (documents administratifs et tableau d'affichage), DEM- (demandes du personnel),
 * ACC- (accueil et téléphone), REU- (réunions), ARC- (archives), CER- (certificats et dossiers d'examen).
 *
 * Sources vérifiées : secretariat/CourrierService, DocumentsService, DemandesService, AccueilService,
 * ReunionsService, ArchivesService, CertificatsService ; SecretariatController, DocumentsController.
 *
 * Données : lycée Horizon (n° 3). Ce que le lot laisse : des courriers « [Recette] … » au registre (un registre ne
 * s'efface pas), une note de service « [Recette] » signée puis annulée, une demande close, deux passages à
 * l'accueil, un certificat de scolarité au registre des certificats. Brouillons, réunion et pièce archivée sont
 * supprimés à la fin de leur scénario.
 */
import { appel, egal, exiger, ignorer, scenario, statut, vrai } from '../lib.mjs';

const D_COU = 'Courrier';
const D_ADM = 'Documents administratifs';
const D_DEM = 'Demandes du personnel';
const D_ACC = 'Accueil';
const D_REU = 'Réunions';
const D_ARC = 'Archives';
const D_CER = 'Certificats et examens';

const PDF = Buffer.from('%PDF-1.4\n% recette\n', 'latin1');

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

function etat(ctx) {
  ctx.partage.secretariat ??= {};
  return ctx.partage.secretariat;
}

function pdf(nom = 'piece.pdf') {
  const f = new FormData();
  f.append('fichier', new Blob([PDF], { type: 'application/pdf' }), nom);
  return f;
}

async function membre(e, r) {
  const liste = await exiger(role(e, 'SECRETARIAT'), 'GET', '/secretariat/personnel');
  const fonction = { CENSEUR: 'Censeur', INTENDANT: 'Intendant' }[r];
  const p = liste.find((x) => x.fonctions.includes(fonction));
  if (!p) ignorer(`Pas de ${fonction} au ${e.nom}`);
  return p;
}

// ===================================================================== courrier

scenario('COU-01', D_COU, 'La secrétaire enregistre un courrier arrivé (numéro A-année) et joint la pièce ; l’intendant ne l’enregistre pas ; date future refusée', async (ctx) => {
  const e = horizon(ctx);
  const sec = role(e, 'SECRETARIAT');
  const corps = { sens: 'ARRIVEE', correspondant: 'DPEPPNF (recette)', categorie: 'DIRECTION_PROVINCIALE', objet: `[Recette] Calendrier des examens ${Date.now()}` };
  statut(await appel(role(e, 'INTENDANT'), 'POST', '/courriers', corps), 403);
  statut(await appel(sec, 'POST', '/courriers', { ...corps, dateCourrier: '2999-01-01' }), 400);
  const c = await exiger(sec, 'POST', '/courriers', corps);
  vrai(/^A-\d{4}-\d{4}$/.test(c.numero), `Numéro ${c.numero}`);
  egal(c.statut, 'ENREGISTRE', 'Statut');
  statut(await appel(sec, 'POST', `/courriers/${c.id}/fichier`, (() => { const f = new FormData(); f.append('fichier', new Blob(['texte'], { type: 'text/plain' }), 'x.txt'); return f; })()), 400);
  const avec = await exiger(sec, 'POST', `/courriers/${c.id}/fichier`, pdf('lettre.pdf'));
  egal(avec.fichierNom, 'lettre.pdf', 'Pièce jointe');
  etat(ctx).courrier = c;
  return c.numero;
});

scenario('COU-02', D_COU, 'Le courrier transmis à l’intendance apparaît chez l’intendant, qui le marque traité mais ne le classe pas ; le censeur ne le voit pas', async (ctx) => {
  const e = horizon(ctx);
  const c = etat(ctx).courrier;
  if (!c) ignorer('Courrier non enregistré (COU-01)');
  const sec = role(e, 'SECRETARIAT');
  const int = role(e, 'INTENDANT');
  vrai(!(await exiger(int, 'GET', '/courriers')).some((x) => x.id === c.id), 'Pas encore transmis : invisible');
  await exiger(sec, 'POST', `/courriers/${c.id}/suivi`, { statut: 'TRANSMIS', service: 'INTENDANCE' });
  vrai((await exiger(int, 'GET', '/courriers')).some((x) => x.id === c.id), 'Visible une fois transmis');
  await exiger(int, 'GET', `/courriers/${c.id}/fichier`, undefined, { fichier: true });
  statut(await appel(role(e, 'CENSEUR'), 'GET', `/courriers/${c.id}`), 403);
  statut(await appel(int, 'POST', `/courriers/${c.id}/suivi`, { statut: 'CLASSE' }), 403);
  const t = await exiger(int, 'POST', `/courriers/${c.id}/suivi`, { statut: 'TRAITE', suivi: 'Recette : vu' });
  egal(t.statut, 'TRAITE', 'Traité');
  egal((await exiger(sec, 'POST', `/courriers/${c.id}/suivi`, { statut: 'CLASSE' })).statut, 'CLASSE', 'Classé par le secrétariat');
});

scenario('COU-03', D_COU, 'Le registre du courrier de l’année s’imprime (Excel, PDF) ; un enseignant ne l’obtient pas', async (ctx) => {
  const e = horizon(ctx);
  const sec = role(e, 'SECRETARIAT');
  const x = await exiger(sec, 'GET', '/courriers/registre', undefined, { fichier: true, params: { format: 'xlsx' } });
  vrai(x.octets.subarray(0, 2).toString() === 'PK', 'Registre Excel');
  const p = await exiger(sec, 'GET', '/courriers/registre', undefined, { fichier: true, params: { format: 'pdf' } });
  vrai(p.octets.subarray(0, 4).toString() === '%PDF', 'Registre PDF');
  const prof = e.enseignant(e.enseignants.find((y) => !y.partage)?.matiere);
  if (prof) statut(await appel(prof, 'GET', '/courriers/registre'), 403);
});

// ===================================================================== documents administratifs

scenario('ADM-01', D_ADM, 'Note de service : brouillon par la secrétaire, signée par le chef (numéro attribué, texte figé), affichée pour tous, lue par un parent, puis annulée', async (ctx) => {
  const e = horizon(ctx);
  const sec = role(e, 'SECRETARIAT');
  const chef = role(e, 'ADMIN_ECOLE');
  const d = await exiger(sec, 'POST', '/documents-administratifs', {
    type: 'NOTE_SERVICE', titre: `[Recette] Horaires ${Date.now()}`, corps: 'Les cours se terminent à 12 h.',
  });
  egal([d.statut, d.numero], ['BROUILLON', null], 'Brouillon sans numéro');
  statut(await appel(sec, 'POST', `/documents-administratifs/${d.id}/signature`), 403);
  statut(await appel(sec, 'PUT', `/documents-administratifs/${d.id}/affichage`, { affichage: 'TOUS', au: '2099-01-01' }), 409, 'DOCUMENT_NON_SIGNE');
  const s = await exiger(chef, 'POST', `/documents-administratifs/${d.id}/signature`);
  vrai(/^\d{4}-\d{4}\/NS$/.test(s.numero), `Numéro ${s.numero}`);
  statut(await appel(sec, 'PUT', `/documents-administratifs/${d.id}`, { type: 'NOTE_SERVICE', titre: 'X', corps: 'X' }), 409, 'DOCUMENT_SIGNE');
  const fin = new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 10);
  await exiger(sec, 'PUT', `/documents-administratifs/${d.id}/affichage`, { affichage: 'TOUS', au: fin });
  const parent = e.parent(0);
  if (parent) {
    vrai((await exiger(parent, 'GET', '/affichage')).some((a) => a.id === d.id), 'Le parent voit la note');
    const p = await exiger(parent, 'GET', `/documents-administratifs/${d.id}/pdf`, undefined, { fichier: true });
    vrai(p.octets.subarray(0, 4).toString() === '%PDF', 'PDF lu par le parent');
  }
  const a = await exiger(chef, 'POST', `/documents-administratifs/${d.id}/annulation`);
  egal([a.statut, a.affichage], ['ANNULE', null], 'Annulée et retirée de l’affichage');
  if (parent) statut(await appel(parent, 'GET', `/documents-administratifs/${d.id}/pdf`), 404);
  return s.numero;
});

scenario('ADM-02', D_ADM, 'Un brouillon se modifie puis se supprime ; la liste des documents est réservée au secrétariat et au chef', async (ctx) => {
  const e = horizon(ctx);
  const sec = role(e, 'SECRETARIAT');
  const d = await exiger(sec, 'POST', '/documents-administratifs', { type: 'COMMUNIQUE', titre: '[Recette] Brouillon', corps: 'Texte' });
  const m = await exiger(sec, 'PUT', `/documents-administratifs/${d.id}`, { type: 'COMMUNIQUE', titre: '[Recette] Brouillon modifié', corps: 'Texte 2' });
  egal(m.titre, '[Recette] Brouillon modifié', 'Modifié');
  statut(await appel(role(e, 'CENSEUR'), 'GET', '/documents-administratifs'), 403);
  statut(await appel(sec, 'DELETE', `/documents-administratifs/${d.id}`), 204);
  statut(await appel(sec, 'GET', `/documents-administratifs/${d.id}`), 404);
});

// ===================================================================== demandes du personnel

scenario('DEM-01', D_DEM, 'Demande d’autorisation d’absence du censeur : reçue, transmise, avis favorable du chef, transmise à la DPEPPNF (courrier départ), close ; le censeur la suit', async (ctx) => {
  const e = horizon(ctx);
  const sec = role(e, 'SECRETARIAT');
  const chef = role(e, 'ADMIN_ECOLE');
  const censeur = await membre(e, 'CENSEUR');
  const jour = new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10);
  statut(await appel(sec, 'POST', '/demandes-administratives', { type: 'AUTORISATION_ABSENCE', demandeurId: censeur.utilisateurId, objet: 'Sans période' }), 400);
  const d = await exiger(sec, 'POST', '/demandes-administratives', {
    type: 'AUTORISATION_ABSENCE', demandeurId: censeur.utilisateurId, objet: '[Recette] Obsèques', du: jour, au: jour,
  });
  vrai(/^R-\d{4}-\d{4}$/.test(d.numero), `Numéro ${d.numero}`);
  await exiger(sec, 'POST', `/demandes-administratives/${d.id}/transmission`);
  statut(await appel(sec, 'POST', `/demandes-administratives/${d.id}/avis`, { favorable: true }), 403);
  statut(await appel(sec, 'POST', `/demandes-administratives/${d.id}/cloture`, { suite: 'X' }), 409, 'AVIS_ATTENDU');
  statut(await appel(chef, 'POST', `/demandes-administratives/${d.id}/avis`, { favorable: false }), 400);
  await exiger(chef, 'POST', `/demandes-administratives/${d.id}/avis`, { favorable: true, avis: 'Recette : accordé' });
  const t = await exiger(sec, 'POST', `/demandes-administratives/${d.id}/transmission-hierarchie`, { destinataire: 'DPEPPNF (recette)' });
  vrai(/^D-\d{4}-\d{4}$/.test(t.courrierNumero ?? ''), 'Courrier de départ enregistré');
  const miennes = await exiger(role(e, 'CENSEUR'), 'GET', '/demandes-administratives/miennes');
  vrai(miennes.some((x) => x.id === d.id && x.statut === 'TRANSMISE_HIERARCHIE'), 'Le censeur suit sa demande');
  statut(await appel(role(e, 'CENSEUR'), 'GET', '/demandes-administratives'), 403);
  egal((await exiger(sec, 'POST', `/demandes-administratives/${d.id}/cloture`, { suite: 'Recette : accordée' })).statut, 'CLOTUREE', 'Close');
  return `${d.numero}, courrier ${t.courrierNumero}`;
});

// ===================================================================== accueil

scenario('ACC-01', D_ACC, 'Appel pour l’intendant : le message lui arrive dans sa messagerie ; une visite orientée a son heure de départ', async (ctx) => {
  const e = horizon(ctx);
  const sec = role(e, 'SECRETARIAT');
  const intendant = await membre(e, 'INTENDANT');
  const avant = (await exiger(role(e, 'INTENDANT'), 'GET', '/messages/non-lus')).nombre;
  const a = await exiger(sec, 'POST', '/accueil', {
    canal: 'APPEL', nom: '[Recette] M. SAWADOGO', qualite: 'PARENT', objet: 'Frais de scolarité',
    personneId: intendant.utilisateurId, message: 'Rappeler avant 17 h', urgent: true, transmettre: true,
  });
  vrai(a.transmisLe, 'Message transmis');
  egal((await exiger(role(e, 'INTENDANT'), 'GET', '/messages/non-lus')).nombre, avant + 1, 'Un message non lu de plus chez l’intendant');
  const v = await exiger(sec, 'POST', '/accueil', { canal: 'VISITE', nom: '[Recette] Mme KABORE', qualite: 'PARENT', objet: 'Inscription', service: 'INTENDANCE' });
  statut(await appel(sec, 'POST', `/accueil/${v.id}/transmission`), 409, 'SANS_DESTINATAIRE');
  vrai((await exiger(sec, 'POST', `/accueil/${v.id}/depart`)).depart, 'Heure de départ');
  statut(await appel(role(e, 'CENSEUR'), 'GET', '/accueil'), 403);
});

// ===================================================================== réunions

scenario('REU-01', D_REU, 'Compte rendu : préparé par la secrétaire, validé par le chef seulement après la réunion, lu par le censeur ; un brouillon se supprime', async (ctx) => {
  const e = horizon(ctx);
  const sec = role(e, 'SECRETARIAT');
  const chef = role(e, 'ADMIN_ECOLE');
  const demain = new Date(Date.now() + 86400000).toISOString().slice(0, 10);
  const r = await exiger(sec, 'POST', '/reunions', { instance: 'CONSEIL_ETABLISSEMENT', titre: '[Recette] Conseil', date: demain, compteRendu: 'Séance ouverte à 9 h.' });
  statut(await appel(sec, 'POST', `/reunions/${r.id}/validation`), 403);
  statut(await appel(chef, 'POST', `/reunions/${r.id}/validation`), 409, 'REUNION_A_VENIR');
  const p = await exiger(role(e, 'CENSEUR'), 'GET', `/reunions/${r.id}/pdf`, undefined, { fichier: true });
  vrai(p.octets.subarray(0, 4).toString() === '%PDF', 'Compte rendu en PDF');
  statut(await appel(role(e, 'INTENDANT'), 'GET', '/reunions'), 403);
  statut(await appel(sec, 'DELETE', `/reunions/${r.id}`), 204);
});

// ===================================================================== archives

scenario('ARC-01', D_ARC, 'Pièce archivée au dossier d’un élève puis retirée ; personne inconnue et lecture par le censeur refusées', async (ctx) => {
  const e = horizon(ctx);
  const sec = role(e, 'SECRETARIAT');
  const c = e.classes[0];
  const ins = (await exiger(sec, 'GET', `/classes/${c.id}/inscriptions`))[0];
  if (!ins) ignorer(`Aucun élève en ${c.code}`);
  const f = pdf('acte.pdf');
  for (const [k, v] of Object.entries({ dossier: 'ELEVE', eleveId: ins.eleveId, categorie: 'État civil', titre: '[Recette] Acte de naissance' })) f.append(k, v);
  const p = await exiger(sec, 'POST', '/archives', f);
  vrai((await exiger(sec, 'GET', '/archives', undefined, { params: { dossier: 'ELEVE', eleveId: ins.eleveId } })).some((x) => x.id === p.id), 'Dans le dossier');
  statut(await appel(role(e, 'CENSEUR'), 'GET', `/archives/${p.id}/fichier`), 403);
  const g = pdf();
  for (const [k, v] of Object.entries({ dossier: 'PERSONNEL', utilisateurId: '00000000-0000-0000-0000-000000000000', categorie: 'Carrière', titre: 'X' })) g.append(k, v);
  statut(await appel(sec, 'POST', '/archives', g), 400);
  statut(await appel(sec, 'DELETE', `/archives/${p.id}`), 204);
});

// ===================================================================== certificats et examens

scenario('CER-01', D_CER, 'Certificat de scolarité numéroté (CS-année) et inscrit au registre ; liste des candidats d’une classe avec ce qui manque à leur dossier', async (ctx) => {
  const e = horizon(ctx);
  const sec = role(e, 'SECRETARIAT');
  const c = e.classes[0];
  const ins = (await exiger(sec, 'GET', `/classes/${c.id}/inscriptions`))[0];
  if (!ins) ignorer(`Aucun élève en ${c.code}`);
  const cert = await exiger(sec, 'POST', `/inscriptions/${ins.id}/certificat-scolarite`, undefined, { fichier: true });
  vrai(cert.octets.subarray(0, 4).toString() === '%PDF', 'Certificat en PDF');
  const registre = await exiger(sec, 'GET', '/certificats-scolarite');
  vrai(registre[0]?.inscriptionId === ins.id && /^CS-\d{4}-\d{4}$/.test(registre[0].numero), 'Au registre des certificats');
  statut(await appel(role(e, 'INTENDANT'), 'POST', `/inscriptions/${ins.id}/certificat-scolarite`), 403);
  const candidats = await exiger(sec, 'GET', '/examens/candidats', undefined, { params: { classes: c.id } });
  vrai(candidats.length > 0 && candidats.every((x) => Array.isArray(x.manquants)), 'Candidats et pièces manquantes');
  const x = await exiger(role(e, 'CENSEUR'), 'GET', '/examens/candidats/export', undefined, { fichier: true, params: { classes: c.id, examen: 'BAC', format: 'xlsx' } });
  vrai(x.octets.subarray(0, 2).toString() === 'PK', 'Liste Excel');
  return `${registre[0].numero} ; ${candidats.length} candidat(s) en ${c.code}, ${candidats.filter((y) => y.manquants.length).length} incomplet(s)`;
});
