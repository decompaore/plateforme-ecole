import { Component, computed, effect, inject, signal, untracked } from '@angular/core';

import { dateHeureCourte } from '../../core/outils';
import { fcfa } from '../../parent/modeles-parent';
import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { AdminNavComponent } from '../admin-nav.component';
import { AnneeCourante } from '../annee-courante.service';
import { AgeVue, Compte, Decision, LIBELLE_DECISION, LIBELLE_ROLE, RapportStatistiques } from '../modeles-admin';

type Vue = 'total' | 'garcons' | 'filles';

export function total(c: Compte | undefined): number {
  return c ? c.garcons + c.filles : 0;
}

function somme(comptes: Compte[]): Compte {
  return comptes.reduce((t, c) => ({ garcons: t.garcons + c.garcons, filles: t.filles + c.filles }), { garcons: 0, filles: 0 });
}

/** Tableau croisé des âges : une ligne par niveau (ordre du rapport), une colonne par âge. */
export function pivotAges(ages: AgeVue[]): { colonnes: number[]; lignes: { niveau: string; parAge: Map<number, Compte>; total: Compte }[] } {
  const colonnes = [...new Set(ages.map((a) => a.age))].sort((a, b) => a - b);
  const niveaux: string[] = [];
  for (const a of ages) {
    if (!niveaux.includes(a.niveau)) {
      niveaux.push(a.niveau);
    }
  }
  const lignes = niveaux.map((niveau) => {
    const parAge = new Map(ages.filter((a) => a.niveau === niveau).map((a) => [a.age, a.effectif] as const));
    return { niveau, parAge, total: somme([...parAge.values()]) };
  });
  return { colonnes, lignes };
}

/** Ordre d'affichage des décisions de fin d'année. */
const DECISIONS: Decision[] = ['ADMIS', 'CERTIFIE', 'REDOUBLE', 'ORIENTE', 'NON_CERTIFIE', 'EXCLU', 'EN_ATTENTE_EXAMEN'];

/**
 * Statistiques de l'année pour la direction et l'intendance : effectifs, âges, bourses,
 * personnel, recouvrement des frais et résultats, avec le classeur Excel à transmettre.
 * Tout est calculé par le serveur à partir des données déjà saisies.
 */
@Component({
  selector: 'app-statistiques',
  imports: [AdminNavComponent],
  template: `
    <div class="page large">
      <h1>Statistiques</h1>
      <app-admin-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (rapport(); as r) {
        <div class="entete">
          <p class="doux">{{ r.etablissement }} · {{ r.annee.libelle }} · chiffres du {{ dateHeureCourte(r.produitLe) }}</p>
          <button type="button" class="bouton" [disabled]="telechargement.enCours()" (click)="telecharger()">
            {{ telechargement.enCours() ? 'Préparation…' : 'Télécharger le classeur Excel' }}
          </button>
        </div>
        @if (telechargement.erreur()) {
          <div class="alerte erreur" role="alert">{{ telechargement.erreur() }}</div>
        }

        <div class="chiffres">
          <div>
            <strong>{{ total(r.effectifTotal) }}</strong>
            <span>élèves · {{ r.effectifTotal.garcons }} G, {{ r.effectifTotal.filles }} F</span>
          </div>
          <div><strong>{{ r.classes }}</strong><span>classes</span></div>
          <div>
            <strong>{{ total(r.personnel.titulaires) + total(r.personnel.vacataires) }}</strong>
            <span>enseignants · {{ total(r.personnel.titulaires) }} titulaires, {{ total(r.personnel.vacataires) }} vacataires</span>
          </div>
          <div>
            <strong>{{ pourcentage(r.recouvrementTotal.tauxFamilles) }}</strong>
            <span>des frais payés par les familles</span>
          </div>
        </div>

        <nav class="sommaire" aria-label="Rubriques">
          <a href="#effectifs" (click)="aller($event, 'effectifs')">Effectifs</a>
          <a href="#ages" (click)="aller($event, 'ages')">Âges</a>
          <a href="#bourses" (click)="aller($event, 'bourses')">Bourses</a>
          <a href="#personnel" (click)="aller($event, 'personnel')">Personnel</a>
          <a href="#recouvrement" (click)="aller($event, 'recouvrement')">Recouvrement</a>
          <a href="#resultats" (click)="aller($event, 'resultats')">Résultats</a>
        </nav>

        <!-- Effectifs -->
        <section class="carte" id="effectifs">
          <div class="titre-section">
            <h2>Effectifs par niveau</h2>
            <span class="legende" aria-hidden="true">
              <span><i class="pastille-g"></i>Garçons</span>
              <span><i class="pastille-f"></i>Filles</span>
            </span>
          </div>
          <div class="tableau-defilant">
            <table class="tableau">
              <thead>
                <tr>
                  <th>Niveau</th>
                  <th class="nombre">Classes</th>
                  <th class="nombre">G</th>
                  <th class="nombre">F</th>
                  <th class="nombre">Total</th>
                  <th class="barre-col">Répartition</th>
                  <th class="nombre">Redoublants</th>
                </tr>
              </thead>
              <tbody>
                @for (e of r.effectifs; track e.niveau) {
                  <tr>
                    <td><strong>{{ e.niveau }}</strong></td>
                    <td class="nombre">{{ e.classes }}</td>
                    <td class="nombre">{{ e.effectif.garcons }}</td>
                    <td class="nombre">{{ e.effectif.filles }}</td>
                    <td class="nombre"><strong>{{ total(e.effectif) }}</strong></td>
                    <td class="barre-col">
                      <div class="barre" [style.width.%]="largeur(total(e.effectif))">
                        @if (e.effectif.garcons) {
                          <span class="seg-g" [style.flex-grow]="e.effectif.garcons" [title]="e.niveau + ' : ' + e.effectif.garcons + ' garçons'"></span>
                        }
                        @if (e.effectif.filles) {
                          <span class="seg-f" [style.flex-grow]="e.effectif.filles" [title]="e.niveau + ' : ' + e.effectif.filles + ' filles'"></span>
                        }
                      </div>
                    </td>
                    <td class="nombre">
                      {{ total(e.redoublants) }}
                      @if (total(e.redoublants)) { <span class="doux">({{ e.redoublants.garcons }} G, {{ e.redoublants.filles }} F)</span> }
                    </td>
                  </tr>
                } @empty {
                  <tr><td colspan="7" class="doux">Aucun élève inscrit pour cette année.</td></tr>
                }
              </tbody>
              @if (r.effectifs.length) {
                <tfoot>
                  <tr>
                    <th>Total</th>
                    <th class="nombre">{{ r.classes }}</th>
                    <th class="nombre">{{ r.effectifTotal.garcons }}</th>
                    <th class="nombre">{{ r.effectifTotal.filles }}</th>
                    <th class="nombre">{{ total(r.effectifTotal) }}</th>
                    <th></th>
                    <th class="nombre">{{ totalRedoublants() }}</th>
                  </tr>
                </tfoot>
              }
            </table>
          </div>
        </section>

        <!-- Âges -->
        <section class="carte" id="ages">
          <div class="titre-section">
            <h2>Âges <span class="doux">au 31 décembre de l'année de la rentrée</span></h2>
            <div class="bascule" role="group" aria-label="Élèves comptés">
              <button type="button" [class.actif]="vueAges() === 'total'" (click)="vueAges.set('total')">Total</button>
              <button type="button" [class.actif]="vueAges() === 'garcons'" (click)="vueAges.set('garcons')">Garçons</button>
              <button type="button" [class.actif]="vueAges() === 'filles'" (click)="vueAges.set('filles')">Filles</button>
            </div>
          </div>
          @if (ages().lignes.length) {
            <div class="tableau-defilant">
              <table class="tableau ages">
                <thead>
                  <tr>
                    <th>Niveau</th>
                    @for (a of ages().colonnes; track a) { <th class="nombre">{{ a }} ans</th> }
                    <th class="nombre">Total</th>
                  </tr>
                </thead>
                <tbody>
                  @for (l of ages().lignes; track l.niveau) {
                    <tr>
                      <td><strong>{{ l.niveau }}</strong></td>
                      @for (a of ages().colonnes; track a) {
                        <td class="nombre">{{ valeur(l.parAge.get(a)) || '' }}</td>
                      }
                      <td class="nombre"><strong>{{ valeur(l.total) }}</strong></td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <p class="doux">Aucune date de naissance renseignée.</p>
          }
        </section>

        <!-- Bourses -->
        <section class="carte" id="bourses">
          <h2>Statut de bourse par filière</h2>
          <div class="tableau-defilant">
            <table class="tableau">
              <thead>
                <tr>
                  <th>Filière</th>
                  <th class="nombre">Boursiers</th>
                  <th class="nombre">Semi-boursiers</th>
                  <th class="nombre">Non boursiers</th>
                </tr>
              </thead>
              <tbody>
                @for (b of r.bourses; track b.filiere) {
                  <tr>
                    <td><strong>{{ b.filiere }}</strong></td>
                    <td class="nombre">{{ gf(b.boursiers) }}</td>
                    <td class="nombre">{{ gf(b.semiBoursiers) }}</td>
                    <td class="nombre">{{ gf(b.nonBoursiers) }}</td>
                  </tr>
                } @empty {
                  <tr><td colspan="4" class="doux">Aucun élève inscrit.</td></tr>
                }
              </tbody>
            </table>
          </div>
          <p class="doux">Chaque case : total (garçons G, filles F).</p>
        </section>

        <!-- Personnel -->
        <section class="carte" id="personnel">
          <h2>Personnel</h2>
          <div class="deux-colonnes">
            <table class="tableau">
              <thead>
                <tr><th>Enseignants en fonction</th><th class="nombre">H</th><th class="nombre">F</th><th class="nombre">Total</th></tr>
              </thead>
              <tbody>
                <tr>
                  <td>Titulaires</td>
                  <td class="nombre">{{ r.personnel.titulaires.garcons }}</td>
                  <td class="nombre">{{ r.personnel.titulaires.filles }}</td>
                  <td class="nombre"><strong>{{ total(r.personnel.titulaires) }}</strong></td>
                </tr>
                <tr>
                  <td>Vacataires</td>
                  <td class="nombre">{{ r.personnel.vacataires.garcons }}</td>
                  <td class="nombre">{{ r.personnel.vacataires.filles }}</td>
                  <td class="nombre"><strong>{{ total(r.personnel.vacataires) }}</strong></td>
                </tr>
              </tbody>
            </table>
            <table class="tableau">
              <thead><tr><th>Personnel administratif</th><th class="nombre">Nombre</th></tr></thead>
              <tbody>
                @for (a of administratif(); track a.role) {
                  <tr><td>{{ a.libelle }}</td><td class="nombre">{{ a.nombre }}</td></tr>
                } @empty {
                  <tr><td colspan="2" class="doux">Aucun compte administratif.</td></tr>
                }
              </tbody>
            </table>
          </div>
          @if (r.personnel.sexeNonRenseigne) {
            <p class="alerte attention">
              {{ r.personnel.sexeNonRenseigne }} enseignant(s) sans sexe renseigné : comptés dans le total, pas dans H / F.
              Complétez leur fiche dans Personnel.
            </p>
          }
        </section>

        <!-- Recouvrement -->
        <section class="carte" id="recouvrement">
          <h2>Recouvrement des frais de scolarité</h2>
          <div class="tableau-defilant">
            <table class="tableau">
              <thead>
                <tr>
                  <th>Classe</th>
                  <th class="nombre">Dû (familles)</th>
                  <th class="nombre">Payé</th>
                  <th class="barre-col">Taux familles</th>
                  <th class="nombre">Dû (organismes)</th>
                  <th class="nombre">Payé</th>
                  <th class="nombre">Taux</th>
                </tr>
              </thead>
              <tbody>
                @for (c of r.recouvrement; track c.classe) {
                  <tr>
                    <td><strong>{{ c.classe }}</strong></td>
                    <td class="nombre">{{ fcfa(c.duFamilles) }}</td>
                    <td class="nombre">{{ fcfa(c.payeFamilles) }}</td>
                    <td class="barre-col">
                      <span class="jauge" [title]="c.classe + ' : ' + pourcentage(c.tauxFamilles)">
                        <span [style.width.%]="c.tauxFamilles ?? 0"></span>
                      </span>
                      <span [class.rouge]="c.tauxFamilles !== null && c.tauxFamilles < 50">{{ pourcentage(c.tauxFamilles) }}</span>
                    </td>
                    <td class="nombre">{{ c.duOrganismes ? fcfa(c.duOrganismes) : '' }}</td>
                    <td class="nombre">{{ c.duOrganismes ? fcfa(c.payeOrganismes) : '' }}</td>
                    <td class="nombre">{{ c.duOrganismes ? pourcentage(c.tauxOrganismes) : '' }}</td>
                  </tr>
                } @empty {
                  <tr><td colspan="7" class="doux">Aucun frais défini pour cette année.</td></tr>
                }
              </tbody>
              @if (r.recouvrement.length) {
                <tfoot>
                  <tr>
                    <th>Établissement</th>
                    <th class="nombre">{{ fcfa(r.recouvrementTotal.duFamilles) }}</th>
                    <th class="nombre">{{ fcfa(r.recouvrementTotal.payeFamilles) }}</th>
                    <th>{{ pourcentage(r.recouvrementTotal.tauxFamilles) }}</th>
                    <th class="nombre">{{ fcfa(r.recouvrementTotal.duOrganismes) }}</th>
                    <th class="nombre">{{ fcfa(r.recouvrementTotal.payeOrganismes) }}</th>
                    <th class="nombre">{{ pourcentage(r.recouvrementTotal.tauxOrganismes) }}</th>
                  </tr>
                </tfoot>
              }
            </table>
          </div>
          <p class="doux">Organismes : part des boursiers prise en charge (État, partenaires). Taux familles en rouge sous 50 %.</p>
        </section>

        <!-- Résultats -->
        <section class="carte" id="resultats">
          <h2>Résultats de fin d'année</h2>
          @if (r.resultats.length) {
            <div class="tableau-defilant">
              <table class="tableau">
                <thead>
                  <tr>
                    <th>Niveau</th>
                    <th class="nombre">Décidés</th>
                    @for (d of decisions(); track d) { <th class="nombre">{{ libelleDecision[d] }}</th> }
                    <th class="nombre">Taux d'admission</th>
                  </tr>
                </thead>
                <tbody>
                  @for (n of r.resultats; track n.niveau) {
                    <tr>
                      <td><strong>{{ n.niveau }}</strong></td>
                      <td class="nombre">{{ total(n.decides) }}</td>
                      @for (d of decisions(); track d) { <td class="nombre">{{ total(n.parDecision[d]) || '' }}</td> }
                      <td class="nombre"><strong>{{ pourcentage(n.tauxAdmission) }}</strong></td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <p class="doux">Taux d'admission : admis et certifiés sur les élèves dont la décision est prise.</p>
          } @else {
            <p class="doux">Les résultats apparaissent une fois les décisions de fin d'année calculées par le conseil de classe.</p>
          }
        </section>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .entete {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 0.5rem 1rem;
      margin-bottom: 0.5rem;
      p {
        margin: 0;
      }
    }
    .chiffres {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
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
    }
    .sommaire {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      margin-bottom: 1rem;
      a {
        padding: 0.35rem 0.8rem;
        border-radius: 999px;
        border: 1px solid var(--bordure);
        background: var(--surface);
        color: var(--texte);
        text-decoration: none;
        font-size: 0.9rem;
      }
      a:hover {
        border-color: var(--primaire);
      }
    }
    section {
      scroll-margin-top: 1rem;
    }
    h2 {
      font-size: 1.1rem;
      margin: 0 0 0.75rem;
    }
    .titre-section {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 0.5rem 1rem;
      margin-bottom: 0.75rem;
      h2 {
        margin: 0;
      }
    }
    table {
      font-variant-numeric: tabular-nums;
    }
    tfoot th {
      border-top: 2px solid var(--bordure);
    }
    /* Deux couleurs catégorielles validées (daltonisme, contraste) en clair et en sombre */
    :host {
      --serie-g: #2a78d6;
      --serie-f: #eb6834;
    }
    @media (prefers-color-scheme: dark) {
      :host {
        --serie-g: #3987e5;
        --serie-f: #d95926;
      }
    }
    .legende {
      display: inline-flex;
      gap: 1rem;
      font-size: 0.85rem;
      color: var(--texte-doux);
      i {
        display: inline-block;
        width: 0.75rem;
        height: 0.75rem;
        border-radius: 3px;
        margin-right: 0.35rem;
        vertical-align: -1px;
      }
    }
    .pastille-g,
    .seg-g {
      background: var(--serie-g);
    }
    .pastille-f,
    .seg-f {
      background: var(--serie-f);
    }
    .barre-col {
      min-width: 9rem;
    }
    .barre {
      display: flex;
      gap: 2px;
      height: 12px;
      span {
        min-width: 3px;
        flex-basis: 0;
      }
      span:first-child {
        border-radius: 4px 0 0 4px;
      }
      span:last-child {
        border-radius: 0 4px 4px 0;
      }
      span:only-child {
        border-radius: 4px;
      }
    }
    .jauge {
      display: inline-block;
      width: 5rem;
      height: 8px;
      border-radius: 4px;
      background: var(--bordure);
      vertical-align: middle;
      margin-right: 0.5rem;
      overflow: hidden;
      span {
        display: block;
        height: 100%;
        background: var(--primaire);
        border-radius: 4px;
      }
    }
    .bascule {
      display: inline-flex;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      padding: 0.2rem;
      background: var(--surface);
      button {
        min-height: 36px;
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
    .deux-colonnes {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
      gap: 1rem;
      align-items: start;
    }
    .rouge {
      color: var(--absent);
      font-weight: 600;
    }
  `,
})
export class StatistiquesPage {
  private readonly api = inject(AdminApi);
  protected readonly annee = inject(AnneeCourante);

  protected readonly action = new Action();
  protected readonly telechargement = new Action();
  protected readonly total = total;
  protected readonly fcfa = fcfa;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly libelleDecision = LIBELLE_DECISION;

  protected readonly rapport = signal<RapportStatistiques | null>(null);
  protected readonly vueAges = signal<Vue>('total');

  protected readonly ages = computed(() => pivotAges(this.rapport()?.ages ?? []));
  private readonly maxEffectif = computed(() => Math.max(1, ...(this.rapport()?.effectifs ?? []).map((e) => total(e.effectif))));
  protected readonly totalRedoublants = computed(() => (this.rapport()?.effectifs ?? []).reduce((t, e) => t + total(e.redoublants), 0));

  /** Seules les décisions présentes dans l'établissement ont une colonne. */
  protected readonly decisions = computed(() => {
    const presentes = new Set((this.rapport()?.resultats ?? []).flatMap((n) => Object.keys(n.parDecision) as Decision[]));
    return DECISIONS.filter((d) => presentes.has(d));
  });

  protected readonly administratif = computed(() =>
    Object.entries(this.rapport()?.personnel.administratif ?? {})
      .filter(([, n]) => (n ?? 0) > 0)
      .map(([role, nombre]) => ({ role, libelle: LIBELLE_ROLE[role as keyof typeof LIBELLE_ROLE] ?? role, nombre: nombre ?? 0 })),
  );

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
    await this.action.executer(async () => {
      const r = await this.api.statistiques(anneeId);
      if (anneeId === this.annee.annee()?.id) {
        this.rapport.set(r);
      }
    });
  }

  protected largeur(n: number): number {
    return Math.round((n / this.maxEffectif()) * 100);
  }

  protected valeur(c: Compte | undefined): number {
    if (!c) {
      return 0;
    }
    const vue = this.vueAges();
    return vue === 'garcons' ? c.garcons : vue === 'filles' ? c.filles : c.garcons + c.filles;
  }

  protected gf(c: Compte): string {
    const t = total(c);
    return t ? `${t} (${c.garcons} G, ${c.filles} F)` : '0';
  }

  protected pourcentage(taux: number | null): string {
    return taux === null ? '—' : `${String(taux).replace('.', ',')} %`;
  }

  protected aller(e: Event, id: string): void {
    e.preventDefault();
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  protected async telecharger(): Promise<void> {
    const r = this.rapport();
    if (!r) {
      return;
    }
    const blob = await this.telechargement.executer(() => this.api.classeurStatistiques(r.annee.id));
    if (blob) {
      const url = URL.createObjectURL(blob);
      const lien = document.createElement('a');
      lien.href = url;
      lien.download = `statistiques-${r.annee.libelle}.xlsx`;
      lien.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
  }
}
