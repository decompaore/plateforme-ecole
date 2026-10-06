package bf.edutech.plateforme.emploidutemps;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/**
 * Une case de l'emploi du temps d'une classe. L'enseignant n'est pas recopié : c'est celui
 * affecté à la matière dans la classe.
 */
@Entity
@Table(name = "seance_emploi")
public class SeanceEmploi extends EntiteCloisonnee {

    @Column(name = "annee_id", nullable = false, updatable = false)
    private UUID anneeId;

    @Column(name = "classe_id", nullable = false)
    private UUID classeId;

    @Column(name = "matiere_id", nullable = false)
    private UUID matiereId;

    @Column(name = "jour", nullable = false)
    private short jour;

    @Column(name = "creneau_id", nullable = false)
    private UUID creneauId;

    @Column(name = "groupe", length = 20)
    private String groupe;

    @Column(name = "atelier_id")
    private UUID atelierId;

    @Column(name = "salle", length = 40)
    private String salle;

    @Column(name = "modifie_par")
    private UUID modifiePar;

    @Column(name = "modifie_le", nullable = false)
    private Instant modifieLe;

    protected SeanceEmploi() {
    }

    SeanceEmploi(UUID id, UUID anneeId) {
        if (id != null) {
            imposerId(id);
        }
        this.anneeId = anneeId;
    }

    void placer(UUID classeId, UUID matiereId, int jour, UUID creneauId, String groupe, UUID atelierId, String salle,
            UUID par, Instant maintenant) {
        this.classeId = classeId;
        this.matiereId = matiereId;
        this.jour = (short) jour;
        this.creneauId = creneauId;
        this.groupe = groupe;
        this.atelierId = atelierId;
        this.salle = salle;
        this.modifiePar = par;
        this.modifieLe = maintenant;
    }

    UUID getAnneeId() {
        return anneeId;
    }

    UUID getClasseId() {
        return classeId;
    }

    UUID getMatiereId() {
        return matiereId;
    }

    int getJour() {
        return jour;
    }

    UUID getCreneauId() {
        return creneauId;
    }

    String getGroupe() {
        return groupe;
    }

    UUID getAtelierId() {
        return atelierId;
    }

    String getSalle() {
        return salle;
    }

    Instant getModifieLe() {
        return modifieLe;
    }
}
