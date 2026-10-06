import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { NotesEvaluationsPage } from '../pages/notes-evaluations.page';
import { NotesSaisiePage } from '../pages/notes-saisie.page';
import { StockageMemoire } from '../hors-ligne/stockage';
import { attendre, configurer, LYCEE } from '../testing/outils-test';
import { SessionService } from './session.service';
import { correspond, filtrer, normaliser } from './recherche';

describe('Recherche', () => {
  it('ignore accents, majuscules et ordre des mots', () => {
    expect(normaliser('  KABORÉ   Aïcha ')).toBe('kabore aicha');
    expect(correspond('aicha kab', 'KABORÉ', 'Aïcha')).toBe(true);
    expect(correspond('kabore ali', 'KABORÉ', 'Aïcha')).toBe(false);
    expect(correspond('', 'n’importe quoi')).toBe(true);
  });

  it('retrouve un téléphone tapé avec des espaces, et un numéro d’ordre', () => {
    expect(correspond('61 00 00 01', '+22661000001')).toBe(true);
    expect(correspond('12', 'ZONGO', 12)).toBe(true);
    expect(filtrer([{ c: 'LTK' }, { c: 'CFP-REO' }], 'reo', (x) => [x.c])).toEqual([{ c: 'CFP-REO' }]);
  });
});

describe('Recherche et périodes côté enseignant, sans réseau', () => {
  let http: HttpTestingController;
  let stockage: StockageMemoire;
  const NOMS = ['COMPAORE', 'KABORE', 'ZONGO', 'OUEDRAOGO', 'SAWADOGO', 'TRAORE', 'SANOU', 'BAZIE', 'KONATE', 'DIALLO',
    'ILBOUDO', 'NIKIEMA', 'YAMEOGO', 'ZERBO', 'TAPSOBA', 'SOME', 'KAMBOU', 'DABIRE'];

  beforeEach(async () => {
    ({ http, stockage } = configurer());
    await stockage.ecrire('session:profil', {
      utilisateurId: 'u1', nom: 'SANOU', prenoms: 'Paul', superAdmin: false, etablissement: LYCEE, doitChangerMotDePasse: false,
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
      eleves: NOMS.map((nom, i) => ({ inscriptionId: `i${i + 1}`, nom, prenoms: i === 1 ? 'Aïcha' : 'Ali', matricule: `M-${i + 1}`, sexe: 'F' })),
    });
    const demarrage = TestBed.inject(SessionService).demarrer();
    await attendre();
    http.expectOne('/api/v1/auth/rafraichir').error(new ProgressEvent('error'), { status: 0 });
    await demarrage;
  });

  afterEach(() => http.verify());

  it('feuille de notes : la recherche garde le numéro d’ordre et Entrée place le curseur sur la note', async () => {
    await stockage.ecrire('cache:u1:etab-1:evaluation:e1', {
      evaluation: {
        id: 'e1', classeId: 'c1', matiereId: 'm1', periodeId: 'p1', libelle: 'Devoir 1', type: 'DEVOIR',
        date: '2026-10-05', bareme: 20, poids: 1, notesSaisies: 0, effectif: NOMS.length,
      },
      notes: {},
      copieLe: '2026-10-05T10:00:00Z',
    });
    const f = TestBed.createComponent(NotesSaisiePage);
    f.componentRef.setInput('classeId', 'c1');
    f.componentRef.setInput('matiereId', 'm1');
    f.componentRef.setInput('evaluationId', 'e1');
    await f.whenStable();
    await attendre();
    f.detectChanges();
    const page: HTMLElement = f.nativeElement;
    expect(page.querySelectorAll('li.eleve')).toHaveLength(18);

    const zone = page.querySelector('input[type=search]') as HTMLInputElement;
    zone.value = 'aicha kabo';
    zone.dispatchEvent(new Event('input'));
    f.detectChanges();
    const lignes = page.querySelectorAll('li.eleve');
    expect(lignes).toHaveLength(1);
    expect(lignes[0].querySelector('.rang')?.textContent?.trim()).toBe('2');
    expect(page.textContent).toContain('1 sur 18');

    zone.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(document.activeElement?.id).toBe('n-i2');
    f.destroy();
  });

  it('évaluations : explique pourquoi on ne peut pas créer d’évaluation quand les trimestres manquent', async () => {
    const f = TestBed.createComponent(NotesEvaluationsPage);
    f.componentRef.setInput('classeId', 'c1');
    f.componentRef.setInput('matiereId', 'm1');
    await f.whenStable();
    await attendre();
    f.detectChanges();
    const texte = (f.nativeElement as HTMLElement).textContent ?? '';
    expect(texte).toContain('Les trimestres de cette classe ne sont pas encore sur ce téléphone');
    expect(texte).toContain('Réessayer');
    expect(texte).not.toContain('Nouvelle évaluation');
  });
});
