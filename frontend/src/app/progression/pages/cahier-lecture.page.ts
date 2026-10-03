import { Component, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte } from '../../core/outils';
import { heures, SeanceVue } from '../modeles-progression';
import { ProgressionApi } from '../progression-api.service';

/** Cahier de textes d'une matière, en lecture, pour le censeur ou le chef des travaux. */
@Component({
  selector: 'app-cahier-lecture',
  imports: [RouterLink],
  template: `
    <div class="page">
      <a [routerLink]="['/progression', classeId(), matiereId()]" class="retour">‹ Progression</a>
      <h1>Cahier de textes</h1>
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (seances(); as liste) {
        <p class="doux">{{ liste.length }} séance(s) · {{ heures(total()) }}</p>
        <ul class="liste carte">
          @for (s of liste; track s.id) {
            <li class="seance">
              <strong>{{ dateCourte(s.date) }} · {{ s.heureDebut.slice(0, 5) }}–{{ s.heureFin.slice(0, 5) }}</strong>
              <span class="doux"> · {{ heures(s.heures) }}</span>
              <p class="doux">{{ s.sequenceTitre ? s.sequenceOrdre + '. ' + s.sequenceTitre : 'Hors séquence' }}</p>
              <p class="contenu">{{ s.contenu }}</p>
              @if (s.travailAFaire) { <p><strong>À faire :</strong> {{ s.travailAFaire }}</p> }
            </li>
          } @empty {
            <li class="doux">Aucune séance notée.</li>
          }
        </ul>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .retour {
      display: inline-block;
      margin-bottom: 0.5rem;
      color: var(--primaire);
      text-decoration: none;
      font-weight: 600;
    }
    .seance {
      padding: 0.75rem 0;
      p {
        margin: 0.25rem 0;
      }
      .contenu {
        white-space: pre-line;
      }
    }
  `,
})
export class CahierLecturePage implements OnInit {
  private readonly api = inject(ProgressionApi);

  readonly classeId = input.required<string>();
  readonly matiereId = input.required<string>();

  protected readonly heures = heures;
  protected readonly dateCourte = dateCourte;
  protected readonly action = new Action();
  protected readonly seances = signal<SeanceVue[] | null>(null);
  protected readonly total = signal(0);

  async ngOnInit(): Promise<void> {
    const l = await this.action.executer(() => this.api.seances(this.classeId(), this.matiereId()));
    if (l) {
      this.seances.set(l);
      this.total.set(Math.round(l.reduce((t, s) => t + s.heures, 0) * 10) / 10);
    }
  }
}
