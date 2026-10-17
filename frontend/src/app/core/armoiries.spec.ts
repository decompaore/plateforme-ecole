import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { StockageMemoire } from '../hors-ligne/stockage';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { ArmoiriesEcran, cleArmoiries } from './armoiries.service';
import { ProfilLocal, SessionService } from './session.service';

describe('Armoiries en fond d’écran (v0.48.1, v0.49.1)', () => {
  let http: HttpTestingController;
  let stockage: StockageMemoire;

  beforeEach(() => {
    ({ http, stockage } = configurer());
  });

  afterEach(() => http.verify());

  it('clé : établissement, sinon direction, sinon pays administré ; rien pour le super administrateur', () => {
    const base = { utilisateurId: 'u', nom: 'N', prenoms: 'P', superAdmin: false, etablissement: null, doitChangerMotDePasse: false } as ProfilLocal;
    expect(cleArmoiries(null)).toBeNull();
    expect(cleArmoiries({ ...base, superAdmin: true })).toBeNull();
    expect(cleArmoiries({ ...base, etablissement: { id: 'e1', code: 'c', nom: 'n', roles: [] } })).toBe('armoiries:etablissement:e1');
    expect(cleArmoiries({ ...base, direction: { id: 'd1' } } as ProfilLocal)).toBe('armoiries:direction:d1');
    expect(cleArmoiries({ ...base, adminPays: { id: 'bf' } } as ProfilLocal)).toBe('armoiries:pays:bf');
  });

  it('lues une seule fois après la connexion et gardées sur l’appareil', async () => {
    const armoiries = TestBed.inject(ArmoiriesEcran);
    TestBed.runInInjectionContext(() => armoiries.demarrer());
    const connexion = TestBed.inject(SessionService).connexion('61000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u5', 1));
    await attendre();
    http.expectOne('/api/v1/moi').flush({ nom: 'SANOU', prenoms: 'Paul' });
    await connexion;
    TestBed.tick();
    await attendre();
    http.expectOne('/api/v1/armoiries').flush(new Blob([new Uint8Array([137, 80, 78, 71])], { type: 'image/png' }));
    await attendre();
    await new Promise((r) => setTimeout(r, 20));
    expect(armoiries.image()).toMatch(/^data:image\/png;base64,/);
    expect(await stockage.lire<string>('armoiries:etablissement:etab-1')).toMatch(/^data:image\/png/);
  });
});
