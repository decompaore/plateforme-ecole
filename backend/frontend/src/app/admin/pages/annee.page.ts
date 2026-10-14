import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { SessionService } from '../../core/session.service';
import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { AdminNavComponent } from '../admin-nav.component';
import { AnneeCourante } from '../annee-courante.service';
import { ClasseVue, LIBELLE_ETAT_ANNEE, PeriodeVue, ProfilVue } from '../modeles-admin';

/** Libellé et dates proposés pour une nouvelle année : rentrée en octobre, fin en juillet. */
function anneeSuggeree(): { libelle: string; debut: string; fin: string } {
  const maintenant = new Date();
  const an = maintenant.getMonth() >= 6 ? maintenant.getFullYear() : maintenant.getFullYear() - 1;
  return { libelle: `${an}-${an + 1}`, debut: `${an}-10-01`, fin: `${an + 1}-07-31` };
}

/** Années scolaires : création, trimestres (ou semestres) et ouverture. */
@Component({
  selector: 'app-annee',
  imports: [FormsModule, DatePipe, AdminNavComponent],
  template: `
    <div class="page large">
      <h1>Année scolaire</h1>
      <app-admin-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (profils().length === 0 && charge()) {
        <section class="carte">
          <h2>Profils pédagogiques</h2>
          <p>
            Les profils décrivent comment l'établissement évalue : enseignement général (notes et coefficients),
            technique (notes par groupes de matières) ou professionnel (compétences par modules).
          </p>
          <button type="button" class="bouton" [disabled]="action.enCours()" (click)="initialiserProfils()">
            Créer les profils général, technique et professionnel
          </button>
        </section>
      }

      @if (annee(); as a) {
        <section class="carte">
          <div class="entete-section">
            <h2>{{ a.libelle }}</h2>
            <span class="pastille" [class.present]="a.etat === 'ACTIVE'">{{ etat[a.etat] }}</span>
          </div>
          <p class="doux">Du {{ a.debut | date: 'dd/MM/yyyy' }} au {{ a.fin | date: 'dd/MM/yyyy' }}</p>

          <h3>Périodes</h3>
          @if (profilsUtilises().length === 0) {
            <p class="doux">Créez d'abord les classes de l'année : les périodes dépendent du profil de leurs filières.</p>
          }
          @for (p of profilsUtilises(); track p.id) {
            <div class="profil">
              <strong>{{ p.libelle }}</strong>
              @if (periodesDe(p.id).length > 0) {
                <ul class="liste">
                  @for (per of periodesDe(p.id); track per.id) {
                    <li class="periode">
                      <span>{{ per.libelle }}</span>
                      <span class="doux">{{ per.debut | date: 'dd/MM' }} – {{ per.fin | date: 'dd/MM/yyyy' }}</span>
                      @if (per.verrouillee) { <span class="pastille">verrouillée</span> }
                    </li>
                  }
                </ul>
              } @else if (p.decoupage === 'MODULE') {
                <p class="doux">Organisé en modules : les périodes se saisissent une à une (par l'API pour l'instant).</p>
              } @else {
                <p>
                  <button type="button" class="bouton secondaire petit" [disabled]="action.enCours()" (click)="generer(p)">
                    Générer les périodes
                  </button>
                </p>
              }
            </div>
          }

          @if (a.etat === 'PREPARATION' && estAdmin()) {
            <h3>Ouverture</h3>
            <p class="doux">
              L'année ouverte devient l'année en cours : les enseignants peuvent faire l'appel et saisir les notes.
              Il faut au moins une classe et des périodes pour chaque profil utilisé.
            </p>
            <button type="button" class="bouton" [disabled]="action.enCours()" (click)="ouvrir()">
              Ouvrir l'année {{ a.libelle }}
            </button>
          }
        </section>
      }

      @if (estAdmin()) {
        <section class="carte">
          <h2>Nouvelle année</h2>
          <form (ngSubmit)="creer()">
            <div class="grille-champs">
              <div class="champ">
                <label for="libelle">Libellé</label>
                <input id="libelle" name="libelle" required pattern="\\d{4}-\\d{4}" [(ngModel)]="libelle" />
              </div>
              <div class="champ">
                <label for="debut">Début</label>
                <input id="debut" name="debut" type="date" required [(ngModel)]="debut" />
              </div>
              <div class="champ">
                <label for="fin">Fin</label>
                <input id="fin" name="fin" type="date" required [(ngModel)]="fin" />
              </div>
            </div>
            <button type="submit" class="bouton secondaire" [disabled]="action.enCours()">Créer l'année</button>
          </form>
        </section>
      }
    </div>
  `,
  styles: `
    h3 {
      font-size: 1rem;
      margin: 1.25rem 0 0.5rem;
    }
    .profil {
      margin-bottom: 0.75rem;
    }
    .periode {
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem;
      padding: 0.4rem 0;
    }
  `,
})
export class AnneePage {
  private readonly api = inject(AdminApi);
  private readonly session = inject(SessionService);
  private readonly anneeCourante = inject(AnneeCourante);

  protected readonly etat = LIBELLE_ETAT_ANNEE;
  protected readonly action = new Action();
  protected readonly annee = this.anneeCourante.annee;
  protected readonly profils = signal<ProfilVue[]>([]);
  protected readonly classes = signal<ClasseVue[]>([]);
  protected readonly periodes = signal<PeriodeVue[]>([]);
  protected readonly charge = signal(false);
  protected readonly estAdmin = computed(() => this.session.aLeRole('ADMIN_ECOLE'));

  protected readonly libelle = signal(anneeSuggeree().libelle);
  protected readonly debut = signal(anneeSuggeree().debut);
  protected readonly fin = signal(anneeSuggeree().fin);

  /** Profils des filières des classes de l'année : ce sont eux qui ont besoin de périodes. */
  protected readonly profilsUtilises = computed(() => {
    const ids = new Set(this.classes().map((c) => c.profilId));
    return this.profils().filter((p) => ids.has(p.id));
  });

  constructor() {
    void this.action.executer(async () => {
      this.profils.set(await this.api.profils());
      await this.anneeCourante.charger(true);
      this.charge.set(true);
    });
    effect(() => {
      const a = this.annee();
      untracked(() => {
        if (a) {
          void this.chargerAnnee(a.id);
        }
      });
    });
  }

  protected periodesDe(profilId: string): PeriodeVue[] {
    return this.periodes()
      .filter((p) => p.profilId === profilId)
      .sort((a, b) => a.ordre - b.ordre);
  }

  private async chargerAnnee(id: string): Promise<void> {
    await this.action.executer(async () => {
      const [classes, periodes] = await Promise.all([this.api.classes(id), this.api.periodes(id)]);
      this.classes.set(classes);
      this.periodes.set(periodes);
    });
  }

  protected async initialiserProfils(): Promise<void> {
    const profils = await this.action.executer(() => this.api.initialiserProfils());
    if (profils) {
      this.profils.set(profils);
    }
  }

  protected async creer(): Promise<void> {
    const a = await this.action.executer(() =>
      this.api.creerAnnee({ libelle: this.libelle().trim(), debut: this.debut(), fin: this.fin() }),
    );
    if (a) {
      await this.anneeCourante.charger(true);
      this.anneeCourante.choisir(a.id);
    }
  }

  protected async generer(p: ProfilVue): Promise<void> {
    const a = this.annee();
    if (a && (await this.action.executer(() => this.api.genererPeriodes(a.id, p.id)))) {
      await this.chargerAnnee(a.id);
    }
  }

  protected async ouvrir(): Promise<void> {
    const a = this.annee();
    if (!a || !window.confirm(`Ouvrir l'année ${a.libelle} ?`)) {
      return;
    }
    if (await this.action.executer(() => this.api.ouvrirAnnee(a.id))) {
      await this.anneeCourante.charger(true);
    }
  }
}
