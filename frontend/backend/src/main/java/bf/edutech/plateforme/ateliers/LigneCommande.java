package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Article commandé : quantité et prix unitaire. */
@Entity
@Table(name = "ligne_commande")
public class LigneCommande extends EntiteCloisonnee {

    @Column(name = "commande_id", nullable = false, updatable = false)
    private UUID commandeId;

    @Column(name = "article_id", nullable = false, updatable = false)
    private UUID articleId;

    @Column(name = "quantite", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantite;

    @Column(name = "prix_unitaire", nullable = false)
    private long prixUnitaire;

    protected LigneCommande() {
    }

    LigneCommande(UUID commandeId, UUID articleId, BigDecimal quantite, long prixUnitaire) {
        this.commandeId = commandeId;
        this.articleId = articleId;
        this.quantite = quantite;
        this.prixUnitaire = prixUnitaire;
    }

    public UUID getCommandeId() {
        return commandeId;
    }

    public UUID getArticleId() {
        return articleId;
    }

    public BigDecimal getQuantite() {
        return quantite;
    }

    public long getPrixUnitaire() {
        return prixUnitaire;
    }
}
