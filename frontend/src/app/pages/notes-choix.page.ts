import { HttpClient } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { messageErreur } from '../core/erreurs';
import { Affectation } from '../core/modeles';
import { API, SessionService } from '../core/session.service';
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
                    <span>
                      {{ a.matiereLibelle }}
                      @if (resume(a); as r) { <br /><span class="doux resume">{{ r }}</span> }
                    </span>
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
    .resume {
      font-size: 0.85rem;
    }
  `,
})
export class NotesChoixPage implements OnInit {
  protected readonly session = inject(SessionService);
  protected readonly notes = inject(NotesService);
  private readonly listes = inject(ListesService);

  private readonly http = inject(HttpClient);
  private readonly affectations = signal<Affectation[]>([]);
  /** Évaluations de l'année par classe et matière (en ligne seulement ; simple information). */
  private readonly suivi = signal<Map<string, { evaluations: number; parType: Record<string, number> }>>(new Map());
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
      const fiche = await this.listes.affectations();
      this.affectations.set(fiche?.affectations ?? []);
      await this.notes.recharger();
      if (fiche && !this.session.horsConnexion()) {
        void this.chargerSuivi(fiche.anneeId);
      }
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  private async chargerSuivi(anneeId: string): Promise<void> {
    try {
      const lignes = await firstValueFrom(
        this.http.get<{ classeId: string; matiereId: string; evaluations: number; parType: Record<string, number> }[]>(
          `${API}/annees/${anneeId}/suivi-evaluations`,
        ),
      );
      this.suivi.set(new Map(lignes.map((l) => [`${l.classeId}/${l.matiereId}`, l])));
    } catch {
      // Simple information : sans réseau ou en cas d'erreur, la page reste utilisable
    }
  }

  /** « 3 évaluations cette année : 2 devoirs, 1 interrogation ». */
  protected resume(a: Affectation): string | null {
    const s = this.suivi().get(`${a.classeId}/${a.matiereId}`);
    if (!s) {
      return null;
    }
    if (s.evaluations === 0) {
      return 'Aucune évaluation cette année';
    }
    const detail = Object.entries(s.parType)
      .filter(([, n]) => n > 0)
      .map(([type, n]) => `${n} ${(LIBELLES_PLURIEL[type] ?? ['évaluation', 'évaluations'])[n > 1 ? 1 : 0]}`)
      .join(', ');
    return `${s.evaluations} évaluation${s.evaluations > 1 ? 's' : ''} cette année : ${detail}`;
  }
}

const LIBELLES_PLURIEL: Record<string, [string, string]> = {
  DEVOIR: ['devoir', 'devoirs'],
  INTERROGATION: ['interrogation', 'interrogations'],
  COMPOSITION: ['composition', 'compositions'],
  TP: ['TP', 'TP'],
  ATELIER: ['atelier', 'ateliers'],
  AUTRE: ['autre', 'autres'],
};
