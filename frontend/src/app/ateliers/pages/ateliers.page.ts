import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { FiliereVue } from '../../admin/modeles-admin';
import { dateCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { AteliersNavComponent } from '../ateliers-nav.component';
import { ExportBoutonsComponent } from '../export-boutons.component';
import { AteliersApi } from '../ateliers-api.service';
import {
  alertesEnTexte,
  AtelierResumeVue,
  DIRECTION_ATELIERS,
  dureeMandat,
  FrequenceInventaire,
  LIBELLE_FREQUENCE,
  ParametresAteliers,
} from '../modeles-ateliers';

/** Contrôle d'un atelier avant l'envoi. */
export function erreurAtelier(d: { code: string; nom: string; postes: string; filieres: string[] }): string | null {
  if (!d.code.trim()) {
    return 'Donnez un code court (ELEC, MECA, MENUIS…).';
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9_./-]*$/.test(d.code.trim())) {
    return 'Code : lettres, chiffres et tirets seulement.';
  }
  if (!d.nom.trim()) {
    return 'Donnez le nom de l’atelier.';
  }
  if (d.postes.trim() && !/^\d{1,3}$/.test(d.postes.trim())) {
    return 'Nombre de postes : un nombre entier.';
  }
  if (!d.filieres.length) {
    return 'Cochez au moins une filière servie par l’atelier.';
  }
  return null;
}

/**
 * Liste des ateliers avec ce qui demande l'attention (sans responsable, mandat à échéance,
 * pannes, matières sous le seuil, inventaire à faire). La direction crée les ateliers et règle
 * la durée des mandats et la fréquence des inventaires.
 */
@Component({
  selector: 'app-ateliers',
  imports: [FormsModule, RouterLink, AteliersNavComponent, ExportBoutonsComponent],
  template: `
    <div class="page large">
      <h1>Ateliers</h1>
      <app-ateliers-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (charge()) {
        @if (direction()) {
          <div class="chiffres">
            <div><strong>{{ ateliers().length }}</strong><span>atelier(s)</span></div>
            <div [class.alerte-valeur]="bilan().sansResponsable > 0"><strong>{{ bilan().sansResponsable }}</strong><span>sans responsable</span></div>
            <div [class.alerte-valeur]="bilan().mandats > 0"><strong>{{ bilan().mandats }}</strong><span>mandat(s) à renouveler</span></div>
            <div [class.alerte-valeur]="bilan().pannes > 0"><strong>{{ bilan().pannes }}</strong><span>équipement(s) en panne</span></div>
            <div [class.alerte-valeur]="bilan().seuil > 0"><strong>{{ bilan().seuil }}</strong><span>matière(s) sous le seuil</span></div>
          </div>
          @if (ateliers().length) {
            <div class="barre-export">
              <span class="doux">Tableau de bord des ateliers :</span>
              <app-export chemin="/ateliers" nom="ateliers" libelle="le tableau de bord des ateliers" />
              <span class="doux">Stock par filière :</span>
              <app-export chemin="/ateliers/stock-par-filiere" nom="stock-par-filiere" libelle="le stock par filière" />
            </div>
          }
        }

        @for (a of ateliers(); track a.id) {
          <a class="carte atelier" [routerLink]="['/ateliers', a.id]">
            <span class="corps">
              <strong>{{ a.code }} · {{ a.nom }}</strong>
              @if (!a.ouvert) { <span class="pastille">fermé</span> }
              @if (a.droits.responsable) { <span class="pastille present">vous êtes responsable</span> }
              <br />
              <span class="doux">
                {{ filieresTexte(a) }}
                @if (a.emplacement) { · {{ a.emplacement }} }
                @if (a.postes) { · {{ a.postes }} postes }
              </span>
              <br />
              <span class="doux">
                Responsable :
                @if (a.responsable) {
                  {{ a.responsable.enseignant }}
                  @if (a.responsable.finPrevue) { (jusqu'au {{ dateCourte(a.responsable.finPrevue) }}) }
                } @else {
                  aucun
                }
                · {{ a.equipements }} équipement(s) · {{ a.articles }} matière(s) d’œuvre
              </span>
              @if (alertes(a).length) {
                <span class="alertes">
                  @for (t of alertes(a); track t) { <span class="pastille absent">{{ t }}</span> }
                </span>
              }
            </span>
          </a>
        } @empty {
          <div class="carte">
            <p class="doux">
              @if (direction()) {
                Aucun atelier pour le moment. Créez le premier : il servira une ou plusieurs filières, et vous pourrez lui
                désigner un responsable parmi les enseignants techniques.
              } @else {
                Vous n'êtes rattaché à aucun atelier : ils sont ouverts aux enseignants des matières techniques et pratiques
                de leurs filières.
              }
            </p>
          </div>
        }

        @if (direction()) {
          <section class="carte">
            <div class="entete-section">
              <h2>Nouvel atelier</h2>
              @if (!ouvert()) {
                <button type="button" class="bouton petit" (click)="ouvrir()">Créer un atelier</button>
              }
            </div>
            @if (ouvert()) {
              <form (ngSubmit)="creer()">
                <div class="grille-champs">
                  <div class="champ">
                    <label for="at-code">Code</label>
                    <input id="at-code" name="code" placeholder="ELEC" [(ngModel)]="code" />
                  </div>
                  <div class="champ">
                    <label for="at-nom">Nom</label>
                    <input id="at-nom" name="nom" placeholder="Atelier d’électricité" [(ngModel)]="nom" />
                  </div>
                  <div class="champ">
                    <label for="at-lieu">Emplacement <span class="doux">(facultatif)</span></label>
                    <input id="at-lieu" name="lieu" placeholder="Bâtiment B" [(ngModel)]="emplacement" />
                  </div>
                  <div class="champ">
                    <label for="at-postes">Postes de travail <span class="doux">(facultatif)</span></label>
                    <input id="at-postes" name="postes" inputmode="numeric" [(ngModel)]="postes" />
                  </div>
                </div>
                <fieldset class="cibles">
                  <legend>Filières servies</legend>
                  @for (f of filieres(); track f.id) {
                    <label class="case"><input type="checkbox" [checked]="choisies().includes(f.id)" (change)="basculer(f.id)" /> {{ f.code }} <span class="doux">{{ f.libelle }}</span></label>
                  } @empty {
                    <p class="doux">Créez d'abord les filières (Année scolaire → Filières et matières).</p>
                  }
                </fieldset>
                @if (code() && nom() && erreurSaisie(); as e) {
                  <p class="rouge" role="alert">{{ e }}</p>
                }
                <div class="actions-ligne">
                  <button type="submit" class="bouton" [disabled]="!!erreurSaisie() || enregistrement.enCours()">Créer l'atelier</button>
                  <button type="button" class="bouton discret" (click)="ouvert.set(false)">Fermer</button>
                </div>
              </form>
            }
          </section>

          @if (parametres(); as p) {
            <section class="carte">
              <div class="entete-section">
                <h2>Paramètres</h2>
                @if (!parOuverts()) {
                  <button type="button" class="bouton secondaire petit" (click)="ouvrirParametres(p)">Modifier</button>
                }
              </div>
              @if (!parOuverts()) {
                <ul class="parametres">
                  <li>Durée du mandat d'un responsable d'atelier : <strong>{{ dureeMandat(p.dureeMandatMois) }}</strong></li>
                  <li>Inventaires : <strong>{{ libelleFrequence[p.frequenceInventaire].toLowerCase() }}</strong></li>
                </ul>
              } @else {
                <form (ngSubmit)="enregistrerParametres()">
                  <div class="grille-champs">
                    <div class="champ">
                      <label for="par-duree">Durée du mandat</label>
                      <select id="par-duree" name="duree" [ngModel]="parDuree()" (ngModelChange)="parDuree.set($event)">
                        @for (d of durees; track d) { <option [value]="d">{{ dureeMandat(d === 'sans' ? null : +d) }}</option> }
                      </select>
                    </div>
                    <div class="champ">
                      <label for="par-freq">Inventaires</label>
                      <select id="par-freq" name="freq" [ngModel]="parFrequence()" (ngModelChange)="parFrequence.set($event)">
                        @for (f of frequences; track f) { <option [value]="f">{{ libelleFrequence[f] }}</option> }
                      </select>
                    </div>
                  </div>
                  <p class="doux">La nouvelle durée s'applique aux prochaines désignations ; les mandats en cours gardent leur date de fin.</p>
                  <div class="actions-ligne">
                    <button type="submit" class="bouton petit" [disabled]="enregistrement.enCours()">Enregistrer</button>
                    <button type="button" class="bouton discret petit" (click)="parOuverts.set(false)">Fermer</button>
                  </div>
                </form>
              }
            </section>
          }
        }
        @if (enregistrement.erreur()) {
          <div class="alerte erreur" role="alert">{{ enregistrement.erreur() }}</div>
        }
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .chiffres {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
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
      .alerte-valeur strong {
        color: var(--absent);
      }
    }
    .atelier {
      display: block;
      text-decoration: none;
      color: inherit;
    }
    .alertes {
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem;
      margin-top: 0.4rem;
    }
    .cibles {
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      margin: 0.75rem 0;
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem 1.25rem;
    }
    .case {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
    }
    .rouge {
      color: var(--absent);
    }
    .parametres {
      margin: 0;
      padding-left: 1.2rem;
    }
    h2 {
      font-size: 1.05rem;
      margin: 0;
    }
  `,
})
export class AteliersPage {
  private readonly api = inject(AteliersApi);
  private readonly admin = inject(AdminApi);
  private readonly router = inject(Router);
  private readonly session = inject(SessionService);

  protected readonly dateCourte = dateCourte;
  protected readonly dureeMandat = dureeMandat;
  protected readonly libelleFrequence = LIBELLE_FREQUENCE;
  protected readonly frequences: FrequenceInventaire[] = ['SEMESTRIELLE', 'ANNUELLE'];
  protected readonly durees = ['12', '24', '36', '48', '60', 'sans'];
  protected readonly action = new Action();
  protected readonly enregistrement = new Action();

  protected readonly ateliers = signal<AtelierResumeVue[]>([]);
  protected readonly charge = signal(false);
  protected readonly parametres = signal<ParametresAteliers | null>(null);
  protected readonly filieres = signal<FiliereVue[]>([]);
  /** Direction des ateliers (chef des travaux et administrateur). */
  protected readonly direction = computed(() => this.session.aLeRole(...DIRECTION_ATELIERS));

  protected readonly ouvert = signal(false);
  protected readonly code = signal('');
  protected readonly nom = signal('');
  protected readonly emplacement = signal('');
  protected readonly postes = signal('');
  protected readonly choisies = signal<string[]>([]);

  protected readonly parOuverts = signal(false);
  protected readonly parDuree = signal('24');
  protected readonly parFrequence = signal<FrequenceInventaire>('SEMESTRIELLE');

  protected readonly erreurSaisie = computed(() =>
    erreurAtelier({ code: this.code(), nom: this.nom(), postes: this.postes(), filieres: this.choisies() }),
  );

  protected readonly bilan = computed(() => {
    const l = this.ateliers();
    return {
      sansResponsable: l.filter((a) => a.alertes.sansResponsable).length,
      mandats: l.filter((a) => a.alertes.mandatAEcheance || a.alertes.mandatEchu).length,
      pannes: l.reduce((t, a) => t + a.alertes.equipementsEnPanne, 0),
      seuil: l.reduce((t, a) => t + a.alertes.articlesSousSeuil, 0),
    };
  });

  constructor() {
    void this.charger();
  }

  protected alertes(a: AtelierResumeVue): string[] {
    return alertesEnTexte(a.alertes);
  }

  protected filieresTexte(a: AtelierResumeVue): string {
    return a.filieres.map((f) => f.code).join(', ') || 'aucune filière';
  }

  protected ouvrir(): void {
    this.code.set('');
    this.nom.set('');
    this.emplacement.set('');
    this.postes.set('');
    this.choisies.set([]);
    this.ouvert.set(true);
  }

  protected basculer(id: string): void {
    const c = this.choisies();
    this.choisies.set(c.includes(id) ? c.filter((x) => x !== id) : [...c, id]);
  }

  protected async creer(): Promise<void> {
    if (this.erreurSaisie()) {
      return;
    }
    const a = await this.enregistrement.executer(() =>
      this.api.creerAtelier({
        code: this.code().trim(),
        nom: this.nom().trim(),
        emplacement: this.emplacement().trim() || null,
        postes: this.postes().trim() ? Number(this.postes()) : null,
        ouvert: true,
        observations: null,
        filieres: this.choisies(),
      }),
    );
    if (a) {
      void this.router.navigate(['/ateliers', a.id]);
    }
  }

  protected ouvrirParametres(p: ParametresAteliers): void {
    this.parDuree.set(p.dureeMandatMois === null ? 'sans' : String(p.dureeMandatMois));
    this.parFrequence.set(p.frequenceInventaire);
    this.parOuverts.set(true);
  }

  protected async enregistrerParametres(): Promise<void> {
    const p = await this.enregistrement.executer(() =>
      this.api.modifierParametres({
        dureeMandatMois: this.parDuree() === 'sans' ? null : Number(this.parDuree()),
        frequenceInventaire: this.parFrequence(),
      }),
    );
    if (p) {
      this.parametres.set(p);
      this.parOuverts.set(false);
    }
  }

  private async charger(): Promise<void> {
    const [ateliers, parametres] = await Promise.all([
      this.action.executer(() => this.api.ateliers()),
      this.api.parametres().catch(() => null),
    ]);
    if (ateliers) {
      this.ateliers.set(ateliers);
      this.charge.set(true);
    }
    this.parametres.set(parametres);
    if (this.direction()) {
      const filieres = await this.admin.filieres().catch(() => null);
      this.filieres.set(filieres ?? []);
    }
  }
}
