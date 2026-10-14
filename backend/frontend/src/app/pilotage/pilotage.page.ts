import { LowerCasePipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import { Action } from '../admin/action';
import { PaysVue } from '../admin/modeles-territoire';
import { PlateformeNavComponent } from '../admin/plateforme-nav.component';
import { TerritoireApi } from '../admin/territoire-api.service';
import { filtrer } from '../core/recherche';
import { SessionService } from '../core/session.service';
import { RechercheComponent } from '../partage/recherche.component';
import { PilotageApi } from './pilotage-api.service';
import { Compte, Indicateurs, LigneEtablissement, TableauPilotage } from './modeles-pilotage';
import { arbreTerritoire, basculer as basculerGroupe, ouvertsParDefaut } from '../partage/arbre-territoire';

/** « 66,7 % », ou un tiret si rien n'est mesuré. */
export function pourcent(taux: number | null | undefined): string {
  return taux === null || taux === undefined ? '—' : `${String(taux).replace('.', ',')} %`;
}

/** Part des filles dans un effectif. */
export function partFilles(c: Compte): string {
  return c.total ? pourcent(Math.round((c.filles / c.total) * 1000) / 10) : '—';
}

/** Noms des examens présents dans le périmètre, dans l'ordre de la synthèse. */
export function nomsExamens(t: TableauPilotage): string[] {
  return t.synthese.examens.map((e) => e.examen);
}

/** Taux de réussite d'un examen pour une ligne (direction ou établissement). */
export function tauxExamen(i: Indicateurs, examen: string): string {
  return pourcent(i.examens.find((e) => e.examen === examen)?.taux ?? null);
}

/** Cumul d'un groupe d'établissements (une direction de l'arbre) : sommes et taux pondérés. */
export function cumul(lignes: readonly LigneEtablissement[]): {
  eleves: Compte; classes: number; enseignants: number; tauxAdmission: number | null;
  examens: Record<string, number | null>; actifs: number; comptes: number;
} {
  let garcons = 0, filles = 0, classes = 0, enseignants = 0, decides = 0, admis = 0, actifs = 0, comptes = 0;
  const parExamen: Record<string, { admis: number; resultats: number }> = {};
  for (const l of lignes) {
    const i = l.indicateurs;
    garcons += i.eleves.garcons;
    filles += i.eleves.filles;
    classes += i.classes;
    enseignants += i.enseignants.total;
    decides += i.decides.total;
    admis += i.admis.total;
    actifs += i.utilisation.actifs;
    comptes += i.utilisation.comptes;
    for (const x of i.examens) {
      const e = (parExamen[x.examen] ??= { admis: 0, resultats: 0 });
      e.admis += x.admis.total;
      e.resultats += x.resultats.total;
    }
  }
  const taux = (p: number, t: number) => (t ? Math.round((p / t) * 1000) / 10 : null);
  const examens: Record<string, number | null> = {};
  for (const [nom, e] of Object.entries(parExamen)) {
    examens[nom] = taux(e.admis, e.resultats);
  }
  return { eleves: { garcons, filles, total: garcons + filles }, classes, enseignants, tauxAdmission: taux(admis, decides), examens, actifs, comptes };
}

/**
 * Tableau de bord de pilotage : une direction (régionale, provinciale…) voit les nombres de
 * chaque établissement de son ressort et leurs cumuls par direction ; l'administrateur pays et
 * le super administrateur voient un pays entier et peuvent descendre vers chaque direction.
 * Aucune donnée d'élève : uniquement des nombres.
 */
@Component({
  selector: 'app-pilotage',
  imports: [LowerCasePipe, PlateformeNavComponent, RechercheComponent],
  template: `
    <div class="page large">
      <h1>Pilotage</h1>
      <app-plateforme-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      <div class="barre-outils">
        @if (superAdmin && pays().length > 1) {
          <label class="visuellement-cache" for="pilotagePays">Pays</label>
          <select id="pilotagePays" [value]="paysId() ?? ''" (change)="choisirPays($any($event.target).value)">
            @for (p of pays(); track p.id) { <option [value]="p.id" [selected]="p.id === paysId()">{{ p.nom }}</option> }
          </select>
        }
        @if (tableau(); as t) {
          @if (t.annees.length) {
            <label class="visuellement-cache" for="pilotageAnnee">Année scolaire</label>
            <select id="pilotageAnnee" (change)="choisirAnnee($any($event.target).value)">
              @for (a of t.annees; track a) { <option [value]="a" [selected]="a === t.annee">{{ a }}</option> }
            </select>
          }
          <span class="espace"></span>
          <button type="button" class="bouton secondaire petit" [disabled]="export.enCours()" (click)="exporter('xlsx')">Excel</button>
          <button type="button" class="bouton secondaire petit" [disabled]="export.enCours()" (click)="exporter('pdf')">PDF</button>
        }
      </div>
      @if (export.erreur()) {
        <div class="alerte erreur" role="alert">{{ export.erreur() }}</div>
      }

      @if (tableau(); as t) {
        <section class="perimetre">
          @if (t.parent; as p) {
            <button type="button" class="bouton discret petit" (click)="remonter()">↑ {{ p.nom }}</button>
          }
          <h2>
            @if (t.perimetre.niveau && t.perimetre.type === 'DIRECTION') { <span class="doux">{{ t.perimetre.niveau }} ·&nbsp;</span> }{{ t.perimetre.nom }}
          </h2>
          @if (t.perimetre.type === 'DIRECTION') { <p class="doux">{{ t.perimetre.chemin }}</p> }
          @if (!t.annee) {
            <p class="doux">Aucune année scolaire n'est encore enregistrée dans les établissements de ce ressort.</p>
          }
        </section>

        <div class="chiffres">
          <div><strong>{{ t.synthese.etablissements }}</strong><span>établissement(s)</span></div>
          <div>
            <strong>{{ t.synthese.eleves.total }}</strong>
            <span>élèves dont {{ t.synthese.eleves.filles }} filles ({{ partFilles(t.synthese.eleves) }}) · {{ t.synthese.classes }} classes</span>
          </div>
          <div>
            <strong>{{ t.synthese.enseignants.total }}</strong>
            <span>enseignants ({{ t.synthese.titulaires.total }} titulaires, {{ t.synthese.vacataires.total }} vacataires)
              @if (t.synthese.elevesParEnseignant !== null) { · {{ moyenne(t.synthese.elevesParEnseignant) }} élèves par enseignant }</span>
          </div>
          <div>
            <strong>{{ pourcent(t.synthese.tauxAdmission) }}</strong>
            <span>d'admis en fin d'année ({{ t.synthese.admis.total }} / {{ t.synthese.decides.total }} décisions validées)</span>
          </div>
          @for (e of t.synthese.examens; track e.examen) {
            <div>
              <strong>{{ pourcent(e.taux) }}</strong>
              <span>de réussite au {{ e.examen }} ({{ e.admis.total }} / {{ e.resultats.total }} résultats connus)</span>
            </div>
          }
          <div>
            <strong>{{ t.synthese.utilisation.actifs }} / {{ t.synthese.utilisation.comptes }}</strong>
            <span>comptes actifs sur 30 jours</span>
          </div>
        </div>

        @if (t.synthese.examens.length) {
          <section class="carte">
            <h3>Examens de fin d'études</h3>
            <div class="tableau-defilant">
              <table class="tableau">
                <thead>
                  <tr>
                    <th>Examen</th><th class="nombre">Candidats</th><th class="nombre">Résultats connus</th><th class="nombre">Admis</th>
                    <th class="nombre">Taux</th><th class="nombre">Garçons</th><th class="nombre">Filles</th>
                  </tr>
                </thead>
                <tbody>
                  @for (e of t.synthese.examens; track e.examen) {
                    <tr>
                      <td><strong>{{ e.examen }}</strong></td>
                      <td class="nombre">{{ e.candidats.total }}</td>
                      <td class="nombre">{{ e.resultats.total }}</td>
                      <td class="nombre">{{ e.admis.total }}</td>
                      <td class="nombre"><strong>{{ pourcent(e.taux) }}</strong></td>
                      <td class="nombre">{{ pourcent(e.tauxGarcons) }}</td>
                      <td class="nombre">{{ pourcent(e.tauxFilles) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            <p class="doux petit-texte">Taux de réussite = admis / résultats connus (saisis par les établissements).</p>
          </section>
        }

        @if (t.synthese.periodes.length) {
          <section class="carte">
            <h3>Moyennes par période</h3>
            <div class="tableau-defilant">
              <table class="tableau">
                <thead>
                  <tr><th>Période</th><th class="nombre">Bulletins publiés</th><th class="nombre">Moyenne</th><th class="nombre">À la moyenne</th><th class="nombre">Taux</th></tr>
                </thead>
                <tbody>
                  @for (p of t.synthese.periodes; track p.libelle) {
                    <tr>
                      <td>{{ p.libelle }}</td>
                      <td class="nombre">{{ p.bulletins.total }}</td>
                      <td class="nombre">{{ moyenne(p.moyenne) }}</td>
                      <td class="nombre">{{ p.admis.total }}</td>
                      <td class="nombre">{{ pourcent(p.taux) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }

        @if (t.synthese.niveaux.length) {
          <section class="carte">
            <h3>Effectifs et fin d'année par niveau</h3>
            <div class="tableau-defilant">
              <table class="tableau">
                <thead>
                  <tr>
                    <th>Niveau</th><th class="nombre">Classes</th><th class="nombre">Garçons</th><th class="nombre">Filles</th>
                    <th class="nombre">Redoublants</th><th class="nombre">Décisions</th><th class="nombre">Admis</th><th class="nombre">Taux</th>
                  </tr>
                </thead>
                <tbody>
                  @for (n of t.synthese.niveaux; track n.niveau) {
                    <tr>
                      <td>{{ n.niveau }}</td>
                      <td class="nombre">{{ n.classes }}</td>
                      <td class="nombre">{{ n.eleves.garcons }}</td>
                      <td class="nombre">{{ n.eleves.filles }}</td>
                      <td class="nombre">{{ n.redoublants.total }}</td>
                      <td class="nombre">{{ n.decides.total }}</td>
                      <td class="nombre">{{ n.admis.total }}</td>
                      <td class="nombre">{{ pourcent(n.tauxAdmission) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }

        @if (t.directions.length) {
          <section class="carte">
            <h3>{{ t.niveauDirections ?? 'Directions' }}</h3>
            <div class="tableau-defilant">
              <table class="tableau">
                <thead>
                  <tr>
                    <th>Direction</th><th class="nombre">Établissements</th><th class="nombre">Élèves</th><th class="nombre">Filles</th>
                    <th class="nombre">Enseignants</th><th class="nombre">Admission</th>
                    @for (x of examens(); track x) { <th class="nombre">{{ x }}</th> }
                    <th class="nombre">Utilisation</th><th></th>
                  </tr>
                </thead>
                <tbody>
                  @for (d of t.directions; track d.id) {
                    <tr>
                      <td><strong>{{ d.nom }}</strong></td>
                      <td class="nombre">{{ d.indicateurs.etablissements }}</td>
                      <td class="nombre">{{ d.indicateurs.eleves.total }}</td>
                      <td class="nombre">{{ partFilles(d.indicateurs.eleves) }}</td>
                      <td class="nombre">{{ d.indicateurs.enseignants.total }}</td>
                      <td class="nombre">{{ pourcent(d.indicateurs.tauxAdmission) }}</td>
                      @for (x of examens(); track x) { <td class="nombre">{{ tauxExamen(d.indicateurs, x) }}</td> }
                      <td class="nombre">{{ d.indicateurs.utilisation.actifs }} / {{ d.indicateurs.utilisation.comptes }}</td>
                      <td><button type="button" class="bouton discret petit" (click)="ouvrir(d.id)">Ouvrir</button></td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }

        <section class="carte">
          <h3>Établissements</h3>
          <div class="filtres">
            <app-recherche libelle="Rechercher un établissement (nom ou code)" [(valeur)]="recherche"
              [total]="t.etablissements.length" [trouves]="affiches().length" />
            @if (t.directions.length) {
              <label class="visuellement-cache" for="pilotageSous">{{ t.niveauDirections ?? 'Direction' }}</label>
              <select id="pilotageSous" (change)="sousDirection.set($any($event.target).value || null)">
                <option value="">Toutes : {{ (t.niveauDirections ?? 'directions') | lowercase }}</option>
                @for (d of t.directions; track d.id) { <option [value]="d.id" [selected]="d.id === sousDirection()">{{ d.nom }}</option> }
              </select>
            }
            <div class="vue" role="group" aria-label="Présentation">
              <button type="button" class="bouton petit" [class.secondaire]="vue() !== 'territoire'" [attr.aria-pressed]="vue() === 'territoire'" (click)="vue.set('territoire')">Par territoire</button>
              <button type="button" class="bouton petit" [class.secondaire]="vue() !== 'liste'" [attr.aria-pressed]="vue() === 'liste'" (click)="vue.set('liste')">Liste</button>
              @if (vue() === 'territoire' && arbre().cles.length) {
                <button type="button" class="bouton discret petit" (click)="toutDeplier()">Tout déplier</button>
                <button type="button" class="bouton discret petit" (click)="ouverts.set(vide)">Tout replier</button>
              }
            </div>
          </div>
          @if (vue() === 'territoire' && arbre().prefixe.length) {
            <p class="doux petit-texte">{{ arbre().prefixe.join(' · ') }}</p>
          }
          <div class="tableau-defilant">
            <table class="tableau">
              <thead>
                <tr>
                  <th>Établissement</th><th class="nombre">Élèves</th><th class="nombre">Filles</th><th class="nombre">Classes</th>
                  <th class="nombre">Enseignants</th><th class="nombre">Admission</th>
                  @for (x of examens(); track x) { <th class="nombre">{{ x }}</th> }
                  <th class="nombre">Utilisation</th><th></th>
                </tr>
              </thead>
              <tbody>
                @for (l of lignes(); track l.cle) {
                  @if (l.groupe; as g) {
                    @let c = cumul(g.elements);
                    <tr class="groupe">
                      <td [style.padding-left.rem]="0.5 + l.profondeur * 1.25">
                        <button type="button" class="replier" [attr.aria-expanded]="g.ouvert" (click)="replier(g.cle)">
                          <span aria-hidden="true">{{ g.ouvert ? '▾' : '▸' }}</span>&nbsp;<strong>{{ g.nom }}</strong>
                        </button>
                        <span class="doux petit-texte">&nbsp;· {{ g.elements.length }} établissement(s)</span>
                      </td>
                      <td class="nombre">{{ c.eleves.total }}</td>
                      <td class="nombre">{{ partFilles(c.eleves) }}</td>
                      <td class="nombre">{{ c.classes }}</td>
                      <td class="nombre">{{ c.enseignants }}</td>
                      <td class="nombre">{{ pourcent(c.tauxAdmission) }}</td>
                      @for (x of examens(); track x) { <td class="nombre">{{ pourcent(c.examens[x] ?? null) }}</td> }
                      <td class="nombre">{{ c.actifs }} / {{ c.comptes }}</td>
                      <td></td>
                    </tr>
                  } @else {
                  @let e = l.element!;
                  <tr>
                    <td [style.padding-left.rem]="0.5 + l.profondeur * 1.25">
                      <strong>{{ e.nom }}</strong>
                      @if (e.statut === 'SUSPENDU') { <span class="pastille">Suspendu</span> }
                      <br /><span class="doux petit-texte">{{ e.direction }}</span>
                    </td>
                    <td class="nombre">{{ e.indicateurs.eleves.total }}</td>
                    <td class="nombre">{{ partFilles(e.indicateurs.eleves) }}</td>
                    <td class="nombre">{{ e.indicateurs.classes }}</td>
                    <td class="nombre">{{ e.indicateurs.enseignants.total }}</td>
                    <td class="nombre">{{ pourcent(e.indicateurs.tauxAdmission) }}</td>
                    @for (x of examens(); track x) { <td class="nombre">{{ tauxExamen(e.indicateurs, x) }}</td> }
                    <td class="nombre">{{ e.indicateurs.utilisation.actifs }} / {{ e.indicateurs.utilisation.comptes }}</td>
                    <td><button type="button" class="bouton discret petit" [attr.aria-expanded]="detail() === e.id" (click)="basculer(e)">Détail</button></td>
                  </tr>
                  @if (detail() === e.id) {
                    <tr class="detail">
                      <td [attr.colspan]="8 + examens().length">
                        <p class="doux">
                          {{ e.indicateurs.eleves.garcons }} garçons, {{ e.indicateurs.eleves.filles }} filles ·
                          {{ e.indicateurs.redoublants.total }} redoublant(s) · {{ e.indicateurs.titulaires.total }} titulaire(s),
                          {{ e.indicateurs.vacataires.total }} vacataire(s)
                          @if (e.indicateurs.elevesParEnseignant !== null) { · {{ moyenne(e.indicateurs.elevesParEnseignant) }} élèves par enseignant }
                        </p>
                        <ul class="totaux">
                          @for (n of e.indicateurs.niveaux; track n.niveau) {
                            <li><strong>{{ n.niveau }}</strong>&nbsp;<span class="doux">{{ n.eleves.total }} élèves · admission {{ pourcent(n.tauxAdmission) }}</span></li>
                          }
                          @for (x of e.indicateurs.examens; track x.examen) {
                            <li><strong>{{ x.examen }}</strong>&nbsp;<span class="doux">{{ x.admis.total }} / {{ x.resultats.total }} admis ({{ x.candidats.total }} candidats)</span></li>
                          }
                          @for (p of e.indicateurs.periodes; track p.libelle) {
                            <li><strong>{{ p.libelle }}</strong>&nbsp;<span class="doux">moyenne {{ moyenne(p.moyenne) }} · {{ pourcent(p.taux) }} à la moyenne</span></li>
                          }
                        </ul>
                      </td>
                    </tr>
                  }
                  }
                } @empty {
                  <tr><td [attr.colspan]="8 + examens().length" class="doux">Aucun établissement.</td></tr>
                }
              </tbody>
            </table>
          </div>
          <p class="doux petit-texte">
            Année {{ t.annee ?? '—' }} · décisions de fin d'année validées par les conseils de classe · utilisation : personnes ayant
            ouvert l'application au cours des 30 derniers jours. Uniquement des nombres : les données des élèves restent dans leur établissement.
          </p>
        </section>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .barre-outils {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 1rem;
      select {
        flex: 0 1 16rem;
        min-width: 0;
      }
      .espace {
        flex: 1;
      }
    }
    .perimetre {
      margin-bottom: 1rem;
      h2 {
        font-size: 1.15rem;
        margin: 0.25rem 0;
      }
      p {
        margin: 0;
      }
    }
    h3 {
      font-size: 1rem;
      margin: 0 0 0.5rem;
    }
    .chiffres {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr));
      gap: 0.75rem;
      margin-bottom: 1rem;
      div {
        display: flex;
        flex-direction: column;
        padding: 0.75rem 1rem;
        border-radius: var(--rayon);
        background: var(--surface);
        border: 1px solid var(--bordure);
      }
      strong {
        font-size: 1.3rem;
        font-variant-numeric: tabular-nums;
      }
      span {
        font-size: 0.85rem;
        color: var(--texte-doux);
      }
    }
    .filtres {
      display: flex;
      flex-wrap: wrap;
      gap: 0 1rem;
      align-items: center;
      app-recherche {
        flex: 1 1 16rem;
        min-width: 0;
      }
      select {
        margin-bottom: 1rem;
        max-width: 100%;
      }
    }
    .totaux {
      display: flex;
      flex-wrap: wrap;
      gap: 0.3rem 1.2rem;
      list-style: none;
      margin: 0.5rem 0 0;
      padding: 0;
    }
    .detail td {
      background: var(--surface);
    }
    .vue {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      margin-bottom: 1rem;
    }
    tr.groupe td {
      background: var(--surface);
      font-variant-numeric: tabular-nums;
    }
    .replier {
      background: none;
      border: none;
      padding: 0;
      min-height: 0;
      font: inherit;
      color: inherit;
      cursor: pointer;
      text-align: left;
    }
    .petit-texte {
      font-size: 0.85rem;
    }
  `,
})
export class PilotagePage implements OnInit {
  private readonly api = inject(PilotageApi);
  private readonly territoire = inject(TerritoireApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly session = inject(SessionService);

  protected readonly superAdmin = this.session.profil()?.superAdmin === true;

  protected readonly pourcent = pourcent;
  protected readonly partFilles = partFilles;
  protected readonly tauxExamen = tauxExamen;

  protected readonly action = new Action();
  protected readonly export = new Action();
  protected readonly pays = signal<PaysVue[]>([]);
  protected readonly paysId = signal<string | null>(null);
  protected readonly directionId = signal<string | null>(null);
  protected readonly annee = signal<string | null>(null);
  protected readonly tableau = signal<TableauPilotage | null>(null);
  protected readonly recherche = signal('');
  protected readonly sousDirection = signal<string | null>(null);
  protected readonly detail = signal<string | null>(null);

  protected readonly examens = computed(() => {
    const t = this.tableau();
    return t ? nomsExamens(t) : [];
  });
  protected readonly cumul = cumul;
  protected readonly vide = new Set<string>();
  /** Présentation des établissements (v0.39) : arbre par direction, ou liste. */
  protected readonly vue = signal<'territoire' | 'liste'>('territoire');
  protected readonly ouverts = signal<Set<string>>(new Set());
  protected readonly arbre = computed(() =>
    arbreTerritoire(this.affiches(), (e) => e.rattachement, this.ouverts(), !!this.recherche().trim() || !!this.sousDirection()),
  );
  protected readonly lignes = computed(() =>
    this.vue() === 'liste' ? this.affiches().map((e) => ({ cle: e.id, profondeur: 0, element: e, groupe: undefined })) : this.arbre().lignes,
  );
  protected readonly affiches = computed(() =>
    filtrer(
      (this.tableau()?.etablissements ?? []).filter((e) => !this.sousDirection() || e.sousDirectionId === this.sousDirection()),
      this.recherche(),
      (e) => [e.nom, e.code, e.direction],
    ),
  );

  async ngOnInit(): Promise<void> {
    const q = this.route.snapshot.queryParamMap;
    this.directionId.set(q.get('direction'));
    this.paysId.set(q.get('pays'));
    this.annee.set(q.get('annee'));
    if (this.superAdmin) {
      const l = await this.action.executer(() => this.territoire.pays());
      if (l) {
        this.pays.set(l);
      }
    }
    await this.charger();
  }

  protected async choisirPays(id: string): Promise<void> {
    this.paysId.set(id || null);
    this.directionId.set(null);
    await this.charger();
  }

  protected async choisirAnnee(annee: string): Promise<void> {
    this.annee.set(annee || null);
    await this.charger();
  }

  protected async ouvrir(directionId: string): Promise<void> {
    this.directionId.set(directionId);
    await this.charger();
  }

  protected async remonter(): Promise<void> {
    const p = this.tableau()?.parent;
    if (!p) {
      return;
    }
    if (p.type === 'PAYS') {
      this.directionId.set(null);
      this.paysId.set(p.id);
    } else {
      this.directionId.set(p.id);
    }
    await this.charger();
  }

  protected replier(cle: string): void {
    this.ouverts.set(basculerGroupe(this.ouverts(), cle));
  }

  protected toutDeplier(): void {
    this.ouverts.set(new Set(this.arbre().cles));
  }

  protected basculer(e: LigneEtablissement): void {
    this.detail.set(this.detail() === e.id ? null : e.id);
  }

  protected moyenne(m: number | null): string {
    return m === null ? '—' : String(m).replace('.', ',');
  }

  protected async exporter(format: 'xlsx' | 'pdf'): Promise<void> {
    const t = this.tableau();
    const blob = await this.export.executer(() => this.api.exporter(this.filtre(), format));
    if (blob && t) {
      const url = URL.createObjectURL(blob);
      const lien = document.createElement('a');
      lien.href = url;
      lien.download = `pilotage-${t.annee ?? 'sans-annee'}.${format}`;
      lien.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
  }

  private filtre() {
    return { pays: this.directionId() ? null : this.paysId(), direction: this.directionId(), annee: this.annee() };
  }

  private async charger(): Promise<void> {
    this.detail.set(null);
    this.sousDirection.set(null);
    const t = await this.action.executer(() => this.api.tableau(this.filtre()));
    if (t) {
      this.tableau.set(t);
      this.ouverts.set(ouvertsParDefaut(t.etablissements, (e) => e.rattachement));
      this.annee.set(t.annee);
      if (t.perimetre.type === 'PAYS') {
        this.paysId.set(t.perimetre.id);
      }
      // L'adresse reflète ce qui est affiché (lien à partager, retour du navigateur)
      void this.router.navigate([], {
        relativeTo: this.route,
        queryParams: { direction: this.directionId(), pays: this.directionId() ? null : this.paysId(), annee: t.annee },
        replaceUrl: true,
      });
    }
  }
}
