import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { PlateformePage } from './admin/pages/plateforme.page';
import { ModuleEtablissementVue } from './admin/modeles-admin';
import { module } from './core/gardes';
import { EtablissementAccessible, Module, Role } from './core/modeles';
import { SessionService } from './core/session.service';
import { AccueilPage } from './pages/accueil.page';
import { EnfantPage } from './parent/pages/enfant.page';
import { attendre, configurer, reponse } from './testing/outils-test';

function etablissement(roles: Role[], modulesDesactives: Module[]): EtablissementAccessible {
  return { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles, modulesDesactives };
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

const MODULES: ModuleEtablissementVue[] = [
  { code: 'ATELIERS', libelle: 'Ateliers et matière d’œuvre', description: 'Catalogue, ateliers…', requis: null, actif: true },
  { code: 'SCOLARITE', libelle: 'Scolarité et paiements', description: 'Frais, guichet…', requis: null, actif: true },
  { code: 'MOBILE_MONEY', libelle: 'Paiement Mobile Money', description: 'Orange Money…', requis: 'SCOLARITE', actif: true },
  { code: 'ESPACE_PARENT', libelle: 'Espace parent', description: 'Comptes des parents', requis: null, actif: true },
];

describe('Modules activables (v0.31)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('accueil : les tuiles des modules désactivés disparaissent, et leurs écrans sont fermés', async () => {
    await session(http, etablissement(['CHEF_TRAVAUX'], ['ATELIERS']));
    const f = TestBed.createComponent(AccueilPage);
    f.detectChanges();
    await attendre();
    http.match('/api/v1/moi/invitations').forEach((r) => r.flush([]));
    const t = texte(f);
    expect(t).toContain('Emplois du temps');
    expect(t).toContain('Progressions');
    expect(t).not.toContain('Ateliers');
    expect(t).not.toContain('Besoins et commandes');

    const garde = (m: Module) => TestBed.runInInjectionContext(() => module(m)({} as never, {} as never));
    expect(garde('EMPLOIS_DU_TEMPS')).toBe(true);
    expect(TestBed.inject(Router).serializeUrl(garde('ATELIERS') as never)).toBe('/');
  });

  it('espace parent : sans scolarité ni vie scolaire, seules les absences et les bulletins sont demandés', async () => {
    await session(http, etablissement(['PARENT'], ['SCOLARITE', 'MOBILE_MONEY', 'VIE_SCOLAIRE']));
    const f = TestBed.createComponent(EnfantPage);
    f.componentRef.setInput('eleveId', 'e1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/espace-parent/enfants').flush([
      { eleveId: 'e1', matricule: null, nom: 'OUEDRAOGO', prenoms: 'Awa', sexe: 'F', dateNaissance: '2010-03-14', lien: 'MERE', anneeLibelle: '2026-2027', classeCode: '6e B', statut: 'ACTIVE' },
    ]);
    http.expectOne('/api/v1/espace-parent/enfants/e1/absences').flush([]);
    http.expectOne('/api/v1/espace-parent/enfants/e1/bulletins').flush([]);
    await attendre();
    const t = texte(f);
    expect(t).toContain('Absences');
    expect(t).toContain('Bulletins');
    expect(t).not.toContain('Scolarité');
    expect(t).not.toContain('Vie scolaire');
  });

  it('super administrateur : désactiver la scolarité désactive Mobile Money, puis enregistrer', async () => {
    await session(http, null, true);
    const f = TestBed.createComponent(PlateformePage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/plateforme/etablissements').flush([{ id: 'e1', code: 'ltk', nom: 'Lycée technique', statut: 'ACTIF', creeLe: '2026-09-01T08:00:00Z' }]);
    await attendre();
    const bouton = (libelle: string) =>
      [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim().startsWith(libelle))!;
    f.detectChanges();
    bouton('Modules').click();
    await attendre();
    http.expectOne('/api/v1/plateforme/etablissements/e1/modules').flush(MODULES);
    await attendre();
    expect(texte(f)).toContain('nécessite « Scolarité et paiements »');
    const cases = () => [...(f.nativeElement as HTMLElement).querySelectorAll<HTMLInputElement>('li.module input')];
    expect(cases().every((c) => c.checked)).toBe(true);

    cases()[1].click(); // Scolarité
    f.detectChanges();
    expect(cases().map((c) => c.checked)).toEqual([true, false, false, true]);
    cases()[2].click(); // Mobile Money : recoche la scolarité
    f.detectChanges();
    expect(cases().map((c) => c.checked)).toEqual([true, true, true, true]);
    cases()[0].click(); // Ateliers
    f.detectChanges();

    bouton('Enregistrer les modules').click();
    await attendre();
    const req = http.expectOne((r) => r.url === '/api/v1/plateforme/etablissements/e1/modules' && r.method === 'PUT');
    expect(req.request.body).toEqual({ actifs: ['SCOLARITE', 'MOBILE_MONEY', 'ESPACE_PARENT'] });
    req.flush(MODULES.map((m) => ({ ...m, actif: m.code !== 'ATELIERS' })));
    await attendre();
    expect(texte(f)).toContain('Modules enregistrés. Désactivés : Ateliers et matière d’œuvre.');
  });
});
