import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

/** Onglets de la vie scolaire. */
@Component({
  selector: 'app-vs-nav',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="onglets" aria-label="Vie scolaire">
      <a routerLink="/vie-scolaire" routerLinkActive="actif" [routerLinkActiveOptions]="{ exact: true }">Absences du jour</a>
      <a routerLink="/vie-scolaire/eleves" routerLinkActive="actif">Élèves</a>
      <a routerLink="/vie-scolaire/classes" routerLinkActive="actif">Classes</a>
      <a routerLink="/vie-scolaire/convocations" routerLinkActive="actif">Convocations</a>
    </nav>
  `,
})
export class VsNavComponent {}
