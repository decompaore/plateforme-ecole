import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { SessionService } from '../core/session.service';
import { NotesService } from '../hors-ligne/notes.service';
import { StockageMemoire } from '../hors-ligne/stockage';
import { attendre, configurer, LYCEE } from '../testing/outils-test';
import { NotesSaisiePage } from './notes-saisie.page';

describe('NotesSaisiePage hors connexion', () => {
  let http: HttpTestingController;
  let stockage: StockageMemoire;

  beforeEach(async () => {
    ({ http, stockage } = configurer());
    await stockage.ecrire('session:profil', {
      utilisateurId: 'u1',
      nom: 'SANOU',
      prenoms: 'Paul',
      superAdmin: false,
      etablissement: LYCEE,
      doitChangerMotDePasse: false,
    });
    await stockage.ecrire('cache:u1:etab-1:affectations', {
      anneeId: 'a1',
      prepareLe: '2026-10-04T18:00:00Z',
      affectations: [{ classeId: 'c1', classeCode: '2nde F3', matiereId: 'm1', matiereLibelle: 'Électrotechnique', engagementId: 'g1' }],
    });
    await stockage.ecrire('cache:u1:etab-1:classe:c1', {
      classeId: 'c1',
      profilId: 'pt',
      prepareLe: '2026-10-04T18:00:00Z',
      eleves: [
        { inscriptionId: 'i1', nom: 'COMPAORE', prenoms: 'Aïcha', matricule: null, sexe: 'F' },
        { inscriptionId: 'i2', nom: 'KABORE', prenoms: 'Ali', matricule: null, sexe: 'M' },
        { inscriptionId: 'i3', nom: 'ZONGO', prenoms: 'Rasmata', matricule: null, sexe: 'F' },
      ],
    });
    await stockage.ecrire('cache:u1:etab-1:evaluation:e1', {
      evaluation: {
        id: 'e1', classeId: 'c1', matiereId: 'm1', periodeId: 'p1', libelle: 'Devoir 1', type: 'DEVOIR',
        date: '2026-10-05', bareme: 20, poids: 1, notesSaisies: 1, effectif: 3,
      },
      notes: { i3: { valeur: 12, absent: false } },
      copieLe: '2026-10-05T10:00:00Z',
    });
    const demarrage = TestBed.inject(SessionService).demarrer();
    await attendre();
    http.expectOne('/api/v1/auth/rafraichir').error(new ProgressEvent('error'), { status: 0 });
    await demarrage;
  });

  afterEach(() => http.verify());

  it('saisit des notes et une absence, refuse une note hors barème, puis met la feuille en file d’envoi', async () => {
    const f = TestBed.createComponent(NotesSaisiePage);
    f.componentRef.setInput('classeId', 'c1');
    f.componentRef.setInput('matiereId', 'm1');
    f.componentRef.setInput('evaluationId', 'e1');
    await f.whenStable();
    await attendre();
    f.detectChanges();

    const page: HTMLElement = f.nativeElement;
    const champs = [...page.querySelectorAll<HTMLInputElement>('input.note')];
    expect(champs).toHaveLength(3);
    expect(champs[2].value).toBe('12'); // note déjà enregistrée, affichée hors connexion

    const taper = (i: number, v: string) => {
      champs[i].value = v;
      champs[i].dispatchEvent(new Event('input'));
      f.detectChanges();
    };
    const enregistrer = () => [...page.querySelectorAll('button')].find((b) => b.textContent?.includes('Enregistrer'))!;

    taper(0, '25');
    expect(page.textContent).toContain('1 note(s) invalide(s)');
    expect(enregistrer().disabled).toBe(true);

    taper(0, '14,5');
    (page.querySelectorAll<HTMLButtonElement>('button.abs')[1]).click();
    f.detectChanges();
    expect(enregistrer().textContent).toContain('(2)');

    // Abs par erreur puis retiré : la note d'origine revient (elle ne doit pas être effacée)
    const absZongo = page.querySelectorAll<HTMLButtonElement>('button.abs')[2];
    absZongo.click();
    f.detectChanges();
    absZongo.click();
    f.detectChanges();
    expect(champs[2].value).toBe('12');
    expect(enregistrer().textContent).toContain('(2)');

    enregistrer().click();
    await attendre();
    f.detectChanges();

    const op = TestBed.inject(NotesService).operations()[0];
    expect(op.type).toBe('NOTES');
    // Seuls les deux élèves modifiés partiront : la note de ZONGO n'est pas renvoyée
    expect(op.notes).toEqual({ i1: { valeur: 14.5, absent: false }, i2: { valeur: null, absent: true } });
    expect(page.textContent).toContain('2 note(s) enregistrée(s) sur le téléphone');
    expect(f.componentInstance.aDesModifications()).toBe(false);
    http.expectNone('/api/v1/evaluations/e1/notes');
    // Pas de jeton : le service a tenté de rouvrir la session pour envoyer
    http.match('/api/v1/auth/rafraichir').forEach((r) => r.error(new ProgressEvent('error'), { status: 0 }));
  });
});
