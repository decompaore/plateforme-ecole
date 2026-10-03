import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { dateLocale } from '../core/outils';
import { SessionService } from '../core/session.service';
import { StockageMemoire } from '../hors-ligne/stockage';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { AbsenceVue } from '../vie-scolaire/modeles-vs';
import { fcfa, SituationVue } from './modeles-parent';
import { EnfantPage } from './pages/enfant.page';
import { EnfantsPage } from './pages/enfants.page';
import { indicateurAbsences, indicateurScolarite } from './resume';

const ETAB = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: ['PARENT' as const] };
const AUJOURDHUI = dateLocale();

const ENFANTS = [
  { eleveId: 'e1', matricule: '2026-00001', nom: 'OUEDRAOGO', prenoms: 'Awa', sexe: 'F', dateNaissance: '2010-03-14', lien: 'MERE', anneeLibelle: '2026-2027', classeCode: '2nde F3 A', statut: 'ACTIVE' },
  { eleveId: 'e2', matricule: null, nom: 'OUEDRAOGO', prenoms: 'Issa', sexe: 'M', dateNaissance: '2013-05-02', lien: 'MERE', anneeLibelle: '2026-2027', classeCode: '6e B', statut: 'ACTIVE' },
];

function absence(date: string, justifiee: boolean, type: 'ABSENCE' | 'RETARD' = 'ABSENCE', matiere: string | null = 'Mathématiques'): AbsenceVue {
  return {
    id: `${date}-${type}-${matiere}`, appelId: 'a', inscriptionId: 'i1', date, heureDebut: '08:00:00', heureFin: '10:00:00', type,
    minutesRetard: type === 'RETARD' ? 10 : null, justifiee, matiereId: matiere ? 'm' : null, matiereCode: null, matiereLibelle: matiere,
  };
}

function situation(autres: Partial<SituationVue> = {}): SituationVue {
  return {
    inscriptionId: 'i1', eleveId: 'e1', classeCode: '2nde F3 A', annee: '2026-2027', organisme: null,
    totalFamille: 75000, payeFamille: 25000, resteFamille: 50000, retardFamille: 25000, avanceFamille: 0, prochaineEcheance: '2027-01-15',
    echeances: [
      { fraisId: 'f1', libelle: 'Scolarité', numero: 1, nombreTranches: 3, dateLimite: '2026-09-15', montant: 25000, exoneration: 0, partOrganisme: 0, partFamille: 25000, payeFamille: 25000, payeOrganisme: 0, resteFamille: 0, resteOrganisme: 0, enRetard: false },
      { fraisId: 'f1', libelle: 'Scolarité', numero: 2, nombreTranches: 3, dateLimite: '2026-09-30', montant: 25000, exoneration: 0, partOrganisme: 0, partFamille: 25000, payeFamille: 0, payeOrganisme: 0, resteFamille: 25000, resteOrganisme: 0, enRetard: true },
      { fraisId: 'f1', libelle: 'Scolarité', numero: 3, nombreTranches: 3, dateLimite: '2027-01-15', montant: 25000, exoneration: 0, partOrganisme: 0, partFamille: 25000, payeFamille: 0, payeOrganisme: 0, resteFamille: 25000, resteOrganisme: 0, enRetard: false },
    ],
    exonerations: [],
    paiements: [{ id: 'p1', inscriptionId: 'i1', montant: 25000, moyen: 'ESPECES', payeur: 'FAMILLE', datePaiement: '2026-09-10', recuNumero: 'R-2026-0001', annule: false }],
    ...autres,
  };
}

async function session(http: HttpTestingController): Promise<void> {
  const connexion = TestBed.inject(SessionService).connexion('62000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('par1', 1, { etablissementActif: ETAB, etablissements: [ETAB] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'OUEDRAOGO', prenoms: 'Mariam' });
  await connexion;
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/[ \t\n\r]+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement {
  f.detectChanges();
  const b = [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((x) => x.textContent?.includes(libelle));
  if (!b) {
    throw new Error(`Bouton « ${libelle} » introuvable`);
  }
  return b;
}

describe('Espace parent : résumés', () => {
  it('signale les absences non justifiées des 30 derniers jours, et rien au-delà', () => {
    expect(indicateurAbsences([], '2026-10-20').ton).toBe('bon');
    const l = [absence('2026-10-19', false), absence('2026-10-19', false, 'ABSENCE', 'Français'), absence('2026-10-02', true), absence('2026-08-01', false)];
    expect(indicateurAbsences(l, '2026-10-20')).toEqual({ texte: "1 jour(s) d'absence non justifiée ces 30 derniers jours", ton: 'alerte' });
    expect(indicateurAbsences([absence('2026-10-02', true)], '2026-10-20').ton).toBe('neutre');
  });

  it('résume la scolarité : retard, reste, soldée', () => {
    expect(indicateurScolarite(situation())).toEqual({ texte: `${fcfa(25000)} en retard`, ton: 'alerte' });
    expect(indicateurScolarite(situation({ retardFamille: 0 }))?.texte).toBe(`Reste ${fcfa(50000)}, prochaine échéance le 15/01/2027`);
    expect(indicateurScolarite(situation({ retardFamille: 0, resteFamille: 0 }))).toEqual({ texte: 'Scolarité soldée', ton: 'bon' });
    expect(fcfa(25000)).toBe('25 000 FCFA');
  });
});

describe('Espace parent', () => {
  let http: HttpTestingController;
  let stockage: StockageMemoire;

  beforeEach(() => {
    ({ http, stockage } = configurer());
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  function repondreResumes(id: string, absences: AbsenceVue[], s: SituationVue[], convocations: object[] = []): void {
    http.expectOne(`/api/v1/espace-parent/enfants/${id}/absences`).flush(absences);
    http.expectOne(`/api/v1/espace-parent/enfants/${id}/scolarite`).flush(s);
    http.expectOne(`/api/v1/espace-parent/enfants/${id}/vie-scolaire`).flush([
      { inscriptionId: 'i', nom: '', prenoms: '', classe: '', annee: '2026-2027', synthese: {}, incidents: [], convocations },
    ]);
  }

  it('mes enfants : un résumé par enfant, puis la même situation sans réseau', async () => {
    await session(http);
    let f = TestBed.createComponent(EnfantsPage);
    f.detectChanges();
    http.expectOne('/api/v1/espace-parent/enfants').flush(ENFANTS);
    await attendre();
    repondreResumes('e1', [absence(AUJOURDHUI, false)], [situation()], [
      { id: 'c1', inscriptionId: 'i1', nom: '', prenoms: '', classe: '', rendezVous: '2099-10-06T10:00:00', motif: 'Comportement', statut: 'PREVUE', compteRendu: null, incidentId: null },
    ]);
    repondreResumes('e2', [], [situation({ inscriptionId: 'i2', eleveId: 'e2', resteFamille: 0, retardFamille: 0, payeFamille: 75000 })]);
    await attendre();
    let t = texte(f);
    expect(t).toContain('Awa OUEDRAOGO');
    expect(t).toContain('Vous êtes convoqué(e) le 06/10/2099 à 10h00');
    expect(t).toContain("1 jour(s) d'absence non justifiée");
    expect(t).toContain(`${fcfa(25000)} en retard`);
    expect(t).toContain('Issa OUEDRAOGO');
    expect(t).toContain('Scolarité soldée');
    expect(t).not.toContain('Pas de réseau');

    // Plus de réseau : la dernière situation reste lisible, datée
    f.destroy();
    f = TestBed.createComponent(EnfantsPage);
    f.detectChanges();
    const coupure = { status: 0 };
    http.expectOne('/api/v1/espace-parent/enfants').error(new ProgressEvent('error'), coupure);
    await attendre();
    for (const id of ['e1', 'e2']) {
      for (const quoi of ['absences', 'scolarite', 'vie-scolaire']) {
        http.expectOne(`/api/v1/espace-parent/enfants/${id}/${quoi}`).error(new ProgressEvent('error'), coupure);
      }
    }
    await attendre();
    t = texte(f);
    expect(t).toContain('Pas de réseau : situation du');
    expect(t).toContain(`${fcfa(25000)} en retard`);
    expect(t).toContain('Scolarité soldée');
    expect((await stockage.cles('cache:par1:etab-1:parent:')).length).toBe(7);
  });

  async function ouvrirEnfant(): Promise<ComponentFixture<EnfantPage>> {
    await session(http);
    const f = TestBed.createComponent(EnfantPage);
    f.componentRef.setInput('eleveId', 'e1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/espace-parent/enfants').flush(ENFANTS);
    http.expectOne('/api/v1/espace-parent/enfants/e1/absences').flush([
      absence('2026-10-05', false),
      absence('2026-10-05', false, 'ABSENCE', null),
      absence('2026-10-01', true, 'RETARD', 'Français'),
    ]);
    http.expectOne('/api/v1/espace-parent/enfants/e1/bulletins').flush([
      { id: 'b1', periode: 'Trimestre 1', classe: '2nde F3 A', moyenne: 12.456, rang: 1, effectif: 48, tauxMaitrise: null, distinction: 'FELICITATIONS', publieLe: '2026-12-22T10:00:00Z' },
    ]);
    http.expectOne('/api/v1/espace-parent/enfants/e1/vie-scolaire').flush([
      { inscriptionId: 'i1', nom: '', prenoms: '', classe: '', annee: '2026-2027', synthese: {}, convocations: [],
        incidents: [{ id: 'x', inscriptionId: 'i1', type: 'AVERTISSEMENT', date: '2026-10-03', motif: 'Bavardages', annule: false }] },
    ]);
    http.expectOne('/api/v1/espace-parent/enfants/e1/scolarite').flush([situation()]);
    await attendre();
    return f;
  }

  it('fiche de l’enfant : absences avec discipline, bulletin à télécharger, vie scolaire', async () => {
    const f = await ouvrirEnfant();
    let t = texte(f);
    expect(t).toContain('Awa OUEDRAOGO');
    expect(t).toContain('4 h');
    expect(t).toContain('08h00–10h00 · Mathématiques');
    expect(t).toContain('08h00–10h00 · appel général');
    expect(t).toContain('Français · retard de 10 min');
    expect(t).toContain('non justifiée');

    bouton(f, 'Bulletins').click();
    t = texte(f);
    expect(t).toContain('Trimestre 1');
    expect(t).toContain('12,46 / 20');
    expect(t).toContain('Félicitations');

    const creer = vi.fn(() => 'blob:bulletin');
    Object.assign(URL, { createObjectURL: creer, revokeObjectURL: vi.fn() });
    const clic = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    bouton(f, 'Télécharger').click();
    await attendre();
    const pdf = http.expectOne('/api/v1/espace-parent/bulletins/b1/pdf');
    expect(pdf.request.responseType).toBe('blob');
    pdf.flush(new Blob(['%PDF'], { type: 'application/pdf' }));
    await attendre();
    expect(creer).toHaveBeenCalled();
    expect(clic).toHaveBeenCalled();

    bouton(f, 'Vie scolaire').click();
    t = texte(f);
    expect(t).toContain('Avertissement');
    expect(t).toContain('Bavardages');
  });

  it('scolarité : paiement Mobile Money suivi jusqu’à la confirmation, puis situation relue', async () => {
    const f = await ouvrirEnfant();
    bouton(f, 'Scolarité').click();
    let t = texte(f);
    expect(t).toContain(`${fcfa(25000)} en retard`);
    expect(t).toContain('Reçu R-2026-0001');

    bouton(f, 'Payer par Mobile Money').click();
    f.detectChanges();
    await f.whenStable();
    const page = f.nativeElement as HTMLElement;
    // Montant proposé : le retard
    expect((page.querySelector('#montant') as HTMLInputElement).value).toBe('25000');
    (page.querySelector('input[value=TELECEL_MONEY]') as HTMLInputElement).click();
    expect(texte(f)).toContain('Numéro Telecel Money qui paie');
    const tel = page.querySelector('#telephone') as HTMLInputElement;
    tel.value = '70 11 22 33';
    tel.dispatchEvent(new Event('input'));
    f.detectChanges();

    vi.useFakeTimers();
    bouton(f, 'Payer 25').click();
    await vi.advanceTimersByTimeAsync(0);
    const demande = http.expectOne('/api/v1/espace-parent/inscriptions/i1/mobile-money');
    expect(demande.request.body).toMatchObject({ montant: 25000, operateur: 'TELECEL_MONEY', telephone: '70 11 22 33' });
    expect(demande.request.body.cleIdempotence).toMatch(/^[0-9a-f-]{36}$/);
    const tr = { id: 't1', inscriptionId: 'i1', montant: 25000, operateur: 'TELECEL_MONEY', telephone: '70112233', statut: 'EN_ATTENTE', reference: 'R', message: null, paiementId: null, expireLe: null };
    demande.flush(tr);
    await vi.advanceTimersByTimeAsync(0);
    expect(texte(f)).toContain('tapez votre code secret');

    // Suivi toutes les 5 secondes, jusqu'à la confirmation
    await vi.advanceTimersByTimeAsync(5000);
    http.expectOne('/api/v1/espace-parent/mobile-money/t1').flush(tr);
    await vi.advanceTimersByTimeAsync(5000);
    http.expectOne('/api/v1/espace-parent/mobile-money/t1').flush({ ...tr, statut: 'CONFIRMEE', paiementId: 'p2' });
    await vi.advanceTimersByTimeAsync(0);
    http.expectOne('/api/v1/espace-parent/enfants/e1/scolarite').flush([situation({ payeFamille: 50000, resteFamille: 25000, retardFamille: 0 })]);
    await vi.advanceTimersByTimeAsync(0);
    t = texte(f);
    expect(t).toContain('Paiement reçu. Merci');
    // Plus aucun suivi après la confirmation
    await vi.advanceTimersByTimeAsync(20000);
    http.expectNone('/api/v1/espace-parent/mobile-money/t1');
  });
});
