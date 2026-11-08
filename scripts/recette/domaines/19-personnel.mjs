/**
 * Lot 19 (v0.51) : dossier administratif du personnel.
 *
 * Préfixe : PER-.
 *
 * Sources vérifiées : personnel/DossiersService, ActesService, AbsencesPersonnelService, PersonnelController ;
 * docs/API_PERSONNEL.md.
 *
 * Données : le lycée technique Les Bâtisseurs de la démonstration. Le lot ouvre les dossiers manquants (reprise
 * sans effet si elle a déjà été faite) ; la demande d'absence créée est retirée aussitôt.
 */
import { appel, egal, exiger, ignorer, refuse, scenario, statut, vrai } from '../lib.mjs';

const D = 'Dossier du personnel';

function ecole(ctx) {
  return ctx.ecole('lt-batisseurs') ?? ignorer('lycée technique absent de la base de démonstration');
}

scenario('PER-01', D, 'Le secrétariat ouvre les dossiers de tous les comptes du personnel ; une seconde reprise n’ajoute rien ; le censeur consulte sans modifier', async (ctx) => {
  const e = ecole(ctx);
  const secretariat = e.session('SECRETARIAT') ?? ignorer('pas de secrétariat');
  await exiger(secretariat, 'POST', '/personnel/reprise');
  egal((await exiger(secretariat, 'POST', '/personnel/reprise')).crees, 0, 'seconde reprise sans effet');
  const liste = await exiger(secretariat, 'GET', '/personnel');
  egal(liste.comptesSansDossier, 0, 'plus aucun compte sans dossier');
  vrai(liste.agents.some((a) => a.categorie === 'ENSEIGNANT'), 'les enseignants ont leur dossier');
  const censeur = e.session('CENSEUR') ?? ignorer('pas de censeur');
  const d = await exiger(censeur, 'GET', `/personnel/${liste.agents[0].id}`);
  egal(d.droits.modifier, false, 'le censeur ne modifie pas');
  refuse(await appel(censeur, 'POST', '/personnel/reprise'), 'Reprise réservée à la gestion');
});

scenario('PER-02', D, 'Un enseignant consulte son dossier, sans les observations internes, et ne voit pas celui d’un collègue', async (ctx) => {
  const e = ecole(ctx);
  const secretariat = e.session('SECRETARIAT') ?? ignorer('pas de secrétariat');
  await exiger(secretariat, 'POST', '/personnel/reprise');
  const enseignants = e.enseignants.map((x) => e.enseignant(x.matiere)).filter(Boolean);
  if (enseignants.length < 2) ignorer('un seul enseignant');
  const moi = await exiger(enseignants[0], 'GET', '/mon-dossier');
  vrai(moi.observations === null || moi.observations === undefined, 'pas d’observations internes');
  vrai(moi.actes.every((a) => a.signeLe), 'seulement les actes signés');
  const autre = await exiger(enseignants[1], 'GET', '/mon-dossier');
  if (autre.id !== moi.id) {
    refuse(await appel(enseignants[0], 'GET', `/personnel/${autre.id}`), 'Le dossier d’un collègue est refusé');
  }
  refuse(await appel(enseignants[0], 'GET', '/personnel'), 'La liste du personnel est refusée à un enseignant');
});

scenario('PER-03', D, 'Un enseignant demande une absence ; une demande qui chevauche est refusée ; il la retire', async (ctx) => {
  const e = ecole(ctx);
  await exiger(e.session('SECRETARIAT') ?? ignorer('pas de secrétariat'), 'POST', '/personnel/reprise');
  const ens = e.enseignants.map((x) => e.enseignant(x.matiere)).find(Boolean) ?? ignorer('aucun enseignant');
  const an = new Date().getFullYear() + 2;
  const demande = { type: 'AUTORISATION_ABSENCE', debut: `${an}-03-10`, fin: `${an}-03-12`, motif: 'Recette : à retirer' };
  const b = await exiger(ens, 'POST', '/mon-dossier/absences', demande);
  try {
    egal(b.statut, 'DEMANDEE', 'demande enregistrée');
    egal(b.jours, 3, 'trois jours');
    statut(await appel(ens, 'POST', '/mon-dossier/absences', { ...demande, debut: `${an}-03-11`, fin: `${an}-03-14` }), [409],
      'ABSENCE_CHEVAUCHEMENT');
    refuse(await appel(ens, 'POST', `/personnel/absences/${b.id}/decision`, { accorder: true }), 'Seul le chef d’établissement décide');
  } finally {
    egal((await exiger(ens, 'POST', `/personnel/absences/${b.id}/annulation`, {})).statut, 'ANNULEE', 'demande retirée');
  }
});
