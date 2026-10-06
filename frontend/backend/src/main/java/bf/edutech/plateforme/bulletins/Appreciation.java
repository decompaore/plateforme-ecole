package bf.edutech.plateforme.bulletins;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Appréciation d'un enseignant pour un élève, dans une matière, sur une période. */
@Entity
@Table(name = "appreciation")
public class Appreciation extends EntiteCloisonnee {

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "periode_id", nullable = false, updatable = false)
    private UUID periodeId;

    @Column(name = "matiere_id", nullable = false, updatable = false)
    private UUID matiereId;

    @Column(name = "texte", nullable = false, length = 200)
    private String texte;

    @Column(name = "saisi_par")
    private UUID saisiPar;

    @Column(name = "saisi_le", nullable = false)
    private Instant saisiLe;

    protected Appreciation() {
    }

    Appreciation(UUID inscriptionId, UUID periodeId, UUID matiereId) {
        this.inscriptionId = inscriptionId;
        this.periodeId = periodeId;
        this.matiereId = matiereId;
    }

    void definir(String texte, UUID par, Instant le) {
        this.texte = texte;
        this.saisiPar = par;
        this.saisiLe = le;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    UUID getMatiereId() {
        return matiereId;
    }

    String getTexte() {
        return texte;
    }
}
