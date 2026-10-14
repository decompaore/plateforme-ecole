/**
 * Lot 14 : besoins et dotations (v0.48) — l'état annuel des besoins remonte de l'établissement au niveau central,
 * les dotations descendent jusqu'à l'établissement, qui en accuse réception.
 *
 * Préfixes : EBD- (état des besoins et remontée), DOT- (dotations).
 *
 * Sources vérifiées : moyens/EtatBesoinsService, RemonteesService, DotationsService, PorteeMoyens,
 * BesoinsEtablissementController, BesoinsPilotageController ; docs/API_BESOINS_DOTATIONS.md.
 *
 * Prérequis : lot 02 (rattachement de lt-batisseurs à DP-KADIOGO et de cfp-atelier-faso à DP-OUBRITENGA ;
 * comptes DR-CENTRE et DP-KADIOGO ; administrateur pays BF).
 *
 * Rejouable : un état des besoins par année. Au premier passage, l'état de lt-batisseurs est rempli, transmis,
 * arbitré et remonté jusqu'au niveau central ; aux passages suivants (état ou transmissions déjà faits), les
 * scénarios vérifient que tout reste verrouillé. Les dotations sont nouvelles à chaque passage.
 */
import { appel, egal, exiger, ignorer, refuse, scenario, statut, suffixe, vrai } from '../lib.mjs';

const D = 'Besoins et dotations';

function partage(ctx) {
  ctx.partage.besoins ??= {};
  return ctx.partage.besoins;
}

function territoire(ctx, ...cles) {
  const t = ctx.partage.territoire;
  for (const c of cles) {
    const v = c.split('.').reduce((o, k) => (o == null ? o : o[k]), t);
    if (v == null) ignorer(`prérequis absent : territoire.${c} (lot 02 en échec ou ignoré)`);
  }
  return t;
}

function requis(ctx, cle) {
  const v = partage(ctx)[cle];
  if (v === undefined || v === null) ignorer(`prérequis absent (${cle}) : scénario précédent en échec`);
  return v;
}

/** Le lycée technique (DP-KADIOGO) et ses acteurs ; les comptes territoriaux. */
function acteurs(ctx) {
  const p = partage(ctx);
  if (p.acteurs) return p.acteurs;
  const t = territoire(ctx, 'comptesDirection.DR-CENTRE', 'comptesDirection.DP-KADIOGO', 'adminPaysBF', 'directions.DP-KADIOGO',
    'directions.DR-CENTRE', 'rattachement.lt-batisseurs');
  const e = ctx.ecole('lt-batisseurs') ?? ignorer('lycée technique absent de la base de démonstration');
  p.acteurs = {
    e,
    autre: ctx.ecole('cfp-atelier-faso'),
    proviseur: e.session('ADMIN_ECOLE') ?? ignorer('pas de chef d’établissement'),
    censeur: e.session('CENSEUR') ?? ignorer('pas de censeur'),
    chef: e.session('CHEF_TRAVAUX') ?? ignorer('pas de chef des travaux'),
    intendant: e.session('INTENDANT') ?? ignorer('pas d’intendant'),
    secretaire: e.session('SECRETARIAT'),
    dp: t.comptesDirection['DP-KADIOGO'],
    dr: t.comptesDirection['DR-CENTRE'],
    central: t.adminPaysBF,
    idDp: t.directions['DP-KADIOGO'],
    idDr: t.directions['DR-CENTRE'],
  };
  return p.acteurs;
}

const ligne = (categorie, domaine, designation, unite, quantite, priorite = 2, justification = null) =>
  ({ categorie, domaine, designation, unite, quantite, priorite, justification });

// ===================================================================== établissement

scenario('EBD-01', D, 'État des besoins : le censeur renseigne le domaine général, le chef des travaux le domaine technique ; le secrétariat n’y a pas accès', async (ctx) => {
  const { censeur, chef, secretaire } = acteurs(ctx);
  const ec = await exiger(censeur, 'GET', '/etat-besoins');
  partage(ctx).etat = ec;
  vrai(ec.direction?.includes('Kadiogo'), `Destination : ${ec.direction}`);
  if (secretaire) refuse(await appel(secretaire, 'GET', '/etat-besoins'), 'Le secrétariat ne voit pas l’état des besoins');
  if (!ec.modifiable) {
    // Passage précédent : l'état est transmis, plus rien ne se modifie
    statut(await appel(chef, 'POST', `/etat-besoins/${ec.anneeId}/lignes`,
      ligne('MATIERE', 'TECHNIQUE', 'Recette : électrodes', 'boîte', 5)), 409, 'ETAT_TRANSMIS');
    return;
  }
  egal(ec.domaines, ['GENERAL'], 'Domaines du censeur');
  egal((await exiger(chef, 'GET', '/etat-besoins')).domaines, ['TECHNIQUE'], 'Domaines du chef des travaux');
  statut(await appel(censeur, 'POST', `/etat-besoins/${ec.anneeId}/lignes`,
    ligne('EQUIPEMENT_OUTILS', 'TECHNIQUE', 'Tour', 'pièce', 1)), 403);
  statut(await appel(censeur, 'POST', `/etat-besoins/${ec.anneeId}/lignes`,
    ligne('PERSONNEL', 'GENERAL', 'Professeur', 'poste', 0)), 400);
  await exiger(censeur, 'POST', `/etat-besoins/${ec.anneeId}/lignes`,
    ligne('PERSONNEL', 'GENERAL', 'Professeur de mathématiques', 'poste', 2, 1, 'Deux classes de seconde sans professeur'));
  await exiger(censeur, 'POST', `/etat-besoins/${ec.anneeId}/lignes`,
    ligne('INFRASTRUCTURE', 'GENERAL', 'Salle de classe', 'salle', 2, 2, 'Effectifs de 80 élèves'));
  await exiger(chef, 'POST', `/etat-besoins/${ec.anneeId}/lignes`,
    ligne('EQUIPEMENT_OUTILS', 'TECHNIQUE', 'Tour parallèle', 'pièce', 3, 1, 'Parc vétuste'));
  const v = await exiger(chef, 'POST', `/etat-besoins/${ec.anneeId}/lignes`,
    ligne('MATIERE', 'TECHNIQUE', 'Rond d’acier Ø 20', 'barre', 60, 2));
  const prof = v.lignes.find((l) => l.designation === 'Professeur de mathématiques');
  vrai(prof && !prof.peutModifier, 'Le chef des travaux ne modifie pas une ligne du domaine général');
  refuse(await appel(chef, 'DELETE', `/etat-besoins/lignes/${prof.id}`), 'Suppression d’une ligne générale par le chef des travaux');
});

scenario('EBD-02', D, 'Seul le chef d’établissement transmet l’état à sa direction provinciale ; ensuite l’état est verrouillé', async (ctx) => {
  const { proviseur, censeur, chef } = acteurs(ctx);
  const ec = await exiger(proviseur, 'GET', '/etat-besoins');
  if (ec.statut === 'TRANSMIS') {
    statut(await appel(proviseur, 'POST', `/etat-besoins/${ec.anneeId}/transmettre`, {}), 409, 'ETAT_TRANSMIS');
    partage(ctx).annee = ec.annee;
    return;
  }
  vrai(ec.lignes.length > 0, 'Des besoins sont saisis (BES-01)');
  statut(await appel(censeur, 'POST', `/etat-besoins/${ec.anneeId}/transmettre`, {}), 403);
  const t = await exiger(proviseur, 'POST', `/etat-besoins/${ec.anneeId}/transmettre`, { observations: 'Recette : besoins prioritaires du lycée' });
  egal(t.statut, 'TRANSMIS', 'Statut après transmission');
  statut(await appel(chef, 'POST', `/etat-besoins/${ec.anneeId}/lignes`,
    ligne('MATIERE', 'TECHNIQUE', 'Électrodes', 'boîte', 5)), 409, 'ETAT_TRANSMIS');
  partage(ctx).annee = ec.annee;
});

// ===================================================================== directions

scenario('EBD-03', D, 'La DP reçoit l’état ; la DR ne le voit qu’après la transmission de la DP et ne peut pas l’arbitrer avant', async (ctx) => {
  const { e, dp, dr } = acteurs(ctx);
  const annee = requis(ctx, 'annee');
  const vp = await exiger(dp, 'GET', '/pilotage/besoins', undefined, { params: { annee } });
  const lt = vp.etablissements.find((x) => x.id === e.id);
  vrai(lt, 'lt-batisseurs dans le ressort de DP-KADIOGO');
  egal(lt.statut, 'RECU', 'État du lycée à la DP');
  partage(ctx).remontee = lt.remonteeId;
  if (ctx.partage.territoire?.rattachement?.['cfp-atelier-faso']) {
    vrai(!vp.etablissements.some((x) => x.code === 'cfp-atelier-faso'), 'Le CFP (DP-OUBRITENGA) n’est pas dans le ressort de DP-KADIOGO');
  }
  const vr = await exiger(dr, 'GET', '/pilotage/besoins', undefined, { params: { annee } });
  const ltr = vr.etablissements.find((x) => x.id === e.id);
  if (vp.transmission?.statut === 'TRANSMISE') {
    egal(ltr.statut, 'RECU', 'À la DR, après transmission de la DP');
    return;
  }
  egal(ltr.statut, 'EN_COURS', 'À la DR, avant transmission de la DP');
  vrai(ltr.etape?.includes('Kadiogo'), `Étape : ${ltr.etape}`);
  const detail = await exiger(dp, 'GET', `/pilotage/besoins/remontees/${lt.remonteeId}`);
  statut(await appel(dr, 'PUT', `/pilotage/besoins/remontees/${lt.remonteeId}/arbitrages`,
    { lignes: [{ ligneId: detail.lignes[0].id, quantiteRetenue: 1 }] }), 409, 'ETAT_NON_RECU');
});

scenario('EBD-04', D, 'La DP arbitre (au plus la quantité demandée), renvoie pour correction, puis reçoit l’état corrigé', async (ctx) => {
  const { proviseur, chef, dp } = acteurs(ctx);
  const id = requis(ctx, 'remontee');
  let r = await exiger(dp, 'GET', `/pilotage/besoins/remontees/${id}`);
  if (!r.peutArbitrer) {
    const l = r.lignes[0];
    statut(await appel(dp, 'PUT', `/pilotage/besoins/remontees/${id}/arbitrages`,
      { lignes: [{ ligneId: l.id, quantiteRetenue: 0 }] }), 409, 'BESOINS_DEJA_TRANSMIS');
    return;
  }
  // Renvoi pour correction
  statut(await appel(dp, 'POST', `/pilotage/besoins/remontees/${id}/renvoyer`, { motif: '' }), 400);
  await exiger(dp, 'POST', `/pilotage/besoins/remontees/${id}/renvoyer`, { motif: 'Recette : précisez la capacité du tour' });
  const ec = await exiger(chef, 'GET', '/etat-besoins');
  egal(ec.statut, 'RENVOYE', 'État renvoyé');
  vrai(ec.motifRenvoi?.includes('capacité'), `Motif : ${ec.motifRenvoi}`);
  const tour = ec.lignes.find((l) => l.unite === 'pièce');
  if (tour) {
    await exiger(chef, 'PUT', `/etat-besoins/lignes/${tour.id}`, {
      categorie: tour.categorie, domaine: tour.domaine, designation: tour.designation, unite: tour.unite, quantite: tour.quantite,
      priorite: tour.priorite, justification: 'Entre-pointes 1 000 mm, parc vétuste',
    });
  }
  egal((await exiger(proviseur, 'POST', `/etat-besoins/${ec.anneeId}/transmettre`, {})).statut, 'TRANSMIS', 'Retransmis');
  // Arbitrage
  r = await exiger(dp, 'GET', `/pilotage/besoins/remontees/${id}`);
  const l = r.lignes.find((x) => x.unite === 'pièce') ?? r.lignes[0];
  statut(await appel(dp, 'PUT', `/pilotage/besoins/remontees/${id}/arbitrages`,
    { lignes: [{ ligneId: l.id, quantiteRetenue: Number(l.quantite) + 1 }] }), 409, 'QUANTITE_SUPERIEURE');
  const retenue = Math.max(0, Number(l.quantite) - 1);
  const a = await exiger(dp, 'PUT', `/pilotage/besoins/remontees/${id}/arbitrages`,
    { lignes: [{ ligneId: l.id, quantiteRetenue: retenue, observation: 'Recette : un tour réparable' }] });
  egal(Number(a.lignes.find((x) => x.id === l.id).retenue), retenue, 'Quantité retenue par la DP');
});

scenario('EBD-05', D, 'La DP transmet à la DR, une seule fois ; la DR arbitre dans la limite de la DP, transmet ; le niveau central reçoit', async (ctx) => {
  const { e, dp, dr, central } = acteurs(ctx);
  const annee = requis(ctx, 'annee');
  const id = requis(ctx, 'remontee');
  const vp = await exiger(dp, 'GET', '/pilotage/besoins', undefined, { params: { annee } });
  if (vp.transmission?.statut !== 'TRANSMISE') {
    egal((await exiger(dp, 'POST', '/pilotage/besoins/transmettre', { annee, observations: 'Recette' })).transmission.statut,
      'TRANSMISE', 'Transmission de la DP');
  }
  statut(await appel(dp, 'POST', '/pilotage/besoins/transmettre', { annee }), 409, 'BESOINS_DEJA_TRANSMIS');
  const vr = await exiger(dr, 'GET', '/pilotage/besoins', undefined, { params: { annee } });
  egal(vr.etablissements.find((x) => x.id === e.id)?.statut, 'RECU', 'À la DR');
  if (vr.transmission?.statut !== 'TRANSMISE') {
    const r = await exiger(dr, 'GET', `/pilotage/besoins/remontees/${id}`);
    const l = r.lignes.find((x) => x.unite === 'pièce') ?? r.lignes[0];
    statut(await appel(dr, 'PUT', `/pilotage/besoins/remontees/${id}/arbitrages`,
      { lignes: [{ ligneId: l.id, quantiteRetenue: Number(l.maximum) + 1 }] }), 409, 'QUANTITE_SUPERIEURE');
    await exiger(dr, 'PUT', `/pilotage/besoins/remontees/${id}/arbitrages`,
      { lignes: [{ ligneId: l.id, quantiteRetenue: Number(l.maximum) }] });
    await exiger(dr, 'POST', '/pilotage/besoins/transmettre', { annee, observations: 'Recette : besoins de la région du Centre' });
  }
  vrai(vr.consolidation.length > 0, 'Besoins consolidés à la DR');
  const vc = await exiger(central, 'GET', '/pilotage/besoins', undefined, { params: { annee } });
  egal(vc.perimetre.type, 'PAYS', 'Niveau central');
  egal(vc.etablissements.find((x) => x.id === e.id)?.statut, 'RECU', 'Au niveau central');
  vrai(vc.peutArbitrer && !vc.peutTransmettre, 'Le niveau central arbitre en dernier, sans transmettre');
  refuse(await appel(central, 'POST', '/pilotage/besoins/transmettre', { annee }));
});

scenario('EBD-06', D, 'Cloisonnement : pas de route de pilotage pour un établissement, pas d’état d’établissement pour une direction ; le super administrateur consulte sans arbitrer ; export', async (ctx) => {
  const { proviseur, dp, dr, idDr } = acteurs(ctx);
  refuse(await appel(proviseur, 'GET', '/pilotage/besoins'));
  refuse(await appel(proviseur, 'GET', '/pilotage/dotations'));
  refuse(await appel(dp, 'GET', '/etat-besoins'));
  refuse(await appel(dp, 'GET', '/dotations-recues'));
  refuse(await appel(dp, 'GET', '/pilotage/besoins', undefined, { params: { direction: idDr } }), 'Une DP ne remonte pas à sa DR');
  const sa = await exiger(ctx.sa, 'GET', '/pilotage/besoins', undefined, { params: { direction: idDr } });
  vrai(!sa.peutArbitrer && !sa.peutTransmettre, 'Le super administrateur consulte seulement');
  const id = partage(ctx).remontee;
  if (id) {
    const r = await exiger(dr, 'GET', `/pilotage/besoins/remontees/${id}`);
    refuse(await appel(ctx.sa, 'PUT', `/pilotage/besoins/remontees/${id}/arbitrages`,
      { lignes: [{ ligneId: r.lignes[0].id, quantiteRetenue: 0 }] }));
  }
  const x = await appel(dr, 'GET', '/pilotage/besoins/export', undefined, { params: { format: 'xlsx' }, fichier: true });
  statut(x, 200);
  vrai(x.type.includes('spreadsheet'), `Type de l’export : ${x.type}`);
});

// ===================================================================== dotations

scenario('DOT-01', D, 'Le niveau central dote la DR (pas une DP) ; le brouillon reste invisible ; la DR accuse réception avec une quantité non conforme', async (ctx) => {
  const { dr, central, idDr, idDp } = acteurs(ctx);
  const annee = partage(ctx).annee ?? (await exiger(acteurs(ctx).proviseur, 'GET', '/etat-besoins')).annee;
  const lignes = [
    { categorie: 'EQUIPEMENT_OUTILS', designation: 'Tour parallèle', unite: 'pièce', quantite: 3, valeurUnitaire: 2_500_000 },
    { categorie: 'MATIERE', designation: 'Rond d’acier Ø 20', unite: 'barre', quantite: 100 },
  ];
  const objet = `Recette ${suffixe()} : dotation de rentrée`;
  statut(await appel(central, 'POST', '/pilotage/dotations',
    { annee, destinataireType: 'DIRECTION', destinataireId: idDp, objet, lignes }), 409, 'DESTINATAIRE_HORS_RESSORT');
  const d = await exiger(central, 'POST', '/pilotage/dotations', { annee, destinataireType: 'DIRECTION', destinataireId: idDr, objet, lignes });
  egal(d.statut, 'BROUILLON', 'Dotation préparée');
  egal(Number(d.valeur), 7_500_000, 'Valeur');
  refuse(await appel(dr, 'GET', `/pilotage/dotations/${d.id}`), 'Brouillon invisible du destinataire');
  egal((await exiger(central, 'POST', `/pilotage/dotations/${d.id}/envoyer`)).statut, 'ENVOYEE', 'Envoyée');
  statut(await appel(central, 'PUT', `/pilotage/dotations/${d.id}`, { annee, destinataireType: 'DIRECTION', destinataireId: idDr, objet, lignes }),
    409, 'DOTATION_ENVOYEE');
  const recue = await exiger(dr, 'GET', `/pilotage/dotations/${d.id}`);
  vrai(recue.peutAccuser, 'La DR peut accuser réception');
  const tour = recue.lignes.find((l) => l.unite === 'pièce');
  const a = await exiger(dr, 'POST', `/pilotage/dotations/${d.id}/accuser`, {
    observations: 'Recette : un tour endommagé au transport',
    lignes: [{ ligneId: tour.id, quantiteRecue: 3, quantiteConforme: 2, observation: 'Carter fendu' }],
  });
  egal(a.statut, 'RECUE', 'Réception accusée');
  vrai(a.peutRepartir, 'La DR peut répartir');
  statut(await appel(dr, 'POST', `/pilotage/dotations/${d.id}/accuser`, {}), 409, 'DEJA_ACCUSEE');
  Object.assign(partage(ctx), { nationale: a });
});

scenario('DOT-02', D, 'La DR répartit vers la DP dans la limite des quantités conformes reçues ; la DP accuse réception', async (ctx) => {
  const { dr, dp, idDp } = acteurs(ctx);
  const n = requis(ctx, 'nationale');
  const tour = n.lignes.find((l) => l.unite === 'pièce');
  const acier = n.lignes.find((l) => l.unite === 'barre');
  statut(await appel(dr, 'POST', '/pilotage/dotations', {
    annee: n.annee, destinataireType: 'DIRECTION', destinataireId: idDp, origineId: n.id, objet: n.objet,
    lignes: [{ ligneOrigineId: tour.id, quantite: 3 }],
  }), 409, 'QUANTITE_DEPASSEE');
  const d = await exiger(dr, 'POST', '/pilotage/dotations', {
    annee: n.annee, destinataireType: 'DIRECTION', destinataireId: idDp, origineId: n.id, objet: n.objet,
    lignes: [{ ligneOrigineId: tour.id, quantite: 1 }, { ligneOrigineId: acier.id, quantite: 60 }],
  });
  egal(d.origineReference, n.reference, 'Répartition de la dotation nationale');
  refuse(await appel(dp, 'POST', `/pilotage/dotations/${d.id}/envoyer`), 'Seul l’émetteur envoie');
  await exiger(dr, 'POST', `/pilotage/dotations/${d.id}/envoyer`);
  const origine = await exiger(dr, 'GET', `/pilotage/dotations/${n.id}`);
  egal(Number(origine.lignes.find((l) => l.id === tour.id).disponible), 1, 'Tours encore disponibles à la DR');
  const a = await exiger(dp, 'POST', `/pilotage/dotations/${d.id}/accuser`, {});
  egal(a.statut, 'RECUE', 'Réception accusée par la DP');
  partage(ctx).provinciale = a;
});

scenario('DOT-03', D, 'La DP dote son lycée (pas celui d’une autre DP) ; l’intendant accuse réception, le censeur ne le peut pas ; la DR suit', async (ctx) => {
  const { e, autre, dp, dr, intendant, censeur } = acteurs(ctx);
  const p = requis(ctx, 'provinciale');
  const acier = p.lignes.find((l) => l.unite === 'barre');
  if (autre) {
    statut(await appel(dp, 'POST', '/pilotage/dotations', {
      annee: p.annee, destinataireType: 'ETABLISSEMENT', destinataireId: autre.id, origineId: p.id, objet: p.objet,
      lignes: [{ ligneOrigineId: acier.id, quantite: 10 }],
    }), 409, 'DESTINATAIRE_HORS_RESSORT');
  }
  const d = await exiger(dp, 'POST', '/pilotage/dotations', {
    annee: p.annee, destinataireType: 'ETABLISSEMENT', destinataireId: e.id, origineId: p.id, objet: p.objet,
    lignes: [{ ligneOrigineId: acier.id, quantite: 60 }],
  });
  await exiger(dp, 'POST', `/pilotage/dotations/${d.id}/envoyer`);
  const liste = await exiger(intendant, 'GET', '/dotations-recues');
  vrai(liste.some((x) => x.id === d.id && x.statut === 'ENVOYEE'), 'Dotation reçue par le lycée');
  const detail = await exiger(intendant, 'GET', `/dotations-recues/${d.id}`);
  const l = detail.lignes[0];
  refuse(await appel(censeur, 'POST', `/dotations-recues/${d.id}/accuser`, {}));
  statut(await appel(intendant, 'POST', `/dotations-recues/${d.id}/accuser`,
    { lignes: [{ ligneId: l.id, quantiteRecue: 60, quantiteConforme: 61 }] }), 400);
  const a = await exiger(intendant, 'POST', `/dotations-recues/${d.id}/accuser`, {
    observations: 'Recette : reçu au magasin',
    lignes: [{ ligneId: l.id, quantiteRecue: 60, quantiteConforme: 58, observation: '2 barres tordues' }],
  });
  egal(a.statut, 'RECUE', 'Réception accusée par le lycée');
  statut(await appel(intendant, 'POST', `/dotations-recues/${d.id}/accuser`, {}), 409, 'DEJA_ACCUSEE');
  const vu = await exiger(dr, 'GET', `/pilotage/dotations/${d.id}`);
  egal(Number(vu.lignes[0].quantiteConforme), 58, 'La DR voit la quantité conforme');
  if (autre) {
    const s = autre.session('ADMIN_ECOLE');
    if (s) refuse(await appel(s, 'GET', `/dotations-recues/${d.id}`), 'Un autre établissement ne voit pas la dotation');
  }
});
