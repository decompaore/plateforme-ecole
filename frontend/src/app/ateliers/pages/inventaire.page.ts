import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateHeureCourte } from '../../core/outils';
import { AteliersNavComponent } from '../ateliers-nav.component';
import { AteliersApi } from '../ateliers-api.service';
import { CLASSE_ETAT, EtatEquipement, InventaireVue, LIBELLE_ETAT, lireQuantite, quantite } from '../modeles-ateliers';

interface SaisieLigne {
  quantite: string;
}

interface SaisieEquipement {
  etat: EtatEquipement | '';
  observation: string;
}

/**
 * Saisie d'un inventaire : quantité constatée de chaque matière d'œuvre, état constaté de chaque
 * équipement. La saisie s'enregistre en plusieurs fois ; la clôture corrige le stock et l'état des
 * équipements une fois toutes les lignes renseignées.
 */
@Component({
  selector: 'app-inventaire',
  imports: [FormsModule, RouterLink, AteliersNavComponent],
  template: `
    <div class="page large">
      <h1>Inventaire</h1>
      <app-ateliers-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (inventaire(); as inv) {
        <section class="carte">
          <p>
            <strong>{{ inv.libelle }}</strong> ·
            <a [routerLink]="['/ateliers', inv.atelierId]">{{ inv.atelierCode }} · {{ inv.atelierNom }}</a>
            <span class="pastille entete {{ inv.statut === 'CLOS' ? 'present' : '' }}">{{ inv.statut === 'CLOS' ? 'Clos' : 'En cours' }}</span>
          </p>
          <p class="doux">
            Ouvert le {{ dateHeureCourte(inv.ouvertLe) }}@if (inv.ouvertPar) { par {{ inv.ouvertPar }} }
            @if (inv.closLe) { · clos le {{ dateHeureCourte(inv.closLe) }}@if (inv.closPar) { par {{ inv.closPar }} } }
          </p>
          @if (inv.modifiable) {
            <p class="doux">
              Comptez ce qui est réellement dans l'atelier. Enregistrez en plusieurs fois si besoin ;
              {{ restantes() }} ligne(s) restent à renseigner.
            </p>
          }
        </section>

        @if (message()) {
          <div class="alerte succes" role="status">{{ message() }}</div>
        }
        @if (enregistrement.erreur()) {
          <div class="alerte erreur" role="alert">{{ enregistrement.erreur() }}</div>
        }

        @if (inv.matieres.length) {
          <section class="carte">
            <div class="entete-section">
              <h2>Matière d’œuvre</h2>
              @if (inv.modifiable) {
                <button type="button" class="bouton discret petit" (click)="reprendreTheorique()">Reprendre les quantités théoriques</button>
              }
            </div>
            <div class="tableau-defilant">
              <table>
                <thead><tr><th>Matière d’œuvre</th><th class="nombre">Théorique</th><th class="nombre">Constaté</th><th class="nombre">Écart</th></tr></thead>
                <tbody>
                  @for (l of inv.matieres; track l.articleId) {
                    <tr>
                      <td>{{ l.designation }} <span class="doux">{{ l.unite }}</span></td>
                      <td class="nombre">{{ quantite(l.quantiteTheorique) }}</td>
                      <td class="nombre">
                        @if (inv.modifiable) {
                          <input class="qte" inputmode="decimal" [attr.aria-label]="'Quantité constatée : ' + l.designation"
                            [class.invalide]="invalide(l.articleId)"
                            [ngModel]="matieres()[l.articleId]?.quantite ?? ''" (ngModelChange)="saisirQuantite(l.articleId, $event)" />
                        } @else {
                          {{ quantite(l.quantiteConstatee) }}
                        }
                      </td>
                      <td class="nombre" [class.rouge]="(ecart(l.articleId, l.quantiteTheorique) ?? 0) < 0">
                        {{ ecartTexte(l.articleId, l.quantiteTheorique) }}
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }

        @if (inv.equipements.length) {
          <section class="carte">
            <div class="entete-section">
              <h2>Équipements</h2>
              @if (inv.modifiable) {
                <button type="button" class="bouton discret petit" (click)="toutPresent()">Tous conformes à l'état enregistré</button>
              }
            </div>
            <ul class="liste">
              @for (l of inv.equipements; track l.equipementId) {
                <li class="ligne-eq">
                  <span>
                    <strong>{{ l.designation }}</strong><span class="doux"> · {{ l.numeroInventaire }} · enregistré : {{ libelleEtat[l.etatTheorique].toLowerCase() }}</span>
                  </span>
                  @if (inv.modifiable) {
                    <span class="saisie-eq">
                      <select [attr.aria-label]="'État constaté : ' + l.designation + ' ' + l.numeroInventaire"
                        [ngModel]="equipements()[l.equipementId]?.etat ?? ''" (ngModelChange)="saisirEtat(l.equipementId, $event)">
                        <option value="">—</option>
                        @for (e of etats; track e) { <option [value]="e">{{ libelleEtat[e] }}</option> }
                      </select>
                      <input placeholder="Observation" [attr.aria-label]="'Observation : ' + l.designation"
                        [ngModel]="equipements()[l.equipementId]?.observation ?? ''" (ngModelChange)="saisirObservation(l.equipementId, $event)" />
                    </span>
                  } @else if (l.etatConstate) {
                    <span>
                      <span class="pastille {{ classeEtat[l.etatConstate] }}">{{ libelleEtat[l.etatConstate] }}</span>
                      @if (l.observation) { <span class="doux"> {{ l.observation }}</span> }
                    </span>
                  }
                </li>
              }
            </ul>
          </section>
        }

        <section class="carte">
          <div class="champ">
            <label for="obs">Observations</label>
            @if (inv.modifiable) {
              <textarea id="obs" rows="2" [ngModel]="observations()" (ngModelChange)="observations.set($event)"></textarea>
            } @else {
              <p id="obs">{{ inv.observations ?? '—' }}</p>
            }
          </div>
          @if (inv.modifiable) {
            <div class="actions-ligne">
              <button type="button" class="bouton secondaire" [disabled]="enregistrement.enCours() || !!erreurSaisie()" (click)="enregistrer()">Enregistrer la saisie</button>
              <button type="button" class="bouton" [disabled]="enregistrement.enCours() || restantes() > 0 || !!erreurSaisie()" (click)="clore()">Clore l'inventaire</button>
            </div>
            @if (erreurSaisie(); as e) { <p class="rouge" role="alert">{{ e }}</p> }
            @if (restantes() > 0) {
              <p class="doux">La clôture sera possible quand toutes les lignes seront renseignées. Elle corrigera le stock et l'état des équipements.</p>
            }
          }
        </section>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    h2 {
      font-size: 1.05rem;
      margin: 0 0 0.5rem;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      th,
      td {
        padding: 0.45rem 0.5rem;
        border-bottom: 1px solid var(--bordure);
        text-align: left;
      }
      .nombre {
        text-align: right;
        font-variant-numeric: tabular-nums;
      }
    }
    .entete {
      margin-left: 0.4rem;
    }
    .qte {
      width: 5rem;
      text-align: right;
    }
    .invalide {
      border-color: var(--absent);
    }
    .rouge {
      color: var(--absent);
    }
    .ligne-eq {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 0.5rem;
      padding: 0.5rem 0;
      border-bottom: 1px solid var(--bordure);
    }
    .saisie-eq {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
    }
  `,
})
export class InventairePage {
  private readonly api = inject(AteliersApi);

  readonly inventaireId = input.required<string>();

  protected readonly quantite = quantite;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly libelleEtat = LIBELLE_ETAT;
  protected readonly classeEtat = CLASSE_ETAT;
  protected readonly etats: EtatEquipement[] = ['BON', 'EN_PANNE', 'MANQUANT', 'REFORME'];
  protected readonly action = new Action();
  protected readonly enregistrement = new Action();
  protected readonly message = signal<string | null>(null);

  protected readonly inventaire = signal<InventaireVue | null>(null);
  protected readonly matieres = signal<Record<string, SaisieLigne>>({});
  protected readonly equipements = signal<Record<string, SaisieEquipement>>({});
  protected readonly observations = signal('');

  protected readonly restantes = computed(() => {
    const inv = this.inventaire();
    if (!inv) {
      return 0;
    }
    const m = this.matieres();
    const e = this.equipements();
    return inv.matieres.filter((l) => !(m[l.articleId]?.quantite ?? '').trim()).length
      + inv.equipements.filter((l) => !e[l.equipementId]?.etat).length;
  });

  protected readonly erreurSaisie = computed(() => {
    const m = this.matieres();
    const fausse = Object.values(m).some((s) => s.quantite.trim() && lireQuantite(s.quantite, true) === null);
    return fausse ? 'Quantités : des nombres positifs, deux décimales au plus (virgule acceptée).' : null;
  });

  constructor() {
    effect(() => {
      const id = this.inventaireId();
      untracked(() => void this.charger(id));
    });
  }

  protected invalide(articleId: string): boolean {
    const t = this.matieres()[articleId]?.quantite ?? '';
    return !!t.trim() && lireQuantite(t, true) === null;
  }

  protected ecart(articleId: string, theorique: number): number | null {
    const t = this.matieres()[articleId]?.quantite ?? '';
    const q = t.trim() ? lireQuantite(t, true) : null;
    return q === null ? null : Math.round((q - theorique) * 100) / 100;
  }

  protected ecartTexte(articleId: string, theorique: number): string {
    const e = this.ecart(articleId, theorique);
    return e === null ? '' : e === 0 ? '=' : `${e > 0 ? '+' : ''}${quantite(e)}`;
  }

  protected saisirQuantite(articleId: string, valeur: string): void {
    this.matieres.set({ ...this.matieres(), [articleId]: { quantite: valeur } });
    this.message.set(null);
  }

  protected saisirEtat(equipementId: string, etat: EtatEquipement | ''): void {
    const actuel = this.equipements()[equipementId] ?? { etat: '', observation: '' };
    this.equipements.set({ ...this.equipements(), [equipementId]: { ...actuel, etat } });
    this.message.set(null);
  }

  protected saisirObservation(equipementId: string, observation: string): void {
    const actuel = this.equipements()[equipementId] ?? { etat: '', observation: '' };
    this.equipements.set({ ...this.equipements(), [equipementId]: { ...actuel, observation } });
  }

  protected reprendreTheorique(): void {
    const inv = this.inventaire();
    if (!inv) {
      return;
    }
    const m = { ...this.matieres() };
    for (const l of inv.matieres) {
      if (!(m[l.articleId]?.quantite ?? '').trim()) {
        m[l.articleId] = { quantite: String(l.quantiteTheorique).replace('.', ',') };
      }
    }
    this.matieres.set(m);
  }

  protected toutPresent(): void {
    const inv = this.inventaire();
    if (!inv) {
      return;
    }
    const e = { ...this.equipements() };
    for (const l of inv.equipements) {
      if (!e[l.equipementId]?.etat) {
        e[l.equipementId] = { etat: l.etatTheorique, observation: e[l.equipementId]?.observation ?? '' };
      }
    }
    this.equipements.set(e);
  }

  protected async enregistrer(): Promise<boolean> {
    const inv = this.inventaire();
    if (!inv || this.erreurSaisie()) {
      return false;
    }
    const m = this.matieres();
    const e = this.equipements();
    const r = await this.enregistrement.executer(() =>
      this.api.saisirInventaire(inv.id, {
        matieres: inv.matieres.map((l) => {
          const t = m[l.articleId]?.quantite ?? '';
          return { articleId: l.articleId, quantiteConstatee: t.trim() ? lireQuantite(t, true) : null };
        }),
        equipements: inv.equipements.map((l) => ({
          equipementId: l.equipementId,
          etatConstate: e[l.equipementId]?.etat || null,
          observation: e[l.equipementId]?.observation?.trim() || null,
        })),
        observations: this.observations().trim(),
      }),
    );
    if (r) {
      this.appliquer(r);
      this.message.set('Saisie enregistrée.');
      return true;
    }
    return false;
  }

  protected async clore(): Promise<void> {
    const inv = this.inventaire();
    if (!inv || !(await this.enregistrer())) {
      return;
    }
    const r = await this.enregistrement.executer(() => this.api.cloreInventaire(inv.id));
    if (r) {
      this.appliquer(r);
      this.message.set('Inventaire clos : le stock et l’état des équipements sont corrigés.');
    }
  }

  private appliquer(inv: InventaireVue): void {
    this.inventaire.set(inv);
    this.matieres.set(Object.fromEntries(inv.matieres.map((l) => [
      l.articleId,
      { quantite: l.quantiteConstatee === null ? '' : String(l.quantiteConstatee).replace('.', ',') },
    ])));
    this.equipements.set(Object.fromEntries(inv.equipements.map((l) => [
      l.equipementId,
      { etat: l.etatConstate ?? '', observation: l.observation ?? '' },
    ])));
    this.observations.set(inv.observations ?? '');
  }

  private async charger(id: string): Promise<void> {
    const inv = await this.action.executer(() => this.api.inventaire(id));
    if (inv && id === this.inventaireId()) {
      this.appliquer(inv);
    }
  }
}
