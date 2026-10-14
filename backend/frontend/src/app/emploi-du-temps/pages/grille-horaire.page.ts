import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { EmploiApi } from '../emploi-api.service';
import { EmploiNavComponent } from '../emploi-nav.component';
import { creneauSuivant, CreneauVue, erreurGrille, grilleCourante, heure, JOURS, JOURS_COURTS, SaisieCreneau } from '../modeles-emploi';

/**
 * Grille horaire de l'année : les heures de cours et les jours où elles existent (mercredi et
 * samedi après-midi libres, par exemple). Fixée par le censeur ; un créneau qui porte déjà des
 * séances peut changer d'heure mais pas disparaître.
 */
@Component({
  selector: 'app-grille-horaire',
  imports: [RouterLink, EmploiNavComponent],
  template: `
    <div class="page large">
      <h1>Grille horaire</h1>
      <app-emploi-nav />

      @if (annees.annees().length > 1) {
        <div class="champ annee">
          <label for="annee">Année scolaire</label>
          <select id="annee" [value]="annees.annee()?.id" (change)="choisirAnnee($event)">
            @for (a of annees.annees(); track a.id) { <option [value]="a.id" [selected]="a.id === annees.annee()?.id">{{ a.libelle }}</option> }
          </select>
        </div>
      }

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message()) {
        <div class="alerte succes" role="status">{{ message() }}</div>
      }

      @if (chargee()) {
        <section class="carte">
          <p class="doux intro">
            Une ligne par heure de cours. Cochez les jours où elle existe. Les récréations et la pause de midi sont
            les intervalles entre deux créneaux. Une séance pratique peut s'étendre sur plusieurs créneaux qui se
            suivent (récréation de 20 minutes au plus entre deux).
          </p>
          @if (!lignes().length) {
            <p>Aucun créneau pour {{ annees.annee()?.libelle }}.</p>
            <button type="button" class="bouton secondaire petit" (click)="modele()">Partir de la grille courante (7 h – 12 h, 15 h – 18 h)</button>
          }
          <div class="tableau-defilant">
            <table>
              @if (lignes().length) {
                <thead>
                  <tr>
                    <th>Début</th><th>Fin</th>
                    @for (j of semaine; track j) { <th class="jour" [title]="jours[j]">{{ joursCourts[j] }}</th> }
                    <th><span class="visuellement-cache">Retirer</span></th>
                  </tr>
                </thead>
              }
              <tbody>
                @for (c of lignes(); track $index; let i = $index) {
                  <tr>
                    <td><input type="time" class="heure" [attr.aria-label]="'Début du créneau ' + (i + 1)" [value]="c.heureDebut.slice(0, 5)" (change)="modifier(i, 'heureDebut', $event)" /></td>
                    <td><input type="time" class="heure" [attr.aria-label]="'Fin du créneau ' + (i + 1)" [value]="c.heureFin.slice(0, 5)" (change)="modifier(i, 'heureFin', $event)" /></td>
                    @for (j of semaine; track j) {
                      <td class="jour">
                        <input type="checkbox" [checked]="c.jours.includes(j)" (change)="basculer(i, j)"
                          [attr.aria-label]="jours[j] + ' à ' + (c.heureDebut ? heure(c.heureDebut) : '')" />
                      </td>
                    }
                    <td><button type="button" class="bouton discret petit" (click)="retirer(i)" [attr.aria-label]="'Retirer le créneau ' + (i + 1)">Retirer</button></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (erreur(); as e) { <p class="rouge" role="alert">{{ e }}</p> }
          <div class="actions-ligne">
            <button type="button" class="bouton secondaire petit" (click)="ajouter()" [disabled]="lignes().length >= 16">Ajouter un créneau</button>
            <button type="button" class="bouton petit" (click)="enregistrer()" [disabled]="!!erreur() || action.enCours() || !modifiee()">Enregistrer la grille</button>
            @if (modifiee()) { <button type="button" class="bouton discret petit" (click)="annuler()">Annuler les changements</button> }
          </div>
        </section>
        <p class="doux"><a routerLink="/emploi-du-temps">Aller aux emplois du temps</a></p>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .intro {
      margin-top: 0;
    }
    .annee {
      max-width: 14rem;
    }
    table {
      border-collapse: collapse;
    }
    th,
    td {
      padding: 0.3rem 0.35rem;
      text-align: left;
      border-bottom: 1px solid var(--bordure);
    }
    .jour {
      text-align: center;
    }
    .jour input {
      width: 1.3rem;
      min-height: 1.3rem;
      height: 1.3rem;
    }
    .heure {
      width: 7.5rem;
      padding: 0.5rem;
    }
    .rouge {
      color: var(--absent);
    }
  `,
})
export class GrilleHorairePage {
  private readonly api = inject(EmploiApi);
  protected readonly annees = inject(AnneeCourante);

  protected readonly heure = heure;
  protected readonly jours = JOURS;
  protected readonly joursCourts = JOURS_COURTS;
  protected readonly semaine = [1, 2, 3, 4, 5, 6, 7];

  protected readonly action = new Action();
  protected readonly chargee = signal(false);
  protected readonly message = signal<string | null>(null);
  private readonly enregistree = signal<CreneauVue[]>([]);
  protected readonly lignes = signal<SaisieCreneau[]>([]);

  protected readonly erreur = computed(() => erreurGrille(this.lignes()));
  protected readonly modifiee = computed(() => JSON.stringify(this.lignes()) !== JSON.stringify(this.versSaisies(this.enregistree())));

  constructor() {
    void this.annees.charger();
    effect(() => {
      const annee = this.annees.annee();
      if (annee) {
        untracked(() => void this.charger(annee.id));
      }
    });
  }

  protected choisirAnnee(e: Event): void {
    this.annees.choisir((e.target as HTMLSelectElement).value);
  }

  protected modifier(i: number, champ: 'heureDebut' | 'heureFin', e: Event): void {
    const v = (e.target as HTMLInputElement).value;
    this.message.set(null);
    this.lignes.update((l) => l.map((c, k) => (k === i ? { ...c, [champ]: v } : c)));
  }

  protected basculer(i: number, jour: number): void {
    this.message.set(null);
    this.lignes.update((l) =>
      l.map((c, k) => (k === i ? { ...c, jours: c.jours.includes(jour) ? c.jours.filter((j) => j !== jour) : [...c.jours, jour].sort() } : c)),
    );
  }

  protected ajouter(): void {
    this.lignes.update((l) => [...l, creneauSuivant(l)]);
  }

  protected retirer(i: number): void {
    this.lignes.update((l) => l.filter((_, k) => k !== i));
  }

  protected modele(): void {
    this.lignes.set(grilleCourante());
  }

  protected annuler(): void {
    this.lignes.set(this.versSaisies(this.enregistree()));
  }

  protected async enregistrer(): Promise<void> {
    const annee = this.annees.annee();
    if (!annee || this.erreur()) {
      return;
    }
    const triees = [...this.lignes()].sort((a, b) => a.heureDebut.localeCompare(b.heureDebut));
    const r = await this.action.executer(() => this.api.definirGrille(annee.id, triees));
    if (r) {
      this.enregistree.set(r);
      this.lignes.set(this.versSaisies(r));
      this.message.set(`Grille enregistrée : ${r.length} créneau(x).`);
    }
  }

  private versSaisies(c: CreneauVue[]): SaisieCreneau[] {
    return c.map((x) => ({ id: x.id, heureDebut: x.heureDebut.slice(0, 5), heureFin: x.heureFin.slice(0, 5), jours: [...x.jours] }));
  }

  private async charger(anneeId: string): Promise<void> {
    const r = await this.action.executer(() => this.api.creneaux(anneeId));
    if (r) {
      this.enregistree.set(r);
      this.lignes.set(this.versSaisies(r));
      this.chargee.set(true);
    }
  }
}
