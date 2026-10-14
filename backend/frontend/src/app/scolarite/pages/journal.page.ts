import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte, dateLocale } from '../../core/outils';
import { filtrer } from '../../core/recherche';
import { RechercheComponent } from '../../partage/recherche.component';
import { fcfa, JournalVue, LIBELLE_MOYEN, PaiementVue } from '../modeles-scolarite';
import { ScoNavComponent } from '../sco-nav.component';
import { enregistrerFichier, ScolariteApi } from '../scolarite-api.service';

/** Premier jour du mois d'une date AAAA-MM-JJ. */
export function debutDuMois(jour: string): string {
  return `${jour.slice(0, 8)}01`;
}

/**
 * Journal de caisse : les paiements d'un jour ou d'une période, avec le total par moyen de
 * paiement pour la clôture de caisse (espèces comptées le soir, Mobile Money à rapprocher).
 */
@Component({
  selector: 'app-journal',
  imports: [FormsModule, RouterLink, RechercheComponent, ScoNavComponent],
  template: `
    <div class="page large">
      <h1>Scolarité et paiements</h1>
      <app-sco-nav />

      <form class="periode" (ngSubmit)="charger()">
        <div class="champ">
          <label for="du">Du</label>
          <input id="du" name="du" type="date" [max]="aujourdhui" [(ngModel)]="du" />
        </div>
        <div class="champ">
          <label for="au">Au</label>
          <input id="au" name="au" type="date" [max]="aujourdhui" [(ngModel)]="au" />
        </div>
        <div class="actions-ligne raccourcis">
          <button type="button" class="bouton secondaire petit" (click)="periode(aujourdhui, aujourdhui)">Aujourd'hui</button>
          <button type="button" class="bouton secondaire petit" (click)="periode(debutDuMois(aujourdhui), aujourdhui)">Ce mois</button>
          <button type="submit" class="bouton petit" [disabled]="action.enCours() || !du() || !au()">Afficher</button>
        </div>
      </form>

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (journal(); as j) {
        <h2 class="titre-periode">
          {{ j.du === j.au ? 'Caisse du ' + dateCourte(j.du) : 'Du ' + dateCourte(j.du) + ' au ' + dateCourte(j.au) }}
        </h2>
        <div class="chiffres">
          <div class="total"><strong>{{ fcfa(j.total) }}</strong><span>{{ j.paiements.length }} paiement(s)</span></div>
          @for (m of j.parMoyen; track m.moyen) {
            <div><strong>{{ fcfa(m.montant) }}</strong><span>{{ libelleMoyen[m.moyen] }} · {{ m.nombre }}</span></div>
          }
        </div>

        @if (j.paiements.length) {
          <app-recherche libelle="Élève, classe ou n° de reçu" [(valeur)]="filtre" [total]="j.paiements.length" [trouves]="lignes().length" />
          <section class="carte">
            <div class="tableau-defilant">
              <table class="tableau">
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Reçu</th>
                    <th>Élève</th>
                    <th>Classe</th>
                    <th>Moyen</th>
                    <th class="nombre">Montant</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  @for (p of lignes(); track p.id) {
                    <tr>
                      <td>{{ dateCourte(p.datePaiement) }}</td>
                      <td>{{ p.recuNumero }}</td>
                      <td>
                        @if (eleve(p); as e) {
                          <a [routerLink]="['/scolarite/inscriptions', p.inscriptionId]"><strong>{{ e.nom }}</strong> {{ e.prenoms }}</a>
                        }
                        @if (p.payeur === 'ORGANISME') { <span class="doux"> · organisme</span> }
                      </td>
                      <td>{{ eleve(p)?.classeCode }}</td>
                      <td>{{ libelleMoyen[p.moyen] }}@if (p.referenceExterne) { <span class="doux"> · {{ p.referenceExterne }}</span> }</td>
                      <td class="nombre"><strong>{{ fcfa(p.montant) }}</strong></td>
                      <td><button type="button" class="bouton discret petit" (click)="recu(p)">Reçu</button></td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>
          <p class="doux">Les paiements annulés ne figurent pas dans le journal.</p>
        } @else {
          <div class="carte"><p class="doux">Aucun paiement sur cette période.</p></div>
        }
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .periode {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: 0 1rem;
      .champ {
        flex: 0 1 11rem;
      }
      .raccourcis {
        margin-bottom: 1rem;
      }
    }
    .titre-periode {
      font-size: 1.1rem;
      margin: 0.25rem 0 0.75rem;
    }
    .chiffres {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
      gap: 0.75rem;
      margin: 0 0 1rem;
      div {
        display: flex;
        flex-direction: column;
        padding: 0.75rem 1rem;
        border-radius: var(--rayon);
        background: var(--surface);
        border: 1px solid var(--bordure);
      }
      .total {
        border-color: var(--primaire);
      }
      strong {
        font-size: 1.2rem;
        font-variant-numeric: tabular-nums;
      }
      span {
        font-size: 0.85rem;
        color: var(--texte-doux);
      }
    }
    table {
      font-variant-numeric: tabular-nums;
    }
    td a {
      color: inherit;
    }
  `,
})
export class JournalPage implements OnInit {
  private readonly api = inject(ScolariteApi);

  protected readonly fcfa = fcfa;
  protected readonly dateCourte = dateCourte;
  protected readonly debutDuMois = debutDuMois;
  protected readonly libelleMoyen = LIBELLE_MOYEN;
  protected readonly aujourdhui = dateLocale();
  protected readonly action = new Action();
  protected readonly fichier = new Action();

  protected readonly du = signal(this.aujourdhui);
  protected readonly au = signal(this.aujourdhui);
  protected readonly journal = signal<JournalVue | null>(null);
  protected readonly filtre = signal('');

  protected readonly lignes = computed(() => {
    const j = this.journal();
    if (!j) {
      return [];
    }
    const recents = [...j.paiements].sort((a, b) => b.enregistreLe.localeCompare(a.enregistreLe));
    return filtrer(recents, this.filtre(), (p) => {
      const e = j.eleves[p.inscriptionId];
      return [e?.nom, e?.prenoms, e?.matricule, e?.classeCode, p.recuNumero];
    });
  });

  ngOnInit(): void {
    void this.charger();
  }

  protected periode(du: string, au: string): void {
    this.du.set(du);
    this.au.set(au);
    void this.charger();
  }

  protected async charger(): Promise<void> {
    const [du, au] = this.du() <= this.au() ? [this.du(), this.au()] : [this.au(), this.du()];
    const j = await this.action.executer(() => this.api.journal(du, au));
    if (j) {
      this.journal.set(j);
    }
  }

  protected eleve(p: PaiementVue) {
    return this.journal()?.eleves[p.inscriptionId];
  }

  protected async recu(p: PaiementVue): Promise<void> {
    const blob = await this.fichier.executer(() => this.api.recu(p.id));
    if (blob) {
      enregistrerFichier(blob, `recu-${p.recuNumero ?? p.id}.pdf`);
    }
  }
}
