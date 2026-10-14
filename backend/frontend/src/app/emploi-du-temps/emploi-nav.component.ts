import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { ROLES_EMPLOI, ROLES_GRILLE } from './modeles-emploi';

const ONGLETS: { lien: string; titre: string; exact?: boolean; roles: Role[] }[] = [
  { lien: '/emploi-du-temps', titre: 'Emplois du temps', exact: true, roles: ROLES_EMPLOI },
  { lien: '/emploi-du-temps/grille', titre: 'Grille horaire', roles: ROLES_GRILLE },
  { lien: '/emploi-du-temps/mon-emploi', titre: 'Mon emploi du temps', roles: ['ENSEIGNANT'] },
];

/** Onglets de l'espace des emplois du temps. */
@Component({
  selector: 'app-emploi-nav',
  imports: [RouterLink, RouterLinkActive],
  template: `
    @if (onglets().length > 1) {
      <nav class="onglets" aria-label="Emplois du temps">
        @for (o of onglets(); track o.lien) {
          <a [routerLink]="o.lien" routerLinkActive="actif" [routerLinkActiveOptions]="{ exact: !!o.exact }">{{ o.titre }}</a>
        }
      </nav>
    }
  `,
})
export class EmploiNavComponent {
  private readonly session = inject(SessionService);
  protected readonly onglets = computed(() => {
    const roles = this.session.roles();
    return ONGLETS.filter((o) => o.roles.some((r) => roles.includes(r)));
  });
}
