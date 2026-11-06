import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SwPush } from '@angular/service-worker';
import { of } from 'rxjs';

import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { CentreNotifications } from './modeles-parent';
import { nomAppareil, NotificationsParentPage } from './pages/notifications.page';

const ETAB = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: ['PARENT' as const] };

async function session(http: HttpTestingController): Promise<void> {
  const connexion = TestBed.inject(SessionService).connexion('62000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('par1', 1, { etablissementActif: ETAB, etablissements: [ETAB] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'OUEDRAOGO', prenoms: 'Mariam' });
  await connexion;
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement {
  f.detectChanges();
  const b = [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((x) => x.textContent?.includes(libelle));
  if (!b) {
    throw new Error(`Bouton « ${libelle} » introuvable`);
  }
  return b;
}

const CENTRE: CentreNotifications = {
  nonLues: 1,
  notifications: [
    { id: 'n1', eleveId: 'e1', type: 'ABSENCE', titre: 'Absence de Awa', message: 'Awa OUEDRAOGO (6e A) était absente le 03/11 de 08h00 à 10h00.',
      lien: '/parent/enfants/e1', recueLe: '2026-11-03T08:05:00Z', lueLe: null, corrigeeLe: null },
    { id: 'n2', eleveId: 'e1', type: 'RETARD', titre: 'Retard de Awa', message: 'Awa est arrivée en retard.',
      lien: '/parent/enfants/e1', recueLe: '2026-11-02T08:05:00Z', lueLe: '2026-11-02T09:00:00Z', corrigeeLe: '2026-11-02T08:20:00Z' },
  ],
};

function repondre(http: HttpTestingController, disponible = true): void {
  http.expectOne('/api/v1/espace-parent/notifications').flush(CENTRE);
  http.expectOne('/api/v1/espace-parent/preferences-notifications').flush({ canal: 'AUTO', notifierRetards: true, discret: false });
  http.expectOne('/api/v1/push/configuration').flush({ disponible, clePublique: disponible ? 'BCLE' : null });
}

describe('Espace parent : notifications (v0.50)', () => {
  let http: HttpTestingController;

  afterEach(() => http.verify());

  it('sans service worker : explique comment faire, liste les notifications et enregistre les préférences', async () => {
    ({ http } = configurer());
    await session(http);
    const f = TestBed.createComponent(NotificationsParentPage);
    f.detectChanges();
    repondre(http);
    await attendre();
    http.expectOne('/api/v1/push/abonnements').flush([]);
    await attendre();
    let t = texte(f);
    expect(t).toContain('Ce navigateur ne reçoit pas les notifications');
    expect(t).toContain('Absence de Awa');
    expect(t).toContain("Corrigé par l'établissement");
    expect(t).toContain('Tout marquer comme lu (1)');

    const discret = (f.nativeElement as HTMLElement).querySelector('input[name="discret"]') as HTMLInputElement;
    discret.checked = true;
    discret.dispatchEvent(new Event('change'));
    await f.whenStable();
    bouton(f, 'Enregistrer').click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/espace-parent/preferences-notifications');
    expect(req.request.body).toEqual({ notifierRetards: true, discret: true });
    req.flush({ canal: 'AUTO', notifierRetards: true, discret: true });
    await attendre();
    expect(texte(f)).toContain('Préférences enregistrées.');

    bouton(f, 'Tout marquer comme lu').click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/espace-parent/notifications/lues')
      .flush({ ...CENTRE, nonLues: 0 });
    await attendre();
    t = texte(f);
    expect(t).not.toContain('Tout marquer comme lu');
  });

  it('active les notifications sur ce téléphone puis envoie un essai', async () => {
    const abonnement = {
      endpoint: 'https://fcm.googleapis.com/fcm/send/abc',
      toJSON: () => ({ endpoint: 'https://fcm.googleapis.com/fcm/send/abc', keys: { p256dh: 'P256', auth: 'AUTH' } }),
    } as unknown as PushSubscription;
    let cle = '';
    ({ http } = configurer([{
      provide: SwPush,
      useValue: {
        isEnabled: true,
        subscription: of(null),
        requestSubscription: async (o: { serverPublicKey: string }) => {
          cle = o.serverPublicKey;
          return abonnement;
        },
        unsubscribe: async () => undefined,
      },
    }]));
    await session(http);
    const f = TestBed.createComponent(NotificationsParentPage);
    f.detectChanges();
    repondre(http);
    await attendre();
    http.expectOne('/api/v1/push/abonnements').flush([]);
    await attendre();
    expect(texte(f)).toContain('sans frais de SMS');

    bouton(f, 'Activer les notifications').click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/push/abonnements');
    expect(cle).toBe('BCLE');
    expect(req.request.body).toEqual(expect.objectContaining({ endpoint: abonnement.endpoint, p256dh: 'P256', auth: 'AUTH' }));
    req.flush([{ id: 'a1', appareil: 'Android · Chrome', creeLe: '2026-11-03T08:00:00Z', dernierEnvoi: null }]);
    await attendre();
    expect(texte(f)).toContain('Notifications activées sur ce téléphone.');

    bouton(f, 'Envoyer un essai').click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/push/essai').flush({ appareils: 1 });
    await attendre();
    expect(texte(f)).toContain('Essai envoyé à 1 appareil(s)');
  });

  it('nomme l’appareil', () => {
    expect(nomAppareil('Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36')).toBe('Android · Chrome');
    expect(nomAppareil('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0) AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1')).toBe('iPhone · Safari');
  });
});
