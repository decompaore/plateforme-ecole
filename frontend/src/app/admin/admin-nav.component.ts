import { Component, computed, inject, OnInit } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { AnneeCourante } from './annee-courante.service';
import { LIBELLE_ETAT_ANNEE } from './modeles-admin';

interface Onglet {
  lien: string;
  titre: string;
  roles: Role[];
}

/** Rôles qui voient chaque écran ; le serveur vérifie de toute façon chaque appel. */
export const ONGLETS: Onglet[] = [
  { lien: '/admin/classes', titre: 'Classes', roles: ['ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT'] },
  { lien: '/admin/eleves', titre: 'Élèves', roles: ['ADMIN_ECOLE', 'SECRETARIAT', 'CENSEUR'] },
  { lien: '/admin/evaluations', titre: 'Évaluations', roles: ['ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT'] },
  { lien: '/admin/statistiques', titre: 'Statistiques', roles: ['ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT', 'INTENDANT'] },
  { lien: '/admin/personnel', titre: 'Personnel', roles: ['ADMIN_ECOLE'] },
  { lien: '/admin/comptes', titre: 'Comptes', roles: ['ADMIN_ECOLE'] },
  { lien: '/admin/referentiel', titre: 'Filières et matières', roles: ['ADMIN_ECOLE', 'CENSEUR'] },
  { lien: '/admin/annee', titre: 'Année scolaire', roles: ['ADMIN_ECOLE', 'CENSEUR'] },
  { lien: '/admin/utilisation', titre: 'Utilisation', roles: ['ADMIN_ECOLE'] },
  { lien: '/admin/donnees', titre: 'Données', roles: ['ADMIN_ECOLE'] },
];

/** Onglets de l'administration et choix de l'année de travail. */
@Component({
  selector: 'app-admin-nav',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav class="onglets" aria-label="Administration">
      @for (o of onglets(); track o.lien) {
        <a [routerLink]="o.lien" routerLinkActive="actif">{{ o.titre }}</a>
      }
    </nav>
    @if (anneeCourante.annees().length > 1) {
      <label class="annee">
        <span class="doux">Année</span>
        <select [value]="anneeCourante.annee()?.id" (change)="choisir($event)">
          @for (a of anneeCourante.annees(); track a.id) {
            <option [value]="a.id">{{ a.libelle }} · {{ etat[a.etat] }}</option>
          }
        </select>
      </label>
    }
  `,
  styles: `
    .annee {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin: -0.25rem 0 1rem;
      select {
        width: auto;
        min-height: 40px;
        padding: 0.3rem 0.6rem;
      }
    }
  `,
})
export class AdminNavComponent implements OnInit {
  private readonly session = inject(SessionService);
  protected readonly anneeCourante = inject(AnneeCourante);
  protected readonly etat = LIBELLE_ETAT_ANNEE;

  protected readonly onglets = computed(() => {
    const roles = this.session.roles();
    return ONGLETS.filter((o) => o.roles.some((r) => roles.includes(r)));
  });

  ngOnInit(): void {
    void this.anneeCourante.charger().catch(() => undefined);
  }

  protected choisir(e: Event): void {
    this.anneeCourante.choisir((e.target as HTMLSelectElement).value);
  }
}
