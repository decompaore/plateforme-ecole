import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { heures, LIBELLE_STATUT, StatutFiche, SuiviProgressionVue } from '../modeles-progression';
import { ProgressionApi } from '../progression-api.service';

/** Pastille d'un statut de fiche (non commencée quand il n'y a pas encore de fiche). */
export function pastille(statut: StatutFiche | null): { texte: string; classe: string } {
  if (!statut) {
    return { texte: 'Non commencée', classe: 'absent' };
  }
  const classe = statut === 'VISEE' ? 'present' : statut === 'A_REVOIR' ? 'absent' : '';
  return { texte: LIBELLE_STATUT[statut], classe };
}

/** Les fiches de progression de l'enseignant : une par matière qu'il enseigne dans chaque classe. */
@Component({
  selector: 'app-mes-progressions',
  imports: [RouterLink],
  template: `
    <div class="page">
      <h1>Mes progressions</h1>
      <p class="doux">
        Pour chaque matière, préparez la progression de l'année (séquences, contenus, heures), puis soumettez-la au
        visa du censeur (matières générales) ou du chef des travaux (matières techniques).
      </p>

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }} <button type="button" class="bouton discret petit" (click)="charger()">Réessayer</button></div>
      }
      @if (aRevoir() > 0) {
        <div class="alerte attention">{{ aRevoir() }} progression(s) renvoyée(s) avec un commentaire : à corriger puis soumettre à nouveau.</div>
      }

      @if (fiches(); as liste) {
        <ul class="liste carte">
          @for (f of liste; track f.classeId + f.matiereId) {
            <li>
              <a class="ligne-lien" [routerLink]="['/progression', f.classeId, f.matiereId]">
                <span>
                  <strong>{{ f.classeCode }}</strong> · {{ f.matiereLibelle }}
                  <br />
                  <span class="doux">
                    @if (f.sequences) { {{ f.sequences }} séquence(s) · {{ heures(f.heuresPrevues) }} prévues } @else { Aucune séquence }
                    @if (f.volumeHebdo) { · programme {{ heures(f.volumeHebdo) }}/semaine }
                  </span>
                </span>
                <span class="pastille {{ pastille(f.statut).classe }}">{{ pastille(f.statut).texte }}</span>
              </a>
            </li>
          } @empty {
            <li class="doux">Aucune matière ne vous est confiée cette année.</li>
          }
        </ul>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
})
export class MesProgressionsPage implements OnInit {
  private readonly api = inject(ProgressionApi);

  protected readonly heures = heures;
  protected readonly pastille = pastille;
  protected readonly action = new Action();
  protected readonly fiches = signal<SuiviProgressionVue[] | null>(null);
  protected readonly aRevoir = computed(() => (this.fiches() ?? []).filter((f) => f.statut === 'A_REVOIR').length);

  ngOnInit(): void {
    void this.charger();
  }

  protected async charger(): Promise<void> {
    const l = await this.action.executer(() => this.api.mesFiches());
    if (l) {
      this.fiches.set(l);
    }
  }
}
