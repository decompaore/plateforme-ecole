package bf.edutech.plateforme.absences;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Élève absent ou en retard lors d'un appel. */
@Entity
@Table(name = "absence")
public class Absence extends EntiteCloisonnee {

    @Column(name = "appel_id", nullable = false, updatable = false)
    private UUID appelId;

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 8)
    private TypeAbsence type;

    @Column(name = "minutes_retard")
    private Short minutesRetard;

    protected Absence() {
    }

    Absence(UUID appelId, UUID inscriptionId, TypeAbsence type, Short minutesRetard) {
        this.appelId = appelId;
        this.inscriptionId = inscriptionId;
        definir(type, minutesRetard);
    }

    void definir(TypeAbsence type, Short minutesRetard) {
        this.type = type;
        this.minutesRetard = type == TypeAbsence.RETARD ? minutesRetard : null;
    }

    UUID getAppelId() {
        return appelId;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    TypeAbsence getType() {
        return type;
    }

    Short getMinutesRetard() {
        return minutesRetard;
    }
}
