package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Article demandé par un atelier, avec la quantité retenue par la direction et le prix figé à la validation. */
@Entity
@Table(name = "ligne_besoin")
public class LigneBesoin extends EntiteCloisonnee {

    @Column(name = "besoin_id", nullable = false, updatable = false)
    private UUID besoinId;

    @Column(name = "article_id", nullable = false, updatable = false)
    private UUID articleId;

    @Column(name = "quantite_demandee", nullable = false, precision = 12, scale = 2)
    private BigDecimal quantiteDemandee;

    @Column(name = "justification", length = 300)
    private String justification;

    @Column(name = "propose_par")
    private UUID proposePar;

    @Column(name = "modifiee_le", nullable = false)
    private Instant modifieeLe;

    @Column(name = "quantite_retenue", precision = 12, scale = 2)
    private BigDecimal quantiteRetenue;

    @Column(name = "prix_unitaire")
    private Long prixUnitaire;

    protected LigneBesoin() {
    }

    LigneBesoin(UUID besoinId, UUID articleId) {
        this.besoinId = besoinId;
        this.articleId = articleId;
    }

    void demander(BigDecimal quantite, String justification, UUID par, Instant maintenant) {
        this.quantiteDemandee = quantite;
        this.justification = justification;
        this.proposePar = par;
        this.modifieeLe = maintenant;
    }

    void retenir(BigDecimal quantite) {
        this.quantiteRetenue = quantite;
    }

    void figerPrix(Long prix) {
        this.prixUnitaire = prix;
    }

    /** Quantité retenue, ou demandée tant que la direction n'a pas arbitré. */
    BigDecimal quantiteFinale() {
        return quantiteRetenue != null ? quantiteRetenue : quantiteDemandee;
    }

    public UUID getBesoinId() {
        return besoinId;
    }

    public UUID getArticleId() {
        return articleId;
    }

    public BigDecimal getQuantiteDemandee() {
        return quantiteDemandee;
    }

    public String getJustification() {
        return justification;
    }

    public UUID getProposePar() {
        return proposePar;
    }

    public Instant getModifieeLe() {
        return modifieeLe;
    }

    public BigDecimal getQuantiteRetenue() {
        return quantiteRetenue;
    }

    public Long getPrixUnitaire() {
        return prixUnitaire;
    }
}
