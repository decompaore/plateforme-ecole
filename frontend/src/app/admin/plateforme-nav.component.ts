import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { SessionService } from '../core/session.service';

/** Onglets des pages du super administrateur. */
@Component({
  selector: 'app-plateforme-nav',
  imports: [RouterLink, RouterLinkActive],
  template: `
    @if (pays(); as p) { <p class="doux pays">Administration du pays : <strong>{{ p.nom }}</strong></p> }
    <nav class="onglets" aria-label="Plateforme">
      <a routerLink="/plateforme" routerLinkActive="actif" [routerLinkActiveOptions]="{ exact: true }">Établissements</a>
      <a routerLink="/plateforme/territoire" routerLinkActive="actif">Territoire</a>
      <a routerLink="/plateforme/adoption" routerLinkActive="actif">Adoption</a>
      <a routerLink="/pilotage" routerLinkActive="actif">Pilotage</a>
    </nav>
  `,
})
export class PlateformeNavComponent {
  private readonly session = inject(SessionService);
  protected readonly pays = computed(() => this.session.profil()?.adminPays ?? null);
}
