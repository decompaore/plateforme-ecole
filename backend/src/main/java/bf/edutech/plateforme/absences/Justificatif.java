package bf.edutech.plateforme.absences;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Période pendant laquelle les absences d'un élève sont justifiées. */
@Entity
@Table(name = "justificatif")
public class Justificatif extends EntiteCloisonnee {

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "du", nullable = false)
    private LocalDate du;

    @Column(name = "au", nullable = false)
    private LocalDate au;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 12)
    private TypeJustificatif type;

    @Column(name = "motif", length = 200)
    private String motif;

    @Column(name = "saisi_par")
    private UUID saisiPar;

    protected Justificatif() {
    }

    Justificatif(UUID inscriptionId, LocalDate du, LocalDate au, TypeJustificatif type, String motif,
            UUID saisiPar) {
        this.inscriptionId = inscriptionId;
        this.du = du;
        this.au = au;
        this.type = type;
        this.motif = motif;
        this.saisiPar = saisiPar;
    }

    boolean couvre(LocalDate date) {
        return !date.isBefore(du) && !date.isAfter(au);
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    LocalDate getDu() {
        return du;
    }

    LocalDate getAu() {
        return au;
    }

    TypeJustificatif getType() {
        return type;
    }

    String getMotif() {
        return motif;
    }
}
