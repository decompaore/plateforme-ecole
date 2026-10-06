import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { EtablissementAccessible } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { ExportsDonneesComponent, tailleLisible } from './exports-donnees.component';
import { ONGLETS } from './admin-nav.component';
import { ExportVue } from './modeles-admin';
import { DonneesPage } from './pages/donnees.page';
import { PlateformePage } from './pages/plateforme.page';

const ETAB: EtablissementAccessible = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: ['ADMIN_ECOLE'], modulesDesactives: [] };

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

function exportVue(partiel: Partial<ExportVue>): ExportVue {
  return {
    id: 'x1', statut: 'PRET', demandeLe: '2026-10-06T08:00:00Z', demandePar: 'KABORE Mathieu', parPlateforme: false,
    termineLe: '2026-10-06T08:02:00Z', expireLe: '2026-10-13T08:02:00Z', taille: 5_452_595, empreinte: 'ab'.repeat(32),
    nombreTables: 80, nombreLignes: 12345, erreur: null, telechargements: 0, dernierTelechargement: null, ...partiel,
  };
}

describe('Export complet des données (v0.33)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    http.verify();
  });

  it('taille lisible', () => {
    expect(tailleLisible(512)).toBe('512 octets');
    expect(tailleLisible(5_452_595)).toBe('5,2 Mo');
    expect(tailleLisible(null)).toBe('');
  });

  it('l’onglet Données est réservé à l’administrateur', () => {
    expect(ONGLETS.find((o) => o.lien === '/admin/donnees')?.roles).toEqual(['ADMIN_ECOLE']);
  });

  it('administrateur : demande avec le mot de passe, suivi de la préparation, puis téléchargement par lien signé', async () => {
    await session(http, ETAB);
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const patienter = async () => {
      for (let i = 0; i < 10; i++) {
        await Promise.resolve();
      }
      await vi.advanceTimersByTimeAsync(1);
    };
    const clic = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const f = TestBed.createComponent(DonneesPage);
    f.detectChanges();
    await patienter();
    http.match('/api/v1/annees').forEach((r) => r.flush([]));
    http.expectOne('/api/v1/exports').flush([]);
    await patienter();
    expect(texte(f)).toContain("Aucun export n'a encore été demandé.");
    expect(bouton(f, 'Préparer un export complet').disabled).toBe(true);

    const champ = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type=password]')!;
    champ.value = 'secret123';
    champ.dispatchEvent(new Event('input'));
    f.detectChanges();
    bouton(f, 'Préparer un export complet').click();
    await patienter();
    const demande = http.expectOne((r) => r.url === '/api/v1/exports' && r.method === 'POST');
    expect(demande.request.body).toEqual({ motDePasse: 'secret123' });
    demande.flush(exportVue({ statut: 'EN_COURS', termineLe: null, expireLe: null, taille: null, empreinte: null }));
    await patienter();
    expect(texte(f)).toContain('En préparation');
    expect(bouton(f, 'Préparation en cours').disabled).toBe(true);
    expect(champ.value).toBe('');

    // La liste se recharge toute seule jusqu'à ce que l'archive soit prête
    await vi.advanceTimersByTimeAsync(3000);
    http.expectOne('/api/v1/exports').flush([exportVue({})]);
    await patienter();
    const t = texte(f);
    expect(t).toContain('L’archive est prête');
    expect(t).toContain('5,2 Mo · 80 tables · 12345 lignes');
    expect(t).toContain('Empreinte SHA-256');
    await vi.advanceTimersByTimeAsync(5000); // plus de rechargement

    bouton(f, 'Télécharger').click();
    await patienter();
    http.expectOne((r) => r.url === '/api/v1/exports/x1/lien' && r.method === 'POST')
      .flush({ chemin: '/telechargements/exports/x1?jeton=abc', expireLe: '2026-10-06T08:12:00Z' });
    await patienter();
    expect(clic).toHaveBeenCalledTimes(1);
    expect((clic.mock.contexts[0] as HTMLAnchorElement).getAttribute('href')).toBe('/api/v1/telechargements/exports/x1?jeton=abc');
    expect(texte(f)).toContain('téléchargé 1 fois');
  });

  it('mot de passe incorrect : message du serveur', async () => {
    await session(http, ETAB);
    const f = TestBed.createComponent(ExportsDonneesComponent);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/exports').flush([exportVue({ statut: 'EXPIRE' }), exportVue({ id: 'x0', statut: 'INTERROMPU', erreur: 'Préparation interrompue (redémarrage du serveur)' })]);
    await attendre();
    expect(texte(f)).toContain('Effacé du serveur');
    expect(texte(f)).toContain('Préparation interrompue');
    expect(bouton(f, 'Télécharger')).toBeUndefined();

    const champ = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type=password]')!;
    champ.value = 'faux';
    champ.dispatchEvent(new Event('input'));
    f.detectChanges();
    bouton(f, 'Préparer un export complet').click();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/exports' && r.method === 'POST')
      .flush({ detail: 'Mot de passe incorrect', code: 'MOT_DE_PASSE_INCORRECT' }, { status: 409, statusText: 'Conflict' });
    await attendre();
    expect(texte(f)).toContain('Mot de passe incorrect');
  });

  it('super administrateur : export d’un établissement depuis la liste des établissements', async () => {
    await session(http, null, true);
    const f = TestBed.createComponent(PlateformePage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/plateforme/etablissements').flush([{ id: 'e1', code: 'ltk', nom: 'Lycée technique', statut: 'RESILIE', creeLe: '2026-09-01T08:00:00Z' }]);
    await attendre();
    f.detectChanges();
    bouton(f, 'Données').click();
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/plateforme/etablissements/e1/exports').flush([]);
    await attendre();
    expect(texte(f)).toContain('possible aussi s\'il est suspendu ou résilié');

    const champ = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type=password]')!;
    champ.value = 'motdepasse1';
    champ.dispatchEvent(new Event('input'));
    f.detectChanges();
    bouton(f, 'Préparer un export complet').click();
    await attendre();
    const r = http.expectOne((x) => x.url === '/api/v1/plateforme/etablissements/e1/exports' && x.method === 'POST');
    expect(r.request.body).toEqual({ motDePasse: 'motdepasse1' });
    r.flush(exportVue({ statut: 'ECHEC', parPlateforme: true, demandePar: 'Plateforme (super administrateur)', erreur: 'La préparation a échoué.' }));
    await attendre();
    expect(texte(f)).toContain('Plateforme (super administrateur)');
    expect(texte(f)).toContain('Échec');
  });
});
