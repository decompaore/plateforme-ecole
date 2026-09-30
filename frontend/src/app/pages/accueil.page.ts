import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { EnvoisService } from '../hors-ligne/envois.service';

interface Tuile {
  titre: string;
  texte: string;
  lien?: string;
  roles: Role[];
}

/** Écrans disponibles selon le rôle. Les autres arrivent dans les prochaines versions. */
const TUILES: Tuile[] = [
  { titre: "Faire l'appel", texte: 'Marche aussi sans réseau', lien: '/appel', roles: ['ENSEIGNANT'] },
  { titre: 'Mes appels', texte: 'Envoyés, en attente, refusés', lien: '/envois', roles: ['ENSEIGNANT'] },
  { titre: 'Saisie des notes', texte: 'Bientôt disponible', roles: ['ENSEIGNANT', 'CENSEUR'] },
  { titre: 'Vie scolaire', texte: 'Bientôt disponible', roles: ['SURVEILLANT', 'CENSEUR', 'ADMIN_ECOLE'] },
  { titre: 'Élèves et classes', texte: 'Bientôt disponible', roles: ['SECRETARIAT', 'CENSEUR', 'ADMIN_ECOLE'] },
  { titre: 'Scolarité et paiements', texte: 'Bientôt disponible', roles: ['INTENDANT', 'ADMIN_ECOLE'] },
  { titre: 'Statistiques', texte: 'Bientôt disponible', roles: ['ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT', 'INTENDANT'] },
  { titre: 'Suivi de mon enfant', texte: 'Bientôt disponible', roles: ['PARENT'] },
];

@Component({
  selector: 'app-accueil',
  imports: [RouterLink],
  template: `
    <div class="page">
      <h1>Bonjour{{ session.profil()?.prenoms ? ' ' + session.profil()?.prenoms : '' }}</h1>

      @if (envois.enAttente() > 0) {
        <a routerLink="/envois" class="alerte attention bloc">
          {{ envois.enAttente() }} appel(s) en attente d'envoi.
        </a>
      }
      @if (envois.refuses() > 0) {
        <a routerLink="/envois" class="alerte erreur bloc">{{ envois.refuses() }} appel(s) refusé(s) : à vérifier.</a>
      }

      @if (session.profil()?.superAdmin) {
        <div class="carte">
          <h2>Administration de la plateforme</h2>
          <p class="doux">L'écran d'administration arrive dans une prochaine version. En attendant, utilisez l'API.</p>
        </div>
      } @else if (!session.profil()?.etablissement) {
        <div class="carte">
          <p>Aucun établissement actif. Si un établissement vous a invité, acceptez l'invitation pour y accéder.</p>
        </div>
      }

      <div class="grille">
        @for (t of tuiles(); track t.titre) {
          @if (t.lien) {
            <a class="tuile active" [routerLink]="t.lien">
              <strong>{{ t.titre }}</strong>
              <span>{{ t.texte }}</span>
            </a>
          } @else {
            <div class="tuile" aria-disabled="true">
              <strong>{{ t.titre }}</strong>
              <span>{{ t.texte }}</span>
            </div>
          }
        }
      </div>
    </div>
  `,
  styles: `
    .bloc {
      display: block;
      text-decoration: none;
    }
    .grille {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(9.5rem, 1fr));
      gap: 0.75rem;
    }
    .tuile {
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      gap: 0.5rem;
      min-height: 7rem;
      padding: 1rem;
      border-radius: var(--rayon);
      border: 1px dashed var(--bordure);
      color: var(--texte-doux);
      text-decoration: none;
      span {
        font-size: 0.85rem;
      }
    }
    .tuile.active {
      border: none;
      background: var(--primaire);
      color: var(--sur-primaire);
      box-shadow: var(--ombre);
    }
  `,
})
export class AccueilPage {
  protected readonly session = inject(SessionService);
  protected readonly envois = inject(EnvoisService);

  protected readonly tuiles = computed(() => {
    const roles = this.session.roles();
    return TUILES.filter((t) => t.roles.some((r) => roles.includes(r)));
  });
}
