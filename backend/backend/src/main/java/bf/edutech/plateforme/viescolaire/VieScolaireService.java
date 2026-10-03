package bf.edutech.plateforme.viescolaire;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.ContactsEleves;
import bf.edutech.plateforme.eleves.ContactsEleves.ContactEleve;
import bf.edutech.plateforme.eleves.ElevesService;
import bf.edutech.plateforme.eleves.EspaceParentService;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.StatutInscription;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.EtatAnnee;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Vie scolaire : incidents (retard, avertissement, blâme, exclusion temporaire) et convocations des
 * parents, avec SMS au contact prioritaire, historique de l'élève et synthèse par classe.
 * <ul>
 * <li>Surveillant et enseignant : retards et avertissements (l'enseignant, dans ses classes) ;</li>
 * <li>censeur et chef d'établissement : toutes les sanctions ;</li>
 * <li>un incident ne se supprime jamais : il s'annule avec un motif (direction, ou son auteur dans les 24 h).</li>
 * </ul>
 */
@Service
public class VieScolaireService {

    private static final String[] DIRECTION = { "CENSEUR", "ADMIN_ECOLE" };
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH'h'mm");

    public record SaisieIncident(TypeIncident type, LocalDate date, String motif, Integer minutesRetard,
            LocalDate debutExclusion, Integer joursExclusion, Boolean prevenirFamille) {
    }

    public record IncidentVue(UUID id, UUID inscriptionId, TypeIncident type, LocalDate date, String motif,
            Integer minutesRetard, LocalDate debutExclusion, LocalDate finExclusion, Integer joursExclusion,
            boolean famillePrevenue, Instant saisiLe, boolean annule, String motifAnnulation) {
    }

    public record ConvocationVue(UUID id, UUID inscriptionId, String nom, String prenoms, String classe,
            LocalDateTime rendezVous, String motif, StatutConvocation statut, String compteRendu, UUID incidentId) {
    }

    public record SyntheseVue(int retards, int minutesRetard, int avertissements, int blames, int exclusions,
            int joursExclusion, int convocations) {
    }

    public record HistoriqueVue(UUID inscriptionId, String nom, String prenoms, String classe, String annee,
            SyntheseVue synthese, List<IncidentVue> incidents, List<ConvocationVue> convocations) {
    }

    public record LigneClasseVue(UUID inscriptionId, String matricule, String nom, String prenoms,
            SyntheseVue synthese) {
    }

    private final IncidentRepository incidents;
    private final ConvocationRepository convocations;
    private final InscriptionsService inscriptions;
    private final ElevesService eleves;
    private final EspaceParentService espaceParent;
    private final EnseignantsService enseignants;
    private final AnneesService annees;
    private final ContactsEleves contacts;
    private final NotificationsService notifications;
    private final AuditService audit;
    private final Clock horloge;

    VieScolaireService(IncidentRepository incidents, ConvocationRepository convocations,
            InscriptionsService inscriptions, ElevesService eleves, EspaceParentService espaceParent,
            EnseignantsService enseignants, AnneesService annees, ContactsEleves contacts,
            NotificationsService notifications, AuditService audit, Clock horloge) {
        this.incidents = incidents;
        this.convocations = convocations;
        this.inscriptions = inscriptions;
        this.eleves = eleves;
        this.espaceParent = espaceParent;
        this.enseignants = enseignants;
        this.annees = annees;
        this.contacts = contacts;
        this.notifications = notifications;
        this.audit = audit;
        this.horloge = horloge;
    }

    // ------------------------------------------------------------------ incidents

    /** Enregistre un incident ; SMS à la famille (par défaut pour les sanctions, sur demande pour un retard). */
    @Transactional
    public IncidentVue signaler(UUID inscriptionId, SaisieIncident s) {
        UtilisateurConnecte.etablissementActif();
        InscriptionVue i = inscriptionEnCours(inscriptionId);
        if (s == null || s.type() == null) {
            throw new IllegalArgumentException("Indiquez le type d'incident");
        }
        TypeIncident type = s.type();
        boolean direction = UtilisateurConnecte.aUnRole(DIRECTION);
        if (type.reserveDirection() && !direction) {
            throw new AccesRefuseException("Le " + type.libelle() + " est prononcé par la direction");
        }
        if (!direction && !UtilisateurConnecte.aUnRole("SURVEILLANT")) {
            // Enseignant : seulement dans ses classes
            boolean enseigne = UtilisateurConnecte.idSiConnecte()
                    .map(u -> enseignants.enseigneDans(u, i.classeId())).orElse(false);
            if (!enseigne) {
                throw new AccesRefuseException("Vous n'enseignez pas dans la classe " + i.classeCode());
            }
        }
        LocalDate aujourdhui = LocalDate.now(horloge);
        LocalDate date = s.date() != null ? s.date() : aujourdhui;
        AnneeVue annee = annees.trouver(i.anneeId());
        if (date.isAfter(aujourdhui) || date.isBefore(annee.debut())) {
            throw new IllegalArgumentException("La date des faits est comprise entre le début de l'année et aujourd'hui");
        }
        String motif = texte(s.motif(), 300, "Le motif");
        Integer minutes = null;
        LocalDate debut = null;
        Integer jours = null;
        if (type == TypeIncident.RETARD) {
            if (s.minutesRetard() == null || s.minutesRetard() < 1 || s.minutesRetard() > 600) {
                throw new IllegalArgumentException("Indiquez la durée du retard (1 à 600 minutes)");
            }
            minutes = s.minutesRetard();
        } else if (s.minutesRetard() != null) {
            throw new IllegalArgumentException("La durée ne concerne que les retards");
        }
        if (type == TypeIncident.EXCLUSION_TEMPORAIRE) {
            if (s.joursExclusion() == null || s.joursExclusion() < 1 || s.joursExclusion() > 30) {
                throw new IllegalArgumentException("Indiquez la durée de l'exclusion (1 à 30 jours)");
            }
            debut = s.debutExclusion() != null ? s.debutExclusion() : date.plusDays(1);
            if (debut.isBefore(date) || debut.isAfter(aujourdhui.plusDays(30))) {
                throw new IllegalArgumentException("L'exclusion commence après les faits, dans les 30 jours");
            }
            if (debut.plusDays(s.joursExclusion() - 1L).isAfter(annee.fin())) {
                throw new IllegalArgumentException("L'exclusion se termine après la fin de l'année scolaire");
            }
            jours = s.joursExclusion();
        } else if (s.joursExclusion() != null || s.debutExclusion() != null) {
            throw new IllegalArgumentException("Les dates d'exclusion ne concernent que l'exclusion temporaire");
        }
        Incident incident = incidents.save(new Incident(inscriptionId, type, date, motif, minutes, debut, jours,
                UtilisateurConnecte.idSiConnecte().orElse(null), horloge.instant()));
        boolean prevenir = s.prevenirFamille() != null ? s.prevenirFamille() : type.famillePrevenueParDefaut();
        if (prevenir) {
            ContactEleve c = contacts.pourInscriptions(List.of(inscriptionId)).get(inscriptionId);
            if (c != null && c.telephone() != null) {
                notifications.planifierSms("incident:" + incident.getId(), c.telephone(), c.langueSms(),
                        messageIncident(incident, c, i.classeCode()));
                incident.famillePrevenue();
            }
        }
        audit.enregistrer("INCIDENT_SIGNALE", i.matricule(), Map.of("type", type.name()));
        return vue(incident);
    }

    /** Annule un incident saisi par erreur (direction, ou son auteur dans les 24 heures). */
    @Transactional
    public IncidentVue annulerIncident(UUID incidentId, String motif) {
        UtilisateurConnecte.etablissementActif();
        Incident incident = incidents.findById(incidentId)
                .orElseThrow(() -> new RessourceIntrouvableException("Incident introuvable"));
        boolean auteur = UtilisateurConnecte.idSiConnecte().map(u -> u.equals(incident.getSaisiPar())).orElse(false)
                && incident.getSaisiLe().isAfter(horloge.instant().minus(Duration.ofHours(24)));
        if (!UtilisateurConnecte.aUnRole(DIRECTION) && !auteur) {
            throw new AccesRefuseException("Seule la direction annule un incident (ou son auteur dans les 24 heures)");
        }
        anneeEnCours(inscriptions.trouver(incident.getInscriptionId()));
        if (!convocations.findByIncidentIdAndStatut(incidentId, StatutConvocation.PREVUE).isEmpty()) {
            throw new RegleMetierException("CONVOCATION_EN_COURS",
                    "Une convocation prévue porte sur cet incident : annulez-la d'abord");
        }
        incident.annuler(texte(motif, 300, "Le motif d'annulation"),
                UtilisateurConnecte.idSiConnecte().orElse(null), horloge.instant());
        if (notifications.annuler("incident:" + incidentId)) {
            incident.famillePasPrevenue();
        }
        audit.enregistrer("INCIDENT_ANNULE", incidentId.toString(), Map.of("type", incident.getType().name()));
        return vue(incident);
    }

    // ------------------------------------------------------------------ convocations

    /** Convoque le parent : SMS avec la date, l'heure et le motif. */
    @Transactional
    public ConvocationVue convoquer(UUID inscriptionId, LocalDateTime rendezVous, String motifSaisi, UUID incidentId) {
        UtilisateurConnecte.etablissementActif();
        InscriptionVue i = inscriptionEnCours(inscriptionId);
        if (rendezVous == null || !rendezVous.isAfter(LocalDateTime.now(horloge))
                || rendezVous.isAfter(LocalDateTime.now(horloge).plusMonths(3))) {
            throw new IllegalArgumentException("Le rendez-vous est à venir, dans les trois prochains mois");
        }
        String motif = texte(motifSaisi, 300, "Le motif");
        if (incidentId != null) {
            Incident incident = incidents.findById(incidentId)
                    .orElseThrow(() -> new RessourceIntrouvableException("Incident introuvable"));
            if (!incident.getInscriptionId().equals(inscriptionId) || incident.estAnnule()) {
                throw new IllegalArgumentException("Cet incident concerne un autre élève ou a été annulé");
            }
        }
        Convocation c = convocations.save(new Convocation(inscriptionId, incidentId, rendezVous.withSecond(0).withNano(0),
                motif, UtilisateurConnecte.idSiConnecte().orElse(null), horloge.instant()));
        ContactEleve contact = contacts.pourInscriptions(List.of(inscriptionId)).get(inscriptionId);
        if (contact != null && contact.telephone() != null) {
            notifications.planifierSms("convocation:" + c.getId(), contact.telephone(), contact.langueSms(),
                    notifications.nomEtablissement() + " : vous êtes convoqué(e) le "
                            + c.getRendezVous().format(JOUR) + " à " + c.getRendezVous().format(HEURE)
                            + " au sujet de " + contact.prenoms() + " " + contact.nom() + " (" + i.classeCode()
                            + ") : " + motif + ". Merci de vous présenter à l'établissement.");
        }
        audit.enregistrer("CONVOCATION", i.matricule(), Map.of("rendezVous", c.getRendezVous().toString()));
        return vue(c, i);
    }

    /** Clôt une convocation : honorée, non honorée ou annulée (la famille est prévenue d'une annulation). */
    @Transactional
    public ConvocationVue cloturer(UUID convocationId, StatutConvocation statut, String compteRendu) {
        UtilisateurConnecte.etablissementActif();
        Convocation c = convocations.findById(convocationId)
                .orElseThrow(() -> new RessourceIntrouvableException("Convocation introuvable"));
        if (statut == null) {
            throw new IllegalArgumentException("Indiquez HONOREE, NON_HONOREE ou ANNULEE");
        }
        String cr = compteRendu == null || compteRendu.isBlank() ? null : compteRendu.trim();
        if (cr != null && cr.length() > 500) {
            throw new IllegalArgumentException("Compte rendu : 500 caractères au plus");
        }
        InscriptionVue i = inscriptions.trouver(c.getInscriptionId());
        anneeEnCours(i);
        if (statut != StatutConvocation.ANNULEE && c.getRendezVous().isAfter(LocalDateTime.now(horloge))) {
            throw new RegleMetierException("RENDEZ_VOUS_A_VENIR",
                    "Le rendez-vous n'a pas encore eu lieu : seule une annulation est possible");
        }
        c.cloturer(statut, cr, UtilisateurConnecte.idSiConnecte().orElse(null), horloge.instant());
        boolean retire = notifications.annuler("convocation:" + convocationId);
        boolean parti = !retire && notifications.statut("convocation:" + convocationId)
                .filter(st -> st == NotificationsService.StatutNotification.ENVOYE
                        || st == NotificationsService.StatutNotification.EN_COURS).isPresent();
        if (statut == StatutConvocation.ANNULEE && parti) {
            // Le SMS de convocation est déjà parti : la famille est prévenue de l'annulation
            ContactEleve contact = contacts.pourInscriptions(List.of(i.id())).get(i.id());
            if (contact != null && contact.telephone() != null) {
                notifications.planifierSms("convocation-annulee:" + convocationId, contact.telephone(),
                        contact.langueSms(), notifications.nomEtablissement() + " : la convocation du "
                                + c.getRendezVous().format(JOUR) + " à " + c.getRendezVous().format(HEURE)
                                + " au sujet de " + contact.prenoms() + " " + contact.nom() + " est annulée.");
            }
        }
        audit.enregistrer("CONVOCATION_CLOSE", i.matricule(), Map.of("statut", statut.name()));
        return vue(c, i);
    }

    /** Agenda des convocations de l'établissement sur une période. */
    @Transactional(readOnly = true)
    public List<ConvocationVue> agenda(LocalDate du, LocalDate au) {
        UtilisateurConnecte.etablissementActif();
        if (du == null || au == null || au.isBefore(du) || du.plusDays(366).isBefore(au)) {
            throw new IllegalArgumentException("Période invalide (366 jours au plus)");
        }
        return convocations.findByRendezVousBetweenOrderByRendezVousAsc(du.atStartOfDay(), au.plusDays(1).atStartOfDay())
                .stream().map(c -> vue(c, inscriptions.trouver(c.getInscriptionId()))).toList();
    }

    // ------------------------------------------------------------------ consultation

    @Transactional(readOnly = true)
    public HistoriqueVue historique(UUID inscriptionId) {
        UtilisateurConnecte.etablissementActif();
        return historique(inscriptions.trouver(inscriptionId), true);
    }

    /** Historique de toutes les années de l'élève dans l'établissement (la plus récente d'abord). */
    @Transactional(readOnly = true)
    public List<HistoriqueVue> historiqueEleve(UUID eleveId) {
        UtilisateurConnecte.etablissementActif();
        return parAnnee(eleveId, true);
    }

    /** Pour le parent : incidents non annulés et convocations de son enfant. */
    @Transactional(readOnly = true)
    public List<HistoriqueVue> deMonEnfant(UUID eleveId) {
        if (!espaceParent.estMonEnfant(eleveId)) {
            throw new AccesRefuseException("Cet élève n'est pas rattaché à votre compte");
        }
        return parAnnee(eleveId, false);
    }

    /** Synthèse de la classe sur une période (élèves actifs et sortis). */
    @Transactional(readOnly = true)
    public List<LigneClasseVue> classe(UUID classeId, LocalDate du, LocalDate au) {
        UtilisateurConnecte.etablissementActif();
        if (du == null || au == null || au.isBefore(du)) {
            throw new IllegalArgumentException("Période invalide");
        }
        List<InscriptionVue> liste = inscriptions.listerParClasse(classeId, true);
        List<UUID> ids = liste.stream().map(InscriptionVue::id).toList();
        Map<UUID, List<Incident>> parEleve = ids.isEmpty() ? Map.of()
                : incidents.findByInscriptionIdInOrderByDateFaitsDescSaisiLeDesc(ids).stream()
                        .filter(x -> !x.estAnnule() && !x.getDateFaits().isBefore(du) && !x.getDateFaits().isAfter(au))
                        .collect(Collectors.groupingBy(Incident::getInscriptionId));
        Map<UUID, List<Convocation>> convParEleve = ids.isEmpty() ? Map.of()
                : convocations.findByInscriptionIdInOrderByRendezVousDesc(ids).stream()
                        .filter(c -> c.getStatut() != StatutConvocation.ANNULEE
                                && !c.getRendezVous().toLocalDate().isBefore(du)
                                && !c.getRendezVous().toLocalDate().isAfter(au))
                        .collect(Collectors.groupingBy(Convocation::getInscriptionId));
        return liste.stream().map(i -> new LigneClasseVue(i.id(), i.matricule(), i.nom(), i.prenoms(),
                synthese(parEleve.getOrDefault(i.id(), List.of()), convParEleve.getOrDefault(i.id(), List.of()))))
                .toList();
    }

    // ------------------------------------------------------------------

    private List<HistoriqueVue> parAnnee(UUID eleveId, boolean complet) {
        List<InscriptionVue> liste = new ArrayList<>(eleves.trouver(eleveId).inscriptions());
        liste.sort(Comparator.comparing(InscriptionVue::inscritLe).reversed());
        return liste.stream().map(i -> historique(i, complet)).toList();
    }

    private HistoriqueVue historique(InscriptionVue i, boolean complet) {
        List<Incident> liste = incidents.findByInscriptionIdInOrderByDateFaitsDescSaisiLeDesc(List.of(i.id()));
        List<Convocation> convs = convocations.findByInscriptionIdInOrderByRendezVousDesc(List.of(i.id()));
        List<Incident> actifs = liste.stream().filter(x -> !x.estAnnule()).toList();
        List<Incident> visibles = complet ? liste : actifs;
        List<Convocation> convsVisibles = convs.stream()
                .filter(c -> complet || c.getStatut() != StatutConvocation.ANNULEE).toList();
        return new HistoriqueVue(i.id(), i.nom(), i.prenoms(), i.classeCode(), i.anneeLibelle(),
                synthese(actifs, convs.stream().filter(c -> c.getStatut() != StatutConvocation.ANNULEE).toList()),
                visibles.stream().map(VieScolaireService::vue).toList(),
                convsVisibles.stream().map(c -> {
                    ConvocationVue v = vue(c, i);
                    return complet ? v : new ConvocationVue(v.id(), v.inscriptionId(), v.nom(), v.prenoms(),
                            v.classe(), v.rendezVous(), v.motif(), v.statut(), null, v.incidentId());
                }).toList());
    }

    private static SyntheseVue synthese(List<Incident> liste, List<Convocation> convs) {
        int retards = 0;
        int minutes = 0;
        int avertissements = 0;
        int blames = 0;
        int exclusions = 0;
        int jours = 0;
        for (Incident x : liste) {
            switch (x.getType()) {
                case RETARD -> {
                    retards++;
                    minutes += x.getMinutesRetard();
                }
                case AVERTISSEMENT -> avertissements++;
                case BLAME -> blames++;
                case EXCLUSION_TEMPORAIRE -> {
                    exclusions++;
                    jours += x.getJoursExclusion();
                }
            }
        }
        return new SyntheseVue(retards, minutes, avertissements, blames, exclusions, jours, convs.size());
    }

    private InscriptionVue inscriptionEnCours(UUID inscriptionId) {
        InscriptionVue i = inscriptions.trouver(inscriptionId);
        if (i.statut() != StatutInscription.ACTIVE) {
            throw new RegleMetierException("INSCRIPTION_TERMINEE", "L'élève a quitté l'établissement");
        }
        anneeEnCours(i);
        return i;
    }

    /** L'historique d'une année close ou archivée ne change plus. */
    private void anneeEnCours(InscriptionVue i) {
        if (annees.trouver(i.anneeId()).etat() != EtatAnnee.ACTIVE) {
            throw new RegleMetierException("ANNEE_NON_ACTIVE", "L'année " + i.anneeLibelle() + " n'est pas en cours");
        }
    }

    private String messageIncident(Incident x, ContactEleve c, String classe) {
        String eleve = c.prenoms() + " " + c.nom() + " (" + classe + ")";
        String debut = notifications.nomEtablissement() + " : ";
        return switch (x.getType()) {
            case RETARD -> debut + eleve + " est arrivé(e) en retard de " + x.getMinutesRetard() + " min le "
                    + x.getDateFaits().format(JOUR) + ".";
            case AVERTISSEMENT, BLAME -> debut + x.getType().libelle() + " pour " + eleve + " le "
                    + x.getDateFaits().format(JOUR) + " : " + x.getMotif() + ".";
            case EXCLUSION_TEMPORAIRE -> debut + "exclusion temporaire de " + eleve + " pour "
                    + x.getJoursExclusion() + " jour(s), du " + x.getDebutExclusion().format(JOUR) + " au "
                    + x.finExclusion().format(JOUR) + " : " + x.getMotif() + ".";
        };
    }

    private static String texte(String valeur, int max, String nom) {
        if (valeur == null || valeur.isBlank() || valeur.trim().length() > max) {
            throw new IllegalArgumentException(nom + " est obligatoire (" + max + " caractères au plus)");
        }
        return valeur.trim().replaceAll("\\s+", " ");
    }

    private static IncidentVue vue(Incident x) {
        return new IncidentVue(x.getId(), x.getInscriptionId(), x.getType(), x.getDateFaits(), x.getMotif(),
                x.getMinutesRetard(), x.getDebutExclusion(), x.finExclusion(), x.getJoursExclusion(),
                x.isFamillePrevenue(), x.getSaisiLe(), x.estAnnule(), x.getMotifAnnulation());
    }

    private static ConvocationVue vue(Convocation c, InscriptionVue i) {
        return new ConvocationVue(c.getId(), c.getInscriptionId(), i.nom(), i.prenoms(), i.classeCode(),
                c.getRendezVous(), c.getMotif(), c.getStatut(), c.getCompteRendu(), c.getIncidentId());
    }
}
