import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { SessionService } from '../core/session.service';
import { lireNote } from '../pages/notes-saisie.page';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { EvaluationServeur, NotesService, OperationNotes } from './notes.service';
import { StockageMemoire } from './stockage';

const LIBELLES = { classeCode: '2nde F3', matiereLibelle: 'Électrotechnique' };
const DEVOIR = {
  classeId: 'c1',
  matiereId: 'm1',
  periodeId: 'p1',
  libelle: 'Devoir 1',
  type: 'DEVOIR' as const,
  date: '2026-10-05',
  bareme: 20,
  poids: 1,
};

function vue(id: string, autres: Partial<EvaluationServeur> = {}): EvaluationServeur {
  return { ...DEVOIR, id, notesSaisies: 0, effectif: 3, ...autres };
}

describe('NotesService', () => {
  let http: HttpTestingController;
  let stockage: StockageMemoire;
  let notes: NotesService;

  beforeEach(async () => {
    ({ http, stockage } = configurer());
    notes = TestBed.inject(NotesService);
    const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u1'));
    await attendre();
    http.expectOne('/api/v1/moi').flush({ nom: 'SANOU', prenoms: 'Paul' });
    await connexion;
  });

  afterEach(() => http.verify());

  async function operations(): Promise<OperationNotes[]> {
    return stockage.lister<OperationNotes>('notes:u1:etab-1:');
  }

  it('crée l’évaluation avec un identifiant de l’appareil : un renvoi après coupure ne fait pas de doublon', async () => {
    const id = await notes.creer(DEVOIR, LIBELLES);
    await attendre();
    const premier = http.expectOne('/api/v1/classes/c1/evaluations');
    expect(premier.request.body).toMatchObject({ matiereId: 'm1', periodeId: 'p1', libelle: 'Devoir 1', idClient: id });
    premier.error(new ProgressEvent('error'), { status: 0 });
    await attendre();
    expect(notes.enAttente()).toBe(1);

    const bilan = notes.synchroniser();
    await attendre();
    const renvoi = http.expectOne('/api/v1/classes/c1/evaluations');
    expect(renvoi.request.body.idClient).toBe(id);
    renvoi.flush(vue(id));
    expect(await bilan).toMatchObject({ envoyees: 1, restantes: 0 });

    // Hors connexion ensuite, l'évaluation reste visible dans la liste de l'appareil
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    const liste = await notes.evaluations('c1', 'm1', 'p1');
    expect(liste.map((e) => e.id)).toEqual([id]);
  });

  it('n’envoie que les élèves modifiés et regroupe les saisies d’une même évaluation', async () => {
    const ev = vue('e1');
    await stockage.ecrire('cache:u1:etab-1:evaluation:e1', { evaluation: ev, notes: { i9: { valeur: 12, absent: false } }, copieLe: '' });
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);

    await notes.enregistrerNotes(ev, { i1: { valeur: 14.5, absent: false } }, LIBELLES);
    await notes.enregistrerNotes(ev, { i2: { valeur: null, absent: true }, i1: { valeur: 15, absent: false } }, LIBELLES);
    const ops = await operations();
    expect(ops).toHaveLength(1);
    expect(ops[0].notes).toEqual({ i1: { valeur: 15, absent: false }, i2: { valeur: null, absent: true } });

    // La feuille affichée = notes du serveur + modifications en attente
    const feuille = await notes.feuille('e1');
    expect(feuille.notes).toEqual({
      i9: { valeur: 12, absent: false },
      i1: { valeur: 15, absent: false },
      i2: { valeur: null, absent: true },
    });

    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);
    const bilan = notes.synchroniser();
    await attendre();
    const put = http.expectOne('/api/v1/evaluations/e1/notes');
    expect(put.request.method).toBe('PUT');
    // L'élève i9 (noté par ailleurs) n'est pas envoyé : sa note ne peut pas être effacée
    expect(put.request.body).toEqual({
      notes: [
        { inscriptionId: 'i1', valeur: 15, absent: false },
        { inscriptionId: 'i2', valeur: null, absent: true },
      ],
    });
    put.flush({
      evaluation: vue('e1', { notesSaisies: 3 }),
      lignes: [
        { inscriptionId: 'i1', valeur: 15, absent: false },
        { inscriptionId: 'i2', valeur: null, absent: true },
        { inscriptionId: 'i9', valeur: 12, absent: false },
      ],
      ignorees: [],
    });
    expect(await bilan).toMatchObject({ envoyees: 1, restantes: 0 });
    expect(await operations()).toHaveLength(0);
  });

  it('création refusée (période verrouillée) : ses notes ne partent pas et s’abandonnent avec elle', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    const id = await notes.creer(DEVOIR, LIBELLES);
    await notes.enregistrerNotes(vue(id), { i1: { valeur: 10, absent: false } }, LIBELLES);
    await attendre(); // la tentative d'envoi hors connexion se termine
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(true);

    const bilan = notes.synchroniser();
    await attendre();
    http.expectOne('/api/v1/classes/c1/evaluations').flush(
      { title: 'Règle', detail: 'La période est verrouillée', code: 'PERIODE_VERROUILLEE' },
      { status: 409, statusText: 'Conflict' },
    );
    expect(await bilan).toMatchObject({ refusees: 1, restantes: 1 });
    http.expectNone(`/api/v1/evaluations/${id}/notes`);
    expect(notes.refusees()).toBe(1);
    expect(notes.operations().find((o) => o.type === 'CREATION')?.message).toBe('La période est verrouillée');

    await notes.abandonner(notes.operations().find((o) => o.type === 'CREATION')!);
    expect(await operations()).toHaveLength(0);
  });

  it('note refusée par le serveur : la saisie corrigée repart', async () => {
    const ev = vue('e1');
    await notes.enregistrerNotes(ev, { i1: { valeur: 19, absent: false } }, LIBELLES);
    await attendre();
    http.expectOne('/api/v1/evaluations/e1/notes').flush(
      { title: 'Requête invalide', detail: 'Note supérieure au barème' },
      { status: 400, statusText: 'Bad Request' },
    );
    await attendre();
    expect(notes.refusees()).toBe(1);

    await notes.enregistrerNotes(ev, { i1: { valeur: 18, absent: false } }, LIBELLES);
    await attendre();
    const put = http.expectOne('/api/v1/evaluations/e1/notes');
    expect(put.request.body.notes).toEqual([{ inscriptionId: 'i1', valeur: 18, absent: false }]);
    put.flush({ evaluation: ev, lignes: [{ inscriptionId: 'i1', valeur: 18, absent: false }], ignorees: [] });
    await attendre();
    expect(notes.refusees()).toBe(0);
    expect(notes.enAttente()).toBe(0);
  });

  it('une saisie faite pendant un envoi n’est pas perdue : elle part au passage suivant', async () => {
    const ev = vue('e1');
    await notes.enregistrerNotes(ev, { i1: { valeur: 12, absent: false } }, LIBELLES);
    await attendre();
    const premier = http.expectOne('/api/v1/evaluations/e1/notes');
    // Pendant que la réponse se fait attendre, l'enseignant enregistre d'autres notes
    await notes.enregistrerNotes(ev, { i2: { valeur: 9, absent: false } }, LIBELLES);
    premier.flush({ evaluation: ev, lignes: [{ inscriptionId: 'i1', valeur: 12, absent: false }], ignorees: [] });
    await attendre();
    const second = http.expectOne('/api/v1/evaluations/e1/notes');
    expect(second.request.body.notes).toEqual([{ inscriptionId: 'i2', valeur: 9, absent: false }]);
    second.flush({ evaluation: ev, lignes: [], ignorees: [] });
    await attendre();
    expect(await operations()).toHaveLength(0);
  });

  it('notes classées avant leur évaluation (horloge qui recule) : envoyées après la création', async () => {
    const id = 'e-neuve';
    const prefixe = 'notes:u1:etab-1:';
    const base = { evaluationId: id, classeId: 'c1', matiereId: 'm1', periodeId: 'p1', libelle: 'Devoir 1', ...LIBELLES, etat: 'EN_ATTENTE' as const };
    await stockage.ecrire(`${prefixe}2026-10-05T09:00:00Z:${id}:notes`, {
      ...base, cle: `${prefixe}2026-10-05T09:00:00Z:${id}:notes`, type: 'NOTES', notes: { i1: { valeur: 11, absent: false } }, saisieLe: '2026-10-05T09:00:00Z',
    });
    await stockage.ecrire(`${prefixe}2026-10-05T10:00:00Z:${id}:creation`, {
      ...base, cle: `${prefixe}2026-10-05T10:00:00Z:${id}:creation`, type: 'CREATION', evaluation: DEVOIR, saisieLe: '2026-10-05T10:00:00Z',
    });
    notes.synchroniser();
    await attendre();
    http.expectNone(`/api/v1/evaluations/${id}/notes`);
    http.expectOne('/api/v1/classes/c1/evaluations').flush(vue(id));
    await attendre();
    const put = http.expectOne(`/api/v1/evaluations/${id}/notes`);
    expect(put.request.body.notes).toEqual([{ inscriptionId: 'i1', valeur: 11, absent: false }]);
    put.flush({ evaluation: vue(id), lignes: [], ignorees: [] });
    await attendre();
    expect(await operations()).toHaveLength(0);
  });

  it('conflit technique à la création (deux envois simultanés) : pas un refus, nouvel essai', async () => {
    await notes.creer(DEVOIR, LIBELLES);
    await attendre();
    http.expectOne('/api/v1/classes/c1/evaluations').flush(
      { title: 'Conflit', detail: 'L’opération entre en conflit avec des données existantes' },
      { status: 409, statusText: 'Conflict' },
    );
    await attendre();
    expect(notes.refusees()).toBe(0);
    expect(notes.enAttente()).toBe(1);
  });

  it('plus de 200 notes : envoyées en plusieurs fois (limite du serveur)', async () => {
    const ev = vue('e1', { effectif: 250 });
    const beaucoup = Object.fromEntries(Array.from({ length: 250 }, (_, i) => [`i${i}`, { valeur: 10, absent: false }]));
    await notes.enregistrerNotes(ev, beaucoup, LIBELLES);
    await attendre();
    const a = http.expectOne('/api/v1/evaluations/e1/notes');
    expect(a.request.body.notes).toHaveLength(200);
    a.flush({ evaluation: ev, lignes: [], ignorees: [] });
    await attendre();
    const b = http.expectOne('/api/v1/evaluations/e1/notes');
    expect(b.request.body.notes).toHaveLength(50);
    b.flush({ evaluation: ev, lignes: [], ignorees: [] });
    await attendre();
    expect(await operations()).toHaveLength(0);
  });

  it('création refusée puis corrigée : repart avec la nouvelle date', async () => {
    const id = await notes.creer(DEVOIR, LIBELLES);
    await attendre();
    http.expectOne('/api/v1/classes/c1/evaluations').flush(
      { title: 'Règle', detail: 'La date est hors de la période', code: 'DATE_HORS_PERIODE' },
      { status: 409, statusText: 'Conflict' },
    );
    await attendre();
    const op = notes.operations()[0];
    expect(op.etat).toBe('REFUSE');
    await notes.corrigerCreation(op, { date: '2026-10-06', bareme: 20, poids: 1 });
    await attendre();
    const renvoi = http.expectOne('/api/v1/classes/c1/evaluations');
    expect(renvoi.request.body).toMatchObject({ date: '2026-10-06', idClient: id });
    renvoi.flush(vue(id, { date: '2026-10-06' }));
    await attendre();
    expect(await operations()).toHaveLength(0);
  });

  it('feuille jamais chargée hors connexion : signalée incomplète', async () => {
    vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    const f = await notes.feuille('inconnue');
    expect(f.evaluation).toBeUndefined();
    expect(f.complete).toBe(false);
  });
});

describe('lireNote', () => {
  it('accepte la virgule et deux décimales au plus', () => {
    expect(lireNote('14,5')).toBe(14.5);
    expect(lireNote(' 14.25 ')).toBe(14.25);
    expect(lireNote('')).toBeNull();
    expect(lireNote('14,555')).toBeNaN();
    expect(lireNote('abc')).toBeNaN();
    expect(lireNote('-3')).toBeNaN();
  });
});
