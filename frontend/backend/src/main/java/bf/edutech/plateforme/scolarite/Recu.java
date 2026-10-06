package bf.edutech.plateforme.scolarite;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Reçu numéroté d'un paiement ; immuable (la base refuse toute modification). */
@Entity
@Table(name = "recu")
public class Recu extends EntiteCloisonnee {

    @Column(name = "paiement_id", nullable = false, updatable = false)
    private UUID paiementId;

    @Column(name = "numero", nullable = false, length = 20, updatable = false)
    private String numero;

    @Column(name = "code_verification", nullable = false, length = 12, updatable = false)
    private String codeVerification;

    @Column(name = "emis_le", nullable = false, updatable = false)
    private Instant emisLe;

    protected Recu() {
    }

    Recu(UUID paiementId, String numero, String codeVerification, Instant emisLe) {
        this.paiementId = paiementId;
        this.numero = numero;
        this.codeVerification = codeVerification;
        this.emisLe = emisLe;
    }

    UUID getPaiementId() {
        return paiementId;
    }

    String getNumero() {
        return numero;
    }

    String getCodeVerification() {
        return codeVerification;
    }

    Instant getEmisLe() {
        return emisLe;
    }
}
