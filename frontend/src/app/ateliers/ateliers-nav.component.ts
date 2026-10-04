import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { ROLES_ATELIERS } from './modeles-ateliers';

const ONGLETS: { lien: string; titre: string; exact?: boolean; roles: Role[] }[] = [
  { lien: '/ateliers', titre: 'Ateliers', exact: true, roles: ROLES_ATELIERS },
  { lien: '/ateliers/catalogue', titre: 'Catalogue des prix', roles: ROLES_ATELIERS },
];

/** Onglets de l'espace des ateliers. */
@Component({
  selector: 'app-ateliers-nav',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="onglets" aria-label="Ateliers">
      @for (o of onglets(); track o.lien) {
        <a [routerLink]="o.lien" routerLinkActive="actif" [routerLinkActiveOptions]="{ exact: !!o.exact }">{{ o.titre }}</a>
      }
    </nav>
  `,
})
export class AteliersNavComponent {
  private readonly session = inject(SessionService);
  protected readonly onglets = computed(() => {
    const roles = this.session.roles();
    return ONGLETS.filter((o) => o.roles.some((r) => roles.includes(r)));
  });
}
