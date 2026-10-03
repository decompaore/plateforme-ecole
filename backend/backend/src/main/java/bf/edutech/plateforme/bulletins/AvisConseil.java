package bf.edutech.plateforme.bulletins;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/**
 * Avis du conseil de classe pour un élève et une période : appréciation
 * générale et distinction (null : la distinction proposée par les seuils s'applique).
 */
@Entity
@Table(name = "avis_conseil")
public class AvisConseil extends EntiteCloisonnee {

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "periode_id", nullable = false, updatable = false)
    private UUID periodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "distinction", length = 24)
    private Distinction distinction;

    @Column(name = "appreciation", length = 300)
    private String appreciation;

    @Column(name = "saisi_par")
    private UUID saisiPar;

    @Column(name = "saisi_le", nullable = false)
    private Instant saisiLe;

    protected AvisConseil() {
    }

    AvisConseil(UUID inscriptionId, UUID periodeId) {
        this.inscriptionId = inscriptionId;
        this.periodeId = periodeId;
    }

    void definir(Distinction distinction, String appreciation, UUID par, Instant le) {
        this.distinction = distinction;
        this.appreciation = appreciation;
        this.saisiPar = par;
        this.saisiLe = le;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    Distinction getDistinction() {
        return distinction;
    }

    String getAppreciation() {
        return appreciation;
    }
}
