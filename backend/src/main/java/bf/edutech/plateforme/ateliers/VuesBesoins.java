package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import bf.edutech.plateforme.ateliers.Vues.FiliereCourte;

/** Données échangées par l'API du circuit des besoins (campagnes, commandes, livraisons). */
public final class VuesBesoins {

    private VuesBesoins() {
    }

    // ---------------- Campagnes

    public record DemandeCampagne(UUID anneeId, TypeCampagne type, String libelle, LocalDate dateLimite,
            String observations) {
    }

    public record CampagneResumeVue(UUID id, UUID anneeId, TypeCampagne type, String libelle, LocalDate dateLimite,
            StatutCampagne statut, Instant ouverteLe, Instant transmiseLe, int ateliers, int transmis, int valides,
            long montant, int commandes) {
    }

    public record BesoinResumeVue(UUID id, UUID atelierId, String atelierCode, String atelierNom,
            List<FiliereCourte> filieres, StatutBesoin statut, int lignes, long montant, Instant transmisLe,
            String responsable) {
    }

    public record LigneConsolideeVue(UUID articleId, String code, String designation, NatureArticle nature,
            String unite, BigDecimal quantite, Long prixUnitaire, long montant, BigDecimal commandee,
            BigDecimal livree) {
    }

    /** Besoins validés regroupés par filière (celles de l'atelier qui les a exprimés). */
    public record FiliereConsolideeVue(String filiere, List<LigneConsolideeVue> lignes, long montant) {
    }

    public record CommandeResumeVue(UUID id, String reference, String fournisseur, PasseePar passeePar,
            LocalDate dateCommande, StatutCommande statut, long montant, int lignes) {
    }

    public record CampagneVue(UUID id, UUID anneeId, TypeCampagne type, String libelle, LocalDate dateLimite,
            StatutCampagne statut, String observations, Instant ouverteLe, Instant transmiseLe, Instant closeLe,
            List<BesoinResumeVue> besoins, List<FiliereConsolideeVue> filieres, List<LigneConsolideeVue> totaux,
            long montant, List<CommandeResumeVue> commandes, boolean gerer, boolean commander) {
    }

    // ---------------- Besoins d'un atelier

    /** Les campagnes vues depuis un atelier. */
    public record BesoinAtelierResumeVue(UUID id, UUID campagneId, String campagne, TypeCampagne type,
            StatutCampagne statutCampagne, LocalDate dateLimite, StatutBesoin statut, int lignes, long montant) {
    }

    public record DroitsBesoin(boolean proposer, boolean modifier, boolean transmettre, boolean arbitrer) {
    }

    public record LigneBesoinVue(UUID articleId, String code, String designation, NatureArticle nature, String unite,
            String specifications, String normes, boolean photo, BigDecimal quantiteDemandee, String justification,
            String proposePar, BigDecimal quantiteRetenue, Long prixUnitaire, long montant) {
    }

    public record BesoinVue(UUID id, UUID campagneId, String campagne, TypeCampagne type, StatutCampagne statutCampagne,
            LocalDate dateLimite, UUID atelierId, String atelierCode, String atelierNom, StatutBesoin statut,
            Instant transmisLe, String transmisPar, Instant valideLe, String commentaire, List<LigneBesoinVue> lignes,
            long montant, DroitsBesoin droits) {
    }

    public record DemandeLigne(BigDecimal quantite, String justification) {
    }

    public record SaisieArbitrage(UUID articleId, BigDecimal quantiteRetenue) {
    }

    public record DemandeArbitrage(List<SaisieArbitrage> lignes) {
    }

    public record DemandeRenvoi(String commentaire) {
    }

    // ---------------- Commandes

    public record SaisieLigneCommande(UUID articleId, BigDecimal quantite, Long prixUnitaire) {
    }

    public record DemandeCommande(String reference, String fournisseur, PasseePar passeePar, LocalDate dateCommande,
            String observations, List<SaisieLigneCommande> lignes) {
    }

    public record DemandeAnnulation(String motif) {
    }

    public record LigneCommandeVue(UUID articleId, String code, String designation, NatureArticle nature, String unite,
            BigDecimal quantite, long prixUnitaire, long montant, BigDecimal recue, BigDecimal conforme,
            BigDecimal reste) {
    }

    public record LivraisonResumeVue(UUID id, LocalDate dateReception, String bonLivraison, StatutLivraison statut,
            int lignes, int nonConformes) {
    }

    public record CommandeVue(UUID id, UUID campagneId, String campagne, String reference, String fournisseur,
            PasseePar passeePar, LocalDate dateCommande, StatutCommande statut, String observations,
            String motifAnnulation, List<LigneCommandeVue> lignes, long montant, List<LivraisonResumeVue> livraisons,
            boolean gerer, boolean recevoir) {
    }

    // ---------------- Livraisons et répartition

    public record SaisieLigneLivraison(UUID articleId, BigDecimal quantiteRecue, BigDecimal quantiteConforme,
            String motifNonConformite) {
    }

    public record DemandeLivraison(LocalDate dateReception, String bonLivraison, String observations,
            List<SaisieLigneLivraison> lignes) {
    }

    public record AtelierCourt(UUID id, String code, String nom) {
    }

    /** Part d'un atelier : besoin retenu dans la campagne, part proposée, part enregistrée. */
    public record PartAtelierVue(UUID atelierId, String atelierCode, BigDecimal retenu, BigDecimal proposee,
            BigDecimal quantite) {
    }

    public record LigneLivraisonVue(UUID articleId, String code, String designation, NatureArticle nature,
            String unite, BigDecimal recue, BigDecimal conforme, String motifNonConformite,
            List<PartAtelierVue> repartition) {
    }

    public record LivraisonVue(UUID id, UUID commandeId, String commandeReference, String fournisseur, UUID campagneId,
            LocalDate dateReception, String bonLivraison, String observations, StatutLivraison statut,
            String recuePar, Instant repartieLe, List<LigneLivraisonVue> lignes, List<AtelierCourt> ateliers,
            boolean gerer) {
    }

    public record SaisieRepartition(UUID articleId, UUID atelierId, BigDecimal quantite) {
    }

    public record DemandeRepartition(List<SaisieRepartition> lignes) {
    }
}
