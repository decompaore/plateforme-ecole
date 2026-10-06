package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Données échangées par l'API des ateliers. */
public final class Vues {

    private Vues() {
    }

    // ---------------- Paramètres

    /** {@code dureeMandatMois} null : mandat sans limite de durée. */
    public record ParametresVue(Integer dureeMandatMois, FrequenceInventaire frequenceInventaire) {
    }

    // ---------------- Catalogue

    public record DonneesArticle(String code, String designation, NatureArticle nature, String unite, UUID filiereId,
            String specifications, String normes, Long prixReference, Boolean actif) {
    }

    public record ArticleVue(UUID id, String code, String designation, NatureArticle nature, String unite,
            UUID filiereId, String filiereCode, String specifications, String normes, Long prixReference,
            Instant prixModifieLe, boolean actif, boolean photo) {
    }

    // ---------------- Ateliers et responsables

    public record FiliereCourte(UUID id, String code, String libelle) {
    }

    /** Atelier ouvert, pour placer les séances pratiques dans l'emploi du temps. */
    public record AtelierCourtVue(UUID id, String code, String nom, Short postes, List<UUID> filieres) {
    }

    /** Mandat d'un responsable ; {@code echeanceProche} : fin prévue dans les 60 jours. */
    public record MandatVue(UUID id, UUID engagementId, String enseignant, LocalDate debut, LocalDate finPrevue,
            LocalDate fin, String motifFin, boolean echeanceProche, boolean echu) {
    }

    public record AlertesAtelier(boolean sansResponsable, boolean mandatAEcheance, boolean mandatEchu,
            int equipementsEnPanne, int equipementsManquants, int articlesSousSeuil, boolean inventaireEnCours,
            LocalDate dernierInventaire, boolean inventaireEnRetard) {

        int nombre() {
            return (sansResponsable ? 1 : 0) + (mandatAEcheance || mandatEchu ? 1 : 0) + equipementsEnPanne
                    + equipementsManquants + articlesSousSeuil + (inventaireEnRetard ? 1 : 0);
        }
    }

    /** Ce que l'utilisateur connecté peut faire dans l'atelier. */
    public record DroitsAtelier(boolean gerer, boolean responsable, boolean signaler) {

        boolean tenir() {
            return gerer || responsable;
        }
    }

    public record AtelierResumeVue(UUID id, String code, String nom, String emplacement, Short postes, boolean ouvert,
            List<FiliereCourte> filieres, MandatVue responsable, int equipements, int articles, AlertesAtelier alertes,
            DroitsAtelier droits) {
    }

    public record AtelierVue(UUID id, String code, String nom, String emplacement, Short postes, boolean ouvert,
            String observations, List<FiliereCourte> filieres, MandatVue responsable, List<MandatVue> historique,
            int equipements, int articles, AlertesAtelier alertes, DroitsAtelier droits) {
    }

    public record DonneesAtelier(String code, String nom, String emplacement, Short postes, Boolean ouvert,
            String observations, List<UUID> filieres) {
    }

    /** Enseignant pouvant être désigné : il enseigne une matière technique d'une filière de l'atelier. */
    public record CandidatVue(UUID engagementId, String enseignant, List<String> matieres) {
    }

    public record DemandeMandat(UUID engagementId, LocalDate debut, LocalDate finPrevue) {
    }

    public record DemandeFinMandat(LocalDate date, String motif) {
    }

    // ---------------- Équipements et pannes

    public record PanneVue(UUID id, UUID equipementId, String description, String signaleePar, Instant signaleeLe,
            StatutPanne statut, String intervention, Long cout, String clotureePar, Instant clotureeLe) {
    }

    public record EquipementVue(UUID id, UUID atelierId, UUID articleId, String designation, String numeroInventaire,
            String marque, String numeroSerie, LocalDate dateAcquisition, Long valeur, EtatEquipement etat,
            String observations, PanneVue panneOuverte) {
    }

    /** {@code numeroInventaire} vide : numéro attribué automatiquement (code de l'atelier, année, rang). */
    public record DonneesEquipement(UUID articleId, String designation, String numeroInventaire, String marque,
            String numeroSerie, LocalDate dateAcquisition, Long valeur, String observations, EtatEquipement etat) {
    }

    public record DemandePanne(String description) {
    }

    public record DemandeCloturePanne(StatutPanne statut, String intervention, Long cout) {
    }

    // ---------------- Matière d'œuvre

    public record LigneStockVue(UUID articleId, String code, String designation, String unite, BigDecimal quantite,
            BigDecimal seuilAlerte, boolean sousLeSeuil, Long prixReference) {
    }

    public record DemandeMouvement(UUID articleId, TypeMouvement type, BigDecimal quantite, LocalDate date,
            String motif) {
    }

    public record DemandeSeuil(BigDecimal seuil) {
    }

    public record MouvementVue(UUID id, UUID articleId, String article, String unite, TypeMouvement type,
            BigDecimal quantite, BigDecimal stockApres, LocalDate date, String motif, String auteur) {
    }

    // ---------------- Inventaires

    public record InventaireResumeVue(UUID id, UUID atelierId, String libelle, StatutInventaire statut,
            Instant ouvertLe, Instant closLe, int ecarts) {
    }

    public record LigneMatiereVue(UUID articleId, String code, String designation, String unite,
            BigDecimal quantiteTheorique, BigDecimal quantiteConstatee, BigDecimal ecart) {
    }

    public record LigneEquipementVue(UUID equipementId, String designation, String numeroInventaire,
            EtatEquipement etatTheorique, EtatEquipement etatConstate, String observation) {
    }

    public record InventaireVue(UUID id, UUID atelierId, String atelierCode, String atelierNom, String libelle,
            StatutInventaire statut, Instant ouvertLe, String ouvertPar, Instant closLe, String closPar,
            String observations, List<LigneMatiereVue> matieres, List<LigneEquipementVue> equipements, int restantes,
            boolean modifiable) {
    }

    public record DemandeInventaire(String libelle) {
    }

    public record SaisieMatiere(UUID articleId, BigDecimal quantiteConstatee) {
    }

    public record SaisieEquipement(UUID equipementId, EtatEquipement etatConstate, String observation) {
    }

    public record SaisieInventaire(List<SaisieMatiere> matieres, List<SaisieEquipement> equipements,
            String observations) {
    }
}
