package bf.edutech.plateforme.scolarite;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Frais facultatif choisi par un élève (cantine, transport…). */
@Entity
@Table(name = "souscription_frais")
public class SouscriptionFrais extends EntiteCloisonnee {

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "frais_id", nullable = false, updatable = false)
    private UUID fraisId;

    @Column(name = "souscrit_le", nullable = false, updatable = false)
    private Instant souscritLe;

    protected SouscriptionFrais() {
    }

    SouscriptionFrais(UUID inscriptionId, UUID fraisId, Instant souscritLe) {
        this.inscriptionId = inscriptionId;
        this.fraisId = fraisId;
        this.souscritLe = souscritLe;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    UUID getFraisId() {
        return fraisId;
    }
}
