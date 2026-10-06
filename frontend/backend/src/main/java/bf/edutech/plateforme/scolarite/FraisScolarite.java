package bf.edutech.plateforme.scolarite;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Frais d'une année scolaire (inscription, scolarité, APE, tenue, cantine…). */
@Entity
@Table(name = "frais_scolarite")
public class FraisScolarite extends EntiteCloisonnee {

    @Column(name = "annee_id", nullable = false, updatable = false)
    private UUID anneeId;

    @Column(name = "libelle", nullable = false, length = 80)
    private String libelle;

    @Column(name = "montant", nullable = false)
    private long montant;

    @Column(name = "obligatoire", nullable = false)
    private boolean obligatoire;

    @Column(name = "couvert_par_bourse", nullable = false)
    private boolean couvertParBourse;

    @Enumerated(EnumType.STRING)
    @Column(name = "portee", nullable = false, length = 8)
    private Portee portee;

    @Column(name = "cree_le", nullable = false, updatable = false)
    private Instant creeLe;

    protected FraisScolarite() {
    }

    FraisScolarite(UUID anneeId, Instant creeLe) {
        this.anneeId = anneeId;
        this.creeLe = creeLe;
    }

    void definir(String libelle, long montant, boolean obligatoire, boolean couvertParBourse, Portee portee) {
        this.libelle = libelle;
        this.montant = montant;
        this.obligatoire = obligatoire;
        this.couvertParBourse = couvertParBourse;
        this.portee = portee;
    }

    UUID getAnneeId() {
        return anneeId;
    }

    String getLibelle() {
        return libelle;
    }

    long getMontant() {
        return montant;
    }

    boolean isObligatoire() {
        return obligatoire;
    }

    boolean isCouvertParBourse() {
        return couvertParBourse;
    }

    Portee getPortee() {
        return portee;
    }
}
