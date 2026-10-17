import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateHeureCourte } from '../../core/outils';
import { CLASSE_RECLAMATION, LIBELLE_RECLAMATION, note, ReclamationVue, StatutReclamation } from '../modeles-pp';
import { ProfesseurPrincipalApi } from '../pp-api.service';

/**
 * Réclamations de notes adressées à l'enseignant (v0.49.1), transmises par le professeur principal : corriger la note
 * dans la saisie des notes puis répondre « note corrigée », ou la maintenir en justifiant.
 */
@Component({
  selector: 'app-reclamations-notes',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page large">
      <p><a routerLink="/professeur-principal">‹ Professeur principal</a></p>
      <h1>Réclamations de notes</h1>
      <p class="doux">
        Si la réclamation est fondée, corrigez d'abord la note dans « Saisie des notes » (la période doit être ouverte),
        puis répondez « Note corrigée ». Sinon, maintenez la note en expliquant pourquoi.
      </p>
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }
      @for (x of liste(); track x.id) {
        <section class="carte">
          <div class="entete-section">
            <h2>{{ x.eleve }} · {{ x.matiere }} · {{ x.classeCode }}</h2>
            <span class="pastille {{ classeReclamation[x.statut] }}">{{ libelleReclamation[x.statut] }}</span>
          </div>
          <p class="doux">
            {{ x.periode }}@if (x.evaluation) { · {{ x.evaluation }} } · note contestée {{ note(x.noteContestee) }}
            @if (x.noteCorrigee !== null) { → {{ note(x.noteCorrigee) }} } · {{ dateHeure(x.enregistreeLe) }}, {{ x.enregistreePar }}
          </p>
          <p><strong>Motif :</strong> {{ x.motif }}</p>
          @if (x.reponse) { <p><strong>Réponse :</strong> {{ x.reponse }}</p> }
          @if (x.peutRepondre) {
            <div class="champ"><label [for]="'rep' + x.id">Votre réponse</label><input [id]="'rep' + x.id" [name]="'rep' + x.id" maxlength="1000" [(ngModel)]="reponses[x.id]" /></div>
            <div class="actions-ligne">
              <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="repondre(x, 'CORRIGEE')">Note corrigée</button>
              <button type="button" class="bouton secondaire petit" [disabled]="action.enCours()" (click)="repondre(x, 'MAINTENUE')">Note maintenue</button>
            </div>
          }
        </section>
      } @empty {
        <p class="doux">{{ charge() ? 'Aucune réclamation.' : 'Chargement…' }}</p>
      }
    </div>
  `,
})
export class ReclamationsPage {
  private readonly api = inject(ProfesseurPrincipalApi);

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly liste = signal<ReclamationVue[]>([]);
  protected readonly charge = signal(false);
  protected reponses: Record<string, string> = {};
  protected readonly note = note;
  protected readonly dateHeure = dateHeureCourte;
  protected readonly libelleReclamation = LIBELLE_RECLAMATION;
  protected readonly classeReclamation = CLASSE_RECLAMATION;

  constructor() {
    void this.charger();
  }

  private async charger(): Promise<void> {
    const l = await this.action.executer(() => this.api.mesReclamations());
    if (l) {
      this.liste.set(l);
    }
    this.charge.set(true);
  }

  protected async repondre(x: ReclamationVue, decision: StatutReclamation): Promise<void> {
    const texte = (this.reponses[x.id] ?? '').trim();
    const r = await this.action.executer(() => this.api.repondre(x.id, decision, texte || null));
    if (r) {
      this.liste.update((l) => l.map((y) => (y.id === r.id ? r : y)));
      this.message.set(`Réponse envoyée au professeur principal de la ${r.classeCode}.`);
    }
  }
}
