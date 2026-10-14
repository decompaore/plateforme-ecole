import { computed, signal } from '@angular/core';

import { Marque, TypeMarque } from '../core/modeles';
import { EleveLocal } from '../hors-ligne/listes.service';

export const MINUTES_PAR_DEFAUT = 10;
export const CHOIX_MINUTES = [5, 10, 15, 20, 30, 45, 60, 90, 120];

interface Etat {
  type: TypeMarque;
  minutes: number;
}

/**
 * Feuille d'appel en cours de saisie. Tout le monde est présent au départ :
 * l'enseignant ne touche que les absents et les retards.
 */
export class FeuilleAppel {
  private readonly etats = signal<ReadonlyMap<string, Etat>>(new Map());

  readonly absents = computed(() => [...this.etats().values()].filter((e) => e.type === 'ABSENCE').length);
  readonly retards = computed(() => [...this.etats().values()].filter((e) => e.type === 'RETARD').length);
  readonly presents = computed(() => this.eleves.length - this.absents());

  constructor(readonly eleves: readonly EleveLocal[]) {}

  etat(inscriptionId: string): Etat | undefined {
    return this.etats().get(inscriptionId);
  }

  /** Appuyer sur « Absent » ou « Retard » : active la marque, ou revient à présent si elle l'était déjà. */
  basculer(inscriptionId: string, type: TypeMarque): void {
    this.etats.update((actuels) => {
      const suivants = new Map(actuels);
      if (actuels.get(inscriptionId)?.type === type) {
        suivants.delete(inscriptionId);
      } else {
        suivants.set(inscriptionId, { type, minutes: actuels.get(inscriptionId)?.minutes ?? MINUTES_PAR_DEFAUT });
      }
      return suivants;
    });
  }

  minutes(inscriptionId: string, minutes: number): void {
    this.etats.update((actuels) => {
      const e = actuels.get(inscriptionId);
      if (!e || e.type !== 'RETARD') {
        return actuels;
      }
      return new Map(actuels).set(inscriptionId, { ...e, minutes: Math.min(Math.max(Math.round(minutes), 1), 600) });
    });
  }

  toutPresent(): void {
    this.etats.set(new Map());
  }

  /** Marques envoyées au serveur (les élèves non cités sont présents). */
  marques(): Marque[] {
    return [...this.etats().entries()]
      .filter(([id]) => this.eleves.some((e) => e.inscriptionId === id))
      .map(([inscriptionId, e]) => ({
        inscriptionId,
        type: e.type,
        minutesRetard: e.type === 'RETARD' ? e.minutes : null,
      }));
  }

  /** Élèves marqués, dans l'ordre de la liste, pour le récapitulatif. */
  detail(): { eleve: EleveLocal; type: TypeMarque; minutes: number }[] {
    const etats = this.etats();
    return this.eleves
      .filter((e) => etats.has(e.inscriptionId))
      .map((e) => ({ eleve: e, type: etats.get(e.inscriptionId)!.type, minutes: etats.get(e.inscriptionId)!.minutes }));
  }
}
