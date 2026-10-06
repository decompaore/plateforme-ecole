import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { AppareilsPage } from './appareils.page';

describe('Mes appareils (v0.32)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('liste les appareils et déconnecte un téléphone perdu avec effacement', async () => {
    // Session ouverte : la page envoie le jeton d'accès, comme le reste de l'API
    const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u1'));
    await attendre();
    http.expectOne('/api/v1/moi').flush({ nom: 'SANOU', prenoms: 'Paul' });
    await connexion;
    const f = TestBed.createComponent(AppareilsPage);
    f.detectChanges();
    const liste = http.expectOne('/api/v1/auth/appareils');
    expect(liste.request.headers.get('Authorization')).toMatch(/^Bearer /);
    liste.flush([
      { id: 's1', appareil: 'Ordinateur Windows · Edge', ouverteLe: '2026-10-05T08:00:00Z', dernierUsage: '2026-10-06T08:00:00Z', etablissement: 'Lycée technique', courant: true },
      { id: 's2', appareil: 'Téléphone Android · Chrome', ouverteLe: '2026-09-20T08:00:00Z', dernierUsage: '2026-10-04T17:00:00Z', etablissement: 'Lycée technique', courant: false },
    ]);
    await attendre();
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    const texte = () => (el.textContent ?? '').replace(/\s+/g, ' ');
    expect(texte()).toMatch(/Ordinateur Windows · Edge\s*cet appareil/);
    // Pas de bouton pour l'appareil courant : un seul « Déconnecter… »
    const boutons = () => [...el.querySelectorAll('button')];
    expect(boutons().filter((b) => b.textContent?.includes('Déconnecter…'))).toHaveLength(1);

    boutons().find((b) => b.textContent?.includes('Déconnecter…'))!.click();
    f.detectChanges();
    boutons().find((b) => b.textContent?.includes('Perdu ou volé'))!.click();
    const req = http.expectOne('/api/v1/auth/appareils/s2/fermeture');
    expect(req.request.body).toEqual({ effacer: true });
    expect(req.request.headers.get('Authorization')).toMatch(/^Bearer /);
    req.flush(null, { status: 204, statusText: 'No Content' });
    await attendre();
    f.detectChanges();
    expect(texte()).toContain('Ses données seront effacées dès qu\'il se connectera au réseau.');
    expect(texte()).not.toContain('Téléphone Android · Chrome connecté');
  });
});
