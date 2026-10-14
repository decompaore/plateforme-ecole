import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { filtrer } from '../../core/recherche';
import { RechercheComponent } from '../../partage/recherche.component';
import { libelleMois, moisCourant, SuiviCahierVue } from '../modeles-progression';
import { ProgressionApi } from '../progression-api.service';

/**
 * Visa mensuel des cahiers de textes (v0.47) : chaque mois, le censeur (matières générales) et le chef des
 * travaux (matières techniques) visent le cahier de textes de chaque classe ; le tableau montre ce qui reste à viser.
 */
@Component({
  selector: 'app-cahiers-visas',
  imports: [FormsModule, RouterLink, RechercheComponent],
  template: `
    <div class="page large">
      <a routerLink="/progression/suivi" class="retour">‹ Progressions</a>
      <h1>Cahiers de textes</h1>
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      <div class="actions-ligne">
        <label for="mois">Mois</label>
        <input id="mois" type="month" [max]="moisMax" [ngModel]="mois()" (ngModelChange)="changerMois($event)" style="width:auto" />
      </div>
      @if (lignes(); as l) {
        <p class="doux">
          {{ libelleMois(mois()) }} : <strong>{{ bilan().visees }}</strong> cahier(s) visé(s) sur {{ l.length }} ·
          {{ bilan().aViser }} à viser · {{ bilan().vides }} sans séance notée
        </p>
        <app-recherche libelle="Classe, matière ou enseignant" [(valeur)]="filtre" [total]="l.length" [trouves]="affichees().length" />
        <div class="tableau-defilant">
          <table class="tableau">
            <thead><tr><th>Classe</th><th>Matière</th><th>Enseignant</th><th class="nombre">Séances</th><th>Visa</th></tr></thead>
            <tbody>
              @for (x of affichees(); track x.classeId + x.matiereId) {
                <tr>
                  <td>{{ x.classeCode }}</td>
                  <td><a [routerLink]="['/progression', x.classeId, x.matiereId, 'cahier']">{{ x.matiereLibelle }}</a></td>
                  <td>{{ x.enseignant ?? '—' }}</td>
                  <td class="nombre" [class.rouge]="x.seances === 0">{{ x.seances }}</td>
                  <td>
                    @if (x.vise) {
                      <span class="pastille present">visé</span> <span class="doux">{{ x.visePar }}</span>
                    } @else {
                      <span class="pastille">à viser</span>
                    }
                  </td>
                </tr>
              } @empty {
                <tr><td colspan="5" class="doux">Aucune matière à suivre.</td></tr>
              }
            </tbody>
          </table>
        </div>
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
    .rouge {
      color: var(--absent);
    }
  `,
})
export class CahiersVisasPage {
  private readonly api = inject(ProgressionApi);
  private readonly annee = inject(AnneeCourante);

  protected readonly action = new Action();
  protected readonly libelleMois = libelleMois;
  protected readonly moisMax = moisCourant();
  protected readonly mois = signal(moisCourant());
  protected readonly lignes = signal<SuiviCahierVue[] | null>(null);
  protected readonly filtre = signal('');

  protected readonly affichees = computed(() =>
    filtrer(this.lignes() ?? [], this.filtre(), (l) => [l.classeCode, l.matiereLibelle, l.enseignant]),
  );
  protected readonly bilan = computed(() => {
    const l = this.lignes() ?? [];
    return {
      visees: l.filter((x) => x.vise).length,
      aViser: l.filter((x) => !x.vise && x.seances > 0).length,
      vides: l.filter((x) => x.seances === 0).length,
    };
  });

  constructor() {
    effect(() => {
      const a = this.annee.annee();
      untracked(() => {
        if (a) {
          void this.charger();
        }
      });
    });
    void this.annee.charger().catch(() => undefined);
  }

  protected changerMois(m: string): void {
    if (m) {
      this.mois.set(m);
      void this.charger();
    }
  }

  private async charger(): Promise<void> {
    const a = this.annee.annee();
    if (!a) {
      return;
    }
    const l = await this.action.executer(() => this.api.suiviCahiers(a.id, this.mois()));
    if (l) {
      this.lignes.set(l);
    }
  }
}
