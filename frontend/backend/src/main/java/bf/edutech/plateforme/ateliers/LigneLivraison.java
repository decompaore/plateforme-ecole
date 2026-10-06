package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Article reçu : quantité reçue, quantité conforme aux spécifications et normes, motif d'un refus. */
@Entity
@Table(name = "ligne_livraison")
public class LigneLivraison extends EntiteCloisonnee {

    @Column(name = "livraison_id", nullable = false, updatable = false)
    private UUID livraisonId;

    @Column(name = "article_id", nullable = false, updatable = false)
    private UUID articleId;

    @Column(name = "quantite_recue", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantiteRecue;

    @Column(name = "quantite_conforme", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantiteConforme;

    @Column(name = "motif_non_conformite", length = 300)
    private String motifNonConformite;

    protected LigneLivraison() {
    }

    LigneLivraison(UUID livraisonId, UUID articleId, BigDecimal recue, BigDecimal conforme, String motif) {
        this.livraisonId = livraisonId;
        this.articleId = articleId;
        this.quantiteRecue = recue;
        this.quantiteConforme = conforme;
        this.motifNonConformite = motif;
    }

    public UUID getLivraisonId() {
        return livraisonId;
    }

    public UUID getArticleId() {
        return articleId;
    }

    public BigDecimal getQuantiteRecue() {
        return quantiteRecue;
    }

    public BigDecimal getQuantiteConforme() {
        return quantiteConforme;
    }

    public String getMotifNonConformite() {
        return motifNonConformite;
    }
}
