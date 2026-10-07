import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

/** Onglets des pages du super administrateur. */
@Component({
  selector: 'app-plateforme-nav',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="onglets" aria-label="Plateforme">
      <a routerLink="/plateforme" routerLinkActive="actif" [routerLinkActiveOptions]="{ exact: true }">Établissements</a>
      <a routerLink="/plateforme/territoire" routerLinkActive="actif">Territoire</a>
      <a routerLink="/plateforme/adoption" routerLinkActive="actif">Adoption</a>
    </nav>
  `,
})
export class PlateformeNavComponent {}
