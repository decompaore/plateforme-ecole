import { Component, computed, inject, OnInit, signal } from '@angular/core';

import { filtrer } from '../../core/recherche';
import { RechercheComponent } from '../../partage/recherche.component';
import { Action } from '../action';
import { ActiviteQuotidienneComponent, jourCourt, pourcentage } from '../activite-quotidienne.component';
import { AdminApi } from '../admin-api.service';
import { AdoptionPlateforme, EtablissementAdoption, LIBELLE_ALERTE_ADOPTION } from '../modeles-admin';
import { PlateformeNavComponent } from '../plateforme-nav.component';

/** Colonnes d'actions affichées dans le tableau (le détail montre toutes les autres). */
const COLONNES = ['APPELS', 'NOTES', 'CAHIER', 'PAIEMENTS', 'SMS'];

/**
 * Super administrateur : adoption de l'application dans chaque établissement (uniquement des
 * nombres). Les établissements à accompagner apparaissent en premier.
 */
@Component({
  selector: 'app-adoption-plateforme',
  imports: [PlateformeNavComponent, ActiviteQuotidienneComponent, RechercheComponent],
  template: `
    <div class="page large">
      <h1>Adoption</h1>
      <app-plateforme-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      <div class="barre-outils">
        <div class="periode" role="group" aria-label="Période">
          @for (p of periodes; track p) {
            <button type="button" class="bouton petit" [class.secondaire]="jours() !== p" [attr.aria-pressed]="jours() === p"
              (click)="choisir(p)">{{ p }} jours</button>
          }
        </div>
        <button type="button" class="bouton discret petit" [disabled]="action.enCours()" (click)="recalculer()">Recalculer la période</button>
      </div>

      @if (vue(); as v) {
        <div class="chiffres">
          <div><strong>{{ v.etablissementsActifs }} / {{ v.etablissements }}</strong><span>établissements avec de l'activité</span></div>
          <div><strong>{{ v.actifs }} / {{ v.comptes }}</strong><span>comptes actifs</span></div>
          <div><strong>{{ pourcentage(v.enseignants) }}</strong><span>des enseignants actifs ({{ v.enseignants.actifs }} / {{ v.enseignants.total }})</span></div>
          <div><strong>{{ pourcentage(v.parents) }}</strong><span>des parents actifs ({{ v.parents.actifs }} / {{ v.parents.total }})</span></div>
        </div>

        <section class="carte">
          <app-activite-quotidienne [jours]="v.quotidien" />
          @if (totaux().length) {
            <ul class="totaux">
              @for (a of totaux(); track a.code) {
                <li><strong>{{ a.valeur }}</strong>&nbsp;<span class="doux">{{ a.libelle }}</span></li>
              }
            </ul>
          }
        </section>

        <section class="carte">
          <div class="filtres">
            <app-recherche libelle="Rechercher un établissement (nom ou code)" [(valeur)]="recherche"
              [total]="v.details.length" [trouves]="affiches().length" />
            <label class="case">
              <input type="checkbox" [checked]="seulementAlertes()" (change)="seulementAlertes.set(!seulementAlertes())" />
              À accompagner seulement ({{ nombreAlertes() }})
            </label>
          </div>
          <div class="tableau-defilant">
            <table class="tableau">
              <thead>
                <tr>
                  <th>Établissement</th>
                  <th class="nombre">Comptes actifs</th>
                  <th class="nombre">Enseignants</th>
                  <th class="nombre">Parents</th>
                  <th class="nombre">Jours actifs</th>
                  <th>Dernière activité</th>
                  @for (c of colonnes; track c) { <th class="nombre">{{ libelleCourt(c) }}</th> }
                  <th></th>
                </tr>
              </thead>
              <tbody>
                @for (e of affiches(); track e.id) {
                  <tr [class.alerte-ligne]="e.alertes.length">
                    <td>
                      <strong>{{ e.nom }}</strong> <span class="doux">{{ e.code }}</span>
                      @if (e.statut !== 'ACTIF') { <span class="pastille">{{ e.statut === 'SUSPENDU' ? 'Suspendu' : 'Résilié' }}</span> }
                      @for (a of e.alertes; track a) { <br /><span class="pastille absent">{{ libelleAlerte[a] }}</span> }
                    </td>
                    <td class="nombre">{{ e.actifs }} / {{ e.comptes }}</td>
                    <td class="nombre">{{ pourcentage(e.enseignants) }}</td>
                    <td class="nombre">{{ pourcentage(e.parents) }}</td>
                    <td class="nombre">{{ e.joursActifs }}</td>
                    <td>{{ e.derniereActivite ? jourCourt(e.derniereActivite) : 'jamais' }}</td>
                    @for (c of colonnes; track c) { <td class="nombre">{{ e.actions[c] ?? 0 }}</td> }
                    <td><button type="button" class="bouton discret petit" [attr.aria-expanded]="ouvert() === e.id" (click)="basculer(e)">Détail</button></td>
                  </tr>
                  @if (ouvert() === e.id) {
                    <tr class="detail">
                      <td [attr.colspan]="7 + colonnes.length">
                        <p class="doux">
                          Enseignants actifs : {{ e.enseignants.actifs }} / {{ e.enseignants.total }} · parents actifs :
                          {{ e.parents.actifs }} / {{ e.parents.total }}
                        </p>
                        <ul class="totaux">
                          @for (i of v.indicateurs; track i.code) {
                            <li><strong>{{ e.actions[i.code] ?? 0 }}</strong>&nbsp;<span class="doux">{{ i.libelle }}</span></li>
                          }
                        </ul>
                      </td>
                    </tr>
                  }
                } @empty {
                  <tr><td [attr.colspan]="7 + colonnes.length" class="doux">Aucun établissement ne correspond.</td></tr>
                }
              </tbody>
            </table>
          </div>
          <p class="doux petit-texte">
            Du {{ jourCourt(v.debut) }} au {{ jourCourt(v.fin) }} : un compte est actif s'il a ouvert l'application au moins un
            jour de la période. Seuls des nombres sont affichés : aucune donnée personnelle des établissements.
          </p>
        </section>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .barre-outils {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 0.5rem;
      margin-bottom: 1rem;
    }
    .periode {
      display: flex;
      gap: 0.4rem;
    }
    .chiffres {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr));
      gap: 0.75rem;
      margin-bottom: 1rem;
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
    }
    .totaux {
      display: flex;
      flex-wrap: wrap;
      gap: 0.3rem 1.2rem;
      list-style: none;
      margin: 0.5rem 0 0;
      padding: 0;
      font-variant-numeric: tabular-nums;
    }
    .filtres {
      display: flex;
      flex-wrap: wrap;
      gap: 0 1rem;
      align-items: center;
      app-recherche {
        flex: 1 1 16rem;
        min-width: 0;
      }
    }
    .case {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      margin-bottom: 1rem;
      input {
        width: 1.2rem;
        min-height: 1.2rem;
        height: 1.2rem;
      }
    }
    .detail td {
      background: var(--surface);
    }
    .petit-texte {
      font-size: 0.85rem;
    }
  `,
})
export class AdoptionPlateformePage implements OnInit {
  private readonly api = inject(AdminApi);

  protected readonly periodes = [7, 30, 90];
  protected readonly colonnes = COLONNES;
  protected readonly pourcentage = pourcentage;
  protected readonly jourCourt = jourCourt;
  protected readonly libelleAlerte = LIBELLE_ALERTE_ADOPTION;
  protected readonly action = new Action();
  protected readonly jours = signal(30);
  protected readonly vue = signal<AdoptionPlateforme | null>(null);
  protected readonly recherche = signal('');
  protected readonly seulementAlertes = signal(false);
  protected readonly ouvert = signal<string | null>(null);

  protected readonly nombreAlertes = computed(() => (this.vue()?.details ?? []).filter((e) => e.alertes.length).length);
  protected readonly affiches = computed(() =>
    filtrer(
      (this.vue()?.details ?? []).filter((e) => !this.seulementAlertes() || e.alertes.length),
      this.recherche(),
      (e) => [e.nom, e.code],
    ),
  );
  protected readonly totaux = computed(() => {
    const v = this.vue();
    return v ? v.indicateurs.filter((i) => v.actions[i.code]).map((i) => ({ ...i, valeur: v.actions[i.code] })) : [];
  });

  async ngOnInit(): Promise<void> {
    await this.charger();
  }

  protected async choisir(jours: number): Promise<void> {
    this.jours.set(jours);
    await this.charger();
  }

  protected async recalculer(): Promise<void> {
    const v = await this.action.executer(() => this.api.recalculerAdoption(this.jours()));
    if (v) {
      this.vue.set(v);
    }
  }

  protected basculer(e: EtablissementAdoption): void {
    this.ouvert.set(this.ouvert() === e.id ? null : e.id);
  }

  protected libelleCourt(code: string): string {
    return { APPELS: 'Appels', NOTES: 'Notes', CAHIER: 'Cahier', PAIEMENTS: 'Paiements', SMS: 'SMS' }[code] ?? code;
  }

  private async charger(): Promise<void> {
    const v = await this.action.executer(() => this.api.adoptionPlateforme(this.jours()));
    if (v) {
      this.vue.set(v);
    }
  }
}
