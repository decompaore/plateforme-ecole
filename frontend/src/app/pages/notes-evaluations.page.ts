import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { messageErreur } from '../core/erreurs';
import { Affectation } from '../core/modeles';
import { dateLocale, dateLongue } from '../core/outils';
import { ListesService, PeriodeLocale } from '../hors-ligne/listes.service';
import { EvaluationServeur, LIBELLE_TYPE_EVALUATION, NotesService, TypeEvaluation } from '../hors-ligne/notes.service';
import { periodeParDefaut } from '../hors-ligne/periodes';

/** Évaluations d'une classe et d'une matière, par période ; création d'une évaluation (aussi sans réseau). */
@Component({
  selector: 'app-notes-evaluations',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page">
      <a routerLink="/notes" class="bouton discret retour">‹ Classes</a>
      <h1>{{ affectation()?.classeCode }} · {{ affectation()?.matiereLibelle }}</h1>
      @if (erreur()) {
        <div class="alerte erreur" role="alert">{{ erreur() }}</div>
      }

      @if (periodes().length > 0) {
        <div class="champ">
          <label for="periode">Période</label>
          <select id="periode" name="periode" [ngModel]="periodeId()" (ngModelChange)="changerPeriode($event)">
            @for (p of periodes(); track p.id) {
              <option [value]="p.id">{{ p.libelle }}@if (p.verrouillee) { (verrouillée) }</option>
            }
          </select>
        </div>
      } @else if (!chargement()) {
        <div class="alerte attention">Aucune période sur ce téléphone pour cette classe. Mettez à jour les listes avec du réseau.</div>
      }

      @if (periode()?.verrouillee) {
        <div class="alerte attention">Cette période est verrouillée : les notes ne se modifient plus.</div>
      }

      <ul class="liste carte">
        @for (e of evaluations(); track e.id) {
          <li>
            <a class="ligne-lien" [routerLink]="['/notes', classeId(), matiereId(), e.id]">
              <span>
                <strong>{{ e.libelle }}</strong>
                <span class="doux"> · {{ types[e.type] }} sur {{ e.bareme }}@if (e.poids != 1) { · poids {{ e.poids }} }</span><br />
                <span class="doux">{{ dateLongue(e.date) }}</span>
              </span>
              <span class="etat">
                @if (enAttente(e.id)) {
                  <span class="pastille">en attente</span>
                } @else if (refusee(e.id)) {
                  <span class="pastille absent">refusée</span>
                } @else if (e.effectif > 0) {
                  <span class="pastille" [class.present]="e.notesSaisies >= e.effectif">{{ e.notesSaisies }} / {{ e.effectif }}</span>
                }
                <span aria-hidden="true">›</span>
              </span>
            </a>
          </li>
        } @empty {
          <li class="doux vide">Aucune évaluation sur cette période.</li>
        }
      </ul>

      @if (!periode()?.verrouillee && periode()) {
        @if (!formulaire()) {
          <button type="button" class="bouton plein" (click)="ouvrirFormulaire()">Nouvelle évaluation</button>
        } @else {
          <form class="carte" (ngSubmit)="creer()">
            <h2>Nouvelle évaluation</h2>
            <div class="champ">
              <label for="libelle">Intitulé</label>
              <input id="libelle" name="libelle" required maxlength="80" [(ngModel)]="libelle" />
            </div>
            <div class="deux">
              <div class="champ">
                <label for="type">Type</label>
                <select id="type" name="type" [(ngModel)]="type" (ngModelChange)="typeChange($event)">
                  @for (t of listeTypes; track t) { <option [value]="t">{{ types[t] }}</option> }
                </select>
              </div>
              <div class="champ">
                <label for="date">Date</label>
                <input id="date" name="date" type="date" required [min]="periode()!.debut" [max]="periode()!.fin" [(ngModel)]="date" />
              </div>
              <div class="champ">
                <label for="bareme">Noté sur</label>
                <input id="bareme" name="bareme" type="number" min="1" max="100" step="1" required [(ngModel)]="bareme" />
              </div>
              <div class="champ">
                <label for="poids">Poids</label>
                <input id="poids" name="poids" type="number" min="0.5" max="10" step="0.5" required [(ngModel)]="poids" />
              </div>
            </div>
            @if (dateHorsPeriode()) {
              <p class="alerte erreur">La date doit être comprise dans la période ({{ dateLongue(periode()!.debut) }} – {{ dateLongue(periode()!.fin) }}).</p>
            }
            @if (parametresInvalides()) {
              <p class="alerte erreur">Noté sur 1 à 100, poids de 0,5 à 10.</p>
            }
            <div class="boutons">
              <button type="button" class="bouton secondaire" (click)="formulaire.set(false)">Annuler</button>
              <button type="submit" class="bouton" [disabled]="!libelle().trim() || dateHorsPeriode() || parametresInvalides()">Créer et saisir les notes</button>
            </div>
            <p class="doux">Sans réseau, l'évaluation est créée sur le téléphone et envoyée plus tard avec ses notes.</p>
          </form>
        }
      }
    </div>
  `,
  styles: `
    .retour {
      margin-left: -0.5rem;
    }
    .etat {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .vide {
      padding: 1rem 0;
    }
    .deux {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0 0.75rem;
    }
    .boutons {
      display: grid;
      grid-template-columns: 1fr 2fr;
      gap: 0.75rem;
    }
  `,
})
export class NotesEvaluationsPage implements OnInit {
  readonly classeId = input.required<string>();
  readonly matiereId = input.required<string>();

  private readonly listes = inject(ListesService);
  private readonly notes = inject(NotesService);
  private readonly router = inject(Router);

  protected readonly types = LIBELLE_TYPE_EVALUATION;
  protected readonly listeTypes = Object.keys(LIBELLE_TYPE_EVALUATION) as TypeEvaluation[];
  protected readonly dateLongue = dateLongue;

  protected readonly affectation = signal<Affectation | null>(null);
  protected readonly periodes = signal<PeriodeLocale[]>([]);
  protected readonly periodeId = signal<string>('');
  protected readonly evaluations = signal<EvaluationServeur[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly periode = computed(() => this.periodes().find((p) => p.id === this.periodeId()));

  protected readonly formulaire = signal(false);
  protected readonly libelle = signal('');
  protected readonly type = signal<TypeEvaluation>('DEVOIR');
  protected readonly date = signal(dateLocale());
  protected readonly bareme = signal(20);
  protected readonly poids = signal(1);
  /** Barème de 1 à 100 et poids de 0,5 à 10, deux décimales au plus (colonnes NUMERIC du serveur). */
  protected readonly parametresInvalides = computed(() => {
    const b = Number(this.bareme());
    const p = Number(this.poids());
    const deuxDecimales = (x: number) => Math.abs(Math.round(x * 100) - x * 100) < 1e-9;
    return !(b >= 1 && b <= 100 && deuxDecimales(b) && p >= 0.5 && p <= 10 && deuxDecimales(p));
  });
  protected readonly dateHorsPeriode = computed(() => {
    const p = this.periode();
    return !!p && (!this.date() || this.date() < p.debut || this.date() > p.fin);
  });

  protected enAttente(id: string): boolean {
    return this.notes.operations().some((o) => o.evaluationId === id && o.etat === 'EN_ATTENTE');
  }

  protected refusee(id: string): boolean {
    return this.notes.operations().some((o) => o.evaluationId === id && o.etat === 'REFUSE');
  }

  async ngOnInit(): Promise<void> {
    try {
      const affectations = await this.listes.affectations();
      const a = affectations?.affectations.find((x) => x.classeId === this.classeId() && x.matiereId === this.matiereId());
      if (!a || !affectations) {
        this.erreur.set("Cette classe et cette matière ne font pas partie de vos affectations sur ce téléphone.");
        return;
      }
      this.affectation.set(a);
      const [classe, periodes] = await Promise.all([this.listes.eleves(a.classeId), this.listes.periodes(affectations.anneeId)]);
      const duProfil = (periodes?.periodes ?? [])
        .filter((p) => !classe?.profilId || p.profilId === classe.profilId)
        .sort((x, y) => x.ordre - y.ordre);
      this.periodes.set(duProfil);
      const choisie = periodeParDefaut(duProfil, classe?.profilId, dateLocale());
      if (choisie) {
        await this.changerPeriode(choisie.id);
      }
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  protected async changerPeriode(id: string): Promise<void> {
    this.periodeId.set(id);
    this.formulaire.set(false);
    try {
      this.evaluations.set(await this.notes.evaluations(this.classeId(), this.matiereId(), id));
    } catch (e) {
      this.erreur.set(messageErreur(e));
    }
  }

  protected ouvrirFormulaire(): void {
    const p = this.periode();
    const aujourdhui = dateLocale();
    this.date.set(p && (aujourdhui < p.debut || aujourdhui > p.fin) ? p.debut : aujourdhui);
    const n = this.evaluations().filter((e) => e.type === this.type()).length + 1;
    this.libelle.set(`${LIBELLE_TYPE_EVALUATION[this.type()]} ${n}`);
    this.formulaire.set(true);
  }

  protected typeChange(t: TypeEvaluation): void {
    const n = this.evaluations().filter((e) => e.type === t).length + 1;
    this.libelle.set(`${LIBELLE_TYPE_EVALUATION[t]} ${n}`);
    if (t === 'COMPOSITION') {
      this.poids.set(2);
    }
  }

  protected async creer(): Promise<void> {
    const a = this.affectation();
    if (!a || this.dateHorsPeriode() || this.parametresInvalides() || !this.libelle().trim()) {
      return;
    }
    try {
      const id = await this.notes.creer(
        {
          classeId: a.classeId,
          matiereId: a.matiereId,
          periodeId: this.periodeId(),
          libelle: this.libelle().trim(),
          type: this.type(),
          date: this.date(),
          bareme: Number(this.bareme()),
          poids: Number(this.poids()),
        },
        { classeCode: a.classeCode, matiereLibelle: a.matiereLibelle },
      );
      await this.router.navigate(['/notes', a.classeId, a.matiereId, id]);
    } catch (e) {
      this.erreur.set(messageErreur(e, "L'évaluation n'a pas pu être créée sur le téléphone."));
    }
  }
}
