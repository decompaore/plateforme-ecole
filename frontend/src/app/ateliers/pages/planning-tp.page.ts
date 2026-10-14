import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte, dateLocale } from '../../core/outils';
import { AteliersNavComponent } from '../ateliers-nav.component';
import { AchatTp, ajouterJours, CLASSE_STATUT_TP, heure, LIBELLE_STATUT_TP, lundi, SeanceTpVue } from '../modeles-tp';
import { TravauxPratiquesApi } from '../travaux-pratiques-api.service';

/**
 * Planning des travaux pratiques de tous les ateliers (chef des travaux, v0.47.2) : occupation de la semaine,
 * demandes en attente, matière d'œuvre à acheter pour les séances à venir.
 */
@Component({
  selector: 'app-planning-tp',
  imports: [RouterLink, AteliersNavComponent],
  template: `
    <div class="page large">
      <h1>Planning des travaux pratiques</h1>
      <app-ateliers-nav />
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      <section class="carte">
        <div class="actions-ligne">
          <button type="button" class="bouton secondaire petit" (click)="changer(-1)">‹ Semaine précédente</button>
          <strong>Semaine du {{ dateCourte(debut()) }}</strong>
          <button type="button" class="bouton secondaire petit" (click)="changer(1)">Semaine suivante ›</button>
        </div>
        <p class="doux">{{ seances().length }} séance(s) · {{ enAttente() }} demande(s) en attente de validation</p>
        @for (g of parAtelier(); track g.atelierId) {
          <h3><a [routerLink]="['/ateliers', g.atelierId, 'travaux-pratiques']" [queryParams]="{ semaine: debut() }">{{ g.atelier }}</a></h3>
          <div class="tableau-defilant">
            <table class="tableau">
              <thead><tr><th>Date</th><th>Heures</th><th>Classe</th><th>Enseignant</th><th>TP</th><th>Statut</th></tr></thead>
              <tbody>
                @for (s of g.liste; track s.id) {
                  <tr>
                    <td>{{ dateCourte(s.date) }}</td>
                    <td>{{ heure(s.heureDebut) }}–{{ heure(s.heureFin) }}</td>
                    <td>{{ s.classeCode }}</td>
                    <td>{{ s.enseignant }}</td>
                    <td>{{ s.titre }}@if (s.conflits.length) { <br /><span class="rouge">{{ s.conflits.join(' ; ') }}</span> }</td>
                    <td><span class="pastille {{ classeStatut[s.statut] }}">{{ libelleStatut[s.statut] }}</span></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @empty {
          <p class="doux">Aucune séance cette semaine.</p>
        }
      </section>
      <section class="carte">
        <h2>Matière d'œuvre à acheter</h2>
        <p class="doux">Quantités accordées par les chefs d'atelier que le stock ne couvre pas, pour les séances planifiées à venir.</p>
        <div class="tableau-defilant">
          <table class="tableau">
            <thead><tr><th>Date</th><th>Atelier</th><th>Classe</th><th>Article</th><th class="nombre">Quantité</th><th class="nombre">Prix unitaire</th></tr></thead>
            <tbody>
              @for (a of achats(); track $index) {
                <tr><td>{{ dateCourte(a.date) }}</td><td>{{ a.atelier }}</td><td>{{ a.classe }}</td><td>{{ a.designation }}</td><td class="nombre">{{ a.quantite }} {{ a.unite }}</td><td class="nombre">{{ a.prix ?? '—' }}</td></tr>
              } @empty {
                <tr><td colspan="6" class="doux">Rien à acheter.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    </div>
  `,
  styles: `
    .rouge {
      color: var(--absent);
    }
  `,
})
export class PlanningTpPage implements OnInit {
  private readonly api = inject(TravauxPratiquesApi);

  protected readonly action = new Action();
  protected readonly debut = signal(lundi(dateLocale()));
  protected readonly seances = signal<SeanceTpVue[]>([]);
  protected readonly achats = signal<AchatTp[]>([]);
  protected readonly libelleStatut = LIBELLE_STATUT_TP;
  protected readonly classeStatut = CLASSE_STATUT_TP;
  protected readonly heure = heure;
  protected readonly dateCourte = dateCourte;
  protected readonly enAttente = computed(() => this.seances().filter((s) => s.statut === 'DEMANDEE').length);
  protected readonly parAtelier = computed(() => {
    const m = new Map<string, { atelierId: string; atelier: string; liste: SeanceTpVue[] }>();
    for (const s of this.seances()) {
      const g = m.get(s.atelierId) ?? { atelierId: s.atelierId, atelier: `${s.atelierCode} · ${s.atelier}`, liste: [] };
      g.liste.push(s);
      m.set(s.atelierId, g);
    }
    return [...m.values()];
  });

  async ngOnInit(): Promise<void> {
    await this.action.executer(async () => {
      const [seances, achats] = await Promise.all([this.api.planningGeneral(this.debut(), ajouterJours(this.debut(), 6)), this.api.achats()]);
      this.seances.set(seances);
      this.achats.set(achats);
    });
  }

  protected async changer(decalage: number): Promise<void> {
    this.debut.set(ajouterJours(this.debut(), decalage * 7));
    const l = await this.action.executer(() => this.api.planningGeneral(this.debut(), ajouterJours(this.debut(), 6)));
    if (l) {
      this.seances.set(l);
    }
  }
}
