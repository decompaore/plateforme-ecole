/**
 * Lot 17 (v0.49.1) : professeur principal de la classe.
 *
 * Préfixe : PPR-.
 *
 * Sources vérifiées : professeurprincipal/ProfesseursPrincipauxService, SaisinesService, VerificationNotesService,
 * ProfesseurPrincipalController ; bulletins/ConseilService et BulletinsService (attente de la vérification) ;
 * docs/API_PROFESSEUR_PRINCIPAL.md.
 *
 * Données : une classe du lycée technique Les Bâtisseurs qui a au moins deux enseignants. Le lot désigne l'un d'eux
 * professeur principal le temps des scénarios, puis rétablit la désignation d'origine (PPR-06) : sinon le conseil de
 * classe et les bulletins de cette classe attendraient la vérification des notes aux passages suivants. Il reste dans
 * la base, à chaque passage : deux dossiers clos (PP-AAAA-NNNN) et une réclamation de note maintenue (aucune note
 * n'est modifiée).
 */
import { appel, egal, exiger, ignorer, refuse, scenario, statut, vrai } from '../lib.mjs';

const D = 'Professeur principal';

function partage(ctx) {
  ctx.partage.professeurPrincipal ??= {};
  return ctx.partage.professeurPrincipal;
}

/** La classe retenue, son professeur principal de recette et un deuxième enseignant, avec leurs sessions. */
async function cible(ctx) {
  const p = partage(ctx);
  if (p.classe) return p;
  const e = ctx.ecole('lt-batisseurs') ?? ignorer('lycée technique absent de la base de démonstration');
  const censeur = e.session('CENSEUR') ?? ignorer('pas de censeur');
  const annees = await exiger(censeur, 'GET', '/annees');
  const annee = annees.find((a) => a.etat === 'ACTIVE') ?? ignorer('aucune année active');
  const tableau = await exiger(censeur, 'GET', `/annees/${annee.id}/professeurs-principaux`);
  for (const c of tableau.filter((x) => x.enseignants.length >= 2)) {
    const donnees = e.classes.find((k) => k.id === c.classeId);
    const tels = [...new Set((donnees?.matieres ?? []).map((m) => m.enseignant).filter(Boolean))];
    const sessions = [];
    for (const tel of tels) {
      const s = ctx.compte(tel, e.id, `enseignant ${c.classeCode}`);
      const moi = await appel(s, 'GET', '/moi');
      if (moi.ok) sessions.push({ s, id: moi.donnees.id, tel });
    }
    const connus = sessions.filter((x) => c.enseignants.some((k) => k.utilisateurId === x.id));
    if (connus.length < 2) continue;
    const autre = tableau.flatMap((x) => x.enseignants).find((k) => !c.enseignants.some((y) => y.utilisateurId === k.utilisateurId));
    Object.assign(p, {
      e, annee, censeur, classe: c, avant: c.professeurId,
      proviseur: e.session('ADMIN_ECOLE'), cpe: e.session('SURVEILLANT'),
      pp: connus[0], collegue: connus[1], horsClasse: autre?.utilisateurId ?? null,
    });
    return p;
  }
  return ignorer('aucune classe avec deux enseignants connus de la démonstration');
}

scenario('PPR-01', D, 'Le censeur désigne le professeur principal parmi les enseignants de la classe ; un enseignant ne désigne pas ; l’enseignant désigné est prévenu', async (ctx) => {
  const { censeur, classe, pp, collegue, horsClasse, annee } = await cible(ctx);
  const chemin = `/classes/${classe.classeId}/professeur-principal`;
  refuse(await appel(collegue.s, 'PUT', chemin, { utilisateurId: collegue.id }), 'Un enseignant ne désigne pas');
  if (horsClasse) {
    statut(await appel(censeur, 'PUT', chemin, { utilisateurId: horsClasse }), 409, 'ENSEIGNANT_HORS_CLASSE');
  }
  const v = await exiger(censeur, 'PUT', chemin, { utilisateurId: pp.id });
  egal(v.professeurId, pp.id, `Professeur principal de la ${classe.classeCode}`);
  const miennes = await exiger(pp.s, 'GET', '/espace-enseignant/classes-principales', undefined, { params: { anneeId: annee.id } });
  vrai(miennes.some((x) => x.classeId === classe.classeId), 'Dans « Mes classes » du professeur principal');
  partage(ctx).designe = true;
});

scenario('PPR-02', D, 'Revendication : un enseignant la confie au professeur principal ; la direction ne la voit qu’après la transmission, répond ; le professeur principal clôt', async (ctx) => {
  const p = await cible(ctx);
  if (!p.designe) ignorer('PPR-01 en échec');
  const { classe, pp, collegue, proviseur } = p;
  const d = await exiger(collegue.s, 'POST', `/classes/${classe.classeId}/saisines`, {
    nature: 'REVENDICATION', objet: '[Recette] Manque de craie', expose: 'Les enseignants de la classe manquent de craie depuis deux semaines.',
  });
  vrai(/^PP-\d{4}-\d{4}$/.test(d.numero ?? ''), `Numéro : ${d.numero}`);
  if (proviseur) refuse(await appel(proviseur, 'GET', `/saisines/${d.id}`), 'Pas encore transmise à la direction');
  statut(await appel(collegue.s, 'POST', `/saisines/${d.id}/transmission`, {}), 409, 'TRANSMISSION_IMPOSSIBLE');
  egal((await exiger(pp.s, 'POST', `/saisines/${d.id}/transmission`, { texte: '[Recette] Demande portée par le professeur principal.' })).statut,
    'TRANSMISE', 'Transmise');
  if (proviseur) {
    const r = await exiger(proviseur, 'POST', `/saisines/${d.id}/suites`, { texte: '[Recette] Une boîte de craie sera remise lundi.' });
    vrai(r.journal.some((j) => j.qualite === "Chef d'établissement"), 'Réponse du chef d’établissement au dossier');
  }
  statut(await appel(pp.s, 'POST', `/saisines/${d.id}/cloture`, {}), 400);
  egal((await exiger(pp.s, 'POST', `/saisines/${d.id}/cloture`, { texte: '[Recette] Craie reçue.' })).statut, 'CLOSE', 'Close');
});

scenario('PPR-03', D, 'Médiation : ouverte par le seul professeur principal pour un élève ; l’enseignant concerné ne la voit qu’une fois informé, le CPE qu’une fois saisi', async (ctx) => {
  const p = await cible(ctx);
  if (!p.designe) ignorer('PPR-01 en échec');
  const { classe, pp, collegue, cpe } = p;
  const eleves = await exiger(pp.s, 'GET', `/classes/${classe.classeId}/inscriptions`);
  const eleve = eleves.find((x) => x.statut === 'ACTIVE') ?? ignorer('classe sans élève');
  const corps = {
    nature: 'MEDIATION', objet: '[Recette] Désaccord en classe', expose: 'L’élève signale un désaccord avec un enseignant.',
    inscriptions: [eleve.id], enseignantId: collegue.id,
  };
  refuse(await appel(collegue.s, 'POST', `/classes/${classe.classeId}/saisines`, corps), 'Un autre enseignant n’ouvre pas de médiation');
  const m = await exiger(pp.s, 'POST', `/classes/${classe.classeId}/saisines`, corps);
  egal(m.eleves.length, 1, 'Élève concerné');
  refuse(await appel(collegue.s, 'GET', `/saisines/${m.id}`), 'Enseignant pas encore informé');
  egal((await exiger(pp.s, 'POST', `/saisines/${m.id}/information-enseignant`, {})).enseignantInforme, true, 'Enseignant informé');
  await exiger(collegue.s, 'POST', `/saisines/${m.id}/suites`, { texte: '[Recette] Version de l’enseignant.' });
  if (cpe) refuse(await appel(cpe, 'GET', `/saisines/${m.id}`), 'CPE pas encore saisi');
  await exiger(pp.s, 'POST', `/saisines/${m.id}/transmission`, {});
  if (cpe) statut(await appel(cpe, 'GET', `/saisines/${m.id}`), 200);
  egal((await exiger(pp.s, 'POST', `/saisines/${m.id}/cloture`, { texte: '[Recette] Entretien tenu, incident clos.' })).statut, 'CLOSE', 'Close');
});

scenario('PPR-04', D, 'Vérification des notes : réclamation transmise à l’enseignant de la matière, maintien justifié, attestation du professeur principal, message au censeur', async (ctx) => {
  const p = await cible(ctx);
  if (!p.designe) ignorer('PPR-01 en échec');
  const { e, annee, classe, pp, collegue, censeur } = p;
  const periodes = (await exiger(pp.s, 'GET', `/annees/${annee.id}/periodes`)).filter((x) => x.profilId === classe.profilId);
  const periode = periodes.find((x) => !x.verrouillee && x.debut <= ctx.aujourdhui) ?? periodes.find((x) => !x.verrouillee) ?? periodes[0]
    ?? ignorer('aucune période pour la classe');
  partage(ctx).periode = periode;
  const base = `/classes/${classe.classeId}/verification-notes`;
  const v = await exiger(pp.s, 'GET', base, undefined, { params: { periodeId: periode.id } });
  vrai(v.professeurPrincipal, 'Vue du professeur principal');
  const matiere = e.classes.find((k) => k.id === classe.classeId).matieres
    .find((m) => m.enseignant === collegue.tel) ?? ignorer('matière du deuxième enseignant introuvable');
  const eleve = (await exiger(pp.s, 'GET', `/classes/${classe.classeId}/inscriptions`)).find((x) => x.statut === 'ACTIVE');
  const demande = { periodeId: periode.id, inscriptionId: eleve.id, matiereId: matiere.id, motif: '[Recette] L’élève conteste sa moyenne' };
  refuse(await appel(collegue.s, 'POST', `${base}/reclamations`, demande), 'Seul le professeur principal recueille les réclamations');
  if (periode.verrouillee) {
    statut(await appel(pp.s, 'POST', `${base}/reclamations`, demande), 409, 'PERIODE_VERROUILLEE');
  } else {
    const r = await exiger(pp.s, 'POST', `${base}/reclamations`, demande);
    egal(r.statut, 'EN_COURS', 'Vérification en cours');
    const x = r.reclamations.find((y) => y.statut === 'OUVERTE' && y.matiereId === matiere.id && y.inscriptionId === eleve.id);
    statut(await appel(pp.s, 'POST', `${base}/validation`, { periodeId: periode.id }), 409, 'RECLAMATIONS_EN_ATTENTE');
    const aTraiter = await exiger(collegue.s, 'GET', '/espace-enseignant/reclamations-notes');
    vrai(aTraiter.some((y) => y.id === x.id), 'Reçue par l’enseignant de la matière');
    statut(await appel(collegue.s, 'POST', `/reclamations-notes/${x.id}/reponse`, { decision: 'MAINTENUE' }), 400);
    egal((await exiger(collegue.s, 'POST', `/reclamations-notes/${x.id}/reponse`,
      { decision: 'MAINTENUE', reponse: '[Recette] Moyenne revue avec l’élève : exacte.' })).statut, 'MAINTENUE', 'Note maintenue');
    // D'autres réclamations de recette ouvertes (passage interrompu) : la direction des études y répond
    const restantes = (await exiger(censeur, 'GET', base, undefined, { params: { periodeId: periode.id } })).reclamations
      .filter((y) => y.statut === 'OUVERTE' && y.motif.startsWith('[Recette]'));
    for (const y of restantes) {
      await exiger(censeur, 'POST', `/reclamations-notes/${y.id}/reponse`, { decision: 'MAINTENUE', reponse: '[Recette] Clôture.' });
    }
  }
  const ouvertes = (await exiger(pp.s, 'GET', base, undefined, { params: { periodeId: periode.id } })).reclamations
    .filter((y) => y.statut === 'OUVERTE').length;
  if (ouvertes) ignorer(`${ouvertes} réclamation(s) réelles en attente : attestation laissée au professeur principal`);
  egal((await exiger(pp.s, 'POST', `${base}/validation`, { periodeId: periode.id, observations: '[Recette]' })).statut, 'VALIDEE', 'Attestée');
  vrai(JSON.stringify(await exiger(censeur, 'GET', '/messages')).includes('Notes vérifiées'), 'Le censeur est prévenu');
});

scenario('PPR-05', D, 'Le conseil de classe d’une classe qui a un professeur principal attend la vérification de la période', async (ctx) => {
  const p = await cible(ctx);
  if (!p.designe) ignorer('PPR-01 en échec');
  const { annee, classe, censeur } = p;
  const periodes = (await exiger(censeur, 'GET', `/annees/${annee.id}/periodes`)).filter((x) => x.profilId === classe.profilId);
  const v = await exiger(censeur, 'GET', `/annees/${annee.id}/professeurs-principaux`);
  const attestees = new Set((v.find((x) => x.classeId === classe.classeId)?.verifications ?? [])
    .filter((x) => x.statut === 'VALIDEE').map((x) => x.periodeId));
  const autre = periodes.find((x) => !attestees.has(x.id)) ?? ignorer('toutes les périodes sont attestées');
  const r = await appel(censeur, 'PUT', `/classes/${classe.classeId}/periodes/${autre.id}/conseil`, { avis: [] });
  if (r.statut === 409 && r.donnees?.code === 'BULLETINS_PUBLIES') ignorer('bulletins de la période déjà publiés');
  statut(r, 409, 'VERIFICATION_NOTES_REQUISE');
});

scenario('PPR-06', D, 'Rétablit la désignation d’origine du professeur principal de la classe de recette', async (ctx) => {
  const p = partage(ctx);
  if (!p.classe || !p.designe) ignorer('rien à rétablir');
  const chemin = `/classes/${p.classe.classeId}/professeur-principal`;
  if (p.avant) {
    egal((await exiger(p.censeur, 'PUT', chemin, { utilisateurId: p.avant })).professeurId, p.avant, 'Désignation d’origine');
  } else {
    statut(await appel(p.censeur, 'DELETE', chemin), 204);
  }
});
