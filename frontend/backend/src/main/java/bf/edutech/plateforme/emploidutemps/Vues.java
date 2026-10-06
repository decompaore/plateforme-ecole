package bf.edutech.plateforme.emploidutemps;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import bf.edutech.plateforme.etablissement.TypeMatiere;

/** Objets échangés par l'API des emplois du temps. */
public final class Vues {

    private Vues() {
    }

    /** Qui place la matière : le censeur (général) ou le chef des travaux (technique, pratique, modules). */
    public enum Domaine {
        GENERAL,
        TECHNIQUE;

        static Domaine de(TypeMatiere type) {
            return type == TypeMatiere.GENERALE ? GENERAL : TECHNIQUE;
        }
    }

    public enum TypeConflit {
        /** Deux séances de la même classe (ou du même groupe) à la même heure. */
        CLASSE,
        /** Un enseignant attendu dans deux classes à la même heure. */
        ENSEIGNANT,
        /** Un vacataire qui a déjà cours dans un autre établissement à cette heure. */
        ENSEIGNANT_AILLEURS,
        /** Un atelier occupé deux fois à la même heure. */
        ATELIER
    }

    public record CreneauVue(UUID id, LocalTime heureDebut, LocalTime heureFin, List<Integer> jours, int minutes) {
    }

    /** {@code id} null : nouveau créneau. */
    public record SaisieCreneau(UUID id, LocalTime heureDebut, LocalTime heureFin, List<Integer> jours) {
    }

    public record DonneesGrille(List<SaisieCreneau> creneaux) {
    }

    /** Matière du programme d'une classe, avec ce qui est déjà placé (en minutes par semaine). */
    public record MatiereClasseVue(UUID matiereId, String code, String libelle, TypeMatiere type, Domaine domaine,
            BigDecimal volumeHebdo, UUID engagementId, String enseignant, int minutesPrevues, int minutesPlacees) {
    }

    /** {@code groupes} : demi-classes déjà utilisées dans l'emploi du temps (TP en groupes). */
    public record ClasseEmploiVue(UUID id, String code, String niveau, UUID filiereId, String filiereCode,
            List<MatiereClasseVue> matieres, List<String> groupes) {
    }

    public record SeanceVue(UUID id, UUID classeId, UUID matiereId, int jour, UUID creneauId, String groupe,
            UUID atelierId, String salle, UUID engagementId, Domaine domaine, boolean modifiable) {
    }

    public record EnseignantCourtVue(UUID engagementId, String nom) {
    }

    public record AtelierEmploiVue(UUID id, String code, String nom, List<UUID> filieres) {
    }

    /** Heures où un enseignant est pris ailleurs (sans dire où). */
    public record OccupationVue(UUID engagementId, int jour, LocalTime heureDebut, LocalTime heureFin) {
    }

    public record ConflitVue(TypeConflit type, List<UUID> seances, String message) {
    }

    /**
     * {@code grille} : peut modifier les heures de cours ; {@code domaines} : matières que la
     * personne place ; {@code modifiable} : l'année accepte encore des changements.
     */
    public record DroitsEmploi(boolean grille, List<Domaine> domaines, boolean publier, boolean modifiable) {
    }

    public record EmploiDuTempsVue(UUID anneeId, String annee, List<Integer> jours, List<CreneauVue> creneaux,
            List<ClasseEmploiVue> classes, List<SeanceVue> seances, List<EnseignantCourtVue> enseignants,
            List<AtelierEmploiVue> ateliers, List<OccupationVue> occupationsAilleurs, List<ConflitVue> conflits,
            DroitsEmploi droits, Instant publieLe) {
    }

    public record DonneesSeance(UUID anneeId, UUID classeId, UUID matiereId, Integer jour, UUID creneauId,
            String groupe, UUID atelierId, String salle) {
    }

    /**
     * {@code classes} vide : toutes les classes ; {@code domaines} vide : ceux de la personne ;
     * {@code remplacer} : retire d'abord les séances de ces matières dans ces classes.
     */
    public record DemandeGeneration(List<UUID> classes, List<Domaine> domaines, Boolean remplacer) {
    }

    public record ManqueVue(UUID classeId, String classe, UUID matiereId, String matiere, int minutesManquantes,
            String raison) {
    }

    public record ResultatGeneration(int seancesPlacees, int seancesRetirees, List<ManqueVue> manques,
            EmploiDuTempsVue emploi) {
    }

    public record MaSeanceVue(int jour, UUID creneauId, LocalTime heureDebut, LocalTime heureFin, String classe,
            String matiere, String groupe, String atelier, String salle) {
    }

    /** Emploi du temps de l'enseignant connecté ; vide tant qu'il n'est pas publié. */
    public record MonEmploiVue(UUID anneeId, String annee, Instant publieLe, List<Integer> jours,
            List<CreneauVue> creneaux, List<MaSeanceVue> seances, List<OccupationVue> ailleurs) {
    }
}
