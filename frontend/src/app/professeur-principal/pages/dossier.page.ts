import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateHeureCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { LIBELLE_NATURE, LIBELLE_STATUT_SAISINE, SaisineVue } from '../modeles-pp';
import { ProfesseurPrincipalApi } from '../pp-api.service';

/**
 * Un dossier du professeur principal (v0.49.1) : exposé, élèves et enseignant concernés, journal des échanges ;
 * selon les droits : écrire, informer l'enseignant (médiation), transmettre à l'administration, clore.
 */
@Component({
  selector: 'app-dossier-professeur-principal',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page large">
      <p><a [routerLink]="retour">‹ {{ retourLibelle }}</a></p>
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }
      @if (s(); as s) {
        <div class="entete-section">
          <h1>{{ s.numero }} · {{ s.objet }}</h1>
          <span class="pastille {{ s.statut === 'CLOSE' ? 'present' : '' }}">{{ libelleStatut[s.statut] }}</span>
        </div>
        <p class="doux">
          {{ libelleNature[s.nature] }} · {{ s.classeCode }} · ouvert le {{ dateHeure(s.creeLe) }} par {{ s.auteur }}
          @if (s.professeur) { · professeur principal : {{ s.professeur }} }
        </p>
        <section class="carte">
          @if (s.eleves.length) {
            <p><strong>Élève(s) :</strong> @for (e of s.eleves; track e.inscriptionId) { {{ e.nom }}@if (!$last) {, } }</p>
          }
          @if (s.enseignant) {
            <p><strong>Enseignant concerné :</strong> {{ s.enseignant }}@if (s.matiere) { ({{ s.matiere }}) }
              · <span class="doux">{{ s.enseignantInforme ? 'informé(e)' : 'pas encore informé(e)' }}</span></p>
          }
          <p class="expose">{{ s.expose }}</p>
          @if (s.transmiseLe) {
            <p class="doux petit-texte">Transmis à l'administration le {{ dateHeure(s.transmiseLe) }}.</p>
          }
        </section>

        <section class="carte">
          <h2>Échanges</h2>
          <ul class="journal">
            @for (j of s.journal ?? []; track j.id) {
              <li>
                <p class="doux petit-texte"><strong>{{ j.auteur }}</strong> · {{ j.qualite }} · {{ dateHeure(j.creeLe) }}</p>
                <p>{{ j.texte }}</p>
              </li>
            } @empty {
              <li class="doux">Aucun échange pour l'instant.</li>
            }
          </ul>
          @if (s.droits.ecrire) {
            <form (ngSubmit)="ecrire(s)">
              <div class="champ"><label for="dTexte">Votre message ou la démarche effectuée</label><textarea id="dTexte" name="dTexte" rows="3" maxlength="2000" [(ngModel)]="texte"></textarea></div>
              <button type="submit" class="bouton petit" [disabled]="action.enCours() || !texte.trim()">Ajouter au dossier</button>
            </form>
          }
        </section>

        @if (s.conclusion) {
          <section class="carte">
            <h2>Conclusion</h2>
            <p>{{ s.conclusion }}</p>
            <p class="doux petit-texte">Clos le {{ dateHeure(s.closeLe!) }} par {{ s.closePar }}.</p>
          </section>
        }

        @if (s.droits.informer || s.droits.transmettre || s.droits.clore) {
          <section class="carte">
            <h2>Suite à donner</h2>
            <div class="actions-ligne">
              @if (s.droits.informer) {
                <button type="button" class="bouton secondaire petit" [disabled]="action.enCours()" (click)="informer(s)">Informer {{ s.enseignant }}</button>
              }
              @if (s.droits.transmettre) {
                <button type="button" class="bouton secondaire petit" [disabled]="action.enCours()" (click)="transmettre(s)">
                  Transmettre à l'administration{{ s.nature === 'MEDIATION' ? ' (et au CPE)' : '' }}
                </button>
              }
            </div>
            @if (s.droits.clore) {
              <form class="sous-formulaire" (ngSubmit)="clore(s)">
                <div class="champ"><label for="dConclusion">Conclusion</label><input id="dConclusion" name="dConclusion" maxlength="1000" [(ngModel)]="conclusion" placeholder="Ex. entretien tenu, malentendu levé" /></div>
                <button type="submit" class="bouton petit" [disabled]="action.enCours() || !conclusion.trim()">Clore le dossier</button>
              </form>
            }
          </section>
        }
      }
    </div>
  `,
  styles: `
    .petit-texte {
      font-size: 0.85rem;
    }
    .expose {
      white-space: pre-line;
    }
    .journal {
      list-style: none;
      padding: 0;
      margin: 0 0 0.75rem;
      li {
        border-left: 3px solid var(--accent);
        padding: 0.25rem 0 0.25rem 0.75rem;
        margin-bottom: 0.6rem;
        p {
          margin: 0.15rem 0;
          white-space: pre-line;
        }
      }
    }
    textarea {
      width: 100%;
    }
  `,
})
export class DossierPage {
  private readonly api = inject(ProfesseurPrincipalApi);
  private readonly session = inject(SessionService);

  readonly id = input.required<string>();

  private readonly direction = !this.session.aLeRole('ENSEIGNANT') || this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR', 'SURVEILLANT');
  protected readonly retour = this.direction ? '/professeurs-principaux' : '/professeur-principal';
  protected readonly retourLibelle = this.direction ? 'Professeurs principaux' : 'Professeur principal';
  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly s = signal<SaisineVue | null>(null);
  protected texte = '';
  protected conclusion = '';
  protected readonly libelleNature = LIBELLE_NATURE;
  protected readonly libelleStatut = LIBELLE_STATUT_SAISINE;
  protected readonly dateHeure = dateHeureCourte;

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => void this.charger(id));
    });
  }

  private async charger(id: string): Promise<void> {
    const s = await this.action.executer(() => this.api.saisine(id));
    if (s) {
      this.s.set(s);
    }
  }

  protected async ecrire(s: SaisineVue): Promise<void> {
    const v = await this.action.executer(() => this.api.ajouterSuite(s.id, this.texte.trim()));
    if (v) {
      this.s.set(v);
      this.texte = '';
      this.message.set('Ajouté au dossier.');
    }
  }

  protected async informer(s: SaisineVue): Promise<void> {
    const v = await this.action.executer(() => this.api.informerEnseignant(s.id));
    if (v) {
      this.s.set(v);
      this.message.set(`${s.enseignant} est informé(e) et peut donner sa version dans le dossier.`);
    }
  }

  protected async transmettre(s: SaisineVue): Promise<void> {
    const v = await this.action.executer(() => this.api.transmettre(s.id, this.texte.trim() || null));
    if (v) {
      this.s.set(v);
      this.texte = '';
      this.message.set("Dossier transmis à l'administration.");
    }
  }

  protected async clore(s: SaisineVue): Promise<void> {
    const v = await this.action.executer(() => this.api.clore(s.id, this.conclusion.trim()));
    if (v) {
      this.s.set(v);
      this.conclusion = '';
      this.message.set('Dossier clos.');
    }
  }
}
