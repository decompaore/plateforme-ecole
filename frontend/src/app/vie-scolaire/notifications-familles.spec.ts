import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { NotificationsFamillesPage } from './pages/notifications-familles.page';

async function session(http: HttpTestingController, role: Role): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('61000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'ZONGO', prenoms: 'Ines' });
  await connexion;
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

describe('Vie scolaire : notifications aux familles (v0.50)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('le CPE règle le délai de correction et voit les derniers SMS', async () => {
    await session(http, 'SURVEILLANT');
    const f = TestBed.createComponent(NotificationsFamillesPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/notifications/reglages').flush({ delaiCorrectionMinutes: 0, smsRetards: false, pushDisponible: true });
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/notifications').flush([
      { id: 's1', cle: 'absence:1', destinataire: '+226 70****12', message: 'Lycée : Ines ZONGO (6e A) était absente…', statut: 'ANNULE',
        tentatives: 0, derniereErreur: null, creeLe: '2026-11-03T08:05:00Z', envoyeLe: null },
    ]);
    await attendre();
    expect(texte(f)).toContain('Annulé (correction)');

    const delai = (f.nativeElement as HTMLElement).querySelector('#delai') as HTMLInputElement;
    delai.value = '10';
    delai.dispatchEvent(new Event('input'));
    await f.whenStable();
    const enregistrer = [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.includes('Enregistrer'));
    (enregistrer as HTMLButtonElement).click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/notifications/reglages');
    expect(req.request.body).toEqual({ delaiCorrectionMinutes: 10, smsRetards: false });
    req.flush({ delaiCorrectionMinutes: 10, smsRetards: false, pushDisponible: true });
    await attendre();
    expect(texte(f)).toContain('Réglages enregistrés.');
  });
});
