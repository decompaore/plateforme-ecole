package bf.edutech.plateforme.scolarite;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Réduction accordée par l'école sur un frais (distincte d'une bourse). */
@Entity
@Table(name = "exoneration")
public class Exoneration extends EntiteCloisonnee {

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "frais_id", nullable = false, updatable = false)
    private UUID fraisId;

    @Column(name = "montant", nullable = false)
    private long montant;

    @Column(name = "motif", nullable = false, length = 200)
    private String motif;

    @Column(name = "accorde_par")
    private UUID accordePar;

    @Column(name = "accorde_le", nullable = false)
    private Instant accordeLe;

    protected Exoneration() {
    }

    Exoneration(UUID inscriptionId, UUID fraisId) {
        this.inscriptionId = inscriptionId;
        this.fraisId = fraisId;
    }

    void definir(long montant, String motif, UUID par, Instant le) {
        this.montant = montant;
        this.motif = motif;
        this.accordePar = par;
        this.accordeLe = le;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    UUID getFraisId() {
        return fraisId;
    }

    long getMontant() {
        return montant;
    }

    String getMotif() {
        return motif;
    }
}
