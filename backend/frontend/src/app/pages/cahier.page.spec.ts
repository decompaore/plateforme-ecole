import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SessionService } from '../core/session.service';
import { CahierService, SeanceLocale } from '../hors-ligne/cahier.service';
import { StockageMemoire } from '../hors-ligne/stockage';
import { dureeHeures, erreurSeance } from '../progression/modeles-progression';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { CahierPage } from './cahier.page';

const FICHE = {
  id: 'fp1', classeId: 'c1', classeCode: '2nde F3', matiereId: 'm1', matiereCode: 'ELEC', matiereLibelle: 'Électrotechnique',
  type: 'TECHNIQUE', domaine: 'TECHNIQUE', engagementId: 'g1', enseignant: 'SANOU Paul', statut: 'VISEE',
  sequences: [
    { ordre: 1, titre: 'Lois de l’électricité', contenu: null, competences: null, heuresPrevues: 10, semaineDebut: null, heuresRealisees: 2, seances: 1 },
    { ordre: 2, titre: 'Installations', contenu: null, competences: null, heuresPrevues: 6, semaineDebut: null, heuresRealisees: 0, seances: 0 },
  ],
  heuresPrevues: 16, volumeHebdo: 4, volumeTotal: null, modifieeLe: null, soumiseLe: null, viseLe: null, visePar: null,
  commentaireVisa: null, auteur: true, modifiable: true, visable: false,
  avancement: { heuresRealisees: 2, heuresHorsSequence: 0, seances: 1, derniereSeance: '2026-10-01' },
};

const SEANCE = {
  id: 's0', classeId: 'c1', matiereId: 'm1', date: '2026-10-01', heureDebut: '08:00:00', heureFin: '10:00:00', heures: 2,
  sequenceOrdre: 1, sequenceTitre: 'Lois de l’électricité', contenu: 'Loi d’Ohm', travailAFaire: null,
  saisiLe: '2026-10-01T10:00:00Z', modifieLe: '2026-10-01T10:00:00Z',
};

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

describe('Cahier de textes', () => {
  let http: HttpTestingController;
  let stockage: StockageMemoire;

  beforeEach(async () => {
    ({ http, stockage } = configurer());
    const connexion = TestBed.inject(SessionService).connexion('61000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u1'));
    await attendre();
    http.expectOne('/api/v1/moi').flush({ nom: 'SANOU', prenoms: 'Paul' });
    await connexion;
  });

  afterEach(() => http.verify());

  it('contrôle la séance avant l’enregistrement', () => {
    expect(dureeHeures('08:00', '10:30')).toBe(2.5);
    expect(dureeHeures('10:00', '08:00')).toBeNull();
    const d = { date: '2026-10-02', heureDebut: '08:00', heureFin: '10:00', contenu: 'Loi d’Ohm', travailAFaire: '' };
    expect(erreurSeance(d, '2026-10-02')).toBeNull();
    expect(erreurSeance({ ...d, date: '2026-10-03' }, '2026-10-02')).toContain('après le cours');
    expect(erreurSeance({ ...d, heureFin: '07:00' }, '2026-10-02')).toContain('heure de fin');
    expect(erreurSeance({ ...d, heureFin: '18:00' }, '2026-10-02')).toContain('8 heures');
    expect(erreurSeance({ ...d, contenu: ' ' }, '2026-10-02')).toContain('ce qui a été fait');
  });

  it('après l’appel : créneau prérempli, séance gardée sans réseau puis envoyée sans doublon', async () => {
    const f = TestBed.createComponent(CahierPage);
    f.componentRef.setInput('classeId', 'c1');
    f.componentRef.setInput('matiereId', 'm1');
    f.componentRef.setInput('date', '2026-10-02');
    f.componentRef.setInput('debut', '08:00');
    f.componentRef.setInput('fin', '10:00');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/espace-enseignant/affectations').flush({
      enseignant: { type: 'TITULAIRE', statut: 'ACTIF', fin: null, motifFin: null, finProgrammee: false },
      anneeId: 'a1',
      affectations: [{ classeId: 'c1', classeCode: '2nde F3', matiereId: 'm1', matiereCode: 'ELEC', matiereLibelle: 'Électrotechnique', volumeHebdo: 4, volumeTotal: null, engagementId: 'g1' }],
      chargeHebdomadaire: 4,
    });
    await attendre();
    http.expectOne('/api/v1/classes/c1/matieres/m1/progression').flush(FICHE);
    http.expectOne('/api/v1/classes/c1/matieres/m1/cahier-textes').flush([SEANCE]);
    await attendre();

    let t = texte(f);
    expect(t).toContain('Électrotechnique · 2nde F3');
    expect(t).toContain('2 h / 10 h');
    expect(t).toContain('Loi d’Ohm');
    const sequence = (f.nativeElement as HTMLElement).querySelector('#sequence') as HTMLSelectElement;
    expect(sequence.value).toBe('1'); // séquence en cours proposée
    expect(((f.nativeElement as HTMLElement).querySelector('#debut') as HTMLInputElement).value).toBe('08:00');

    const contenu = (f.nativeElement as HTMLElement).querySelector('#contenu') as HTMLTextAreaElement;
    contenu.value = 'Puissance et énergie';
    contenu.dispatchEvent(new Event('input'));
    f.detectChanges();
    const bouton = [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.includes('Enregistrer'))!;
    expect(bouton.textContent).toContain('(2 h)');
    bouton.click();
    await attendre();

    // Coupure : la séance reste sur le téléphone
    const premier = http.expectOne((r) => r.method === 'PUT' && r.url.startsWith('/api/v1/cahier-textes/'));
    const id = premier.request.url.split('/').pop()!;
    expect(premier.request.body).toEqual({
      classeId: 'c1', matiereId: 'm1', date: '2026-10-02', heureDebut: '08:00', heureFin: '10:00',
      sequenceOrdre: 1, contenu: 'Puissance et énergie', travailAFaire: null,
    });
    premier.error(new ProgressEvent('error'), { status: 0 });
    await attendre();
    t = texte(f);
    expect(t).toContain('Séance enregistrée sur le téléphone');
    expect(t).toContain('en attente d\'envoi');
    expect(t).toContain('4 h / 10 h'); // réalisé compté avec la séance en attente
    expect((await stockage.lister<SeanceLocale>('cahier:u1:etab-1:')).length).toBe(1);

    // Retour du réseau : même identifiant renvoyé, la séance rejoint la liste
    void TestBed.inject(CahierService).synchroniser();
    await attendre();
    const renvoi = http.expectOne(`/api/v1/cahier-textes/${id}`);
    renvoi.flush({ ...SEANCE, id, date: '2026-10-02', contenu: 'Puissance et énergie' });
    await attendre();
    t = texte(f);
    expect(t).not.toContain('en attente d\'envoi');
    expect(t).toContain('Séance envoyée.');
    expect(t).toContain('Puissance et énergie');
    expect((await stockage.lister<SeanceLocale>('cahier:u1:etab-1:')).length).toBe(0);
  });

  it('refus du serveur : la séance reste visible avec le message', async () => {
    const cahier = TestBed.inject(CahierService);
    await cahier.enregistrer('s9', {
      classeId: 'c1', matiereId: 'm1', date: '2026-10-02', heureDebut: '08:00', heureFin: '10:00', sequenceOrdre: null,
      contenu: 'Doublon', travailAFaire: null,
    }, { classeCode: '2nde F3', matiereLibelle: 'Électrotechnique' });
    await attendre();
    http.expectOne('/api/v1/cahier-textes/s9').flush(
      { title: 'Conflit', detail: 'Une séance est déjà notée le 2026-10-02 à 08:00', code: 'SEANCE_EXISTANTE' },
      { status: 409, statusText: 'Conflict' },
    );
    await attendre();
    expect(cahier.refusees()).toBe(1);
    expect(cahier.operations()[0].message).toContain('déjà notée');
  });
});
