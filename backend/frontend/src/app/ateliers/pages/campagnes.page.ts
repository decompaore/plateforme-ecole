import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte, dateLocale } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { fcfa } from '../../scolarite/modeles-scolarite';
import { AteliersNavComponent } from '../ateliers-nav.component';
import { AteliersApi } from '../ateliers-api.service';
import { DIRECTION_ATELIERS } from '../modeles-ateliers';
import {
  CampagneResumeVue,
  LIBELLE_STATUT_CAMPAGNE,
  LIBELLE_TYPE_CAMPAGNE,
  StatutCampagne,
  TypeCampagne,
} from '../modeles-besoins';

const CLASSE_STATUT: Record<StatutCampagne, string> = { OUVERTE: 'absent', TRANSMISE: 'present', CLOSE: '' };

/**
 * Campagnes de besoins (deux par an : année en cours, examens de fin d'études). Le chef des
 * travaux les ouvre ; chaque atelier y exprime ses besoins ; la direction les arbitre puis les
 * transmet à la direction régionale ; les commandes et livraisons s'y rattachent.
 */
@Component({
  selector: 'app-campagnes',
  imports: [FormsModule, RouterLink, AteliersNavComponent],
  template: `
    <div class="page large">
      <h1>Besoins et commandes</h1>
      <app-ateliers-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (charge()) {
        @for (c of campagnes(); track c.id) {
          <a class="carte campagne" [routerLink]="['/ateliers/besoins', c.id]">
            <span class="corps">
              <strong>{{ c.libelle }}</strong>
              <span class="pastille {{ classeStatut[c.statut] }}">{{ libelleStatut[c.statut] }}</span>
              <br />
              <span class="doux">
                {{ libelleType[c.type] }}
                @if (c.dateLimite && c.statut === 'OUVERTE') { · à remettre avant le {{ dateCourte(c.dateLimite) }} }
              </span>
              <br />
              <span class="doux">
                {{ c.transmis }} / {{ c.ateliers }} atelier(s) ont transmis · {{ c.valides }} validé(s)
                @if (c.montant) { · {{ fcfa(c.montant) }} retenus }
                @if (c.commandes) { · {{ c.commandes }} commande(s) }
              </span>
            </span>
          </a>
        } @empty {
          <div class="carte">
            <p class="doux">
              Aucune campagne. Le chef des travaux ouvre une campagne pour l'année scolaire en cours et une pour
              les examens de fin d'études ; chaque atelier ouvert y reçoit sa fiche de besoins.
            </p>
          </div>
        }

        @if (direction()) {
          <section class="carte">
            <div class="entete-section">
              <h2>Nouvelle campagne</h2>
              @if (!ouvert()) {
                <button type="button" class="bouton petit" (click)="ouvert.set(true)">Ouvrir une campagne</button>
              }
            </div>
            @if (ouvert()) {
              <form (ngSubmit)="creer()">
                <div class="grille-champs">
                  <div class="champ">
                    <label for="cp-type">Campagne</label>
                    <select id="cp-type" name="type" [ngModel]="type()" (ngModelChange)="type.set($event)">
                      @for (t of types; track t) { <option [value]="t">{{ libelleType[t] }}</option> }
                    </select>
                  </div>
                  <div class="champ">
                    <label for="cp-libelle">Libellé <span class="doux">(facultatif)</span></label>
                    <input id="cp-libelle" name="libelle" placeholder="Besoins 2026-2027" [(ngModel)]="libelle" />
                  </div>
                  <div class="champ">
                    <label for="cp-limite">Date limite de remise <span class="doux">(facultatif)</span></label>
                    <input id="cp-limite" name="limite" type="date" [min]="aujourdhui" [(ngModel)]="dateLimite" />
                  </div>
                </div>
                <div class="champ">
                  <label for="cp-obs">Consignes aux ateliers <span class="doux">(facultatif)</span></label>
                  <textarea id="cp-obs" name="obs" rows="2" [(ngModel)]="observations"></textarea>
                </div>
                <p class="doux">La campagne porte sur l'année scolaire active. Une fiche de besoins est créée pour chaque atelier ouvert.</p>
                @if (enregistrement.erreur()) { <div class="alerte erreur" role="alert">{{ enregistrement.erreur() }}</div> }
                <div class="actions-ligne">
                  <button type="submit" class="bouton" [disabled]="enregistrement.enCours()">Ouvrir la campagne</button>
                  <button type="button" class="bouton discret" (click)="ouvert.set(false)">Fermer</button>
                </div>
              </form>
            }
          </section>
        }
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .campagne {
      display: block;
      text-decoration: none;
      color: inherit;
    }
    .pastille {
      margin-left: 0.5rem;
    }
    h2 {
      font-size: 1.05rem;
      margin: 0;
    }
  `,
})
export class CampagnesPage {
  private readonly api = inject(AteliersApi);
  private readonly router = inject(Router);
  private readonly session = inject(SessionService);

  protected readonly dateCourte = dateCourte;
  protected readonly fcfa = fcfa;
  protected readonly libelleType = LIBELLE_TYPE_CAMPAGNE;
  protected readonly libelleStatut = LIBELLE_STATUT_CAMPAGNE;
  protected readonly classeStatut = CLASSE_STATUT;
  protected readonly types: TypeCampagne[] = ['ANNEE_EN_COURS', 'EXAMENS'];
  protected readonly aujourdhui = dateLocale();
  protected readonly action = new Action();
  protected readonly enregistrement = new Action();

  protected readonly campagnes = signal<CampagneResumeVue[]>([]);
  protected readonly charge = signal(false);
  protected readonly direction = computed(() => this.session.aLeRole(...DIRECTION_ATELIERS));

  protected readonly ouvert = signal(false);
  protected readonly type = signal<TypeCampagne>('ANNEE_EN_COURS');
  protected readonly libelle = signal('');
  protected readonly dateLimite = signal('');
  protected readonly observations = signal('');

  constructor() {
    void this.charger();
  }

  protected async creer(): Promise<void> {
    const c = await this.enregistrement.executer(() =>
      this.api.ouvrirCampagne({
        anneeId: null,
        type: this.type(),
        libelle: this.libelle().trim() || null,
        dateLimite: this.dateLimite() || null,
        observations: this.observations().trim() || null,
      }),
    );
    if (c) {
      void this.router.navigate(['/ateliers/besoins', c.id]);
    }
  }

  private async charger(): Promise<void> {
    const l = await this.action.executer(() => this.api.campagnes());
    if (l) {
      this.campagnes.set(l);
      this.charge.set(true);
    }
  }
}
