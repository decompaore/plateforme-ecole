package bf.edutech.plateforme.ateliers;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Équipement d'un atelier, inventorié un par un (numéro d'inventaire unique dans l'établissement). */
@Entity
@Table(name = "equipement")
public class Equipement extends EntiteCloisonnee {

    @Column(name = "atelier_id", nullable = false)
    private UUID atelierId;

    @Column(name = "article_id")
    private UUID articleId;

    @Column(name = "designation", nullable = false, length = 150)
    private String designation;

    @Column(name = "numero_inventaire", nullable = false, length = 40)
    private String numeroInventaire;

    @Column(name = "marque", length = 60)
    private String marque;

    @Column(name = "numero_serie", length = 60)
    private String numeroSerie;

    @Column(name = "date_acquisition")
    private LocalDate dateAcquisition;

    @Column(name = "valeur")
    private Long valeur;

    @Enumerated(EnumType.STRING)
    @Column(name = "etat", nullable = false, length = 10)
    private EtatEquipement etat = EtatEquipement.BON;

    @Column(name = "observations", length = 500)
    private String observations;

    protected Equipement() {
    }

    Equipement(UUID atelierId) {
        this.atelierId = atelierId;
    }

    void definir(UUID articleId, String designation, String numeroInventaire, String marque, String numeroSerie,
            LocalDate dateAcquisition, Long valeur, String observations) {
        this.articleId = articleId;
        this.designation = designation;
        this.numeroInventaire = numeroInventaire;
        this.marque = marque;
        this.numeroSerie = numeroSerie;
        this.dateAcquisition = dateAcquisition;
        this.valeur = valeur;
        this.observations = observations;
    }

    void changerEtat(EtatEquipement etat) {
        this.etat = etat;
    }

    void deplacer(UUID atelierId) {
        this.atelierId = atelierId;
    }

    public UUID getAtelierId() {
        return atelierId;
    }

    public UUID getArticleId() {
        return articleId;
    }

    public String getDesignation() {
        return designation;
    }

    public String getNumeroInventaire() {
        return numeroInventaire;
    }

    public String getMarque() {
        return marque;
    }

    public String getNumeroSerie() {
        return numeroSerie;
    }

    public LocalDate getDateAcquisition() {
        return dateAcquisition;
    }

    public Long getValeur() {
        return valeur;
    }

    public EtatEquipement getEtat() {
        return etat;
    }

    public String getObservations() {
        return observations;
    }
}
