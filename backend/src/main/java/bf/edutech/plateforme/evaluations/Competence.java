package bf.edutech.plateforme.evaluations;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Compétence du référentiel d'un module (matière de type MODULE_COMPETENCES). */
@Entity
@Table(name = "competence")
public class Competence extends EntiteCloisonnee {

    @Column(name = "matiere_id", nullable = false, updatable = false)
    private UUID matiereId;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "libelle", nullable = false, length = 200)
    private String libelle;

    @Column(name = "ordre", nullable = false)
    private short ordre;

    @Column(name = "actif", nullable = false)
    private boolean actif = true;

    protected Competence() {
    }

    Competence(UUID matiereId, String code, String libelle, short ordre) {
        this.matiereId = matiereId;
        this.code = code;
        this.libelle = libelle;
        this.ordre = ordre;
    }

    UUID getMatiereId() {
        return matiereId;
    }

    String getCode() {
        return code;
    }

    String getLibelle() {
        return libelle;
    }

    short getOrdre() {
        return ordre;
    }

    boolean isActif() {
        return actif;
    }
}
