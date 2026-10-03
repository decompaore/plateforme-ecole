import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AnneeCourante } from '../admin/annee-courante.service';
import { Role } from '../core/modeles';
import { dateLocale } from '../core/outils';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { lireMontant } from './modeles-scolarite';
import { bilanRelances, ClassesScoPage } from './pages/classes-sco.page';
import { erreurEncaissement, FicheScolaritePage } from './pages/fiche-scolarite.page';
import { erreurFrais, FraisPage, repartir } from './pages/frais.page';
import { debutDuMois, JournalPage } from './pages/journal.page';

const ANNEE = { id: 'a1', libelle: '2026-2027', debut: '2026-10-01', fin: '2027-07-31', etat: 'ACTIVE' };
const AUJOURDHUI = dateLocale();

async function session(http: HttpTestingController, role: Role, annees = false): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('70000003', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u7', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'SOME', prenoms: 'Issa' });
  await connexion;
  if (annees) {
    const a = TestBed.inject(AnneeCourante).charger();
    http.expectOne('/api/v1/annees').flush([ANNEE]);
    await a;
  }
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

/** Les champs ngModel d'un formulaire s'enregistrent après une micro-tâche : on la laisse passer. */
async function saisir<T>(f: ComponentFixture<T>, selecteur: string, valeur: string): Promise<void> {
  f.detectChanges();
  await attendre();
  const champ = (f.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement | HTMLSelectElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event(champ instanceof HTMLSelectElement ? 'change' : 'input'));
  f.detectChanges();
}

const echeance = (numero: number, dateLimite: string, montant: number, payeFamille: number, enRetard: boolean) => ({
  fraisId: 'f1', libelle: 'Scolarité', numero, nombreTranches: 3, dateLimite, montant, exoneration: 0,
  partOrganisme: montant / 2, partFamille: montant / 2, payeFamille, payeOrganisme: 0,
  resteFamille: montant / 2 - payeFamille, resteOrganisme: montant / 2, enRetard,
});

function situation(resteFamille: number, retardFamille: number, paiements: object[] = []) {
  return {
    inscriptionId: 'i1', eleveId: 'e1', matricule: '2026-00012', nom: 'OUEDRAOGO', prenoms: 'Awa', classeCode: '2nde F3 A',
    annee: '2026-2027', statutBourse: 'SEMI_BOURSIER', tauxPriseEnCharge: 50, organisme: 'État burkinabè',
    total: 90_000, exonere: 0, totalFamille: 45_000, totalOrganisme: 45_000, payeFamille: 45_000 - resteFamille, payeOrganisme: 0,
    resteFamille, resteOrganisme: 45_000, retardFamille, retardOrganisme: 15_000, avanceFamille: 0, prochaineEcheance: '2027-01-15',
    echeances: [echeance(1, '2026-10-15', 30_000, 7_500, true), echeance(2, '2027-01-15', 30_000, 0, false), echeance(3, '2027-04-15', 30_000, 0, false)],
    exonerations: [],
    paiements,
  };
}

const PAIEMENT = {
  id: 'p1', inscriptionId: 'i1', montant: 7_500, moyen: 'ORANGE_MONEY', payeur: 'FAMILLE', organismeId: null,
  referenceExterne: 'OM-4455', deposant: null, datePaiement: AUJOURDHUI, enregistreLe: `${AUJOURDHUI}T09:00:00Z`,
  recuNumero: '2026-000045', recuCode: 'ABCDE23456', annule: false, motifAnnulation: null,
};

describe('Scolarité et paiements', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('contrôle les saisies avant l’envoi', () => {
    expect(lireMontant('15 000')).toBe(15_000);
    expect(lireMontant('15,5')).toBeNull();
    expect(lireMontant('0')).toBeNull();
    expect(erreurEncaissement(null, 10_000, 'ESPECES', '', AUJOURDHUI, AUJOURDHUI)).toContain('sans centimes');
    expect(erreurEncaissement(12_000, 10_000, 'ESPECES', '', AUJOURDHUI, AUJOURDHUI)).toMatch(/reste à payer est de 10.000 FCFA/);
    expect(erreurEncaissement(5_000, 10_000, 'CHEQUE', ' ', AUJOURDHUI, AUJOURDHUI)).toContain('Référence de la transaction Chèque');
    expect(erreurEncaissement(5_000, 10_000, 'ESPECES', '', '2999-01-01', AUJOURDHUI)).toContain('futur');
    expect(erreurEncaissement(5_000, 0, 'ESPECES', '', AUJOURDHUI, AUJOURDHUI)).toContain('Rien à payer');
    expect(erreurEncaissement(5_000, 10_000, 'ESPECES', '', AUJOURDHUI, AUJOURDHUI)).toBeNull();

    expect(repartir(100_000, 3)).toEqual([33_334, 33_333, 33_333]);
    const frais = { libelle: 'Scolarité', montant: 75_000, portee: 'TOUTES' as const, cibles: 0 };
    expect(erreurFrais({ ...frais, tranches: [{ dateLimite: '2026-10-15', montant: 25_000 }, { dateLimite: '2027-01-15', montant: 25_000 }] })).toMatch(/somme des tranches \(50.000 FCFA\)/);
    expect(erreurFrais({ ...frais, tranches: [{ dateLimite: '2027-01-15', montant: 37_500 }, { dateLimite: '2026-10-15', montant: 37_500 }] })).toContain('se suivre');
    expect(erreurFrais({ ...frais, portee: 'NIVEAUX', tranches: [] })).toContain('au moins une');
    expect(erreurFrais({ ...frais, tranches: [] })).toBeNull();

    expect(bilanRelances({ envoyees: 12, sansContact: 2, dejaRelancees: 5 })).toBe(
      '12 SMS envoyé(s) · 5 famille(s) déjà relancée(s) récemment · 2 sans numéro de téléphone',
    );
    expect(debutDuMois('2026-10-23')).toBe('2026-10-01');
  });

  it('guichet : situation d’une semi-boursière, encaissement du retard avec reçu, puis annulation', async () => {
    await session(http, 'INTENDANT');
    const f = TestBed.createComponent(FicheScolaritePage);
    f.componentRef.setInput('eleveId', 'e1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/eleves/e1').flush({
      eleve: { id: 'e1', matricule: '2026-00012', nom: 'OUEDRAOGO', prenoms: 'Awa' },
      responsables: [],
      inscriptions: [{ id: 'i1', eleveId: 'e1', anneeId: 'a1', anneeLibelle: '2026-2027', classeId: 'c1', classeCode: '2nde F3 A', statut: 'ACTIVE', statutBourse: 'SEMI_BOURSIER' }],
    });
    await attendre();
    const chargement = (s: object) => {
      http.expectOne('/api/v1/inscriptions/i1/scolarite').flush(s);
      http.expectOne('/api/v1/annees/a1/frais').flush([
        { id: 'f1', anneeId: 'a1', libelle: 'Scolarité', montant: 90_000, obligatoire: true, couvertParBourse: true, portee: 'TOUTES', filieres: [], niveaux: [], classes: [], tranches: [] },
        { id: 'f2', anneeId: 'a1', libelle: 'Cantine', montant: 20_000, obligatoire: false, couvertParBourse: false, portee: 'TOUTES', filieres: [], niveaux: [], classes: [], tranches: [] },
      ]);
    };
    chargement(situation(37_500, 7_500));
    http.expectOne('/api/v1/organismes').flush([{ id: 'o1', nom: 'État burkinabè', type: 'ETAT', telephone: null, actif: true }]);
    await attendre();
    // Pas de décision enregistrée : le serveur répond 404, l'écran l'explique
    http.expectOne('/api/v1/inscriptions/i1/prise-en-charge').flush({ title: 'Introuvable' }, { status: 404, statusText: 'Not Found' });
    await attendre();

    let t = texte(f);
    expect(t).toContain('OUEDRAOGO Awa');
    expect(t).toMatch(/37.500 FCFA ?reste à payer par la famille/);
    expect(t).toMatch(/7.500 FCFA ?en retard/);
    expect(t).toContain('Part prise en charge par État burkinabè (50 %)');
    expect(t).toContain('tranche 1/3');
    expect(t).toContain('en retard');
    expect(t).toContain('sans décision enregistrée');
    expect(t).toContain('Cantine');

    // Le retard proposé en un clic ; une transaction Orange Money exige sa référence
    bouton(f, 'Le retard').click();
    await saisir(f, '#moyen', 'ORANGE_MONEY');
    expect(texte(f)).toContain('Référence de la transaction Orange Money obligatoire');
    expect(bouton(f, 'Encaisser').disabled).toBe(true);
    await saisir(f, '#reference', 'OM-4455');
    expect(bouton(f, 'Encaisser').textContent).toMatch(/Encaisser 7.500 FCFA/);
    bouton(f, 'Encaisser').click();
    await attendre();
    const envoi = http.expectOne('/api/v1/inscriptions/i1/paiements');
    const corps = envoi.request.body as Record<string, unknown>;
    expect(corps).toMatchObject({ montant: 7_500, moyen: 'ORANGE_MONEY', payeur: 'FAMILLE', referenceExterne: 'OM-4455', datePaiement: null, organismeId: null });
    expect(typeof corps['cleIdempotence']).toBe('string');
    envoi.flush(PAIEMENT);
    await attendre();
    chargement(situation(30_000, 0, [PAIEMENT]));
    await attendre();
    http.expectOne('/api/v1/inscriptions/i1/prise-en-charge').flush({ title: 'Introuvable' }, { status: 404, statusText: 'Not Found' });
    await attendre();
    t = texte(f);
    expect(t).toContain('Paiement enregistré · reçu n° 2026-000045');
    expect(t).toMatch(/Reste à payer : 30.000 FCFA/);

    // Annulation avec motif obligatoire
    bouton(f, 'Annuler').click();
    expect(bouton(f, 'Confirmer l’annulation'.replace('’', "'")).disabled).toBe(true);
    await saisir(f, 'input[name=motif]', 'Erreur de saisie');
    bouton(f, "Confirmer l'annulation").click();
    await attendre();
    const annulation = http.expectOne('/api/v1/paiements/p1/annulation');
    expect(annulation.request.body).toEqual({ motif: 'Erreur de saisie' });
    annulation.flush({ ...PAIEMENT, annule: true, motifAnnulation: 'Erreur de saisie' });
    await attendre();
    chargement(situation(37_500, 7_500, [{ ...PAIEMENT, annule: true, motifAnnulation: 'Erreur de saisie' }]));
    await attendre();
    http.expectOne('/api/v1/inscriptions/i1/prise-en-charge').flush({ title: 'Introuvable' }, { status: 404, statusText: 'Not Found' });
    await attendre();
    t = texte(f);
    expect(t).toMatch(/Reçu n° 2026-000045 annulé : 7.500 FCFA ne comptent plus/);
    expect(t).toContain('Annulé : Erreur de saisie');
    expect(t).not.toContain('Paiement enregistré');
  });

  it('secrétariat : consultation de la situation, sans encaissement', async () => {
    await session(http, 'SECRETARIAT');
    const f = TestBed.createComponent(FicheScolaritePage);
    f.componentRef.setInput('inscription', 'i1');
    f.detectChanges();
    await attendre();
    // Ouverte depuis l'état d'une classe : l'élève est retrouvé par son inscription
    http.expectOne('/api/v1/inscriptions/i1/scolarite').flush(situation(37_500, 7_500));
    await attendre();
    http.expectOne('/api/v1/eleves/e1').flush({
      eleve: { id: 'e1', matricule: '2026-00012', nom: 'OUEDRAOGO', prenoms: 'Awa' },
      responsables: [],
      inscriptions: [{ id: 'i1', eleveId: 'e1', anneeId: 'a1', anneeLibelle: '2026-2027', classeCode: '2nde F3 A', statut: 'ACTIVE' }],
    });
    await attendre();
    http.expectOne('/api/v1/inscriptions/i1/scolarite').flush(situation(37_500, 7_500));
    http.expectOne('/api/v1/annees/a1/frais').flush([]);
    await attendre();
    http.expectOne('/api/v1/inscriptions/i1/prise-en-charge').flush({
      inscriptionId: 'i1', organismeId: 'o1', organisme: 'État burkinabè', taux: 50, referenceDecision: 'Arrêté 2026-045', dateDecision: '2026-09-01',
    });
    await attendre();
    const t = texte(f);
    expect(t).toContain('décision Arrêté 2026-045 du 01/09/2026');
    expect(t).not.toContain('Encaisser un paiement');
    expect(t).not.toContain('Accorder une exonération');
  });

  it('journal de caisse : totaux par moyen et élève de chaque paiement', async () => {
    await session(http, 'INTENDANT');
    const f = TestBed.createComponent(JournalPage);
    f.detectChanges();
    await attendre();
    const req = http.expectOne((r) => r.url === '/api/v1/paiements');
    expect(req.request.params.get('du')).toBe(AUJOURDHUI);
    expect(req.request.params.get('au')).toBe(AUJOURDHUI);
    req.flush({
      du: AUJOURDHUI, au: AUJOURDHUI, total: 32_500,
      parMoyen: [{ moyen: 'ESPECES', montant: 25_000, nombre: 2 }, { moyen: 'ORANGE_MONEY', montant: 7_500, nombre: 1 }],
      paiements: [
        PAIEMENT,
        { ...PAIEMENT, id: 'p2', inscriptionId: 'i2', montant: 15_000, moyen: 'ESPECES', referenceExterne: null, recuNumero: '2026-000046', enregistreLe: `${AUJOURDHUI}T10:00:00Z` },
        { ...PAIEMENT, id: 'p3', inscriptionId: 'i2', montant: 10_000, moyen: 'ESPECES', referenceExterne: null, recuNumero: '2026-000047', enregistreLe: `${AUJOURDHUI}T11:00:00Z` },
      ],
      eleves: {
        i1: { matricule: '2026-00012', nom: 'OUEDRAOGO', prenoms: 'Awa', classeCode: '2nde F3 A' },
        i2: { matricule: '2026-00031', nom: 'SAWADOGO', prenoms: 'Ali', classeCode: '1re F3' },
      },
    });
    await attendre();
    const t = texte(f);
    const tuiles = [...(f.nativeElement as HTMLElement).querySelectorAll('.chiffres strong')].map((x) => x.textContent?.replace(/\s/g, ' '));
    expect(tuiles).toEqual(['32 500 FCFA', '25 000 FCFA', '7 500 FCFA']);
    expect(t).toContain('Espèces · 2');
    expect(t).toContain('3 paiement(s)');
    // Le plus récent en premier, avec le nom de l'élève et sa classe
    const premiere = (f.nativeElement as HTMLElement).querySelector('tbody tr')!.textContent!.replace(/\s+/g, ' ');
    expect(premiere).toContain('2026-000047');
    expect(premiere).toContain('SAWADOGO Ali');
    expect(premiere).toContain('1re F3');
  });

  it('classes : retards d’une classe, puis relance par SMS', async () => {
    await session(http, 'INTENDANT', true);
    const f = TestBed.createComponent(ClassesScoPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/annees/a1/classes').flush([
      { id: 'c2', code: '1re F3', niveau: '1re' },
      { id: 'c1', code: '2nde F3 A', niveau: '2nde' },
    ]);
    await attendre();
    await saisir(f, '#classe', 'c1');
    await attendre();
    const ligne = (id: string, nom: string, reste: number, retard: number) => ({
      inscriptionId: id, matricule: null, nom, prenoms: 'X', statutBourse: 'NON_BOURSIER', totalFamille: 90_000,
      payeFamille: 90_000 - reste, resteFamille: reste, retardFamille: retard, totalOrganisme: 0, payeOrganisme: 0, resteOrganisme: 0, derniereRelance: null,
    });
    const etat = {
      classeId: 'c1', classeCode: '2nde F3 A',
      eleves: [ligne('i1', 'ZONGO', 0, 0), ligne('i2', 'BAMBARA', 60_000, 30_000), ligne('i3', 'OUEDRAOGO', 45_000, 15_000)],
      totalFamille: 270_000, payeFamille: 165_000, retardFamille: 45_000, totalOrganisme: 0, payeOrganisme: 0,
      tauxRecouvrementFamille: 61.1, tauxRecouvrementOrganisme: null,
    };
    http.expectOne('/api/v1/classes/c1/scolarite').flush(etat);
    await attendre();
    expect(texte(f)).toContain('61,1 %');
    bouton(f, 'En retard').click();
    const noms = () => [...(f.nativeElement as HTMLElement).querySelectorAll('tbody td:first-child strong')].map((x) => x.textContent);
    f.detectChanges();
    expect(noms()).toEqual(['BAMBARA', 'OUEDRAOGO']); // le plus gros retard d'abord

    bouton(f, 'Relancer la classe par SMS').click();
    await attendre();
    const relance = http.expectOne((r) => r.url === '/api/v1/scolarite/relances');
    expect(relance.request.params.get('classeId')).toBe('c1');
    relance.flush({ envoyees: 2, sansContact: 0, dejaRelancees: 0 });
    await attendre();
    http.expectOne('/api/v1/classes/c1/scolarite').flush(etat);
    await attendre();
    expect(texte(f)).toContain('Relance de la classe : 2 SMS envoyé(s)');
  });

  it('frais : nouveau frais en trois tranches réparties à parts égales', async () => {
    await session(http, 'INTENDANT', true);
    const f = TestBed.createComponent(FraisPage);
    f.detectChanges();
    await attendre();
    const charger = (frais: object[], filieres = true) => {
      http.expectOne('/api/v1/annees/a1/frais').flush(frais);
      if (filieres) {
        http.expectOne('/api/v1/filieres').flush([{ id: 'fi1', code: 'F3', libelle: 'Électrotechnique' }]);
      }
      http.expectOne('/api/v1/annees/a1/classes').flush([{ id: 'c1', code: '2nde F3 A', niveau: '2nde' }]);
      http.expectOne('/api/v1/organismes').flush([]);
      http.expectOne('/api/v1/parametres/scolarite').flush({ tauxBoursier: 100, tauxSemiBoursier: 50, delaiRelanceJours: 7, relancesAutomatiques: false });
    };
    charger([]);
    await attendre();
    expect(texte(f)).toContain('Aucun frais pour cette année');
    expect(texte(f)).toContain('50 % pour un semi-boursier');

    bouton(f, 'Nouveau frais').click();
    await saisir(f, '#libelle', 'Scolarité');
    await saisir(f, '#montant', '100 000');
    bouton(f, 'Ajouter une tranche').click();
    bouton(f, 'Ajouter une tranche').click();
    bouton(f, 'Ajouter une tranche').click();
    const dates = ['2026-10-15', '2027-01-15', '2027-04-15'];
    f.detectChanges();
    const champsDate = [...(f.nativeElement as HTMLElement).querySelectorAll('.tranche input[type=date]')] as HTMLInputElement[];
    champsDate.forEach((c, i) => {
      c.value = dates[i];
      c.dispatchEvent(new Event('change'));
    });
    f.detectChanges();
    bouton(f, 'Répartir à parts égales').click();
    expect(texte(f)).toMatch(/Total des tranches : 100.000 FCFA/);
    bouton(f, 'Enregistrer').click();
    await attendre();
    const creation = http.expectOne('/api/v1/annees/a1/frais');
    expect(creation.request.method).toBe('POST');
    expect(creation.request.body).toEqual({
      libelle: 'Scolarité', montant: 100_000, obligatoire: true, couvertParBourse: true, portee: 'TOUTES',
      filieres: [], niveaux: [], classes: [],
      tranches: [{ dateLimite: '2026-10-15', montant: 33_334 }, { dateLimite: '2027-01-15', montant: 33_333 }, { dateLimite: '2027-04-15', montant: 33_333 }],
    });
    const cree = { id: 'f1', anneeId: 'a1', ...(creation.request.body as object), tranches: dates.map((d, i) => ({ numero: i + 1, dateLimite: d, montant: i ? 33_333 : 33_334 })) };
    creation.flush(cree);
    await attendre();
    charger([cree], false);
    await attendre();
    const t = texte(f);
    expect(t).toMatch(/Scolarité : 100.000 FCFA ajouté/);
    expect(t).toMatch(/3 tranche\(s\) : 33.334 FCFA au 15\/10\/2026/);
  });
});
