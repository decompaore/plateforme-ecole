import { Component, computed, inject, OnInit, signal } from '@angular/core';

import { filtrer } from '../../core/recherche';
import { RechercheComponent } from '../../partage/recherche.component';
import { Action } from '../action';
import { ActiviteQuotidienneComponent, jourCourt, pourcentage } from '../activite-quotidienne.component';
import { AdminApi } from '../admin-api.service';
import { AdminNavComponent } from '../admin-nav.component';
import { AdoptionEtablissement, LIBELLE_ROLE, PersonneActivite } from '../modeles-admin';

type Filtre = 'tous' | 'inactifs' | 'enseignants-sans-appel';

/**
 * Utilisation de l'application dans l'établissement : qui s'en sert, quelles fonctions sont
 * utilisées, et les membres du personnel à accompagner (jamais connectés, enseignants qui ne
 * font pas encore l'appel dans l'application).
 */
@Component({
  selector: 'app-utilisation',
  imports: [AdminNavComponent, ActiviteQuotidienneComponent, RechercheComponent],
  template: `
    <div class="page large">
      <h1>Utilisation de l'application</h1>
      <app-admin-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      <div class="periode" role="group" aria-label="Période">
        @for (p of periodes; track p) {
          <button type="button" class="bouton petit" [class.secondaire]="jours() !== p" [attr.aria-pressed]="jours() === p"
            (click)="choisir(p)">{{ p }} jours</button>
        }
      </div>

      @if (vue(); as v) {
        <div class="chiffres">
          <div><strong>{{ v.personnel.actifs }} / {{ v.personnel.total }}</strong><span>membres du personnel actifs ({{ pourcentage(v.personnel) }})</span></div>
          <div><strong>{{ v.enseignants.actifs }} / {{ v.enseignants.total }}</strong><span>enseignants actifs ({{ pourcentage(v.enseignants) }})</span></div>
          <div><strong>{{ v.parents.actifs }} / {{ v.parents.total }}</strong><span>parents actifs ({{ pourcentage(v.parents) }})</span></div>
        </div>

        <section class="carte">
          <app-activite-quotidienne [jours]="v.quotidien" />
          <h2>Ce qui a été fait dans l'application</h2>
          @if (actions().length) {
            <ul class="liste actions">
              @for (a of actions(); track a.code) {
                <li><span>{{ a.libelle }}</span><strong>{{ a.valeur }}</strong></li>
              }
            </ul>
          } @else {
            <p class="doux">Aucune action enregistrée sur la période.</p>
          }
          <p class="doux petit-texte">Une personne est « active » un jour où elle a ouvert l'application connectée à l'établissement.</p>
        </section>

        <section class="carte">
          <h2>Personnel</h2>
          <div class="filtres">
            <app-recherche libelle="Rechercher une personne" [(valeur)]="recherche" [total]="v.personnes.length" [trouves]="affichees().length" />
            <label class="visuellement-cache" for="filtrePersonnel">Afficher</label>
            <select id="filtrePersonnel" [value]="filtre()" (change)="filtre.set($any($event.target).value)">
              <option value="tous">Tout le personnel</option>
              <option value="inactifs">Pas actifs sur la période</option>
              <option value="enseignants-sans-appel">Enseignants sans appel dans l'application</option>
            </select>
          </div>
          <div class="tableau-defilant">
            <table class="tableau">
              <thead>
                <tr>
                  <th>Nom</th><th>Rôles</th><th class="nombre">Jours actifs</th><th>Dernière activité</th>
                  <th class="nombre">Appels</th><th class="nombre">Notes</th><th class="nombre">Cahier</th>
                </tr>
              </thead>
              <tbody>
                @for (p of affichees(); track p.utilisateurId) {
                  <tr [class.inactif]="!p.joursActifs">
                    <td><strong>{{ p.nom }}</strong> {{ p.prenoms }}</td>
                    <td>{{ roles(p) }}</td>
                    <td class="nombre">{{ p.joursActifs }}</td>
                    <td>{{ p.derniereActivite ? jourCourt(p.derniereActivite) : 'jamais' }}</td>
                    <td class="nombre">{{ p.appels }}</td>
                    <td class="nombre">{{ p.notes }}</td>
                    <td class="nombre">{{ p.cahier }}</td>
                  </tr>
                } @empty {
                  <tr><td colspan="7" class="doux">Personne ne correspond.</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .periode {
      display: flex;
      gap: 0.4rem;
      margin-bottom: 1rem;
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
    h2 {
      font-size: 1.05rem;
      margin: 0.5rem 0;
    }
    .actions li {
      display: flex;
      justify-content: space-between;
      gap: 1rem;
      font-variant-numeric: tabular-nums;
    }
    .petit-texte {
      font-size: 0.85rem;
    }
    .filtres {
      display: flex;
      flex-wrap: wrap;
      gap: 0 0.75rem;
      align-items: flex-start;
      app-recherche {
        flex: 1 1 16rem;
        min-width: 0;
      }
      select {
        width: auto;
      }
    }
    tr.inactif td {
      color: var(--texte-doux);
    }
  `,
})
export class UtilisationPage implements OnInit {
  private readonly api = inject(AdminApi);

  protected readonly periodes = [7, 30, 90];
  protected readonly pourcentage = pourcentage;
  protected readonly jourCourt = jourCourt;
  protected readonly action = new Action();
  protected readonly jours = signal(30);
  protected readonly vue = signal<AdoptionEtablissement | null>(null);
  protected readonly recherche = signal('');
  protected readonly filtre = signal<Filtre>('tous');

  protected readonly actions = computed(() => {
    const v = this.vue();
    return v ? v.indicateurs.filter((i) => v.actions[i.code]).map((i) => ({ ...i, valeur: v.actions[i.code] })) : [];
  });

  protected readonly affichees = computed(() => {
    const personnes = (this.vue()?.personnes ?? []).filter((p) => {
      switch (this.filtre()) {
        case 'inactifs':
          return !p.joursActifs;
        case 'enseignants-sans-appel':
          return p.roles.includes('ENSEIGNANT') && !p.appels;
        default:
          return true;
      }
    });
    return filtrer(personnes, this.recherche(), (p) => [p.nom, p.prenoms]);
  });

  async ngOnInit(): Promise<void> {
    await this.charger();
  }

  protected async choisir(jours: number): Promise<void> {
    this.jours.set(jours);
    await this.charger();
  }

  protected roles(p: PersonneActivite): string {
    return p.roles.map((r) => LIBELLE_ROLE[r] ?? r).join(', ');
  }

  private async charger(): Promise<void> {
    const v = await this.action.executer(() => this.api.adoption(this.jours()));
    if (v) {
      this.vue.set(v);
    }
  }
}
