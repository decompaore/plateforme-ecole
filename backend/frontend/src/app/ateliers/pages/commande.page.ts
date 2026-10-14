import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte, dateLocale } from '../../core/outils';
import { fcfa } from '../../scolarite/modeles-scolarite';
import { AteliersNavComponent } from '../ateliers-nav.component';
import { AteliersApi } from '../ateliers-api.service';
import { ExportBoutonsComponent } from '../export-boutons.component';
import { lireQuantite, quantite } from '../modeles-ateliers';
import {
  CommandeVue,
  erreurReception,
  LIBELLE_PASSEE_PAR,
  LIBELLE_STATUT_COMMANDE,
  LIBELLE_STATUT_LIVRAISON,
  LigneCommandeVue,
} from '../modeles-besoins';

interface SaisieReception {
  recue: string;
  conforme: string;
  motif: string;
}

/**
 * Fiche d'une commande (bon de commande exportable) et réception des livraisons par le chef des
 * travaux : quantité reçue et quantité conforme aux spécifications et normes du catalogue, avec
 * le motif de non-conformité. Chaque livraison est ensuite répartie entre les ateliers.
 */
@Component({
  selector: 'app-commande',
  imports: [FormsModule, RouterLink, AteliersNavComponent, ExportBoutonsComponent],
  template: `
    <div class="page large">
      <h1>{{ commande() ? 'Commande ' + commande()!.reference : 'Commande' }}</h1>
      <app-ateliers-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (commande(); as co) {
        <section class="carte">
          <div class="entete-section">
            <p class="infos">
              <strong>{{ co.fournisseur }}</strong>
              <span class="pastille {{ co.statut === 'LIVREE' ? 'present' : co.statut === 'ANNULEE' ? '' : 'absent' }}">{{ libelleStatut[co.statut] }}</span>
            </p>
            <app-export [chemin]="'/commandes/' + co.id" [nom]="'commande-' + co.reference" libelle="le bon de commande" />
          </div>
          <p class="doux">
            {{ libellePasseePar[co.passeePar] }} · commande du {{ dateCourte(co.dateCommande) }} ·
            <a [routerLink]="['/ateliers/besoins', co.campagneId]">{{ co.campagne }}</a>
          </p>
          @if (co.observations) { <p>{{ co.observations }}</p> }
          @if (co.motifAnnulation) { <p class="alerte attention">Annulée : {{ co.motifAnnulation }}</p> }
        </section>

        @if (message()) {
          <div class="alerte succes" role="status">{{ message() }}</div>
        }
        @if (enregistrement.erreur()) {
          <div class="alerte erreur" role="alert">{{ enregistrement.erreur() }}</div>
        }

        <section class="carte">
          <div class="entete-section">
            <h2>Articles commandés</h2>
            @if (co.recevoir && !reception()) {
              <button type="button" class="bouton petit" (click)="ouvrirReception(co)">Réceptionner une livraison</button>
            }
          </div>
          @if (reception()) {
            <form (ngSubmit)="recevoir()">
              <div class="grille-champs">
                <div class="champ">
                  <label for="lv-date">Date de réception</label>
                  <input id="lv-date" name="date" type="date" [min]="co.dateCommande" [max]="aujourdhui" [(ngModel)]="dateReception" />
                </div>
                <div class="champ">
                  <label for="lv-bl">N° du bon de livraison <span class="doux">(facultatif)</span></label>
                  <input id="lv-bl" name="bl" [(ngModel)]="bonLivraison" />
                </div>
              </div>
              <p class="doux">
                Vérifiez chaque article par rapport aux spécifications et aux normes du catalogue. La quantité
                conforme est vide si tout est conforme ; sinon indiquez le motif du refus.
              </p>
            </form>
          }
          <div class="tableau-defilant">
            <table>
              <thead>
                <tr>
                  <th>Désignation</th><th class="nombre">Commandé</th><th class="nombre">Prix unitaire</th><th class="nombre">Montant</th>
                  <th class="nombre">Reçu</th><th class="nombre">Conforme</th><th class="nombre">Reste</th>
                  @if (reception()) { <th class="saisie">Reçu ce jour</th><th class="saisie">Dont conforme</th><th>Motif de non-conformité</th> }
                </tr>
              </thead>
              <tbody>
                @for (l of co.lignes; track l.articleId) {
                  <tr>
                    <td>{{ l.designation }} <span class="doux">({{ l.unite }})</span></td>
                    <td class="nombre">{{ quantite(l.quantite) }}</td>
                    <td class="nombre">{{ fcfa(l.prixUnitaire) }}</td>
                    <td class="nombre">{{ fcfa(l.montant) }}</td>
                    <td class="nombre">{{ quantite(l.recue) }}</td>
                    <td class="nombre">{{ quantite(l.conforme) }}</td>
                    <td class="nombre" [class.rouge]="l.reste > 0">{{ quantite(l.reste) }}</td>
                    @if (reception()) {
                      @if (l.reste > 0) {
                        <td><input class="court" inputmode="decimal" [attr.aria-label]="'Reçu ' + l.designation" [value]="saisies()[l.articleId]?.recue ?? ''" (input)="saisir(l, 'recue', $event)" /></td>
                        <td><input class="court" inputmode="decimal" [attr.aria-label]="'Conforme ' + l.designation" [placeholder]="saisies()[l.articleId]?.recue ?? ''" [value]="saisies()[l.articleId]?.conforme ?? ''" (input)="saisir(l, 'conforme', $event)" /></td>
                        <td><input [attr.aria-label]="'Motif ' + l.designation" placeholder="Hors norme, abîmé…" [value]="saisies()[l.articleId]?.motif ?? ''" (input)="saisir(l, 'motif', $event)" /></td>
                      } @else {
                        <td colspan="3" class="doux">entièrement livré</td>
                      }
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <p class="total">Total : <strong>{{ fcfa(co.montant) }}</strong></p>
          @if (reception()) {
            @if (erreurSaisie(); as e) { <p class="rouge" role="alert">{{ e }}</p> }
            <div class="actions-ligne">
              <button type="button" class="bouton petit" [disabled]="!!erreurSaisie() || enregistrement.enCours()" (click)="recevoir()">Enregistrer la réception</button>
              <button type="button" class="bouton discret petit" (click)="reception.set(false)">Fermer</button>
            </div>
          }
        </section>

        <section class="carte">
          <h2>Livraisons</h2>
          <ul class="liste">
            @for (l of co.livraisons; track l.id) {
              <li>
                <a class="ligne-lien" [routerLink]="['/ateliers/livraisons', l.id]">
                  <span>
                    <strong>Reçue le {{ dateCourte(l.dateReception) }}</strong>@if (l.bonLivraison) { · BL {{ l.bonLivraison }} }
                    <br />
                    <span class="doux">{{ l.lignes }} article(s)@if (l.nonConformes) { · {{ l.nonConformes }} avec non-conformité }</span>
                  </span>
                  <span class="pastille {{ l.statut === 'REPARTIE' ? 'present' : 'absent' }}">{{ libelleStatutLivraison[l.statut] }}</span>
                </a>
              </li>
            } @empty {
              <li class="doux">Aucune livraison reçue.</li>
            }
          </ul>
        </section>

        @if (co.gerer) {
          <section class="carte">
            <h2>Annulation</h2>
            <form class="actions-ligne" (ngSubmit)="annuler()">
              <label class="visuellement-cache" for="co-motif">Motif de l'annulation</label>
              <input id="co-motif" name="motif" placeholder="Motif (budget non disponible…)" [(ngModel)]="motif" />
              <button type="submit" class="bouton danger petit" [disabled]="!motif().trim() || enregistrement.enCours()">Annuler la commande</button>
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
      gap: 0.5rem;
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
      vertical-align: middle;
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
    .total {
      text-align: right;
      margin: 0.75rem 0;
    }
    .rouge {
      color: var(--absent);
    }
  `,
})
export class CommandePage {
  private readonly api = inject(AteliersApi);
  private readonly router = inject(Router);

  readonly commandeId = input.required<string>();

  protected readonly dateCourte = dateCourte;
  protected readonly fcfa = fcfa;
  protected readonly quantite = quantite;
  protected readonly libelleStatut = LIBELLE_STATUT_COMMANDE;
  protected readonly libelleStatutLivraison = LIBELLE_STATUT_LIVRAISON;
  protected readonly libellePasseePar = LIBELLE_PASSEE_PAR;
  protected readonly aujourdhui = dateLocale();

  protected readonly action = new Action();
  protected readonly enregistrement = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly commande = signal<CommandeVue | null>(null);

  protected readonly reception = signal(false);
  protected readonly dateReception = signal(dateLocale());
  protected readonly bonLivraison = signal('');
  protected readonly saisies = signal<Record<string, SaisieReception>>({});
  protected readonly motif = signal('');

  protected readonly erreurSaisie = computed(() => {
    const co = this.commande();
    if (!co) {
      return null;
    }
    let une = false;
    for (const l of co.lignes) {
      const s = this.saisies()[l.articleId];
      if (!s || !s.recue.trim()) {
        continue;
      }
      const recue = lireQuantite(s.recue);
      const conforme = s.conforme.trim() ? lireQuantite(s.conforme, true) : null;
      if (recue === null || (s.conforme.trim() && conforme === null)) {
        return `${l.designation} : quantités positives, deux décimales au plus.`;
      }
      if (l.nature === 'EQUIPEMENT' && (!Number.isInteger(recue) || (conforme !== null && !Number.isInteger(conforme)))) {
        return `${l.designation} : un équipement se compte à l’unité.`;
      }
      const e = erreurReception({ designation: l.designation, recue, conforme, motif: s.motif, reste: l.reste });
      if (e) {
        return e;
      }
      une = true;
    }
    if (this.dateReception() > this.aujourdhui) {
      return 'La date de réception ne peut pas être dans le futur.';
    }
    return une ? null : 'Saisissez la quantité reçue d’au moins un article.';
  });

  constructor() {
    effect(() => {
      const id = this.commandeId();
      untracked(() => void this.charger(id));
    });
  }

  protected ouvrirReception(co: CommandeVue): void {
    const s: Record<string, SaisieReception> = {};
    for (const l of co.lignes) {
      s[l.articleId] = { recue: l.reste > 0 ? quantite(l.reste).replace(/\s/g, '') : '', conforme: '', motif: '' };
    }
    this.saisies.set(s);
    this.dateReception.set(dateLocale());
    this.bonLivraison.set('');
    this.reception.set(true);
  }

  protected saisir(l: LigneCommandeVue, champ: keyof SaisieReception, e: Event): void {
    const v = (e.target as HTMLInputElement).value;
    this.saisies.update((s) => ({ ...s, [l.articleId]: { ...(s[l.articleId] ?? { recue: '', conforme: '', motif: '' }), [champ]: v } }));
  }

  protected async recevoir(): Promise<void> {
    if (this.erreurSaisie()) {
      return;
    }
    const lignes = this.commande()!
      .lignes.map((l) => ({ l, s: this.saisies()[l.articleId] }))
      .filter((x) => x.s && x.s.recue.trim())
      .map(({ l, s }) => ({
        articleId: l.articleId,
        quantiteRecue: lireQuantite(s.recue)!,
        quantiteConforme: s.conforme.trim() ? lireQuantite(s.conforme, true) : null,
        motifNonConformite: s.motif.trim() || null,
      }));
    const liv = await this.enregistrement.executer(() =>
      this.api.recevoir(this.commandeId(), {
        dateReception: this.dateReception() || null,
        bonLivraison: this.bonLivraison().trim() || null,
        observations: null,
        lignes,
      }),
    );
    if (liv) {
      void this.router.navigate(['/ateliers/livraisons', liv.id]);
    }
  }

  protected async annuler(): Promise<void> {
    const co = await this.enregistrement.executer(() => this.api.annulerCommande(this.commandeId(), this.motif().trim()));
    if (co) {
      this.commande.set(co);
      this.motif.set('');
      this.message.set('Commande annulée.');
    }
  }

  private async charger(id: string): Promise<void> {
    const co = await this.action.executer(() => this.api.commande(id));
    if (co && id === this.commandeId()) {
      this.commande.set(co);
    }
  }
}
