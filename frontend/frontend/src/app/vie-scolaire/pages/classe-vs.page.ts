import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { ClasseVue, PeriodeVue } from '../../admin/modeles-admin';
import { dateCourte, dateLocale } from '../../core/outils';
import { AbsencesParMatiereVue, discipline, heures, SyntheseEleveVue } from '../modeles-vs';
import { VieScolaireApi } from '../vie-scolaire-api.service';
import { VsNavComponent } from '../vs-nav.component';

/** Période étudiée : toute l'année, une période (trimestre, semestre) ou les 30 derniers jours. */
type Choix = 'annee' | '30j' | string;

/**
 * Absences d'une classe : par discipline (quelle matière, quel cours les élèves manquent
 * le plus) et par élève (heures non justifiées d'abord). Pour le professeur principal, le
 * censeur et le conseil de classe.
 */
@Component({
  selector: 'app-classe-vs',
  imports: [RouterLink, VsNavComponent],
  template: `
    <div class="page large">
      <h1>Vie scolaire</h1>
      <app-vs-nav />

      <div class="choix">
        <div class="champ">
          <label for="classe">Classe</label>
          <select id="classe" [value]="classeId()" (change)="choisirClasse($any($event.target).value)">
            <option value="" disabled>Choisir une classe</option>
            @for (c of classes(); track c.id) { <option [value]="c.id">{{ c.code }}</option> }
          </select>
        </div>
        <div class="champ">
          <label for="periode">Période</label>
          <select id="periode" [value]="choix()" (change)="choisirPeriode($any($event.target).value)">
            <option value="annee">Toute l'année{{ annee.annee() ? ' ' + annee.annee()!.libelle : '' }}</option>
            @for (p of periodesClasse(); track p.id) {
              <option [value]="p.id">{{ p.libelle }} ({{ dateCourte(p.debut) }} – {{ dateCourte(p.fin) }})</option>
            }
            <option value="30j">30 derniers jours</option>
          </select>
        </div>
      </div>

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (classeId() && charge()) {
        <section class="carte">
          <h2>Par discipline</h2>
          @if (parMatiere().length > 0) {
            <p class="doux">Cours manqués : une absence d'un élève à une séance. Les disciplines les plus manquées d'abord.</p>
            <div class="tableau-defilant">
              <table class="tableau">
                <thead>
                  <tr>
                    <th>Discipline</th><th class="nombre">Cours manqués</th><th class="nombre">Élèves</th>
                    <th class="nombre">Heures</th><th class="nombre">Non justifiées</th><th class="nombre">Retards</th>
                  </tr>
                </thead>
                <tbody>
                  @for (m of parMatiere(); track m.matiereId ?? 'general') {
                    <tr>
                      <td>
                        <span class="barre" [style.width.%]="part(m)" aria-hidden="true"></span>
                        {{ discipline(m) }}
                      </td>
                      <td class="nombre">{{ m.absences }}</td>
                      <td class="nombre">{{ m.eleves }}</td>
                      <td class="nombre">{{ heures(m.heures) }}</td>
                      <td class="nombre" [class.alerte-valeur]="m.heuresNonJustifiees > 0">{{ heures(m.heuresNonJustifiees) }}</td>
                      <td class="nombre">{{ m.retards }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else {
            <p class="doux">Aucune absence ni aucun retard sur cette période.</p>
          }
        </section>

        <section class="carte">
          <h2>Par élève</h2>
          @if (eleves().length > 0) {
            <p class="doux">Heures non justifiées d'abord. Touchez un nom pour ouvrir la fiche.</p>
            <div class="tableau-defilant">
              <table class="tableau">
                <thead>
                  <tr>
                    <th>Élève</th><th class="nombre">Absences</th><th class="nombre">Heures</th>
                    <th class="nombre">Non justifiées</th><th class="nombre">Retards</th>
                  </tr>
                </thead>
                <tbody>
                  @for (e of eleves(); track e.inscriptionId) {
                    <tr>
                      <td>
                        @if (eleveDe()[e.inscriptionId]; as id) {
                          <a [routerLink]="['/vie-scolaire/eleves', id]"><strong>{{ e.nom }}</strong> {{ e.prenoms }}</a>
                        } @else {
                          <strong>{{ e.nom }}</strong> {{ e.prenoms }}
                        }
                      </td>
                      <td class="nombre">{{ e.absences }}</td>
                      <td class="nombre">{{ heures(e.heuresAbsence) }}</td>
                      <td class="nombre" [class.alerte-valeur]="e.heuresNonJustifiees > 0">{{ heures(e.heuresNonJustifiees) }}</td>
                      <td class="nombre">{{ e.retards }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (sansAbsence() > 0) {
              <p class="doux">{{ sansAbsence() }} élève(s) sans absence ni retard sur cette période.</p>
            }
          } @else {
            <p class="doux">Aucun élève absent ou en retard sur cette période.</p>
          }
        </section>
      } @else if (!classeId() && classes().length > 0) {
        <p class="doux">Choisissez une classe.</p>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .choix {
      display: flex;
      flex-wrap: wrap;
      gap: 0 1rem;
      .champ {
        flex: 1 1 14rem;
        min-width: 0;
      }
    }
    h2 {
      font-size: 1.05rem;
    }
    td {
      position: relative;
    }
    .barre {
      position: absolute;
      left: 0;
      bottom: 2px;
      height: 3px;
      border-radius: 2px;
      background: var(--absent);
      opacity: 0.55;
    }
    .alerte-valeur {
      color: var(--absent);
      font-weight: 600;
    }
    table {
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class ClasseVsPage implements OnInit {
  private readonly api = inject(VieScolaireApi);
  private readonly admin = inject(AdminApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  protected readonly annee = inject(AnneeCourante);

  protected readonly discipline = discipline;
  protected readonly heures = heures;
  protected readonly dateCourte = dateCourte;
  protected readonly action = new Action();

  protected readonly classes = signal<ClasseVue[]>([]);
  private readonly periodes = signal<PeriodeVue[]>([]);
  protected readonly classeId = signal('');
  protected readonly choix = signal<Choix>('annee');
  protected readonly parMatiere = signal<AbsencesParMatiereVue[]>([]);
  private readonly synthese = signal<SyntheseEleveVue[]>([]);
  protected readonly eleveDe = signal<Record<string, string>>({});
  protected readonly charge = signal(false);

  protected readonly periodesClasse = computed(() => {
    const classe = this.classes().find((c) => c.id === this.classeId());
    return this.periodes()
      .filter((p) => !classe || p.profilId === classe.profilId)
      .sort((a, b) => a.ordre - b.ordre);
  });
  protected readonly eleves = computed(() =>
    this.synthese()
      .filter((e) => e.absences > 0 || e.retards > 0)
      .sort((a, b) => b.heuresNonJustifiees - a.heuresNonJustifiees || b.heuresAbsence - a.heuresAbsence || a.nom.localeCompare(b.nom)),
  );
  protected readonly sansAbsence = computed(() => this.synthese().length - this.eleves().length);
  private readonly maxHeures = computed(() => Math.max(1, ...this.parMatiere().map((m) => m.heures)));

  async ngOnInit(): Promise<void> {
    await this.action.executer(async () => {
      await this.annee.charger();
      const a = this.annee.annee();
      if (!a) {
        return;
      }
      const [classes, periodes] = await Promise.all([this.admin.classes(a.id), this.admin.periodes(a.id)]);
      this.classes.set([...classes].sort((x, y) => x.code.localeCompare(y.code)));
      this.periodes.set(periodes);
    });
    const demandee = this.route.snapshot.queryParamMap.get('classe');
    if (demandee && this.classes().some((c) => c.id === demandee)) {
      await this.choisirClasse(demandee);
    }
  }

  protected part(m: AbsencesParMatiereVue): number {
    return Math.round((m.heures / this.maxHeures()) * 100);
  }

  protected async choisirClasse(id: string): Promise<void> {
    this.classeId.set(id);
    if (this.choix() !== 'annee' && this.choix() !== '30j' && !this.periodesClasse().some((p) => p.id === this.choix())) {
      this.choix.set('annee');
    }
    void this.router.navigate([], { queryParams: { classe: id }, replaceUrl: true });
    await this.charger();
  }

  protected async choisirPeriode(choix: Choix): Promise<void> {
    this.choix.set(choix);
    await this.charger();
  }

  private bornes(): [string | undefined, string | undefined] {
    const c = this.choix();
    if (c === 'annee') {
      return [undefined, undefined];
    }
    if (c === '30j') {
      const d = new Date();
      d.setDate(d.getDate() - 29);
      return [dateLocale(d), dateLocale()];
    }
    const p = this.periodes().find((x) => x.id === c);
    return p ? [p.debut, p.fin] : [undefined, undefined];
  }

  private async charger(): Promise<void> {
    const id = this.classeId();
    const choix = this.choix();
    if (!id) {
      return;
    }
    const [du, au] = this.bornes();
    await this.action.executer(async () => {
      const [parMatiere, synthese, inscriptions] = await Promise.all([
        this.api.absencesParMatiere(id, du, au),
        this.api.syntheseClasse(id, du, au),
        this.admin.inscriptions(id),
      ]);
      if (id === this.classeId() && choix === this.choix()) {
        this.parMatiere.set(parMatiere);
        this.synthese.set(synthese);
        this.eleveDe.set(Object.fromEntries(inscriptions.map((i) => [i.id, i.eleveId])));
        this.charge.set(true);
      }
    });
  }
}
