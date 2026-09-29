package bf.edutech.plateforme.scolarite;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.ContactsEleves;
import bf.edutech.plateforme.eleves.ContactsEleves.ContactEleve;
import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.eleves.StatutInscription;
import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.EtatAnnee;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.scolarite.Situations.Situation;
import bf.edutech.plateforme.scolarite.Vues.ResultatRelancesVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Relance par SMS des familles en retard : seulement la part famille échue
 * (jamais la part d'un organisme), élèves encore inscrits, pas plus d'une relance
 * par élève pendant le délai fixé dans les paramètres (7 jours par défaut).
 */
@Service
public class RelancesService {

    private final Situations situations;
    private final RelanceRepository relances;
    private final ParametresScolariteService parametres;
    private final InscriptionsService inscriptions;
    private final ClassesService classes;
    private final AnneesService annees;
    private final ContactsEleves contacts;
    private final NotificationsService notifications;
    private final AuditService audit;
    private final Clock horloge;

    RelancesService(Situations situations, RelanceRepository relances, ParametresScolariteService parametres,
            InscriptionsService inscriptions, ClassesService classes, AnneesService annees, ContactsEleves contacts,
            NotificationsService notifications, AuditService audit, Clock horloge) {
        this.situations = situations;
        this.relances = relances;
        this.parametres = parametres;
        this.inscriptions = inscriptions;
        this.classes = classes;
        this.annees = annees;
        this.contacts = contacts;
        this.notifications = notifications;
        this.audit = audit;
        this.horloge = horloge;
    }

    /** Relance les familles d'une classe, ou de toutes les classes de l'année active si {@code classeId} est absent. */
    @Transactional
    public ResultatRelancesVue relancer(UUID classeId) {
        UtilisateurConnecte.etablissementActif();
        List<ClasseVue> cibles = classeId != null ? List.of(classes.trouver(classeId))
                : classes.lister(annees.active().id());
        return relancer(cibles, classeId != null ? cibles.get(0).code() : "toutes les classes");
    }

    /**
     * Relance automatique (tâche du lundi) : toutes les classes de l'année active, si l'établissement
     * n'a pas désactivé les relances automatiques. Rien s'il n'y a pas d'année active.
     */
    @Transactional
    public Optional<ResultatRelancesVue> relancerSiAutomatique() {
        UtilisateurConnecte.etablissementActif();
        if (!parametres.valeurs().relancesAutomatiques()) {
            return Optional.empty();
        }
        Optional<AnneeVue> active = annees.lister().stream().filter(a -> a.etat() == EtatAnnee.ACTIVE).findFirst();
        return active.map(a -> relancer(classes.lister(a.id()), "relance automatique"));
    }

    private ResultatRelancesVue relancer(List<ClasseVue> cibles, String libelle) {
        List<InscriptionVue> liste = new ArrayList<>();
        cibles.forEach(c -> inscriptions.listerParClasse(c.id(), false).stream()
                .filter(i -> i.statut() == StatutInscription.ACTIVE).forEach(liste::add));
        List<Situation> enRetard = situations.calculer(liste).values().stream()
                .filter(s -> s.resultat().retardFamille() > 0).toList();
        if (enRetard.isEmpty()) {
            return new ResultatRelancesVue(0, 0, 0);
        }
        List<UUID> ids = enRetard.stream().map(s -> s.inscription().id()).toList();
        Instant maintenant = horloge.instant();
        Instant limite = maintenant.minus(Duration.ofDays(parametres.valeurs().delaiRelanceJours()));
        Map<UUID, Instant> dernieres = new HashMap<>();
        relances.findByInscriptionIdIn(ids).forEach(r -> dernieres.merge(r.getInscriptionId(), r.getEnvoyeLe(),
                (a, b) -> a.isAfter(b) ? a : b));
        Map<UUID, ContactEleve> parInscription = contacts.pourInscriptions(ids);
        String etablissement = notifications.nomEtablissement();
        UUID moi = UtilisateurConnecte.idSiConnecte().orElse(null);
        LocalDate jour = LocalDate.now(horloge);
        int envoyees = 0;
        int sansContact = 0;
        int dejaRelancees = 0;
        for (Situation s : enRetard) {
            InscriptionVue i = s.inscription();
            Instant derniere = dernieres.get(i.id());
            if (derniere != null && derniere.isAfter(limite)) {
                dejaRelancees++;
                continue;
            }
            ContactEleve contact = parInscription.get(i.id());
            if (contact == null || contact.telephone() == null) {
                sansContact++;
                continue;
            }
            long retard = s.resultat().retardFamille();
            notifications.planifierSms("relance:" + i.id() + ":" + jour, contact.telephone(), contact.langueSms(),
                    etablissement + " : la scolarité de " + i.prenoms() + " " + i.nom() + " (" + i.classeCode()
                            + ") présente un retard de " + Montants.fcfa(retard)
                            + ". Merci de régulariser auprès de l'intendance. Reste total : "
                            + Montants.fcfa(s.resultat().resteFamille()) + ".");
            relances.save(new Relance(i.id(), retard, moi, maintenant));
            envoyees++;
        }
        audit.enregistrer("RELANCES_ENVOYEES", libelle,
                Map.of("envoyees", envoyees, "sansContact", sansContact, "dejaRelancees", dejaRelancees));
        return new ResultatRelancesVue(envoyees, sansContact, dejaRelancees);
    }
}
