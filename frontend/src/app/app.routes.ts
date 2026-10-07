import { inject } from '@angular/core';
import { CanActivateFn, CanDeactivateFn, Router, Routes } from '@angular/router';

import { anonyme, connecte, module, role } from './core/gardes';
import { SessionService } from './core/session.service';
// Accueil, appel et notes chargés d'emblée (et non à la demande) : ils doivent s'ouvrir
// sans réseau même avant que le service worker ait fini de tout mettre en cache.
import { AccueilPage } from './pages/accueil.page';
import { AppelChoixPage } from './pages/appel-choix.page';
import { AppelSaisiePage } from './pages/appel-saisie.page';
import { CahierChoixPage } from './pages/cahier-choix.page';
import { CahierPage } from './pages/cahier.page';
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

/** Super administrateur ou administrateur pays (v0.36) ; le serveur limite ce dernier à son pays. */
const superAdmin: CanActivateFn = () =>
  inject(SessionService).administrePlateforme() || inject(Router).parseUrl('/');

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
        path: 'cahier',
        canActivate: [role('ENSEIGNANT'), module('PROGRESSION')],
        title: 'Cahier de textes',
        component: CahierChoixPage,
      },
      {
        path: 'cahier/:classeId/:matiereId',
        canActivate: [role('ENSEIGNANT'), module('PROGRESSION')],
        title: 'Cahier de textes',
        component: CahierPage,
      },
      {
        path: 'appareils',
        title: 'Mes appareils',
        loadComponent: () => import('./pages/appareils.page').then((m) => m.AppareilsPage),
      },
      {
        path: 'plateforme/territoire',
        canActivate: [superAdmin],
        title: 'Territoire',
        loadComponent: () => import('./admin/pages/territoire.page').then((m) => m.TerritoirePage),
      },
      {
        path: 'plateforme/adoption',
        canActivate: [superAdmin],
        title: 'Adoption',
        loadComponent: () => import('./admin/pages/adoption-plateforme.page').then((m) => m.AdoptionPlateformePage),
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
        canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT', 'INTENDANT')],
        children: [
          {
            path: '',
            pathMatch: 'full',
            // L'intendance n'a que les statistiques dans l'administration
            redirectTo: () => (inject(SessionService).aLeRole('ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT') ? 'classes' : 'statistiques'),
          },
          {
            path: 'classes',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT')],
            title: 'Classes',
            loadComponent: () => import('./admin/pages/classes.page').then((m) => m.ClassesPage),
          },
          {
            path: 'classes/:id',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT')],
            title: 'Classe',
            loadComponent: () => import('./admin/pages/classe.page').then((m) => m.ClassePage),
          },
          {
            path: 'eleves',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT')],
            title: 'Élèves',
            loadComponent: () => import('./admin/pages/eleves.page').then((m) => m.ElevesPage),
          },
          {
            path: 'evaluations',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT')],
            title: 'Suivi des évaluations',
            loadComponent: () => import('./admin/pages/suivi-evaluations.page').then((m) => m.SuiviEvaluationsPage),
          },
          {
            path: 'statistiques',
            title: 'Statistiques',
            loadComponent: () => import('./admin/pages/statistiques.page').then((m) => m.StatistiquesPage),
          },
          {
            path: 'personnel',
            canActivate: [role('ADMIN_ECOLE')],
            title: 'Personnel',
            loadComponent: () => import('./admin/pages/personnel.page').then((m) => m.PersonnelPage),
          },
          {
            path: 'comptes',
            canActivate: [role('ADMIN_ECOLE')],
            title: 'Comptes et mots de passe',
            loadComponent: () => import('./admin/pages/comptes.page').then((m) => m.ComptesPage),
          },
          {
            path: 'identite',
            canActivate: [role('ADMIN_ECOLE')],
            title: 'Identité de l’établissement',
            loadComponent: () => import('./admin/pages/identite.page').then((m) => m.IdentitePage),
          },
          {
            path: 'utilisation',
            canActivate: [role('ADMIN_ECOLE')],
            title: 'Utilisation de l’application',
            loadComponent: () => import('./admin/pages/utilisation.page').then((m) => m.UtilisationPage),
          },
          {
            path: 'donnees',
            canActivate: [role('ADMIN_ECOLE')],
            title: 'Données de l’établissement',
            loadComponent: () => import('./admin/pages/donnees.page').then((m) => m.DonneesPage),
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
        canActivate: [role('PARENT'), module('ESPACE_PARENT')],
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
        // Progressions : préparées par l'enseignant, visées par le censeur ou le chef des travaux (en ligne)
        path: 'progression',
        canActivate: [role('ENSEIGNANT', 'ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX'), module('PROGRESSION')],
        children: [
          {
            path: '',
            pathMatch: 'full',
            canActivate: [() => inject(SessionService).aLeRole('ENSEIGNANT') || inject(Router).parseUrl('/progression/suivi')],
            title: 'Mes progressions',
            loadComponent: () => import('./progression/pages/mes-progressions.page').then((m) => m.MesProgressionsPage),
          },
          {
            path: 'suivi',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX')],
            title: 'Suivi des progressions',
            loadComponent: () => import('./progression/pages/suivi-progressions.page').then((m) => m.SuiviProgressionsPage),
          },
          {
            path: ':classeId/:matiereId/cahier',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX')],
            title: 'Cahier de textes',
            loadComponent: () => import('./progression/pages/cahier-lecture.page').then((m) => m.CahierLecturePage),
          },
          {
            path: ':classeId/:matiereId',
            title: 'Fiche de progression',
            loadComponent: () => import('./progression/pages/fiche-progression.page').then((m) => m.FicheProgressionPage),
          },
        ],
      },
      {
        // Emplois du temps : le censeur place les matières générales, le chef des travaux les
        // matières techniques et pratiques ; l'enseignant voit le sien une fois publié (en ligne)
        path: 'emploi-du-temps',
        canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'SURVEILLANT', 'ENSEIGNANT'), module('EMPLOIS_DU_TEMPS')],
        children: [
          {
            path: '',
            pathMatch: 'full',
            canActivate: [
              () =>
                inject(SessionService).aLeRole('ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'SURVEILLANT') ||
                inject(Router).parseUrl('/emploi-du-temps/mon-emploi'),
            ],
            title: 'Emplois du temps',
            loadComponent: () => import('./emploi-du-temps/pages/emploi-du-temps.page').then((m) => m.EmploiDuTempsPage),
          },
          {
            path: 'grille',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR')],
            title: 'Grille horaire',
            loadComponent: () => import('./emploi-du-temps/pages/grille-horaire.page').then((m) => m.GrilleHorairePage),
          },
          {
            path: 'mon-emploi',
            canActivate: [role('ENSEIGNANT')],
            title: 'Mon emploi du temps',
            loadComponent: () => import('./emploi-du-temps/pages/mon-emploi.page').then((m) => m.MonEmploiPage),
          },
        ],
      },
      {
        // Ateliers : catalogue des prix, responsables, équipements, matière d'œuvre, inventaires,
        // besoins, commandes, réception et répartition (en ligne)
        path: 'ateliers',
        canActivate: [role('ADMIN_ECOLE', 'CHEF_TRAVAUX', 'INTENDANT', 'ENSEIGNANT'), module('ATELIERS')],
        children: [
          {
            path: '',
            pathMatch: 'full',
            title: 'Ateliers',
            loadComponent: () => import('./ateliers/pages/ateliers.page').then((m) => m.AteliersPage),
          },
          {
            path: 'catalogue',
            title: 'Catalogue des prix',
            loadComponent: () => import('./ateliers/pages/catalogue.page').then((m) => m.CataloguePage),
          },
          {
            path: 'inventaires/:inventaireId',
            title: 'Inventaire',
            loadComponent: () => import('./ateliers/pages/inventaire.page').then((m) => m.InventairePage),
          },
          {
            path: 'besoins',
            title: 'Besoins et commandes',
            loadComponent: () => import('./ateliers/pages/campagnes.page').then((m) => m.CampagnesPage),
          },
          {
            path: 'besoins/:campagneId',
            title: 'Campagne de besoins',
            loadComponent: () => import('./ateliers/pages/campagne.page').then((m) => m.CampagnePage),
          },
          {
            path: 'besoins-ateliers/:besoinId',
            title: 'Besoins de l’atelier',
            loadComponent: () => import('./ateliers/pages/besoin.page').then((m) => m.BesoinPage),
          },
          {
            path: 'commandes/:commandeId',
            title: 'Commande',
            loadComponent: () => import('./ateliers/pages/commande.page').then((m) => m.CommandePage),
          },
          {
            path: 'livraisons/:livraisonId',
            title: 'Livraison',
            loadComponent: () => import('./ateliers/pages/livraison.page').then((m) => m.LivraisonPage),
          },
          {
            path: ':atelierId',
            title: 'Atelier',
            loadComponent: () => import('./ateliers/pages/atelier.page').then((m) => m.AtelierPage),
          },
        ],
      },
      {
        // Scolarité et paiements : en ligne (l'encaissement exige le serveur), chargée à la demande
        path: 'scolarite',
        canActivate: [role('INTENDANT', 'ADMIN_ECOLE', 'SECRETARIAT'), module('SCOLARITE')],
        children: [
          {
            path: '',
            pathMatch: 'full',
            title: 'Guichet',
            loadComponent: () => import('./scolarite/pages/guichet.page').then((m) => m.GuichetPage),
          },
          {
            path: 'eleves/:eleveId',
            title: 'Scolarité de l’élève',
            loadComponent: () => import('./scolarite/pages/fiche-scolarite.page').then((m) => m.FicheScolaritePage),
          },
          {
            path: 'inscriptions/:inscription',
            title: 'Scolarité de l’élève',
            loadComponent: () => import('./scolarite/pages/fiche-scolarite.page').then((m) => m.FicheScolaritePage),
          },
          {
            path: 'classes',
            title: 'Paiements par classe',
            loadComponent: () => import('./scolarite/pages/classes-sco.page').then((m) => m.ClassesScoPage),
          },
          {
            path: 'journal',
            canActivate: [role('INTENDANT', 'ADMIN_ECOLE')],
            title: 'Journal de caisse',
            loadComponent: () => import('./scolarite/pages/journal.page').then((m) => m.JournalPage),
          },
          {
            path: 'frais',
            title: 'Frais et bourses',
            loadComponent: () => import('./scolarite/pages/frais.page').then((m) => m.FraisPage),
          },
        ],
      },
      {
        // Vie scolaire : en ligne, chargée à la demande
        path: 'vie-scolaire',
        canActivate: [role('SURVEILLANT', 'CENSEUR', 'ADMIN_ECOLE', 'SECRETARIAT'), module('VIE_SCOLAIRE')],
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
