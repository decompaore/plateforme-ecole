import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { messageErreur } from '../../core/erreurs';
import { dateHeureCourte } from '../../core/outils';
import { dateHeure } from '../../vie-scolaire/modeles-vs';
import { EnfantVue } from '../modeles-parent';
import { ParentApi } from '../parent-api.service';
import { Indicateur, indicateurAbsences, indicateurScolarite, prochaineConvocation, situationCourante } from '../resume';

interface Carte {
  enfant: EnfantVue;
  indicateurs: Indicateur[];
  convocation: string | null;
}

/**
 * Accueil du parent : un résumé par enfant (absences récentes, scolarité, convocation),
 * lisible d'un coup d'œil sur un petit téléphone, même sans réseau.
 */
@Component({
  selector: 'app-enfants',
  imports: [RouterLink],
  template: `
    <div class="page">
      <h1>Mes enfants</h1>

      @if (horsLigne(); as le) {
        <div class="alerte attention" role="status">Pas de réseau : situation du {{ dateHeureCourte(le) }}.</div>
      }
      @if (erreur()) {
        <div class="alerte erreur" role="alert">{{ erreur() }}</div>
      }
      @if (chargement()) {
        <p class="doux">Chargement…</p>
      }

      @for (c of cartes(); track c.enfant.eleveId) {
        <a class="carte enfant" [routerLink]="['/parent/enfants', c.enfant.eleveId]">
          <div class="entete">
            <div>
              <strong class="nom">{{ c.enfant.prenoms }} {{ c.enfant.nom }}</strong>
              <div class="doux">
                {{ c.enfant.classeCode ?? 'Pas inscrit cette année' }}@if (c.enfant.anneeLibelle) { · {{ c.enfant.anneeLibelle }} }
              </div>
            </div>
            <span class="fleche" aria-hidden="true">›</span>
          </div>
          @if (c.convocation) {
            <p class="indicateur ton-alerte">Vous êtes convoqué(e) le {{ c.convocation }}</p>
          }
          @for (i of c.indicateurs; track i.texte) {
            <p [class]="'indicateur ton-' + i.ton">{{ i.texte }}</p>
          }
        </a>
      } @empty {
        @if (!chargement() && !erreur()) {
          <div class="carte">
            <p>Aucun enfant n'est rattaché à votre compte dans cet établissement.</p>
            <p class="doux">Adressez-vous au secrétariat avec le numéro de téléphone que vous lui avez donné.</p>
          </div>
        }
      }
    </div>
  `,
  styles: `
    .enfant {
      display: block;
      color: inherit;
      text-decoration: none;
    }
    .entete {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 0.5rem;
    }
    .nom {
      font-size: 1.1rem;
    }
    .fleche {
      font-size: 1.5rem;
      color: var(--texte-doux);
    }
    .indicateur {
      margin: 0.35rem 0 0;
      padding-left: 0.75rem;
      border-left: 3px solid var(--bordure);
    }
    .ton-bon {
      border-left-color: var(--present);
    }
    .ton-attention {
      border-left-color: var(--retard);
    }
    .ton-alerte {
      border-left-color: var(--absent);
      color: var(--absent);
      font-weight: 600;
    }
  `,
})
export class EnfantsPage implements OnInit {
  private readonly api = inject(ParentApi);

  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly cartes = signal<Carte[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  /** Date de la copie affichée quand le serveur est injoignable. */
  protected readonly horsLigne = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    try {
      const enfants = await this.api.enfants();
      this.noter(enfants);
      this.cartes.set(enfants.donnees.map((enfant) => ({ enfant, indicateurs: [], convocation: null })));
      // Les résumés arrivent ensuite, enfant par enfant ; une erreur n'empêche pas les autres
      await Promise.all(enfants.donnees.map((e) => this.resumer(e)));
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  private noter(l: { horsLigne: boolean; le: string }): void {
    if (l.horsLigne && (!this.horsLigne() || l.le < this.horsLigne()!)) {
      this.horsLigne.set(l.le);
    }
  }

  private async resumer(enfant: EnfantVue): Promise<void> {
    const [absences, scolarite, vie] = await Promise.allSettled([
      this.api.absences(enfant.eleveId),
      this.api.scolarite(enfant.eleveId),
      this.api.vieScolaire(enfant.eleveId),
    ]);
    const indicateurs: Indicateur[] = [];
    let convocation: string | null = null;
    if (absences.status === 'fulfilled') {
      this.noter(absences.value);
      indicateurs.push(indicateurAbsences(absences.value.donnees));
    }
    if (scolarite.status === 'fulfilled') {
      this.noter(scolarite.value);
      const i = indicateurScolarite(situationCourante(enfant, scolarite.value.donnees));
      if (i) {
        indicateurs.push(i);
      }
    }
    if (vie.status === 'fulfilled') {
      this.noter(vie.value);
      const c = prochaineConvocation(vie.value.donnees);
      convocation = c ? dateHeure(c.rendezVous) : null;
    }
    this.cartes.update((l) => l.map((c) => (c.enfant.eleveId === enfant.eleveId ? { ...c, indicateurs, convocation } : c)));
  }
}
