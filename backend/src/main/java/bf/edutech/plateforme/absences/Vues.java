package bf.edutech.plateforme.absences;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Objets échangés par l'API du module Absences. */
public final class Vues {

    private Vues() {
    }

    /** Élève marqué lors d'un appel (les élèves non cités sont présents). */
    public record Marque(UUID inscriptionId, TypeAbsence type, Integer minutesRetard) {
    }

    /**
     * Appel saisi sur l'appareil. {@code idClient} est généré par l'appareil et
     * rend l'envoi idempotent : renvoyer le même appel ne crée jamais de doublon.
     */
    public record DonneesAppel(UUID idClient, UUID classeId, UUID matiereId, LocalDate date, LocalTime heureDebut,
            LocalTime heureFin, Instant saisiLe, List<Marque> marques) {
    }

    /** Accusé de réception d'un appel, pour que l'appareil vide sa file locale. */
    public record AccuseAppel(UUID idClient, StatutAccuse statut, String code, String message, int absents,
            int retards, List<UUID> inscriptionsIgnorees) {
    }

    public record MarqueVue(UUID inscriptionId, String nom, String prenoms, TypeAbsence type, Integer minutesRetard) {
    }

    public record AppelVue(UUID id, UUID classeId, UUID matiereId, LocalDate date, LocalTime heureDebut,
            LocalTime heureFin, UUID faitPar, Instant saisiLe, Instant recuLe, Instant modifieLe,
            List<MarqueVue> marques) {
    }

    /** {@code matiere*} : discipline du cours manqué ; null pour un appel général (sans matière). */
    public record AbsenceVue(UUID id, UUID appelId, UUID inscriptionId, LocalDate date, LocalTime heureDebut,
            LocalTime heureFin, TypeAbsence type, Integer minutesRetard, boolean justifiee, UUID matiereId,
            String matiereCode, String matiereLibelle) {
    }

    public record JustificatifVue(UUID id, UUID inscriptionId, LocalDate du, LocalDate au, TypeJustificatif type,
            String motif) {

        static JustificatifVue depuis(Justificatif j) {
            return new JustificatifVue(j.getId(), j.getInscriptionId(), j.getDu(), j.getAu(), j.getType(),
                    j.getMotif());
        }
    }

    /** Synthèse d'un élève sur une période (pour le bulletin et le suivi). */
    public record SyntheseEleveVue(UUID inscriptionId, String nom, String prenoms, int absences,
            int absencesJustifiees, int retards, BigDecimal heuresAbsence, BigDecimal heuresNonJustifiees) {
    }

    /** Un créneau manqué (absence) ou un retard, pour la liste du jour. */
    public record CreneauVue(UUID appelId, LocalTime heureDebut, LocalTime heureFin, TypeAbsence type,
            Integer minutesRetard, UUID matiereId, String matiereCode, String matiereLibelle) {
    }

    /**
     * Élève absent ou en retard un jour donné, tous créneaux réunis : la liste de travail
     * de la vie scolaire (appeler la famille, enregistrer un justificatif).
     */
    public record EleveDuJourVue(UUID inscriptionId, UUID eleveId, String matricule, String nom, String prenoms,
            UUID classeId, String classeCode, int absences, int retards, boolean justifiee,
            List<CreneauVue> creneaux) {
    }

    /**
     * Absences d'une classe dans une discipline sur une période : nombre de cours
     * manqués (élève × séance), heures, dont non justifiées, élèves concernés, retards.
     * {@code matiereId} null : appels généraux, sans matière.
     */
    public record AbsencesParMatiereVue(UUID matiereId, String matiereCode, String matiereLibelle, int absences,
            BigDecimal heures, BigDecimal heuresNonJustifiees, int eleves, int retards) {
    }
}
