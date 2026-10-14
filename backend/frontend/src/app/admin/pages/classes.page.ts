import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { SessionService } from '../../core/session.service';
import { filtrer } from '../../core/recherche';
import { RechercheComponent } from '../../partage/recherche.component';
import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { AdminNavComponent } from '../admin-nav.component';
import { AnneeCourante } from '../annee-courante.service';
import { ClasseVue, FiliereVue } from '../modeles-admin';

/** Classes de l'année de travail, par niveau. */
@Component({
  selector: 'app-classes',
  imports: [FormsModule, RouterLink, AdminNavComponent, RechercheComponent],
  template: `
    <div class="page large">
      <h1>Classes</h1>
      <app-admin-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (!annee() && anneeCourante.chargee()) {
        <div class="carte">
          <p>Aucune année scolaire.</p>
          <a routerLink="/admin/annee" class="bouton secondaire">Créer l'année scolaire</a>
        </div>
      }

      @if (annee(); as a) {
        <section class="carte">
          <div class="entete-section">
            <h2>{{ classes().length }} classe(s) en {{ a.libelle }}</h2>
            @if (peutModifier()) {
              <button type="button" class="bouton" (click)="formulaire.set(!formulaire())">
                {{ formulaire() ? 'Annuler' : 'Nouvelle classe' }}
              </button>
            }
          </div>

          @if (formulaire()) {
            @if (filieres().length === 0) {
              <div class="alerte attention">
                Créez d'abord une filière (onglet « Filières et matières »).
              </div>
            } @else {
              <form (ngSubmit)="creer()" class="nouveau">
                <div class="grille-champs">
                  <div class="champ">
                    <label for="filiere">Filière</label>
                    <select id="filiere" name="filiere" required [(ngModel)]="filiereId">
                      @for (f of filieres(); track f.id) {
                        <option [value]="f.id">{{ f.code }} · {{ f.libelle }}</option>
                      }
                    </select>
                  </div>
                  <div class="champ">
                    <label for="niveau">Niveau</label>
                    <input id="niveau" name="niveau" required list="niveaux" placeholder="2nde" [(ngModel)]="niveau" />
                    <datalist id="niveaux">
                      @for (n of niveaux; track n) { <option [value]="n"></option> }
                    </datalist>
                  </div>
                  <div class="champ">
                    <label for="code">Nom de la classe</label>
                    <input id="code" name="code" required maxlength="30" placeholder="2nde F3 A" [(ngModel)]="code" />
                  </div>
                  <div class="champ">
                    <label for="effectif">Effectif maximal</label>
                    <input id="effectif" name="effectif" type="number" min="1" [(ngModel)]="effectifMax" />
                  </div>
                </div>
                <button type="submit" class="bouton" [disabled]="action.enCours()">Créer la classe</button>
              </form>
            }
          }

          @if (classes().length > 8) {
            <app-recherche
              libelle="Rechercher une classe (code, niveau, filière)"
              [(valeur)]="filtre"
              [total]="classes().length"
              [trouves]="nombreAffiches()"
            />
          }
          @for (groupe of parNiveau(); track groupe.niveau) {
            <h3>{{ groupe.niveau }}</h3>
            <ul class="liste">
              @for (c of groupe.classes; track c.id) {
                <li>
                  <a class="ligne-lien" [routerLink]="['/admin/classes', c.id]">
                    <span>
                      <strong>{{ c.code }}</strong>
                      <span class="doux"> · filière {{ c.filiereCode }}@if (c.effectifMax) { · {{ c.effectifMax }} places max }</span>
                    </span>
                    <span aria-hidden="true">›</span>
                  </a>
                </li>
              }
            </ul>
          } @empty {
            <p class="doux">{{ classes().length ? 'Aucune classe ne correspond.' : 'Aucune classe pour cette année.' }}</p>
          }
        </section>
      }
    </div>
  `,
  styles: `
    .nouveau {
      border-bottom: 1px solid var(--bordure);
      padding-bottom: 1rem;
      margin-bottom: 0.5rem;
    }
    h3 {
      font-size: 0.95rem;
      color: var(--texte-doux);
      margin: 1rem 0 0;
    }
  `,
})
export class ClassesPage {
  private readonly api = inject(AdminApi);
  private readonly session = inject(SessionService);
  protected readonly anneeCourante = inject(AnneeCourante);

  protected readonly niveaux = ['6e', '5e', '4e', '3e', '2nde', '1re', 'Tle', 'CAP 1', 'CAP 2', 'BEP 1', 'BEP 2'];
  protected readonly action = new Action();
  protected readonly annee = this.anneeCourante.annee;
  protected readonly classes = signal<ClasseVue[]>([]);
  protected readonly filieres = signal<FiliereVue[]>([]);
  protected readonly formulaire = signal(false);
  protected readonly peutModifier = computed(
    () =>
      this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR') &&
      (this.annee()?.etat === 'PREPARATION' || this.annee()?.etat === 'ACTIVE'),
  );

  protected readonly filiereId = signal('');
  protected readonly niveau = signal('');
  protected readonly code = signal('');
  protected readonly effectifMax = signal<number | null>(60);

  protected readonly filtre = signal('');
  protected readonly nombreAffiches = computed(() => this.parNiveau().reduce((n, g) => n + g.classes.length, 0));

  /** Classes regroupées par niveau, dans l'ordre des niveaux du secondaire. */
  protected readonly parNiveau = computed(() => {
    const ordre = (n: string) => {
      const i = this.niveaux.indexOf(n);
      return i < 0 ? 100 : i;
    };
    const groupes = new Map<string, ClasseVue[]>();
    for (const c of filtrer(this.classes(), this.filtre(), (x) => [x.code, x.niveau, x.filiereCode])) {
      groupes.set(c.niveau, [...(groupes.get(c.niveau) ?? []), c]);
    }
    return [...groupes.entries()]
      .sort(([a], [b]) => ordre(a) - ordre(b) || a.localeCompare(b))
      .map(([niveau, classes]) => ({ niveau, classes: classes.sort((a, b) => a.code.localeCompare(b.code)) }));
  });

  constructor() {
    void this.action.executer(async () => {
      const filieres = await this.api.filieres();
      this.filieres.set(filieres);
      this.filiereId.set(filieres[0]?.id ?? '');
    });
    effect(() => {
      const a = this.annee();
      untracked(() => {
        if (a) {
          void this.action.executer(async () => this.classes.set(await this.api.classes(a.id)));
        }
      });
    });
  }

  protected async creer(): Promise<void> {
    const a = this.annee();
    if (!a) {
      return;
    }
    const c = await this.action.executer(() =>
      this.api.creerClasse(a.id, {
        filiereId: this.filiereId(),
        code: this.code().trim(),
        niveau: this.niveau().trim(),
        effectifMax: this.effectifMax() || null,
      }),
    );
    if (c) {
      this.classes.update((l) => [...l, c]);
      this.code.set('');
      this.formulaire.set(false);
    }
  }
}
