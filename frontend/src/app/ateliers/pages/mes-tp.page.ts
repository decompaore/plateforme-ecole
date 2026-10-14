import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte } from '../../core/outils';
import { AteliersApi } from '../ateliers-api.service';
import { AteliersNavComponent } from '../ateliers-nav.component';
import { AtelierResumeVue } from '../modeles-ateliers';
import { CLASSE_STATUT_TP, heure, LIBELLE_STATUT_TP, SeanceTpVue } from '../modeles-tp';
import { TravauxPratiquesApi } from '../travaux-pratiques-api.service';

/** Mes travaux pratiques (enseignant, v0.47.2) : mes séances dans les ateliers, ce qui reste à faire. */
@Component({
  selector: 'app-mes-tp',
  imports: [RouterLink, AteliersNavComponent],
  template: `
    <div class="page large">
      <h1>Mes travaux pratiques</h1>
      <app-ateliers-nav />
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      <section class="carte">
        <h2>Mes ateliers</h2>
        <p class="doux">Ouvrez un atelier pour demander une séance au chef d'atelier, avec la matière d'œuvre nécessaire.</p>
        <ul class="liste">
          @for (a of ateliers(); track a.id) {
            <li><a class="ligne-lien" [routerLink]="['/ateliers', a.id, 'travaux-pratiques']"><span><strong>{{ a.code }}</strong> · {{ a.nom }}</span><span>›</span></a></li>
          } @empty {
            <li class="doux">Aucun atelier : vous n'enseignez pas de matière technique d'une filière servie par un atelier.</li>
          }
        </ul>
      </section>
      @if (aFaire().length) {
        <section class="carte">
          <h2>Comptes rendus à faire ({{ aFaire().length }})</h2>
          <ul class="liste">
            @for (s of aFaire(); track s.id) {
              <li><a class="ligne-lien" [routerLink]="['/ateliers', s.atelierId, 'travaux-pratiques']" [queryParams]="{ semaine: s.date }"><span>{{ dateCourte(s.date) }} {{ heure(s.heureDebut) }} · {{ s.classeCode }} · {{ s.titre }}</span><span>Compte rendu ›</span></a></li>
            }
          </ul>
        </section>
      }
      <section class="carte">
        <h2>Mes séances</h2>
        <ul class="liste">
          @for (s of seances(); track s.id) {
            <li>
              <a class="ligne-lien" [routerLink]="['/ateliers', s.atelierId, 'travaux-pratiques']" [queryParams]="{ semaine: s.date }">
                <span>
                  <strong>{{ dateCourte(s.date) }} {{ heure(s.heureDebut) }}–{{ heure(s.heureFin) }}</strong> · {{ s.atelierCode }} · {{ s.classeCode }} · {{ s.titre }}
                  @if (s.motif) { <br /><span class="doux">{{ s.motif }}</span> }
                </span>
                <span class="pastille {{ classeStatut[s.statut] }}">{{ libelleStatut[s.statut] }}</span>
              </a>
            </li>
          } @empty {
            <li class="doux">Aucune séance de travaux pratiques.</li>
          }
        </ul>
      </section>
    </div>
  `,
})
export class MesTpPage implements OnInit {
  private readonly api = inject(TravauxPratiquesApi);
  private readonly ateliersApi = inject(AteliersApi);

  protected readonly action = new Action();
  protected readonly seances = signal<SeanceTpVue[]>([]);
  protected readonly ateliers = signal<AtelierResumeVue[]>([]);
  protected readonly libelleStatut = LIBELLE_STATUT_TP;
  protected readonly classeStatut = CLASSE_STATUT_TP;
  protected readonly heure = heure;
  protected readonly dateCourte = dateCourte;
  protected readonly aFaire = computed(() => this.seances().filter((s) => s.droits.compteRendu));

  async ngOnInit(): Promise<void> {
    await this.action.executer(async () => {
      const [seances, ateliers] = await Promise.all([this.api.miennes(), this.ateliersApi.ateliers()]);
      this.seances.set(seances);
      this.ateliers.set(ateliers.filter((a) => a.ouvert));
    });
  }
}
