import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { dateCourte, dateLocale } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { CommissionResume, LIBELLE_STATUT_COMMISSION } from '../modeles-recrutement';
import { RecrutementApi } from '../recrutement-api.service';

/** Commissions de recrutement de l'année (v0.49) ; le chef d'établissement en crée une. */
@Component({
  selector: 'app-commissions-recrutement',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page large">
      <h1>Commission de recrutement</h1>
      <p class="doux">
        Complément d'effectif : places disponibles par niveau, candidatures, classement au mérite, délibération,
        procès-verbal, puis inscription des retenus par l'intendance.
      </p>
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (president && annee.annee() && !form()) {
        <button type="button" class="bouton petit" (click)="nouvelle()">Nouvelle commission</button>
      }
      @if (form()) {
        <form class="carte sous-formulaire" (ngSubmit)="creer()">
          <h2>Nouvelle commission — {{ annee.annee()?.libelle }}</h2>
          <div class="grille-champs">
            <div class="champ"><label for="cIntitule">Intitulé</label><input id="cIntitule" name="cIntitule" maxlength="200" required [(ngModel)]="intitule" /></div>
            <div class="champ"><label for="cDate">Date de la session</label><input id="cDate" name="cDate" type="date" required [(ngModel)]="dateSession" /></div>
            <div class="champ"><label for="cLieu">Lieu</label><input id="cLieu" name="cLieu" maxlength="120" [(ngModel)]="lieu" /></div>
            <div class="champ"><label for="cLimite">Date limite de dépôt</label><input id="cLimite" name="cLimite" type="date" [(ngModel)]="dateLimite" /></div>
            <div class="champ"><label for="cMin">Moyenne minimale (/20)</label><input id="cMin" name="cMin" type="number" min="0" max="20" step="0.25" [(ngModel)]="moyenneMinimale" /></div>
            <div class="champ"><label for="cAttente">Liste d'attente (par niveau)</label><input id="cAttente" name="cAttente" type="number" min="0" max="100" [(ngModel)]="listeAttente" /></div>
          </div>
          <p class="doux petit-texte">Les membres sont proposés d'après le personnel (président, vice-président, rapporteur, intendant, représentant de l'APE) et les niveaux d'après les classes de l'année : vous les ajusterez ensuite.</p>
          <div class="actions-ligne">
            <button type="submit" class="bouton" [disabled]="action.enCours()">Créer la commission</button>
            <button type="button" class="bouton secondaire" (click)="form.set(false)">Annuler</button>
          </div>
        </form>
      }
      <section class="carte">
        <ul class="liste">
          @for (c of commissions(); track c.id) {
            <li>
              <a class="ligne-lien" [routerLink]="['/recrutement', c.id]">
                <span>
                  <strong>{{ c.numero }}</strong> · {{ c.intitule }}<br />
                  <span class="doux petit-texte">Session du {{ dateCourte(c.dateSession) }} · {{ c.places }} place(s) · {{ c.candidatures }} candidature(s) · {{ c.retenues }} retenu(s) · {{ c.inscrites }} inscrit(s)</span>
                </span>
                <span class="pastille {{ c.statut === 'PREPARATION' ? '' : 'present' }}">{{ libelleStatut[c.statut] }}</span>
              </a>
            </li>
          } @empty {
            <li class="doux">{{ charge() ? 'Aucune commission pour cette année.' : 'Chargement…' }}</li>
          }
        </ul>
      </section>
    </div>
  `,
  styles: `
    .petit-texte {
      font-size: 0.85rem;
    }
  `,
})
export class CommissionsPage {
  private readonly api = inject(RecrutementApi);
  private readonly router = inject(Router);
  protected readonly annee = inject(AnneeCourante);
  private readonly session = inject(SessionService);

  protected readonly president = this.session.aLeRole('ADMIN_ECOLE');
  protected readonly action = new Action();
  protected readonly commissions = signal<CommissionResume[]>([]);
  protected readonly charge = signal(false);
  protected readonly form = signal(false);
  protected readonly libelleStatut = LIBELLE_STATUT_COMMISSION;
  protected readonly dateCourte = dateCourte;
  protected readonly anneeId = computed(() => this.annee.annee()?.id ?? null);

  protected intitule = '';
  protected dateSession = dateLocale();
  protected lieu = '';
  protected dateLimite = '';
  protected moyenneMinimale = 10;
  protected listeAttente = 5;

  constructor() {
    effect(() => {
      const id = this.anneeId();
      untracked(() => {
        if (id) {
          void this.charger(id);
        }
      });
    });
    void this.annee.charger().catch(() => undefined);
  }

  private async charger(anneeId: string): Promise<void> {
    const l = await this.action.executer(() => this.api.commissions(anneeId));
    if (l) {
      this.commissions.set(l);
    }
    this.charge.set(true);
  }

  protected nouvelle(): void {
    this.intitule = `Complément d'effectif ${this.annee.annee()?.libelle ?? ''}`.trim();
    this.form.set(true);
  }

  protected async creer(): Promise<void> {
    const anneeId = this.anneeId();
    if (!anneeId) {
      return;
    }
    const c = await this.action.executer(() =>
      this.api.creer(anneeId, {
        intitule: this.intitule, dateSession: this.dateSession, lieu: this.lieu.trim() || null,
        dateLimiteDepot: this.dateLimite || null, criteres: null, moyenneMinimale: Number(this.moyenneMinimale),
        poidsExamen: 0, tailleListeAttente: Number(this.listeAttente),
      }),
    );
    if (c) {
      await this.router.navigate(['/recrutement', c.id]);
    }
  }
}
