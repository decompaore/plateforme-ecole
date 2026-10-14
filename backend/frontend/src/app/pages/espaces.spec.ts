import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { EtablissementAccessible } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { EtablissementPage } from './etablissement.page';

const LYCEE: EtablissementAccessible = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: ['PARENT'], modulesDesactives: [] };
const ADMIN_PAYS = { type: 'PAYS' as const, libelle: 'Administration du pays : Burkina Faso' };

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement {
  f.detectChanges();
  return [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.includes(libelle))!;
}

describe('Espaces (v0.41) : compte d’administration qui est aussi parent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('choisit son espace à la connexion, puis passe de l’administration à l’établissement de son enfant', async () => {
    const session = TestBed.inject(SessionService);
    const connexion = session.connexion('70000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush({
      ...reponse('u1', 1, { etablissementActif: null, etablissements: [LYCEE], superAdmin: false, administration: ADMIN_PAYS }),
      jetonAcces: null, jetonSelection: 'jeton-selection', selectionRequise: true,
    });
    expect(await connexion).toBe('selection');

    const f = TestBed.createComponent(EtablissementPage);
    f.detectChanges();
    await attendre();
    let t = texte(f);
    expect(t).toContain('Choisissez votre espace');
    expect(t).toContain('Administration du pays : Burkina Faso');
    expect(t).toContain('Lycée technique');
    expect(t).toContain('Parent');

    // Administration du pays
    bouton(f, 'Administration du pays').click();
    await attendre();
    const choix = http.expectOne('/api/v1/auth/administration');
    expect(choix.request.headers.get('Authorization')).toBe('Bearer jeton-selection');
    choix.flush(reponse('u1', 2, { etablissementActif: null, etablissements: [LYCEE], superAdmin: false,
      adminPays: { id: 'bf', code: 'BF', nom: 'Burkina Faso' }, administration: ADMIN_PAYS }));
    await attendre();
    http.expectOne('/api/v1/moi').flush({ nom: 'OUEDRAOGO', prenoms: 'Mariam' });
    await attendre();
    expect(session.administrePlateforme()).toBe(true);
    expect(session.plusieursEtablissements()).toBe(true);

    // « Changer d'espace » : l'établissement, en parent seulement
    const g = TestBed.createComponent(EtablissementPage);
    g.detectChanges();
    await attendre();
    http.expectOne('/api/v1/moi/etablissements').flush([LYCEE]);
    await attendre();
    t = texte(g);
    expect(t).toContain('actuel');
    bouton(g, 'Lycée technique').click();
    await attendre();
    http.expectOne('/api/v1/auth/etablissement').flush(reponse('u1', 3, { etablissementActif: LYCEE, etablissements: [LYCEE],
      superAdmin: false, administration: ADMIN_PAYS }));
    await attendre();
    expect(session.profil()?.etablissement?.roles).toEqual(['PARENT']);
    expect(session.administrePlateforme()).toBe(false);
    expect(session.pilote()).toBe(false);
    expect(session.plusieursEtablissements()).toBe(true);
  });
});
