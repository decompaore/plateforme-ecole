import { Component, computed, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { dateHeureCourte } from '../core/outils';
import { API } from '../core/session.service';
import { Action } from './action';
import { AdminApi } from './admin-api.service';
import { ExportVue, LIBELLE_STATUT_EXPORT } from './modeles-admin';

/** « 12,4 Mo » */
export function tailleLisible(octets: number | null): string {
  if (octets === null) {
    return '';
  }
  const unites = ['octets', 'Ko', 'Mo', 'Go'];
  let v = octets;
  let i = 0;
  while (v >= 1024 && i < unites.length - 1) {
    v /= 1024;
    i++;
  }
  return `${i === 0 ? v : v.toLocaleString('fr-FR', { maximumFractionDigits: 1 })} ${unites[i]}`;
}

/** Intervalle de rafraîchissement pendant la préparation d'une archive. */
export const RAFRAICHISSEMENT_MS = 3000;

/**
 * Export complet des données d'un établissement (réversibilité) : demande avec le mot de passe,
 * suivi de la préparation, téléchargement par un lien signé que le navigateur ouvre lui-même
 * (barre de progression, reprise). Sans `etablissementId`, l'établissement de la session ; avec,
 * le super administrateur agit pour cet établissement.
 */
@Component({
  selector: 'app-exports-donnees',
  imports: [FormsModule],
  template: `
    @if (action.erreur()) {
      <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
    }
    @if (message()) {
      <div class="alerte succes" role="status">{{ message() }}</div>
    }

    <form class="demande" (ngSubmit)="demander()">
      <div class="champ">
        <label [for]="'mdp-export-' + (etablissementId() ?? 'etab')">Votre mot de passe, pour confirmer</label>
        <input [id]="'mdp-export-' + (etablissementId() ?? 'etab')" name="motDePasse" type="password"
          autocomplete="current-password" required [(ngModel)]="motDePasse" />
      </div>
      <button type="submit" class="bouton" [disabled]="action.enCours() || !motDePasse() || enPreparation()">
        {{ enPreparation() ? 'Préparation en cours…' : 'Préparer un export complet' }}
      </button>
    </form>

    @if (exports() === null) {
      <p class="doux">Chargement…</p>
    } @else if (exports()!.length) {
      <ul class="liste exports">
        @for (e of exports(); track e.id) {
          <li>
            <div class="ligne">
              <span>
                <strong>{{ date(e.demandeLe) }}</strong>
                <span class="doux"> · {{ e.demandePar ?? '—' }}</span>
              </span>
              <span class="pastille" [class.present]="e.statut === 'PRET'" [class.absent]="e.statut === 'ECHEC' || e.statut === 'INTERROMPU'">
                {{ libelle[e.statut] }}
              </span>
            </div>
            @if (e.statut === 'PRET') {
              <div class="ligne">
                <span class="doux">
                  {{ taille(e.taille) }} · {{ e.nombreTables }} tables · {{ e.nombreLignes }} lignes ·
                  disponible jusqu'au {{ date(e.expireLe!) }}
                  @if (e.telechargements) { · téléchargé {{ e.telechargements }} fois }
                </span>
                <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="telecharger(e)">Télécharger</button>
              </div>
              <p class="doux empreinte" [title]="e.empreinte">Empreinte SHA-256 : <code>{{ e.empreinte }}</code></p>
            } @else if (e.statut === 'EN_COURS') {
              <p class="doux">L'archive se prépare ; cette page se met à jour toute seule.</p>
            } @else if (e.erreur) {
              <p class="doux">{{ e.erreur }}</p>
            }
          </li>
        }
      </ul>
    } @else {
      <p class="doux">Aucun export n'a encore été demandé.</p>
    }
  `,
  styles: `
    .demande {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: 0 1rem;
      margin-bottom: 1rem;
      .champ {
        flex: 0 1 18rem;
      }
      .bouton {
        margin-bottom: 1rem;
      }
    }
    .exports li {
      display: block;
    }
    .ligne {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 0.4rem 0.75rem;
    }
    .ligne + .ligne {
      margin-top: 0.35rem;
    }
    .empreinte {
      margin: 0.35rem 0 0;
      font-size: 0.8rem;
      overflow-wrap: anywhere;
    }
  `,
})
export class ExportsDonneesComponent implements OnInit {
  private readonly api = inject(AdminApi);

  readonly etablissementId = input<string | undefined>(undefined);

  protected readonly libelle = LIBELLE_STATUT_EXPORT;
  protected readonly date = dateHeureCourte;
  protected readonly taille = tailleLisible;
  protected readonly action = new Action();
  protected readonly exports = signal<ExportVue[] | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly motDePasse = signal('');
  protected readonly enPreparation = computed(() => (this.exports() ?? []).some((e) => e.statut === 'EN_COURS'));

  private minuteur: ReturnType<typeof setTimeout> | null = null;
  private detruit = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.detruit = true;
      this.arreterSuivi();
    });
  }

  async ngOnInit(): Promise<void> {
    await this.charger();
  }

  protected async demander(): Promise<void> {
    this.message.set(null);
    const e = await this.action.executer(() => this.api.demanderExport(this.motDePasse(), this.etablissementId()));
    this.motDePasse.set('');
    if (e) {
      this.exports.update((l) => [e, ...(l ?? [])]);
      this.message.set('Export demandé. L’archive se prépare ; elle sera téléchargeable ici dans quelques instants.');
      this.suivre();
    }
  }

  protected async telecharger(e: ExportVue): Promise<void> {
    this.message.set(null);
    const lien = await this.action.executer(() => this.api.lienExport(e.id, this.etablissementId()));
    if (lien) {
      ouvrirTelechargement(API + lien.chemin);
      this.exports.update((l) => (l ?? []).map((x) => (x.id === e.id ? { ...x, telechargements: x.telechargements + 1 } : x)));
    }
  }

  private async charger(): Promise<void> {
    const l = await this.action.executer(() => this.api.exports(this.etablissementId()));
    if (l) {
      const etaitEnCours = this.enPreparation();
      this.exports.set(l);
      if (etaitEnCours && l[0]?.statut === 'PRET') {
        this.message.set('L’archive est prête : vous pouvez la télécharger.');
      }
      this.suivre();
    }
  }

  /** Recharge la liste tant qu'une archive est en préparation. */
  private suivre(): void {
    this.arreterSuivi();
    if (this.enPreparation() && !this.detruit) {
      this.minuteur = setTimeout(() => void this.charger(), RAFRAICHISSEMENT_MS);
    }
  }

  private arreterSuivi(): void {
    if (this.minuteur !== null) {
      clearTimeout(this.minuteur);
      this.minuteur = null;
    }
  }
}

/** Le navigateur télécharge lui-même l'archive (progression, reprise) : lien signé, sans jeton d'accès. */
export function ouvrirTelechargement(url: string): void {
  const lien = document.createElement('a');
  lien.href = url;
  lien.rel = 'noopener';
  lien.download = '';
  document.body.appendChild(lien);
  lien.click();
  lien.remove();
}
