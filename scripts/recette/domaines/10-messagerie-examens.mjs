/**
 * Lot 10 : messagerie de l'établissement et examens nationaux (v0.46).
 *
 * Préfixes : MSG- (messagerie), EXA- (taux de réussite aux examens).
 *
 * Sources vérifiées : messagerie/MessagerieController/Service ; eleves/FamillesDestinataires ;
 * passage/ExamensController/Service.
 *
 * Données : tout se passe au lycée Horizon (n° 3). Les messages de recette portent « [Recette] » dans l'objet et
 * partent sans SMS (ils restent dans les boîtes : il n'y a pas de suppression). Les résultats d'examen saisis pour
 * l'année 2015-2016 sont supprimés à la fin.
 */
import { appel, egal, exiger, ignorer, scenario, statut, vrai } from '../lib.mjs';

const D_MSG = 'Messagerie';
const D_EXA = 'Examens nationaux';

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
  ctx.partage.messagerie ??= {};
  return ctx.partage.messagerie;
}

function enseignant(e) {
  const p = e.enseignants.find((x) => !x.partage);
  if (!p) ignorer(`Aucun enseignant au ${e.nom}`);
  return e.enseignant(p.matiere);
}

// ===================================================================== messagerie

scenario('MSG-01', D_MSG, 'Le chef d’établissement voit avant l’envoi qui sera joint : les parents sans compte ne le sont que par SMS ; les autres rôles n’écrivent pas', async (ctx) => {
  const e = horizon(ctx);
  const chef = role(e, 'ADMIN_ECOLE');
  const sans = await exiger(chef, 'POST', '/messages/apercu', { objet: 'x', corps: 'x', sms: false, groupes: ['PARENTS'] });
  const avec = await exiger(chef, 'POST', '/messages/apercu', { objet: 'x', corps: 'x', sms: true, groupes: ['PARENTS'] });
  vrai(sans.destinataires > 0 || sans.injoignables > 0, 'Des parents dans l’année en cours');
  egal(avec.comptes, sans.comptes, 'Mêmes comptes avec ou sans SMS');
  egal(avec.smsSeulement, sans.injoignables, 'Les parents sans compte sont joints par SMS');
  egal(avec.injoignables, 0, 'Plus personne d’injoignable avec le SMS');
  statut(await appel(role(e, 'SURVEILLANT'), 'POST', '/messages/apercu', { groupes: ['PARENTS'] }), 403);
  statut(await appel(enseignant(e), 'POST', '/messages', { objet: 'x', corps: 'x', groupes: ['PARENTS'] }), 403);
  statut(await appel(chef, 'POST', '/messages', { objet: '', corps: 'x', groupes: ['PARENTS'] }), 400);
  statut(await appel(chef, 'POST', '/messages', { objet: 'x', corps: 'x' }), 409, 'AUCUN_DESTINATAIRE');
  return `${sans.comptes} parent(s) avec compte, ${sans.injoignables} sans compte`;
});

scenario('MSG-02', D_MSG, 'Message aux enseignants : chacun le trouve non lu dans sa boîte, le lit ; un parent ne le voit pas ; le chef suit la lecture', async (ctx) => {
  const e = horizon(ctx);
  const chef = role(e, 'ADMIN_ECOLE');
  const prof = enseignant(e);
  const objet = `[Recette] Conseil pédagogique ${Date.now()}`;
  const avant = (await exiger(prof, 'GET', '/messages/non-lus')).nombre;
  const envoi = await exiger(chef, 'POST', '/messages', { objet, corps: 'Lundi à 15 h en salle des professeurs.', sms: false, groupes: ['ENSEIGNANTS'] });
  egal(envoi.sms, 0, 'Aucun SMS');
  vrai(envoi.destinataires > 0, 'Des enseignants joints');
  egal((await exiger(prof, 'GET', '/messages/non-lus')).nombre, avant + 1, 'Un message non lu de plus');
  const boite = await exiger(prof, 'GET', '/messages');
  const recu = boite.messages.find((m) => m.objet === objet);
  vrai(recu && !recu.lu, 'Message reçu, non lu');
  const lu = await exiger(prof, 'GET', `/messages/${recu.id}`);
  vrai(lu.luLe, 'Lu');
  egal((await exiger(prof, 'GET', '/messages/non-lus')).nombre, avant, 'Plus de non lu');
  const parent = e.parent(0);
  if (parent) {
    vrai(!(await exiger(parent, 'GET', '/messages')).messages.some((m) => m.objet === objet), 'Le parent ne le voit pas');
    statut(await appel(parent, 'GET', `/messages/${recu.id}`), 404);
  }
  const envoyes = await exiger(chef, 'GET', '/messages/envoyes');
  const suivi = envoyes.find((m) => m.id === envoi.id);
  vrai(suivi && suivi.lus >= 1, 'Le chef voit au moins une lecture');
  etat(ctx).envoi = envoi.id;
  return `${envoi.destinataires} enseignant(s), ${suivi.lus} lecture(s)`;
});

scenario('MSG-03', D_MSG, 'Message à tous les parents (sans SMS) : le parent le lit dans son espace ; l’annuaire distingue les parents sans compte', async (ctx) => {
  const e = horizon(ctx);
  const chef = role(e, 'ADMIN_ECOLE');
  const parent = e.parent(0);
  if (!parent) ignorer('Aucun parent avec espace parent au lycée Horizon');
  const objet = `[Recette] Information aux familles ${Date.now()}`;
  await exiger(chef, 'POST', '/messages', { objet, corps: 'Les bulletins sont disponibles.', sms: false, groupes: ['PARENTS'] });
  const boite = await exiger(parent, 'GET', '/messages');
  vrai(boite.messages.some((m) => m.objet === objet), 'Le parent a reçu le message');
  const annuaire = await exiger(chef, 'GET', '/messages/annuaire');
  vrai(annuaire.some((p) => p.categorie === 'ENSEIGNANT') && annuaire.some((p) => p.categorie.startsWith('PARENT')), 'Personnel et parents dans l’annuaire');
  vrai(annuaire.filter((p) => p.categorie === 'PARENT_SANS_COMPTE').every((p) => !p.utilisateurId && p.responsableId), 'Parents sans compte repérés');
  statut(await appel(role(e, 'SECRETARIAT'), 'GET', '/messages/annuaire'), 403);
  return `${annuaire.length} personne(s) dans l’annuaire`;
});

// ===================================================================== examens

scenario('EXA-01', D_EXA, 'Taux de réussite : lus par la direction ; une année antérieure se saisit, ne se double pas, se supprime ; l’export s’ouvre', async (ctx) => {
  const e = horizon(ctx);
  const chef = role(e, 'ADMIN_ECOLE');
  await exiger(role(e, 'CENSEUR'), 'GET', '/examens/resultats');
  statut(await appel(enseignant(e), 'GET', '/examens/resultats'), 403);
  const saisie = { annee: '2015-2016', examen: 'Recette Exam', candidats: 80, admis: 52, candidatesFilles: 30, admisesFilles: 21 };
  statut(await appel(role(e, 'CENSEUR'), 'POST', '/examens/historique', saisie), 403);
  statut(await appel(chef, 'POST', '/examens/historique', { ...saisie, admis: 90 }), 400);
  statut(await appel(chef, 'POST', '/examens/historique', { ...saisie, annee: '2015-2017' }), 400);
  // Reprise d'un passage précédent interrompu
  const avant = await exiger(chef, 'GET', '/examens/resultats');
  for (const r of avant.resultats.filter((x) => x.examen === 'RECETTE EXAM' && x.historiqueId)) {
    await exiger(chef, 'DELETE', `/examens/historique/${r.historiqueId}`);
  }
  const t = await exiger(chef, 'POST', '/examens/historique', saisie);
  const ligne = t.resultats.find((r) => r.examen === 'RECETTE EXAM' && r.annee === '2015-2016');
  egal([ligne?.source, ligne?.taux, ligne?.tauxFilles], ['SAISI', 65, 70], 'Taux calculés');
  statut(await appel(chef, 'POST', '/examens/historique', saisie), 409, 'EXAMEN_DEJA_SAISI');
  const xlsx = await exiger(chef, 'GET', '/examens/resultats/export', undefined, { fichier: true, params: { format: 'xlsx' } });
  vrai(xlsx.octets.subarray(0, 2).toString() === 'PK', 'Export Excel');
  const apres = await exiger(chef, 'DELETE', `/examens/historique/${ligne.historiqueId}`);
  vrai(!apres.resultats.some((r) => r.examen === 'RECETTE EXAM'), 'Saisie de recette supprimée');
  const calcules = avant.resultats.filter((r) => r.source === 'CALCULE');
  return `${calcules.length} résultat(s) calculé(s) par la plateforme`;
});
