package bf.edutech.plateforme.scolarite;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Bourse d'une inscription : l'organisme paie {@code taux} % des frais couverts. */
@Entity
@Table(name = "prise_en_charge")
public class PriseEnCharge extends EntiteCloisonnee {

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "organisme_id", nullable = false)
    private UUID organismeId;

    @Column(name = "taux", nullable = false, precision = 5, scale = 2)
    private BigDecimal taux;

    @Column(name = "reference_decision", length = 60)
    private String referenceDecision;

    @Column(name = "date_decision")
    private LocalDate dateDecision;

    @Column(name = "saisi_par")
    private UUID saisiPar;

    @Column(name = "saisi_le", nullable = false)
    private Instant saisiLe;

    protected PriseEnCharge() {
    }

    PriseEnCharge(UUID inscriptionId) {
        this.inscriptionId = inscriptionId;
    }

    void definir(UUID organismeId, BigDecimal taux, String referenceDecision, LocalDate dateDecision, UUID par,
            Instant le) {
        this.organismeId = organismeId;
        this.taux = taux;
        this.referenceDecision = referenceDecision;
        this.dateDecision = dateDecision;
        this.saisiPar = par;
        this.saisiLe = le;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    UUID getOrganismeId() {
        return organismeId;
    }

    BigDecimal getTaux() {
        return taux;
    }

    String getReferenceDecision() {
        return referenceDecision;
    }

    LocalDate getDateDecision() {
        return dateDecision;
    }
}
