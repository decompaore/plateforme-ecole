import { Component, computed, effect, inject, signal, untracked } from '@angular/core';

import { dateCourte } from '../../core/outils';
import { filtrer } from '../../core/recherche';
import { RechercheComponent } from '../../partage/recherche.component';
import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { AdminNavComponent } from '../admin-nav.component';
import { AnneeCourante } from '../annee-courante.service';
import { PeriodeVue, SuiviEvaluationVue, TypeEvaluation } from '../modeles-admin';

type Regroupement = 'enseignant' | 'classe';

interface Groupe {
  titre: string;
  sousTitre: string;
  lignes: SuiviEvaluationVue[];
  evaluations: number;
  sansEvaluation: number;
  taux: number | null;
}

/** Colonnes du tableau : types regroupés comme on en parle en salle des professeurs. */
const COLONNES: { titre: string; types: TypeEvaluation[] }[] = [
  { titre: 'Devoirs', types: ['DEVOIR'] },
  { titre: 'Interros', types: ['INTERROGATION'] },
  { titre: 'Compos', types: ['COMPOSITION'] },
  { titre: 'TP / ateliers', types: ['TP', 'ATELIER'] },
  { titre: 'Autres', types: ['AUTRE'] },
];

/** Taux de saisie : notes saisies / notes attendues (null si aucune évaluation). */
export function taux(saisies: number, attendues: number): number | null {
  return attendues > 0 ? Math.round((saisies / attendues) * 100) : null;
}

/**
 * Suivi des évaluations pour la direction : par enseignant ou par classe, le nombre et le
 * type d'évaluations faites dans chaque matière, la dernière, et l'avancement de la saisie
 * des notes. Les matières sans évaluation ressortent avant le conseil de classe.
 */
@Component({
  selector: 'app-suivi-evaluations',
  imports: [AdminNavComponent, RechercheComponent],
  template: `
    <div class="page large">
      <h1>Suivi des évaluations</h1>
      <app-admin-nav />

      <div class="filtres">
        <div class="champ">
          <label for="periode">Période</label>
          <select id="periode" [value]="ordre()" (change)="ordre.set(+$any($event.target).value)">
            <option value="0">Toute l'année</option>
            @for (p of choixPeriodes(); track p.ordre) {
              <option [value]="p.ordre">{{ p.libelle }}</option>
            }
          </select>
        </div>
        <div class="champ">
          <span class="etiquette" id="regroupement">Regrouper par</span>
          <div class="bascule" role="group" aria-labelledby="regroupement">
            <button type="button" [class.actif]="regroupement() === 'enseignant'" (click)="regroupement.set('enseignant')">Enseignant</button>
            <button type="button" [class.actif]="regroupement() === 'classe'" (click)="regroupement.set('classe')">Classe</button>
          </div>
        </div>
      </div>

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (charge()) {
        <div class="chiffres">
          <div><strong>{{ bilan().evaluations }}</strong><span>évaluations</span></div>
          <div [class.alerte-valeur]="bilan().sansEvaluation > 0"><strong>{{ bilan().sansEvaluation }}</strong><span>matières sans évaluation</span></div>
          <div><strong>{{ bilan().taux === null ? '—' : bilan().taux + ' %' }}</strong><span>des notes saisies</span></div>
        </div>

        <app-recherche
          libelle="Enseignant, classe ou matière"
          [(valeur)]="filtre"
          [total]="lignes().length"
          [trouves]="affichees().length"
        />

        @for (g of groupes(); track g.titre) {
          <section class="carte">
            <div class="entete-groupe">
              <h2>{{ g.titre }} <span class="doux">{{ g.sousTitre }}</span></h2>
              <span class="doux">
                {{ g.evaluations }} évaluation(s)@if (g.sansEvaluation > 0) { · <span class="rouge">{{ g.sansEvaluation }} matière(s) sans évaluation</span> }
              </span>
            </div>
            <div class="tableau-defilant">
              <table class="tableau">
                <thead>
                  <tr>
                    <th>{{ regroupement() === 'enseignant' ? 'Classe' : 'Matière' }}</th>
                    <th>{{ regroupement() === 'enseignant' ? 'Matière' : 'Enseignant' }}</th>
                    <th class="nombre">Total</th>
                    @for (c of colonnes; track c.titre) { <th class="nombre">{{ c.titre }}</th> }
                    <th>Dernière</th>
                    <th class="nombre">Notes saisies</th>
                  </tr>
                </thead>
                <tbody>
                  @for (l of g.lignes; track l.classeId + l.matiereId) {
                    <tr [class.vide]="l.evaluations === 0">
                      @if (regroupement() === 'enseignant') {
                        <td><strong>{{ l.classeCode }}</strong></td>
                        <td>{{ l.matiereLibelle }}</td>
                      } @else {
                        <td><strong>{{ l.matiereLibelle }}</strong></td>
                        <td>{{ l.enseignant ?? 'sans enseignant' }}</td>
                      }
                      <td class="nombre">
                        @if (l.evaluations === 0) { <span class="pastille absent">aucune</span> } @else { <strong>{{ l.evaluations }}</strong> }
                      </td>
                      @for (c of colonnes; track c.titre) {
                        <td class="nombre doux">{{ compte(l, c.types) || '' }}</td>
                      }
                      <td>{{ l.derniere ? dateCourte(l.derniere) : '' }}</td>
                      <td class="nombre">
                        @if (taux(l.notesSaisies, l.notesAttendues); as t) {
                          <span [class.rouge]="t < 90">{{ t }} %</span>
                        } @else if (l.evaluations > 0) {
                          <span class="rouge">0 %</span>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        } @empty {
          <div class="carte"><p class="doux">{{ lignes().length ? 'Rien ne correspond à la recherche.' : 'Aucune classe ni matière pour cette année.' }}</p></div>
        }
        <p class="doux">Notes saisies : notes entrées sur le nombre attendu (une par élève et par évaluation, absences comprises). En rouge sous 90 %.</p>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .filtres {
      display: flex;
      flex-wrap: wrap;
      gap: 0 1rem;
      .champ {
        flex: 0 1 16rem;
        min-width: 0;
      }
    }
    .etiquette {
      display: block;
      font-weight: 600;
      margin-bottom: 0.35rem;
    }
    .bascule {
      display: inline-flex;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      padding: 0.2rem;
      background: var(--surface);
      button {
        min-height: 40px;
        padding: 0 0.9rem;
        border: none;
        background: none;
        border-radius: calc(var(--rayon) - 4px);
        font: inherit;
        font-weight: 600;
        color: var(--texte-doux);
        cursor: pointer;
      }
      button.actif {
        background: var(--primaire);
        color: var(--sur-primaire);
      }
    }
    .chiffres {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.75rem;
      margin: 0.5rem 0 1rem;
      div {
        display: flex;
        flex-direction: column;
        padding: 0.75rem 1rem;
        border-radius: var(--rayon);
        background: var(--surface);
        border: 1px solid var(--bordure);
      }
      strong {
        font-size: 1.4rem;
        font-variant-numeric: tabular-nums;
      }
      span {
        font-size: 0.85rem;
        color: var(--texte-doux);
      }
      .alerte-valeur strong {
        color: var(--absent);
      }
    }
    .entete-groupe {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: baseline;
      gap: 0 1rem;
      h2 {
        font-size: 1.05rem;
        margin: 0 0 0.5rem;
      }
    }
    table {
      font-variant-numeric: tabular-nums;
    }
    tr.vide td {
      background: var(--absent-fond);
    }
    .rouge {
      color: var(--absent);
      font-weight: 600;
    }
  `,
})
export class SuiviEvaluationsPage {
  private readonly api = inject(AdminApi);
  protected readonly annee = inject(AnneeCourante);

  protected readonly colonnes = COLONNES;
  protected readonly dateCourte = dateCourte;
  protected readonly taux = taux;
  protected readonly action = new Action();

  /** 0 : toute l'année ; sinon le rang de la période (1er trimestre…). */
  protected readonly ordre = signal(0);
  protected readonly regroupement = signal<Regroupement>('enseignant');
  protected readonly filtre = signal('');
  protected readonly lignes = signal<SuiviEvaluationVue[]>([]);
  private readonly periodes = signal<PeriodeVue[]>([]);
  protected readonly charge = signal(false);

  /** Une entrée par rang de période (les profils ont chacun leurs trimestres ou semestres). */
  protected readonly choixPeriodes = computed(() => {
    const parOrdre = new Map<number, string[]>();
    for (const p of this.periodes()) {
      const l = parOrdre.get(p.ordre) ?? [];
      if (!l.includes(p.libelle)) {
        l.push(p.libelle);
      }
      parOrdre.set(p.ordre, l);
    }
    return [...parOrdre.entries()].sort(([a], [b]) => a - b).map(([ordre, libelles]) => ({ ordre, libelle: libelles.join(' / ') }));
  });

  protected readonly affichees = computed(() =>
    filtrer(this.lignes(), this.filtre(), (l) => [l.enseignant, l.classeCode, l.matiereLibelle, l.matiereCode]),
  );

  protected readonly groupes = computed<Groupe[]>(() => {
    const parEnseignant = this.regroupement() === 'enseignant';
    const map = new Map<string, SuiviEvaluationVue[]>();
    for (const l of this.affichees()) {
      const cle = parEnseignant ? (l.enseignant ?? '') : l.classeCode;
      map.set(cle, [...(map.get(cle) ?? []), l]);
    }
    return [...map.entries()]
      .sort(([a], [b]) => (a === '' ? 1 : b === '' ? -1 : a.localeCompare(b)))
      .map(([cle, lignes]) => {
        const saisies = lignes.reduce((t, l) => t + l.notesSaisies, 0);
        const attendues = lignes.reduce((t, l) => t + l.notesAttendues, 0);
        const classes = new Set(lignes.map((l) => l.classeCode)).size;
        return {
          titre: parEnseignant ? cle || 'Matières sans enseignant' : cle,
          sousTitre: parEnseignant ? `· ${classes} classe(s), ${lignes.length} matière(s)` : `· ${lignes.length} matière(s)`,
          lignes: [...lignes].sort((a, b) =>
            parEnseignant
              ? a.classeCode.localeCompare(b.classeCode) || a.matiereLibelle.localeCompare(b.matiereLibelle)
              : a.matiereLibelle.localeCompare(b.matiereLibelle),
          ),
          evaluations: lignes.reduce((t, l) => t + l.evaluations, 0),
          sansEvaluation: lignes.filter((l) => l.evaluations === 0).length,
          taux: taux(saisies, attendues),
        };
      });
  });

  protected readonly bilan = computed(() => {
    const l = this.lignes();
    return {
      evaluations: l.reduce((t, x) => t + x.evaluations, 0),
      sansEvaluation: l.filter((x) => x.evaluations === 0).length,
      taux: taux(l.reduce((t, x) => t + x.notesSaisies, 0), l.reduce((t, x) => t + x.notesAttendues, 0)),
    };
  });

  constructor() {
    effect(() => {
      const a = this.annee.annee();
      const ordre = this.ordre();
      untracked(() => {
        if (a) {
          void this.charger(a.id, ordre);
        }
      });
    });
    void this.annee.charger().catch(() => undefined);
  }

  private async charger(anneeId: string, ordre: number): Promise<void> {
    await this.action.executer(async () => {
      const [lignes, periodes] = await Promise.all([
        this.api.suiviEvaluations(anneeId, ordre || undefined),
        this.periodes().length && this.periodes()[0].anneeId === anneeId ? Promise.resolve(this.periodes()) : this.api.periodes(anneeId),
      ]);
      if (anneeId === this.annee.annee()?.id && ordre === this.ordre()) {
        this.lignes.set(lignes);
        this.periodes.set(periodes);
        this.charge.set(true);
      }
    });
  }

  protected compte(l: SuiviEvaluationVue, types: TypeEvaluation[]): number {
    return types.reduce((t, type) => t + (l.parType[type] ?? 0), 0);
  }
}
