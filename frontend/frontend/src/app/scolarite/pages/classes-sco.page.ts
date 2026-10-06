import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { ClasseVue } from '../../admin/modeles-admin';
import { dateHeureCourte } from '../../core/outils';
import { filtrer } from '../../core/recherche';
import { SessionService } from '../../core/session.service';
import { RechercheComponent } from '../../partage/recherche.component';
import { EtatClasseVue, fcfa, LIBELLE_BOURSE, ResultatRelancesVue } from '../modeles-scolarite';
import { GESTION_SCOLARITE, ScoNavComponent } from '../sco-nav.component';
import { enregistrerFichier, ScolariteApi } from '../scolarite-api.service';

/** Texte du bilan d'une relance par SMS. */
export function bilanRelances(r: ResultatRelancesVue): string {
  const parties = [`${r.envoyees} SMS envoyé(s)`];
  if (r.dejaRelancees) {
    parties.push(`${r.dejaRelancees} famille(s) déjà relancée(s) récemment`);
  }
  if (r.sansContact) {
    parties.push(`${r.sansContact} sans numéro de téléphone`);
  }
  return parties.join(' · ');
}

/**
 * Suivi des paiements par classe : ce que chaque famille a payé, les retards, la liste
 * Excel des retardataires et la relance des familles par SMS.
 */
@Component({
  selector: 'app-classes-sco',
  imports: [RouterLink, RechercheComponent, ScoNavComponent],
  template: `
    <div class="page large">
      <h1>Scolarité et paiements</h1>
      <app-sco-nav />

      <div class="filtres">
        <div class="champ">
          <label for="classe">Classe</label>
          <select id="classe" [value]="classeId()" (change)="classeId.set($any($event.target).value)">
            <option value="">Choisir une classe</option>
            @for (c of classes(); track c.id) { <option [value]="c.id">{{ c.code }}</option> }
          </select>
        </div>
        @if (gestion()) {
          <button type="button" class="bouton secondaire" [disabled]="relance.enCours()" (click)="relancer()">
            Relancer toutes les familles en retard
          </button>
        }
      </div>

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (relance.erreur()) {
        <div class="alerte erreur" role="alert">{{ relance.erreur() }}</div>
      }
      @if (bilan()) {
        <div class="alerte succes" role="status">{{ bilan() }}</div>
      }

      @if (etat(); as e) {
        <div class="chiffres">
          <div><strong>{{ pourcentage(e.tauxRecouvrementFamille) }}</strong><span>payé par les familles · {{ fcfa(e.payeFamille) }} sur {{ fcfa(e.totalFamille) }}</span></div>
          <div [class.alerte-valeur]="e.retardFamille > 0"><strong>{{ fcfa(e.retardFamille) }}</strong><span>de retard · {{ enRetard().length }} élève(s)</span></div>
          @if (e.totalOrganisme > 0) {
            <div><strong>{{ pourcentage(e.tauxRecouvrementOrganisme) }}</strong><span>versé par les organismes · {{ fcfa(e.payeOrganisme) }} sur {{ fcfa(e.totalOrganisme) }}</span></div>
          }
        </div>

        <div class="entete-section">
          <div class="bascule" role="group" aria-label="Élèves affichés">
            <button type="button" [class.actif]="!seulementRetards()" (click)="seulementRetards.set(false)">Tous ({{ e.eleves.length }})</button>
            <button type="button" [class.actif]="seulementRetards()" (click)="seulementRetards.set(true)">En retard ({{ enRetard().length }})</button>
          </div>
          <div class="actions-ligne">
            <button type="button" class="bouton secondaire petit" [disabled]="fichier.enCours()" (click)="telechargerRetards(e)">Liste Excel des retards</button>
            @if (gestion()) {
              <button type="button" class="bouton petit" [disabled]="relance.enCours() || !enRetard().length" (click)="relancer(e.classeId)">Relancer la classe par SMS</button>
            }
          </div>
        </div>
        @if (fichier.erreur()) {
          <div class="alerte erreur" role="alert">{{ fichier.erreur() }}</div>
        }

        <app-recherche libelle="Nom ou matricule" [(valeur)]="filtre" [total]="lignesBase().length" [trouves]="lignes().length" />

        <section class="carte">
          <div class="tableau-defilant">
            <table class="tableau">
              <thead>
                <tr>
                  <th>Élève</th>
                  <th>Bourse</th>
                  <th class="nombre">Dû famille</th>
                  <th class="nombre">Payé</th>
                  <th class="nombre">Reste</th>
                  <th class="nombre">Retard</th>
                  <th>Dernière relance</th>
                </tr>
              </thead>
              <tbody>
                @for (l of lignes(); track l.inscriptionId) {
                  <tr [class.retard]="l.retardFamille > 0">
                    <td>
                      <a [routerLink]="['/scolarite/inscriptions', l.inscriptionId]"><strong>{{ l.nom }}</strong> {{ l.prenoms }}</a>
                      @if (l.matricule) { <span class="doux"> · {{ l.matricule }}</span> }
                    </td>
                    <td>{{ l.statutBourse === 'NON_BOURSIER' ? '' : libelleBourse[l.statutBourse] }}</td>
                    <td class="nombre">{{ fcfa(l.totalFamille) }}</td>
                    <td class="nombre">{{ fcfa(l.payeFamille) }}</td>
                    <td class="nombre">{{ l.resteFamille ? fcfa(l.resteFamille) : 'soldé ✓' }}</td>
                    <td class="nombre">@if (l.retardFamille) { <strong class="rouge">{{ fcfa(l.retardFamille) }}</strong> }</td>
                    <td class="doux">{{ l.derniereRelance ? dateHeureCourte(l.derniereRelance) : '' }}</td>
                  </tr>
                } @empty {
                  <tr><td colspan="7" class="doux">{{ seulementRetards() ? 'Aucun élève en retard ✓' : 'Aucun élève.' }}</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>
        <p class="doux">Les relances ne portent que sur la part famille échue, une fois par élève pendant le délai paramétré.</p>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      } @else if (!classeId()) {
        <p class="doux">Choisissez une classe pour voir les paiements de chaque élève.</p>
      }
    </div>
  `,
  styles: `
    .filtres {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      justify-content: space-between;
      gap: 0 1rem;
      .champ {
        flex: 0 1 16rem;
        min-width: 0;
      }
      .bouton {
        margin-bottom: 1rem;
      }
    }
    .chiffres {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
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
      strong {
        font-size: 1.3rem;
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
    .bascule {
      display: inline-flex;
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
    table {
      font-variant-numeric: tabular-nums;
    }
    td a {
      color: inherit;
    }
    tr.retard td {
      background: var(--absent-fond);
    }
    .rouge {
      color: var(--absent);
    }
  `,
})
export class ClassesScoPage {
  private readonly admin = inject(AdminApi);
  private readonly api = inject(ScolariteApi);
  private readonly session = inject(SessionService);
  private readonly annee = inject(AnneeCourante);

  protected readonly fcfa = fcfa;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly libelleBourse = LIBELLE_BOURSE;
  protected readonly action = new Action();
  protected readonly relance = new Action();
  protected readonly fichier = new Action();

  protected readonly gestion = computed(() => this.session.aLeRole(...GESTION_SCOLARITE));
  protected readonly classes = signal<ClasseVue[]>([]);
  protected readonly classeId = signal('');
  protected readonly etat = signal<EtatClasseVue | null>(null);
  protected readonly seulementRetards = signal(false);
  protected readonly filtre = signal('');
  protected readonly bilan = signal<string | null>(null);

  protected readonly enRetard = computed(() => (this.etat()?.eleves ?? []).filter((l) => l.retardFamille > 0));
  /** En retard : le plus gros retard d'abord ; sinon par ordre alphabétique. */
  protected readonly lignesBase = computed(() =>
    this.seulementRetards()
      ? [...this.enRetard()].sort((a, b) => b.retardFamille - a.retardFamille)
      : [...(this.etat()?.eleves ?? [])].sort((a, b) => `${a.nom} ${a.prenoms}`.localeCompare(`${b.nom} ${b.prenoms}`)),
  );
  protected readonly lignes = computed(() => filtrer(this.lignesBase(), this.filtre(), (l) => [l.nom, l.prenoms, l.matricule]));

  constructor() {
    effect(() => {
      const a = this.annee.annee();
      untracked(() => {
        if (a) {
          void this.action.executer(async () => {
            const liste = await this.admin.classes(a.id);
            this.classes.set([...liste].sort((x, y) => x.code.localeCompare(y.code)));
          });
        }
      });
    });
    effect(() => {
      const id = this.classeId();
      untracked(() => {
        this.etat.set(null);
        this.bilan.set(null);
        if (id) {
          void this.charger(id);
        }
      });
    });
    void this.annee.charger().catch(() => undefined);
  }

  private async charger(id: string): Promise<void> {
    const e = await this.action.executer(() => this.api.etatClasse(id));
    if (e && id === this.classeId()) {
      this.etat.set(e);
    }
  }

  protected pourcentage(taux: number | null): string {
    return taux === null ? '—' : `${String(taux).replace('.', ',')} %`;
  }

  protected async telechargerRetards(e: EtatClasseVue): Promise<void> {
    const blob = await this.fichier.executer(() => this.api.retards(e.classeId));
    if (blob) {
      enregistrerFichier(blob, `retards-${e.classeCode}.xlsx`);
    }
  }

  protected async relancer(classeId?: string): Promise<void> {
    this.bilan.set(null);
    const r = await this.relance.executer(() => this.api.relancer(classeId));
    if (r) {
      this.bilan.set((classeId ? 'Relance de la classe : ' : 'Relance de l’établissement : ') + bilanRelances(r));
      if (classeId) {
        await this.charger(classeId);
      }
    }
  }
}
