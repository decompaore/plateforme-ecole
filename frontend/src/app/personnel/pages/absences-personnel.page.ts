import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { AbsenceVue, STATUTS_ABSENCE, TYPES_ABSENCE } from '../modeles-personnel';
import { PersonnelApi } from '../personnel-api.service';

/**
 * Congés et absences du personnel (v0.51) : demandes à décider par le chef d'établissement, agents absents
 * aujourd'hui, absences à venir.
 */
@Component({
  selector: 'app-absences-personnel',
  imports: [RouterLink],
  template: `
    <div class="page large">
      <a routerLink="/personnel" class="bouton discret">‹ Dossiers du personnel</a>
      <h1>Congés et absences du personnel</h1>

      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      <section class="carte">
        <h2>Demandes à décider ({{ demandes().length }})</h2>
        <ul class="liste">
          @for (b of demandes(); track b.id) {
            <li>
              <span><a [routerLink]="['/personnel', b.agentId]"><strong>{{ b.agent }}</strong></a> · {{ types[b.type] }}<br />
                du {{ dateCourte(b.debut) }} au {{ dateCourte(b.fin) }} ({{ b.jours }} j) · <span class="doux">{{ b.motif }}</span></span>
              @if (chef()) {
                <span class="actions-ligne">
                  <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="decider(b, true)">Accorder</button>
                  <button type="button" class="bouton discret petit" [disabled]="action.enCours()" (click)="decider(b, false)">Refuser</button>
                </span>
              }
            </li>
          } @empty { <li class="doux">Aucune demande en attente.</li> }
        </ul>
      </section>

      <section class="carte">
        <h2>Absents aujourd'hui ({{ aujourdhui().length }})</h2>
        <ul class="liste">
          @for (b of aujourdhui(); track b.id) {
            <li><span><a [routerLink]="['/personnel', b.agentId]"><strong>{{ b.agent }}</strong></a> · {{ types[b.type] }} jusqu'au {{ dateCourte(b.fin) }}
              · reprise le {{ dateCourte(b.reprise) }}</span>
              <span class="pastille" [class.absent]="b.statut === 'CONSTATEE'">{{ statuts[b.statut] }}</span></li>
          } @empty { <li class="doux">Personne.</li> }
        </ul>
      </section>

      <section class="carte">
        <h2>À venir et récentes</h2>
        <ul class="liste">
          @for (b of autres(); track b.id) {
            <li><span><a [routerLink]="['/personnel', b.agentId]"><strong>{{ b.agent }}</strong></a> · {{ types[b.type] }}
              du {{ dateCourte(b.debut) }} au {{ dateCourte(b.fin) }}</span>
              <span class="pastille">{{ statuts[b.statut] }}</span></li>
          } @empty { <li class="doux">Rien de prévu.</li> }
        </ul>
      </section>
    </div>
  `,
  styles: `
    h2 { font-size: 1.05rem; margin: 0 0 0.5rem; }
  `,
})
export class AbsencesPersonnelPage implements OnInit {
  private readonly api = inject(PersonnelApi);
  private readonly session = inject(SessionService);

  protected readonly dateCourte = dateCourte;
  protected readonly types = TYPES_ABSENCE;
  protected readonly statuts = STATUTS_ABSENCE;
  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly absences = signal<AbsenceVue[]>([]);
  protected readonly chef = computed(() => this.session.aLeRole('ADMIN_ECOLE'));

  private readonly jour = new Date().toISOString().slice(0, 10);
  protected readonly demandes = computed(() => this.absences().filter((b) => b.statut === 'DEMANDEE'));
  protected readonly aujourdhui = computed(() =>
    this.absences().filter((b) => b.statut !== 'DEMANDEE' && b.debut <= this.jour && b.fin >= this.jour),
  );
  protected readonly autres = computed(() =>
    this.absences().filter((b) => b.statut !== 'DEMANDEE' && !(b.debut <= this.jour && b.fin >= this.jour)),
  );

  async ngOnInit(): Promise<void> {
    await this.charger();
  }

  protected async decider(b: AbsenceVue, accorder: boolean): Promise<void> {
    const reponse = accorder ? null : globalThis.prompt?.(`Motif du refus pour ${b.agent} :`)?.trim();
    if (!accorder && !reponse) return;
    const r = await this.action.executer(() => this.api.decider(b.id, accorder, reponse ?? null));
    if (r) {
      this.message.set(accorder ? `Absence de ${b.agent} accordée : l'autorisation est signée.` : `Demande de ${b.agent} refusée.`);
      await this.charger();
    }
  }

  private async charger(): Promise<void> {
    const l = await this.action.executer(() => this.api.suiviAbsences());
    if (l) this.absences.set(l);
  }
}
