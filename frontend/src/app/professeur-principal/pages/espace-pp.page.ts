import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { InscriptionVue } from '../../admin/modeles-admin';
import { dateHeureCourte } from '../../core/outils';
import {
  ClassePrincipale, CLASSE_VERIFICATION, LIBELLE_NATURE, LIBELLE_STATUT_SAISINE, LIBELLE_VERIFICATION, SaisineVue,
} from '../modeles-pp';
import { ProfesseurPrincipalApi } from '../pp-api.service';

interface ClasseEnseignee {
  classeId: string;
  classeCode: string;
}

/**
 * Espace « Professeur principal » de l'enseignant (v0.49.1) : ses classes de professeur principal (vérification des
 * notes, médiations), les revendications qu'il confie au professeur principal d'une de ses classes, et les dossiers
 * qui le concernent.
 */
@Component({
  selector: 'app-espace-professeur-principal',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page large">
      <h1>Professeur principal</h1>
      <p class="doux">
        Le professeur principal porte la voix des enseignants de la classe auprès de l'administration, reçoit les élèves
        qui ont un souci avec un enseignant et vérifie avec eux les notes et moyennes avant le conseil de classe.
      </p>
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }
      <p><a routerLink="/professeur-principal/reclamations">Réclamations de notes à traiter</a></p>

      @for (c of mesClasses(); track c.classeId) {
        <section class="carte">
          <div class="entete-section">
            <h2>{{ c.classeCode }}</h2>
            <span class="doux">{{ c.enseignants.length }} enseignant(s) · {{ c.saisinesOuvertes }} dossier(s) en cours</span>
          </div>
          <p class="doux petit-texte">
            Vérification des notes :
            @for (v of c.verifications; track v.periodeId) {
              <span class="pastille {{ classeVerif[v.statut] }}">{{ v.periode }} : {{ libelleVerif[v.statut] }}</span>
            } @empty { aucune période commencée. }
          </p>
          <div class="actions-ligne">
            <a class="bouton petit" [routerLink]="['/professeur-principal/classes', c.classeId, 'verification']">Vérifier les notes</a>
            <button type="button" class="bouton secondaire petit" (click)="nouvelleMediation(c)">Ouvrir une médiation</button>
          </div>
          @if (mediation()?.classeId === c.classeId) {
            <form class="sous-formulaire" (ngSubmit)="ouvrirMediation(c)">
              <h3>Médiation : souci d'élèves avec un enseignant</h3>
              <fieldset class="eleves">
                <legend>Élève(s) concerné(s)</legend>
                @for (e of eleves(); track e.id) {
                  <label class="case"><input type="checkbox" [checked]="choisis().has(e.id)" (change)="basculer(e.id)" /> {{ e.nom }} {{ e.prenoms }}</label>
                } @empty { <span class="doux">Chargement…</span> }
              </fieldset>
              <div class="champ">
                <label for="mEnseignant">Enseignant concerné</label>
                <select id="mEnseignant" name="mEnseignant" [(ngModel)]="enseignantId">
                  <option [ngValue]="null">— Aucun en particulier —</option>
                  @for (p of c.enseignants; track p.utilisateurId) { <option [ngValue]="p.utilisateurId">{{ p.nom }} ({{ p.matieres.join(', ') }})</option> }
                </select>
              </div>
              <div class="champ"><label for="mObjet">Objet</label><input id="mObjet" name="mObjet" maxlength="200" required [(ngModel)]="objet" /></div>
              <div class="champ"><label for="mExpose">Ce que disent les élèves</label><textarea id="mExpose" name="mExpose" rows="4" maxlength="4000" required [(ngModel)]="expose"></textarea></div>
              <div class="actions-ligne">
                <button type="submit" class="bouton" [disabled]="action.enCours() || !choisis().size || !objet.trim() || !expose.trim()">Ouvrir le dossier</button>
                <button type="button" class="bouton secondaire" (click)="mediation.set(null)">Annuler</button>
              </div>
            </form>
          }
        </section>
      }

      <section class="carte">
        <h2>Confier une revendication au professeur principal</h2>
        <p class="doux">Une difficulté que partagent les enseignants d'une classe : le professeur principal la porte à l'administration.</p>
        <form (ngSubmit)="confierRevendication()">
          <div class="grille-champs">
            <div class="champ">
              <label for="rClasse">Classe</label>
              <select id="rClasse" name="rClasse" [(ngModel)]="classeRevendication">
                @for (k of classesEnseignees(); track k.classeId) { <option [value]="k.classeId">{{ k.classeCode }}</option> }
              </select>
            </div>
            <div class="champ"><label for="rObjet">Objet</label><input id="rObjet" name="rObjet" maxlength="200" [(ngModel)]="objetRevendication" /></div>
          </div>
          <div class="champ"><label for="rExpose">Exposé</label><textarea id="rExpose" name="rExpose" rows="3" maxlength="4000" [(ngModel)]="exposeRevendication"></textarea></div>
          <button type="submit" class="bouton petit" [disabled]="action.enCours() || !classeRevendication || !objetRevendication.trim() || !exposeRevendication.trim()">Confier au professeur principal</button>
        </form>
      </section>

      <section class="carte">
        <h2>Dossiers</h2>
        <ul class="liste">
          @for (s of saisines(); track s.id) {
            <li>
              <a class="ligne-lien" [routerLink]="['/professeur-principal/dossiers', s.id]">
                <span>
                  <strong>{{ s.numero }}</strong> · {{ s.classeCode }} · {{ s.objet }}<br />
                  <span class="doux petit-texte">{{ libelleNature[s.nature] }} · {{ dateHeure(s.creeLe) }} · {{ s.suites }} échange(s)</span>
                </span>
                <span class="pastille {{ s.statut === 'CLOSE' ? 'present' : '' }}">{{ libelleStatut[s.statut] }}</span>
              </a>
            </li>
          } @empty {
            <li class="doux">Aucun dossier.</li>
          }
        </ul>
      </section>
    </div>
  `,
  styles: `
    .petit-texte {
      font-size: 0.85rem;
    }
    .pastille {
      margin-right: 0.3rem;
    }
    .eleves {
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      padding: 0.5rem 0.75rem;
      margin: 0 0 0.75rem;
      max-height: 14rem;
      overflow-y: auto;
    }
    .case {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      min-height: 32px;
    }
    textarea {
      width: 100%;
    }
  `,
})
export class EspacePpPage {
  private readonly api = inject(ProfesseurPrincipalApi);
  private readonly admin = inject(AdminApi);
  private readonly router = inject(Router);
  private readonly annee = inject(AnneeCourante);

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly mesClasses = signal<ClassePrincipale[]>([]);
  protected readonly saisines = signal<SaisineVue[]>([]);
  protected readonly classesEnseignees = signal<ClasseEnseignee[]>([]);
  protected readonly mediation = signal<ClassePrincipale | null>(null);
  protected readonly eleves = signal<InscriptionVue[]>([]);
  protected readonly choisis = signal<Set<string>>(new Set());
  protected enseignantId: string | null = null;
  protected objet = '';
  protected expose = '';
  protected classeRevendication = '';
  protected objetRevendication = '';
  protected exposeRevendication = '';

  protected readonly libelleNature = LIBELLE_NATURE;
  protected readonly libelleStatut = LIBELLE_STATUT_SAISINE;
  protected readonly libelleVerif = LIBELLE_VERIFICATION;
  protected readonly classeVerif = CLASSE_VERIFICATION;
  protected readonly dateHeure = dateHeureCourte;
  private readonly anneeId = computed(() => this.annee.annee()?.id ?? null);

  constructor() {
    effect(() => {
      const id = this.anneeId();
      untracked(() => {
        if (id) {
          void this.charger(id);
        }
      });
    });
    void this.annee.charger().catch(() => undefined);
  }

  private async charger(anneeId: string): Promise<void> {
    const [m, s, f] = await Promise.all([
      this.action.executer(() => this.api.mesClasses(anneeId)),
      this.action.executer(() => this.api.saisines(anneeId)),
      this.action.executer(() => this.api.affectations()),
    ]);
    if (m) {
      this.mesClasses.set(m);
    }
    if (s) {
      this.saisines.set(s);
    }
    if (f) {
      const vues = new Map<string, ClasseEnseignee>();
      for (const a of f.affectations) {
        vues.set(a.classeId, { classeId: a.classeId, classeCode: a.classeCode });
      }
      const liste = [...vues.values()].sort((a, b) => a.classeCode.localeCompare(b.classeCode));
      this.classesEnseignees.set(liste);
      this.classeRevendication ||= liste[0]?.classeId ?? '';
    }
  }

  protected async nouvelleMediation(c: ClassePrincipale): Promise<void> {
    this.mediation.set(c);
    this.choisis.set(new Set());
    this.enseignantId = null;
    this.objet = '';
    this.expose = '';
    this.eleves.set([]);
    const l = await this.action.executer(() => this.admin.inscriptions(c.classeId));
    if (l) {
      this.eleves.set(l.filter((e) => e.statut === 'ACTIVE'));
    }
  }

  protected basculer(id: string): void {
    this.choisis.update((s) => {
      const n = new Set(s);
      if (!n.delete(id)) {
        n.add(id);
      }
      return n;
    });
  }

  protected async ouvrirMediation(c: ClassePrincipale): Promise<void> {
    const s = await this.action.executer(() =>
      this.api.ouvrir(c.classeId, {
        nature: 'MEDIATION', objet: this.objet.trim(), expose: this.expose.trim(), inscriptions: [...this.choisis()],
        matiereId: null, enseignantId: this.enseignantId,
      }),
    );
    if (s) {
      await this.router.navigate(['/professeur-principal/dossiers', s.id]);
    }
  }

  protected async confierRevendication(): Promise<void> {
    const s = await this.action.executer(() =>
      this.api.ouvrir(this.classeRevendication, {
        nature: 'REVENDICATION', objet: this.objetRevendication.trim(), expose: this.exposeRevendication.trim(),
        inscriptions: [], matiereId: null, enseignantId: null,
      }),
    );
    if (s) {
      this.message.set(`Revendication ${s.numero} confiée au professeur principal de la ${s.classeCode}.`);
      this.objetRevendication = '';
      this.exposeRevendication = '';
      this.saisines.update((l) => [s, ...l]);
    }
  }
}
