import { Component, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../admin/action';
import { JustificatifVue, LIBELLE_JUSTIFICATIF, TypeJustificatif } from './modeles-vs';
import { VieScolaireApi } from './vie-scolaire-api.service';

/**
 * Justification des absences d'un élève sur une période (certificat médical, mot des
 * parents…). Toutes les absences couvertes deviennent justifiées, y compris celles déjà
 * enregistrées.
 */
@Component({
  selector: 'app-justification',
  imports: [FormsModule],
  template: `
    <form class="justification" (ngSubmit)="enregistrer()" [attr.aria-label]="'Justifier les absences de ' + eleve()">
      <div class="grille-champs">
        <div class="champ">
          <label [for]="id + '-type'">Motif</label>
          <select [id]="id + '-type'" name="type" [(ngModel)]="type">
            @for (t of types; track t) { <option [value]="t">{{ libelles[t] }}</option> }
          </select>
        </div>
        <div class="champ">
          <label [for]="id + '-du'">Du</label>
          <input [id]="id + '-du'" name="du" type="date" required [(ngModel)]="du" />
        </div>
        <div class="champ">
          <label [for]="id + '-au'">Au</label>
          <input [id]="id + '-au'" name="au" type="date" required [min]="du()" [(ngModel)]="au" />
        </div>
        <div class="champ">
          <label [for]="id + '-motif'">Précision (facultatif)</label>
          <input [id]="id + '-motif'" name="motif" maxlength="200" placeholder="Certificat du CSPS" [(ngModel)]="motif" />
        </div>
      </div>
      @if (action.erreur()) {
        <p class="alerte erreur" role="alert">{{ action.erreur() }}</p>
      }
      <div class="actions-ligne">
        <button type="submit" class="bouton petit" [disabled]="action.enCours() || !du() || !au() || au() < du()">Justifier</button>
        <button type="button" class="bouton secondaire petit" (click)="annule.emit()">Annuler</button>
      </div>
    </form>
  `,
  styles: `
    .justification {
      margin-top: 0.75rem;
      padding-top: 0.75rem;
      border-top: 1px solid var(--bordure);
    }
  `,
})
export class JustificationComponent implements OnInit {
  private readonly api = inject(VieScolaireApi);

  readonly inscriptionId = input.required<string>();
  readonly eleve = input('');
  /** Jour proposé par défaut (du et au). */
  readonly jour = input.required<string>();
  readonly justifie = output<JustificatifVue>();
  readonly annule = output<void>();

  protected readonly types = Object.keys(LIBELLE_JUSTIFICATIF) as TypeJustificatif[];
  protected readonly libelles = LIBELLE_JUSTIFICATIF;
  protected readonly action = new Action();
  protected readonly id = `j-${Math.random().toString(36).slice(2, 8)}`;
  protected readonly type = signal<TypeJustificatif>('MALADIE');
  protected readonly du = signal('');
  protected readonly au = signal('');
  protected readonly motif = signal('');

  ngOnInit(): void {
    this.du.set(this.jour());
    this.au.set(this.jour());
  }

  protected async enregistrer(): Promise<void> {
    const r = await this.action.executer(() =>
      this.api.justifier(this.inscriptionId(), {
        du: this.du(),
        au: this.au(),
        type: this.type(),
        motif: this.motif().trim() || null,
      }),
    );
    if (r) {
      this.justifie.emit(r);
    }
  }
}
