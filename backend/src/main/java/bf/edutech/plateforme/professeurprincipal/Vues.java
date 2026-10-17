package bf.edutech.plateforme.professeurprincipal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Données échangées par le module du professeur principal (v0.49.1). */
public final class Vues {

    private Vues() {
    }

    public enum Nature {
        /** Voix et revendications des enseignants de la classe, portées à l'administration. */
        REVENDICATION,
        /** Souci d'un élève ou d'un groupe d'élèves avec un enseignant : médiation du professeur principal. */
        MEDIATION
    }

    public enum StatutSaisine {
        OUVERTE, TRANSMISE, CLOSE
    }

    public enum StatutVerification {
        A_FAIRE, EN_COURS, VALIDEE
    }

    public enum StatutReclamation {
        OUVERTE, CORRIGEE, MAINTENUE
    }

    // ------------------------------------------------------------------ saisies

    public record DemandeDesignation(UUID utilisateurId) {
    }

    public record DonneesSaisine(Nature nature, String objet, String expose, List<UUID> inscriptions, UUID matiereId,
            UUID enseignantId) {
    }

    public record DemandeTexte(String texte) {
    }

    public record DonneesReclamation(UUID periodeId, UUID inscriptionId, UUID matiereId, UUID evaluationId,
            String motif) {
    }

    public record DemandeReponseReclamation(StatutReclamation decision, String reponse) {
    }

    public record DemandeValidation(UUID periodeId, String observations) {
    }

    // ------------------------------------------------------------------ vues

    /** Un enseignant de la classe (candidat à la désignation). */
    public record EnseignantClasse(UUID utilisateurId, String nom, List<String> matieres) {
    }

    /** État de la vérification des notes d'une période, pour le tableau des classes. */
    public record EtatVerification(UUID periodeId, String periode, StatutVerification statut, int reclamationsOuvertes) {
    }

    public record ClassePrincipale(UUID classeId, String classeCode, String niveau, UUID profilId, UUID professeurId,
            String professeur, Instant designeLe, List<EnseignantClasse> enseignants, int saisinesOuvertes,
            List<EtatVerification> verifications) {
    }

    public record EleveConcerne(UUID inscriptionId, String nom) {
    }

    public record SuiteVue(UUID id, String auteur, String qualite, String texte, Instant creeLe) {
    }

    public record Droits(boolean ecrire, boolean informer, boolean transmettre, boolean clore) {
    }

    public record SaisineVue(UUID id, String numero, UUID classeId, String classeCode, Nature nature, String objet,
            String expose, String auteur, String professeur, List<EleveConcerne> eleves, UUID matiereId,
            String matiere, UUID enseignantId, String enseignant, boolean enseignantInforme, StatutSaisine statut,
            Instant transmiseLe, String conclusion, Instant closeLe, String closePar, Instant creeLe, int suites,
            List<SuiteVue> journal, Droits droits) {
    }

    public record ReclamationVue(UUID id, UUID classeId, String classeCode, UUID periodeId, String periode,
            UUID inscriptionId, String eleve, UUID matiereId, String matiere, UUID evaluationId, String evaluation,
            BigDecimal noteContestee, String motif, String enseignant, String enregistreePar, Instant enregistreeLe,
            StatutReclamation statut, BigDecimal noteCorrigee, String reponse, String reponduePar,
            Instant reponduLe, boolean peutRepondre) {
    }

    public record VerificationVue(UUID classeId, String classeCode, UUID periodeId, String periode,
            boolean periodeVerrouillee, String professeur, StatutVerification statut, Instant valideeLe,
            String valideePar, String observations, List<ReclamationVue> reclamations, boolean professeurPrincipal,
            boolean peutValider) {
    }
}
