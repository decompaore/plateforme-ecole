import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { EtablissementAccessible } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { ActiviteQuotidienneComponent, jourCourt, pourcentage } from './activite-quotidienne.component';
import { ONGLETS } from './admin-nav.component';
import { AdoptionEtablissement, AdoptionPlateforme, EtablissementAdoption, IndicateurAdoption, JourActif } from './modeles-admin';
import { AdoptionPlateformePage } from './pages/adoption-plateforme.page';
import { UtilisationPage } from './pages/utilisation.page';

const ETAB: EtablissementAccessible = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: ['ADMIN_ECOLE'], modulesDesactives: [] };

const INDICATEURS: IndicateurAdoption[] = [
  { code: 'APPELS', libelle: 'Appels faits' },
  { code: 'NOTES', libelle: 'Notes saisies' },
  { code: 'CAHIER', libelle: 'Séances du cahier de textes' },
  { code: 'PAIEMENTS', libelle: 'Paiements au guichet' },
  { code: 'SMS', libelle: 'SMS envoyés' },
];

function jours(n: number, actifs: (i: number) => number): JourActif[] {
  return Array.from({ length: n }, (_, i) => ({ jour: `2026-10-${String(i + 1).padStart(2, '0')}`, actifs: actifs(i) }));
}

async function session(http: HttpTestingController, etab: EtablissementAccessible | null, superAdmin = false): Promise<void> {
  const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: etab, etablissements: etab ? [etab] : [], superAdmin }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'KABORE', prenoms: 'Mathieu' });
  await connexion;
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement {
  return [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim().startsWith(libelle))!;
}

function etablissement(partiel: Partial<EtablissementAdoption>): EtablissementAdoption {
  return {
    id: 'e1', code: 'ltk', nom: 'Lycée technique', statut: 'ACTIF', directionId: null, rattachement: null, comptes: 120, actifs: 80,
    enseignants: { total: 40, actifs: 30 }, parents: { total: 70, actifs: 35 }, joursActifs: 22,
    derniereActivite: '2026-10-06', actions: { APPELS: 900, NOTES: 2400, CAHIER: 300 }, alertes: [], ...partiel,
  };
}

describe('Mesure de l’adoption (v0.34)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('outils : pourcentage, jour court, barres du graphique', () => {
    expect(pourcentage({ total: 40, actifs: 30 })).toBe('75 %');
    expect(pourcentage({ total: 0, actifs: 0 })).toBe('—');
    expect(jourCourt('2026-10-06')).toContain('6 oct');
    expect(jourCourt('')).toBe('');

    const f = TestBed.createComponent(ActiviteQuotidienneComponent);
    f.componentRef.setInput('jours', jours(7, (i) => (i === 3 ? 0 : i + 1)));
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelectorAll('path.barre').length).toBe(6); // pas de barre un jour sans activité
    expect(el.querySelectorAll('g.jour title').length).toBe(7);
    expect(el.querySelector('svg')!.getAttribute('aria-label')).toContain('maximum 7');
    expect(el.textContent).toContain('max. 7');
  });

  it('l’onglet Utilisation est réservé à l’administrateur', () => {
    expect(ONGLETS.find((o) => o.lien === '/admin/utilisation')?.roles).toEqual(['ADMIN_ECOLE']);
  });

  it('administrateur : chiffres, actions, personnel à accompagner', async () => {
    await session(http, ETAB);
    const f = TestBed.createComponent(UtilisationPage);
    f.detectChanges();
    await attendre();
    http.match('/api/v1/annees').forEach((r) => r.flush([]));
    const vue: AdoptionEtablissement = {
      debut: '2026-09-07', fin: '2026-10-06', calculeLe: '2026-10-06T10:00:00Z', indicateurs: INDICATEURS,
      comptes: 50, actifs: 20, personnel: { total: 4, actifs: 3 }, enseignants: { total: 3, actifs: 2 },
      parents: { total: 46, actifs: 17 }, actions: { APPELS: 120, NOTES: 300 }, quotidien: jours(30, () => 5),
      personnes: [
        { utilisateurId: 'u1', nom: 'KABORE', prenoms: 'Mathieu', roles: ['ADMIN_ECOLE'], joursActifs: 20, derniereActivite: '2026-10-06', appels: 0, notes: 0, cahier: 0 },
        { utilisateurId: 'u2', nom: 'OUEDRAOGO', prenoms: 'Awa', roles: ['ENSEIGNANT'], joursActifs: 18, derniereActivite: '2026-10-05', appels: 120, notes: 300, cahier: 40 },
        { utilisateurId: 'u3', nom: 'SANOU', prenoms: 'Paul', roles: ['ENSEIGNANT'], joursActifs: 2, derniereActivite: '2026-09-20', appels: 0, notes: 0, cahier: 0 },
        { utilisateurId: 'u4', nom: 'ZONGO', prenoms: 'Ali', roles: ['ENSEIGNANT'], joursActifs: 0, derniereActivite: null, appels: 0, notes: 0, cahier: 0 },
      ],
    };
    const req = http.expectOne((r) => r.url === '/api/v1/adoption');
    expect(req.request.params.get('jours')).toBe('30');
    req.flush(vue);
    await attendre();
    let t = texte(f);
    expect(t).toContain('3 / 4membres du personnel actifs (75 %)');
    expect(t).toContain('2 / 3enseignants actifs (67 %)');
    expect(t).toContain('Appels faits120');
    expect(t).not.toContain('Séances du cahier de textes'); // aucune action sur la période
    expect(t).toContain('jamais');

    const select = (f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#filtrePersonnel')!;
    select.value = 'enseignants-sans-appel';
    select.dispatchEvent(new Event('change'));
    t = texte(f);
    expect(t).toContain('SANOU');
    expect(t).toContain('ZONGO');
    expect(t).not.toContain('OUEDRAOGO');
    expect(t).not.toContain('KABORE');

    select.value = 'inactifs';
    select.dispatchEvent(new Event('change'));
    t = texte(f);
    expect(t).toContain('ZONGO');
    expect(t).not.toContain('SANOU');

    bouton(f, '7 jours').click();
    await attendre();
    const r7 = http.expectOne((r) => r.url === '/api/v1/adoption');
    expect(r7.request.params.get('jours')).toBe('7');
    r7.flush({ ...vue, quotidien: jours(7, () => 1) });
    await attendre();
  });

  it('super administrateur : établissements à accompagner en premier, filtre et détail', async () => {
    await session(http, null, true);
    const f = TestBed.createComponent(AdoptionPlateformePage);
    f.detectChanges();
    await attendre();
    const vue: AdoptionPlateforme = {
      debut: '2026-09-07', fin: '2026-10-06', calculeLe: '2026-10-06T10:00:00Z', indicateurs: INDICATEURS,
      etablissements: 2, etablissementsActifs: 1, comptes: 150, actifs: 80, enseignants: { total: 50, actifs: 30 },
      parents: { total: 90, actifs: 35 }, actions: { APPELS: 900, NOTES: 2400, SMS: 75 }, quotidien: jours(30, (i) => i),
      details: [
        etablissement({ id: 'e2', code: 'lpb', nom: 'Lycée professionnel', comptes: 30, actifs: 0, enseignants: { total: 10, actifs: 0 }, parents: { total: 20, actifs: 0 }, joursActifs: 0, derniereActivite: null, actions: {}, alertes: ['SANS_ACTIVITE'] }),
        etablissement({}),
      ],
    };
    http.expectOne((r) => r.url === '/api/v1/plateforme/adoption').flush(vue);
    await attendre();
    let t = texte(f);
    expect(t).toContain('1 / 2établissements avec de l\'activité');
    expect(t).toContain('60 %des enseignants actifs');
    expect(t).toContain('900 Appels faits');
    expect(t).toContain('Aucune activité depuis 7 jours');
    const lignes = (f.nativeElement as HTMLElement).querySelectorAll('tbody tr');
    expect(lignes[0].textContent).toContain('Lycée professionnel');
    expect(lignes[1].textContent).toContain('75 %');

    (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('.case input')!.click();
    t = texte(f);
    expect(t).toContain('À accompagner seulement (1)');
    expect((f.nativeElement as HTMLElement).querySelectorAll('tbody tr').length).toBe(1);
    (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('.case input')!.click();
    f.detectChanges();

    [...(f.nativeElement as HTMLElement).querySelectorAll('tbody button')][1].dispatchEvent(new Event('click'));
    t = texte(f);
    expect(t).toContain('Enseignants actifs : 30 / 40');
    expect(t).toContain('0 SMS envoyés');

    bouton(f, 'Recalculer la période').click();
    await attendre();
    const r = http.expectOne((x) => x.url === '/api/v1/plateforme/adoption/calcul' && x.method === 'POST');
    expect(r.request.params.get('jours')).toBe('30');
    r.flush(vue);
    await attendre();
  });
});
