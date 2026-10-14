import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Role } from '../core/modeles';
import { dateLocale } from '../core/outils';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { AlertesAtelier, ArticleVue, AtelierVue } from './modeles-ateliers';
import { ajouterJours, ConsignesVue, heure, lundi, SeanceTpVue } from './modeles-tp';
import { TravauxPratiquesPage } from './pages/travaux-pratiques.page';

async function session(http: HttpTestingController, role: Role): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('64000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u9', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'TRAORE', prenoms: 'Ali' });
  await connexion;
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement {
  f.detectChanges();
  const b = [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((x) => x.textContent?.trim().startsWith(libelle));
  if (!b) {
    throw new Error(`Bouton « ${libelle} » introuvable`);
  }
  return b;
}

function saisir<T>(f: ComponentFixture<T>, selecteur: string, valeur: string): void {
  f.detectChanges();
  const champ = (f.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement | HTMLSelectElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event(champ instanceof HTMLSelectElement ? 'change' : 'input'));
  f.detectChanges();
}

const SANS_ALERTE: AlertesAtelier = {
  sansResponsable: false, mandatAEcheance: false, mandatEchu: false, equipementsEnPanne: 0, equipementsManquants: 0,
  articlesSousSeuil: 0, inventaireEnCours: false, dernierInventaire: null, inventaireEnRetard: false,
};

function atelier(droits: AtelierVue['droits']): AtelierVue {
  return {
    id: 'at1', code: 'FAB', nom: 'Atelier de fabrication', emplacement: null, postes: 20, ouvert: true, observations: null,
    filieres: [{ id: 'f1', code: 'F1', libelle: 'Fabrication mécanique' }], responsable: null, historique: [], equipements: 1,
    articles: 1, alertes: SANS_ALERTE, droits,
  };
}

const ACIER: ArticleVue = {
  id: 'ac', code: 'ACIER-20', designation: "Rond d'acier", nature: 'MATIERE_OEUVRE', unite: 'barre', filiereId: null,
  filiereCode: null, specifications: null, normes: null, prixReference: 4500, prixModifieLe: null, actif: true,
} as ArticleVue;

const CONSIGNES: ConsignesVue = {
  atelierId: 'at1', modifieesLe: '2026-10-01T08:00:00Z', luLe: null, aLire: true, modifier: false,
  consignes: [{ id: 'c1', categorie: 'EPI', equipementId: null, equipement: null, texte: 'Lunettes obligatoires' }],
};

const AUJOURDHUI = dateLocale();
const DEBUT = lundi(AUJOURDHUI);

function seance(autres: Partial<SeanceTpVue> = {}): SeanceTpVue {
  return {
    id: 's1', atelierId: 'at1', atelierCode: 'FAB', atelier: 'Atelier de fabrication', classeId: 'c2', classeCode: '2nde F1',
    matiereId: 'm1', matiere: 'TP fabrication', engagementId: 'g1', enseignant: 'TRAORE Ali', date: AUJOURDHUI,
    heureDebut: '08:00:00', heureFin: '10:00:00', groupe: null, titre: 'Tournage cylindrique', effectif: 24, productionId: null,
    production: null, origine: 'DEMANDE', statut: 'DEMANDEE', motif: null, prepareeLe: null, observationsPreparation: null,
    realiseeLe: null, nettoyage: null, rangement: null, epiRespectes: null, quantiteProduite: null, observations: null, bilanLe: null,
    besoins: [{ articleId: 'ac', code: 'ACIER-20', designation: "Rond d'acier", unite: 'barre', demandee: 4, accordee: null, aAcheter: null, servie: null, utilisee: null, rendue: null, stock: 10 }],
    conflits: [], equipementsIndisponibles: [],
    droits: { modifier: false, valider: false, preparer: false, compteRendu: false, bilan: false, annuler: false },
    ...autres,
  };
}

async function ouvrir(http: HttpTestingController, droits: AtelierVue['droits'], consignes: ConsignesVue, seances: SeanceTpVue[]) {
  const f = TestBed.createComponent(TravauxPratiquesPage);
  f.componentRef.setInput('atelierId', 'at1');
  f.detectChanges();
  await attendre();
  http.expectOne('/api/v1/ateliers/at1').flush(atelier(droits));
  http.expectOne('/api/v1/ateliers/at1/consignes').flush(consignes);
  http.expectOne('/api/v1/ateliers/at1/seances-tp/options').flush([
    { classeId: 'c2', classeCode: '2nde F1', matiereId: 'm1', matiere: 'TP fabrication', engagementId: 'g1', enseignant: 'TRAORE Ali' },
  ]);
  http.expectOne('/api/v1/catalogue').flush([ACIER]);
  http.expectOne((r) => r.url === '/api/v1/productions').flush([]);
  await attendre();
  const planning = http.expectOne((r) => r.url === '/api/v1/ateliers/at1/seances-tp');
  expect(planning.request.params.get('du')).toBe(DEBUT);
  expect(planning.request.params.get('au')).toBe(ajouterJours(DEBUT, 6));
  planning.flush(seances);
  await attendre();
  return f;
}

describe('Travaux pratiques (v0.47.2)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('outils : semaine du lundi, heures', () => {
    expect(lundi('2026-10-14')).toBe('2026-10-12');
    expect(lundi('2026-10-18')).toBe('2026-10-12');
    expect(ajouterJours('2026-10-12', 6)).toBe('2026-10-18');
    expect(heure('08:00:00')).toBe('08h00');
  });

  it('enseignant : prend connaissance des consignes, puis demande une séance avec sa matière d’œuvre', async () => {
    await session(http, 'ENSEIGNANT');
    const f = await ouvrir(http, { gerer: false, responsable: false, signaler: true }, CONSIGNES, []);
    expect(texte(f)).toContain('Lunettes obligatoires');
    bouton(f, "J'ai pris connaissance").click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/ateliers/at1/consignes/lecture')
      .flush({ ...CONSIGNES, aLire: false, luLe: '2026-10-14T08:00:00Z' });
    await attendre();
    expect(texte(f)).not.toContain("J'ai pris connaissance");

    bouton(f, 'Demander une séance').click();
    f.detectChanges();
    await attendre();
    saisir(f, '#nDate', ajouterJours(AUJOURDHUI, 1));
    saisir(f, '#nTitre', 'Tournage cylindrique');
    bouton(f, "Ajouter de la matière d'œuvre").click();
    f.detectChanges();
    saisir(f, 'input[aria-label="Quantité 1"]', '4');
    await attendre();
    bouton(f, 'Envoyer la demande').click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/ateliers/at1/seances-tp');
    expect(req.request.body).toEqual({
      classeId: 'c2', matiereId: 'm1', date: ajouterJours(AUJOURDHUI, 1), heureDebut: '08:00', heureFin: '10:00', groupe: null,
      titre: 'Tournage cylindrique', effectif: null, productionId: null, besoins: [{ articleId: 'ac', quantite: 4 }],
    });
    const cree = seance({ date: ajouterJours(AUJOURDHUI, 1) });
    req.flush(cree);
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/ateliers/at1/seances-tp' && r.method === 'GET').flush([cree]);
    await attendre();
    expect(texte(f)).toContain("Demande envoyée au chef d'atelier");
    expect(texte(f)).toContain('Tournage cylindrique');
  });

  it('chef d’atelier : valide en accordant moins, prépare, puis fait le bilan après le compte rendu', async () => {
    await session(http, 'ENSEIGNANT');
    const droitsChef = { modifier: true, valider: true, preparer: false, compteRendu: false, bilan: false, annuler: true };
    const f = await ouvrir(http, { gerer: false, responsable: true, signaler: true }, { ...CONSIGNES, aLire: false, modifier: true },
      [seance({ droits: droitsChef, conflits: [] })]);
    expect(texte(f)).toContain('(1 à valider)');

    bouton(f, 'Valider').click();
    f.detectChanges();
    saisir(f, 'input[aria-label="Accordé Rond d\'acier"]', '3');
    bouton(f, 'Planifier la séance').click();
    await attendre();
    const v = http.expectOne('/api/v1/seances-tp/s1/validation');
    expect(v.request.body).toEqual({ accordees: [{ articleId: 'ac', quantite: 3 }] });
    const planifiee = seance({
      statut: 'PLANIFIEE', droits: { ...droitsChef, valider: false, preparer: true },
      besoins: [{ ...seance().besoins[0], accordee: 3, aAcheter: 0 }],
      equipementsIndisponibles: [{ id: 'e1', numeroInventaire: 'FAB-2026-001', designation: 'Tour parallèle', etat: 'EN_PANNE' }],
    });
    v.flush(planifiee);
    await attendre();
    expect(texte(f)).toContain('Séance de 2nde F1 planifiée');
    expect(texte(f)).toContain('Indisponible : Tour parallèle (FAB-2026-001, en panne)');

    bouton(f, "Préparer l'atelier").click();
    f.detectChanges();
    bouton(f, 'Atelier prêt').click();
    await attendre();
    http.expectOne('/api/v1/seances-tp/s1/preparation').flush({
      ...planifiee, prepareeLe: '2026-10-14T07:30:00Z', droits: { ...droitsChef, valider: false, compteRendu: true },
      besoins: [{ ...planifiee.besoins[0], servie: 3 }],
    });
    await attendre();
    expect(texte(f)).toContain('Séance préparée');

    // Compte rendu (le chef d'atelier peut le saisir pour l'enseignant)
    bouton(f, 'Compte rendu').click();
    await attendre();
    http.expectOne('/api/v1/ateliers/at1/equipements').flush([]);
    await attendre();
    saisir(f, 'input[aria-label="Utilisé Rond d\'acier"]', '2.5');
    const epi = [...(f.nativeElement as HTMLElement).querySelectorAll('label.case')].find((l) => l.textContent?.includes('EPI'))!;
    epi.querySelector('input')!.click();
    f.detectChanges();
    bouton(f, 'Enregistrer le compte rendu').click();
    await attendre();
    const cr = http.expectOne('/api/v1/seances-tp/s1/compte-rendu');
    expect(cr.request.body).toEqual({
      nettoyage: true, rangement: true, epiRespectes: false, quantiteProduite: null, observations: null,
      consommation: [{ articleId: 'ac', quantite: 2.5 }], pannes: [],
    });
    const realisee = seance({
      statut: 'REALISEE', nettoyage: true, rangement: true, epiRespectes: false, prepareeLe: '2026-10-14T07:30:00Z',
      droits: { ...droitsChef, valider: false, bilan: true, annuler: false },
      besoins: [{ ...planifiee.besoins[0], servie: 3, utilisee: 2.5 }],
    });
    cr.flush(realisee);
    await attendre();
    expect(texte(f)).toContain('EPI non respectés');

    bouton(f, 'Faire le bilan').click();
    await attendre();
    http.expectOne('/api/v1/seances-tp/s1/bilan').flush({
      ...realisee, bilanLe: '2026-10-14T12:00:00Z', droits: { ...realisee.droits, bilan: false },
      besoins: [{ ...realisee.besoins[0], rendue: 0.5 }],
    });
    await attendre();
    expect(texte(f)).toContain("Retour au stock : 0.5 barre Rond d'acier");
  });
});
