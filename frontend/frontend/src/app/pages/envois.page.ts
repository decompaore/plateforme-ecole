import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { dateHeureCourte, dateLongue } from '../core/outils';
import { SessionService } from '../core/session.service';
import { CahierService } from '../hors-ligne/cahier.service';
import { Envoi, EnvoisService } from '../hors-ligne/envois.service';
import { NotesService, OperationNotes } from '../hors-ligne/notes.service';

@Component({
  selector: 'app-envois',
  imports: [RouterLink],
  template: `
    <div class="page">
      <h1>Mes envois</h1>

      @if (vientDeSaisir()) {
        <div class="alerte succes" role="status">
          Appel enregistré sur le téléphone.
          @if (envois.enAttente() > 0) {
            Il sera envoyé dès qu'il y aura du réseau.
          }
          @if (cours(); as c) {
            @if (progression) {
            <br /><a [routerLink]="['/cahier', c.classeId, c.matiereId]" [queryParams]="{ date: c.date, debut: c.debut, fin: c.fin }">Remplir le cahier de textes de ce cours ›</a>
            }
          }
        </div>
      }
      @if (cahier.enAttente() + cahier.refusees() > 0) {
        <a routerLink="/cahier" class="alerte {{ cahier.refusees() > 0 ? 'erreur' : 'attention' }} bloc">
          Cahier de textes : {{ cahier.enAttente() }} séance(s) en attente@if (cahier.refusees() > 0) {, {{ cahier.refusees() }} refusée(s)} ›
        </a>
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
          [disabled]="envois.synchronisation() || notes.synchronisation() || (envois.enAttente() === 0 && notes.enAttente() === 0) || session.horsConnexion()"
          (click)="envoyerTout()"
        >
          {{ envois.synchronisation() || notes.synchronisation() ? 'Envoi…' : 'Envoyer maintenant' }}
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

      <h2 class="section">Notes</h2>
      @if (notes.dernierBilan()?.erreur; as erreur) {
        @if (notes.enAttente() > 0) {
          <div class="alerte attention" role="status">{{ erreur }}</div>
        }
      }
      <ul class="liste carte">
        @for (o of notes.operations(); track o.cle) {
          <li class="envoi">
            <div class="entete">
              <strong>{{ o.classeCode }} · {{ o.matiereLibelle }}</strong>
              @if (o.etat === 'REFUSE') {
                <span class="pastille absent">refusé</span>
              } @else {
                <span class="pastille">en attente</span>
              }
            </div>
            <div class="doux">
              {{ o.type === 'CREATION' ? 'Création de « ' + o.libelle + ' »' : 'Notes de « ' + o.libelle + ' » (' + nombreNotes(o) + ' élève(s))' }}
            </div>
            @if (o.etat === 'REFUSE') {
              <p class="alerte erreur motif">{{ o.message }}</p>
              <div class="actions-ligne">
                <a class="bouton discret petit" [routerLink]="['/notes', o.classeId, o.matiereId, o.evaluationId]">Ouvrir</a>
                <button type="button" class="bouton discret petit" (click)="abandonner(o)">Abandonner</button>
              </div>
            }
          </li>
        } @empty {
          <li class="doux vide">Toutes les notes saisies sur ce téléphone ont été envoyées.</li>
        }
      </ul>
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
    .section {
      margin-top: 1.5rem;
    }
    .vide {
      padding: 0.75rem 0;
    }
    .motif {
      margin: 0.5rem 0 0.25rem;
    }
  `,
})
export class EnvoisPage {
  protected readonly cahier = inject(CahierService);
  protected readonly envois = inject(EnvoisService);
  protected readonly session = inject(SessionService);
  protected readonly progression = this.session.moduleActif('PROGRESSION');
  protected readonly dateLongue = dateLongue;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly vientDeSaisir = signal(
    Boolean(inject(Router).currentNavigation()?.extras.state?.['vientDeSaisir']),
  );
  /** Cours dont l'appel vient d'être fait (pour enchaîner sur le cahier de textes). */
  protected readonly cours = signal<{ classeId: string; matiereId: string; date: string; debut: string; fin: string } | null>(
    inject(Router).currentNavigation()?.extras.state?.['cours'] ?? null,
  );

  protected readonly groupes = computed(() => {
    const tous = this.envois.envois();
    return [
      { titre: 'Refusés', envois: tous.filter((e) => e.etat === 'REFUSE') },
      { titre: 'En attente', envois: tous.filter((e) => e.etat === 'EN_ATTENTE') },
      { titre: 'Envoyés', envois: tous.filter((e) => e.etat === 'ENVOYE') },
    ];
  });

  protected readonly notes = inject(NotesService);

  constructor() {
    void this.notes.recharger();
  }

  protected nombreNotes(o: OperationNotes): number {
    return Object.keys(o.notes ?? {}).length;
  }

  protected async envoyerTout(): Promise<void> {
    await this.envois.synchroniser();
    await this.notes.synchroniser();
  }

  protected abandonner(o: OperationNotes): Promise<void> {
    const texte =
      o.type === 'CREATION'
        ? `Abandonner la création de « ${o.libelle} » et les notes saisies dessus ?`
        : `Abandonner les notes de « ${o.libelle} » non envoyées ?`;
    return window.confirm(texte) ? this.notes.abandonner(o) : Promise.resolve();
  }

  protected retirer(e: Envoi): Promise<void> {
    return this.envois.retirer(e);
  }
}
