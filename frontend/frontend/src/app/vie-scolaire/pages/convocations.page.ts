import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { dateLocale, dateLongue } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { ConvocationVue, heure, LIBELLE_CONVOCATION, StatutConvocation } from '../modeles-vs';
import { VieScolaireApi } from '../vie-scolaire-api.service';
import { VsNavComponent } from '../vs-nav.component';

function decaler(jours: number): string {
  const d = new Date();
  d.setDate(d.getDate() + jours);
  return dateLocale(d);
}

/**
 * Agenda des convocations de parents : les rendez-vous à venir, et ceux des derniers
 * jours à clôturer (parent venu ou non).
 */
@Component({
  selector: 'app-convocations',
  imports: [FormsModule, VsNavComponent],
  template: `
    <div class="page large">
      <h1>Vie scolaire</h1>
      <app-vs-nav />

      <div class="periode actions-ligne" role="group" aria-label="Période">
        <button type="button" class="bouton petit" [class.secondaire]="vue() !== 'avenir'" (click)="choisir('avenir')">À venir (30 jours)</button>
        <button type="button" class="bouton petit" [class.secondaire]="vue() !== 'passees'" (click)="choisir('passees')">14 derniers jours</button>
      </div>

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }
      @if (aCloturer() > 0 && vue() === 'passees') {
        <div class="alerte attention">{{ aCloturer() }} rendez-vous passé(s) à clôturer : indiquez si le parent est venu.</div>
      }

      @for (j of parJour(); track j.jour) {
        <section class="carte">
          <h2>{{ j.jour === aujourdhui ? "Aujourd'hui" : dateLongue(j.jour) }}</h2>
          <ul class="liste">
            @for (c of j.convocations; track c.id) {
              <li>
                <div class="ligne">
                  <span class="heure">{{ heure(c.rendezVous.split('T')[1]) }}</span>
                  <span class="qui">
                    <strong>{{ c.nom }}</strong> {{ c.prenoms }} <span class="doux">· {{ c.classe }}</span><br />
                    {{ c.motif }}
                    @if (c.compteRendu) { <br /><span class="doux">{{ c.compteRendu }}</span> }
                  </span>
                  <span class="actions-ligne">
                    <span class="pastille" [class.present]="c.statut === 'HONOREE'" [class.absent]="c.statut === 'NON_HONOREE'">{{ libelles[c.statut] }}</span>
                    @if (c.statut === 'PREVUE' && encadrement()) {
                      @if (passe(c)) {
                        <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="cloturer(c, 'HONOREE')">Venu</button>
                        <button type="button" class="bouton secondaire petit" [disabled]="action.enCours()" (click)="cloturer(c, 'NON_HONOREE')">Pas venu</button>
                      } @else {
                        <button type="button" class="bouton discret petit" [disabled]="action.enCours()" (click)="cloturer(c, 'ANNULEE')">Annuler</button>
                      }
                    }
                  </span>
                </div>
              </li>
            }
          </ul>
        </section>
      } @empty {
        @if (!action.enCours()) {
          <div class="carte">
            <p class="doux">
              {{ vue() === 'avenir' ? 'Aucune convocation prévue dans les 30 prochains jours.' : 'Aucune convocation ces 14 derniers jours.' }}
              On convoque les parents depuis la fiche d'un élève (onglet « Élèves »).
            </p>
          </div>
        }
      }
    </div>
  `,
  styles: `
    .periode {
      margin-bottom: 1rem;
    }
    h2 {
      font-size: 1.05rem;
    }
    h2::first-letter {
      text-transform: uppercase;
    }
    .ligne {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: 0.5rem 1rem;
    }
    .heure {
      font-weight: 700;
      font-variant-numeric: tabular-nums;
      min-width: 3.5rem;
    }
    .qui {
      flex: 1 1 14rem;
      min-width: 0;
    }
  `,
})
export class ConvocationsPage implements OnInit {
  private readonly api = inject(VieScolaireApi);
  private readonly session = inject(SessionService);

  protected readonly aujourdhui = dateLocale();
  protected readonly dateLongue = dateLongue;
  protected readonly heure = heure;
  protected readonly libelles = LIBELLE_CONVOCATION;
  protected readonly action = new Action();
  protected readonly vue = signal<'avenir' | 'passees'>('avenir');
  protected readonly convocations = signal<ConvocationVue[]>([]);
  protected readonly message = signal<string | null>(null);
  protected readonly encadrement = computed(() =>
    this.session.roles().some((r) => ['SURVEILLANT', 'CENSEUR', 'ADMIN_ECOLE'].includes(r)),
  );

  protected readonly parJour = computed(() => {
    const groupes = new Map<string, ConvocationVue[]>();
    const tri = this.vue() === 'avenir' ? 1 : -1;
    for (const c of [...this.convocations()].sort((a, b) => tri * a.rendezVous.localeCompare(b.rendezVous))) {
      const jour = c.rendezVous.slice(0, 10);
      groupes.set(jour, [...(groupes.get(jour) ?? []), c]);
    }
    return [...groupes.entries()].map(([jour, convocations]) => ({ jour, convocations }));
  });
  protected readonly aCloturer = computed(() => this.convocations().filter((c) => c.statut === 'PREVUE' && this.passe(c)).length);

  async ngOnInit(): Promise<void> {
    await this.charger();
  }

  protected async choisir(vue: 'avenir' | 'passees'): Promise<void> {
    this.vue.set(vue);
    this.message.set(null);
    await this.charger();
  }

  private async charger(): Promise<void> {
    const vue = this.vue();
    const [du, au] = vue === 'avenir' ? [this.aujourdhui, decaler(30)] : [decaler(-14), this.aujourdhui];
    const l = await this.action.executer(() => this.api.agenda(du, au));
    if (l && vue === this.vue()) {
      this.convocations.set(l);
    }
  }

  protected passe(c: ConvocationVue): boolean {
    return new Date(c.rendezVous) <= new Date();
  }

  protected async cloturer(c: ConvocationVue, statut: StatutConvocation): Promise<void> {
    if (statut === 'ANNULEE' && !window.confirm(`Annuler le rendez-vous de la famille de ${c.prenoms} ${c.nom} ?`)) {
      return;
    }
    const r = await this.action.executer(() => this.api.cloturer(c.id, statut, null));
    if (r) {
      this.convocations.update((l) => l.map((x) => (x.id === r.id ? r : x)));
      this.message.set(`${c.prenoms} ${c.nom} : ${LIBELLE_CONVOCATION[r.statut].toLowerCase()}.`);
    }
  }
}
