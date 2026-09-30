import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { dateHeureCourte, dateLongue } from '../core/outils';
import { SessionService } from '../core/session.service';
import { Envoi, EnvoisService } from '../hors-ligne/envois.service';

@Component({
  selector: 'app-envois',
  imports: [RouterLink],
  template: `
    <div class="page">
      <h1>Mes appels</h1>

      @if (vientDeSaisir()) {
        <div class="alerte succes" role="status">
          Appel enregistré sur le téléphone.
          @if (envois.enAttente() > 0) {
            Il sera envoyé dès qu'il y aura du réseau.
          }
        </div>
      }
      @if (envois.dernierBilan()?.erreur; as erreur) {
        @if (envois.enAttente() > 0) {
          <div class="alerte attention" role="status">{{ erreur }}</div>
        }
      }

      <div class="actions">
        <a routerLink="/appel" class="bouton">Nouvel appel</a>
        <button
          type="button"
          class="bouton secondaire"
          [disabled]="envois.synchronisation() || envois.enAttente() === 0 || session.horsConnexion()"
          (click)="envois.synchroniser()"
        >
          {{ envois.synchronisation() ? 'Envoi…' : 'Envoyer maintenant' }}
        </button>
      </div>

      @for (groupe of groupes(); track groupe.titre) {
        @if (groupe.envois.length > 0) {
          <h2>{{ groupe.titre }}</h2>
          <ul class="liste carte">
            @for (e of groupe.envois; track e.cle) {
              <li class="envoi">
                <div class="entete">
                  <strong>{{ e.classeCode }} · {{ e.matiereLibelle }}</strong>
                  @switch (e.etat) {
                    @case ('EN_ATTENTE') { <span class="pastille">en attente</span> }
                    @case ('ENVOYE') { <span class="pastille present">envoyé ✓</span> }
                    @case ('REFUSE') { <span class="pastille absent">refusé</span> }
                  }
                </div>
                <div class="doux">
                  {{ dateLongue(e.appel.date) }}, {{ e.appel.heureDebut }}–{{ e.appel.heureFin }} ·
                  {{ e.absents }} absent(s), {{ e.retards }} retard(s)
                </div>
                @if (e.etat === 'ENVOYE' && e.traiteLe) {
                  <div class="doux">Reçu par le serveur {{ dateHeureCourte(e.traiteLe) }}</div>
                }
                @if (e.accuse?.inscriptionsIgnorees?.length; as ignores) {
                  <div class="doux">
                    {{ ignores }} élève(s) ignoré(s) : ils ne sont plus inscrits dans
                    cette classe.
                  </div>
                }
                @if (e.etat === 'REFUSE') {
                  <p class="alerte erreur motif">{{ e.message }}</p>
                  <button type="button" class="bouton discret" (click)="retirer(e)">Retirer de la liste</button>
                }
              </li>
            }
          </ul>
        }
      }

      @if (envois.envois().length === 0) {
        <p class="doux">Aucun appel sur ce téléphone.</p>
      } @else {
        <p class="doux">Les appels envoyés restent affichés une semaine.</p>
      }
    </div>
  `,
  styles: `
    .actions {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0.75rem;
      margin-bottom: 1.25rem;
    }
    .envoi {
      padding: 0.75rem 0;
    }
    .entete {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 0.5rem;
    }
    .motif {
      margin: 0.5rem 0 0.25rem;
    }
  `,
})
export class EnvoisPage {
  protected readonly envois = inject(EnvoisService);
  protected readonly session = inject(SessionService);
  protected readonly dateLongue = dateLongue;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly vientDeSaisir = signal(
    Boolean(inject(Router).currentNavigation()?.extras.state?.['vientDeSaisir']),
  );

  protected readonly groupes = computed(() => {
    const tous = this.envois.envois();
    return [
      { titre: 'Refusés', envois: tous.filter((e) => e.etat === 'REFUSE') },
      { titre: 'En attente', envois: tous.filter((e) => e.etat === 'EN_ATTENTE') },
      { titre: 'Envoyés', envois: tous.filter((e) => e.etat === 'ENVOYE') },
    ];
  });

  protected retirer(e: Envoi): Promise<void> {
    return this.envois.retirer(e);
  }
}
