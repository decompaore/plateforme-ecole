package bf.edutech.plateforme.etablissement;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Matière enseignée dans une classe : coefficient, groupe de matières, volumes horaires. */
@Entity
@Table(name = "classe_matiere")
public class ClasseMatiere extends EntiteCloisonnee {

    @Column(name = "classe_id", nullable = false, updatable = false)
    private UUID classeId;

    @Column(name = "matiere_id", nullable = false, updatable = false)
    private UUID matiereId;

    @Column(name = "coefficient", nullable = false, precision = 4, scale = 1)
    private BigDecimal coefficient;

    @Column(name = "groupe", length = 40)
    private String groupe;

    @Column(name = "volume_hebdo", precision = 4, scale = 1)
    private BigDecimal volumeHebdo;

    @Column(name = "volume_total", precision = 6, scale = 1)
    private BigDecimal volumeTotal;

    protected ClasseMatiere() {
    }

    ClasseMatiere(UUID classeId, UUID matiereId) {
        this.classeId = classeId;
        this.matiereId = matiereId;
    }

    void definir(BigDecimal coefficient, String groupe, BigDecimal volumeHebdo, BigDecimal volumeTotal) {
        this.coefficient = coefficient;
        this.groupe = groupe;
        this.volumeHebdo = volumeHebdo;
        this.volumeTotal = volumeTotal;
    }

    UUID getClasseId() {
        return classeId;
    }

    UUID getMatiereId() {
        return matiereId;
    }

    BigDecimal getCoefficient() {
        return coefficient;
    }

    String getGroupe() {
        return groupe;
    }

    BigDecimal getVolumeHebdo() {
        return volumeHebdo;
    }

    BigDecimal getVolumeTotal() {
        return volumeTotal;
    }
}
