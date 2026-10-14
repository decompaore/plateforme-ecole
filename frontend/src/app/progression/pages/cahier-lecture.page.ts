import { Component, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte } from '../../core/outils';
import { heures, libelleMois, moisCourant, SeanceVue, VisaCahierVue } from '../modeles-progression';
import { ProgressionApi } from '../progression-api.service';

/**
 * Cahier de textes d'une matière, en lecture, pour le censeur ou le chef des travaux, qui le visent chaque mois
 * (v0.47) : un mois visé est figé pour l'enseignant.
 */
@Component({
  selector: 'app-cahier-lecture',
  imports: [RouterLink, FormsModule],
  template: `
    <div class="page">
      <a [routerLink]="['/progression', classeId(), matiereId()]" class="retour">‹ Progression</a>
      <h1>Cahier de textes</h1>
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }
      <section class="carte">
        <h2>Visa mensuel</h2>
        <form class="actions-ligne visa" (ngSubmit)="viser()">
          <label for="mois">Mois</label>
          <input id="mois" name="mois" type="month" [max]="moisMax" [(ngModel)]="mois" required style="width:auto" />
          <input id="observation" name="observation" maxlength="500" placeholder="Observation (facultatif)" [(ngModel)]="observation" />
          <button type="submit" class="bouton petit" [disabled]="action.enCours() || !mois()">Viser {{ mois() ? libelleMois(mois()) : '' }}</button>
        </form>
        <ul class="liste">
          @for (v of visas(); track v.id) {
            <li>
              <span class="pastille present">visé</span> <strong>{{ libelleMois(v.mois) }}</strong> · {{ v.seances }} séance(s)
              @if (v.visePar) { · {{ v.visePar }} }
              @if (v.observation) { <br /><span class="doux">{{ v.observation }}</span> }
            </li>
          } @empty {
            <li class="doux">Aucun mois visé.</li>
          }
        </ul>
      </section>
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
    .visa {
      margin-bottom: 0.5rem;
      input[name='observation'] {
        flex: 1;
        min-width: 12rem;
      }
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
  protected readonly libelleMois = libelleMois;
  protected readonly seances = signal<SeanceVue[] | null>(null);
  protected readonly total = signal(0);
  protected readonly visas = signal<VisaCahierVue[]>([]);
  protected readonly moisMax = moisCourant();
  protected readonly mois = signal(moisCourant());
  protected readonly observation = signal('');
  protected readonly message = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    const r = await this.action.executer(() =>
      Promise.all([this.api.seances(this.classeId(), this.matiereId()), this.api.visasCahier(this.classeId(), this.matiereId())]),
    );
    if (r) {
      const [l, v] = r;
      this.seances.set(l);
      this.total.set(Math.round(l.reduce((t, s) => t + s.heures, 0) * 10) / 10);
      this.visas.set(v);
    }
  }

  protected async viser(): Promise<void> {
    this.message.set(null);
    const v = await this.action.executer(() =>
      this.api.viserCahier(this.classeId(), this.matiereId(), this.mois(), this.observation().trim() || null),
    );
    if (v) {
      this.visas.set([v, ...this.visas()].sort((a, b) => b.mois.localeCompare(a.mois)));
      this.observation.set('');
      this.message.set(`Cahier visé pour ${libelleMois(v.mois)} (${v.seances} séance(s)) : ce mois ne peut plus être modifié par l'enseignant.`);
    }
  }
}
