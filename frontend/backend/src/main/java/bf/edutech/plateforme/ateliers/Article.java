package bf.edutech.plateforme.ateliers;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/**
 * Article du catalogue des prix : matière d'œuvre (suivie en quantité) ou équipement (suivi un
 * par un). Spécifications et normes fixées par les enseignants spécialistes de la filière ; prix
 * de référence convenu avec l'intendant.
 */
@Entity
@Table(name = "article_catalogue")
public class Article extends EntiteCloisonnee {

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "designation", nullable = false, length = 150)
    private String designation;

    @Enumerated(EnumType.STRING)
    @Column(name = "nature", nullable = false, length = 16)
    private NatureArticle nature;

    @Column(name = "unite", nullable = false, length = 20)
    private String unite;

    @Column(name = "filiere_id")
    private UUID filiereId;

    @Column(name = "specifications", length = 2000)
    private String specifications;

    @Column(name = "normes", length = 500)
    private String normes;

    @Column(name = "prix_reference")
    private Long prixReference;

    @Column(name = "prix_modifie_le")
    private Instant prixModifieLe;

    @Column(name = "actif", nullable = false)
    private boolean actif = true;

    @Column(name = "photo_type", length = 20)
    private String photoType;

    protected Article() {
    }

    Article(String code, NatureArticle nature) {
        this.code = code;
        this.nature = nature;
    }

    void definir(String designation, String unite, UUID filiereId, String specifications, String normes, boolean actif) {
        this.designation = designation;
        this.unite = unite;
        this.filiereId = filiereId;
        this.specifications = specifications;
        this.normes = normes;
        this.actif = actif;
    }

    void definirPrix(Long prix, Instant maintenant) {
        if (prix == null ? prixReference != null : !prix.equals(prixReference)) {
            prixReference = prix;
            prixModifieLe = maintenant;
        }
    }

    void definirPhoto(String type) {
        this.photoType = type;
    }

    public String getCode() {
        return code;
    }

    public String getDesignation() {
        return designation;
    }

    public NatureArticle getNature() {
        return nature;
    }

    public String getUnite() {
        return unite;
    }

    public UUID getFiliereId() {
        return filiereId;
    }

    public String getSpecifications() {
        return specifications;
    }

    public String getNormes() {
        return normes;
    }

    public Long getPrixReference() {
        return prixReference;
    }

    public Instant getPrixModifieLe() {
        return prixModifieLe;
    }

    public boolean isActif() {
        return actif;
    }

    public String getPhotoType() {
        return photoType;
    }
}
