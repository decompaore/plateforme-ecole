import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { MembreVue } from '../../admin/modeles-admin';
import { dateCourte, dateHeureCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import {
  CandidatureVue, CLASSE_STATUT_CANDIDATURE, ClassementOffre, CommissionVue, DonneesCandidature, LIBELLE_LIEN,
  LIBELLE_QUALITE, LIBELLE_STATUT_CANDIDATURE, LIBELLE_STATUT_COMMISSION, Lien, MembreSaisi, note, OffreVue, Qualite,
  QUALITES,
} from '../modeles-recrutement';
import { RecrutementApi } from '../recrutement-api.service';

type Onglet = 'commission' | 'places' | 'candidatures' | 'classement' | 'resultats';

interface FormCandidature extends Omit<DonneesCandidature, 'moyenneAnnuelle' | 'noteExamen'> {
  id: string | null;
  moyenneAnnuelle: number | null;
  noteExamen: number | null;
}

/**
 * Une commission de recrutement (v0.49) : membres et critères, places par niveau, candidatures et recevabilité,
 * classement au mérite et délibération, résultats (procès-verbal, inscription des retenus, désistements).
 */
@Component({
  selector: 'app-commission-recrutement',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page large">
      <p><a routerLink="/recrutement">‹ Commissions de recrutement</a></p>
      @if (c(); as c) {
        <div class="entete-section">
          <h1>{{ c.numero }} · {{ c.intitule }}</h1>
          <span class="pastille {{ c.statut === 'PREPARATION' ? '' : 'present' }}">{{ libelleStatut[c.statut] }}</span>
        </div>
        <p class="doux">
          Année {{ c.annee }} · session du {{ dateCourte(c.dateSession) }}@if (c.lieu) { · {{ c.lieu }} }
          · moyenne minimale {{ note(c.moyenneMinimale) }}/20 · liste d'attente de {{ c.tailleListeAttente }} par niveau
          @if (c.delibereeLe) { · délibérée le {{ dateHeure(c.delibereeLe) }}@if (c.delibereePar) { par {{ c.delibereePar }} } }
        </p>
      }
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      @if (c(); as c) {
        <div class="bascule" role="group" aria-label="Rubriques">
          <button type="button" [class.actif]="onglet() === 'commission'" (click)="onglet.set('commission')">Commission</button>
          <button type="button" [class.actif]="onglet() === 'places'" (click)="onglet.set('places')">Places ({{ totalPlaces() }})</button>
          <button type="button" [class.actif]="onglet() === 'candidatures'" (click)="afficherCandidatures()">Candidatures ({{ candidatures().length }})</button>
          <button type="button" [class.actif]="onglet() === 'classement'" (click)="afficherClassement()">{{ c.statut === 'PREPARATION' ? 'Classement' : 'Résultats' }}</button>
        </div>

        <!-- ============================================================ commission -->
        @if (onglet() === 'commission') {
          <section class="carte">
            <div class="entete-section">
              <h2>Membres</h2>
              @if (c.droits.modifier && !membresEdites()) {
                <button type="button" class="bouton secondaire petit" (click)="editerMembres(c)">Modifier les membres</button>
              }
            </div>
            @if (membresEdites(); as liste) {
              <div class="tableau-defilant">
                <table class="tableau">
                  <thead><tr><th>Qualité</th><th>Personne</th><th>Fonction</th><th>Présent</th><th></th></tr></thead>
                  <tbody>
                    @for (m of liste; track $index) {
                      <tr>
                        <td>
                          <select [attr.aria-label]="'Qualité ' + ($index + 1)" [(ngModel)]="m.qualite" [name]="'q' + $index">
                            @for (q of qualites; track q) { <option [value]="q">{{ libelleQualite[q] }}</option> }
                          </select>
                        </td>
                        <td>
                          <select [attr.aria-label]="'Personne ' + ($index + 1)" [(ngModel)]="m.utilisateurId" [name]="'u' + $index">
                            <option [ngValue]="null">Personne extérieure</option>
                            @for (p of personnel(); track p.utilisateurId) { <option [ngValue]="p.utilisateurId">{{ p.nom }} {{ p.prenoms }}</option> }
                          </select>
                          @if (!m.utilisateurId) {
                            <input type="text" maxlength="160" [attr.aria-label]="'Nom ' + ($index + 1)" [(ngModel)]="m.nom" [name]="'n' + $index" placeholder="Nom et prénoms" />
                          }
                        </td>
                        <td><input type="text" maxlength="100" [attr.aria-label]="'Fonction ' + ($index + 1)" [(ngModel)]="m.fonction" [name]="'f' + $index" /></td>
                        <td><input type="checkbox" [attr.aria-label]="'Présent ' + ($index + 1)" [(ngModel)]="m.present" [name]="'p' + $index" /></td>
                        <td><button type="button" class="bouton discret petit" (click)="retirerMembre($index)">Retirer</button></td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
              <div class="actions-ligne">
                <button type="button" class="bouton secondaire petit" (click)="ajouterMembre()">Ajouter un membre</button>
                <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="enregistrerMembres(c)">Enregistrer les membres</button>
                <button type="button" class="bouton discret petit" (click)="membresEdites.set(null)">Annuler</button>
              </div>
            } @else {
              <ul class="liste">
                @for (m of c.membres; track m.id) {
                  <li class="ligne-lien">
                    <span><strong>{{ libelleQualite[m.qualite] }}</strong> · {{ m.nom }} · <span class="doux">{{ m.fonction }}</span></span>
                    @if (!m.present) { <span class="pastille absent">Absent</span> }
                  </li>
                }
              </ul>
            }
          </section>
          @if (c.criteres) { <p><strong>Critères :</strong> {{ c.criteres }}</p> }
        }

        <!-- ============================================================ places -->
        @if (onglet() === 'places') {
          <section class="carte">
            <p class="doux">Places disponibles : effectif maximal des classes du niveau moins les élèves inscrits. La commission offre au plus ces places.</p>
            <div class="tableau-defilant">
              <table class="tableau">
                <thead><tr><th>Niveau</th><th>Classes</th><th class="nombre">Disponibles</th><th class="nombre">Offertes</th><th class="nombre">Candidatures</th><th class="nombre">Retenus</th><th class="nombre">Inscrits</th></tr></thead>
                <tbody>
                  @for (o of c.offres; track o.id) {
                    <tr>
                      <td><strong>{{ o.niveau }} {{ o.filiere }}</strong></td>
                      <td class="petit-texte">@for (k of o.classes; track k.id) { {{ k.code }} ({{ k.inscrits }}/{{ k.effectifMax ?? '∞' }}) }</td>
                      <td class="nombre">{{ o.disponibles ?? '—' }}</td>
                      <td class="nombre">
                        @if (c.droits.saisir && peutPreparer) {
                          <input type="number" min="0" [max]="o.disponibles ?? 999" [attr.aria-label]="'Places ' + o.niveau + ' ' + o.filiere" [(ngModel)]="places[o.id]" [name]="'pl' + o.id" />
                        } @else { {{ o.places }} }
                      </td>
                      <td class="nombre">{{ o.candidatures }}</td>
                      <td class="nombre">{{ o.retenues }}</td>
                      <td class="nombre">{{ o.inscrites }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (c.droits.saisir && peutPreparer) {
              <div class="actions-ligne">
                <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="enregistrerPlaces(c)">Enregistrer les places</button>
                <button type="button" class="bouton discret petit" [disabled]="action.enCours()" (click)="actualiserOffres(c)">Ajouter les nouveaux niveaux</button>
              </div>
              <p class="doux petit-texte">« ∞ » : effectif maximal non renseigné pour la classe (à fixer dans Classes pour calculer les places).</p>
            }
          </section>
        }

        <!-- ============================================================ candidatures -->
        @if (onglet() === 'candidatures') {
          @if (c.droits.saisir && !formCandidature()) {
            <button type="button" class="bouton petit" (click)="nouvelleCandidature(c)">Enregistrer une candidature</button>
          }
          @if (formCandidature(); as f) {
            <form class="carte sous-formulaire" (ngSubmit)="enregistrerCandidature(c)">
              <h2>{{ f.id ? 'Modifier la candidature' : 'Nouvelle candidature' }}</h2>
              <div class="grille-champs">
                <div class="champ">
                  <label for="kOffre">Niveau demandé</label>
                  <select id="kOffre" name="kOffre" [(ngModel)]="f.offreId">
                    @for (o of c.offres; track o.id) { <option [value]="o.id">{{ o.niveau }} {{ o.filiere }}</option> }
                  </select>
                </div>
                <div class="champ"><label for="kNom">Nom</label><input id="kNom" name="kNom" maxlength="80" required [(ngModel)]="f.nom" /></div>
                <div class="champ"><label for="kPrenoms">Prénoms</label><input id="kPrenoms" name="kPrenoms" maxlength="120" required [(ngModel)]="f.prenoms" /></div>
                <div class="champ">
                  <label for="kSexe">Sexe</label>
                  <select id="kSexe" name="kSexe" [(ngModel)]="f.sexe"><option value="F">Fille</option><option value="M">Garçon</option></select>
                </div>
                <div class="champ"><label for="kNaissance">Date de naissance</label><input id="kNaissance" name="kNaissance" type="date" required [(ngModel)]="f.dateNaissance" /></div>
                <div class="champ"><label for="kLieu">Lieu de naissance</label><input id="kLieu" name="kLieu" maxlength="120" [(ngModel)]="f.lieuNaissance" /></div>
                <div class="champ"><label for="kOrigine">Établissement d'origine</label><input id="kOrigine" name="kOrigine" maxlength="200" required [(ngModel)]="f.etablissementOrigine" /></div>
                <div class="champ"><label for="kClasse">Classe d'origine</label><input id="kClasse" name="kClasse" maxlength="40" [(ngModel)]="f.classeOrigine" /></div>
                <div class="champ"><label for="kMoyenne">Moyenne annuelle (/20)</label><input id="kMoyenne" name="kMoyenne" type="number" min="0" max="20" step="0.01" required [(ngModel)]="f.moyenneAnnuelle" /></div>
                <div class="champ"><label for="kExamen">Note d'examen (/20)</label><input id="kExamen" name="kExamen" type="number" min="0" max="20" step="0.01" [(ngModel)]="f.noteExamen" /></div>
                <div class="champ"><label for="kRespNom">Nom du responsable</label><input id="kRespNom" name="kRespNom" maxlength="80" required [(ngModel)]="f.responsableNom" /></div>
                <div class="champ"><label for="kRespPrenoms">Prénoms du responsable</label><input id="kRespPrenoms" name="kRespPrenoms" maxlength="120" required [(ngModel)]="f.responsablePrenoms" /></div>
                <div class="champ"><label for="kRespTel">Téléphone du responsable</label><input id="kRespTel" name="kRespTel" type="tel" maxlength="20" required [(ngModel)]="f.responsableTelephone" /></div>
                <div class="champ">
                  <label for="kLien">Lien</label>
                  <select id="kLien" name="kLien" [(ngModel)]="f.responsableLien">
                    @for (l of liens; track l) { <option [value]="l">{{ libelleLien[l] }}</option> }
                  </select>
                </div>
              </div>
              <label class="case"><input type="checkbox" name="kRedoublant" [(ngModel)]="f.redoublant" /> Redoublant</label>
              <div class="champ"><label for="kObs">Observations</label><input id="kObs" name="kObs" maxlength="500" [(ngModel)]="f.observations" /></div>
              <div class="actions-ligne">
                <button type="submit" class="bouton" [disabled]="action.enCours()">Enregistrer</button>
                <button type="button" class="bouton secondaire" (click)="formCandidature.set(null)">Annuler</button>
              </div>
            </form>
          }
          <section class="carte">
            <div class="tableau-defilant">
              <table class="tableau">
                <thead><tr><th>N°</th><th>Candidat</th><th>Niveau</th><th>Origine</th><th class="nombre">Moyenne</th><th>Statut</th><th></th></tr></thead>
                <tbody>
                  @for (k of candidatures(); track k.id) {
                    <tr>
                      <td class="petit-texte">{{ k.numero }}</td>
                      <td>{{ k.nom }} {{ k.prenoms }} <span class="doux petit-texte">({{ k.sexe === 'F' ? 'F' : 'G' }}, {{ dateCourte(k.dateNaissance) }})</span></td>
                      <td>{{ k.offre }}</td>
                      <td class="petit-texte">{{ k.etablissementOrigine }}@if (k.classeOrigine) { · {{ k.classeOrigine }} }</td>
                      <td class="nombre">{{ note(k.moyenneAnnuelle) }}@if (k.noteExamen !== null) { <br /><span class="doux petit-texte">examen {{ note(k.noteExamen) }}</span> }</td>
                      <td>
                        <span class="pastille {{ classeStatut[k.statut] }}">{{ libelleCandidature[k.statut] }}</span>
                        @if (k.motif) { <br /><span class="doux petit-texte">{{ k.motif }}</span> }
                      </td>
                      <td>
                        @if (c.droits.saisir) {
                          <div class="actions-ligne">
                            <button type="button" class="bouton discret petit" (click)="modifierCandidature(k)">Modifier</button>
                            @if (peutPreparer) {
                              @if (k.statut === 'IRRECEVABLE') {
                                <button type="button" class="bouton discret petit" (click)="recevable(k, true)">Recevable</button>
                              } @else {
                                <button type="button" class="bouton danger petit" (click)="irrecevable.set(k)">Irrecevable</button>
                              }
                            }
                          </div>
                        }
                      </td>
                    </tr>
                  } @empty {
                    <tr><td colspan="7" class="doux">Aucune candidature.</td></tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
          @if (irrecevable(); as k) {
            <form class="carte sous-formulaire" (ngSubmit)="recevable(k, false)">
              <h3>Candidature irrecevable : {{ k.nom }} {{ k.prenoms }}</h3>
              <div class="champ"><label for="kMotif">Motif</label><input id="kMotif" name="kMotif" maxlength="300" required [(ngModel)]="motif" placeholder="Ex. dossier incomplet, limite d'âge dépassée" /></div>
              <div class="actions-ligne">
                <button type="submit" class="bouton" [disabled]="!motif.trim()">Confirmer</button>
                <button type="button" class="bouton secondaire" (click)="irrecevable.set(null)">Annuler</button>
              </div>
            </form>
          }
        }

        <!-- ============================================================ classement et résultats -->
        @if (onglet() === 'classement') {
          @if (c.statut === 'PREPARATION') {
            <div class="alerte" role="status">Aperçu du classement au mérite : rien n'est figé avant la délibération du président.</div>
          } @else {
            <div class="actions-ligne marge">
              <button type="button" class="bouton secondaire petit" (click)="telechargerPv(c)">Procès-verbal (PDF)</button>
              <button type="button" class="bouton secondaire petit" (click)="telechargerResultats(c, 'pdf')">Résultats à afficher (PDF)</button>
              <button type="button" class="bouton secondaire petit" (click)="telechargerResultats(c, 'xlsx')">Résultats (Excel)</button>
            </div>
          }
          @for (o of classement(); track o.offreId) {
            <section class="carte">
              <h2>{{ o.offre }} — {{ o.places }} place(s)</h2>
              <div class="tableau-defilant">
                <table class="tableau">
                  <thead><tr><th class="nombre">Rang</th><th>Candidat</th><th class="nombre">Moyenne</th><th class="nombre">Score</th><th>Décision</th><th></th></tr></thead>
                  <tbody>
                    @for (l of o.lignes; track l.candidatureId) {
                      <tr>
                        <td class="nombre">{{ l.rang ?? '—' }}</td>
                        <td>{{ l.nom }} {{ l.prenoms }} <span class="doux petit-texte">{{ l.numero }}</span></td>
                        <td class="nombre">{{ note(l.moyenneAnnuelle) }}</td>
                        <td class="nombre">{{ note(l.score) }}</td>
                        <td>
                          <span class="pastille {{ classeStatut[l.decision] }}">{{ libelleCandidature[l.decision] }}</span>
                          @if (detail(l.candidatureId)?.repechee) { <span class="doux petit-texte"> (repêché)</span> }
                          @if (detail(l.candidatureId)?.classeInscription; as k) { <span class="doux petit-texte"> · {{ k }}</span> }
                          @if (l.motif && l.decision !== 'RETENUE') { <br /><span class="doux petit-texte">{{ l.motif }}</span> }
                        </td>
                        <td>
                          @if (c.droits.inscrire && l.decision === 'RETENUE') {
                            <div class="actions-ligne">
                              <select [attr.aria-label]="'Classe pour ' + l.nom" [(ngModel)]="classeChoisie[l.candidatureId]" [name]="'cl' + l.candidatureId">
                                @for (k of classesDe(c, o.offreId); track k.id) {
                                  <option [value]="k.id" [disabled]="k.effectifMax !== null && k.inscrits >= k.effectifMax">{{ k.code }} ({{ k.inscrits }}/{{ k.effectifMax ?? '∞' }})</option>
                                }
                              </select>
                              <button type="button" class="bouton petit" [disabled]="action.enCours() || !classeChoisie[l.candidatureId]" (click)="inscrire(c, l.candidatureId)">Inscrire</button>
                              <button type="button" class="bouton danger petit" [disabled]="action.enCours()" (click)="desister(c, l.candidatureId)">Désistement</button>
                            </div>
                          } @else if (c.droits.inscrire && l.decision === 'LISTE_ATTENTE') {
                            <button type="button" class="bouton discret petit" [disabled]="action.enCours()" (click)="desister(c, l.candidatureId)">Désistement</button>
                          }
                        </td>
                      </tr>
                    } @empty {
                      <tr><td colspan="6" class="doux">Aucune candidature pour ce niveau.</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            </section>
          }
          @if (c.droits.deliberer) {
            <section class="carte">
              <h2>Délibération</h2>
              <p class="doux">Le président fige les résultats : retenus dans la limite des places, liste d'attente, non retenus. Les candidatures ne sont plus modifiables ensuite.</p>
              <div class="champ"><label for="dObs">Observations du procès-verbal</label><textarea id="dObs" name="dObs" rows="3" maxlength="4000" [(ngModel)]="observations"></textarea></div>
              <button type="button" class="bouton" [disabled]="action.enCours() || !candidatures().length" (click)="deliberer(c)">Délibérer</button>
            </section>
          }
          @if (c.droits.clore) {
            <button type="button" class="bouton secondaire" [disabled]="action.enCours()" (click)="clore(c)">Clore la commission</button>
          }
        }
      }
    </div>
  `,
  styles: `
    .bascule {
      display: inline-flex;
      flex-wrap: wrap;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      padding: 0.2rem;
      margin-bottom: 0.75rem;
      background: var(--surface);
      button {
        min-height: 38px;
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
    .petit-texte {
      font-size: 0.85rem;
    }
    .marge {
      margin-bottom: 0.75rem;
    }
    td input[type='number'] {
      width: 5.5rem;
    }
    td input[type='text'] {
      min-width: 9rem;
    }
    textarea {
      width: 100%;
    }
    .case {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      margin: 0.25rem 0 0.75rem;
    }
  `,
})
export class CommissionPage {
  private readonly api = inject(RecrutementApi);
  private readonly admin = inject(AdminApi);
  private readonly session = inject(SessionService);

  readonly id = input.required<string>();

  protected readonly peutPreparer = this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR');
  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly c = signal<CommissionVue | null>(null);
  protected readonly onglet = signal<Onglet>('commission');
  protected readonly candidatures = signal<CandidatureVue[]>([]);
  protected readonly classement = signal<ClassementOffre[]>([]);
  protected readonly membresEdites = signal<MembreSaisi[] | null>(null);
  protected readonly personnel = signal<MembreVue[]>([]);
  protected readonly formCandidature = signal<FormCandidature | null>(null);
  protected readonly irrecevable = signal<CandidatureVue | null>(null);
  protected places: Record<string, number> = {};
  protected classeChoisie: Record<string, string> = {};
  protected motif = '';
  protected observations = '';

  protected readonly qualites = QUALITES;
  protected readonly liens: Lien[] = ['PERE', 'MERE', 'TUTEUR', 'AUTRE'];
  protected readonly libelleQualite = LIBELLE_QUALITE;
  protected readonly libelleLien = LIBELLE_LIEN;
  protected readonly libelleStatut = LIBELLE_STATUT_COMMISSION;
  protected readonly libelleCandidature = LIBELLE_STATUT_CANDIDATURE;
  protected readonly classeStatut = CLASSE_STATUT_CANDIDATURE;
  protected readonly note = note;
  protected readonly dateCourte = dateCourte;
  protected readonly dateHeure = dateHeureCourte;
  protected readonly totalPlaces = computed(() => (this.c()?.offres ?? []).reduce((s, o) => s + o.places, 0));
  private readonly parId = computed(() => new Map(this.candidatures().map((k) => [k.id, k])));

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => void this.charger(id));
    });
  }

  private async charger(id: string): Promise<void> {
    const [c, k] = await Promise.all([
      this.action.executer(() => this.api.commission(id)),
      this.action.executer(() => this.api.candidatures(id)),
    ]);
    if (c) {
      this.appliquer(c);
    }
    if (k) {
      this.candidatures.set(k);
    }
  }

  private appliquer(c: CommissionVue): void {
    this.c.set(c);
    this.places = Object.fromEntries(c.offres.map((o) => [o.id, o.places]));
  }

  protected detail(id: string): CandidatureVue | undefined {
    return this.parId().get(id);
  }

  protected classesDe(c: CommissionVue, offreId: string): OffreVue['classes'] {
    return c.offres.find((o) => o.id === offreId)?.classes ?? [];
  }

  // ------------------------------------------------------------ membres

  protected async editerMembres(c: CommissionVue): Promise<void> {
    this.membresEdites.set(c.membres.map((m) => ({
      qualite: m.qualite, utilisateurId: m.utilisateurId, nom: m.utilisateurId ? null : m.nom, fonction: m.fonction, present: m.present,
    })));
    if (!this.personnel().length) {
      const p = await this.action.executer(() => this.admin.membres());
      if (p) {
        const vus = new Set<string>();
        this.personnel.set(p.filter((m) => m.actif && m.role !== 'PARENT' && m.role !== 'ELEVE' && !vus.has(m.utilisateurId) && vus.add(m.utilisateurId)));
      }
    }
  }

  protected ajouterMembre(): void {
    this.membresEdites.update((l) => [...(l ?? []), { qualite: 'MEMBRE' as Qualite, utilisateurId: null, nom: '', fonction: '', present: true }]);
  }

  protected retirerMembre(i: number): void {
    this.membresEdites.update((l) => (l ?? []).filter((_, j) => j !== i));
  }

  protected async enregistrerMembres(c: CommissionVue): Promise<void> {
    const l = this.membresEdites() ?? [];
    const v = await this.action.executer(() => this.api.membres(c.id, l.map((m) => ({ ...m, nom: m.utilisateurId ? null : m.nom }))));
    if (v) {
      this.appliquer(v);
      this.membresEdites.set(null);
      this.message.set('Membres enregistrés.');
    }
  }

  // ------------------------------------------------------------ places

  protected async enregistrerPlaces(c: CommissionVue): Promise<void> {
    const v = await this.action.executer(() =>
      this.api.offres(c.id, c.offres.map((o) => ({ offreId: o.id, places: Number(this.places[o.id] ?? 0) }))),
    );
    if (v) {
      this.appliquer(v);
      this.message.set('Places enregistrées.');
    }
  }

  protected async actualiserOffres(c: CommissionVue): Promise<void> {
    const v = await this.action.executer(() => this.api.actualiserOffres(c.id));
    if (v) {
      this.appliquer(v);
    }
  }

  // ------------------------------------------------------------ candidatures

  protected async afficherCandidatures(): Promise<void> {
    this.onglet.set('candidatures');
    const k = await this.action.executer(() => this.api.candidatures(this.id()));
    if (k) {
      this.candidatures.set(k);
    }
  }

  protected nouvelleCandidature(c: CommissionVue): void {
    this.formCandidature.set({
      id: null, offreId: c.offres.find((o) => o.places > 0)?.id ?? c.offres[0]?.id ?? '', nom: '', prenoms: '', sexe: 'F',
      dateNaissance: '', lieuNaissance: null, responsableNom: '', responsablePrenoms: '', responsableTelephone: '',
      responsableLien: 'PERE', etablissementOrigine: '', classeOrigine: null, moyenneAnnuelle: null, noteExamen: null,
      redoublant: false, observations: null,
    });
  }

  protected modifierCandidature(k: CandidatureVue): void {
    this.formCandidature.set({ ...k, id: k.id });
  }

  protected async enregistrerCandidature(c: CommissionVue): Promise<void> {
    const f = this.formCandidature();
    if (!f) {
      return;
    }
    const d: DonneesCandidature = {
      offreId: f.offreId, nom: f.nom, prenoms: f.prenoms, sexe: f.sexe, dateNaissance: f.dateNaissance,
      lieuNaissance: f.lieuNaissance || null, responsableNom: f.responsableNom, responsablePrenoms: f.responsablePrenoms,
      responsableTelephone: f.responsableTelephone, responsableLien: f.responsableLien, etablissementOrigine: f.etablissementOrigine,
      classeOrigine: f.classeOrigine || null, moyenneAnnuelle: Number(f.moyenneAnnuelle),
      noteExamen: f.noteExamen === null || (f.noteExamen as unknown) === '' ? null : Number(f.noteExamen),
      redoublant: !!f.redoublant, observations: f.observations || null,
    };
    const k = await this.action.executer(() => (f.id ? this.api.modifierCandidature(f.id, d) : this.api.enregistrer(c.id, d)));
    if (k) {
      this.formCandidature.set(null);
      this.message.set(`Candidature ${k.numero} enregistrée.`);
      await this.recharger(c.id);
    }
  }

  protected async recevable(k: CandidatureVue, recevable: boolean): Promise<void> {
    const v = await this.action.executer(() => this.api.recevabilite(k.id, recevable, recevable ? null : this.motif.trim()));
    if (v) {
      this.irrecevable.set(null);
      this.motif = '';
      this.candidatures.update((l) => l.map((x) => (x.id === v.id ? v : x)));
    }
  }

  // ------------------------------------------------------------ classement, délibération, résultats

  protected async afficherClassement(): Promise<void> {
    this.onglet.set('classement');
    const [k, l] = await Promise.all([
      this.action.executer(() => this.api.classement(this.id())),
      this.action.executer(() => this.api.candidatures(this.id())),
    ]);
    if (k) {
      this.classement.set(k);
    }
    if (l) {
      this.candidatures.set(l);
    }
  }

  protected async deliberer(c: CommissionVue): Promise<void> {
    const v = await this.action.executer(() => this.api.deliberer(c.id, this.observations.trim() || null));
    if (v) {
      this.appliquer(v);
      this.message.set('Délibération enregistrée : les résultats sont figés.');
      await this.afficherClassement();
    }
  }

  protected async inscrire(c: CommissionVue, candidatureId: string): Promise<void> {
    const classeId = this.classeChoisie[candidatureId];
    const k = await this.action.executer(() => this.api.inscrire(candidatureId, classeId));
    if (k) {
      this.message.set(`${k.nom} ${k.prenoms} inscrit(e) en ${k.classeInscription} (complément d'effectif).`);
      await this.recharger(c.id);
      await this.afficherClassement();
    }
  }

  protected async desister(c: CommissionVue, candidatureId: string): Promise<void> {
    const r = await this.action.executer(() => this.api.desister(candidatureId, null));
    if (r) {
      this.message.set(
        `Désistement de ${r.desistee.nom} ${r.desistee.prenoms} enregistré.` +
          (r.repechee ? ` ${r.repechee.nom} ${r.repechee.prenoms} est repêché(e) sur la liste d'attente.` : ''),
      );
      await this.recharger(c.id);
      await this.afficherClassement();
    }
  }

  protected async clore(c: CommissionVue): Promise<void> {
    const v = await this.action.executer(() => this.api.clore(c.id));
    if (v) {
      this.appliquer(v);
      this.message.set('Commission close.');
    }
  }

  protected async telechargerPv(c: CommissionVue): Promise<void> {
    const f = await this.action.executer(() => this.api.procesVerbal(c.id));
    if (f) {
      enregistrerFichier(f, `proces-verbal-${c.numero}.pdf`);
    }
  }

  protected async telechargerResultats(c: CommissionVue, format: 'xlsx' | 'pdf'): Promise<void> {
    const f = await this.action.executer(() => this.api.resultats(c.id, format));
    if (f) {
      enregistrerFichier(f, `resultats-${c.numero}.${format}`);
    }
  }

  private async recharger(id: string): Promise<void> {
    const [c, k] = await Promise.all([
      this.action.executer(() => this.api.commission(id)),
      this.action.executer(() => this.api.candidatures(id)),
    ]);
    if (c) {
      this.appliquer(c);
    }
    if (k) {
      this.candidatures.set(k);
    }
  }
}
