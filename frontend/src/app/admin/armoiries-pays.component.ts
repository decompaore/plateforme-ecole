import { HttpClient } from '@angular/common/http';
import { Component, inject, input, OnDestroy, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import { Action } from './action';

interface EtatArmoiries {
  paysId: string;
  presentes: boolean;
  modifieLe: string | null;
  taille: number | null;
}

/**
 * Armoiries d'un pays (v0.48.1), affichées en fond d'écran (en couleurs depuis v0.49.1) pour toutes les personnes du pays. L'image officielle
 * (PNG transparent de préférence) est téléversée par l'administrateur du pays ou le super administrateur.
 */
@Component({
  selector: 'app-armoiries-pays',
  template: `
    <h3>Armoiries (fond d'écran)</h3>
    @if (!ouvert()) {
      <button type="button" class="bouton discret petit" (click)="ouvrir()">Gérer les armoiries de {{ nom() }}</button>
    } @else {
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      <p class="doux">
        Image officielle des armoiries du pays, de préférence en PNG à fond transparent (5 Mo au plus ; elle est réduite
        à 800 pixels). Elle apparaît en fond d'écran, en couleurs, derrière le contenu des écrans des établissements, des directions et
        de l'administration du pays.
      </p>
      <div class="armoiries">
        @if (apercu(); as a) {
          <img [src]="a" alt="Armoiries enregistrées" />
        } @else if (etat() && !etat()!.presentes) {
          <span class="doux">Aucune armoirie enregistrée.</span>
        }
        <div class="actions-ligne">
          <label class="bouton secondaire petit">
            {{ etat()?.presentes ? 'Remplacer' : 'Téléverser' }}
            <input type="file" accept="image/png,image/jpeg" class="visuellement-cache" (change)="televerser($event)" />
          </label>
          @if (etat()?.presentes) {
            <button type="button" class="bouton danger petit" [disabled]="action.enCours()" (click)="supprimer()">Retirer</button>
          }
        </div>
      </div>
    }
  `,
  styles: `
    .armoiries {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 1rem;
    }
    img {
      max-height: 120px;
      max-width: 160px;
      object-fit: contain;
      background: var(--fond);
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      padding: 0.4rem;
    }
  `,
})
export class ArmoiriesPaysComponent implements OnDestroy {
  private readonly http = inject(HttpClient);

  readonly paysId = input.required<string>();
  readonly nom = input<string>('ce pays');

  protected readonly action = new Action();
  protected readonly ouvert = signal(false);
  protected readonly etat = signal<EtatArmoiries | null>(null);
  protected readonly apercu = signal<string | null>(null);

  private url(): string {
    return `${API}/plateforme/territoire/pays/${this.paysId()}/armoiries`;
  }

  protected async ouvrir(): Promise<void> {
    this.ouvert.set(true);
    await this.charger();
  }

  private async charger(): Promise<void> {
    const e = await this.action.executer(() => firstValueFrom(this.http.get<EtatArmoiries>(`${this.url()}/etat`)));
    if (!e) {
      return;
    }
    this.etat.set(e);
    this.liberer();
    if (e.presentes) {
      const b = await this.action.executer(() => firstValueFrom(this.http.get(this.url(), { responseType: 'blob' })));
      if (b) {
        this.apercu.set(URL.createObjectURL(b));
      }
    }
  }

  protected async televerser(evenement: Event): Promise<void> {
    const champ = evenement.target as HTMLInputElement;
    const fichier = champ.files?.[0];
    champ.value = '';
    if (!fichier) {
      return;
    }
    const donnees = new FormData();
    donnees.append('fichier', fichier, fichier.name);
    const e = await this.action.executer(() => firstValueFrom(this.http.post<EtatArmoiries>(this.url(), donnees)));
    if (e) {
      await this.charger();
    }
  }

  protected async supprimer(): Promise<void> {
    const e = await this.action.executer(() => firstValueFrom(this.http.delete<EtatArmoiries>(this.url())));
    if (e) {
      this.etat.set(e);
      this.liberer();
    }
  }

  private liberer(): void {
    const a = this.apercu();
    if (a) {
      URL.revokeObjectURL(a);
    }
    this.apercu.set(null);
  }

  ngOnDestroy(): void {
    this.liberer();
  }
}
