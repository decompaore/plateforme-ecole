import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { EnvoisService } from '../hors-ligne/envois.service';
import { NotesService } from '../hors-ligne/notes.service';

interface Tuile {
  titre: string;
  texte: string;
  lien?: string;
  roles: Role[];
}

/** Écrans disponibles selon le rôle. Les autres arrivent dans les prochaines versions. */
const TUILES: Tuile[] = [
  { titre: "Faire l'appel", texte: 'Marche aussi sans réseau', lien: '/appel', roles: ['ENSEIGNANT'] },
  { titre: 'Mes envois', texte: 'Appels et notes : envoyés, en attente', lien: '/envois', roles: ['ENSEIGNANT'] },
  { titre: 'Classes', texte: 'Programmes, coefficients, enseignants', lien: '/admin/classes', roles: ['ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT'] },
  { titre: 'Élèves', texte: 'Inscriptions, import Excel', lien: '/admin/eleves', roles: ['ADMIN_ECOLE', 'SECRETARIAT', 'CENSEUR'] },
  { titre: 'Personnel', texte: 'Enseignants et administration', lien: '/admin/personnel', roles: ['ADMIN_ECOLE'] },
  { titre: 'Année scolaire', texte: 'Périodes, ouverture, filières', lien: '/admin/annee', roles: ['ADMIN_ECOLE', 'CENSEUR'] },
  { titre: 'Saisie des notes', texte: 'Marche aussi sans réseau', lien: '/notes', roles: ['ENSEIGNANT'] },
  { titre: 'Vie scolaire', texte: 'Bientôt disponible', roles: ['SURVEILLANT', 'CENSEUR', 'ADMIN_ECOLE'] },
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
      @if (notes.enAttente() > 0) {
        <a routerLink="/envois" class="alerte attention bloc">{{ notes.enAttente() }} saisie(s) de notes en attente d'envoi.</a>
      }
      @if (notes.refusees() > 0) {
        <a routerLink="/envois" class="alerte erreur bloc">{{ notes.refusees() }} saisie(s) de notes refusée(s) : à vérifier.</a>
      }
      @if (envois.refuses() > 0) {
        <a routerLink="/envois" class="alerte erreur bloc">{{ envois.refuses() }} appel(s) refusé(s) : à vérifier.</a>
      }

      @if (session.profil()?.superAdmin) {
        <div class="grille">
          <a class="tuile active" routerLink="/plateforme">
            <strong>Établissements</strong>
            <span>Créer, suivre, suspendre</span>
          </a>
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
  protected readonly notes = inject(NotesService);

  protected readonly tuiles = computed(() => {
    const roles = this.session.roles();
    return TUILES.filter((t) => t.roles.some((r) => roles.includes(r)));
  });
}
