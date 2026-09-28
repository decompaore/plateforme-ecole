package bf.edutech.plateforme.evaluations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Devoir, interrogation, composition, TP… d'une matière dans une classe, pour une période. */
@Entity
@Table(name = "evaluation")
public class Evaluation extends EntiteCloisonnee {

    @Column(name = "classe_id", nullable = false, updatable = false)
    private UUID classeId;

    @Column(name = "matiere_id", nullable = false, updatable = false)
    private UUID matiereId;

    @Column(name = "periode_id", nullable = false, updatable = false)
    private UUID periodeId;

    @Column(name = "libelle", nullable = false, length = 80)
    private String libelle;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 14)
    private TypeEvaluation type;

    @Column(name = "date_evaluation", nullable = false)
    private LocalDate dateEvaluation;

    @Column(name = "bareme", nullable = false, precision = 5, scale = 2)
    private BigDecimal bareme;

    @Column(name = "poids", nullable = false, precision = 4, scale = 2)
    private BigDecimal poids;

    @Column(name = "cree_par", updatable = false)
    private UUID creePar;

    protected Evaluation() {
    }

    Evaluation(UUID classeId, UUID matiereId, UUID periodeId, UUID creePar) {
        this.classeId = classeId;
        this.matiereId = matiereId;
        this.periodeId = periodeId;
        this.creePar = creePar;
    }

    void definir(String libelle, TypeEvaluation type, LocalDate date, BigDecimal bareme, BigDecimal poids) {
        this.libelle = libelle;
        this.type = type;
        this.dateEvaluation = date;
        this.bareme = bareme;
        this.poids = poids;
    }

    UUID getClasseId() {
        return classeId;
    }

    UUID getMatiereId() {
        return matiereId;
    }

    UUID getPeriodeId() {
        return periodeId;
    }

    String getLibelle() {
        return libelle;
    }

    TypeEvaluation getType() {
        return type;
    }

    LocalDate getDateEvaluation() {
        return dateEvaluation;
    }

    BigDecimal getBareme() {
        return bareme;
    }

    BigDecimal getPoids() {
        return poids;
    }
}
