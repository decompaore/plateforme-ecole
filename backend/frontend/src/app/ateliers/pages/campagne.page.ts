import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte, dateHeureCourte, dateLocale } from '../../core/outils';
import { fcfa, lireMontant } from '../../scolarite/modeles-scolarite';
import { AteliersNavComponent } from '../ateliers-nav.component';
import { AteliersApi } from '../ateliers-api.service';
import { ExportBoutonsComponent } from '../export-boutons.component';
import { LIBELLE_NATURE, lireQuantite, NatureArticle, quantite } from '../modeles-ateliers';
import {
  BesoinResumeVue,
  CampagneVue,
  CLASSE_STATUT_BESOIN,
  LIBELLE_PASSEE_PAR,
  LIBELLE_STATUT_BESOIN,
  LIBELLE_STATUT_CAMPAGNE,
  LIBELLE_STATUT_COMMANDE,
  LIBELLE_TYPE_CAMPAGNE,
  PasseePar,
} from '../modeles-besoins';

/** Ligne du formulaire de commande, préremplie avec ce qui reste à commander. */
interface LigneSaisie {
  articleId: string;
  designation: string;
  nature: NatureArticle;
  unite: string;
  inclure: boolean;
  quantite: string;
  prix: string;
}

/** Contrôle d'une commande avant l'envoi. */
export function erreurCommande(reference: string, fournisseur: string, lignes: LigneSaisie[]): string | null {
  if (!reference.trim()) {
    return 'Indiquez la référence de la commande (numéro du bon de commande).';
  }
  if (!fournisseur.trim()) {
    return 'Indiquez le fournisseur.';
  }
  const retenues = lignes.filter((l) => l.inclure);
  if (!retenues.length) {
    return 'Cochez au moins un article.';
  }
  for (const l of retenues) {
    const q = lireQuantite(l.quantite);
    if (q === null) {
      return `${l.designation} : quantité positive, deux décimales au plus.`;
    }
    if (l.nature === 'EQUIPEMENT' && !Number.isInteger(q)) {
      return `${l.designation} : un équipement se compte à l'unité.`;
    }
    if (l.prix.trim() && lireMontant(l.prix) === null) {
      return `${l.designation} : prix unitaire en francs CFA, sans décimales.`;
    }
  }
  return null;
}

/**
 * Fiche d'une campagne : besoins de chaque atelier (arbitrage et validation sur la fiche de
 * l'atelier), état consolidé par filière pour la direction régionale (exportable en Excel et en
 * PDF avec photos et prix du catalogue), transmission, puis commandes et leur suivi.
 */
@Component({
  selector: 'app-campagne',
  imports: [FormsModule, RouterLink, AteliersNavComponent, ExportBoutonsComponent],
  template: `
    <div class="page large">
      <h1>{{ campagne()?.libelle ?? 'Campagne de besoins' }}</h1>
      <app-ateliers-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (campagne(); as c) {
        <section class="carte">
          <div class="entete-section">
            <p class="infos">
              <span>{{ libelleType[c.type] }}</span>
              <span class="pastille {{ c.statut === 'OUVERTE' ? 'absent' : c.statut === 'TRANSMISE' ? 'present' : '' }}">{{ libelleStatut[c.statut] }}</span>
            </p>
            <span class="actions-ligne">
              @if (c.gerer && c.statut === 'OUVERTE') {
                <button type="button" class="bouton petit" [disabled]="enregistrement.enCours()" (click)="transmettre()">Transmettre à la direction régionale</button>
              }
              @if (c.gerer && c.statut === 'TRANSMISE') {
                <button type="button" class="bouton secondaire petit" [disabled]="enregistrement.enCours()" (click)="clore()">Clore la campagne</button>
              }
            </span>
          </div>
          <p class="doux">
            Ouverte le {{ dateHeureCourte(c.ouverteLe) }}
            @if (c.dateLimite) { · remise des besoins avant le {{ dateCourte(c.dateLimite) }} }
            @if (c.transmiseLe) { · transmise le {{ dateHeureCourte(c.transmiseLe) }} }
            @if (c.closeLe) { · close le {{ dateHeureCourte(c.closeLe) }} }
          </p>
          @if (c.observations) { <p>{{ c.observations }}</p> }
          @if (c.statut === 'OUVERTE' && c.gerer) {
            <p class="doux">
              Ouvrez la fiche de chaque atelier pour arbitrer les quantités puis valider. La transmission exige que
              tous les besoins exprimés soient validés.
            </p>
          }
        </section>

        @if (message()) {
          <div class="alerte succes" role="status">{{ message() }}</div>
        }
        @if (enregistrement.erreur()) {
          <div class="alerte erreur" role="alert">{{ enregistrement.erreur() }}</div>
        }

        <section class="carte">
          <h2>Besoins des ateliers</h2>
          <div class="tableau-defilant">
            <table>
              <thead>
                <tr><th>Atelier</th><th>Filières</th><th>Responsable</th><th class="nombre">Articles</th><th class="nombre">Montant</th><th>État</th></tr>
              </thead>
              <tbody>
                @for (b of c.besoins; track b.id) {
                  <tr>
                    <td><a [routerLink]="['/ateliers/besoins-ateliers', b.id]">{{ b.atelierCode }} · {{ b.atelierNom }}</a></td>
                    <td>{{ filieres(b) }}</td>
                    <td>{{ b.responsable ?? '—' }}</td>
                    <td class="nombre">{{ b.lignes }}</td>
                    <td class="nombre">{{ b.montant ? fcfa(b.montant) : '' }}</td>
                    <td><span class="pastille {{ classeStatutBesoin[b.statut] }}">{{ libelleStatutBesoin[b.statut] }}</span></td>
                  </tr>
                } @empty {
                  <tr><td colspan="6" class="doux">Aucun atelier ouvert.</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>

        <section class="carte">
          <div class="entete-section">
            <h2>État des besoins validés par filière</h2>
            <app-export [chemin]="'/campagnes-besoins/' + c.id" nom="etat-des-besoins" libelle="l'état des besoins pour la direction régionale" />
          </div>
          <p class="doux">
            Désignation, spécifications et normes, photo et prix du catalogue, quantités retenues ; le fichier
            comporte aussi un récapitulatif, le détail par atelier et les signatures (chef des travaux, intendant, proviseur).
          </p>
          @for (f of c.filieres; track f.filiere) {
            <h3>{{ f.filiere }} <span class="doux">· {{ fcfa(f.montant) }}</span></h3>
            <div class="tableau-defilant">
              <table>
                <thead>
                  <tr><th>Désignation</th><th>Nature</th><th class="nombre">Quantité</th><th class="nombre">Prix unitaire</th><th class="nombre">Montant</th>@if (c.statut !== 'OUVERTE') {<th class="nombre">Commandé</th><th class="nombre">Livré</th>}</tr>
                </thead>
                <tbody>
                  @for (l of f.lignes; track l.articleId) {
                    <tr>
                      <td>{{ l.designation }} <span class="doux">({{ l.unite }})</span></td>
                      <td>{{ libelleNature[l.nature] }}</td>
                      <td class="nombre">{{ quantite(l.quantite) }}</td>
                      <td class="nombre">{{ l.prixUnitaire === null ? 'à fixer' : fcfa(l.prixUnitaire) }}</td>
                      <td class="nombre">{{ fcfa(l.montant) }}</td>
                      @if (c.statut !== 'OUVERTE') {
                        <td class="nombre">{{ quantite(l.commandee) }}</td>
                        <td class="nombre">{{ quantite(l.livree) }}</td>
                      }
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @empty {
            <p class="doux">Aucun besoin validé pour le moment.</p>
          }
          @if (c.filieres.length) {
            <p class="total">Total : <strong>{{ fcfa(c.montant) }}</strong></p>
          }
        </section>

        @if (c.statut !== 'OUVERTE') {
          <section class="carte">
            <div class="entete-section">
              <h2>Commandes</h2>
              @if (c.commander && !commandeOuverte()) {
                <button type="button" class="bouton petit" (click)="ouvrirCommande(c)">Enregistrer une commande</button>
              }
            </div>
            <ul class="liste">
              @for (co of c.commandes; track co.id) {
                <li>
                  <a class="ligne-lien" [routerLink]="['/ateliers/commandes', co.id]">
                    <span>
                      <strong>{{ co.reference }}</strong> · {{ co.fournisseur }}
                      <br />
                      <span class="doux">{{ libellePasseePar[co.passeePar] }} · {{ dateCourte(co.dateCommande) }} · {{ co.lignes }} article(s) · {{ fcfa(co.montant) }}</span>
                    </span>
                    <span class="pastille {{ co.statut === 'LIVREE' ? 'present' : co.statut === 'ANNULEE' ? '' : 'absent' }}">{{ libelleStatutCommande[co.statut] }}</span>
                  </a>
                </li>
              } @empty {
                <li class="doux">Aucune commande enregistrée. Selon le budget, la direction régionale ou l'intendant passe commande auprès des fournisseurs.</li>
              }
            </ul>

            @if (commandeOuverte()) {
              <form class="sous-formulaire" (ngSubmit)="commander()">
                <h3>Nouvelle commande</h3>
                <div class="grille-champs">
                  <div class="champ">
                    <label for="co-ref">Référence</label>
                    <input id="co-ref" name="ref" placeholder="BC-2026-014" [(ngModel)]="reference" />
                  </div>
                  <div class="champ">
                    <label for="co-four">Fournisseur</label>
                    <input id="co-four" name="four" [(ngModel)]="fournisseur" />
                  </div>
                  <div class="champ">
                    <label for="co-par">Passée par</label>
                    <select id="co-par" name="par" [ngModel]="passeePar()" (ngModelChange)="passeePar.set($event)">
                      <option value="DIRECTION_REGIONALE">{{ libellePasseePar.DIRECTION_REGIONALE }}</option>
                      <option value="ETABLISSEMENT">{{ libellePasseePar.ETABLISSEMENT }}</option>
                    </select>
                  </div>
                  <div class="champ">
                    <label for="co-date">Date de la commande</label>
                    <input id="co-date" name="date" type="date" [max]="aujourdhui" [(ngModel)]="dateCommande" />
                  </div>
                </div>
                <div class="tableau-defilant">
                  <table>
                    <thead><tr><th>Commander</th><th>Article</th><th class="nombre">Quantité</th><th class="nombre">Prix unitaire (FCFA)</th></tr></thead>
                    <tbody>
                      @for (l of lignes(); track l.articleId; let i = $index) {
                        <tr>
                          <td><input type="checkbox" [attr.aria-label]="'Commander ' + l.designation" [checked]="l.inclure" (change)="modifier(i, { inclure: !l.inclure })" /></td>
                          <td>{{ l.designation }} <span class="doux">({{ l.unite }})</span></td>
                          <td class="nombre"><input class="court" inputmode="decimal" [attr.aria-label]="'Quantité ' + l.designation" [value]="l.quantite" (input)="modifier(i, { quantite: valeur($event) })" [disabled]="!l.inclure" /></td>
                          <td class="nombre"><input class="court" inputmode="numeric" [attr.aria-label]="'Prix ' + l.designation" [value]="l.prix" (input)="modifier(i, { prix: valeur($event) })" [disabled]="!l.inclure" /></td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
                <div class="champ">
                  <label for="co-obs">Observations <span class="doux">(facultatif)</span></label>
                  <input id="co-obs" name="obs" [(ngModel)]="observationsCommande" />
                </div>
                @if (erreurSaisie(); as e) { <p class="rouge" role="alert">{{ e }}</p> }
                <div class="actions-ligne">
                  <button type="submit" class="bouton petit" [disabled]="!!erreurSaisie() || enregistrement.enCours()">Enregistrer la commande</button>
                  <button type="button" class="bouton discret petit" (click)="commandeOuverte.set(false)">Fermer</button>
                </div>
              </form>
            }
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
      gap: 0.5rem;
      align-items: center;
      margin: 0;
    }
    h2 {
      font-size: 1.05rem;
      margin: 0;
    }
    h3 {
      font-size: 0.95rem;
      margin: 1rem 0 0.4rem;
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
      vertical-align: middle;
    }
    .nombre {
      text-align: right;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }
    .court {
      width: 7rem;
      text-align: right;
    }
    .total {
      text-align: right;
      margin: 0.75rem 0 0;
    }
    .rouge {
      color: var(--absent);
    }
    .sous-formulaire {
      margin-top: 1rem;
      padding-top: 0.75rem;
      border-top: 1px solid var(--bordure);
    }
  `,
})
export class CampagnePage {
  private readonly api = inject(AteliersApi);
  private readonly router = inject(Router);

  readonly campagneId = input.required<string>();

  protected readonly dateCourte = dateCourte;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly fcfa = fcfa;
  protected readonly quantite = quantite;
  protected readonly libelleType = LIBELLE_TYPE_CAMPAGNE;
  protected readonly libelleStatut = LIBELLE_STATUT_CAMPAGNE;
  protected readonly libelleStatutBesoin = LIBELLE_STATUT_BESOIN;
  protected readonly classeStatutBesoin = CLASSE_STATUT_BESOIN;
  protected readonly libelleStatutCommande = LIBELLE_STATUT_COMMANDE;
  protected readonly libellePasseePar = LIBELLE_PASSEE_PAR;
  protected readonly libelleNature = LIBELLE_NATURE;
  protected readonly aujourdhui = dateLocale();

  protected readonly action = new Action();
  protected readonly enregistrement = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly campagne = signal<CampagneVue | null>(null);

  protected readonly commandeOuverte = signal(false);
  protected readonly reference = signal('');
  protected readonly fournisseur = signal('');
  protected readonly passeePar = signal<PasseePar>('DIRECTION_REGIONALE');
  protected readonly dateCommande = signal(dateLocale());
  protected readonly observationsCommande = signal('');
  protected readonly lignes = signal<LigneSaisie[]>([]);
  protected readonly erreurSaisie = computed(() => erreurCommande(this.reference(), this.fournisseur(), this.lignes()));

  constructor() {
    effect(() => {
      const id = this.campagneId();
      untracked(() => void this.charger(id));
    });
  }

  protected filieres(b: BesoinResumeVue): string {
    return b.filieres.map((f) => f.code).join(', ');
  }

  protected valeur(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected modifier(i: number, changement: Partial<LigneSaisie>): void {
    this.lignes.update((l) => l.map((x, j) => (j === i ? { ...x, ...changement } : x)));
  }

  protected ouvrirCommande(c: CampagneVue): void {
    this.reference.set('');
    this.fournisseur.set('');
    this.passeePar.set('DIRECTION_REGIONALE');
    this.dateCommande.set(dateLocale());
    this.observationsCommande.set('');
    this.lignes.set(
      c.totaux.map((t) => {
        const reste = Math.max(0, Math.round((t.quantite - t.commandee) * 100) / 100);
        return {
          articleId: t.articleId,
          designation: t.designation,
          nature: t.nature,
          unite: t.unite,
          inclure: reste > 0,
          quantite: reste > 0 ? String(reste).replace('.', ',') : '',
          prix: t.prixUnitaire === null ? '' : String(t.prixUnitaire),
        };
      }),
    );
    this.commandeOuverte.set(true);
  }

  protected async commander(): Promise<void> {
    if (this.erreurSaisie()) {
      return;
    }
    const co = await this.enregistrement.executer(() =>
      this.api.creerCommande(this.campagneId(), {
        reference: this.reference().trim(),
        fournisseur: this.fournisseur().trim(),
        passeePar: this.passeePar(),
        dateCommande: this.dateCommande() || null,
        observations: this.observationsCommande().trim() || null,
        lignes: this.lignes()
          .filter((l) => l.inclure)
          .map((l) => ({ articleId: l.articleId, quantite: lireQuantite(l.quantite)!, prixUnitaire: l.prix.trim() ? lireMontant(l.prix) : null })),
      }),
    );
    if (co) {
      void this.router.navigate(['/ateliers/commandes', co.id]);
    }
  }

  protected async transmettre(): Promise<void> {
    const c = await this.enregistrement.executer(() => this.api.transmettreCampagne(this.campagneId()));
    if (c) {
      this.campagne.set(c);
      this.message.set('Besoins transmis : téléchargez l’état pour la direction régionale. Les commandes peuvent maintenant être enregistrées.');
    }
  }

  protected async clore(): Promise<void> {
    const c = await this.enregistrement.executer(() => this.api.cloreCampagne(this.campagneId()));
    if (c) {
      this.campagne.set(c);
      this.message.set('Campagne close.');
    }
  }

  private async charger(id: string): Promise<void> {
    const c = await this.action.executer(() => this.api.campagne(id));
    if (c && id === this.campagneId()) {
      this.campagne.set(c);
    }
  }
}
