/**
 * Lot 18 (v0.50) : bilan des heures dues et réalisées, notifications aux parents dans l'application.
 *
 * Préfixes : HEU- (bilan des heures), NOT- (notifications aux familles).
 *
 * Sources vérifiées : progression/BilanHeuresService et BilanHeuresController ; etablissement/ClassesController
 * (volumes-annuels) ; notifications/NotificationsFamilles, AbonnementsPush, NotificationsFamillesController ;
 * eleves/PreferencesNotifications ; docs/API_BILAN_HEURES.md, docs/API_NOTIFICATIONS_PARENTS.md.
 *
 * Données : le lycée technique Les Bâtisseurs de la démonstration. Le lot ne laisse rien de modifié : les réglages,
 * le canal et les préférences touchés sont remis à leur valeur d'origine.
 */
import { appel, egal, exiger, ignorer, refuse, scenario, statut, vrai } from '../lib.mjs';

const D_HEU = 'Bilan des heures';
const D_NOT = 'Notifications aux familles';

function ecole(ctx) {
  return ctx.ecole('lt-batisseurs') ?? ignorer('lycée technique absent de la base de démonstration');
}

async function anneeActive(s) {
  const annees = await exiger(s, 'GET', '/annees');
  return annees.find((a) => a.etat === 'ACTIVE') ?? ignorer('aucune année active');
}

scenario('HEU-01', D_HEU, 'Le censeur obtient le bilan des heures de l’année : dû annuel, dû à ce jour, réalisé, cumuls par enseignant, matière et classe', async (ctx) => {
  const e = ecole(ctx);
  const censeur = e.session('CENSEUR') ?? ignorer('pas de censeur');
  const annee = await anneeActive(censeur);
  const b = await exiger(censeur, 'GET', `/annees/${annee.id}/bilan-heures`);
  vrai(b.lignes.length > 0, 'au moins une matière au bilan');
  vrai(b.avancementAnnee >= 0 && b.avancementAnnee <= 100, 'avancement de l’année entre 0 et 100 %');
  for (const l of b.lignes) {
    vrai(['REFERENTIEL', 'ESTIME', 'AUCUN'].includes(l.sourceVolume), `source du volume de ${l.classeCode} ${l.matiereCode}`);
    if (l.cumul.volumeAnnuel !== null) {
      vrai(l.cumul.duADate <= l.cumul.volumeAnnuel + 0.05, `dû à ce jour ≤ dû annuel (${l.classeCode} ${l.matiereCode})`);
    } else {
      egal(l.etat, 'SANS_VOLUME', `état sans volume (${l.classeCode} ${l.matiereCode})`);
    }
  }
  const realise = b.lignes.reduce((t, l) => t + l.cumul.realise, 0);
  vrai(Math.abs(realise - b.total.realise) < 0.05, 'le total réalisé est la somme des lignes');
  vrai(b.classes.length > 0 && b.matieres.length > 0, 'cumuls par classe et par matière');
});

scenario('HEU-02', D_HEU, 'Un enseignant voit ses propres heures, pas le bilan de la direction', async (ctx) => {
  const e = ecole(ctx);
  const ens = e.enseignants.map((x) => e.enseignant(x.matiere)).find(Boolean) ?? ignorer('aucun enseignant');
  const annee = await anneeActive(e.session('CENSEUR') ?? ignorer('pas de censeur'));
  refuse(await appel(ens, 'GET', `/annees/${annee.id}/bilan-heures`), 'Le bilan de la direction est refusé à un enseignant');
  const b = await exiger(ens, 'GET', '/espace-enseignant/bilan-heures');
  vrai(b.enseignants.length <= 1, 'un seul cumul : le sien');
  const moi = b.enseignants[0]?.engagementId;
  if (moi) {
    vrai(b.lignes.every((l) => l.engagementId === moi || l.intervenants.some((i) => i.engagementId === moi)), 'seulement ses matières');
  }
});

scenario('HEU-03', D_HEU, 'Le bilan s’exporte en Excel et en PDF', async (ctx) => {
  const e = ecole(ctx);
  const censeur = e.session('CENSEUR') ?? ignorer('pas de censeur');
  const annee = await anneeActive(censeur);
  for (const [format, type] of [['xlsx', 'spreadsheetml'], ['pdf', 'application/pdf']]) {
    const r = await appel(censeur, 'GET', `/annees/${annee.id}/bilan-heures/export`, undefined, { params: { format }, fichier: true });
    statut(r, [200]);
    vrai((r.entetes.get('content-type') ?? '').includes(type), `type du fichier ${format}`);
  }
});

scenario('NOT-01', D_NOT, 'Le CPE règle le délai de correction après l’appel ; une valeur hors limites est refusée ; un enseignant ne règle rien', async (ctx) => {
  const e = ecole(ctx);
  const cpe = e.session('SURVEILLANT') ?? ignorer('pas de CPE');
  const avant = await exiger(cpe, 'GET', '/notifications/reglages');
  try {
    const r = await exiger(cpe, 'PUT', '/notifications/reglages', { delaiCorrectionMinutes: 7, smsRetards: !avant.smsRetards });
    egal(r.delaiCorrectionMinutes, 7, 'délai enregistré');
    statut(await appel(cpe, 'PUT', '/notifications/reglages', { delaiCorrectionMinutes: 120, smsRetards: false }), [409], 'DELAI_INVALIDE');
    const ens = e.enseignants.map((x) => e.enseignant(x.matiere)).find(Boolean);
    if (ens) refuse(await appel(ens, 'PUT', '/notifications/reglages', { delaiCorrectionMinutes: 0, smsRetards: false }));
  } finally {
    await exiger(cpe, 'PUT', '/notifications/reglages', { delaiCorrectionMinutes: avant.delaiCorrectionMinutes, smsRetards: avant.smsRetards });
  }
});

scenario('NOT-02', D_NOT, 'Le parent consulte ses notifications et règle ses préférences ; il ne voit que les siennes', async (ctx) => {
  const e = ecole(ctx);
  const parent = e.parent(0) ?? ignorer('aucun parent');
  const config = await exiger(parent, 'GET', '/push/configuration');
  vrai(typeof config.disponible === 'boolean', 'configuration push');
  const centre = await exiger(parent, 'GET', '/espace-parent/notifications');
  vrai(centre.nonLues >= 0 && Array.isArray(centre.notifications), 'centre de notifications');
  const enfants = (await exiger(parent, 'GET', '/espace-parent/enfants')).map((x) => x.eleveId);
  vrai(centre.notifications.every((n) => !n.eleveId || enfants.includes(n.eleveId)), 'seulement ses enfants');
  const avant = await exiger(parent, 'GET', '/espace-parent/preferences-notifications');
  try {
    const p = await exiger(parent, 'PUT', '/espace-parent/preferences-notifications', { notifierRetards: !avant.notifierRetards, discret: !avant.discret });
    egal(p.notifierRetards, !avant.notifierRetards, 'préférence des retards');
  } finally {
    await exiger(parent, 'PUT', '/espace-parent/preferences-notifications', { notifierRetards: avant.notifierRetards, discret: avant.discret });
  }
  refuse(await appel(e.session('CENSEUR'), 'GET', '/espace-parent/notifications'), 'Le centre est réservé aux parents');
});

scenario('NOT-03', D_NOT, 'Le secrétariat met un parent « toujours par SMS » (pas de smartphone), puis le remet', async (ctx) => {
  const e = ecole(ctx);
  const secretariat = e.session('SECRETARIAT') ?? ignorer('pas de secrétariat');
  const famille = e.famille(0) ?? ignorer('aucune famille');
  const proviseur = e.session('ADMIN_ECOLE');
  const dossier = await exiger(proviseur, 'GET', `/eleves/${famille.enfants[0].eleveId}`);
  const r = dossier.responsables.find((x) => x.telephone.endsWith(famille.telephone.slice(-8))) ?? dossier.responsables[0];
  const avant = r.canalNotification ?? 'AUTO';
  try {
    statut(await appel(secretariat, 'PUT', `/responsables/${r.responsableId}/canal-notification`, { canal: 'SMS' }), [204]);
    const apres = await exiger(proviseur, 'GET', `/eleves/${famille.enfants[0].eleveId}`);
    egal(apres.responsables.find((x) => x.responsableId === r.responsableId).canalNotification, 'SMS', 'canal SMS enregistré');
    statut(await appel(secretariat, 'PUT', `/responsables/${r.responsableId}/canal-notification`, { canal: 'PIGEON' }), [400]);
  } finally {
    await exiger(secretariat, 'PUT', `/responsables/${r.responsableId}/canal-notification`, { canal: avant });
  }
});
