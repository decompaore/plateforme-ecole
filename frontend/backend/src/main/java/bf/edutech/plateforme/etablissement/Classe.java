package bf.edutech.plateforme.etablissement;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Classe (ou groupe) d'une année scolaire, rattachée à une filière. */
@Entity
@Table(name = "classe")
public class Classe extends EntiteCloisonnee {

    @Column(name = "annee_id", nullable = false, updatable = false)
    private UUID anneeId;

    @Column(name = "filiere_id", nullable = false)
    private UUID filiereId;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "niveau", nullable = false, length = 30)
    private String niveau;

    @Column(name = "effectif_max")
    private Short effectifMax;

    protected Classe() {
    }

    Classe(UUID anneeId, UUID filiereId, String code, String niveau, Short effectifMax) {
        this.anneeId = anneeId;
        this.filiereId = filiereId;
        this.code = code;
        this.niveau = niveau;
        this.effectifMax = effectifMax;
    }

    void modifier(UUID filiereId, String code, String niveau, Short effectifMax) {
        this.filiereId = filiereId;
        this.code = code;
        this.niveau = niveau;
        this.effectifMax = effectifMax;
    }

    UUID getAnneeId() {
        return anneeId;
    }

    UUID getFiliereId() {
        return filiereId;
    }

    String getCode() {
        return code;
    }

    String getNiveau() {
        return niveau;
    }

    Short getEffectifMax() {
        return effectifMax;
    }
}
