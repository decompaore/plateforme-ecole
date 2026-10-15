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

/** Comptes de direction, administrateurs pays et super administrateur (v0.37). */
const pilote: CanActivateFn = () => inject(SessionService).pilote() || inject(Router).parseUrl('/');

/** Besoins et dotations (v0.48.1) : administrateur pays et comptes de direction, pas le super administrateur. */
const territoire: CanActivateFn = () => inject(SessionService).gereMoyens() || inject(Router).parseUrl('/');

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
        // Appréciations du bulletin (v0.43) : en ligne uniquement, chargées à la demande
        path: 'appreciations/:classeId/:matiereId',
        canActivate: [role('ENSEIGNANT')],
        title: 'Appréciations',
        loadComponent: () => import('./bulletins/pages/appreciations.page').then((m) => m.AppreciationsPage),
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
        // Messagerie de l'établissement (v0.46) : boîte de réception de chacun, envoi par le chef d'établissement
        path: 'messages',
        title: 'Messages',
        loadComponent: () => import('./messagerie/pages/messages.page').then((m) => m.MessagesPage),
      },
      {
        // Tableau d'affichage (v0.47.1) : documents signés affichés par le secrétariat ; mes demandes administratives
        path: 'affichage',
        title: "Tableau d'affichage",
        loadComponent: () => import('./pages/affichage.page').then((m) => m.AffichagePage),
      },
      {
        // Secrétariat (v0.47.1) : courrier, documents, demandes du personnel, accueil, réunions, archives, certificats
        path: 'secretariat',
        canActivate: [role('ADMIN_ECOLE', 'SECRETARIAT', 'CENSEUR', 'CHEF_TRAVAUX', 'INTENDANT', 'SURVEILLANT', 'ENSEIGNANT')],
        children: [
          {
            path: '',
            pathMatch: 'full',
            redirectTo: 'courrier',
          },
          {
            path: 'courrier',
            title: 'Courrier',
            loadComponent: () => import('./secretariat/pages/courrier.page').then((m) => m.CourrierPage),
          },
          {
            path: 'documents',
            canActivate: [role('ADMIN_ECOLE', 'SECRETARIAT')],
            title: 'Documents administratifs',
            loadComponent: () => import('./secretariat/pages/documents.page').then((m) => m.DocumentsPage),
          },
          {
            path: 'demandes',
            canActivate: [role('ADMIN_ECOLE', 'SECRETARIAT')],
            title: 'Demandes du personnel',
            loadComponent: () => import('./secretariat/pages/demandes.page').then((m) => m.DemandesPage),
          },
          {
            path: 'accueil',
            canActivate: [role('ADMIN_ECOLE', 'SECRETARIAT')],
            title: 'Accueil et téléphone',
            loadComponent: () => import('./secretariat/pages/accueil-public.page').then((m) => m.AccueilPublicPage),
          },
          {
            path: 'reunions',
            canActivate: [role('ADMIN_ECOLE', 'SECRETARIAT', 'CENSEUR', 'CHEF_TRAVAUX')],
            title: 'Réunions',
            loadComponent: () => import('./secretariat/pages/reunions.page').then((m) => m.ReunionsPage),
          },
          {
            path: 'archives',
            canActivate: [role('ADMIN_ECOLE', 'SECRETARIAT')],
            title: 'Archives',
            loadComponent: () => import('./secretariat/pages/archives.page').then((m) => m.ArchivesPage),
          },
          {
            path: 'eleves',
            canActivate: [role('ADMIN_ECOLE', 'SECRETARIAT', 'CENSEUR')],
            title: 'Certificats et examens',
            loadComponent: () => import('./secretariat/pages/eleves-secretariat.page').then((m) => m.ElevesSecretariatPage),
          },
        ],
      },
      {
        // Signature numérisée imprimée devant les matières visées (v0.44) : en ligne uniquement
        path: 'signature',
        title: 'Ma signature',
        loadComponent: () => import('./pages/signature.page').then((m) => m.SignaturePage),
      },
      {
        path: 'appareils',
        title: 'Mes appareils',
        loadComponent: () => import('./pages/appareils.page').then((m) => m.AppareilsPage),
      },
      {
        // Compte de direction (v0.39) : adoption de son ressort, en lecture
        path: 'pilotage/adoption',
        canActivate: [pilote],
        title: 'Adoption',
        loadComponent: () => import('./admin/pages/adoption-plateforme.page').then((m) => m.AdoptionPlateformePage),
      },
      {
        // Besoins remontés et dotations (v0.48) : directions et niveau central
        path: 'pilotage/besoins',
        canActivate: [territoire],
        title: 'Besoins des établissements',
        loadComponent: () => import('./moyens/pages/besoins-pilotage.page').then((m) => m.BesoinsPilotagePage),
      },
      {
        path: 'pilotage/dotations',
        canActivate: [territoire],
        title: 'Dotations',
        loadComponent: () => import('./moyens/pages/dotations-pilotage.page').then((m) => m.DotationsPilotagePage),
      },
      {
        // État annuel des besoins de l'établissement et dotations reçues (v0.48)
        path: 'besoins',
        canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'INTENDANT')],
        title: 'Besoins et dotations',
        loadComponent: () => import('./moyens/pages/besoins.page').then((m) => m.BesoinsPage),
      },
      {
        path: 'pilotage',
        canActivate: [pilote],
        title: 'Pilotage',
        loadComponent: () => import('./pilotage/pilotage.page').then((m) => m.PilotagePage),
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
            // L'intendance inscrit les élèves (v0.47) et consulte les statistiques
            redirectTo: () => (inject(SessionService).aLeRole('ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT') ? 'classes' : 'eleves'),
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
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT', 'INTENDANT')],
            title: 'Élèves',
            loadComponent: () => import('./admin/pages/eleves.page').then((m) => m.ElevesPage),
          },
          {
            path: 'evaluations',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR')],          // v0.47.1 : plus le secrétariat
            title: 'Suivi des évaluations',
            loadComponent: () => import('./admin/pages/suivi-evaluations.page').then((m) => m.SuiviEvaluationsPage),
          },
          {
            path: 'bulletins',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR')],          // v0.47.1 : plus le secrétariat
            title: 'Bulletins',
            loadComponent: () => import('./bulletins/pages/bulletins.page').then((m) => m.BulletinsPage),
          },
          {
            // Fin d'année (v0.45) : conseils, année suivante, plan de passage, clôture
            path: 'fin-annee',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT')],
            title: "Fin d'année",
            loadComponent: () => import('./fin-annee/pages/fin-annee.page').then((m) => m.FinAnneePage),
          },
          {
            path: 'fin-annee/classes/:classeId',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT')],
            title: "Conseil de fin d'année",
            loadComponent: () => import('./fin-annee/pages/conseil-fin-annee.page').then((m) => m.ConseilFinAnneePage),
          },
          {
            // Taux de réussite aux examens nationaux (v0.46)
            path: 'examens',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR')],
            title: 'Examens',
            loadComponent: () => import('./fin-annee/pages/examens.page').then((m) => m.ExamensPage),
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
            // Visa mensuel des cahiers de textes (v0.47)
            path: 'cahiers',
            canActivate: [role('ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX')],
            title: 'Cahiers de textes',
            loadComponent: () => import('./progression/pages/cahiers-visas.page').then((m) => m.CahiersVisasPage),
          },
          {
            // Interpellation des enseignants en retard (v0.48.1) : direction et enseignant
            path: 'interpellations',
            title: 'Interpellations',
            loadComponent: () => import('./progression/pages/interpellations.page').then((m) => m.InterpellationsPage),
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
            // Travaux pratiques (v0.47.2) : séances, sécurité, production
            path: 'mes-tp',
            canActivate: [role('ENSEIGNANT')],
            title: 'Mes travaux pratiques',
            loadComponent: () => import('./ateliers/pages/mes-tp.page').then((m) => m.MesTpPage),
          },
          {
            path: 'planning',
            canActivate: [role('ADMIN_ECOLE', 'CHEF_TRAVAUX')],
            title: 'Planning des travaux pratiques',
            loadComponent: () => import('./ateliers/pages/planning-tp.page').then((m) => m.PlanningTpPage),
          },
          {
            path: ':atelierId/travaux-pratiques',
            title: 'Travaux pratiques',
            loadComponent: () => import('./ateliers/pages/travaux-pratiques.page').then((m) => m.TravauxPratiquesPage),
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
          {
            path: 'virements',
            canActivate: [role('INTENDANT', 'ADMIN_ECOLE')],
            title: 'Virements déclarés',
            loadComponent: () => import('./scolarite/pages/virements.page').then((m) => m.VirementsPage),
          },
          {
            path: 'moyens',
            title: 'Moyens de paiement',
            loadComponent: () => import('./scolarite/pages/moyens-paiement.page').then((m) => m.MoyensPaiementPage),
          },
        ],
      },
      {
        // Vie scolaire : en ligne, chargée à la demande
        path: 'vie-scolaire',
        // Le CPE (rôle SURVEILLANT) saisit ; la direction, le chef des travaux et le secrétariat consultent (v0.47)
        canActivate: [role('SURVEILLANT', 'CENSEUR', 'CHEF_TRAVAUX', 'ADMIN_ECOLE', 'SECRETARIAT'), module('VIE_SCOLAIRE')],
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
