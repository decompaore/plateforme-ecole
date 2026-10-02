import { Component, computed, DestroyRef, inject, input, isDevMode, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { messageErreur } from '../core/erreurs';
import { nouvelIdentifiant } from '../core/outils';
import { fcfa, LIBELLE_OPERATEUR, Operateur, SituationVue, TransactionVue } from './modeles-parent';
import { ParentApi } from './parent-api.service';

/** Intervalle de suivi de la transaction (le serveur attend la confirmation de l'opérateur). */
export const SUIVI_MS = 5000;

/**
 * Paiement de la scolarité par Mobile Money : le parent choisit le montant et l'opérateur,
 * confirme sur son téléphone avec son code secret, et l'écran suit la transaction jusqu'au
 * reçu. Une même demande envoyée deux fois (double appui, réseau lent) ne paie qu'une fois.
 */
@Component({
  selector: 'app-paiement',
  imports: [FormsModule],
  template: `
    @if (!transaction()) {
      <form class="paiement" (ngSubmit)="payer()" aria-label="Payer par Mobile Money">
        <h3>Payer par Mobile Money</h3>
        <fieldset class="operateurs">
          <legend>Opérateur</legend>
          @for (o of operateurs; track o) {
            <label class="operateur" [class.choisi]="operateur() === o">
              <input type="radio" name="operateur" [value]="o" [checked]="operateur() === o" (change)="operateur.set(o)" />
              {{ libelles[o] }}
            </label>
          }
        </fieldset>
        <div class="champ">
          <label for="montant">Montant (FCFA)</label>
          <input id="montant" name="montant" type="number" inputmode="numeric" min="100" [max]="situation().resteFamille" step="1" required [(ngModel)]="montant" />
          <div class="raccourcis">
            @if (situation().retardFamille > 0 && situation().retardFamille < situation().resteFamille) {
              <button type="button" class="bouton secondaire petit" (click)="montant.set(situation().retardFamille)">Le retard : {{ fcfa(situation().retardFamille) }}</button>
            }
            <button type="button" class="bouton secondaire petit" (click)="montant.set(situation().resteFamille)">Tout le reste : {{ fcfa(situation().resteFamille) }}</button>
          </div>
        </div>
        <div class="champ">
          <label for="telephone">Numéro {{ libelles[operateur()] }} qui paie</label>
          <input id="telephone" name="telephone" type="tel" inputmode="tel" autocomplete="tel" required placeholder="70 11 22 33" [(ngModel)]="telephone" />
        </div>
        @if (invalide(); as m) {
          <p class="alerte erreur">{{ m }}</p>
        }
        @if (erreur()) {
          <p class="alerte erreur" role="alert">{{ erreur() }}</p>
        }
        <div class="actions-ligne">
          <button type="submit" class="bouton" [disabled]="envoi() || !!invalide()">
            {{ envoi() ? 'Envoi…' : 'Payer ' + fcfa(montant() || 0) }}
          </button>
          <button type="button" class="bouton secondaire" (click)="ferme.emit()">Annuler</button>
        </div>
      </form>
    } @else {
      @let t = transaction()!;
      <section class="paiement" aria-live="polite">
        <h3>Paiement de {{ fcfa(t.montant) }} par {{ libelles[t.operateur] }}</h3>
        @switch (t.statut) {
          @case ('CONFIRMEE') {
            <p class="alerte succes">Paiement reçu. Merci ! Le reçu est dans la liste des paiements et vous l'avez aussi reçu par SMS.</p>
            <button type="button" class="bouton" (click)="ferme.emit()">Terminer</button>
          }
          @case ('ECHOUEE') {
            <p class="alerte erreur">Le paiement n'a pas abouti{{ t.message ? ' : ' + t.message : '' }}. Aucun montant n'a été prélevé.</p>
            <button type="button" class="bouton" (click)="recommencer()">Réessayer</button>
          }
          @case ('EXPIREE') {
            <p class="alerte erreur">Pas de confirmation dans les 15 minutes : la demande est annulée. Aucun montant n'a été prélevé.</p>
            <button type="button" class="bouton" (click)="recommencer()">Recommencer</button>
          }
          @case ('A_VERIFIER') {
            <p class="alerte attention">L'opérateur a confirmé, mais le paiement doit être vérifié par l'intendance. Gardez le SMS de l'opérateur ; l'établissement vous recontactera si besoin.</p>
            <button type="button" class="bouton" (click)="ferme.emit()">Fermer</button>
          }
          @default {
            <ol class="etapes">
              <li>Vous allez recevoir une demande de {{ libelles[t.operateur] }} sur le <strong>{{ t.telephone }}</strong>.</li>
              <li>Vérifiez le montant ({{ fcfa(t.montant) }}) et tapez votre <strong>code secret</strong> pour confirmer.</li>
              <li>Cet écran se met à jour tout seul. Vous pouvez aussi le quitter : le paiement sera enregistré.</li>
            </ol>
            <p class="doux attente"><span class="point" aria-hidden="true"></span> En attente de votre confirmation…</p>
            @if (dev) {
              <div class="actions-ligne dev">
                <span class="doux">Développement :</span>
                <button type="button" class="bouton secondaire petit" (click)="simuler(true)">Simuler « confirmé »</button>
                <button type="button" class="bouton discret petit" (click)="simuler(false)">Simuler « refusé »</button>
              </div>
            }
          }
        }
        @if (erreur()) {
          <p class="alerte erreur" role="alert">{{ erreur() }}</p>
        }
      </section>
    }
  `,
  styles: `
    .paiement {
      margin-top: 1rem;
      padding-top: 1rem;
      border-top: 1px solid var(--bordure);
    }
    h3 {
      font-size: 1rem;
      margin: 0 0 0.75rem;
    }
    .operateurs {
      border: none;
      padding: 0;
      margin: 0 0 0.75rem;
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.4rem;
      legend {
        font-weight: 600;
        margin-bottom: 0.4rem;
      }
    }
    .operateur {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      min-height: 52px;
      padding: 0.25rem 0.5rem;
      font-size: 0.9rem;
      line-height: 1.15;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      input {
        width: auto;
        min-height: 0;
      }
    }
    .operateur.choisi {
      border-color: var(--primaire);
      box-shadow: inset 0 0 0 1px var(--primaire);
      font-weight: 600;
    }
    .raccourcis {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      margin-top: 0.4rem;
    }
    .etapes {
      padding-left: 1.25rem;
      li {
        margin-bottom: 0.4rem;
      }
    }
    .attente {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .point {
      width: 0.6rem;
      height: 0.6rem;
      border-radius: 50%;
      background: var(--retard);
      animation: pulse 1.2s ease-in-out infinite;
    }
    @keyframes pulse {
      50% {
        opacity: 0.25;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .point {
        animation: none;
      }
    }
    .dev {
      margin-top: 0.5rem;
    }
  `,
})
export class PaiementComponent implements OnInit {
  private readonly api = inject(ParentApi);

  readonly situation = input.required<SituationVue>();
  /** Paiement confirmé : la situation est à relire. */
  readonly paye = output<void>();
  readonly ferme = output<void>();

  protected readonly fcfa = fcfa;
  protected readonly libelles = LIBELLE_OPERATEUR;
  protected readonly operateurs: Operateur[] = ['ORANGE_MONEY', 'MOOV_MONEY', 'TELECEL_MONEY'];
  protected readonly dev = isDevMode();

  protected readonly operateur = signal<Operateur>('ORANGE_MONEY');
  protected readonly montant = signal<number | null>(null);
  protected readonly telephone = signal('');
  protected readonly envoi = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly transaction = signal<TransactionVue | null>(null);
  /** Une clé par demande : renvoyer la même demande ne crée pas un second paiement. */
  private cle = nouvelIdentifiant();
  private minuterie: ReturnType<typeof setTimeout> | null = null;

  protected readonly invalide = computed(() => {
    const m = Number(this.montant());
    if (!this.montant() && this.montant() !== 0) {
      return null;
    }
    if (!Number.isInteger(m) || m < 100) {
      return 'Montant minimum : 100 FCFA, sans centimes.';
    }
    if (m > this.situation().resteFamille) {
      return `Le reste à payer est de ${fcfa(this.situation().resteFamille)}.`;
    }
    return null;
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.arreterSuivi());
  }

  ngOnInit(): void {
    const s = this.situation();
    this.montant.set(s.retardFamille > 0 ? Math.min(s.retardFamille, s.resteFamille) : s.resteFamille);
  }

  protected async payer(): Promise<void> {
    if (this.invalide() || !this.montant() || !this.telephone().trim()) {
      return;
    }
    this.envoi.set(true);
    this.erreur.set(null);
    try {
      const t = await this.api.payer(this.situation().inscriptionId, {
        montant: Number(this.montant()),
        operateur: this.operateur(),
        telephone: this.telephone().trim(),
        cleIdempotence: this.cle,
      });
      this.suivre(t);
    } catch (e) {
      this.erreur.set(messageErreur(e, "Le paiement n'a pas pu être demandé. Réessayez, ou payez à l'intendance."));
    } finally {
      this.envoi.set(false);
    }
  }

  private suivre(t: TransactionVue): void {
    this.transaction.set(t);
    this.arreterSuivi();
    if (t.statut === 'CONFIRMEE') {
      this.paye.emit();
    }
    if (t.statut === 'INITIEE' || t.statut === 'EN_ATTENTE') {
      this.minuterie = setTimeout(() => void this.relire(t.id), SUIVI_MS);
    }
  }

  private async relire(id: string): Promise<void> {
    try {
      this.suivre(await this.api.transaction(id));
    } catch {
      // Réseau coupé : on réessaie au prochain tour, la transaction continue côté serveur
      this.minuterie = setTimeout(() => void this.relire(id), SUIVI_MS);
    }
  }

  private arreterSuivi(): void {
    if (this.minuterie) {
      clearTimeout(this.minuterie);
      this.minuterie = null;
    }
  }

  protected recommencer(): void {
    this.arreterSuivi();
    this.transaction.set(null);
    this.cle = nouvelIdentifiant();
  }

  protected async simuler(accepter: boolean): Promise<void> {
    const t = this.transaction();
    if (t) {
      try {
        this.suivre(await this.api.simulerConfirmation(t.id, accepter));
      } catch (e) {
        this.erreur.set(messageErreur(e));
      }
    }
  }
}
