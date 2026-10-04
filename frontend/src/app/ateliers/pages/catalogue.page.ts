import { Component, computed, inject, OnDestroy, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { FiliereVue } from '../../admin/modeles-admin';
import { dateHeureCourte } from '../../core/outils';
import { filtrer } from '../../core/recherche';
import { SessionService } from '../../core/session.service';
import { RechercheComponent } from '../../partage/recherche.component';
import { fcfa, lireMontant } from '../../scolarite/modeles-scolarite';
import { AteliersNavComponent } from '../ateliers-nav.component';
import { AteliersApi } from '../ateliers-api.service';
import { ArticleVue, LIBELLE_NATURE, NatureArticle } from '../modeles-ateliers';

/** Contrôle d'un article avant l'envoi. */
export function erreurArticle(d: { code: string; designation: string; unite: string; prix: string }): string | null {
  if (!d.code.trim()) {
    return 'Donnez un code court (FIL-2.5, PERC-COL…).';
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9_./-]*$/.test(d.code.trim())) {
    return 'Code : lettres, chiffres, tirets, points ou barres obliques.';
  }
  if (!d.designation.trim()) {
    return 'Donnez la désignation.';
  }
  if (!d.unite.trim()) {
    return 'Indiquez l’unité (pièce, mètre, kg, litre, rouleau…).';
  }
  if (d.prix.trim() && lireMontant(d.prix) === null) {
    return 'Prix en francs CFA, sans centimes.';
  }
  return null;
}

const TYPES_PHOTO = ['image/jpeg', 'image/png', 'image/webp'];

/**
 * Catalogue des prix : matière d'œuvre et équipements, avec spécifications et normes (définies
 * par les enseignants spécialistes de la filière) et prix de référence (convenu avec l'intendant).
 * Tenu par le chef des travaux, l'intendant et les responsables d'atelier ; consultable par tous.
 */
@Component({
  selector: 'app-catalogue',
  imports: [FormsModule, RechercheComponent, AteliersNavComponent],
  template: `
    <div class="page large">
      <h1>Catalogue des prix</h1>
      <app-ateliers-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message()) {
        <div class="alerte succes" role="status">{{ message() }}</div>
      }

      <section class="carte">
        <div class="entete-section">
          <div class="bascule" role="group" aria-label="Nature">
            <button type="button" [class.actif]="nature() === null" (click)="nature.set(null)">Tout ({{ articles().length }})</button>
            <button type="button" [class.actif]="nature() === 'MATIERE_OEUVRE'" (click)="nature.set('MATIERE_OEUVRE')">Matière d’œuvre</button>
            <button type="button" [class.actif]="nature() === 'EQUIPEMENT'" (click)="nature.set('EQUIPEMENT')">Équipements</button>
          </div>
          @if (peutModifier() && !ouvert()) {
            <button type="button" class="bouton petit" (click)="nouveau()">Nouvel article</button>
          }
        </div>

        @if (ouvert()) {
          <form class="sous-formulaire" (ngSubmit)="enregistrer()">
            <h2>{{ edite() ? 'Modifier ' + edite()!.code : 'Nouvel article' }}</h2>
            <div class="grille-champs">
              <div class="champ">
                <label for="art-code">Code</label>
                <input id="art-code" name="code" [disabled]="!!edite()" [(ngModel)]="code" />
              </div>
              <div class="champ">
                <label for="art-nature">Nature</label>
                <select id="art-nature" name="nature" [disabled]="!!edite()" [ngModel]="natureSaisie()" (ngModelChange)="natureSaisie.set($event)">
                  <option value="MATIERE_OEUVRE">Matière d’œuvre (suivie en quantité)</option>
                  <option value="EQUIPEMENT">Équipement (suivi un par un)</option>
                </select>
              </div>
              <div class="champ">
                <label for="art-designation">Désignation</label>
                <input id="art-designation" name="designation" [(ngModel)]="designation" />
              </div>
              <div class="champ">
                <label for="art-unite">Unité</label>
                <input id="art-unite" name="unite" placeholder="pièce, mètre, kg, rouleau…" [(ngModel)]="unite" />
              </div>
              <div class="champ">
                <label for="art-filiere">Spécialité <span class="doux">(facultatif)</span></label>
                <select id="art-filiere" name="filiere" [ngModel]="filiere()" (ngModelChange)="filiere.set($event)">
                  <option value="">Toutes filières</option>
                  @for (f of filieres(); track f.id) { <option [value]="f.id">{{ f.code }} · {{ f.libelle }}</option> }
                </select>
              </div>
              <div class="champ">
                <label for="art-prix">Prix de référence (FCFA)</label>
                <input id="art-prix" name="prix" inputmode="numeric" [(ngModel)]="prix" />
              </div>
            </div>
            <div class="champ">
              <label for="art-specs">Spécifications</label>
              <textarea id="art-specs" name="specs" rows="3" placeholder="Dimensions, matériau, puissance, qualité attendue…" [(ngModel)]="specifications"></textarea>
            </div>
            <div class="champ">
              <label for="art-normes">Normes</label>
              <input id="art-normes" name="normes" placeholder="NF C 15-100, ISO…" [(ngModel)]="normes" />
            </div>
            @if (edite()) {
              <label class="case"><input type="checkbox" name="actif" [ngModel]="actif()" (ngModelChange)="actif.set($event)" /> Toujours au catalogue</label>
            }
            @if (code() && designation() && erreurSaisie(); as e) { <p class="rouge" role="alert">{{ e }}</p> }
            @if (enregistrement.erreur()) { <div class="alerte erreur" role="alert">{{ enregistrement.erreur() }}</div> }
            <div class="actions-ligne">
              <button type="submit" class="bouton petit" [disabled]="!!erreurSaisie() || enregistrement.enCours()">Enregistrer</button>
              <button type="button" class="bouton discret petit" (click)="ouvert.set(false)">Fermer</button>
            </div>
          </form>
        }

        <app-recherche libelle="Code, désignation, norme" [(valeur)]="filtre" [total]="articles().length" [trouves]="affiches().length" />

        <ul class="liste">
          @for (a of affiches(); track a.id) {
            <li class="article" [class.inactif]="!a.actif">
              <div class="ligne-article">
                <span>
                  <strong>{{ a.designation }}</strong><span class="doux"> · {{ a.code }} · {{ libelleNature[a.nature] }}@if (a.filiereCode) { · {{ a.filiereCode }} }</span>
                  @if (!a.actif) { <span class="pastille">retiré</span> }
                  <br />
                  <span>{{ a.prixReference === null ? 'Prix à fixer' : fcfa(a.prixReference) + ' / ' + a.unite }}</span>
                  @if (a.prixModifieLe) { <span class="doux"> (prix du {{ dateHeureCourte(a.prixModifieLe) }})</span> }
                  @if (a.specifications) { <br /><span class="doux">{{ a.specifications }}</span> }
                  @if (a.normes) { <br /><span class="doux">Normes : {{ a.normes }}</span> }
                </span>
                <span class="actions-ligne">
                  @if (a.photo) {
                    <button type="button" class="bouton discret petit" (click)="basculerPhoto(a)">{{ photos()[a.id] ? 'Masquer la photo' : 'Photo' }}</button>
                  }
                  @if (peutModifier()) {
                    <button type="button" class="bouton secondaire petit" (click)="modifier(a)">Modifier</button>
                    <label class="bouton discret petit fichier">
                      {{ a.photo ? 'Changer la photo' : 'Ajouter une photo' }}
                      <input type="file" accept="image/jpeg,image/png,image/webp" class="visuellement-cache" (change)="envoyerPhoto(a, $event)" />
                    </label>
                  }
                </span>
              </div>
              @if (photos()[a.id]; as url) {
                <img class="photo" [src]="url" [alt]="'Photo : ' + a.designation" />
              }
            </li>
          } @empty {
            <li class="doux">{{ articles().length ? 'Aucun article ne correspond.' : 'Le catalogue est vide.' }}</li>
          }
        </ul>
      </section>
    </div>
  `,
  styles: `
    .bascule {
      display: inline-flex;
      flex-wrap: wrap;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      padding: 0.2rem;
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
    h2 {
      font-size: 1.05rem;
      margin: 0 0 0.5rem;
    }
    .sous-formulaire {
      margin: 0.75rem 0;
      padding: 0.75rem;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      background: var(--fond);
    }
    .article {
      padding: 0.6rem 0;
      border-bottom: 1px solid var(--bordure);
    }
    .ligne-article .pastille {
      margin-left: 0.4rem;
    }
    .article.inactif {
      opacity: 0.65;
    }
    .ligne-article {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 0.5rem;
    }
    .photo {
      display: block;
      max-width: min(100%, 22rem);
      max-height: 16rem;
      margin-top: 0.5rem;
      border-radius: var(--rayon);
      border: 1px solid var(--bordure);
    }
    .fichier {
      cursor: pointer;
    }
    .case {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
    }
    .rouge {
      color: var(--absent);
    }
  `,
})
export class CataloguePage implements OnDestroy {
  private readonly api = inject(AteliersApi);
  private readonly admin = inject(AdminApi);
  private readonly session = inject(SessionService);

  protected readonly fcfa = fcfa;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly libelleNature = LIBELLE_NATURE;
  protected readonly action = new Action();
  protected readonly enregistrement = new Action();
  protected readonly message = signal<string | null>(null);

  protected readonly articles = signal<ArticleVue[]>([]);
  protected readonly filieres = signal<FiliereVue[]>([]);
  protected readonly nature = signal<NatureArticle | null>(null);
  protected readonly filtre = signal('');
  protected readonly photos = signal<Record<string, string>>({});
  /** Un enseignant ne modifie le catalogue que s'il est responsable d'un atelier. */
  private readonly responsable = signal(false);
  protected readonly peutModifier = computed(
    () => this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'INTENDANT') || this.responsable(),
  );

  protected readonly ouvert = signal(false);
  protected readonly edite = signal<ArticleVue | null>(null);
  protected readonly code = signal('');
  protected readonly natureSaisie = signal<NatureArticle>('MATIERE_OEUVRE');
  protected readonly designation = signal('');
  protected readonly unite = signal('');
  protected readonly filiere = signal('');
  protected readonly prix = signal('');
  protected readonly specifications = signal('');
  protected readonly normes = signal('');
  protected readonly actif = signal(true);

  protected readonly erreurSaisie = computed(() =>
    erreurArticle({ code: this.code(), designation: this.designation(), unite: this.unite(), prix: this.prix() }),
  );

  protected readonly affiches = computed(() => {
    const n = this.nature();
    const base = n ? this.articles().filter((a) => a.nature === n) : this.articles();
    return filtrer(base, this.filtre(), (a) => [a.code, a.designation, a.normes, a.specifications, a.filiereCode]);
  });

  constructor() {
    void this.charger();
  }

  ngOnDestroy(): void {
    Object.values(this.photos()).forEach((u) => URL.revokeObjectURL(u));
  }

  protected nouveau(): void {
    this.edite.set(null);
    this.code.set('');
    this.natureSaisie.set(this.nature() ?? 'MATIERE_OEUVRE');
    this.designation.set('');
    this.unite.set('');
    this.filiere.set('');
    this.prix.set('');
    this.specifications.set('');
    this.normes.set('');
    this.actif.set(true);
    this.ouvert.set(true);
    this.message.set(null);
  }

  protected modifier(a: ArticleVue): void {
    this.edite.set(a);
    this.code.set(a.code);
    this.natureSaisie.set(a.nature);
    this.designation.set(a.designation);
    this.unite.set(a.unite);
    this.filiere.set(a.filiereId ?? '');
    this.prix.set(a.prixReference === null ? '' : String(a.prixReference));
    this.specifications.set(a.specifications ?? '');
    this.normes.set(a.normes ?? '');
    this.actif.set(a.actif);
    this.ouvert.set(true);
    this.message.set(null);
  }

  protected async enregistrer(): Promise<void> {
    if (this.erreurSaisie()) {
      return;
    }
    const d = {
      code: this.code().trim(),
      designation: this.designation().trim(),
      nature: this.natureSaisie(),
      unite: this.unite().trim(),
      filiereId: this.filiere() || null,
      specifications: this.specifications().trim() || null,
      normes: this.normes().trim() || null,
      prixReference: this.prix().trim() ? lireMontant(this.prix()) : null,
      actif: this.actif(),
    };
    const e = this.edite();
    const r = await this.enregistrement.executer(() => (e ? this.api.modifierArticle(e.id, d) : this.api.creerArticle(d)));
    if (r) {
      this.remplacer(r);
      this.ouvert.set(false);
      this.message.set(e ? `${r.designation} : modifications enregistrées.` : `${r.designation} ajouté au catalogue.`);
    }
  }

  protected async basculerPhoto(a: ArticleVue): Promise<void> {
    const actuelle = this.photos()[a.id];
    if (actuelle) {
      URL.revokeObjectURL(actuelle);
      const reste = { ...this.photos() };
      delete reste[a.id];
      this.photos.set(reste);
      return;
    }
    const blob = await this.action.executer(() => this.api.photo(a.id));
    if (blob) {
      this.photos.set({ ...this.photos(), [a.id]: URL.createObjectURL(blob) });
    }
  }

  protected async envoyerPhoto(a: ArticleVue, evenement: Event): Promise<void> {
    const champ = evenement.target as HTMLInputElement;
    const fichier = champ.files?.[0];
    champ.value = '';
    if (!fichier) {
      return;
    }
    if (!TYPES_PHOTO.includes(fichier.type)) {
      this.action.erreur.set('Photo au format JPEG, PNG ou WebP.');
      return;
    }
    if (fichier.size > 2 * 1024 * 1024) {
      this.action.erreur.set('Photo de 2 Mo au plus : réduisez-la avant de l’envoyer.');
      return;
    }
    const r = await this.action.executer(() => this.api.envoyerPhoto(a.id, fichier));
    if (r) {
      this.remplacer(r);
      const ancienne = this.photos()[a.id];
      if (ancienne) {
        URL.revokeObjectURL(ancienne);
      }
      this.photos.set({ ...this.photos(), [a.id]: URL.createObjectURL(fichier) });
      this.message.set(`Photo enregistrée pour ${r.designation}.`);
    }
  }

  private remplacer(a: ArticleVue): void {
    const l = this.articles().filter((x) => x.id !== a.id);
    this.articles.set([...l, a].sort((x, y) => x.nature.localeCompare(y.nature) || x.designation.localeCompare(y.designation)));
  }

  private async charger(): Promise<void> {
    const [articles, filieres] = await Promise.all([
      this.action.executer(() => this.api.catalogue()),
      this.admin.filieres().catch(() => []),
    ]);
    if (articles) {
      this.articles.set(articles);
    }
    this.filieres.set(filieres);
    if (!this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'INTENDANT')) {
      const ateliers = await this.api.ateliers().catch(() => []);
      this.responsable.set(ateliers.some((a) => a.droits.responsable));
    }
  }
}
