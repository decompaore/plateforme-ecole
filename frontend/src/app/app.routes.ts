import { inject } from '@angular/core';
import { CanActivateFn, CanDeactivateFn, Router, Routes } from '@angular/router';

import { anonyme, connecte, role } from './core/gardes';
import { SessionService } from './core/session.service';
// Accueil, appel et notes chargés d'emblée (et non à la demande) : ils doivent s'ouvrir
// sans réseau même avant que le service worker ait fini de tout mettre en cache.
import { AccueilPage } from './pages/accueil.page';
import { AppelChoixPage } from './pages/appel-choix.page';
import { AppelSaisiePage } from './pages/appel-saisie.page';
import { EnvoisPage } from './pages/envois.page';
import { NotesChoixPage } from './pages/notes-choix.page';
import { NotesEvaluationsPage } from './pages/notes-evaluations.page';
import { NotesSaisiePage } from './pages/notes-saisie.page';

/** Quitter la feuille de notes sans enregistrer : on demande confirmation. */
const notesEnregistrees: CanDeactivateFn<NotesSaisiePage> = (page) =>
  !page.aDesModifications() || window.confirm('Des notes ne sont pas enregistrées. Quitter quand même ?');

/** Choix de l'établissement : pendant la connexion (jeton de sélection) ou une fois connecté. */
const selectionOuConnecte: CanActivateFn = () => {
  const session = inject(SessionService);
  if (session.selection() !== null) {
    return true;
  }
  if (session.profil() === null) {
    return inject(Router).parseUrl('/connexion');
  }
  return (!session.horsConnexion() && session.plusieursEtablissements()) || inject(Router).parseUrl('/');
};

const superAdmin: CanActivateFn = () =>
  inject(SessionService).profil()?.superAdmin === true || inject(Router).parseUrl('/');

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
        path: 'notes',
        canActivate: [role('ENSEIGNANT')],
        title: 'Saisie des notes',
        component: NotesChoixPage,
      },
      {
        path: 'notes/:classeId/:matiereId',
        canActivate: [role('ENSEIGNANT')],
        title: 'Évaluations',
        component: NotesEvaluationsPage,
      },
      {
        path: 'notes/:classeId/:matiereId/:evaluationId',
        canActivate: [role('ENSEIGNANT')],
        canDeactivate: [notesEnregistrees],
        title: 'Notes',
        component: NotesSaisiePage,
      },
      {
        path: 'plateforme',
        canActivate: [superAdmin],
        title: 'Établissements',
        loadComponent: () => import('./admin/pages/plateforme.page').then((m) => m.PlateformePage),
      },
      {
        // Administration de l'établissement : en ligne uniquement, chargée à la demande
        path: 'admin',
        canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT')],
        children: [
          { path: '', pathMatch: 'full', redirectTo: 'classes' },
          {
            path: 'classes',
            title: 'Classes',
            loadComponent: () => import('./admin/pages/classes.page').then((m) => m.ClassesPage),
          },
          {
            path: 'classes/:id',
            title: 'Classe',
            loadComponent: () => import('./admin/pages/classe.page').then((m) => m.ClassePage),
          },
          {
            path: 'eleves',
            title: 'Élèves',
            loadComponent: () => import('./admin/pages/eleves.page').then((m) => m.ElevesPage),
          },
          {
            path: 'evaluations',
            title: 'Suivi des évaluations',
            loadComponent: () => import('./admin/pages/suivi-evaluations.page').then((m) => m.SuiviEvaluationsPage),
          },
          {
            path: 'personnel',
            canActivate: [role('ADMIN_ECOLE')],
            title: 'Personnel',
            loadComponent: () => import('./admin/pages/personnel.page').then((m) => m.PersonnelPage),
          },
          {
            path: 'referentiel',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR')],
            title: 'Filières et matières',
            loadComponent: () => import('./admin/pages/referentiel.page').then((m) => m.ReferentielPage),
          },
          {
            path: 'annee',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR')],
            title: 'Année scolaire',
            loadComponent: () => import('./admin/pages/annee.page').then((m) => m.AnneePage),
          },
        ],
      },
      {
        // Espace parent : chargé à la demande ; la dernière situation reste lisible sans réseau
        path: 'parent',
        canActivate: [role('PARENT')],
        children: [
          {
            path: '',
            pathMatch: 'full',
            title: 'Mes enfants',
            loadComponent: () => import('./parent/pages/enfants.page').then((m) => m.EnfantsPage),
          },
          {
            path: 'enfants/:eleveId',
            title: 'Suivi de mon enfant',
            loadComponent: () => import('./parent/pages/enfant.page').then((m) => m.EnfantPage),
          },
        ],
      },
      {
        // Vie scolaire : en ligne, chargée à la demande
        path: 'vie-scolaire',
        canActivate: [role('SURVEILLANT', 'CENSEUR', 'ADMIN_ECOLE', 'SECRETARIAT')],
        children: [
          {
            path: '',
            pathMatch: 'full',
            title: 'Absences du jour',
            loadComponent: () => import('./vie-scolaire/pages/absences-jour.page').then((m) => m.AbsencesJourPage),
          },
          {
            path: 'eleves',
            title: 'Vie scolaire · Élèves',
            loadComponent: () => import('./vie-scolaire/pages/eleves-vs.page').then((m) => m.ElevesVsPage),
          },
          {
            path: 'eleves/:eleveId',
            title: 'Fiche de vie scolaire',
            loadComponent: () => import('./vie-scolaire/pages/fiche-eleve.page').then((m) => m.FicheElevePage),
          },
          {
            path: 'classes',
            title: 'Vie scolaire · Classes',
            loadComponent: () => import('./vie-scolaire/pages/classe-vs.page').then((m) => m.ClasseVsPage),
          },
          {
            path: 'convocations',
            title: 'Convocations',
            loadComponent: () => import('./vie-scolaire/pages/convocations.page').then((m) => m.ConvocationsPage),
          },
        ],
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
