import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';

import { anonyme, connecte, role } from './core/gardes';
import { SessionService } from './core/session.service';
// Accueil et écrans de l'appel chargés d'emblée (et non à la demande) : ils doivent s'ouvrir
// sans réseau même avant que le service worker ait fini de tout mettre en cache.
import { AccueilPage } from './pages/accueil.page';
import { AppelChoixPage } from './pages/appel-choix.page';
import { AppelSaisiePage } from './pages/appel-saisie.page';
import { EnvoisPage } from './pages/envois.page';

/** Choix de l'établissement : pendant la connexion (jeton de sélection) ou une fois connecté. */
const selectionOuConnecte: CanActivateFn = () => {
  const session = inject(SessionService);
  return session.selection() !== null || (session.profil() !== null && !session.horsConnexion())
    ? true
    : inject(Router).parseUrl('/connexion');
};

const profilPresent: CanActivateFn = () =>
  inject(SessionService).profil() !== null || inject(Router).parseUrl('/connexion');

export const routes: Routes = [
  {
    path: 'connexion',
    canActivate: [anonyme],
    title: 'Connexion',
    loadComponent: () => import('./pages/connexion.page').then((m) => m.ConnexionPage),
  },
  {
    path: 'etablissement',
    canActivate: [selectionOuConnecte],
    title: 'Établissement',
    loadComponent: () => import('./pages/etablissement.page').then((m) => m.EtablissementPage),
  },
  {
    path: 'mot-de-passe',
    canActivate: [profilPresent],
    title: 'Mot de passe',
    loadComponent: () => import('./pages/mot-de-passe.page').then((m) => m.MotDePassePage),
  },
  {
    path: '',
    canActivate: [connecte],
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Accueil',
        component: AccueilPage,
      },
      {
        path: 'appel',
        canActivate: [role('ENSEIGNANT')],
        title: "Faire l'appel",
        component: AppelChoixPage,
      },
      {
        path: 'appel/:classeId/:matiereId',
        canActivate: [role('ENSEIGNANT')],
        title: 'Appel',
        component: AppelSaisiePage,
      },
      {
        path: 'envois',
        canActivate: [role('ENSEIGNANT')],
        title: 'Mes appels',
        component: EnvoisPage,
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
