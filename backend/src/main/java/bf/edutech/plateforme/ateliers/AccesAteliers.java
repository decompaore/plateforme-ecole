package bf.edutech.plateforme.ateliers;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.ateliers.Vues.DroitsAtelier;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.TypeMatiere;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.MembresService;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Qui peut quoi dans les ateliers :
 * <ul>
 * <li>la direction des ateliers (administrateur, chef des travaux ; le censeur tant qu'aucun chef des
 * travaux n'est en fonction) gère tout ;</li>
 * <li>le responsable d'un atelier (mandat en cours) tient son inventaire, son stock et ses pannes ;</li>
 * <li>les enseignants des matières techniques d'une filière de l'atelier le consultent et y signalent
 * les pannes ;</li>
 * <li>l'intendant consulte tout (prix, stocks).</li>
 * </ul>
 */
@Component
class AccesAteliers {

    private final MembresService membres;
    private final EnseignantsService enseignants;
    private final AnneesService annees;
    private final ClassesService classes;
    private final MandatRepository mandats;
    private final AtelierFiliereRepository filieres;

    AccesAteliers(MembresService membres, EnseignantsService enseignants, AnneesService annees, ClassesService classes,
            MandatRepository mandats, AtelierFiliereRepository filieres) {
        this.membres = membres;
        this.enseignants = enseignants;
        this.annees = annees;
        this.classes = classes;
        this.mandats = mandats;
        this.filieres = filieres;
    }

    boolean direction() {
        if (UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "CHEF_TRAVAUX")) {
            return true;
        }
        return UtilisateurConnecte.aUnRole("CENSEUR") && !chefDesTravauxEnFonction();
    }

    boolean intendance() {
        return UtilisateurConnecte.aUnRole("INTENDANT");
    }

    void exigerDirection(String quoi) {
        if (!direction()) {
            throw new AccesRefuseException("Réservé au chef des travaux et à l'administration : " + quoi);
        }
    }

    boolean chefDesTravauxEnFonction() {
        return membres.lister().stream().anyMatch(m -> m.actif() && m.role() == Role.CHEF_TRAVAUX);
    }

    Optional<UUID> monEngagement() {
        if (!UtilisateurConnecte.aUnRole("ENSEIGNANT")) {
            return Optional.empty();
        }
        return UtilisateurConnecte.idSiConnecte().flatMap(enseignants::engagementActif);
    }

    /** Ateliers dont l'enseignant connecté est responsable (mandat en cours). */
    Set<UUID> mesAteliersResponsable() {
        Optional<UUID> moi = monEngagement();
        if (moi.isEmpty()) {
            return Set.of();
        }
        return mandats.findByFinIsNull().stream().filter(m -> m.getEngagementId().equals(moi.get()))
                .map(MandatResponsable::getAtelierId).collect(Collectors.toSet());
    }

    boolean responsable(UUID atelierId) {
        Optional<UUID> moi = monEngagement();
        return moi.isPresent() && mandats.findByAtelierIdAndFinIsNull(atelierId)
                .map(m -> m.getEngagementId().equals(moi.get())).orElse(false);
    }

    /**
     * Enseignants des matières techniques, pratiques ou modules des classes de l'année active dont la
     * filière est servie par l'atelier, avec les matières qu'ils y enseignent.
     */
    Map<UUID, Set<String>> enseignantsTechniques(Collection<UUID> filieresAtelier) {
        Map<UUID, Set<String>> resultat = new LinkedHashMap<>();
        if (filieresAtelier.isEmpty()) {
            return resultat;
        }
        AnneeVue annee;
        try {
            annee = annees.active();
        } catch (RessourceIntrouvableException e) {
            return resultat;
        }
        for (ClasseVue c : classes.lister(annee.id())) {
            if (!filieresAtelier.contains(c.filiereId())) {
                continue;
            }
            for (MatiereDeClasseVue m : classes.matieres(c.id())) {
                if (m.engagementId() != null && m.type() != TypeMatiere.GENERALE) {
                    resultat.computeIfAbsent(m.engagementId(), k -> new TreeSet<>())
                            .add(m.matiereLibelle() + " (" + c.code() + ")");
                }
            }
        }
        return resultat;
    }

    List<UUID> filieresDe(UUID atelierId) {
        return filieres.findByAtelierId(atelierId).stream().map(AtelierFiliere::getFiliereId).toList();
    }

    boolean enseignantDe(UUID atelierId) {
        Optional<UUID> moi = monEngagement();
        return moi.isPresent() && enseignantsTechniques(filieresDe(atelierId)).containsKey(moi.get());
    }

    DroitsAtelier droits(UUID atelierId) {
        boolean gerer = direction();
        boolean responsable = responsable(atelierId);
        boolean signaler = gerer || responsable || enseignantDe(atelierId);
        return new DroitsAtelier(gerer, responsable, signaler);
    }

    /** Lecture : direction, intendance, responsable ou enseignant technique de l'atelier. */
    DroitsAtelier exigerLecture(UUID atelierId) {
        DroitsAtelier d = droits(atelierId);
        if (!d.signaler() && !intendance()) {
            throw new AccesRefuseException("Vous n'enseignez pas dans cet atelier");
        }
        return d;
    }

    /** Tenue de l'atelier (inventaire, stock, pannes) : direction ou responsable. */
    DroitsAtelier exigerTenue(UUID atelierId) {
        DroitsAtelier d = droits(atelierId);
        if (!d.tenir()) {
            throw new AccesRefuseException("Réservé au responsable de l'atelier et au chef des travaux");
        }
        return d;
    }

    /** Modification du catalogue : direction, intendance (prix) et responsables d'atelier (spécifications). */
    void exigerCatalogue() {
        if (!direction() && !intendance() && mesAteliersResponsable().isEmpty()) {
            throw new AccesRefuseException(
                    "Le catalogue est tenu par le chef des travaux, l'intendant et les responsables d'atelier");
        }
    }
}
