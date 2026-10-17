package bf.edutech.plateforme.recrutement;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import bf.edutech.plateforme.eleves.LienParente;
import bf.edutech.plateforme.socle.referentiel.Sexe;

/** Objets échangés par l'API de la commission de recrutement (v0.49). */
public final class Vues {

    private Vues() {
    }

    // ------------------------------------------------------------------ saisies

    public record DonneesCommission(String intitule, LocalDate dateSession, String lieu, LocalDate dateLimiteDepot,
            String criteres, BigDecimal moyenneMinimale, BigDecimal poidsExamen, Integer tailleListeAttente) {
    }

    public record MembreSaisi(Qualite qualite, UUID utilisateurId, String nom, String fonction, Boolean present) {
    }

    public record DemandeMembres(List<MembreSaisi> membres) {
    }

    public record OffreSaisie(UUID offreId, Integer places) {
    }

    public record DemandeOffres(List<OffreSaisie> offres) {
    }

    public record DonneesCandidature(UUID offreId, String nom, String prenoms, Sexe sexe, LocalDate dateNaissance,
            String lieuNaissance, String responsableNom, String responsablePrenoms, String responsableTelephone,
            LienParente responsableLien, String etablissementOrigine, String classeOrigine,
            BigDecimal moyenneAnnuelle, BigDecimal noteExamen, Boolean redoublant, String observations) {
    }

    public record DemandeRecevabilite(Boolean recevable, String motif) {
    }

    public record DemandeDeliberation(String observations) {
    }

    public record DemandeInscription(UUID classeId) {
    }

    public record DemandeDesistement(String motif) {
    }

    // ------------------------------------------------------------------ vues

    public record CommissionResume(UUID id, String numero, String intitule, LocalDate dateSession,
            StatutCommission statut, int places, int candidatures, int retenues, int inscrites) {
    }

    public record MembreCommissionVue(UUID id, Qualite qualite, UUID utilisateurId, String nom, String fonction,
            boolean present) {
    }

    public record ClasseOffre(UUID id, String code, Short effectifMax, int inscrits) {
    }

    /** Places disponibles : null si une classe n'a pas d'effectif maximal (capacité non renseignée). */
    public record OffreVue(UUID id, String niveau, UUID filiereId, String filiere, int places, Integer disponibles,
            int candidatures, int retenues, int listeAttente, int inscrites, List<ClasseOffre> classes) {
    }

    public record Droits(boolean modifier, boolean saisir, boolean deliberer, boolean inscrire, boolean clore) {
    }

    public record CommissionVue(UUID id, String numero, UUID anneeId, String annee, String intitule,
            LocalDate dateSession, String lieu, LocalDate dateLimiteDepot, String criteres,
            BigDecimal moyenneMinimale, BigDecimal poidsExamen, int tailleListeAttente, StatutCommission statut,
            String observationsPv, Instant delibereeLe, String delibereePar, Instant closeLe,
            List<MembreCommissionVue> membres, List<OffreVue> offres, Droits droits) {
    }

    public record CandidatureVue(UUID id, String numero, UUID offreId, String offre, String nom, String prenoms,
            Sexe sexe, LocalDate dateNaissance, String lieuNaissance, String responsableNom,
            String responsablePrenoms, String responsableTelephone, LienParente responsableLien,
            String etablissementOrigine, String classeOrigine, BigDecimal moyenneAnnuelle, BigDecimal noteExamen,
            boolean redoublant, String observations, StatutCandidature statut, String motif, BigDecimal score,
            Integer rang, boolean repechee, UUID inscriptionId, Instant inscriteLe, String classeInscription) {
    }

    public record LigneClassement(UUID candidatureId, String numero, String nom, String prenoms, Sexe sexe,
            BigDecimal moyenneAnnuelle, BigDecimal noteExamen, BigDecimal score, Integer rang,
            StatutCandidature decision, String motif) {
    }

    public record ClassementOffre(UUID offreId, String offre, int places, List<LigneClassement> lignes) {
    }

    public record ResultatDesistement(CandidatureVue desistee, CandidatureVue repechee) {
    }
}
