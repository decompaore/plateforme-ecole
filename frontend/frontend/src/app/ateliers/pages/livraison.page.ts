import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte, dateHeureCourte } from '../../core/outils';
import { AteliersNavComponent } from '../ateliers-nav.component';
import { AteliersApi } from '../ateliers-api.service';
import { ExportBoutonsComponent } from '../export-boutons.component';
import { lireQuantite, quantite } from '../modeles-ateliers';
import { LIBELLE_STATUT_LIVRAISON, LigneLivraisonVue, LivraisonVue, SaisieRepartition } from '../modeles-besoins';

/** Clé d'une case de la grille de répartition. */
function cle(articleId: string, atelierId: string): string {
  return `${articleId}|${atelierId}`;
}

/** Somme répartie d'un article et contrôle par rapport à la quantité conforme. */
export function bilanRepartition(l: LigneLivraisonVue, saisies: Record<string, string>, ateliers: string[]): { total: number; erreur: string | null } {
  let total = 0;
  for (const a of ateliers) {
    const t = (saisies[cle(l.articleId, a)] ?? '').trim();
    if (!t) {
      continue;
    }
    const q = lireQuantite(t, true);
    if (q === null) {
      return { total, erreur: `${l.designation} : quantités positives, deux décimales au plus.` };
    }
    if (l.nature === 'EQUIPEMENT' && !Number.isInteger(q)) {
      return { total, erreur: `${l.designation} : un équipement se compte à l’unité.` };
    }
    total = Math.round((total + q) * 100) / 100;
  }
  if (total > l.conforme) {
    return { total, erreur: `${l.designation} : ${quantite(total)} répartis pour ${quantite(l.conforme)} conformes.` };
  }
  return { total, erreur: null };
}

/**
 * Livraison reçue : procès-verbal de réception (conformité) et répartition de la quantité conforme
 * entre les ateliers. La part proposée suit les besoins retenus de chaque atelier ; à la validation,
 * la matière d'œuvre entre dans le stock des ateliers et chaque équipement reçoit sa fiche.
 */
@Component({
  selector: 'app-livraison',
  imports: [RouterLink, AteliersNavComponent, ExportBoutonsComponent],
  template: `
    <div class="page large">
      <h1>Livraison</h1>
      <app-ateliers-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (livraison(); as l) {
        <section class="carte">
          <div class="entete-section">
            <p class="infos">
              <a [routerLink]="['/ateliers/commandes', l.commandeId]"><strong>Commande {{ l.commandeReference }}</strong></a>
              <span>{{ l.fournisseur }}</span>
              <span class="pastille {{ l.statut === 'REPARTIE' ? 'present' : 'absent' }}">{{ libelleStatut[l.statut] }}</span>
            </p>
            <app-export [chemin]="'/livraisons/' + l.id" [nom]="'livraison-' + l.commandeReference" libelle="les procès-verbaux de réception et de répartition" />
          </div>
          <p class="doux">
            Reçue le {{ dateCourte(l.dateReception) }}@if (l.recuePar) { par {{ l.recuePar }} }
            @if (l.bonLivraison) { · bon de livraison {{ l.bonLivraison }} }
            @if (l.repartieLe) { · répartie le {{ dateHeureCourte(l.repartieLe) }} }
          </p>
          @if (l.observations) { <p>{{ l.observations }}</p> }
        </section>

        @if (message()) {
          <div class="alerte succes" role="status">{{ message() }}</div>
        }
        @if (enregistrement.erreur()) {
          <div class="alerte erreur" role="alert">{{ enregistrement.erreur() }}</div>
        }

        <section class="carte">
          <h2>Réception</h2>
          <div class="tableau-defilant">
            <table>
              <thead><tr><th>Désignation</th><th class="nombre">Reçu</th><th class="nombre">Conforme</th><th>Non-conformité</th></tr></thead>
              <tbody>
                @for (x of l.lignes; track x.articleId) {
                  <tr>
                    <td>{{ x.designation }} <span class="doux">({{ x.unite }})</span></td>
                    <td class="nombre">{{ quantite(x.recue) }}</td>
                    <td class="nombre" [class.rouge]="x.conforme < x.recue">{{ quantite(x.conforme) }}</td>
                    <td>{{ x.motifNonConformite ?? '' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>

        <section class="carte">
          <div class="entete-section">
            <h2>Répartition entre les ateliers</h2>
            @if (modifiable()) {
              <button type="button" class="bouton discret petit" (click)="reprendrePropositions()">Reprendre les parts proposées</button>
            }
          </div>
          @if (modifiable()) {
            <p class="doux">
              La part proposée suit les besoins retenus de chaque atelier dans la campagne. Toute la quantité
              conforme doit être attribuée avant la validation.
            </p>
          }
          <div class="tableau-defilant">
            <table>
              <thead>
                <tr>
                  <th>Désignation</th><th class="nombre">Conforme</th>
                  @for (a of l.ateliers; track a.id) { <th class="nombre" [title]="a.nom">{{ a.code }}</th> }
                  <th class="nombre">Réparti</th>
                </tr>
              </thead>
              <tbody>
                @for (x of l.lignes; track x.articleId) {
                  <tr>
                    <td>{{ x.designation }}</td>
                    <td class="nombre">{{ quantite(x.conforme) }}</td>
                    @for (a of l.ateliers; track a.id) {
                      <td class="nombre">
                        @if (modifiable()) {
                          <input class="court" inputmode="decimal" [attr.aria-label]="x.designation + ' pour ' + a.code"
                            [value]="saisies()[cle(x.articleId, a.id)] ?? ''" (input)="saisir(x.articleId, a.id, $event)" [disabled]="x.conforme === 0" />
                        } @else {
                          {{ partEnregistree(x, a.id) }}
                        }
                        @if (retenu(x, a.id); as r) { <br /><span class="doux petit-texte">besoin {{ r }}</span> }
                      </td>
                    }
                    <td class="nombre" [class.rouge]="bilan(x).total !== x.conforme">{{ quantite(bilan(x).total) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (modifiable()) {
            @if (erreurSaisie(); as e) { <p class="rouge" role="alert">{{ e }}</p> }
            <div class="actions-ligne">
              <button type="button" class="bouton secondaire petit" [disabled]="!!erreurSaisie() || enregistrement.enCours()" (click)="enregistrer()">Enregistrer</button>
              <button type="button" class="bouton petit" [disabled]="!!erreurSaisie() || !complete() || enregistrement.enCours()" (click)="valider()">Valider et mettre à jour les stocks</button>
            </div>
            @if (!complete() && !erreurSaisie()) {
              <p class="doux">Répartissez toute la quantité conforme pour pouvoir valider.</p>
            }
          }
        </section>
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
      width: 5rem;
      text-align: right;
    }
    .petit-texte {
      font-size: 0.75rem;
    }
    .rouge {
      color: var(--absent);
    }
  `,
})
export class LivraisonPage {
  private readonly api = inject(AteliersApi);

  readonly livraisonId = input.required<string>();

  protected readonly cle = cle;
  protected readonly dateCourte = dateCourte;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly quantite = quantite;
  protected readonly libelleStatut = LIBELLE_STATUT_LIVRAISON;

  protected readonly action = new Action();
  protected readonly enregistrement = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly livraison = signal<LivraisonVue | null>(null);
  protected readonly saisies = signal<Record<string, string>>({});

  protected readonly modifiable = computed(() => {
    const l = this.livraison();
    return !!l && l.gerer && l.statut === 'A_REPARTIR';
  });
  private readonly idsAteliers = computed(() => (this.livraison()?.ateliers ?? []).map((a) => a.id));
  protected readonly erreurSaisie = computed(() => {
    for (const x of this.livraison()?.lignes ?? []) {
      const e = this.bilan(x).erreur;
      if (e) {
        return e;
      }
    }
    return null;
  });
  protected readonly complete = computed(() => (this.livraison()?.lignes ?? []).every((x) => this.bilan(x).total === x.conforme));

  constructor() {
    effect(() => {
      const id = this.livraisonId();
      untracked(() => void this.charger(id));
    });
  }

  protected bilan(x: LigneLivraisonVue): { total: number; erreur: string | null } {
    if (!this.modifiable()) {
      return { total: x.repartition.reduce((t, p) => t + (p.quantite ?? 0), 0), erreur: null };
    }
    return bilanRepartition(x, this.saisies(), this.idsAteliers());
  }

  protected retenu(x: LigneLivraisonVue, atelierId: string): string | null {
    const p = x.repartition.find((r) => r.atelierId === atelierId);
    return p && p.retenu > 0 ? quantite(p.retenu) : null;
  }

  protected partEnregistree(x: LigneLivraisonVue, atelierId: string): string {
    const p = x.repartition.find((r) => r.atelierId === atelierId);
    return p?.quantite ? quantite(p.quantite) : '';
  }

  protected saisir(articleId: string, atelierId: string, e: Event): void {
    const v = (e.target as HTMLInputElement).value;
    this.saisies.update((s) => ({ ...s, [cle(articleId, atelierId)]: v }));
  }

  protected reprendrePropositions(): void {
    this.remplir(this.livraison()!, true);
  }

  protected async enregistrer(): Promise<boolean> {
    const lignes: SaisieRepartition[] = [];
    for (const [k, v] of Object.entries(this.saisies())) {
      const q = v.trim() ? lireQuantite(v, true) : null;
      if (q) {
        const [articleId, atelierId] = k.split('|');
        lignes.push({ articleId, atelierId, quantite: q });
      }
    }
    const l = await this.enregistrement.executer(() => this.api.enregistrerRepartition(this.livraisonId(), lignes));
    if (l) {
      this.livraison.set(l);
      this.remplir(l, false);
      this.message.set('Répartition enregistrée.');
      return true;
    }
    return false;
  }

  protected async valider(): Promise<void> {
    if (!(await this.enregistrer())) {
      return;
    }
    const l = await this.enregistrement.executer(() => this.api.validerRepartition(this.livraisonId()));
    if (l) {
      this.livraison.set(l);
      this.message.set('Répartition validée : la matière d’œuvre est entrée dans le stock des ateliers et les équipements ont reçu leur fiche.');
    }
  }

  /** Remplit la grille avec les parts enregistrées, sinon (ou si demandé) les parts proposées. */
  private remplir(l: LivraisonVue, propositions: boolean): void {
    const dejaEnregistree = l.lignes.some((x) => x.repartition.some((p) => p.quantite !== null));
    const s: Record<string, string> = {};
    for (const x of l.lignes) {
      for (const p of x.repartition) {
        const q = propositions || !dejaEnregistree ? p.proposee : p.quantite;
        if (q) {
          s[cle(x.articleId, p.atelierId)] = quantite(q).replace(/\s/g, '');
        }
      }
    }
    this.saisies.set(s);
  }

  private async charger(id: string): Promise<void> {
    const l = await this.action.executer(() => this.api.livraison(id));
    if (l && id === this.livraisonId()) {
      this.livraison.set(l);
      this.remplir(l, false);
    }
  }
}
