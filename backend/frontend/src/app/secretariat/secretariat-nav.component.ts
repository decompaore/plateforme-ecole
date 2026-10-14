import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { GESTION_SECRETARIAT, SERVICES_COURRIER } from './modeles-secretariat';

const ONGLETS: { lien: string; titre: string; roles: Role[] }[] = [
  { lien: '/secretariat/courrier', titre: 'Courrier', roles: [...GESTION_SECRETARIAT, ...SERVICES_COURRIER] },
  { lien: '/secretariat/documents', titre: 'Documents', roles: GESTION_SECRETARIAT },
  { lien: '/secretariat/demandes', titre: 'Demandes du personnel', roles: GESTION_SECRETARIAT },
  { lien: '/secretariat/accueil', titre: 'Accueil', roles: GESTION_SECRETARIAT },
  { lien: '/secretariat/reunions', titre: 'Réunions', roles: [...GESTION_SECRETARIAT, 'CENSEUR', 'CHEF_TRAVAUX'] },
  { lien: '/secretariat/archives', titre: 'Archives', roles: GESTION_SECRETARIAT },
  { lien: '/secretariat/eleves', titre: 'Certificats et examens', roles: [...GESTION_SECRETARIAT, 'CENSEUR'] },
];

/** Onglets du secrétariat (v0.47.1). */
@Component({
  selector: 'app-secretariat-nav',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="onglets" aria-label="Secrétariat">
      @for (o of onglets(); track o.lien) {
        <a [routerLink]="o.lien" routerLinkActive="actif">{{ o.titre }}</a>
      }
    </nav>
  `,
})
export class SecretariatNavComponent {
  private readonly session = inject(SessionService);
  protected readonly onglets = computed(() => {
    const roles = this.session.roles();
    return ONGLETS.filter((o) => o.roles.some((r) => roles.includes(r)));
  });
}
