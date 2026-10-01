import { computed, inject, Injectable, signal } from '@angular/core';

import { AdminApi } from './admin-api.service';
import { AnneeVue } from './modeles-admin';

/**
 * Année scolaire sur laquelle travaille l'administration : l'année en cours
 * par défaut, sinon la plus récente (en préparation). Partagée par les écrans.
 */
@Injectable({ providedIn: 'root' })
export class AnneeCourante {
  private readonly api = inject(AdminApi);

  readonly annees = signal<AnneeVue[]>([]);
  private readonly choisie = signal<string | null>(null);
  readonly chargee = signal(false);

  readonly annee = computed<AnneeVue | null>(() => {
    const annees = this.annees();
    return (
      annees.find((a) => a.id === this.choisie()) ??
      annees.find((a) => a.etat === 'ACTIVE') ??
      [...annees].sort((a, b) => b.debut.localeCompare(a.debut))[0] ??
      null
    );
  });

  async charger(forcer = false): Promise<void> {
    if (this.chargee() && !forcer) {
      return;
    }
    const annees = await this.api.annees();
    this.annees.set([...annees].sort((a, b) => b.debut.localeCompare(a.debut)));
    this.chargee.set(true);
  }

  choisir(id: string): void {
    this.choisie.set(id);
  }

  /** À appeler à la déconnexion ou au changement d'établissement. */
  oublier(): void {
    this.annees.set([]);
    this.choisie.set(null);
    this.chargee.set(false);
  }
}
