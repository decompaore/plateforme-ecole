package bf.edutech.plateforme.scolarite;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Tranche d'un frais : montant à payer avant une date limite. */
@Entity
@Table(name = "tranche_frais")
public class TrancheFrais extends EntiteCloisonnee {

    @Column(name = "frais_id", nullable = false, updatable = false)
    private UUID fraisId;

    @Column(name = "numero", nullable = false, updatable = false)
    private short numero;

    @Column(name = "date_limite", nullable = false, updatable = false)
    private LocalDate dateLimite;

    @Column(name = "montant", nullable = false, updatable = false)
    private long montant;

    protected TrancheFrais() {
    }

    TrancheFrais(UUID fraisId, int numero, LocalDate dateLimite, long montant) {
        this.fraisId = fraisId;
        this.numero = (short) numero;
        this.dateLimite = dateLimite;
        this.montant = montant;
    }

    UUID getFraisId() {
        return fraisId;
    }

    int getNumero() {
        return numero;
    }

    LocalDate getDateLimite() {
        return dateLimite;
    }

    long getMontant() {
        return montant;
    }
}
