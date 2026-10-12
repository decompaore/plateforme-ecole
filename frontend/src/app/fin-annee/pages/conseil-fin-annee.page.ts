import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AdminNavComponent } from '../../admin/admin-nav.component';
import { Decision, LIBELLE_DECISION } from '../../admin/modeles-admin';
import { SessionService } from '../../core/session.service';
import { FinAnneeApi } from '../fin-annee-api.service';
import {
  DECISION_ELEVE,
  DECISIONS_CONSEIL,
  DecisionsClasseVue,
  DecisionVue,
  moyenne,
  ResultatExamen,
  SaisieDecision,
} from '../modeles-fin-annee';

interface Ligne {
  d: DecisionVue;
  decision: Decision;
  motif: string;
  examen: ResultatExamen | '';
}

/** Une ligne a-t-elle besoin d'un motif (décision différente de la proposition) ? */
export function motifManquant(l: { decision: Decision; motif: string; d: { proposition: Decision } }): boolean {
  return l.decision !== l.d.proposition && !l.motif.trim();
}

/**
 * Conseil de classe de fin d'année (v0.45) : moyennes annuelles et propositions de la plateforme, résultats
 * de l'examen national, décisions du conseil (motif obligatoire pour s'écarter de la proposition), puis
 * validation définitive avec SMS aux familles.
 */
@Component({
  selector: 'app-conseil-fin-annee',
  imports: [FormsModule, RouterLink, AdminNavComponent],
  template: `
    <div class="page large">
      <a routerLink="/admin/fin-annee" class="bouton discret retour">‹ Fin d'année</a>
      <h1>Conseil de fin d'année @if (vue(); as v) { · {{ v.classeCode }} }</h1>
      <app-admin-nav />

      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      @if (vue(); as v) {
        <section class="carte">
          <div class="entete-section">
            <h2>{{ v.eleves.length }} élève(s)@if (v.examen) { · classe d'examen : {{ v.examen }} }</h2>
            @if (v.validees) {
              <span class="pastille present">décisions validées</span>
            } @else if (v.eleves.length) {
              <span class="pastille">en cours</span>
            }
          </div>
          @if (repartition().length) {
            <p class="doux">
              @for (r of repartition(); track r.decision; let dernier = $last) {
                {{ libelles[r.decision] }} : <strong>{{ r.nombre }}</strong>@if (!dernier) { · }
              }
            </p>
          }

          @if (!v.eleves.length) {
            <p class="doux">
              Les propositions n'ont pas encore été calculées. Toutes les périodes de la classe doivent être verrouillées.
            </p>
            @if (direction()) {
              <button type="button" class="bouton" [disabled]="action.enCours()" (click)="calculer()">Calculer les propositions</button>
            }
          } @else {
            @if (!v.validees && direction()) {
              <p class="doux">
                La plateforme propose une décision d'après la moyenne annuelle et les règles de l'établissement. Le conseil
                peut la changer, avec un motif.@if (v.examen) { Saisissez d'abord le résultat de l'examen de chaque élève. }
              </p>
            }
            <div class="tableau-defilant">
              <table class="tableau">
                <thead>
                  <tr>
                    <th class="nombre">Rang</th>
                    <th>Élève</th>
                    <th class="nombre">{{ competences() ? 'Maîtrise' : 'Moy. ann.' }}</th>
                    @if (v.examen) { <th>{{ v.examen }}</th> }
                    <th>Proposition</th>
                    <th>Décision du conseil</th>
                    <th>Motif</th>
                  </tr>
                </thead>
                <tbody>
                  @for (l of lignes(); track l.d.inscriptionId; let i = $index) {
                    <tr [class.modifiee]="l.decision !== l.d.proposition">
                      <td class="nombre">{{ l.d.rang ?? '' }}</td>
                      <td>
                        <strong>{{ l.d.nom }}</strong> {{ l.d.prenoms }}
                        @if (l.d.redoublements > 0) { <span class="pastille">{{ l.d.redoublements }} redoublement(s)</span> }
                      </td>
                      <td class="nombre">{{ competences() ? (l.d.tauxMaitrise ?? '—') + ' %' : moyenne(l.d.moyenneAnnuelle) }}</td>
                      @if (v.examen) {
                        <td>
                          @if (modifiable()) {
                            <select [ngModel]="l.examen" (ngModelChange)="l.examen = $event; saisi()" [name]="'ex' + i" [attr.aria-label]="'Résultat de ' + l.d.nom">
                              <option value="">—</option>
                              <option value="ADMIS">Admis</option>
                              <option value="AJOURNE">Ajourné</option>
                            </select>
                          } @else {
                            {{ l.d.resultatExamen === 'ADMIS' ? 'Admis' : l.d.resultatExamen === 'AJOURNE' ? 'Ajourné' : '—' }}
                          }
                        </td>
                      }
                      <td>{{ eleve[l.d.proposition] }}</td>
                      <td>
                        @if (modifiable() && l.d.proposition !== 'EN_ATTENTE_EXAMEN') {
                          <select [ngModel]="l.decision" (ngModelChange)="l.decision = $event; saisi()" [name]="'dec' + i" [attr.aria-label]="'Décision pour ' + l.d.nom">
                            @for (d of decisionsConseil; track d) { <option [value]="d">{{ eleve[d] }}</option> }
                          </select>
                        } @else {
                          <strong>{{ eleve[l.d.decision] }}</strong>
                        }
                      </td>
                      <td>
                        @if (modifiable() && l.decision !== l.d.proposition) {
                          <input [ngModel]="l.motif" (ngModelChange)="l.motif = $event; saisi()" [name]="'motif' + i" maxlength="300" placeholder="Motif obligatoire"
                            [class.manquant]="motifManquant(l)" [attr.aria-label]="'Motif pour ' + l.d.nom" />
                        } @else {
                          <span class="doux">{{ l.d.motif }}</span>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>

            @if (modifiable()) {
              <div class="actions">
                <button type="button" class="bouton" [disabled]="action.enCours() || motifsManquants() > 0" (click)="enregistrer()">
                  Enregistrer les décisions
                </button>
                <button type="button" class="bouton secondaire" [disabled]="action.enCours()" (click)="calculer()">Recalculer</button>
                @if (confirmation()) {
                  <span class="confirmation" role="alertdialog" aria-label="Valider les décisions">
                    <span>Valider ? Les décisions deviennent définitives et chaque famille reçoit un SMS.</span>
                    <button type="button" class="bouton secondaire petit" (click)="confirmation.set(false)">Annuler</button>
                    <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="valider()">Confirmer la validation</button>
                  </span>
                } @else {
                  <button type="button" class="bouton secondaire" [disabled]="action.enCours() || aEnregistrer() > 0" (click)="confirmation.set(true)">
                    Valider les décisions
                  </button>
                }
              </div>
              @if (motifsManquants() > 0) {
                <p class="alerte attention">{{ motifsManquants() }} décision(s) modifiée(s) sans motif.</p>
              } @else if (aEnregistrer() > 0) {
                <p class="doux">{{ aEnregistrer() }} modification(s) à enregistrer avant de valider.</p>
              }
            }
          }
        </section>
      }
    </div>
  `,
  styles: `
    .retour {
      margin-left: -0.5rem;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-top: 1rem;
    }
    .confirmation {
      display: inline-flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
    }
    tr.modifiee td {
      background: var(--retard-fond);
    }
    input.manquant {
      border-color: var(--absent);
    }
    select,
    input {
      min-height: 36px;
      width: auto;
    }
  `,
})
export class ConseilFinAnneePage implements OnInit {
  readonly classeId = input.required<string>();

  private readonly api = inject(FinAnneeApi);
  private readonly session = inject(SessionService);

  protected readonly libelles = LIBELLE_DECISION;
  protected readonly eleve = DECISION_ELEVE;
  protected readonly decisionsConseil = DECISIONS_CONSEIL;
  protected readonly moyenne = moyenne;
  protected readonly motifManquant = motifManquant;
  protected readonly action = new Action();
  protected readonly vue = signal<DecisionsClasseVue | null>(null);
  protected readonly lignes = signal<Ligne[]>([]);
  protected readonly message = signal<string | null>(null);
  protected readonly confirmation = signal(false);
  /** Incrémenté à chaque saisie : les lignes sont modifiées en place, les compteurs se recalculent. */
  private readonly saisies = signal(0);

  protected readonly direction = computed(() => this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR'));
  protected readonly modifiable = computed(() => this.direction() && !!this.vue() && !this.vue()!.validees);
  protected readonly competences = computed(() => {
    const e = this.vue()?.eleves ?? [];
    return e.length > 0 && e.every((x) => x.moyenneAnnuelle === null) && e.some((x) => x.tauxMaitrise !== null);
  });
  protected readonly repartition = computed(() =>
    Object.entries(this.vue()?.repartition ?? {}).map(([decision, nombre]) => ({ decision: decision as Decision, nombre })),
  );
  protected readonly motifsManquants = computed(() => {
    this.saisies();
    return this.lignes().filter((l) => motifManquant(l)).length;
  });
  protected readonly aEnregistrer = computed(() => {
    this.saisies();
    return this.modifications().length;
  });

  async ngOnInit(): Promise<void> {
    const v = await this.action.executer(() => this.api.decisions(this.classeId()));
    if (v) {
      this.afficher(v);
    }
  }

  protected saisi(): void {
    this.saisies.update((n) => n + 1);
  }

  protected async calculer(): Promise<void> {
    this.message.set(null);
    const v = await this.action.executer(() => this.api.calculer(this.classeId()));
    if (v) {
      this.afficher(v);
      this.message.set('Propositions calculées. Le conseil peut maintenant décider.');
    }
  }

  /** Lignes modifiées : résultat d'examen ou décision (avec motif). */
  private modifications(): SaisieDecision[] {
    const r: SaisieDecision[] = [];
    for (const l of this.lignes()) {
      const examen = l.examen || null;
      const s: SaisieDecision = { inscriptionId: l.d.inscriptionId };
      let change = false;
      if (examen !== l.d.resultatExamen && examen !== null) {
        s.resultatExamen = examen;
        change = true;
      }
      if (l.d.proposition !== 'EN_ATTENTE_EXAMEN' && (l.decision !== l.d.decision || (l.motif.trim() || null) !== (l.d.motif ?? null))) {
        s.decision = l.decision;
        s.motif = l.motif.trim() || null;
        change = true;
      }
      if (change) {
        r.push(s);
      }
    }
    return r;
  }

  protected async enregistrer(): Promise<void> {
    const m = this.modifications();
    if (m.length === 0) {
      this.message.set('Rien à enregistrer.');
      return;
    }
    this.message.set(null);
    // Les résultats d'examen d'abord : ils changent la proposition
    const examens = m.filter((x) => x.resultatExamen).map((x) => ({ inscriptionId: x.inscriptionId, resultatExamen: x.resultatExamen }));
    const decisions = m.filter((x) => x.decision).map((x) => ({ inscriptionId: x.inscriptionId, decision: x.decision, motif: x.motif }));
    const v = await this.action.executer(async () => {
      let r = examens.length ? await this.api.modifier(this.classeId(), examens) : null;
      if (decisions.length) {
        r = await this.api.modifier(this.classeId(), decisions);
      }
      return r!;
    });
    if (v) {
      this.afficher(v);
      this.message.set(`${m.length} élève(s) mis à jour.`);
    }
  }

  protected async valider(): Promise<void> {
    const v = await this.action.executer(() => this.api.valider(this.classeId()));
    this.confirmation.set(false);
    if (v) {
      this.afficher(v);
      this.message.set('Décisions validées : les familles sont prévenues par SMS. La classe entre dans le plan de passage.');
    }
  }

  private afficher(v: DecisionsClasseVue): void {
    this.vue.set(v);
    this.lignes.set(v.eleves.map((d) => ({ d, decision: d.decision, motif: d.motif ?? '', examen: d.resultatExamen ?? '' })));
    this.saisies.update((n) => n + 1);
  }
}
