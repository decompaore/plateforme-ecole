import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { dateCourte } from '../../core/outils';
import { filtrer } from '../../core/recherche';
import { RechercheComponent } from '../../partage/recherche.component';
import { Domaine, heures, LIBELLE_DOMAINE, SuiviProgressionVue } from '../modeles-progression';
import { ProgressionApi } from '../progression-api.service';
import { pastille } from './mes-progressions.page';

type Filtre = 'toutes' | 'a-viser' | 'non-commencees';

/**
 * Suivi des progressions par la direction : chaque matière de son domaine (général pour le
 * censeur, technique et pratique pour le chef des travaux), fiche commencée ou non, à viser.
 */
@Component({
  selector: 'app-suivi-progressions',
  imports: [RouterLink, RechercheComponent],
  template: `
    <div class="page large">
      <h1>Progressions</h1>
      @if (domaines().length) {
        <p class="doux">{{ domaines().join(' · ') }}</p>
      }

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (charge()) {
        <div class="chiffres">
          <div [class.alerte-valeur]="bilan().aViser > 0"><strong>{{ bilan().aViser }}</strong><span>à viser</span></div>
          <div><strong>{{ bilan().visees }}</strong><span>visées</span></div>
          <div><strong>{{ bilan().enCours }}</strong><span>en préparation ou à revoir</span></div>
          <div [class.alerte-valeur]="bilan().nonCommencees > 0"><strong>{{ bilan().nonCommencees }}</strong><span>non commencées</span></div>
        </div>

        <div class="bascule" role="group" aria-label="Fiches affichées">
          <button type="button" [class.actif]="filtreStatut() === 'toutes'" (click)="filtreStatut.set('toutes')">Toutes</button>
          <button type="button" [class.actif]="filtreStatut() === 'a-viser'" (click)="filtreStatut.set('a-viser')">À viser ({{ bilan().aViser }})</button>
          <button type="button" [class.actif]="filtreStatut() === 'non-commencees'" (click)="filtreStatut.set('non-commencees')">Non commencées</button>
        </div>

        <app-recherche libelle="Classe, matière ou enseignant" [(valeur)]="filtre" [total]="lignes().length" [trouves]="affichees().length" />

        @for (g of groupes(); track g.classe) {
          <section class="carte">
            <h2>{{ g.classe }}</h2>
            <ul class="liste">
              @for (l of g.lignes; track l.matiereId) {
                <li>
                  <a class="ligne-lien" [routerLink]="['/progression', l.classeId, l.matiereId]">
                    <span>
                      <strong>{{ l.matiereLibelle }}</strong> · {{ l.enseignant ?? 'sans enseignant' }}
                      <br />
                      <span class="doux">
                        @if (l.sequences) { {{ l.sequences }} séquence(s) · {{ heures(l.avancement.heuresRealisees) }} faites / {{ heures(l.heuresPrevues) }} prévues } @else { Aucune séquence }
                        @if (l.avancement.derniereSeance) { · cahier tenu jusqu'au {{ dateCourte(l.avancement.derniereSeance) }} } @else { · cahier vide }
                      </span>
                    </span>
                    <span class="pastille {{ pastille(l.statut).classe }}">{{ pastille(l.statut).texte }}</span>
                  </a>
                </li>
              }
            </ul>
          </section>
        } @empty {
          <div class="carte"><p class="doux">{{ lignes().length ? 'Aucune fiche ne correspond.' : 'Aucune matière à suivre pour cette année.' }}</p></div>
        }
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .chiffres {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
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
    .bascule {
      display: inline-flex;
      flex-wrap: wrap;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      padding: 0.2rem;
      margin-bottom: 0.75rem;
      background: var(--surface);
      button {
        min-height: 38px;
        padding: 0 0.8rem;
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
    h2 {
      font-size: 1.05rem;
      margin: 0 0 0.25rem;
    }
  `,
})
export class SuiviProgressionsPage {
  private readonly api = inject(ProgressionApi);
  private readonly annee = inject(AnneeCourante);

  protected readonly heures = heures;
  protected readonly dateCourte = dateCourte;
  protected readonly pastille = pastille;
  protected readonly action = new Action();
  protected readonly lignes = signal<SuiviProgressionVue[]>([]);
  protected readonly charge = signal(false);
  protected readonly filtre = signal('');
  protected readonly filtreStatut = signal<Filtre>('toutes');

  protected readonly domaines = computed(() =>
    [...new Set(this.lignes().map((l) => l.domaine))].sort().map((d: Domaine) => LIBELLE_DOMAINE[d]),
  );

  protected readonly bilan = computed(() => {
    const l = this.lignes();
    return {
      aViser: l.filter((x) => x.statut === 'SOUMISE').length,
      visees: l.filter((x) => x.statut === 'VISEE').length,
      enCours: l.filter((x) => x.statut === 'BROUILLON' || x.statut === 'A_REVOIR').length,
      nonCommencees: l.filter((x) => !x.statut).length,
    };
  });

  protected readonly affichees = computed(() => {
    const f = this.filtreStatut();
    const base = this.lignes().filter((l) => (f === 'a-viser' ? l.statut === 'SOUMISE' : f === 'non-commencees' ? !l.statut : true));
    return filtrer(base, this.filtre(), (l) => [l.classeCode, l.matiereLibelle, l.matiereCode, l.enseignant]);
  });

  protected readonly groupes = computed(() => {
    const map = new Map<string, SuiviProgressionVue[]>();
    for (const l of this.affichees()) {
      map.set(l.classeCode, [...(map.get(l.classeCode) ?? []), l]);
    }
    return [...map.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([classe, lignes]) => ({ classe, lignes }));
  });

  constructor() {
    effect(() => {
      const a = this.annee.annee();
      untracked(() => {
        if (a) {
          void this.charger(a.id);
        }
      });
    });
    void this.annee.charger().catch(() => undefined);
  }

  private async charger(anneeId: string): Promise<void> {
    const l = await this.action.executer(() => this.api.suivi(anneeId));
    if (l && anneeId === this.annee.annee()?.id) {
      this.lignes.set(l);
      this.charge.set(true);
    }
  }
}
