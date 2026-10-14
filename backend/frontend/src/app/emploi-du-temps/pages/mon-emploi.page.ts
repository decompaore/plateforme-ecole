import { Component, computed, inject, signal } from '@angular/core';

import { Action } from '../../admin/action';
import { ExportBoutonsComponent } from '../../ateliers/export-boutons.component';
import { dateHeureCourte } from '../../core/outils';
import { EmploiApi } from '../emploi-api.service';
import { EmploiNavComponent } from '../emploi-nav.component';
import { duree, JOURS, MonEmploiVue, plage } from '../modeles-emploi';

interface Ligne {
  debut: string;
  plage: string;
  titre: string;
  detail: string | null;
  ailleurs: boolean;
}

/** Une journée de l'enseignant : ses cours, et les heures prises dans un autre établissement. */
export function journees(e: MonEmploiVue): { jour: number; libelle: string; lignes: Ligne[] }[] {
  return e.jours
    .map((jour) => {
      const lignes: Ligne[] = e.seances
        .filter((s) => s.jour === jour)
        .map((s) => ({
          debut: s.heureDebut,
          plage: plage(s.heureDebut, s.heureFin),
          titre: `${s.classe}${s.groupe ? ' (' + s.groupe + ')' : ''} · ${s.matiere}`,
          detail: s.atelier ?? s.salle,
          ailleurs: false,
        }));
      for (const o of e.ailleurs.filter((x) => x.jour === jour)) {
        lignes.push({ debut: o.heureDebut, plage: plage(o.heureDebut, o.heureFin), titre: 'Cours dans un autre établissement', detail: null, ailleurs: true });
      }
      lignes.sort((a, b) => a.debut.localeCompare(b.debut));
      return { jour, libelle: JOURS[jour], lignes };
    })
    .filter((j) => j.lignes.length > 0);
}

/** Emploi du temps de l'enseignant connecté, une fois publié par le censeur. */
@Component({
  selector: 'app-mon-emploi',
  imports: [EmploiNavComponent, ExportBoutonsComponent],
  template: `
    <div class="page">
      <h1>Mon emploi du temps</h1>
      <app-emploi-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (emploi(); as e) {
        @if (!e.anneeId) {
          <div class="carte"><p>Aucune année scolaire en cours dans cet établissement.</p></div>
        } @else if (!e.publieLe) {
          <div class="carte"><p>L’emploi du temps {{ e.annee }} n’est pas encore publié. Il s’affichera ici dès que le censeur l’aura publié.</p></div>
        } @else {
          <p class="doux">
            {{ e.annee }} · publié le {{ dateHeureCourte(e.publieLe) }} · {{ duree(minutes()) }} de cours par semaine
          </p>
          <div class="barre-export">
            <app-export chemin="/espace-enseignant/emploi-du-temps" nom="mon-emploi-du-temps" libelle="mon emploi du temps" />
          </div>
          @for (j of jours(); track j.jour) {
            <section class="carte">
              <h2>{{ j.libelle }}</h2>
              <ul class="liste">
                @for (l of j.lignes; track $index) {
                  <li [class.ailleurs]="l.ailleurs">
                    <span class="heure">{{ l.plage }}</span>
                    <span><strong>{{ l.titre }}</strong>@if (l.detail) { <br /><span class="doux">{{ l.detail }}</span> }</span>
                  </li>
                }
              </ul>
            </section>
          } @empty {
            <div class="carte"><p>Aucun cours ne vous est attribué dans l’emploi du temps publié.</p></div>
          }
        }
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    h2 {
      font-size: 1.05rem;
      margin: 0 0 0.25rem;
    }
    li {
      display: grid;
      grid-template-columns: 7.5rem 1fr;
      gap: 0.5rem;
      align-items: start;
    }
    .heure {
      font-variant-numeric: tabular-nums;
      font-weight: 600;
    }
    .ailleurs {
      color: var(--texte-doux);
      font-style: italic;
    }
  `,
})
export class MonEmploiPage {
  private readonly api = inject(EmploiApi);
  protected readonly action = new Action();
  protected readonly emploi = signal<MonEmploiVue | null>(null);
  protected readonly duree = duree;
  protected readonly dateHeureCourte = dateHeureCourte;

  protected readonly jours = computed(() => {
    const e = this.emploi();
    return e ? journees(e) : [];
  });

  protected readonly minutes = computed(() =>
    (this.emploi()?.seances ?? []).reduce((t, s) => {
      const [h1, m1] = s.heureDebut.split(':').map(Number);
      const [h2, m2] = s.heureFin.split(':').map(Number);
      return t + h2 * 60 + m2 - (h1 * 60 + m1);
    }, 0),
  );

  constructor() {
    void this.charger();
  }

  private async charger(): Promise<void> {
    const r = await this.action.executer(() => this.api.monEmploi());
    if (r) {
      this.emploi.set(r);
    }
  }
}
