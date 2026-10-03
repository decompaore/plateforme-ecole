import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';

/** Encaisser, annuler, relancer, journal de caisse, paramétrer : intendance et direction. */
export const GESTION_SCOLARITE: Role[] = ['INTENDANT', 'ADMIN_ECOLE'];

const ONGLETS: { lien: string; titre: string; exact?: boolean; roles: Role[] }[] = [
  { lien: '/scolarite', titre: 'Guichet', exact: true, roles: ['INTENDANT', 'ADMIN_ECOLE', 'SECRETARIAT'] },
  { lien: '/scolarite/classes', titre: 'Classes et retards', roles: ['INTENDANT', 'ADMIN_ECOLE', 'SECRETARIAT'] },
  { lien: '/scolarite/journal', titre: 'Journal de caisse', roles: GESTION_SCOLARITE },
  { lien: '/scolarite/frais', titre: 'Frais et bourses', roles: ['INTENDANT', 'ADMIN_ECOLE', 'SECRETARIAT'] },
];

/** Onglets de la scolarité. */
@Component({
  selector: 'app-sco-nav',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="onglets" aria-label="Scolarité">
      @for (o of onglets(); track o.lien) {
        <a [routerLink]="o.lien" routerLinkActive="actif" [routerLinkActiveOptions]="{ exact: !!o.exact }">{{ o.titre }}</a>
      }
    </nav>
  `,
})
export class ScoNavComponent {
  private readonly session = inject(SessionService);
  protected readonly onglets = computed(() => {
    const roles = this.session.roles();
    return ONGLETS.filter((o) => o.roles.some((r) => roles.includes(r)));
  });
}
