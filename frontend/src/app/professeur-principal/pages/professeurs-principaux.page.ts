import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { dateHeureCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import {
  ClassePrincipale, CLASSE_VERIFICATION, LIBELLE_NATURE, LIBELLE_STATUT_SAISINE, LIBELLE_VERIFICATION, SaisineVue,
} from '../modeles-pp';
import { ProfesseurPrincipalApi } from '../pp-api.service';

/**
 * Professeurs principaux de l'établissement (v0.49.1). Le chef d'établissement ou le censeur désigne, pour chaque
 * classe, un professeur principal parmi les enseignants de la classe. La direction suit la vérification des notes de
 * chaque classe et les dossiers que les professeurs principaux lui ont transmis.
 */
@Component({
  selector: 'app-professeurs-principaux',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page large">
      <h1>Professeurs principaux</h1>
      <p class="doux">
        Un professeur principal par classe, choisi parmi ses enseignants : interface entre les enseignants, les élèves et
        l'administration ; il vérifie les notes avec les élèves avant le conseil de classe.
      </p>
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }
      @if (sansProfesseur() > 0 && classes().length) {
        <div class="alerte attention" role="status">{{ sansProfesseur() }} classe(s) sans professeur principal.</div>
      }

      <section class="carte">
        <div class="tableau-defilant">
          <table class="tableau">
            <thead><tr><th>Classe</th><th>Professeur principal</th><th>Vérification des notes</th><th class="nombre">Dossiers en cours</th></tr></thead>
            <tbody>
              @for (c of classes(); track c.classeId) {
                <tr>
                  <td><strong>{{ c.classeCode }}</strong></td>
                  <td>
                    @if (designateur) {
                      <select [attr.aria-label]="'Professeur principal de la ' + c.classeCode" [ngModel]="c.professeurId" (ngModelChange)="designer(c, $event)" [name]="'pp' + c.classeId" [disabled]="action.enCours()">
                        <option [ngValue]="null">— À désigner —</option>
                        @for (e of c.enseignants; track e.utilisateurId) { <option [ngValue]="e.utilisateurId">{{ e.nom }} ({{ e.matieres.join(', ') }})</option> }
                      </select>
                      @if (!c.enseignants.length) { <br /><span class="doux petit-texte">Aucun enseignant affecté à la classe.</span> }
                    } @else {
                      {{ c.professeur ?? '—' }}
                    }
                  </td>
                  <td>
                    @for (v of c.verifications; track v.periodeId) {
                      <span class="pastille {{ classeVerif[v.statut] }}">{{ v.periode }} : {{ libelleVerif[v.statut] }}@if (v.reclamationsOuvertes) { ({{ v.reclamationsOuvertes }} en attente) }</span>
                    } @empty { <span class="doux">—</span> }
                    @if (designateur && c.professeurId) {
                      <br /><a class="petit-texte" [routerLink]="['/professeur-principal/classes', c.classeId, 'verification']">Voir les réclamations</a>
                    }
                  </td>
                  <td class="nombre">{{ c.saisinesOuvertes }}</td>
                </tr>
              } @empty {
                <tr><td colspan="4" class="doux">{{ charge() ? 'Aucune classe pour cette année.' : 'Chargement…' }}</td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      @if (voitDossiers) {
        <section class="carte">
          <h2>Dossiers transmis par les professeurs principaux</h2>
          <ul class="liste">
            @for (s of saisines(); track s.id) {
              <li>
                <a class="ligne-lien" [routerLink]="['/professeur-principal/dossiers', s.id]">
                  <span>
                    <strong>{{ s.numero }}</strong> · {{ s.classeCode }} · {{ s.objet }}<br />
                    <span class="doux petit-texte">{{ libelleNature[s.nature] }} · {{ s.professeur }} · transmis le {{ dateHeure(s.transmiseLe!) }}</span>
                  </span>
                  <span class="pastille {{ s.statut === 'CLOSE' ? 'present' : '' }}">{{ libelleStatut[s.statut] }}</span>
                </a>
              </li>
            } @empty {
              <li class="doux">Aucun dossier transmis.</li>
            }
          </ul>
        </section>
      }
    </div>
  `,
  styles: `
    .petit-texte {
      font-size: 0.85rem;
    }
    .pastille {
      margin: 0 0.3rem 0.2rem 0;
      display: inline-block;
    }
    td select {
      min-width: 12rem;
    }
  `,
})
export class ProfesseursPrincipauxPage {
  private readonly api = inject(ProfesseurPrincipalApi);
  private readonly annee = inject(AnneeCourante);
  private readonly session = inject(SessionService);

  protected readonly designateur = this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR');
  protected readonly voitDossiers = this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR', 'SURVEILLANT');
  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly classes = signal<ClassePrincipale[]>([]);
  protected readonly saisines = signal<SaisineVue[]>([]);
  protected readonly charge = signal(false);
  protected readonly sansProfesseur = computed(() => this.classes().filter((c) => !c.professeurId).length);
  protected readonly libelleVerif = LIBELLE_VERIFICATION;
  protected readonly classeVerif = CLASSE_VERIFICATION;
  protected readonly libelleNature = LIBELLE_NATURE;
  protected readonly libelleStatut = LIBELLE_STATUT_SAISINE;
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
    const [c, s] = await Promise.all([
      this.action.executer(() => this.api.classes(anneeId)),
      this.voitDossiers ? this.action.executer(() => this.api.saisines(anneeId)) : Promise.resolve([] as SaisineVue[]),
    ]);
    if (c) {
      this.classes.set(c);
    }
    if (s) {
      this.saisines.set(s);
    }
    this.charge.set(true);
  }

  protected async designer(c: ClassePrincipale, utilisateurId: string | null): Promise<void> {
    if (utilisateurId === c.professeurId) {
      return;
    }
    if (utilisateurId === null) {
      if (await this.action.reussit(() => this.api.retirer(c.classeId))) {
        this.classes.update((l) => l.map((x) => (x.classeId === c.classeId ? { ...x, professeurId: null, professeur: null } : x)));
        this.message.set(`La ${c.classeCode} n'a plus de professeur principal.`);
      }
      return;
    }
    const v = await this.action.executer(() => this.api.designer(c.classeId, utilisateurId));
    if (v) {
      this.classes.update((l) => l.map((x) => (x.classeId === v.classeId ? v : x)));
      this.message.set(`${v.professeur} est professeur principal de la ${v.classeCode} ; il ou elle en est informé(e).`);
    } else {
      // Refus : revenir à la valeur enregistrée
      this.classes.update((l) => [...l]);
    }
  }
}
