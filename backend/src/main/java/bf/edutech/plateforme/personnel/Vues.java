package bf.edutech.plateforme.personnel;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Données échangées par le module du personnel (v0.51). */
public final class Vues {

    private Vues() {
    }

    public enum Categorie {
        ENSEIGNANT,
        ADMINISTRATIF,
        SOUTIEN
    }

    public enum Situation {
        FONCTIONNAIRE,
        CONTRACTUEL,
        VACATAIRE,
        AUTRE
    }

    public enum NaturePiece {
        ARRETE,
        DECISION,
        NOTE_SERVICE,
        CERTIFICAT,
        DIPLOME,
        ETAT_CIVIL,
        CONTRAT,
        AUTRE
    }

    public enum NatureDiplome {
        ACADEMIQUE,
        PROFESSIONNEL
    }

    public enum TypeActe {
        PRISE_SERVICE("Certificat de prise de service"),
        CESSATION_SERVICE("Certificat de cessation de service"),
        PRESENCE_POSTE("Attestation de présence au poste"),
        ATTESTATION_TRAVAIL("Attestation de travail"),
        AUTORISATION_ABSENCE("Autorisation d'absence");

        public final String titre;

        TypeActe(String titre) {
            this.titre = titre;
        }
    }

    public enum TypeAbsence {
        AUTORISATION_ABSENCE("autorisation d'absence"),
        PERMISSION("permission"),
        CONGE_ANNUEL("congé annuel"),
        CONGE_MALADIE("congé de maladie"),
        CONGE_MATERNITE("congé de maternité"),
        MISSION("mission"),
        FORMATION("formation"),
        ABSENCE_IRREGULIERE("absence irrégulière");

        public final String libelle;

        TypeAbsence(String libelle) {
            this.libelle = libelle;
        }
    }

    public enum StatutAbsence {
        DEMANDEE,
        ACCORDEE,
        REFUSEE,
        ANNULEE,
        CONSTATEE
    }

    /** Durée écoulée entre deux dates, en années, mois et jours. */
    public record Duree(int annees, int mois, int jours, String texte) {
    }

    /** Ancienneté dans la fonction, dans le grade et dans l'établissement (null si la date manque). */
    public record Anciennete(Duree fonction, Duree grade, Duree etablissement) {
    }

    /** Données saisies d'un dossier. */
    public record DonneesAgent(Categorie categorie, String nom, String prenoms, String sexe, LocalDate dateNaissance,
            String lieuNaissance, String telephone, String adresse, String matricule, Situation situation,
            String emploi, String grade, String categorieEchelle, String echelon, String fonction,
            LocalDate datePremierePriseService, LocalDate dateEntreeGrade, LocalDate datePriseService,
            LocalDate dateCessation, String motifCessation, String observations) {
    }

    public record AgentResume(UUID id, Categorie categorie, String nom, String prenoms, String sexe, String matricule,
            Situation situation, String emploi, String grade, String fonction, LocalDate datePriseService,
            LocalDate dateCessation, boolean compte, Duree ancienneteEtablissement, int pieces, int signalements,
            boolean absentAujourdhui, List<String> manquants) {
    }

    public record DiplomeVue(UUID id, NatureDiplome nature, String intitule, String specialite, String delivrePar,
            Integer annee, UUID pieceId) {
    }

    public record PieceVue(UUID id, NaturePiece nature, String reference, LocalDate datePiece, String objet,
            String nomFichier, String typeContenu, int taille, String deposePar, Instant deposeLe) {
    }

    public record ActeVue(UUID id, TypeActe type, String numero, String titre, LocalDate dateEffet, String etabliPar,
            Instant etabliLe, String signataire, Instant signeLe, Instant annuleLe, String motifAnnulation,
            UUID absenceId) {
    }

    public record AbsenceVue(UUID id, UUID agentId, String agent, TypeAbsence type, LocalDate debut, LocalDate fin,
            int jours, LocalDate reprise, String motif, StatutAbsence statut, String demandeePar, Instant demandeeLe,
            String decideePar, Instant decideeLe, String reponse, UUID pieceId, UUID acteId) {
    }

    public record SignalementVue(UUID id, String texte, String signalePar, Instant signaleLe, String traitePar,
            Instant traiteLe, String reponse) {
    }

    /** Droits de l'utilisateur connecté sur le dossier. */
    public record Droits(boolean modifier, boolean pieces, boolean actes, boolean signer, boolean decider) {
    }

    public record DossierVue(UUID id, Categorie categorie, UUID utilisateurId, String nom, String prenoms, String sexe,
            LocalDate dateNaissance, String lieuNaissance, String telephone, String adresse, String matricule,
            Situation situation, String emploi, String grade, String categorieEchelle, String echelon,
            String fonction, LocalDate datePremierePriseService, LocalDate dateEntreeGrade,
            LocalDate datePriseService, LocalDate dateCessation, String motifCessation, String observations,
            Anciennete anciennete, List<String> manquants, List<DiplomeVue> diplomes, List<PieceVue> pieces,
            List<ActeVue> actes, List<AbsenceVue> absences, List<SignalementVue> signalements, Droits droits,
            Instant modifieLe) {
    }

    public record ResultatImport(int crees, int dejaPresents) {
    }
}
