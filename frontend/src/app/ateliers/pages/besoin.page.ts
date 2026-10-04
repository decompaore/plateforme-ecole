import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte, dateHeureCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { fcfa } from '../../scolarite/modeles-scolarite';
import { AteliersNavComponent } from '../ateliers-nav.component';
import { AteliersApi } from '../ateliers-api.service';
import { ExportBoutonsComponent } from '../export-boutons.component';
import { ArticleVue, LIBELLE_NATURE, lireQuantite, quantite } from '../modeles-ateliers';
import { BesoinVue, CLASSE_STATUT_BESOIN, LIBELLE_STATUT_BESOIN, LIBELLE_TYPE_CAMPAGNE, ROLES_BESOINS } from '../modeles-besoins';

/** Contrôle d'une ligne de besoin avant l'envoi. */
export function erreurLigneBesoin(article: ArticleVue | undefined, texte: string): string | null {
  if (!article) {
    return 'Choisissez un article du catalogue.';
  }
  const q = lireQuantite(texte);
  if (q === null) {
    return 'Quantité positive, deux décimales au plus.';
  }
  if (article.nature === 'EQUIPEMENT' && !Number.isInteger(q)) {
    return 'Un équipement se compte à l’unité : quantité entière.';
  }
  return null;
}

/**
 * Fiche de besoins d'un atelier dans une campagne. Les enseignants techniques de l'atelier
 * proposent les articles du catalogue ; le responsable transmet au chef des travaux ; la
 * direction arbitre les quantités (avec le proviseur et l'intendant), renvoie ou valide.
 */
@Component({
  selector: 'app-besoin',
  imports: [FormsModule, RouterLink, AteliersNavComponent, ExportBoutonsComponent],
  template: `
    <div class="page large">
      <h1>Besoins de l'atelier</h1>
      <app-ateliers-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (besoin(); as b) {
        <section class="carte">
          <div class="entete-section">
            <p class="infos">
              <a [routerLink]="['/ateliers', b.atelierId]"><strong>{{ b.atelierCode }} · {{ b.atelierNom }}</strong></a>
              @if (suiviCampagne()) {
                <a [routerLink]="['/ateliers/besoins', b.campagneId]">{{ b.campagne }}</a>
              } @else {
                <span>{{ b.campagne }}</span>
              }
              <span class="pastille {{ classeStatut[b.statut] }}">{{ libelleStatut[b.statut] }}</span>
            </p>
            <app-export [chemin]="'/besoins-ateliers/' + b.id" [nom]="'besoins-' + b.atelierCode" libelle="la fiche de besoins" />
          </div>
          <p class="doux">
            {{ libelleType[b.type] }}
            @if (b.dateLimite && b.statutCampagne === 'OUVERTE') { · à remettre avant le {{ dateCourte(b.dateLimite) }} }
            @if (b.transmisLe) { · transmis le {{ dateHeureCourte(b.transmisLe) }}@if (b.transmisPar) { par {{ b.transmisPar }} } }
            @if (b.valideLe) { · validé le {{ dateHeureCourte(b.valideLe) }} }
          </p>
          @if (b.commentaire && b.statut === 'BROUILLON') {
            <p class="alerte attention">Renvoyé par le chef des travaux : {{ b.commentaire }}</p>
          }
          @if (b.statutCampagne !== 'OUVERTE') {
            <p class="doux">La campagne est {{ b.statutCampagne === 'TRANSMISE' ? 'transmise à la direction régionale' : 'close' }} : la fiche ne change plus.</p>
          } @else if (b.statut === 'TRANSMIS' && !b.droits.arbitrer) {
            <p class="doux">Transmis au chef des travaux : en attente de sa validation.</p>
          }
        </section>

        @if (message()) {
          <div class="alerte succes" role="status">{{ message() }}</div>
        }
        @if (enregistrement.erreur()) {
          <div class="alerte erreur" role="alert">{{ enregistrement.erreur() }}</div>
        }

        <section class="carte">
          <div class="entete-section">
            <h2>Articles demandés</h2>
            <span class="actions-ligne">
              @if (b.droits.proposer && !ajout()) {
                <button type="button" class="bouton petit" (click)="ouvrirAjout()">Ajouter un article</button>
              }
              @if (b.droits.transmettre && b.lignes.length) {
                <button type="button" class="bouton petit" [disabled]="enregistrement.enCours()" (click)="transmettre()">Transmettre au chef des travaux</button>
              }
            </span>
          </div>

          @if (ajout()) {
            <form class="sous-formulaire" (ngSubmit)="ajouter()">
              <div class="grille-champs">
                <div class="champ">
                  <label for="bl-article">Article du catalogue</label>
                  <select id="bl-article" name="article" [ngModel]="article()" (ngModelChange)="article.set($event)">
                    <option value="">— choisir —</option>
                    @for (a of articles(); track a.id) {
                      <option [value]="a.id">{{ a.designation }} ({{ a.unite }})</option>
                    }
                  </select>
                </div>
                <div class="champ">
                  <label for="bl-quantite">Quantité @if (articleChoisi(); as a) { <span class="doux">en {{ a.unite }}</span> }</label>
                  <input id="bl-quantite" name="quantite" inputmode="decimal" [(ngModel)]="quantiteTexte" />
                </div>
              </div>
              @if (articleChoisi(); as a) {
                @if (a.specifications || a.normes) {
                  <p class="doux">@if (a.specifications) { {{ a.specifications }} }@if (a.normes) { · normes : {{ a.normes }} }</p>
                }
              }
              <div class="champ">
                <label for="bl-justif">Justification <span class="doux">(TP concernés, effectifs…)</span></label>
                <input id="bl-justif" name="justif" [(ngModel)]="justification" />
              </div>
              <p class="doux">Un article déjà demandé est mis à jour avec la nouvelle quantité.</p>
              @if (article() && quantiteTexte() && erreurSaisie(); as e) { <p class="rouge" role="alert">{{ e }}</p> }
              <div class="actions-ligne">
                <button type="submit" class="bouton petit" [disabled]="!!erreurSaisie() || enregistrement.enCours()">Enregistrer</button>
                <button type="button" class="bouton discret petit" (click)="ajout.set(false)">Fermer</button>
              </div>
            </form>
          }

          <div class="tableau-defilant">
            <table>
              <thead>
                <tr>
                  <th>Désignation</th><th class="nombre">Demandé</th><th>Justification</th>
                  <th class="nombre">Retenu</th><th class="nombre">Prix unitaire</th><th class="nombre">Montant</th>
                  @if (b.droits.proposer || b.droits.modifier) { <th><span class="visuellement-cache">Actions</span></th> }
                </tr>
              </thead>
              <tbody>
                @for (l of b.lignes; track l.articleId) {
                  <tr>
                    <td>
                      {{ l.designation }} <span class="doux">({{ l.unite }})</span>
                      <br /><span class="doux petit-texte">{{ libelleNature[l.nature] }}@if (l.normes) { · {{ l.normes }} }</span>
                    </td>
                    <td class="nombre">{{ quantite(l.quantiteDemandee) }}</td>
                    <td>{{ l.justification ?? '' }}@if (l.proposePar) { <br /><span class="doux petit-texte">{{ l.proposePar }}</span> }</td>
                    <td class="nombre">
                      @if (b.droits.arbitrer) {
                        <input class="court" inputmode="decimal" [attr.aria-label]="'Quantité retenue ' + l.designation"
                          [placeholder]="quantite(l.quantiteDemandee)" [value]="retenues()[l.articleId] ?? ''" (input)="retenir(l.articleId, $event)" />
                      } @else {
                        {{ l.quantiteRetenue === null ? '' : quantite(l.quantiteRetenue) }}
                      }
                    </td>
                    <td class="nombre">{{ l.prixUnitaire === null ? 'à fixer' : fcfa(l.prixUnitaire) }}</td>
                    <td class="nombre">{{ fcfa(l.montant) }}</td>
                    @if (b.droits.proposer || b.droits.modifier) {
                      <td><button type="button" class="bouton discret petit" [disabled]="enregistrement.enCours()" (click)="retirer(l.articleId)">Retirer</button></td>
                    }
                  </tr>
                } @empty {
                  <tr><td colspan="7" class="doux">Aucun article demandé.@if (b.droits.proposer) { Ajoutez les articles du catalogue nécessaires aux TP. }</td></tr>
                }
              </tbody>
            </table>
          </div>
          @if (b.lignes.length) {
            <p class="total">Total : <strong>{{ fcfa(b.montant) }}</strong></p>
          }
        </section>

        @if (b.droits.arbitrer) {
          <section class="carte">
            <h2>Arbitrage</h2>
            <p class="doux">
              Saisissez la quantité retenue (vide : la quantité demandée). La validation fige les prix du catalogue ;
              le renvoi rend la fiche à l'atelier avec votre commentaire.
            </p>
            @if (arbitrageInvalide(); as e) { <p class="rouge" role="alert">{{ e }}</p> }
            <div class="actions-ligne">
              <button type="button" class="bouton secondaire petit" [disabled]="!!arbitrageInvalide() || enregistrement.enCours()" (click)="arbitrer()">Enregistrer les quantités</button>
              <button type="button" class="bouton petit" [disabled]="!!arbitrageInvalide() || enregistrement.enCours()" (click)="valider()">Valider les besoins</button>
            </div>
            <form class="sous-formulaire" (ngSubmit)="renvoyer()">
              <div class="champ">
                <label for="bl-renvoi">Renvoyer à l'atelier avec un commentaire</label>
                <input id="bl-renvoi" name="renvoi" placeholder="Précisez les quantités du TP 3…" [(ngModel)]="commentaire" />
              </div>
              <button type="submit" class="bouton discret petit" [disabled]="!commentaire().trim() || enregistrement.enCours()">Renvoyer</button>
            </form>
          </section>
        }
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .infos {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem 0.75rem;
      align-items: center;
      margin: 0;
    }
    h2 {
      font-size: 1.05rem;
      margin: 0;
    }
    table {
      width: 100%;
      border-collapse: collapse;
    }
    th,
    td {
      text-align: left;
      padding: 0.35rem 0.5rem;
      border-bottom: 1px solid var(--bordure);
      vertical-align: top;
    }
    .nombre {
      text-align: right;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }
    .court {
      width: 6rem;
      text-align: right;
    }
    .petit-texte {
      font-size: 0.8rem;
    }
    .total {
      text-align: right;
      margin: 0.75rem 0 0;
    }
    .rouge {
      color: var(--absent);
    }
    .sous-formulaire {
      margin: 0.75rem 0;
    }
  `,
})
export class BesoinPage {
  private readonly api = inject(AteliersApi);
  private readonly session = inject(SessionService);

  readonly besoinId = input.required<string>();

  protected readonly dateCourte = dateCourte;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly fcfa = fcfa;
  protected readonly quantite = quantite;
  protected readonly libelleNature = LIBELLE_NATURE;
  protected readonly libelleStatut = LIBELLE_STATUT_BESOIN;
  protected readonly classeStatut = CLASSE_STATUT_BESOIN;
  protected readonly libelleType = LIBELLE_TYPE_CAMPAGNE;

  protected readonly action = new Action();
  protected readonly enregistrement = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly besoin = signal<BesoinVue | null>(null);
  /** Lien vers la campagne : direction et intendance seulement (le serveur refuse aux enseignants). */
  protected readonly suiviCampagne = computed(() => this.session.aLeRole(...ROLES_BESOINS));

  protected readonly ajout = signal(false);
  protected readonly catalogue = signal<ArticleVue[]>([]);
  protected readonly articles = computed(() => this.catalogue().filter((a) => a.actif));
  protected readonly article = signal('');
  protected readonly quantiteTexte = signal('');
  protected readonly justification = signal('');
  protected readonly articleChoisi = computed(() => this.catalogue().find((a) => a.id === this.article()));
  protected readonly erreurSaisie = computed(() => erreurLigneBesoin(this.articleChoisi(), this.quantiteTexte()));

  protected readonly retenues = signal<Record<string, string>>({});
  protected readonly commentaire = signal('');
  protected readonly arbitrageInvalide = computed(() => {
    const b = this.besoin();
    if (!b) {
      return null;
    }
    for (const l of b.lignes) {
      const t = (this.retenues()[l.articleId] ?? '').trim();
      if (!t) {
        continue;
      }
      const q = lireQuantite(t, true);
      if (q === null) {
        return `${l.designation} : quantité retenue positive ou nulle, deux décimales au plus.`;
      }
      if (l.nature === 'EQUIPEMENT' && !Number.isInteger(q)) {
        return `${l.designation} : un équipement se compte à l’unité.`;
      }
    }
    return null;
  });

  constructor() {
    effect(() => {
      const id = this.besoinId();
      untracked(() => void this.charger(id));
    });
  }

  protected async ouvrirAjout(): Promise<void> {
    this.article.set('');
    this.quantiteTexte.set('');
    this.justification.set('');
    this.ajout.set(true);
    if (!this.catalogue().length) {
      this.catalogue.set(await this.api.catalogue().catch(() => []));
    }
  }

  protected async ajouter(): Promise<void> {
    if (this.erreurSaisie()) {
      return;
    }
    const b = await this.enregistrement.executer(() =>
      this.api.proposerLigne(this.besoinId(), this.article(), lireQuantite(this.quantiteTexte())!, this.justification().trim() || null),
    );
    if (b) {
      this.afficher(b, null);
      this.article.set('');
      this.quantiteTexte.set('');
      this.justification.set('');
    }
  }

  protected async retirer(articleId: string): Promise<void> {
    const b = await this.enregistrement.executer(() => this.api.retirerLigne(this.besoinId(), articleId));
    if (b) {
      this.afficher(b, null);
    }
  }

  protected async transmettre(): Promise<void> {
    const b = await this.enregistrement.executer(() => this.api.transmettreBesoin(this.besoinId()));
    if (b) {
      this.ajout.set(false);
      this.afficher(b, 'Besoins transmis au chef des travaux.');
    }
  }

  protected retenir(articleId: string, e: Event): void {
    const v = (e.target as HTMLInputElement).value;
    this.retenues.update((r) => ({ ...r, [articleId]: v }));
  }

  protected async arbitrer(): Promise<void> {
    const b = await this.enregistrerArbitrage();
    if (b) {
      this.afficher(b, 'Quantités retenues enregistrées.');
    }
  }

  protected async valider(): Promise<void> {
    if (!(await this.enregistrerArbitrage())) {
      return;
    }
    const b = await this.enregistrement.executer(() => this.api.validerBesoin(this.besoinId()));
    if (b) {
      this.afficher(b, 'Besoins validés : ils figureront dans l’état transmis à la direction régionale.');
    }
  }

  protected async renvoyer(): Promise<void> {
    const b = await this.enregistrement.executer(() => this.api.renvoyerBesoin(this.besoinId(), this.commentaire().trim()));
    if (b) {
      this.commentaire.set('');
      this.afficher(b, 'Fiche renvoyée à l’atelier.');
    }
  }

  private enregistrerArbitrage(): Promise<BesoinVue | undefined> {
    const b = this.besoin()!;
    const lignes = b.lignes.map((l) => {
      const t = (this.retenues()[l.articleId] ?? '').trim();
      return { articleId: l.articleId, quantiteRetenue: t ? lireQuantite(t, true) : null };
    });
    return this.enregistrement.executer(() => this.api.arbitrer(this.besoinId(), lignes));
  }

  private afficher(b: BesoinVue, message: string | null): void {
    this.besoin.set(b);
    this.message.set(message);
    const r: Record<string, string> = {};
    for (const l of b.lignes) {
      if (l.quantiteRetenue !== null) {
        r[l.articleId] = quantite(l.quantiteRetenue).replace(/\s/g, '');
      }
    }
    this.retenues.set(r);
  }

  private async charger(id: string): Promise<void> {
    const b = await this.action.executer(() => this.api.besoin(id));
    if (b && id === this.besoinId()) {
      this.afficher(b, null);
    }
  }
}
