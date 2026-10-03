package bf.edutech.plateforme.scolarite;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** SMS de relance envoyé à une famille en retard de paiement. */
@Entity
@Table(name = "relance")
public class Relance extends EntiteCloisonnee {

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "montant_du", nullable = false, updatable = false)
    private long montantDu;

    @Column(name = "envoye_par", updatable = false)
    private UUID envoyePar;

    @Column(name = "envoye_le", nullable = false, updatable = false)
    private Instant envoyeLe;

    protected Relance() {
    }

    Relance(UUID inscriptionId, long montantDu, UUID envoyePar, Instant envoyeLe) {
        this.inscriptionId = inscriptionId;
        this.montantDu = montantDu;
        this.envoyePar = envoyePar;
        this.envoyeLe = envoyeLe;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    Instant getEnvoyeLe() {
        return envoyeLe;
    }
}
