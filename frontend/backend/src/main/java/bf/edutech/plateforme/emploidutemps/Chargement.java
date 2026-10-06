package bf.edutech.plateforme.emploidutemps;

import java.sql.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.ateliers.AteliersService;
import bf.edutech.plateforme.emploidutemps.Instantane.Occupation;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Charge l'emploi du temps d'une année (à appeler dans une transaction). */
@Component
class Chargement {

    private final AnneesService annees;
    private final ClassesService classes;
    private final EnseignantsService enseignants;
    private final AteliersService ateliers;
    private final CreneauRepository creneaux;
    private final SeanceEmploiRepository seances;
    private final JdbcTemplate jdbc;

    Chargement(AnneesService annees, ClassesService classes, EnseignantsService enseignants, AteliersService ateliers,
            CreneauRepository creneaux, SeanceEmploiRepository seances, JdbcTemplate jdbc) {
        this.annees = annees;
        this.classes = classes;
        this.enseignants = enseignants;
        this.ateliers = ateliers;
        this.creneaux = creneaux;
        this.seances = seances;
        this.jdbc = jdbc;
    }

    Instantane charger(UUID anneeId) {
        UtilisateurConnecte.etablissementActif();
        AnneeVue annee = annees.trouver(anneeId);
        List<ClasseVue> listeClasses = classes.lister(anneeId);
        Map<UUID, List<MatiereDeClasseVue>> programme = new LinkedHashMap<>();
        Set<UUID> engagements = new HashSet<>();
        for (ClasseVue c : listeClasses) {
            List<MatiereDeClasseVue> m = classes.matieres(c.id());
            programme.put(c.id(), m);
            m.stream().map(MatiereDeClasseVue::engagementId).filter(e -> e != null).forEach(engagements::add);
        }
        return new Instantane(annee, creneaux.findByAnneeIdOrderByHeureDebutAsc(anneeId), listeClasses, programme,
                seances.findByAnneeId(anneeId), enseignants.nomsParEngagement(engagements), ateliers.ouverts(),
                ailleurs(engagements, annee));
    }

    /** Heures prises par ces enseignants dans leurs autres établissements (vacataires). */
    private List<Occupation> ailleurs(Set<UUID> engagements, AnneeVue annee) {
        if (engagements.isEmpty()) {
            return List.of();
        }
        String tableau = engagements.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        return jdbc.query("select engagement_id, jour, heure_debut, heure_fin from occupations_ailleurs(?::uuid[], ?, ?)",
                (rs, i) -> new Occupation(rs.getObject(1, UUID.class), rs.getInt(2),
                        rs.getTime(3).toLocalTime(), rs.getTime(4).toLocalTime()),
                tableau, Date.valueOf(annee.debut()), Date.valueOf(annee.fin()));
    }
}
