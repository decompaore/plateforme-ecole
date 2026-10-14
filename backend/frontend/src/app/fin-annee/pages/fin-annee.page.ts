import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { AdminNavComponent } from '../../admin/admin-nav.component';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { AnneeVue, ClasseVue, Decision, LIBELLE_DECISION, PeriodeVue, ProfilVue } from '../../admin/modeles-admin';
import { messageErreur } from '../../core/erreurs';
import { SessionService } from '../../core/session.service';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import { FinAnneeApi } from '../fin-annee-api.service';
import {
  anPlusUn,
  ClasseCibleVue,
  DECISION_ELEVE,
  effectifsPrevus,
  libelleSuivant,
  LIBELLE_SORTIE,
  LignePlan,
  moyenne,
  ParametresPassage,
  PlanVue,
  PoidsPeriode,
  ResultatPlan,
  TableauPassageVue,
} from '../modeles-fin-annee';

/** Code d'erreur d'un problème renvoyé par l'API. */
function code(e: unknown): string | undefined {
  return e instanceof HttpErrorResponse ? (e.error as { code?: string } | null)?.code : undefined;
}

/**
 * Fin d'année (v0.45) : tout le processus sur une page, dans l'ordre.
 * 1. Notes figées (périodes verrouillées). 2. Conseils de classe : décisions validées. 3. Année suivante
 * créée. 4. Plan de passage : la plateforme répartit admis et redoublants dans les classes de l'année
 * suivante, la direction ajuste puis confirme (réinscriptions en une fois). 5. Clôture de l'année et
 * ouverture de la suivante.
 */
@Component({
  selector: 'app-fin-annee',
  imports: [FormsModule, RouterLink, AdminNavComponent],
  template: `
    <div class="page large">
      <h1>Fin d'année @if (annee(); as a) { · {{ a.libelle }} }</h1>
      <app-admin-nav />

      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      @if (annee(); as a) {
        <!-- 1. Notes -->
        <section class="carte etape" [class.faite]="periodesOuvertes().length === 0">
          <h2><span class="numero">1</span> Notes figées</h2>
          @if (periodesOuvertes().length === 0) {
            <p>Toutes les périodes sont verrouillées : les moyennes annuelles peuvent se calculer.</p>
          } @else {
            <p>
              {{ periodesOuvertes().length }} période(s) encore ouverte(s) : {{ libellesPeriodesOuvertes() }}.
              Verrouillez-les depuis l'onglet <a routerLink="/admin/bulletins">Bulletins</a>.
            </p>
          }
        </section>

        <!-- 2. Conseils -->
        <section class="carte etape" [class.faite]="!!tableau() && tableau()!.classes > 0 && tableau()!.classesValidees === tableau()!.classes">
          <div class="entete-section">
            <h2><span class="numero">2</span> Conseils de classe</h2>
            @if (tableau(); as t) {
              <span class="pastille" [class.present]="t.classesValidees === t.classes">{{ t.classesValidees }} / {{ t.classes }} classes validées</span>
            }
          </div>
          @if (direction() && aCalculer().length > 0) {
            <button type="button" class="bouton secondaire" [disabled]="action.enCours() || periodesOuvertes().length > 0" (click)="calculerTout()">
              Calculer les propositions de {{ aCalculer().length }} classe(s)
            </button>
          }
          @if (tableau(); as t) {
            <div class="tableau-defilant">
              <table class="tableau">
                <thead><tr><th>Classe</th><th>Examen</th><th>Décisions</th><th>Répartition</th><th></th></tr></thead>
                <tbody>
                  @for (c of t.detail; track c.classeId) {
                    <tr>
                      <td><strong>{{ c.classeCode }}</strong></td>
                      <td>{{ c.examen ?? '' }}</td>
                      <td>
                        @if (c.validees) {
                          <span class="pastille present">validées</span>
                        } @else if (c.decisions > 0) {
                          <span class="pastille">à valider</span>
                        } @else {
                          <span class="doux">à calculer</span>
                        }
                      </td>
                      <td class="doux">{{ texteRepartition(c.repartition) }}</td>
                      <td><a class="bouton discret petit" [routerLink]="['/admin/fin-annee/classes', c.classeId]">Conseil ›</a></td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </section>

        <!-- 3. Année suivante -->
        <section class="carte etape" [class.faite]="!!plan()">
          <h2><span class="numero">3</span> Année suivante</h2>
          @if (plan(); as p) {
            <p>
              L'année <strong>{{ p.anneeSuivante.libelle }}</strong> est créée ({{ p.classesCibles.length }} classes,
              {{ p.anneeSuivante.etat === 'ACTIVE' ? 'en cours' : 'en préparation' }}). Une classe de niveau supérieur qui
              n'existait pas cette année (ex. 5e quand l'école n'avait que la 6e) se crée dans l'onglet
              <a routerLink="/admin/classes">Classes</a>, en choisissant l'année {{ p.anneeSuivante.libelle }}.
            </p>
          } @else if (sansAnneeSuivante()) {
            <p class="doux">
              Elle reprend les classes, matières et coefficients, les périodes (décalées d'un an), les frais de scolarité et
              les classes d'examen de {{ a.libelle }}.
            </p>
            @if (admin()) {
              <form class="grille-champs" (ngSubmit)="creerAnneeSuivante()">
                <div class="champ">
                  <label for="sLibelle">Libellé</label>
                  <input id="sLibelle" name="sLibelle" required [(ngModel)]="sLibelle" placeholder="2027-2028" />
                </div>
                <div class="champ">
                  <label for="sDebut">Début</label>
                  <input id="sDebut" name="sDebut" type="date" required [(ngModel)]="sDebut" />
                </div>
                <div class="champ">
                  <label for="sFin">Fin</label>
                  <input id="sFin" name="sFin" type="date" required [(ngModel)]="sFin" />
                </div>
                <div class="champ">
                  <button type="submit" class="bouton" [disabled]="action.enCours()">Créer l'année suivante</button>
                </div>
              </form>
            } @else {
              <p class="doux">Le chef d'établissement la crée.</p>
            }
          }
        </section>

        <!-- 4. Plan de passage -->
        @if (plan(); as p) {
          <section class="carte etape" [class.faite]="aReinscrire().length === 0 && p.eleves.length > 0">
            <div class="entete-section">
              <h2><span class="numero">4</span> Listes de {{ p.anneeSuivante.libelle }}</h2>
              <div class="actions-ligne">
                <button type="button" class="bouton secondaire petit" [disabled]="actionListes.enCours()" (click)="telecharger('xlsx')">Excel</button>
                <button type="button" class="bouton secondaire petit" [disabled]="actionListes.enCours()" (click)="telecharger('pdf')">PDF</button>
              </div>
            </div>
            <p class="doux">
              La plateforme place les admis au niveau supérieur de leur filière et les redoublants au même niveau, en
              équilibrant les classes parallèles (effectif, filles et garçons, redoublants). Déplacez un élève en changeant sa
              classe, puis confirmez : les réinscriptions se font en une fois.
            </p>
            @if (p.classesNonValidees.length) {
              <p class="alerte attention">
                Décisions non validées : {{ p.classesNonValidees.join(', ') }}. Leurs élèves entreront dans le plan après la
                validation du conseil.
              </p>
            }
            @if (resultat(); as r) {
              <div class="alerte succes" role="status">
                {{ r.reinscrits }} élève(s) réinscrit(s) en {{ p.anneeSuivante.libelle }}, dont {{ r.redoublants }} redoublant(s).
                @if (r.nonReinscrits.length) {
                  <ul>
                    @for (n of r.nonReinscrits; track n.inscriptionId) { <li>{{ n.nomComplet ?? '' }} : {{ n.motif }}</li> }
                  </ul>
                }
              </div>
            }

            <div class="cibles">
              @for (c of ciblesUtiles(); track c.id) {
                @let e = effectifs()[c.id];
                <div class="cible" [class.pleine]="e.depasse">
                  <div class="entete-cible">
                    <strong>{{ c.code }}</strong>
                    <span [class.rouge]="e.depasse">{{ e.total }}@if (c.effectifMax) { / {{ c.effectifMax }} }</span>
                  </div>
                  <p class="doux">
                    @if (c.dejaInscrits) { {{ c.dejaInscrits }} déjà inscrit(s) · }
                    {{ e.filles }} F, {{ e.garcons }} G proposés@if (e.redoublants) { , dont {{ e.redoublants }} redoublant(s) }
                  </p>
                  <ul class="eleves">
                    @for (l of proposesDans(c.id); track l.inscriptionId) {
                      <li>
                        <span>
                          <strong>{{ l.nom }}</strong> {{ l.prenoms }}
                          <span class="doux">{{ l.sexe }} · {{ l.classeSourceCode }}@if (l.redoublant) { · redouble }</span>
                        </span>
                        @if (peutAppliquer()) {
                          <select [ngModel]="choix()[l.inscriptionId]" (ngModelChange)="deplacer(l, $event)" [attr.aria-label]="'Classe de ' + l.prenoms + ' ' + l.nom">
                            @for (k of ciblesPour(l); track k.id) { <option [value]="k.id">{{ k.code }}</option> }
                            <option value="">— ne pas réinscrire —</option>
                          </select>
                        }
                      </li>
                    }
                  </ul>
                </div>
              }
            </div>

            @if (aPlacer().length) {
              <h3>Sans classe ({{ aPlacer().length }})</h3>
              <ul class="liste">
                @for (l of aPlacer(); track l.inscriptionId) {
                  <li class="ligne-sortant">
                    <span>
                      <strong>{{ l.nom }}</strong> {{ l.prenoms }} <span class="doux">{{ l.classeSourceCode }} · {{ eleve[l.decision] }}</span>
                      <br /><span class="doux">{{ l.sortie ? sorties[l.sortie] : 'Retiré du plan' }}</span>
                    </span>
                    @if (peutAppliquer() && l.sortie !== 'EXCLU') {
                      <select [ngModel]="choix()[l.inscriptionId] ?? ''" (ngModelChange)="deplacer(l, $event)" [attr.aria-label]="'Placer ' + l.prenoms + ' ' + l.nom">
                        <option value="">— à placer —</option>
                        @for (k of p.classesCibles; track k.id) { <option [value]="k.id">{{ k.code }}</option> }
                      </select>
                    }
                  </li>
                }
              </ul>
            }
            @if (dejaReinscrits().length) {
              <p class="doux">{{ dejaReinscrits().length }} élève(s) déjà inscrit(s) en {{ p.anneeSuivante.libelle }}.</p>
            }

            @if (peutAppliquer() && aReinscrire().length) {
              <label class="case"><input type="checkbox" [(ngModel)]="conserverBourse" name="bourse" /> Garder le statut de bourse de cette année</label>
              @if (confirmationPlan()) {
                <div class="confirmation" role="alertdialog" aria-label="Confirmer le plan">
                  <span>Réinscrire {{ aReinscrire().length }} élève(s) en {{ p.anneeSuivante.libelle }} ?</span>
                  <button type="button" class="bouton secondaire petit" (click)="confirmationPlan.set(false)">Annuler</button>
                  <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="appliquer()">Confirmer les réinscriptions</button>
                </div>
              } @else {
                <button type="button" class="bouton" [disabled]="action.enCours()" (click)="confirmationPlan.set(true)">
                  Réinscrire {{ aReinscrire().length }} élève(s)
                </button>
              }
            }
          </section>
        }

        <!-- 5. Clôture -->
        <section class="carte etape" [class.faite]="a.etat === 'CLOTUREE' || a.etat === 'ARCHIVEE'">
          <h2><span class="numero">5</span> Clôture</h2>
          @if (a.etat === 'ACTIVE') {
            <p class="doux">
              Clôturer {{ a.libelle }} interdit toute nouvelle saisie (notes, absences, inscriptions) ; l'année reste
              consultable. Vous pourrez ensuite ouvrir l'année suivante.
            </p>
            @if (admin()) {
              @if (confirmationCloture()) {
                <div class="confirmation" role="alertdialog" aria-label="Clôturer l'année">
                  <span>Clôturer {{ a.libelle }} ? Ce n'est pas réversible.</span>
                  <button type="button" class="bouton secondaire petit" (click)="confirmationCloture.set(false)">Annuler</button>
                  <button type="button" class="bouton danger petit" [disabled]="action.enCours()" (click)="cloturer()">Clôturer</button>
                </div>
              } @else {
                <button type="button" class="bouton secondaire" [disabled]="action.enCours()" (click)="confirmationCloture.set(true)">Clôturer {{ a.libelle }}</button>
              }
            }
          } @else if (a.etat === 'CLOTUREE' || a.etat === 'ARCHIVEE') {
            <p>{{ a.libelle }} est clôturée.</p>
          } @else {
            <p class="doux">{{ a.libelle }} est en préparation.</p>
          }
          @if (plan(); as p) {
            @if (admin() && p.anneeSuivante.etat === 'PREPARATION' && a.etat !== 'ACTIVE') {
              <button type="button" class="bouton" [disabled]="action.enCours()" (click)="ouvrirSuivante(p.anneeSuivante)">
                Ouvrir l'année {{ p.anneeSuivante.libelle }}
              </button>
            }
          }
        </section>

        <!-- Réglages -->
        <details class="carte reglages">
          <summary><strong>Réglages du passage</strong> <span class="doux">(seuils, poids des périodes, classes d'examen)</span></summary>
          @if (parametres(); as pr) {
            <h3>Règles de l'établissement</h3>
            <div class="grille-champs">
              <div class="champ">
                <label for="seuil">Exclusion sous la moyenne annuelle de</label>
                <input id="seuil" name="seuil" type="number" min="0" max="20" step="0.25" [(ngModel)]="seuil" [disabled]="!admin()" placeholder="désactivée" />
              </div>
              <div class="champ">
                <label for="redMax">Redoublements au plus dans un niveau</label>
                <input id="redMax" name="redMax" type="number" min="1" max="5" [(ngModel)]="redoublementsMax" [disabled]="!admin()" placeholder="sans limite" />
              </div>
            </div>
            @if (admin()) {
              <button type="button" class="bouton secondaire petit" [disabled]="action.enCours()" (click)="enregistrerParametres()">Enregistrer les règles</button>
            }
            <p class="doux">Champ vide : la règle est désactivée. L'admission se fait au seuil du profil pédagogique (10 par défaut).</p>
          }

          @if (poidsParProfil().length) {
            <h3>Poids des périodes dans la moyenne annuelle</h3>
            @for (pp of poidsParProfil(); track pp.profil.id) {
              <div class="poids">
                <strong>{{ pp.profil.libelle }}</strong>
                @for (x of pp.poids; track x.ordre) {
                  <label>
                    {{ pp.libelles[x.ordre] ?? 'Période ' + x.ordre }}
                    <input type="number" min="0.5" max="10" step="0.5" [(ngModel)]="x.poids" [name]="'poids' + pp.profil.id + x.ordre" [disabled]="!admin()" />
                  </label>
                }
                @if (admin()) {
                  <button type="button" class="bouton secondaire petit" [disabled]="action.enCours()" (click)="enregistrerPoids(pp.profil, pp.poids)">Enregistrer</button>
                }
              </div>
            }
          }

          <h3>Classes d'examen</h3>
          <p class="doux">Nom de l'examen national préparé (BEPC, BAC F3, CAP, BEP…) ; vide : pas d'examen. Les résultats se saisissent au conseil.</p>
          <div class="examens">
            @for (c of classes(); track c.id) {
              <label>
                <span>{{ c.code }}</span>
                <input [ngModel]="examens()[c.id] ?? ''" (ngModelChange)="examensSaisis[c.id] = $event" [name]="'examen' + c.id" maxlength="40" [disabled]="!direction()" />
              </label>
            }
          </div>
          @if (direction() && classes().length) {
            <button type="button" class="bouton secondaire petit" [disabled]="action.enCours()" (click)="enregistrerExamens()">Enregistrer les examens</button>
          }
        </details>
      }
    </div>
  `,
  styles: `
    .etape h2 {
      display: flex;
      align-items: center;
      gap: 0.6rem;
      font-size: 1.15rem;
      margin: 0 0 0.6rem;
    }
    .numero {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 1.8rem;
      height: 1.8rem;
      border-radius: 50%;
      background: var(--retard-fond);
      color: var(--retard);
      font-size: 0.95rem;
    }
    .etape.faite .numero {
      background: var(--present-fond);
      color: var(--present);
    }
    .cibles {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(17rem, 1fr));
      gap: 0.75rem;
      margin: 0.75rem 0;
    }
    .cible {
      border: 1px solid var(--bordure);
      border-radius: 10px;
      padding: 0.6rem 0.75rem;
      &.pleine {
        border-color: var(--absent);
      }
      p {
        margin: 0.2rem 0 0.4rem;
        font-size: 0.85rem;
      }
    }
    .entete-cible {
      display: flex;
      justify-content: space-between;
    }
    .eleves {
      list-style: none;
      margin: 0;
      padding: 0;
      max-height: 22rem;
      overflow: auto;
      li {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 0.4rem;
        padding: 0.25rem 0;
        border-top: 1px solid var(--bordure);
        font-size: 0.9rem;
      }
      select {
        width: auto;
        min-height: 32px;
        font-size: 0.85rem;
      }
    }
    .ligne-sortant {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 0.5rem;
      select {
        width: auto;
      }
    }
    .rouge {
      color: var(--absent);
      font-weight: 600;
    }
    .confirmation {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
    }
    .case {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      margin: 0.5rem 0;
      input {
        width: auto;
        min-height: 0;
      }
    }
    .reglages summary {
      cursor: pointer;
    }
    .poids {
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem;
      align-items: flex-end;
      margin-bottom: 0.75rem;
      label {
        display: flex;
        flex-direction: column;
        font-size: 0.85rem;
      }
      input {
        width: 5rem;
      }
    }
    .examens {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(12rem, 1fr));
      gap: 0.5rem;
      margin-bottom: 0.75rem;
      label {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        span {
          min-width: 4.5rem;
        }
      }
    }
  `,
})
export class FinAnneePage {
  private readonly api = inject(FinAnneeApi);
  private readonly admin_ = inject(AdminApi);
  private readonly session = inject(SessionService);
  private readonly anneeCourante = inject(AnneeCourante);

  protected readonly libelles = LIBELLE_DECISION;
  protected readonly eleve = DECISION_ELEVE;
  protected readonly sorties = LIBELLE_SORTIE;
  protected readonly moyenne = moyenne;
  protected readonly action = new Action();
  protected readonly actionListes = new Action();
  protected readonly annee = this.anneeCourante.annee;
  protected readonly message = signal<string | null>(null);

  protected readonly direction = computed(() => this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR'));
  protected readonly admin = computed(() => this.session.aLeRole('ADMIN_ECOLE'));
  /** Le censeur répartit les élèves dans les classes (v0.47). */
  protected readonly peutAppliquer = computed(() => this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR'));

  protected readonly periodes = signal<PeriodeVue[]>([]);
  protected readonly classes = signal<ClasseVue[]>([]);
  protected readonly tableau = signal<TableauPassageVue | null>(null);
  protected readonly plan = signal<PlanVue | null>(null);
  protected readonly sansAnneeSuivante = signal(false);
  /** Classe choisie pour chaque élève du plan (inscription → classe ; '' ou null : pas de réinscription). */
  protected readonly choix = signal<Record<string, string | null>>({});
  protected readonly resultat = signal<ResultatPlan | null>(null);
  protected readonly confirmationPlan = signal(false);
  protected readonly confirmationCloture = signal(false);
  protected conserverBourse = false;

  // Année suivante
  protected readonly sLibelle = signal('');
  protected readonly sDebut = signal('');
  protected readonly sFin = signal('');

  // Réglages
  protected readonly parametres = signal<ParametresPassage | null>(null);
  protected seuil: number | null = null;
  protected redoublementsMax: number | null = null;
  protected readonly poidsParProfil = signal<{ profil: ProfilVue; poids: PoidsPeriode[]; libelles: Record<number, string> }[]>([]);
  protected readonly examens = signal<Record<string, string>>({});
  protected examensSaisis: Record<string, string> = {};

  protected readonly periodesOuvertes = computed(() => this.periodes().filter((p) => !p.verrouillee));
  protected readonly libellesPeriodesOuvertes = computed(() => [...new Set(this.periodesOuvertes().map((p) => p.libelle))].join(', '));
  protected readonly aCalculer = computed(() => (this.tableau()?.detail ?? []).filter((c) => !c.validees));

  /** Lignes à placer ou placées par le plan (pas encore inscrites l'an prochain). */
  private readonly enAttente = computed(() => (this.plan()?.eleves ?? []).filter((l) => !l.dejaInscrit));
  protected readonly dejaReinscrits = computed(() => (this.plan()?.eleves ?? []).filter((l) => !!l.dejaInscrit));
  protected readonly aReinscrire = computed(() => this.enAttente().filter((l) => !!this.choix()[l.inscriptionId]));
  protected readonly aPlacer = computed(() => this.enAttente().filter((l) => !this.choix()[l.inscriptionId]));
  protected readonly effectifs = computed(() => {
    const r: Record<string, ReturnType<typeof effectifsPrevus>> = {};
    for (const c of this.plan()?.classesCibles ?? []) {
      r[c.id] = effectifsPrevus(c, this.enAttente(), this.choix());
    }
    return r;
  });
  /** Classes de l'année suivante qui reçoivent des élèves (ou en ont déjà). */
  protected readonly ciblesUtiles = computed(() =>
    (this.plan()?.classesCibles ?? []).filter((c) => c.dejaInscrits > 0 || this.enAttente().some((l) => this.choix()[l.inscriptionId] === c.id)),
  );

  constructor() {
    effect(() => {
      const a = this.annee();
      untracked(() => {
        if (a) {
          void this.charger(a);
        }
      });
    });
    void this.anneeCourante.charger().catch(() => undefined);
  }

  private async charger(a: AnneeVue): Promise<void> {
    this.message.set(null);
    this.resultat.set(null);
    this.sLibelle.set(libelleSuivant(a.libelle));
    this.sDebut.set(anPlusUn(a.debut));
    this.sFin.set(anPlusUn(a.fin));
    await this.action.executer(async () => {
      const [periodes, classes, tableau, parametres] = await Promise.all([
        this.admin_.periodes(a.id),
        this.admin_.classes(a.id),
        this.api.tableau(a.id),
        this.api.parametres(),
      ]);
      this.periodes.set(periodes);
      this.classes.set([...classes].sort((x, y) => x.code.localeCompare(y.code, 'fr')));
      this.tableau.set({ ...tableau, detail: [...tableau.detail].sort((x, y) => x.classeCode.localeCompare(y.classeCode, 'fr')) });
      this.parametres.set(parametres);
      this.seuil = parametres.seuilExclusion;
      this.redoublementsMax = parametres.redoublementsMax;
      const examens: Record<string, string> = {};
      tableau.detail.forEach((c) => (examens[c.classeId] = c.examen ?? ''));
      this.examens.set(examens);
      this.examensSaisis = {};
      await this.chargerPoids(classes, periodes);
    });
    await this.chargerPlan(a);
  }

  private async chargerPoids(classes: ClasseVue[], periodes: PeriodeVue[]): Promise<void> {
    const profils = (await this.admin_.profils()).filter((p) => classes.some((c) => c.profilId === p.id));
    const r = [];
    for (const profil of profils) {
      const duProfil = periodes.filter((p) => p.profilId === profil.id).sort((x, y) => x.ordre - y.ordre);
      const enregistres = await this.api.poids(profil.id);
      const poids = duProfil.map((p) => ({ ordre: p.ordre, poids: Number(enregistres.find((x) => x.ordre === p.ordre)?.poids ?? 1) }));
      const libelles: Record<number, string> = {};
      duProfil.forEach((p) => (libelles[p.ordre] = p.libelle));
      r.push({ profil, poids, libelles });
    }
    this.poidsParProfil.set(r);
  }

  private async chargerPlan(a: AnneeVue): Promise<void> {
    try {
      const p = await this.api.plan(a.id);
      this.plan.set(p);
      this.sansAnneeSuivante.set(false);
      const choix: Record<string, string | null> = {};
      p.eleves.forEach((l) => (choix[l.inscriptionId] = l.classeCibleId));
      this.choix.set(choix);
    } catch (e) {
      this.plan.set(null);
      if (code(e) === 'ANNEE_SUIVANTE_ABSENTE') {
        this.sansAnneeSuivante.set(true);
      } else {
        this.action.erreur.set(messageErreur(e));
      }
    }
  }

  protected texteRepartition(r: Partial<Record<Decision, number>>): string {
    return Object.entries(r)
      .map(([d, n]) => `${this.libelles[d as Decision]} ${n}`)
      .join(' · ');
  }

  /** Classes possibles pour un élève : même filière et même niveau que la classe proposée. */
  protected ciblesPour(l: LignePlan): ClasseCibleVue[] {
    const toutes = this.plan()?.classesCibles ?? [];
    const proposee = toutes.find((c) => c.id === l.classeCibleId);
    return proposee ? toutes.filter((c) => c.niveau === proposee.niveau && c.filiereCode === proposee.filiereCode) : toutes;
  }

  protected proposesDans(classeId: string): LignePlan[] {
    return this.enAttente()
      .filter((l) => this.choix()[l.inscriptionId] === classeId)
      .sort((a, b) => a.nom.localeCompare(b.nom, 'fr') || a.prenoms.localeCompare(b.prenoms, 'fr'));
  }

  protected deplacer(l: LignePlan, classeId: string): void {
    this.choix.set({ ...this.choix(), [l.inscriptionId]: classeId || null });
  }

  // ---------- Actions

  protected async calculerTout(): Promise<void> {
    const a = this.annee();
    if (!a) {
      return;
    }
    this.message.set(null);
    const erreurs: string[] = [];
    let faites = 0;
    await this.action.executer(async () => {
      for (const c of this.aCalculer()) {
        try {
          await this.api.calculer(c.classeId);
          faites++;
        } catch (e) {
          erreurs.push(`${c.classeCode} : ${messageErreur(e)}`);
        }
      }
      this.tableau.set(await this.api.tableau(a.id));
    });
    this.message.set(`Propositions calculées pour ${faites} classe(s). Ouvrez chaque conseil pour décider et valider.`);
    if (erreurs.length) {
      this.action.erreur.set(erreurs.join(' — '));
    }
  }

  protected async creerAnneeSuivante(): Promise<void> {
    const a = this.annee();
    if (!a) {
      return;
    }
    const r = await this.action.executer(() =>
      this.api.creerAnneeSuivante(a.id, { libelle: this.sLibelle().trim(), debut: this.sDebut(), fin: this.sFin() }),
    );
    if (r) {
      this.message.set(
        `Année ${r.annee.libelle} créée : ${r.classesCopiees} classe(s), ${r.periodesCopiees} période(s), ${r.fraisRepris} frais et ${r.examensRepris} classe(s) d'examen repris.`,
      );
      await this.anneeCourante.charger(true);
      await this.chargerPlan(a);
    }
  }

  protected async telecharger(format: 'xlsx' | 'pdf'): Promise<void> {
    const a = this.annee();
    const p = this.plan();
    if (!a || !p) {
      return;
    }
    const blob = await this.actionListes.executer(() => this.api.listes(a.id, format), "Les listes n'ont pas pu être préparées.");
    if (blob) {
      enregistrerFichier(blob, `listes-${p.anneeSuivante.libelle}.${format}`);
    }
  }

  protected async appliquer(): Promise<void> {
    const a = this.annee();
    if (!a) {
      return;
    }
    const affectations = this.aReinscrire().map((l) => ({ inscriptionId: l.inscriptionId, classeCibleId: this.choix()[l.inscriptionId]! }));
    const r = await this.action.executer(() => this.api.appliquerPlan(a.id, affectations, this.conserverBourse));
    this.confirmationPlan.set(false);
    if (r) {
      await this.chargerPlan(a);
      this.resultat.set(r);
    }
  }

  protected async cloturer(): Promise<void> {
    const a = this.annee();
    if (!a) {
      return;
    }
    const r = await this.action.executer(() => this.api.cloturer(a.id));
    this.confirmationCloture.set(false);
    if (r) {
      this.message.set(`${r.libelle} est clôturée.`);
      await this.anneeCourante.charger(true);
      this.anneeCourante.choisir(r.id);
    }
  }

  protected async ouvrirSuivante(suivante: AnneeVue): Promise<void> {
    const r = await this.action.executer(() => this.api.ouvrir(suivante.id));
    if (r) {
      this.message.set(`L'année ${r.libelle} est ouverte : elle devient l'année en cours.`);
      await this.anneeCourante.charger(true);
    }
  }

  protected async enregistrerParametres(): Promise<void> {
    const vide = (v: unknown) => v === null || v === undefined || v === '';
    const r = await this.action.executer(() =>
      this.api.modifierParametres({
        seuilExclusion: vide(this.seuil) ? null : Number(this.seuil),
        redoublementsMax: vide(this.redoublementsMax) ? null : Number(this.redoublementsMax),
      }),
    );
    if (r) {
      this.parametres.set(r);
      this.message.set('Règles enregistrées. Recalculez les propositions des classes non validées.');
    }
  }

  protected async enregistrerPoids(profil: ProfilVue, poids: PoidsPeriode[]): Promise<void> {
    const r = await this.action.executer(() =>
      this.api.definirPoids(profil.id, poids.map((p) => ({ ordre: p.ordre, poids: Number(p.poids) }))),
    );
    if (r) {
      this.message.set(`Poids des périodes enregistrés (${profil.libelle}).`);
    }
  }

  protected async enregistrerExamens(): Promise<void> {
    const changes = Object.entries(this.examensSaisis).filter(([id, v]) => (v ?? '').trim() !== (this.examens()[id] ?? ''));
    if (!changes.length) {
      this.message.set('Rien à enregistrer.');
      return;
    }
    const ok = await this.action.reussit(async () => {
      for (const [id, v] of changes) {
        await this.api.definirExamen(id, v.trim());
      }
    });
    if (ok) {
      const a = this.annee();
      this.message.set(`${changes.length} classe(s) d'examen enregistrée(s).`);
      if (a) {
        this.tableau.set(await this.api.tableau(a.id));
        const examens = { ...this.examens() };
        changes.forEach(([id, v]) => (examens[id] = v.trim()));
        this.examens.set(examens);
        this.examensSaisis = {};
      }
    }
  }
}
