import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { Action } from '../admin/action';
import { dateHeureCourte } from '../core/outils';
import { API } from '../core/session.service';

/** Appareil connecté au compte (v0.32). */
export interface AppareilVue {
  id: string;
  appareil: string;
  ouverteLe: string;
  dernierUsage: string;
  etablissement: string | null;
  courant: boolean;
}

/**
 * Mes appareils : où mon compte est connecté. Un appareil oublié chez un collègue se déconnecte ;
 * un téléphone perdu ou volé se déconnecte et efface ses données au prochain contact avec le serveur.
 */
@Component({
  selector: 'app-appareils',
  template: `
    <div class="page">
      <h1>Mes appareils</h1>
      <p class="doux">
        Les téléphones et ordinateurs où votre compte est connecté. Si vous ne reconnaissez pas un appareil, ou si votre
        téléphone est perdu ou volé, déconnectez-le. Avec « effacer », il supprime toutes les données de l'application
        (y compris les appels et notes pas encore envoyés) dès qu'il se connecte au réseau. Pensez aussi à changer votre
        mot de passe.
      </p>

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message()) {
        <div class="alerte succes" role="status">{{ message() }}</div>
      }

      @if (appareils(); as liste) {
        <ul class="liste">
          @for (a of liste; track a.id) {
            <li class="appareil">
              <div>
                <strong>{{ a.appareil }}</strong>
                @if (a.courant) { <span class="pastille present">cet appareil</span> }
                <br />
                <span class="doux">
                  @if (a.etablissement) { {{ a.etablissement }} · }
                  connecté le {{ dateHeureCourte(a.ouverteLe) }} · dernière activité le {{ dateHeureCourte(a.dernierUsage) }}
                </span>
              </div>
              @if (!a.courant) {
                @if (confirmation() === a.id) {
                  <div class="confirmation" role="alertdialog" [attr.aria-label]="'Déconnecter ' + a.appareil">
                    <p>Déconnecter « {{ a.appareil }} » ?</p>
                    <div class="actions-ligne">
                      <button type="button" class="bouton secondaire petit" [disabled]="action.enCours()" (click)="fermer(a, false)">Déconnecter</button>
                      <button type="button" class="bouton danger petit" [disabled]="action.enCours()" (click)="fermer(a, true)">Perdu ou volé : déconnecter et effacer</button>
                      <button type="button" class="bouton discret petit" (click)="confirmation.set(null)">Annuler</button>
                    </div>
                  </div>
                } @else {
                  <button type="button" class="bouton secondaire petit" (click)="confirmation.set(a.id)">Déconnecter…</button>
                }
              }
            </li>
          } @empty {
            <li class="doux">Aucun appareil connecté.</li>
          }
        </ul>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .appareil {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 0.5rem 1rem;
      .pastille {
        margin-left: 0.4rem;
      }
    }
    .confirmation {
      flex-basis: 100%;
      padding: 0.6rem 0.75rem;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      background: var(--surface);
      p {
        margin: 0 0 0.5rem;
      }
    }
  `,
})
export class AppareilsPage {
  private readonly http = inject(HttpClient);
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly action = new Action();
  protected readonly appareils = signal<AppareilVue[] | null>(null);
  protected readonly confirmation = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);

  constructor() {
    void this.charger();
  }

  protected async fermer(a: AppareilVue, effacer: boolean): Promise<void> {
    this.message.set(null);
    const ok = await this.action.reussit(() =>
      firstValueFrom(this.http.post(`${API}/auth/appareils/${a.id}/fermeture`, { effacer })),
    );
    this.confirmation.set(null);
    if (ok) {
      this.appareils.update((l) => (l ?? []).filter((x) => x.id !== a.id));
      this.message.set(
        effacer
          ? `« ${a.appareil} » est déconnecté. Ses données seront effacées dès qu'il se connectera au réseau.`
          : `« ${a.appareil} » est déconnecté.`,
      );
    }
  }

  private async charger(): Promise<void> {
    const l = await this.action.executer(() => firstValueFrom(this.http.get<AppareilVue[]>(`${API}/auth/appareils`)));
    if (l) {
      this.appareils.set(l);
    }
  }
}
