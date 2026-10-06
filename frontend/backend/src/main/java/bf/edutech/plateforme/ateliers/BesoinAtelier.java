package bf.edutech.plateforme.ateliers;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Besoins d'un atelier dans une campagne : préparés avec les enseignants, transmis, validés. */
@Entity
@Table(name = "besoin_atelier")
public class BesoinAtelier extends EntiteCloisonnee {

    @Column(name = "campagne_id", nullable = false, updatable = false)
    private UUID campagneId;

    @Column(name = "atelier_id", nullable = false, updatable = false)
    private UUID atelierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 10)
    private StatutBesoin statut = StatutBesoin.BROUILLON;

    @Column(name = "transmis_par")
    private UUID transmisPar;

    @Column(name = "transmis_le")
    private Instant transmisLe;

    @Column(name = "valide_par")
    private UUID validePar;

    @Column(name = "valide_le")
    private Instant valideLe;

    @Column(name = "commentaire", length = 500)
    private String commentaire;

    protected BesoinAtelier() {
    }

    BesoinAtelier(UUID campagneId, UUID atelierId) {
        this.campagneId = campagneId;
        this.atelierId = atelierId;
    }

    void exigerBrouillon() {
        if (statut != StatutBesoin.BROUILLON) {
            throw new RegleMetierException("BESOINS_TRANSMIS", statut == StatutBesoin.VALIDE
                    ? "Les besoins de l'atelier sont validés : ils ne changent plus"
                    : "Les besoins sont transmis au chef des travaux : attendez sa réponse");
        }
    }

    void transmettre(UUID par, Instant maintenant) {
        exigerBrouillon();
        statut = StatutBesoin.TRANSMIS;
        transmisPar = par;
        transmisLe = maintenant;
    }

    void renvoyer(String commentaire) {
        if (statut != StatutBesoin.TRANSMIS) {
            throw new RegleMetierException("BESOINS_NON_TRANSMIS", "Seuls des besoins transmis peuvent être renvoyés");
        }
        statut = StatutBesoin.BROUILLON;
        this.commentaire = commentaire;
    }

    void exigerTransmis() {
        if (statut != StatutBesoin.TRANSMIS) {
            throw new RegleMetierException("BESOINS_NON_TRANSMIS", statut == StatutBesoin.VALIDE
                    ? "Les besoins de l'atelier sont déjà validés"
                    : "L'atelier n'a pas encore transmis ses besoins");
        }
    }

    void valider(UUID par, Instant maintenant) {
        exigerTransmis();
        statut = StatutBesoin.VALIDE;
        validePar = par;
        valideLe = maintenant;
    }

    public UUID getCampagneId() {
        return campagneId;
    }

    public UUID getAtelierId() {
        return atelierId;
    }

    public StatutBesoin getStatut() {
        return statut;
    }

    public UUID getTransmisPar() {
        return transmisPar;
    }

    public Instant getTransmisLe() {
        return transmisLe;
    }

    public UUID getValidePar() {
        return validePar;
    }

    public Instant getValideLe() {
        return valideLe;
    }

    public String getCommentaire() {
        return commentaire;
    }
}
