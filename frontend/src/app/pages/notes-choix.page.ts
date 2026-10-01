import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { messageErreur } from '../core/erreurs';
import { Affectation } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { ListesService } from '../hors-ligne/listes.service';
import { NotesService } from '../hors-ligne/notes.service';

/** Saisie des notes : choix de la classe et de la matière. */
@Component({
  selector: 'app-notes-choix',
  imports: [RouterLink],
  template: `
    <div class="page">
      <h1>Saisie des notes</h1>
      @if (erreur()) {
        <div class="alerte erreur" role="alert">{{ erreur() }}</div>
      }
      @if (notes.enAttente() > 0) {
        <a routerLink="/envois" class="alerte attention bloc">{{ notes.enAttente() }} saisie(s) de notes en attente d'envoi.</a>
      }
      @if (notes.refusees() > 0) {
        <a routerLink="/envois" class="alerte erreur bloc">{{ notes.refusees() }} saisie(s) refusée(s) : à vérifier.</a>
      }

      @if (chargement()) {
        <p class="doux">Chargement…</p>
      } @else if (groupes().length === 0) {
        <div class="carte">
          @if (session.horsConnexion()) {
            <p>Aucune classe sur ce téléphone. Ouvrez l'application une fois avec du réseau.</p>
          } @else {
            <p>Vous n'avez aucune classe cette année.</p>
          }
        </div>
      } @else {
        @for (g of groupes(); track g.classeId) {
          <section class="carte">
            <h2>{{ g.classeCode }}</h2>
            <ul class="liste">
              @for (a of g.affectations; track a.matiereId) {
                <li>
                  <a class="ligne-lien" [routerLink]="['/notes', a.classeId, a.matiereId]">
                    <span>{{ a.matiereLibelle }}</span>
                    <span aria-hidden="true">›</span>
                  </a>
                </li>
              }
            </ul>
          </section>
        }
      }
    </div>
  `,
  styles: `
    .bloc {
      display: block;
      text-decoration: none;
    }
    h2 {
      margin-bottom: 0.25rem;
    }
  `,
})
export class NotesChoixPage implements OnInit {
  protected readonly session = inject(SessionService);
  protected readonly notes = inject(NotesService);
  private readonly listes = inject(ListesService);

  private readonly affectations = signal<Affectation[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  protected readonly groupes = computed(() => {
    const groupes = new Map<string, { classeId: string; classeCode: string; affectations: Affectation[] }>();
    for (const a of this.affectations()) {
      const g = groupes.get(a.classeId) ?? { classeId: a.classeId, classeCode: a.classeCode, affectations: [] };
      g.affectations.push(a);
      groupes.set(a.classeId, g);
    }
    return [...groupes.values()];
  });

  async ngOnInit(): Promise<void> {
    try {
      this.affectations.set((await this.listes.affectations())?.affectations ?? []);
      await this.notes.recharger();
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }
}
