package bf.edutech.plateforme.absences;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import bf.edutech.plateforme.absences.Vues.AccuseAppel;
import bf.edutech.plateforme.absences.Vues.AppelVue;
import bf.edutech.plateforme.absences.Vues.DonneesAppel;
import bf.edutech.plateforme.absences.Vues.Marque;
import bf.edutech.plateforme.absences.Vues.MarqueVue;
import bf.edutech.plateforme.eleves.ContactsEleves;
import bf.edutech.plateforme.eleves.ContactsEleves.ContactEleve;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.EtatAnnee;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.referentiel.Sexe;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Appels : synchronisation par lot depuis les appareils (hors connexion),
 * modification, consultation.
 * <p>
 * Chaque appel d'un lot est enregistré dans sa propre transaction : un appel
 * refusé n'empêche jamais les autres de passer, et l'appareil reçoit un accusé
 * par appel. L'identifiant généré par l'appareil rend l'envoi idempotent.
 * Les SMS aux familles sont mis en file dans la même transaction (outbox).
 */
@Service
public class AppelsService {

    static final int LOT_MAX = 50;
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH'h'mm");

    private final AppelRepository appels;
    private final AbsenceRepository absences;
    private final ClassesService classes;
    private final AnneesService annees;
    private final InscriptionsService inscriptions;
    private final ContactsEleves contacts;
    private final NotificationsService notifications;
    private final Autorisations autorisations;
    private final AuditService audit;
    private final Clock horloge;
    private final TransactionTemplate transaction;

    AppelsService(AppelRepository appels, AbsenceRepository absences, ClassesService classes, AnneesService annees,
            InscriptionsService inscriptions, ContactsEleves contacts, NotificationsService notifications,
            Autorisations autorisations, AuditService audit, Clock horloge,
            PlatformTransactionManager gestionnaireTransactions) {
        this.appels = appels;
        this.absences = absences;
        this.classes = classes;
        this.annees = annees;
        this.inscriptions = inscriptions;
        this.contacts = contacts;
        this.notifications = notifications;
        this.autorisations = autorisations;
        this.audit = audit;
        this.horloge = horloge;
        this.transaction = new TransactionTemplate(gestionnaireTransactions);
    }

    /** Enregistre un lot d'appels venant d'un appareil ; un accusé par appel, dans l'ordre reçu. */
    public List<AccuseAppel> synchroniser(List<DonneesAppel> lot) {
        UtilisateurConnecte.etablissementActif();
        if (lot.size() > LOT_MAX) {
            throw new IllegalArgumentException("Un lot contient au plus " + LOT_MAX + " appels");
        }
        List<AccuseAppel> accuses = new ArrayList<>();
        for (DonneesAppel donnees : lot) {
            try {
                accuses.add(transaction.execute(etat -> appliquer(donnees)));
            } catch (RegleMetierException e) {
                accuses.add(refus(donnees, e.getCode(), e.getMessage()));
            } catch (AccesRefuseException e) {
                accuses.add(refus(donnees, "NON_AUTORISE", e.getMessage()));
            } catch (RessourceIntrouvableException e) {
                accuses.add(refus(donnees, "INTROUVABLE", e.getMessage()));
            } catch (IllegalArgumentException e) {
                accuses.add(refus(donnees, "DONNEES_INVALIDES", e.getMessage()));
            } catch (DataIntegrityViolationException e) {
                accuses.add(refus(donnees, "CONFLIT", "Appel en conflit avec un appel déjà enregistré"));
            }
        }
        return accuses;
    }

    /** Remplace la liste des absents et retards d'un appel existant. */
    @Transactional
    public AccuseAppel modifier(UUID appelId, List<Marque> marques) {
        Appel appel = charger(appelId);
        return appliquer(new DonneesAppel(appel.getId(), appel.getClasseId(), appel.getMatiereId(),
                appel.getDateAppel(), appel.getHeureDebut(), appel.getHeureFin(), appel.getSaisiLe(), marques));
    }

    @Transactional(readOnly = true)
    public AppelVue trouver(UUID appelId) {
        Appel appel = charger(appelId);
        autorisations.verifierConsultationClasse(appel.getClasseId());
        return vue(appel, nomsDeLaClasse(appel.getClasseId()));
    }

    /** Appels d'une classe pour une journée. */
    @Transactional(readOnly = true)
    public List<AppelVue> duJour(UUID classeId, LocalDate date) {
        UtilisateurConnecte.etablissementActif();
        autorisations.verifierConsultationClasse(classeId);
        Map<UUID, InscriptionVue> noms = nomsDeLaClasse(classeId);
        return appels.findByClasseIdAndDateAppelOrderByHeureDebutAsc(classeId, date).stream()
                .map(a -> vue(a, noms))
                .toList();
    }

    // ------------------------------------------------------------------

    /** Enregistre ou modifie un appel dans la transaction courante. */
    private AccuseAppel appliquer(DonneesAppel d) {
        UUID moi = UtilisateurConnecte.id();
        verifierDonnees(d);
        ClasseVue classe = classes.trouver(d.classeId());
        AnneeVue annee = annees.trouver(classe.anneeId());
        LocalDate aujourdhui = LocalDate.now(horloge);
        if (annee.etat() != EtatAnnee.ACTIVE) {
            throw new RegleMetierException("ANNEE_NON_ACTIVE", "L'année " + annee.libelle() + " n'est pas en cours");
        }
        if (d.date().isBefore(annee.debut()) || d.date().isAfter(annee.fin())) {
            throw new RegleMetierException("HORS_ANNEE", "La date de l'appel est en dehors de l'année scolaire");
        }
        if (d.date().isAfter(aujourdhui)) {
            throw new RegleMetierException("DATE_FUTURE", "Un appel ne peut pas porter sur une date future");
        }
        boolean vieScolaire = autorisations.vieScolaire();
        if (!vieScolaire && !autorisations.enseigneDans(classe.id())) {
            throw new RegleMetierException("NON_AFFECTE", "Vous n'enseignez pas dans la classe " + classe.code());
        }
        if (d.matiereId() != null && classes.matieres(classe.id()).stream()
                .noneMatch(m -> m.matiereId().equals(d.matiereId()))) {
            throw new RegleMetierException("MATIERE_HORS_CLASSE", "Cette matière n'est pas enseignée dans la classe");
        }

        Appel appel = appels.findById(d.idClient()).orElse(null);
        if (appel != null && (!appel.getClasseId().equals(classe.id()) || !appel.getDateAppel().equals(d.date())
                || !appel.getHeureDebut().equals(d.heureDebut()))) {
            throw new RegleMetierException("IDENTIFIANT_DEJA_UTILISE",
                    "Cet identifiant correspond à un autre appel (classe, date ou heure différente)");
        }
        Map<UUID, Absence> actuelles = appel == null ? new HashMap<>()
                : absences.findByAppelId(appel.getId()).stream()
                        .collect(Collectors.toMap(Absence::getInscriptionId, Function.identity()));

        // Élèves marqués : inscrits (actifs) dans la classe, ou déjà marqués sur cet appel
        // (un élève parti depuis garde ses absences passées)
        Map<UUID, InscriptionVue> inscrits = nomsDeLaClasse(classe.id());
        Map<UUID, Marque> retenues = new LinkedHashMap<>();
        List<UUID> ignorees = new ArrayList<>();
        for (Marque m : d.marques() != null ? d.marques() : List.<Marque>of()) {
            if (m == null || m.inscriptionId() == null || m.type() == null) {
                throw new IllegalArgumentException("Chaque élève marqué doit avoir une inscription et un type");
            }
            if (m.minutesRetard() != null && (m.minutesRetard() < 1 || m.minutesRetard() > 600)) {
                throw new IllegalArgumentException("Minutes de retard invalides");
            }
            if (inscrits.containsKey(m.inscriptionId()) || actuelles.containsKey(m.inscriptionId())) {
                retenues.put(m.inscriptionId(), m);
            } else {
                ignorees.add(m.inscriptionId());
            }
        }

        // Différences avec ce qui est enregistré
        List<Marque> aCreer = new ArrayList<>();
        List<Marque> aModifier = new ArrayList<>();
        for (Marque m : retenues.values()) {
            Absence a = actuelles.get(m.inscriptionId());
            if (a == null) {
                aCreer.add(m);
            } else if (a.getType() != m.type() || !Objects.equals(minutes(a), minutes(m))) {
                aModifier.add(m);
            }
        }
        // Retirées : absences d'élèves toujours dans la classe, que l'appel ne cite plus
        List<Absence> aRetirer = actuelles.values().stream()
                .filter(a -> !retenues.containsKey(a.getInscriptionId()) && inscrits.containsKey(a.getInscriptionId()))
                .toList();

        StatutAccuse statut;
        if (appel == null) {
            appels.findByClasseIdAndDateAppelAndHeureDebut(classe.id(), d.date(), d.heureDebut()).ifPresent(autre -> {
                throw new RegleMetierException("CRENEAU_DEJA_FAIT",
                        "L'appel de ce créneau a déjà été fait depuis un autre appareil");
            });
            appel = appels.saveAndFlush(new Appel(d.idClient(), classe.id(), d.matiereId(), d.date(), d.heureDebut(),
                    d.heureFin(), moi, d.saisiLe() != null ? d.saisiLe() : horloge.instant(), horloge.instant()));
            statut = StatutAccuse.ENREGISTRE;
        } else {
            boolean enteteModifiee = !appel.getHeureFin().equals(d.heureFin())
                    || !Objects.equals(appel.getMatiereId(), d.matiereId());
            if (!enteteModifiee && aCreer.isEmpty() && aModifier.isEmpty() && aRetirer.isEmpty()) {
                return accuse(d.idClient(), StatutAccuse.DEJA_RECU, retenues, ignorees);
            }
            if (!vieScolaire && !(appel.getFaitPar().equals(moi) && appel.getDateAppel().equals(aujourdhui))) {
                throw new RegleMetierException("APPEL_CLOS", "Après le jour de l'appel, seule la vie scolaire "
                        + "(surveillant, censeur) peut le modifier");
            }
            appel.corriger(d.heureFin(), d.matiereId());
            appel.marquerModifie(moi, horloge.instant());
            statut = StatutAccuse.MODIFIE;
        }

        // Application des différences et file des SMS (outbox)
        List<Absence> nouvellesAbsences = new ArrayList<>();
        for (Marque m : aCreer) {
            Absence creee = absences.save(new Absence(appel.getId(), m.inscriptionId(), m.type(), court(m)));
            if (creee.getType() == TypeAbsence.ABSENCE) {
                nouvellesAbsences.add(creee);
            }
        }
        for (Marque m : aModifier) {
            Absence existante = actuelles.get(m.inscriptionId());
            TypeAbsence avant = existante.getType();
            existante.definir(m.type(), court(m));
            if (avant == TypeAbsence.RETARD && m.type() == TypeAbsence.ABSENCE) {
                nouvellesAbsences.add(existante);
            } else if (avant == TypeAbsence.ABSENCE && m.type() == TypeAbsence.RETARD) {
                notifications.annuler(cle(existante));
            }
        }
        for (Absence retiree : aRetirer) { // élèves finalement présents
            notifications.annuler(cle(retiree));
            absences.delete(retiree);
        }
        prevenirLesFamilles(nouvellesAbsences, appel, classe);
        audit.enregistrer(statut == StatutAccuse.ENREGISTRE ? "APPEL_ENREGISTRE" : "APPEL_MODIFIE",
                classe.code() + " " + d.date() + " " + d.heureDebut(),
                Map.of("appel", appel.getId(), "marques", retenues.size()));
        return accuse(d.idClient(), statut, retenues, ignorees);
    }

    private void prevenirLesFamilles(List<Absence> nouvelles, Appel appel, ClasseVue classe) {
        if (nouvelles.isEmpty()) {
            return;
        }
        Map<UUID, ContactEleve> parInscription = contacts
                .pourInscriptions(nouvelles.stream().map(Absence::getInscriptionId).toList());
        String etablissement = notifications.nomEtablissement();
        for (Absence absence : nouvelles) {
            ContactEleve contact = parInscription.get(absence.getInscriptionId());
            if (contact == null || contact.telephone() == null) {
                continue; // pas de contact prioritaire : l'absence reste visible dans l'application
            }
            String message = etablissement + " : " + contact.prenoms() + " " + contact.nom() + " (" + classe.code()
                    + ") " + (contact.sexe() == Sexe.F ? "était absente" : "était absent") + " le "
                    + appel.getDateAppel().format(JOUR) + " de " + appel.getHeureDebut().format(HEURE) + " à "
                    + appel.getHeureFin().format(HEURE) + ". Merci de justifier cette absence auprès de l'établissement.";
            notifications.planifierSms(cle(absence), contact.telephone(), contact.langueSms(), message);
        }
    }

    private static Integer minutes(Absence a) {
        return a.getMinutesRetard() != null ? a.getMinutesRetard().intValue() : null;
    }

    private static Integer minutes(Marque m) {
        return m.type() == TypeAbsence.RETARD ? m.minutesRetard() : null;
    }

    private static Short court(Marque m) {
        Integer minutes = minutes(m);
        return minutes != null ? minutes.shortValue() : null;
    }

    private static void verifierDonnees(DonneesAppel d) {
        if (d.idClient() == null || d.classeId() == null || d.date() == null || d.heureDebut() == null
                || d.heureFin() == null) {
            throw new IllegalArgumentException("Identifiant, classe, date et horaires de l'appel sont obligatoires");
        }
        if (!d.heureFin().isAfter(d.heureDebut())) {
            throw new IllegalArgumentException("L'heure de fin doit suivre l'heure de début");
        }
    }

    private static String cle(Absence absence) {
        return "absence:" + absence.getId();
    }

    private static AccuseAppel accuse(UUID idClient, StatutAccuse statut, Map<UUID, Marque> retenues,
            List<UUID> ignorees) {
        int absents = (int) retenues.values().stream().filter(m -> m.type() == TypeAbsence.ABSENCE).count();
        return new AccuseAppel(idClient, statut, null, null, absents, retenues.size() - absents, ignorees);
    }

    private static AccuseAppel refus(DonneesAppel d, String code, String message) {
        return new AccuseAppel(d.idClient(), StatutAccuse.REFUSE, code, message, 0, 0, List.of());
    }

    private Appel charger(UUID appelId) {
        UtilisateurConnecte.etablissementActif();
        return appels.findById(appelId).orElseThrow(() -> new RessourceIntrouvableException("Appel introuvable"));
    }

    /** Élèves inscrits (actifs) de la classe, par inscription. */
    private Map<UUID, InscriptionVue> nomsDeLaClasse(UUID classeId) {
        Map<UUID, InscriptionVue> parId = new HashMap<>();
        inscriptions.listerParClasse(classeId, false).forEach(i -> parId.put(i.id(), i));
        return parId;
    }

    private AppelVue vue(Appel appel, Map<UUID, InscriptionVue> noms) {
        List<MarqueVue> marques = absences.findByAppelId(appel.getId()).stream()
                .map(a -> {
                    InscriptionVue i = noms.get(a.getInscriptionId());
                    return new MarqueVue(a.getInscriptionId(), i != null ? i.nom() : null,
                            i != null ? i.prenoms() : null, a.getType(),
                            a.getMinutesRetard() != null ? a.getMinutesRetard().intValue() : null);
                })
                .sorted(Comparator.comparing((MarqueVue m) -> m.nom() == null ? "" : m.nom())
                        .thenComparing(m -> m.prenoms() == null ? "" : m.prenoms()))
                .toList();
        return new AppelVue(appel.getId(), appel.getClasseId(), appel.getMatiereId(), appel.getDateAppel(),
                appel.getHeureDebut(), appel.getHeureFin(), appel.getFaitPar(), appel.getSaisiLe(), appel.getRecuLe(),
                appel.getModifieLe(), marques);
    }
}
