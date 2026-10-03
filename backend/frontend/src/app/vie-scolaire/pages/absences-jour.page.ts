import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateLocale, dateLongue, lendemain } from '../../core/outils';
import { filtrer } from '../../core/recherche';
import { SessionService } from '../../core/session.service';
import { RechercheComponent } from '../../partage/recherche.component';
import { JustificationComponent } from '../justification.component';
import { CreneauVue, discipline, EleveDuJourVue, heure } from '../modeles-vs';
import { VieScolaireApi } from '../vie-scolaire-api.service';
import { VsNavComponent } from '../vs-nav.component';

/** Veille d'une date AAAA-MM-JJ. */
function veille(jour: string): string {
  const [a, m, j] = jour.split('-').map(Number);
  return dateLocale(new Date(a, m - 1, j - 1));
}

/**
 * Absences du jour : tous les élèves absents ou en retard dans l'établissement, classe
 * par classe, d'après les appels reçus. C'est la liste de travail du surveillant :
 * appeler les familles, enregistrer les justificatifs.
 */
@Component({
  selector: 'app-absences-jour',
  imports: [FormsModule, RouterLink, VsNavComponent, RechercheComponent, JustificationComponent],
  template: `
    <div class="page large">
      <h1>Vie scolaire</h1>
      <app-vs-nav />

      <div class="jour">
        <button type="button" class="bouton secondaire petit" aria-label="Jour précédent" (click)="changerJour(veille(date()))">‹</button>
        <label for="date" class="visuellement-cache">Jour</label>
        <input id="date" type="date" [max]="aujourdhui" [ngModel]="date()" (ngModelChange)="changerJour($event)" />
        <button
          type="button"
          class="bouton secondaire petit"
          aria-label="Jour suivant"
          [disabled]="date() >= aujourdhui"
          (click)="changerJour(lendemain(date()))"
        >›</button>
        <strong class="libelle-jour">{{ date() === aujourdhui ? "Aujourd'hui" : dateLongue(date()) }}</strong>
      </div>

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      @if (charge()) {
        <div class="bilan">
          <span class="pastille absent">{{ bilan().absents }} élève(s) absent(s)</span>
          <span class="pastille">{{ bilan().retards }} en retard</span>
          <span class="pastille" [class.absent]="bilan().aJustifier > 0" [class.present]="bilan().aJustifier === 0">
            {{ bilan().aJustifier }} absence(s) à justifier
          </span>
        </div>

        @if (liste().length > 0) {
          <div class="filtres">
            <app-recherche
              libelle="Élève, classe ou matière"
              [(valeur)]="filtre"
              [total]="liste().length"
              [trouves]="affiches().length"
            />
            <label class="case">
              <input type="checkbox" [ngModel]="aJustifierSeulement()" (ngModelChange)="aJustifierSeulement.set($event)" />
              À justifier seulement
            </label>
          </div>
        }

        @for (g of parClasse(); track g.classe) {
          <section class="carte">
            <h2>{{ g.classe }} <span class="doux">· {{ g.eleves.length }}</span></h2>
            <ul class="liste">
              @for (e of g.eleves; track e.inscriptionId) {
                <li class="eleve" [class.justifiee]="e.justifiee">
                  <div class="ligne">
                    <div class="qui">
                      <strong>{{ e.nom }}</strong> {{ e.prenoms }}
                      @if (e.matricule) { <span class="doux"> · {{ e.matricule }}</span> }
                      <div class="creneaux doux">
                        @for (c of e.creneaux; track c.appelId) {
                          <span>{{ creneau(c) }}</span>
                        }
                      </div>
                    </div>
                    <div class="etat">
                      @if (e.absences > 0) {
                        @if (e.justifiee) {
                          <span class="pastille present">justifiée</span>
                        } @else {
                          <span class="pastille absent">non justifiée</span>
                        }
                      }
                      <div class="actions-ligne">
                        @if (e.absences > 0 && !e.justifiee && peutJustifier() && ouvert() !== e.inscriptionId) {
                          <button type="button" class="bouton petit" (click)="ouvert.set(e.inscriptionId)">Justifier</button>
                        }
                        @if (e.eleveId) {
                          <a class="bouton discret petit" [routerLink]="['/vie-scolaire/eleves', e.eleveId]">Fiche</a>
                        }
                      </div>
                    </div>
                  </div>
                  @if (ouvert() === e.inscriptionId) {
                    <app-justification
                      [inscriptionId]="e.inscriptionId"
                      [eleve]="e.prenoms + ' ' + e.nom"
                      [jour]="date()"
                      (justifie)="justifiee(e)"
                      (annule)="ouvert.set(null)"
                    />
                  }
                </li>
              }
            </ul>
          </section>
        } @empty {
          <div class="carte">
            @if (liste().length === 0) {
              <p>Aucune absence ni aucun retard relevé {{ date() === aujourdhui ? "aujourd'hui" : 'ce jour-là' }}.</p>
              <p class="doux">La liste se remplit à mesure que les enseignants envoient leurs appels.</p>
            } @else {
              <p class="doux">Aucun élève ne correspond.</p>
            }
          </div>
        }
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .jour {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 1rem;
      input {
        width: auto;
      }
    }
    .libelle-jour {
      margin-left: 0.25rem;
    }
    .bilan {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      margin-bottom: 1rem;
    }
    .filtres {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: 0 1rem;
      app-recherche {
        flex: 1 1 16rem;
        min-width: 0;
      }
    }
    .case {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      min-height: 44px;
      input {
        width: auto;
        min-height: 0;
      }
    }
    h2 {
      font-size: 1.05rem;
      margin-bottom: 0.25rem;
    }
    .eleve {
      padding: 0.6rem 0;
    }
    .ligne {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 0.5rem;
    }
    .qui {
      min-width: 0;
      flex: 1 1 14rem;
    }
    .creneaux {
      display: flex;
      flex-direction: column;
      font-size: 0.9rem;
      font-variant-numeric: tabular-nums;
    }
    .etat {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
    }
    .justifiee .qui strong {
      font-weight: 600;
    }
  `,
})
export class AbsencesJourPage implements OnInit {
  private readonly api = inject(VieScolaireApi);
  private readonly session = inject(SessionService);

  protected readonly aujourdhui = dateLocale();
  protected readonly dateLongue = dateLongue;
  protected readonly lendemain = lendemain;
  protected readonly veille = veille;
  protected readonly action = new Action();

  protected readonly date = signal(dateLocale());
  protected readonly liste = signal<EleveDuJourVue[]>([]);
  protected readonly charge = signal(false);
  protected readonly filtre = signal('');
  protected readonly aJustifierSeulement = signal(false);
  protected readonly ouvert = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly peutJustifier = computed(() =>
    this.session.roles().some((r) => ['SURVEILLANT', 'CENSEUR', 'ADMIN_ECOLE', 'SECRETARIAT'].includes(r)),
  );

  protected readonly affiches = computed(() =>
    filtrer(
      this.liste().filter((e) => !this.aJustifierSeulement() || (e.absences > 0 && !e.justifiee)),
      this.filtre(),
      (e) => [e.nom, e.prenoms, e.matricule, e.classeCode, ...e.creneaux.map((c) => c.matiereLibelle)],
    ),
  );

  protected readonly parClasse = computed(() => {
    const groupes = new Map<string, EleveDuJourVue[]>();
    for (const e of this.affiches()) {
      const c = e.classeCode ?? '—';
      groupes.set(c, [...(groupes.get(c) ?? []), e]);
    }
    return [...groupes.entries()].map(([classe, eleves]) => ({ classe, eleves }));
  });

  protected readonly bilan = computed(() => {
    const l = this.liste();
    return {
      absents: l.filter((e) => e.absences > 0).length,
      retards: l.filter((e) => e.retards > 0).length,
      aJustifier: l.filter((e) => e.absences > 0 && !e.justifiee).length,
    };
  });

  async ngOnInit(): Promise<void> {
    await this.charger();
  }

  /** « absent 08h00–10h00 · Mathématiques », « retard 10 min 10h00–12h00 · Français ». */
  protected creneau(c: CreneauVue): string {
    const quoi = c.type === 'ABSENCE' ? 'absent' : `retard ${c.minutesRetard ?? '?'} min`;
    return `${quoi} ${heure(c.heureDebut)}–${heure(c.heureFin)} · ${discipline(c)}`;
  }

  protected async changerJour(jour: string): Promise<void> {
    if (!jour || jour > this.aujourdhui) {
      return;
    }
    this.date.set(jour);
    this.ouvert.set(null);
    this.message.set(null);
    await this.charger();
  }

  private async charger(): Promise<void> {
    const jour = this.date();
    const l = await this.action.executer(() => this.api.absencesDuJour(jour));
    if (l && jour === this.date()) {
      this.liste.set(l);
      this.charge.set(true);
    }
  }

  protected async justifiee(e: EleveDuJourVue): Promise<void> {
    this.ouvert.set(null);
    this.message.set(`Absences de ${e.prenoms} ${e.nom} justifiées.`);
    await this.charger();
  }
}
