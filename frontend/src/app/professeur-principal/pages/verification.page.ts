import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { ClasseVue, PeriodeVue } from '../../admin/modeles-admin';
import { dateHeureCourte, dateLocale } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import {
  CLASSE_RECLAMATION, CLASSE_VERIFICATION, EvaluationResume, LIBELLE_RECLAMATION, LIBELLE_VERIFICATION, note,
  ReclamationVue, ResultatsPeriode, StatutReclamation, VerificationVue,
} from '../modeles-pp';
import { ProfesseurPrincipalApi } from '../pp-api.service';

/** Période proposée : la dernière commencée (en cours ou précédente). */
function periodeParDefaut(periodes: PeriodeVue[], aujourdhui = dateLocale()): PeriodeVue | null {
  const commencees = periodes.filter((p) => p.debut <= aujourdhui);
  return commencees[commencees.length - 1] ?? periodes[0] ?? null;
}

/**
 * Vérification des notes d'une période avec les élèves (v0.49.1). Le professeur principal parcourt les moyennes par
 * matière, enregistre les réclamations (transmises à l'enseignant de la matière), suit les réponses et atteste la
 * vérification : le conseil de classe et les bulletins l'attendent. L'enseignant de la matière et la direction y
 * répondent aussi aux réclamations.
 */
@Component({
  selector: 'app-verification-notes',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page large">
      <p><a [routerLink]="retour">‹ {{ retourLibelle }}</a></p>
      <h1>Vérification des notes{{ classe() ? ' — ' + classe()!.code : '' }}</h1>
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }
      <div class="champ periode">
        <label for="vPeriode">Période</label>
        <select id="vPeriode" name="vPeriode" [ngModel]="periodeChoisie()" (ngModelChange)="changerPeriode($event)">
          @for (p of periodesClasse(); track p.id) { <option [value]="p.id">{{ p.libelle }}{{ p.verrouillee ? ' (verrouillée)' : '' }}</option> }
        </select>
      </div>

      @if (v(); as v) {
        <section class="carte">
          <div class="entete-section">
            <h2>{{ v.periode }}</h2>
            <span class="pastille {{ classeVerif[v.statut] }}">{{ libelleVerif[v.statut] }}</span>
          </div>
          <p class="doux">
            Professeur principal : {{ v.professeur ?? 'non désigné' }}
            @if (v.valideeLe) { · vérification attestée le {{ dateHeure(v.valideeLe) }} par {{ v.valideePar }} }
          </p>
          @if (v.observations) { <p><strong>Observations :</strong> {{ v.observations }}</p> }
          @if (v.periodeVerrouillee) {
            <div class="alerte" role="status">Période verrouillée : les notes ne peuvent plus être corrigées.</div>
          }
        </section>

        @if (resultats(); as r) {
          <section class="carte">
            <h2>Moyennes de la classe</h2>
            <p class="doux petit-texte">À parcourir avec les élèves : chacun vérifie ses moyennes ; une erreur ou un désaccord devient une réclamation.</p>
            <div class="tableau-defilant">
              <table class="tableau">
                <thead>
                  <tr>
                    <th>Élève</th>
                    @for (m of matieres(); track m.matiereId) { <th class="nombre" [title]="m.libelle">{{ m.code }}</th> }
                    <th class="nombre">Moyenne</th><th class="nombre">Rang</th>
                  </tr>
                </thead>
                <tbody>
                  @for (e of r.eleves; track e.inscriptionId) {
                    <tr>
                      <td>{{ e.nom }} {{ e.prenoms }}</td>
                      @for (m of matieres(); track m.matiereId) { <td class="nombre">{{ moyenne(e.inscriptionId, m.matiereId) }}</td> }
                      <td class="nombre"><strong>{{ note(e.moyenne) }}</strong></td>
                      <td class="nombre">{{ e.rang ?? '—' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
        }

        @if (v.professeurPrincipal && !v.periodeVerrouillee) {
          <form class="carte sous-formulaire" (ngSubmit)="reclamer(v)">
            <h2>Enregistrer une réclamation</h2>
            <div class="grille-champs">
              <div class="champ">
                <label for="rEleve">Élève</label>
                <select id="rEleve" name="rEleve" [(ngModel)]="eleveId">
                  @for (e of resultats()?.eleves ?? []; track e.inscriptionId) { <option [value]="e.inscriptionId">{{ e.nom }} {{ e.prenoms }}</option> }
                </select>
              </div>
              <div class="champ">
                <label for="rMatiere">Matière</label>
                <select id="rMatiere" name="rMatiere" [ngModel]="matiereId()" (ngModelChange)="matiereId.set($event); evaluationId = null">
                  @for (m of matieres(); track m.matiereId) { <option [value]="m.matiereId">{{ m.libelle }}</option> }
                </select>
              </div>
              <div class="champ">
                <label for="rEvaluation">Évaluation (facultatif)</label>
                <select id="rEvaluation" name="rEvaluation" [(ngModel)]="evaluationId">
                  <option [ngValue]="null">— La moyenne de la matière —</option>
                  @for (e of evaluationsMatiere(); track e.id) { <option [ngValue]="e.id">{{ e.libelle }}</option> }
                </select>
              </div>
            </div>
            <div class="champ"><label for="rMotif">Motif de la réclamation</label><input id="rMotif" name="rMotif" maxlength="1000" [(ngModel)]="motif" placeholder="Ex. 14 sur la copie, 11 saisi" /></div>
            <button type="submit" class="bouton petit" [disabled]="action.enCours() || !eleveId || !matiereId() || !motif.trim()">Transmettre à l'enseignant</button>
          </form>
        }

        <section class="carte">
          <h2>Réclamations ({{ v.reclamations.length }})</h2>
          <div class="tableau-defilant">
            <table class="tableau">
              <thead><tr><th>Élève</th><th>Matière</th><th class="nombre">Note</th><th>Motif</th><th>Réponse</th></tr></thead>
              <tbody>
                @for (x of v.reclamations; track x.id) {
                  <tr>
                    <td>{{ x.eleve }}</td>
                    <td>{{ x.matiere }}@if (x.evaluation) { <br /><span class="doux petit-texte">{{ x.evaluation }}</span> }</td>
                    <td class="nombre">{{ note(x.noteContestee) }}@if (x.noteCorrigee !== null) { → <strong>{{ note(x.noteCorrigee) }}</strong> }</td>
                    <td>{{ x.motif }}</td>
                    <td>
                      <span class="pastille {{ classeReclamation[x.statut] }}">{{ libelleReclamation[x.statut] }}</span>
                      @if (x.reponse) { <br /><span class="petit-texte">{{ x.reponse }}</span> }
                      @if (x.peutRepondre) {
                        <div class="reponse">
                          <input type="text" maxlength="1000" [attr.aria-label]="'Réponse à ' + x.eleve" [(ngModel)]="reponses[x.id]" [name]="'rep' + x.id" placeholder="Réponse (obligatoire si maintenue)" />
                          <div class="actions-ligne">
                            <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="repondre(v, x, 'CORRIGEE')">Note corrigée</button>
                            <button type="button" class="bouton secondaire petit" [disabled]="action.enCours()" (click)="repondre(v, x, 'MAINTENUE')">Note maintenue</button>
                          </div>
                        </div>
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr><td colspan="5" class="doux">Aucune réclamation.</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>

        @if (v.professeurPrincipal && v.statut !== 'VALIDEE') {
          <section class="carte">
            <h2>Attester la vérification</h2>
            <p class="doux">Une fois toutes les réclamations traitées, vous attestez avoir vérifié les notes avec les élèves : la classe est prête pour le conseil de classe.</p>
            <div class="champ"><label for="vObs">Observations (facultatif)</label><input id="vObs" name="vObs" maxlength="2000" [(ngModel)]="observations" /></div>
            <button type="button" class="bouton" [disabled]="action.enCours() || !v.peutValider" (click)="valider(v)">Attester la vérification</button>
          </section>
        }
      }
    </div>
  `,
  styles: `
    .petit-texte {
      font-size: 0.85rem;
    }
    .periode {
      max-width: 18rem;
    }
    .reponse {
      margin-top: 0.4rem;
      input {
        width: 100%;
        min-width: 12rem;
        margin-bottom: 0.3rem;
      }
    }
  `,
})
export class VerificationPage {
  private readonly api = inject(ProfesseurPrincipalApi);
  private readonly admin = inject(AdminApi);
  private readonly router = inject(Router);
  private readonly session = inject(SessionService);

  readonly classeId = input.required<string>();
  readonly periodeId = input<string>();

  private readonly direction = this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR');
  protected readonly retour = this.direction ? '/professeurs-principaux' : '/professeur-principal';
  protected readonly retourLibelle = this.direction ? 'Professeurs principaux' : 'Professeur principal';
  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly classe = signal<ClasseVue | null>(null);
  private readonly periodes = signal<PeriodeVue[]>([]);
  protected readonly periodeChoisie = signal<string | null>(null);
  protected readonly v = signal<VerificationVue | null>(null);
  protected readonly resultats = signal<ResultatsPeriode | null>(null);
  private readonly evaluations = signal<EvaluationResume[]>([]);
  protected readonly matiereId = signal<string>('');
  protected eleveId = '';
  protected evaluationId: string | null = null;
  protected motif = '';
  protected observations = '';
  protected reponses: Record<string, string> = {};

  protected readonly note = note;
  protected readonly dateHeure = dateHeureCourte;
  protected readonly libelleVerif = LIBELLE_VERIFICATION;
  protected readonly classeVerif = CLASSE_VERIFICATION;
  protected readonly libelleReclamation = LIBELLE_RECLAMATION;
  protected readonly classeReclamation = CLASSE_RECLAMATION;

  protected readonly periodesClasse = computed(() =>
    this.periodes().filter((p) => p.profilId === this.classe()?.profilId).sort((a, b) => a.ordre - b.ordre),
  );
  protected readonly matieres = computed(() => this.resultats()?.eleves[0]?.matieres ?? []);
  protected readonly evaluationsMatiere = computed(() => this.evaluations().filter((e) => e.matiereId === this.matiereId()));
  private readonly index = computed(() => {
    const m = new Map<string, number | null>();
    for (const e of this.resultats()?.eleves ?? []) {
      for (const x of e.matieres) {
        m.set(`${e.inscriptionId}|${x.matiereId}`, x.moyenne);
      }
    }
    return m;
  });

  constructor() {
    effect(() => {
      const id = this.classeId();
      untracked(() => void this.charger(id));
    });
  }

  private async charger(classeId: string): Promise<void> {
    const c = await this.action.executer(() => this.admin.classe(classeId));
    if (!c) {
      return;
    }
    this.classe.set(c);
    const p = await this.action.executer(() => this.admin.periodes(c.anneeId));
    if (!p) {
      return;
    }
    this.periodes.set(p);
    const voulue = this.periodeId();
    const choisie = (voulue && this.periodesClasse().find((x) => x.id === voulue)) || periodeParDefaut(this.periodesClasse());
    if (choisie) {
      await this.ouvrir(choisie.id);
    }
  }

  protected async changerPeriode(id: string): Promise<void> {
    await this.router.navigate([], { queryParams: { periodeId: id }, replaceUrl: true });
    await this.ouvrir(id);
  }

  private async ouvrir(periodeId: string): Promise<void> {
    this.periodeChoisie.set(periodeId);
    const classeId = this.classeId();
    const [v, r, e] = await Promise.all([
      this.action.executer(() => this.api.verification(classeId, periodeId)),
      this.action.executer(() => this.api.resultats(classeId, periodeId)),
      this.action.executer(() => this.api.evaluations(classeId, periodeId)),
    ]);
    if (v) {
      this.v.set(v);
    }
    if (r) {
      this.resultats.set(r);
      this.eleveId ||= r.eleves[0]?.inscriptionId ?? '';
      if (!this.matiereId()) {
        this.matiereId.set(r.eleves[0]?.matieres[0]?.matiereId ?? '');
      }
    }
    if (e) {
      this.evaluations.set(e);
    }
  }

  protected moyenne(inscriptionId: string, matiereId: string): string {
    return note(this.index().get(`${inscriptionId}|${matiereId}`));
  }

  protected async reclamer(v: VerificationVue): Promise<void> {
    const n = await this.action.executer(() =>
      this.api.reclamer(v.classeId, {
        periodeId: v.periodeId, inscriptionId: this.eleveId, matiereId: this.matiereId(), evaluationId: this.evaluationId,
        motif: this.motif.trim(),
      }),
    );
    if (n) {
      this.v.set(n);
      this.motif = '';
      this.evaluationId = null;
      this.message.set("Réclamation transmise à l'enseignant de la matière.");
    }
  }

  protected async repondre(v: VerificationVue, x: ReclamationVue, decision: StatutReclamation): Promise<void> {
    const texte = (this.reponses[x.id] ?? '').trim();
    const r = await this.action.executer(() => this.api.repondre(x.id, decision, texte || null));
    if (r) {
      this.message.set(decision === 'CORRIGEE' ? 'Correction enregistrée.' : 'Note maintenue.');
      const n = await this.action.executer(() => this.api.verification(v.classeId, v.periodeId));
      if (n) {
        this.v.set(n);
      }
    }
  }

  protected async valider(v: VerificationVue): Promise<void> {
    const n = await this.action.executer(() => this.api.valider(v.classeId, v.periodeId, this.observations.trim() || null));
    if (n) {
      this.v.set(n);
      this.message.set('Vérification attestée : la classe est prête pour le conseil de classe.');
    }
  }
}
