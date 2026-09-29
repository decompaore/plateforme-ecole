package bf.edutech.plateforme.evaluations;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Niveau de maîtrise d'une compétence par un apprenant, pour une période. */
@Entity
@Table(name = "resultat_competence")
public class ResultatCompetence extends EntiteCloisonnee {

    @Column(name = "competence_id", nullable = false, updatable = false)
    private UUID competenceId;

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "periode_id", nullable = false, updatable = false)
    private UUID periodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "niveau", nullable = false, length = 10)
    private NiveauMaitrise niveau;

    @Column(name = "evalue_par")
    private UUID evaluePar;

    @Column(name = "evalue_le", nullable = false)
    private Instant evalueLe;

    protected ResultatCompetence() {
    }

    ResultatCompetence(UUID competenceId, UUID inscriptionId, UUID periodeId) {
        this.competenceId = competenceId;
        this.inscriptionId = inscriptionId;
        this.periodeId = periodeId;
    }

    void definir(NiveauMaitrise niveau, UUID par, Instant le) {
        this.niveau = niveau;
        this.evaluePar = par;
        this.evalueLe = le;
    }

    UUID getCompetenceId() {
        return competenceId;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    NiveauMaitrise getNiveau() {
        return niveau;
    }
}
