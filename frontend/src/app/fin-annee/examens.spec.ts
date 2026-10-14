import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ONGLETS } from '../admin/admin-nav.component';
import { EtablissementAccessible, Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { pourcent, serieExamen, TableauExamensVue } from './modeles-fin-annee';
import { ExamensPage } from './pages/examens.page';

const TABLEAU: TableauExamensVue = {
  examens: ['BEPC'],
  annees: ['2025-2026', '2024-2025'],
  resultats: [
    {
      annee: '2025-2026', examen: 'BEPC', source: 'CALCULE', historiqueId: null, inscrits: 10, resultatsConnus: 8, admis: 5,
      resultatsFilles: 4, admisFilles: 3, taux: 62.5, tauxFilles: 75, tauxGarcons: 50,
      classes: [{ classeId: 'c1', classeCode: '3e A', inscrits: 10, resultatsConnus: 8, admis: 5, taux: 62.5 }],
    },
    {
      annee: '2024-2025', examen: 'BEPC', source: 'SAISI', historiqueId: 'h1', inscrits: 100, resultatsConnus: 100, admis: 55,
      resultatsFilles: null, admisFilles: null, taux: 55, tauxFilles: null, tauxGarcons: null, classes: [],
    },
  ],
};

async function session(http: HttpTestingController, role: Role): Promise<void> {
  const etab: EtablissementAccessible = { id: 'etab-1', code: 'CEG', nom: 'CEG Horizon', roles: [role], modulesDesactives: [] };
  const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'KABORE', prenoms: 'Mathieu' });
  await connexion;
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement | undefined {
  f.detectChanges();
  return [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim().startsWith(libelle));
}

async function ouvrir(http: HttpTestingController): Promise<ComponentFixture<ExamensPage>> {
  const f = TestBed.createComponent(ExamensPage);
  f.detectChanges();
  await attendre();
  http.match('/api/v1/annees').forEach((r) => r.flush([]));
  http.expectOne('/api/v1/examens/resultats').flush(TABLEAU);
  await attendre();
  f.detectChanges();
  await f.whenStable();
  return f;
}

describe('Examens nationaux (v0.46)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('outils : pourcentages et série d’un examen', () => {
    expect(pourcent(62.5)).toBe('62,5 %');
    expect(pourcent(null)).toBe('—');
    expect(serieExamen(TABLEAU, 'BEPC')).toEqual([
      { annee: '2024-2025', taux: 55, ecart: null },
      { annee: '2025-2026', taux: 62.5, ecart: 7.5 },
    ]);
    expect(ONGLETS.find((o) => o.lien === '/admin/examens')?.roles).toEqual(['ADMIN_ECOLE', 'CENSEUR']);
  });

  it('censeur : synthèse, évolution, détail par classe ; pas de saisie', async () => {
    await session(http, 'CENSEUR');
    const f = await ouvrir(http);
    const t = texte(f);
    expect(t).toContain('62,5 %');
    expect(t).toContain('5 admis sur 8 (2 résultat(s) en attente)');
    expect(t).toContain('▲ +7,5 point(s)');
    expect(t).toContain('Filles 75 % · Garçons 50 %');
    expect((f.nativeElement as HTMLElement).querySelectorAll('.barre')).toHaveLength(2);
    expect(t).not.toContain('Saisir une année antérieure');
    expect(bouton(f, 'Modifier')).toBeUndefined();
    bouton(f, 'Classes')!.click();
    expect(texte(f)).toContain('3e A');
  });

  it('chef d’établissement : saisit une année antérieure, corrige puis supprime', async () => {
    await session(http, 'ADMIN_ECOLE');
    const f = await ouvrir(http);
    const el = f.nativeElement as HTMLElement;
    const remplir = (sel: string, v: string) => {
      const c = el.querySelector<HTMLInputElement>(sel)!;
      c.value = v;
      c.dispatchEvent(new Event('input'));
    };
    remplir('#hAnnee', '2023 - 2024');
    remplir('#hExamen', ' bepc ');
    remplir('#hCandidats', '90');
    remplir('#hAdmis', '45');
    f.detectChanges();
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await attendre();
    const post = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/examens/historique');
    expect(post.request.body).toEqual({ annee: '2023-2024', examen: 'bepc', candidats: 90, admis: 45, candidatesFilles: null, admisesFilles: null });
    post.flush(TABLEAU);
    await attendre();
    expect(texte(f)).toContain('Résultats du BEPC 2023-2024 enregistrés');

    bouton(f, 'Modifier')!.click();
    f.detectChanges();
    await f.whenStable();
    expect(texte(f)).toContain('Modifier les résultats saisis');
    expect(el.querySelector<HTMLInputElement>('#hAnnee')!.value).toBe('2024-2025');
    remplir('#hAdmis', '60');
    el.querySelector('form')!.dispatchEvent(new Event('submit'));
    await attendre();
    const put = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/examens/historique/h1');
    expect(put.request.body.admis).toBe(60);
    put.flush(TABLEAU);
    await attendre();

    vi.spyOn(window, 'confirm').mockReturnValue(true);
    bouton(f, 'Supprimer')!.click();
    await attendre();
    http.expectOne((r) => r.method === 'DELETE' && r.url === '/api/v1/examens/historique/h1').flush({ ...TABLEAU, resultats: [TABLEAU.resultats[0]] });
    await attendre();
    expect(texte(f)).toContain('Résultats du BEPC 2024-2025 supprimés');
    vi.restoreAllMocks();
  });
});
