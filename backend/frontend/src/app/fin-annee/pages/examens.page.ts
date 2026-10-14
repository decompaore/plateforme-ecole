import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { AdminNavComponent } from '../../admin/admin-nav.component';
import { SessionService } from '../../core/session.service';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import { FinAnneeApi } from '../fin-annee-api.service';
import { pourcent, ResultatExamenVue, SaisieHistorique, serieExamen, TableauExamensVue } from '../modeles-fin-annee';

/**
 * Examens nationaux (v0.46), à titre indicatif pour la direction : taux de réussite par examen et par année,
 * filles et garçons, détail par classe. Les années suivies dans la plateforme se calculent à partir des
 * résultats saisis au conseil de fin d'année ; le chef d'établissement saisit les années antérieures.
 */
@Component({
  selector: 'app-examens',
  imports: [FormsModule, AdminNavComponent],
  template: `
    <div class="page large">
      <h1>Examens nationaux</h1>
      <app-admin-nav />

      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }
      <p class="doux">
        Chiffres indicatifs. Pour les années suivies dans la plateforme, le taux se calcule à partir des résultats saisis
        élève par élève au conseil de fin d'année (admis / candidats dont le résultat est connu). Les années antérieures
        se saisissent à la main pour comparer.
      </p>

      @if (tableau(); as t) {
        @if (t.examens.length === 0) {
          <section class="carte">
            <p>
              Aucun examen pour l'instant. Indiquez l'examen préparé par chaque classe (Fin d'année › Réglages), puis saisissez
              les résultats au conseil ; ou saisissez ci-dessous les résultats des années passées.
            </p>
          </section>
        }

        <!-- Synthèse : un bloc par examen, la dernière année et l'évolution -->
        <div class="syntheses">
          @for (s of syntheses(); track s.examen) {
            <section class="carte synthese" [attr.aria-label]="'Examen ' + s.examen">
              <h2>{{ s.examen }}</h2>
              <p class="chiffre">{{ pourcent(s.derniere.taux) }}</p>
              <p class="doux">
                {{ s.derniere.annee }} · {{ s.derniere.admis }} admis sur {{ s.derniere.resultatsConnus }}
                @if (s.derniere.resultatsConnus < s.derniere.inscrits) { ({{ s.derniere.inscrits - s.derniere.resultatsConnus }} résultat(s) en attente) }
              </p>
              @if (s.ecart !== null) {
                <p [class.hausse]="s.ecart > 0" [class.baisse]="s.ecart < 0">
                  {{ s.ecart > 0 ? '▲ +' : s.ecart < 0 ? '▼ ' : '= ' }}{{ s.ecart.toString().replace('.', ',') }} point(s) par rapport à l'année précédente
                </p>
              }
              @if (s.derniere.tauxFilles !== null) {
                <p class="doux">Filles {{ pourcent(s.derniere.tauxFilles) }} · Garçons {{ pourcent(s.derniere.tauxGarcons) }}</p>
              }
              @if (s.serie.length > 1) {
                <div class="barres" role="img" [attr.aria-label]="'Taux de réussite au ' + s.examen + ' par année'">
                  @for (p of s.serie; track p.annee) {
                    <div class="barre" [title]="p.annee + ' : ' + pourcent(p.taux)">
                      <span class="valeur">{{ p.taux === null ? '—' : arrondi(p.taux) + ' %' }}</span>
                      <span class="colonne"><span class="remplissage" [style.height.%]="p.taux ?? 0"></span></span>
                      <span class="annee">{{ p.annee.slice(2, 4) }}-{{ p.annee.slice(7) }}</span>
                    </div>
                  }
                </div>
              }
            </section>
          }
        </div>

        <!-- Détail -->
        @if (t.resultats.length) {
          <section class="carte">
            <div class="entete-section">
              <h2>Toutes les années</h2>
              <div class="actions-ligne">
                <button type="button" class="bouton secondaire petit" [disabled]="actionExport.enCours()" (click)="exporter('xlsx')">Excel</button>
                <button type="button" class="bouton secondaire petit" [disabled]="actionExport.enCours()" (click)="exporter('pdf')">PDF</button>
              </div>
            </div>
            <div class="tableau-defilant">
              <table class="tableau">
                <thead>
                  <tr>
                    <th>Année</th><th>Examen</th><th class="nombre">Candidats</th><th class="nombre">Admis</th>
                    <th class="nombre">Taux</th><th class="nombre">Filles</th><th class="nombre">Garçons</th><th>Source</th><th></th>
                  </tr>
                </thead>
                <tbody>
                  @for (r of t.resultats; track r.annee + r.examen) {
                    <tr>
                      <td>{{ r.annee }}</td>
                      <td><strong>{{ r.examen }}</strong></td>
                      <td class="nombre">{{ r.resultatsConnus }}@if (r.resultatsConnus < r.inscrits) { <span class="doux"> / {{ r.inscrits }}</span> }</td>
                      <td class="nombre">{{ r.admis }}</td>
                      <td class="nombre"><strong>{{ pourcent(r.taux) }}</strong></td>
                      <td class="nombre">{{ pourcent(r.tauxFilles) }}</td>
                      <td class="nombre">{{ pourcent(r.tauxGarcons) }}</td>
                      <td>
                        @if (r.source === 'CALCULE') { <span class="pastille present">plateforme</span> } @else { <span class="pastille">saisie</span> }
                      </td>
                      <td class="actions-cellule">
                        @if (r.source === 'CALCULE' && r.classes.length) {
                          <button type="button" class="bouton discret petit" (click)="basculer(r)">{{ ouvert() === cle(r) ? 'Masquer' : 'Classes' }}</button>
                        }
                        @if (r.source === 'SAISI' && chef()) {
                          <button type="button" class="bouton discret petit" (click)="editer(r)">Modifier</button>
                          <button type="button" class="bouton discret petit" (click)="supprimer(r)">Supprimer</button>
                        }
                      </td>
                    </tr>
                    @if (ouvert() === cle(r)) {
                      @for (c of r.classes; track c.classeId) {
                        <tr class="classe">
                          <td></td><td>{{ c.classeCode }}</td>
                          <td class="nombre">{{ c.resultatsConnus }}@if (c.resultatsConnus < c.inscrits) { <span class="doux"> / {{ c.inscrits }}</span> }</td>
                          <td class="nombre">{{ c.admis }}</td>
                          <td class="nombre">{{ pourcent(c.taux) }}</td>
                          <td colspan="4"></td>
                        </tr>
                      }
                    }
                  }
                </tbody>
              </table>
            </div>
            <p class="doux">« / 30 » : candidats inscrits dont le résultat n'est pas encore saisi.</p>
          </section>
        }

        <!-- Saisie des années antérieures -->
        @if (chef()) {
          <section class="carte">
            <h2>{{ edition() ? 'Modifier les résultats saisis' : 'Saisir une année antérieure' }}</h2>
            <form (ngSubmit)="enregistrer()">
              <div class="grille-champs">
                <div class="champ">
                  <label for="hAnnee">Année scolaire</label>
                  <input id="hAnnee" name="hAnnee" placeholder="2022-2023" required [(ngModel)]="saisie.annee" />
                </div>
                <div class="champ">
                  <label for="hExamen">Examen</label>
                  <input id="hExamen" name="hExamen" list="examensConnus" placeholder="BEPC, BAC F3, CAP…" required [(ngModel)]="saisie.examen" />
                  <datalist id="examensConnus">
                    @for (e of t.examens; track e) { <option [value]="e"></option> }
                  </datalist>
                </div>
                <div class="champ">
                  <label for="hCandidats">Candidats présents</label>
                  <input id="hCandidats" name="hCandidats" type="number" min="0" required [(ngModel)]="saisie.candidats" />
                </div>
                <div class="champ">
                  <label for="hAdmis">Admis</label>
                  <input id="hAdmis" name="hAdmis" type="number" min="0" required [(ngModel)]="saisie.admis" />
                </div>
                <div class="champ">
                  <label for="hFilles">dont candidates <span class="doux">(facultatif)</span></label>
                  <input id="hFilles" name="hFilles" type="number" min="0" [(ngModel)]="saisie.candidatesFilles" />
                </div>
                <div class="champ">
                  <label for="hAdmises">dont admises <span class="doux">(facultatif)</span></label>
                  <input id="hAdmises" name="hAdmises" type="number" min="0" [(ngModel)]="saisie.admisesFilles" />
                </div>
              </div>
              <div class="actions-ligne">
                <button type="submit" class="bouton" [disabled]="action.enCours()">Enregistrer</button>
                @if (edition()) {
                  <button type="button" class="bouton secondaire" (click)="annulerEdition()">Annuler</button>
                }
              </div>
            </form>
          </section>
        }
      }
    </div>
  `,
  styles: `
    .syntheses {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr));
      gap: 0.75rem;
      margin-bottom: 1rem;
    }
    .synthese {
      margin: 0;
      h2 {
        font-size: 1rem;
        margin: 0;
      }
      p {
        margin: 0.2rem 0;
      }
    }
    .chiffre {
      font-size: 2rem;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
    }
    .hausse {
      color: var(--present);
    }
    .baisse {
      color: var(--absent);
    }
    .barres {
      display: flex;
      align-items: flex-end;
      gap: 0.35rem;
      margin-top: 0.6rem;
    }
    .barre {
      flex: 1;
      display: flex;
      flex-direction: column;
      align-items: center;
      font-size: 0.7rem;
      color: var(--texte-doux);
      min-width: 0;
    }
    .colonne {
      display: flex;
      align-items: flex-end;
      width: 100%;
      max-width: 1.6rem;
      height: 4.5rem;
      border-bottom: 1px solid var(--bordure);
    }
    .remplissage {
      width: 100%;
      background: var(--primaire);
      border-radius: 4px 4px 0 0;
    }
    .valeur {
      font-variant-numeric: tabular-nums;
    }
    tr.classe td {
      font-size: 0.9rem;
      color: var(--texte-doux);
    }
    .actions-cellule {
      white-space: nowrap;
    }
  `,
})
export class ExamensPage implements OnInit {
  private readonly api = inject(FinAnneeApi);
  private readonly session = inject(SessionService);

  protected readonly pourcent = pourcent;
  protected readonly arrondi = (t: number) => Math.round(Number(t));
  protected readonly action = new Action();
  protected readonly actionExport = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly tableau = signal<TableauExamensVue | null>(null);
  protected readonly ouvert = signal<string | null>(null);
  protected readonly edition = signal<string | null>(null);
  protected readonly chef = computed(() => this.session.aLeRole('ADMIN_ECOLE'));
  protected saisie: SaisieHistorique = vide();

  /** Par examen : la dernière année avec un résultat, l'écart à la précédente, la série. */
  protected readonly syntheses = computed(() => {
    const t = this.tableau();
    if (!t) {
      return [];
    }
    return t.examens.map((examen) => {
      const lignes = t.resultats.filter((r) => r.examen === examen);
      const serie = serieExamen(t, examen);
      const derniere = lignes[0];                 // le serveur trie les années de la plus récente à la plus ancienne
      const ecart = serie.find((p) => p.annee === derniere.annee)?.ecart ?? null;
      return { examen, derniere, ecart, serie: serie.slice(-8) };
    });
  });

  async ngOnInit(): Promise<void> {
    const t = await this.action.executer(() => this.api.examens());
    if (t) {
      this.tableau.set(t);
    }
  }

  protected cle(r: ResultatExamenVue): string {
    return `${r.annee}|${r.examen}`;
  }

  protected basculer(r: ResultatExamenVue): void {
    this.ouvert.set(this.ouvert() === this.cle(r) ? null : this.cle(r));
  }

  protected editer(r: ResultatExamenVue): void {
    this.edition.set(r.historiqueId);
    this.saisie = {
      annee: r.annee,
      examen: r.examen,
      candidats: r.resultatsConnus,
      admis: r.admis,
      candidatesFilles: r.resultatsFilles,
      admisesFilles: r.admisFilles,
    };
    this.message.set(null);
  }

  protected annulerEdition(): void {
    this.edition.set(null);
    this.saisie = vide();
  }

  protected async enregistrer(): Promise<void> {
    const s = normaliser(this.saisie);
    const id = this.edition();
    const t = await this.action.executer(() => (id ? this.api.modifierHistorique(id, s) : this.api.ajouterHistorique(s)));
    if (t) {
      this.tableau.set(t);
      this.message.set(`Résultats du ${s.examen.toUpperCase()} ${s.annee} enregistrés.`);
      this.annulerEdition();
    }
  }

  protected async supprimer(r: ResultatExamenVue): Promise<void> {
    if (!r.historiqueId || !window.confirm(`Supprimer les résultats saisis du ${r.examen} ${r.annee} ?`)) {
      return;
    }
    const t = await this.action.executer(() => this.api.supprimerHistorique(r.historiqueId!));
    if (t) {
      this.tableau.set(t);
      this.message.set(`Résultats du ${r.examen} ${r.annee} supprimés.`);
    }
  }

  protected async exporter(format: 'xlsx' | 'pdf'): Promise<void> {
    const blob = await this.actionExport.executer(() => this.api.exportExamens(format), "L'export n'a pas pu être préparé.");
    if (blob) {
      enregistrerFichier(blob, `examens.${format}`);
    }
  }
}

function vide(): SaisieHistorique {
  return { annee: '', examen: '', candidats: null, admis: null, candidatesFilles: null, admisesFilles: null };
}

/** Champs numériques vides → null ; texte nettoyé. */
function normaliser(s: SaisieHistorique): SaisieHistorique {
  const n = (v: unknown) => (v === null || v === undefined || v === '' ? null : Number(v));
  return {
    annee: (s.annee ?? '').replace(/\s/g, ''),
    examen: (s.examen ?? '').trim(),
    candidats: n(s.candidats),
    admis: n(s.admis),
    candidatesFilles: n(s.candidatesFilles),
    admisesFilles: n(s.admisesFilles),
  };
}
