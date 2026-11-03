import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SwPush } from '@angular/service-worker';
import { firstValueFrom } from 'rxjs';

import { messageErreur } from '../../core/erreurs';
import { dateHeureCourte } from '../../core/outils';
import { AppareilVue, ConfigurationPush, NotificationParentVue, PreferencesNotifications } from '../modeles-parent';
import { ParentApi } from '../parent-api.service';

/** Nom court de l'appareil, pour que le parent reconnaisse ses téléphones. */
export function nomAppareil(ua: string): string {
  const systeme = /iPhone|iPad/.test(ua) ? 'iPhone' : /Android/.test(ua) ? 'Android' : /Windows/.test(ua) ? 'Windows'
    : /Mac OS/.test(ua) ? 'Mac' : 'Appareil';
  const navigateur = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome'
    : /Safari\//.test(ua) ? 'Safari' : 'navigateur';
  return `${systeme} · ${navigateur}`;
}

type EtatTelephone = 'chargement' | 'indisponible-plateforme' | 'navigateur' | 'refusees' | 'inactives' | 'actives';

/**
 * Notifications du parent (v0.50) : activation sur ce téléphone (Web Push), préférences, et
 * notifications reçues (absences et retards signalés à l'appel). Sans smartphone, ou sans
 * activation, l'établissement prévient le parent par SMS.
 */
@Component({
  selector: 'app-notifications-parent',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page">
      <a routerLink="/parent" class="bouton discret">‹ Mes enfants</a>
      <h1>Notifications</h1>

      @if (erreur()) {
        <div class="alerte erreur" role="alert">{{ erreur() }}</div>
      }
      @if (message()) {
        <div class="alerte succes" role="status">{{ message() }}</div>
      }

      <section class="carte">
        <h2>Sur ce téléphone</h2>
        @switch (etat()) {
          @case ('chargement') { <p class="doux">Vérification…</p> }
          @case ('indisponible-plateforme') {
            <p>Les notifications dans l'application ne sont pas encore ouvertes. L'établissement vous prévient par SMS.</p>
          }
          @case ('navigateur') {
            <p>Ce navigateur ne reçoit pas les notifications.</p>
            <p class="doux">
              Sur Android, ouvrez l'application avec Chrome. Sur iPhone, ajoutez-la d'abord à l'écran d'accueil
              (bouton Partager, puis « Sur l'écran d'accueil »), puis ouvrez-la depuis l'icône.
              En attendant, l'établissement vous prévient par SMS.
            </p>
          }
          @case ('refusees') {
            <p>Les notifications ont été refusées pour cette application.</p>
            <p class="doux">Autorisez-les dans les réglages du téléphone (Notifications), puis revenez ici.</p>
          }
          @case ('inactives') {
            <p>Recevez tout de suite les absences et les retards de vos enfants signalés à l'appel, sans frais de SMS.</p>
            <button type="button" class="bouton" [disabled]="enCours()" (click)="activer()">Activer les notifications</button>
          }
          @case ('actives') {
            <p><span class="pastille present">Activées</span> Vous êtes prévenu(e) sur ce téléphone.</p>
            <span class="actions-ligne">
              <button type="button" class="bouton secondaire petit" [disabled]="enCours()" (click)="essai()">Envoyer un essai</button>
              <button type="button" class="bouton discret petit" [disabled]="enCours()" (click)="desactiver()">Désactiver</button>
            </span>
          }
        }
        @if (appareils().length > 1 || (appareils().length === 1 && etat() !== 'actives')) {
          <p class="doux">Activées aussi sur : {{ autresAppareils() }}</p>
        }
      </section>

      @if (preferences(); as p) {
        <section class="carte">
          <h2>Préférences</h2>
          @if (p.canal === 'SMS') {
            <p class="doux">L'établissement vous prévient par SMS. Adressez-vous au secrétariat pour changer.</p>
          }
          <label class="case">
            <input type="checkbox" name="retards" [(ngModel)]="retards" /> Me prévenir aussi des retards
          </label>
          <label class="case">
            <input type="checkbox" name="discret" [(ngModel)]="discret" />
            Message discret sur l'écran verrouillé (le détail s'affiche à l'ouverture)
          </label>
          @if (preferencesModifiees()) {
            <button type="button" class="bouton secondaire petit" [disabled]="enCours()" (click)="enregistrerPreferences()">Enregistrer</button>
          }
        </section>
      }

      <section class="carte">
        <div class="entete-section">
          <h2>Reçues</h2>
          @if (nonLues() > 0) {
            <button type="button" class="bouton discret petit" [disabled]="enCours()" (click)="toutLu()">Tout marquer comme lu ({{ nonLues() }})</button>
          }
        </div>
        <ul class="liste">
          @for (n of notifications(); track n.id) {
            <li [class.non-lue]="!n.lueLe" [class.corrigee]="n.corrigeeLe">
              <span>
                <strong>{{ n.titre }}</strong> <span class="doux">· {{ dateHeureCourte(n.recueLe) }}</span><br />
                <span class="texte">{{ n.message }}</span>
                @if (n.corrigeeLe) { <br /><span class="pastille present">Corrigé par l'établissement : votre enfant était présent(e).</span> }
              </span>
              @if (n.eleveId) { <a class="bouton discret petit" [routerLink]="['/parent/enfants', n.eleveId]">Voir</a> }
            </li>
          } @empty {
            <li class="doux">Aucune notification pour le moment.</li>
          }
        </ul>
      </section>
    </div>
  `,
  styles: `
    .case {
      display: flex;
      gap: 0.5rem;
      align-items: flex-start;
      margin: 0.5rem 0;
    }
    .non-lue strong {
      color: var(--primaire);
    }
    .corrigee .texte {
      text-decoration: line-through;
      color: var(--texte-doux);
    }
    h2 {
      font-size: 1.05rem;
      margin: 0 0 0.5rem;
    }
  `,
})
export class NotificationsParentPage implements OnInit {
  private readonly api = inject(ParentApi);
  private readonly swPush = inject(SwPush, { optional: true });

  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly etat = signal<EtatTelephone>('chargement');
  protected readonly notifications = signal<NotificationParentVue[]>([]);
  protected readonly nonLues = signal(0);
  protected readonly appareils = signal<AppareilVue[]>([]);
  protected readonly preferences = signal<PreferencesNotifications | null>(null);
  protected readonly retards = signal(true);
  protected readonly discret = signal(false);
  protected readonly enCours = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);
  private configuration: ConfigurationPush | null = null;
  private endpointLocal: string | null = null;

  protected readonly preferencesModifiees = computed(() => {
    const p = this.preferences();
    return !!p && (p.notifierRetards !== this.retards() || p.discret !== this.discret());
  });
  protected readonly autresAppareils = computed(() =>
    this.appareils().map((a) => a.appareil ?? 'un appareil').join(', '),
  );

  async ngOnInit(): Promise<void> {
    await Promise.all([this.chargerCentre(), this.chargerPreferences(), this.chargerTelephone()]);
  }

  protected async activer(): Promise<void> {
    if (!this.swPush?.isEnabled || !this.configuration?.clePublique) {
      return;
    }
    await this.executer(async () => {
      let abonnement: PushSubscription;
      try {
        abonnement = await this.swPush!.requestSubscription({ serverPublicKey: this.configuration!.clePublique! });
      } catch {
        this.etat.set(typeof Notification !== 'undefined' && Notification.permission === 'denied' ? 'refusees' : 'inactives');
        throw new Error("Les notifications n'ont pas été autorisées sur ce téléphone.");
      }
      const json = abonnement.toJSON();
      this.appareils.set(await this.api.abonner({
        endpoint: abonnement.endpoint,
        p256dh: json.keys?.['p256dh'] ?? '',
        auth: json.keys?.['auth'] ?? '',
        appareil: nomAppareil(navigator.userAgent),
      }));
      this.endpointLocal = abonnement.endpoint;
      this.etat.set('actives');
      this.message.set('Notifications activées sur ce téléphone.');
    });
  }

  protected async desactiver(): Promise<void> {
    await this.executer(async () => {
      const endpoint = this.endpointLocal;
      await this.swPush?.unsubscribe().catch(() => undefined);
      if (endpoint) {
        this.appareils.set(await this.api.desabonner(endpoint));
      }
      this.endpointLocal = null;
      this.etat.set('inactives');
      this.message.set("Notifications désactivées sur ce téléphone : l'établissement vous préviendra par SMS.");
    });
  }

  protected async essai(): Promise<void> {
    await this.executer(async () => {
      const r = await this.api.essaiPush();
      this.message.set(`Essai envoyé à ${r.appareils} appareil(s) : il arrive dans quelques secondes.`);
    });
  }

  protected async enregistrerPreferences(): Promise<void> {
    await this.executer(async () => {
      const p = await this.api.modifierPreferences(this.retards(), this.discret());
      this.majPreferences(p);
      this.message.set('Préférences enregistrées.');
    });
  }

  protected async toutLu(): Promise<void> {
    await this.executer(async () => {
      const c = await this.api.marquerLues();
      this.notifications.set(c.notifications);
      this.nonLues.set(c.nonLues);
    });
  }

  private async chargerCentre(): Promise<void> {
    try {
      const c = await this.api.centreNotifications();
      this.notifications.set(c.notifications);
      this.nonLues.set(c.nonLues);
    } catch (e) {
      this.erreur.set(messageErreur(e));
    }
  }

  private async chargerPreferences(): Promise<void> {
    try {
      this.majPreferences(await this.api.preferencesNotifications());
    } catch {
      // sans enfant rattaché : pas de préférences
    }
  }

  private majPreferences(p: PreferencesNotifications): void {
    this.preferences.set(p);
    this.retards.set(p.notifierRetards);
    this.discret.set(p.discret);
  }

  private async chargerTelephone(): Promise<void> {
    try {
      this.configuration = await this.api.configurationPush();
      if (!this.configuration.disponible) {
        this.etat.set('indisponible-plateforme');
        return;
      }
      this.appareils.set(await this.api.appareils());
      if (!this.swPush?.isEnabled) {
        this.etat.set('navigateur');
        return;
      }
      if (typeof Notification !== 'undefined' && Notification.permission === 'denied') {
        this.etat.set('refusees');
        return;
      }
      const abonnement = await firstValueFrom(this.swPush.subscription);
      this.endpointLocal = abonnement?.endpoint ?? null;
      this.etat.set(abonnement ? 'actives' : 'inactives');
      if (abonnement && !this.appareils().length) {
        // Abonnement du navigateur inconnu du serveur (réinstallation) : on le réenregistre
        const json = abonnement.toJSON();
        this.appareils.set(await this.api.abonner({
          endpoint: abonnement.endpoint, p256dh: json.keys?.['p256dh'] ?? '', auth: json.keys?.['auth'] ?? '',
          appareil: nomAppareil(navigator.userAgent),
        }));
      }
    } catch (e) {
      this.etat.set('indisponible-plateforme');
      this.erreur.set(messageErreur(e));
    }
  }

  private async executer(action: () => Promise<void>): Promise<void> {
    this.enCours.set(true);
    this.erreur.set(null);
    this.message.set(null);
    try {
      await action();
    } catch (e) {
      this.erreur.set(e instanceof Error && !('status' in e) ? e.message : messageErreur(e));
    } finally {
      this.enCours.set(false);
    }
  }
}

