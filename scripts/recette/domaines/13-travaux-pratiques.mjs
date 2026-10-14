/**
 * Lot 13 : travaux pratiques dans les ateliers (v0.47.2) — relation entre le chef d'atelier et les enseignants.
 *
 * Préfixe : TPR-.
 *
 * Sources vérifiées : ateliers/SeancesTpService, SecuriteAteliersService, ProductionService,
 * TravauxPratiquesController.
 *
 * Données : lycée technique Les Bâtisseurs. La recette crée son propre atelier (filière F3) avec un article de
 * matière d'œuvre propre à la recette ; elle le ferme et termine le mandat à la fin. Les ateliers de démonstration
 * ne sont pas modifiés. Il reste dans la base : des séances, une commande de production livrée et une observation
 * HSE de cet atelier fermé.
 */
import { ajouterJours, appel, egal, exiger, ignorer, scenario, statut, suffixe, vrai } from '../lib.mjs';

const D = 'Travaux pratiques';

function partage(ctx) {
  ctx.partage.tp ??= {};
  return ctx.partage.tp;
}

function requis(ctx, cle) {
  const v = partage(ctx)[cle];
  if (v === undefined || v === null) ignorer(`prérequis absent (${cle}) : scénario précédent en échec`);
  return v;
}

/** Le lycée technique : chef des travaux, intendant, futur chef d'atelier, enseignant de TP, enseignant général. */
function acteurs(ctx) {
  const p = partage(ctx);
  if (p.acteurs) return p.acteurs;
  const e = ctx.ecole('lt-batisseurs');
  if (!e) ignorer('lycée technique absent de la base de démonstration');
  const classe = e.classes.find((c) => c.filiere === 'F3') ?? ignorer('classe de la filière F3 absente');
  const techniques = classe.matieres.filter((m) => m.type !== 'GENERALE' && m.enseignant);
  const resp = techniques[0] ?? ignorer('aucune matière technique avec enseignant en F3');
  const tp = techniques.find((m) => m.enseignant !== resp.enseignant) ?? ignorer('un seul enseignant technique en F3');
  const general = classe.matieres.find((m) => m.type === 'GENERALE' && m.enseignant
    && !techniques.some((t) => t.enseignant === m.enseignant));
  const engagement = (tel) => e.enseignants.find((x) => x.telephone === tel)?.engagementId ?? null;
  p.acteurs = {
    e,
    classe,
    chef: e.session('CHEF_TRAVAUX') ?? ignorer('pas de chef des travaux'),
    intendant: e.session('INTENDANT') ?? ignorer('pas d’intendant'),
    resp: { session: ctx.compte(resp.enseignant, e.id, `chef d’atelier ${e.code}`), engagementId: engagement(resp.enseignant) },
    tech: { session: ctx.compte(tp.enseignant, e.id, `enseignant de TP ${e.code}`), matiereId: tp.id },
    general: general ? ctx.compte(general.enseignant, e.id, `enseignant général ${e.code}`) : null,
  };
  return p.acteurs;
}

function seance(classeId, matiereId, date, debut, fin, titre, articleId, quantite, autres = {}) {
  return {
    classeId, matiereId, date, heureDebut: debut, heureFin: fin, groupe: null, titre, effectif: 20, productionId: null,
    besoins: articleId ? [{ articleId, quantite }] : [], ...autres,
  };
}

async function quantiteEnStock(qui, atelierId, articleId) {
  const l = (await exiger(qui, 'GET', `/ateliers/${atelierId}/stock`)).find((x) => x.articleId === articleId);
  return l ? Number(l.quantite) : 0;
}

// ===================================================================== mise en place et sécurité

scenario('TPR-01', D, 'Atelier de recette : le chef d’atelier fixe les consignes de sécurité ; l’enseignant ne peut rien demander avant d’en avoir pris connaissance', async (ctx) => {
  const { classe, chef, intendant, resp, tech } = acteurs(ctx);
  const filiereId = (await exiger(chef, 'GET', `/classes/${classe.id}`)).filiereId;
  const code = `TP-${suffixe().toUpperCase()}`;
  const a = await exiger(chef, 'POST', '/ateliers', { code, nom: `Atelier de recette ${code}`, postes: 16, filieres: [filiereId] });
  await exiger(chef, 'POST', `/ateliers/${a.id}/responsable`, { engagementId: resp.engagementId, debut: ctx.aujourdhui, finPrevue: null });
  const article = await exiger(intendant, 'POST', '/catalogue', {
    code: `REC-TP-${suffixe().toUpperCase()}`, designation: 'Recette : rond d’acier', nature: 'MATIERE_OEUVRE', unite: 'barre', prixReference: 4500,
  });
  await exiger(resp.session, 'POST', `/ateliers/${a.id}/mouvements`, { articleId: article.id, type: 'ENTREE', quantite: 10, motif: 'Recette' });
  Object.assign(partage(ctx), { atelier: a, article });

  statut(await appel(tech.session, 'PUT', `/ateliers/${a.id}/consignes`, []), 403);
  const c = await exiger(resp.session, 'PUT', `/ateliers/${a.id}/consignes`, [
    { categorie: 'EPI', texte: 'Blouse, lunettes et chaussures de sécurité' },
    { categorie: 'ARRET_URGENCE', texte: 'Arrêt d’urgence près de la porte' },
  ]);
  egal(c.consignes.length, 2, 'Consignes enregistrées');
  const demain = ajouterJours(ctx.aujourdhui, 1);
  statut(await appel(tech.session, 'POST', `/ateliers/${a.id}/seances-tp`,
    seance(classe.id, tech.matiereId, demain, '08:00', '10:00', 'Recette', article.id, 4)), 409, 'CONSIGNES_A_LIRE');
  vrai((await exiger(tech.session, 'GET', `/ateliers/${a.id}/consignes`)).aLire, 'Consignes à lire');
  egal((await exiger(tech.session, 'POST', `/ateliers/${a.id}/consignes/lecture`)).aLire, false, 'Prise de connaissance');
  const lectures = await exiger(resp.session, 'GET', `/ateliers/${a.id}/consignes/lectures`);
  vrai(lectures.some((l) => l.aJour), 'Le chef d’atelier voit qui est à jour');
  return `${code} : ${lectures.filter((l) => l.aJour).length}/${lectures.length} enseignant(s) à jour`;
});

// ===================================================================== planning

scenario('TPR-02', D, 'Planning : l’enseignant demande une séance ; le chef d’atelier la valide en accordant moins ; une demande en conflit est refusée ; un enseignant hors atelier ne demande rien', async (ctx) => {
  const { classe, resp, tech, general } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const article = requis(ctx, 'article');
  const demain = ajouterJours(ctx.aujourdhui, 1);
  const s1 = await exiger(tech.session, 'POST', `/ateliers/${a.id}/seances-tp`,
    seance(classe.id, tech.matiereId, demain, '08:00', '10:00', 'Recette : tournage', article.id, 4));
  egal([s1.statut, s1.droits.valider], ['DEMANDEE', false], 'Demande en attente');
  statut(await appel(tech.session, 'POST', `/seances-tp/${s1.id}/validation`, {}), 403);
  if (general) {
    statut(await appel(general, 'POST', `/ateliers/${a.id}/seances-tp`,
      seance(classe.id, tech.matiereId, demain, '14:00', '15:00', 'Intrus', null, 0)), 403);
  }
  const v = await exiger(resp.session, 'POST', `/seances-tp/${s1.id}/validation`, { accordees: [{ articleId: article.id, quantite: 3 }] });
  egal([v.statut, Number(v.besoins[0].accordee)], ['PLANIFIEE', 3], 'Séance planifiée, 3 barres accordées');
  const s2 = await exiger(tech.session, 'POST', `/ateliers/${a.id}/seances-tp`,
    seance(classe.id, tech.matiereId, demain, '09:00', '11:00', 'Recette : doublon', article.id, 1));
  vrai(s2.conflits.length > 0, 'Conflit signalé dès la demande');
  statut(await appel(resp.session, 'POST', `/seances-tp/${s2.id}/validation`, {}), 409, 'CONFLIT_ATELIER');
  statut(await appel(resp.session, 'POST', `/seances-tp/${s2.id}/refus`, { motif: '' }), 400);
  egal((await exiger(resp.session, 'POST', `/seances-tp/${s2.id}/refus`, { motif: 'Recette : créneau pris' })).statut, 'REFUSEE', 'Refus');
  partage(ctx).s1 = s1.id;
  return `séance du ${demain} planifiée, doublon refusé`;
});

// ===================================================================== préparation, compte rendu, bilan, production

scenario('TPR-03', D, 'Production et séance du jour : le chef d’atelier prépare (sortie du stock), l’enseignant fait le compte rendu, le bilan remet le surplus en stock, la production avance', async (ctx) => {
  const { classe, resp, tech } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const article = requis(ctx, 'article');
  const s1 = requis(ctx, 's1');
  statut(await appel(tech.session, 'POST', '/productions', { atelierId: a.id, client: 'X', typeClient: 'PRIVE', objet: 'X', quantite: 1, unite: 'pièce' }), 403);
  const p = await exiger(resp.session, 'POST', '/productions', {
    atelierId: a.id, client: 'Recette : mairie', typeClient: 'PUBLIC', objet: 'Tables-bancs', quantite: 20, unite: 'pièce',
    montant: 1000000, echeance: ajouterJours(ctx.aujourdhui, 60),
  });
  vrai(/^PR-\d{4}-\d{3}$/.test(p.numero), `Numéro de production : ${p.numero}`);
  const s3 = await exiger(resp.session, 'POST', `/ateliers/${a.id}/seances-tp`,
    seance(classe.id, tech.matiereId, ctx.aujourdhui, '14:00', '16:00', 'Recette : pieds de tables-bancs', article.id, 2, { productionId: p.id }));
  egal(s3.statut, 'PLANIFIEE', 'Planifiée directement par le chef d’atelier');
  statut(await appel(tech.session, 'POST', `/seances-tp/${s3.id}/preparation`, {}), 403);
  await exiger(resp.session, 'POST', `/seances-tp/${s1}/preparation`, { observations: 'Recette' });
  await exiger(resp.session, 'POST', `/seances-tp/${s3.id}/preparation`, {});
  egal(await quantiteEnStock(resp.session, a.id, article.id), 5, 'Stock après préparation (10 − 3 − 2)');
  const cr = { nettoyage: true, rangement: true, epiRespectes: true, quantiteProduite: 4, observations: 'Recette', consommation: [{ articleId: article.id, quantite: 3 }], pannes: [] };
  statut(await appel(tech.session, 'POST', `/seances-tp/${s1}/compte-rendu`, cr), 409, 'SEANCE_A_VENIR');
  statut(await appel(tech.session, 'POST', `/seances-tp/${s3.id}/compte-rendu`, cr), 409, 'CONSOMMATION_EXCESSIVE');
  const r = await exiger(tech.session, 'POST', `/seances-tp/${s3.id}/compte-rendu`, { ...cr, consommation: [{ articleId: article.id, quantite: 1.5 }] });
  egal(r.statut, 'REALISEE', 'Compte rendu');
  const b = await exiger(resp.session, 'POST', `/seances-tp/${s3.id}/bilan`);
  egal(Number(b.besoins[0].rendue), 0.5, 'Surplus rendu');
  statut(await appel(resp.session, 'POST', `/seances-tp/${s3.id}/bilan`), 409, 'BILAN_DEJA_FAIT');
  egal(await quantiteEnStock(resp.session, a.id, article.id), 5.5, 'Stock après bilan');
  const vue = await exiger(tech.session, 'GET', `/productions/${p.id}`);
  egal([Number(vue.quantiteProduite), vue.seances], [4, 1], 'Avancement de la production');
  partage(ctx).production = p.id;
  return `${p.numero} : 4 / 20 produits`;
});

// ===================================================================== HSE, plannings, nettoyage

scenario('TPR-04', D, 'HSE et plannings : le chef d’atelier consigne une observation, l’enseignant la lit sans en saisir ; le chef des travaux voit tous les ateliers et l’intendant les achats', async (ctx) => {
  const { chef, intendant, resp, tech } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  statut(await appel(tech.session, 'POST', `/ateliers/${a.id}/hse`, { gravite: 'OBSERVATION', description: 'x' }), 403);
  statut(await appel(resp.session, 'POST', `/ateliers/${a.id}/hse`, { gravite: 'OBSERVATION', description: '' }), 400);
  await exiger(resp.session, 'POST', `/ateliers/${a.id}/hse`, { gravite: 'OBSERVATION', description: 'Recette : allées encombrées', mesures: 'Rangement demandé' });
  vrai((await exiger(tech.session, 'GET', `/ateliers/${a.id}/hse`)).length >= 1, 'Journal lu par l’enseignant');
  const demain = ajouterJours(ctx.aujourdhui, 1);
  const general = await exiger(chef, 'GET', '/seances-tp', undefined, { params: { du: ctx.aujourdhui, au: demain } });
  vrai(general.filter((s) => s.atelierId === a.id).length >= 3, 'Planning général');
  statut(await appel(tech.session, 'GET', '/seances-tp'), 403);
  vrai((await exiger(tech.session, 'GET', '/seances-tp/miennes')).some((s) => s.atelierId === a.id), 'Mes séances');
  await exiger(intendant, 'GET', '/seances-tp/achats');
  const g = await exiger(resp.session, 'POST', `/ateliers/${a.id}/seances-tp/generation`, { du: ctx.aujourdhui, au: ajouterJours(ctx.aujourdhui, 6) });
  egal(g.creees, 0, 'Atelier de recette absent de l’emploi du temps : rien à générer');
});

scenario('TPR-05', D, 'Fin du lot : la séance préparée ne s’annule que par le chef d’atelier (la matière revient au stock) ; la production est livrée ; l’atelier de recette est fermé', async (ctx) => {
  const { chef, resp, tech } = acteurs(ctx);
  const a = requis(ctx, 'atelier');
  const article = requis(ctx, 'article');
  const s1 = partage(ctx).s1;
  if (s1) {
    statut(await appel(tech.session, 'POST', `/seances-tp/${s1}/annulation`, { motif: 'Recette' }), 409, 'SEANCE_PREPAREE');
    egal((await exiger(resp.session, 'POST', `/seances-tp/${s1}/annulation`, { motif: 'Recette : fin du lot' })).statut, 'ANNULEE', 'Annulée');
    egal(await quantiteEnStock(resp.session, a.id, article.id), 8.5, 'Matière remise en stock');
  }
  const p = partage(ctx).production;
  if (p) egal((await exiger(resp.session, 'POST', `/productions/${p}/statut`, { statut: 'LIVREE' })).statut, 'LIVREE', 'Livrée');
  await exiger(chef, 'POST', `/ateliers/${a.id}/responsable/fin`, { date: ctx.aujourdhui, motif: 'Fin de la recette' });
  const fiche = await exiger(chef, 'GET', `/ateliers/${a.id}`);
  await exiger(chef, 'PUT', `/ateliers/${a.id}`, {
    code: fiche.code, nom: fiche.nom, emplacement: fiche.emplacement, postes: fiche.postes, ouvert: false,
    observations: 'Atelier de recette', filieres: fiche.filieres.map((f) => f.id),
  });
});
