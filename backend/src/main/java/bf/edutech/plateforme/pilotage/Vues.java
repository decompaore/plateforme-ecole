package bf.edutech.plateforme.pilotage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Nombres du tableau de bord de pilotage. */
public final class Vues {

    private Vues() {
    }

    /** Élèves : garçons, filles et total. */
    public record Compte(int garcons, int filles, int total) {

        public static final Compte ZERO = new Compte(0, 0, 0);

        public static Compte de(int garcons, int filles) {
            return new Compte(garcons, filles, garcons + filles);
        }

        public Compte plus(Compte autre) {
            return de(garcons + autre.garcons, filles + autre.filles);
        }
    }

    /** Personnel : hommes, femmes et total. */
    public record Personnes(int hommes, int femmes, int total) {

        public static final Personnes ZERO = new Personnes(0, 0, 0);

        public static Personnes de(int hommes, int femmes) {
            return new Personnes(hommes, femmes, hommes + femmes);
        }

        public Personnes plus(Personnes autre) {
            return de(hommes + autre.hommes, femmes + autre.femmes);
        }
    }

    public record NiveauVue(String niveau, int classes, Compte eleves, Compte redoublants, Compte decides,
            Compte admis, BigDecimal tauxAdmission) {
    }

    /**
     * Examen de fin d'études : candidats (inscrits des classes d'examen), résultats connus, admis ;
     * taux de réussite = admis / résultats connus.
     */
    public record ExamenVue(String examen, Compte candidats, Compte resultats, Compte admis, BigDecimal taux,
            BigDecimal tauxGarcons, BigDecimal tauxFilles) {
    }

    /** Bulletins publiés d'une période : moyenne des moyennes, part à la moyenne. */
    public record PeriodeVue(String decoupage, int ordre, String libelle, Compte bulletins, BigDecimal moyenne,
            Compte admis, BigDecimal taux) {
    }

    /** Utilisation de l'application sur les 30 derniers jours. */
    public record UtilisationVue(int comptes, int actifs, int enseignants, int enseignantsActifs,
            LocalDate derniereActivite) {
    }

    public record Indicateurs(int etablissements, int classes, Compte eleves, Compte redoublants,
            Personnes enseignants, Personnes titulaires, Personnes vacataires, BigDecimal elevesParEnseignant,
            Compte decides, Compte admis, BigDecimal tauxAdmission, List<NiveauVue> niveaux, List<ExamenVue> examens,
            List<PeriodeVue> periodes, UtilisationVue utilisation) {
    }

    /** Ce que l'on regarde : un pays (toutes ses directions) ou une direction. */
    public record PerimetreVue(String type, UUID id, String nom, String niveau, String chemin) {
    }

    /** Niveau au-dessus, quand la personne connectée peut y remonter. */
    public record ParentVue(String type, UUID id, String nom) {
    }

    public record LigneDirection(UUID id, String code, String nom, Indicateurs indicateurs) {
    }

    /** {@code sousDirectionId} : direction de la ligne « directions » dont dépend l'établissement. */
    public record LigneEtablissement(UUID id, String code, String nom, String statut, UUID directionId,
            String direction, UUID sousDirectionId, Indicateurs indicateurs) {
    }

    public record TableauPilotage(PerimetreVue perimetre, ParentVue parent, List<String> annees, String annee,
            LocalDate debutUtilisation, LocalDate finUtilisation, Indicateurs synthese, String niveauDirections,
            List<LigneDirection> directions, List<LigneEtablissement> etablissements, Instant produitLe) {
    }

    /** Pourcentage à une décimale ; null si rien à mesurer. */
    static BigDecimal taux(long partie, long total) {
        return total == 0 ? null
                : BigDecimal.valueOf(partie).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
    }
}
