import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { dateHeureCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { ReglageNotifications, SmsVue, StatutSms } from '../modeles-vs';
import { VieScolaireApi } from '../vie-scolaire-api.service';
import { VsNavComponent } from '../vs-nav.component';

const STATUTS: Record<StatutSms, string> = {
  EN_ATTENTE: 'En attente',
  EN_COURS: 'En cours',
  ENVOYE: 'Envoyé',
  ECHEC: 'Échec',
  ANNULE: 'Annulé (correction)',
};

/**
 * Notifications aux familles (v0.50) : délai de correction laissé à l'enseignant après l'appel,
 * retards par SMS ou seulement dans l'application, et suivi des derniers SMS.
 */
@Component({
  selector: 'app-notifications-familles',
  imports: [FormsModule, VsNavComponent],
  template: `
    <div class="page large">
      <h1>Vie scolaire</h1>
      <app-vs-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      @if (reglage(); as r) {
        <section class="carte">
          <h2>Comment les familles sont prévenues</h2>
          <p>
            Dès que l'enseignant envoie son appel, les parents qui ont activé les notifications sur leur téléphone
            sont prévenus dans l'application ; les autres, et ceux que le secrétariat a mis « toujours par SMS »
            (parents sans smartphone), reçoivent un SMS.
          </p>
          @if (!r.pushDisponible) {
            <div class="alerte attention">Les notifications dans l'application ne sont pas encore ouvertes sur la plateforme : tout part par SMS.</div>
          }
          <div class="grille-champs">
            <div class="champ">
              <label for="delai">Délai de correction (minutes)</label>
              <input id="delai" name="delai" type="number" min="0" max="60" step="1" [disabled]="!modifiable()" [(ngModel)]="delai" />
              <span class="doux">Le temps laissé à l'enseignant pour corriger une erreur d'appel. 0 : immédiat.</span>
            </div>
          </div>
          <label class="case">
            <input type="checkbox" name="smsRetards" [disabled]="!modifiable()" [(ngModel)]="smsRetards" />
            Prévenir aussi des retards par SMS (sinon, seulement dans l'application)
          </label>
          @if (modifiable() && modifie()) {
            <button type="button" class="bouton" [disabled]="action.enCours()" (click)="enregistrer()">Enregistrer</button>
          }
        </section>
      }

      @if (voitSms()) {
        <section class="carte">
          <h2>Derniers SMS</h2>
          <ul class="liste">
            @for (s of sms(); track s.id) {
              <li>
                <span>
                  <span class="doux">{{ dateHeureCourte(s.creeLe) }} · {{ s.destinataire }}</span><br />
                  {{ s.message }}
                  @if (s.derniereErreur && s.statut !== 'ENVOYE') { <br /><span class="doux">{{ s.derniereErreur }}</span> }
                </span>
                <span class="pastille" [class.present]="s.statut === 'ENVOYE'" [class.absent]="s.statut === 'ECHEC'">{{ statuts[s.statut] }}</span>
              </li>
            } @empty {
              <li class="doux">Aucun SMS envoyé.</li>
            }
          </ul>
        </section>
      }
    </div>
  `,
  styles: `
    .case {
      display: flex;
      gap: 0.5rem;
      align-items: flex-start;
      margin: 0.75rem 0;
    }
    h2 {
      font-size: 1.05rem;
      margin: 0 0 0.5rem;
    }
  `,
})
export class NotificationsFamillesPage implements OnInit {
  private readonly api = inject(VieScolaireApi);
  private readonly session = inject(SessionService);

  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly statuts = STATUTS;
  protected readonly action = new Action();
  protected readonly reglage = signal<ReglageNotifications | null>(null);
  protected readonly delai = signal(0);
  protected readonly smsRetards = signal(false);
  protected readonly sms = signal<SmsVue[]>([]);
  protected readonly message = signal<string | null>(null);
  protected readonly modifiable = computed(() => this.session.aLeRole('ADMIN_ECOLE', 'SURVEILLANT'));
  protected readonly voitSms = computed(() => this.session.aLeRole('ADMIN_ECOLE', 'SURVEILLANT'));
  protected readonly modifie = computed(() => {
    const r = this.reglage();
    return !!r && (Number(this.delai()) !== r.delaiCorrectionMinutes || this.smsRetards() !== r.smsRetards);
  });

  async ngOnInit(): Promise<void> {
    const r = await this.action.executer(() => this.api.reglagesNotifications());
    if (r) {
      this.majReglage(r);
    }
    if (this.voitSms()) {
      const l = await this.action.executer(() => this.api.derniersSms());
      if (l) {
        this.sms.set(l);
      }
    }
  }

  protected async enregistrer(): Promise<void> {
    this.message.set(null);
    const r = await this.action.executer(() =>
      this.api.modifierReglagesNotifications(Number(this.delai()), this.smsRetards()),
    );
    if (r) {
      this.majReglage(r);
      this.message.set('Réglages enregistrés.');
    }
  }

  private majReglage(r: ReglageNotifications): void {
    this.reglage.set(r);
    this.delai.set(r.delaiCorrectionMinutes);
    this.smsRetards.set(r.smsRetards);
  }
}
