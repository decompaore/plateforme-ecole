package bf.edutech.plateforme.ateliers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Campagne d'expression des besoins (deux par année scolaire). */
@Entity
@Table(name = "campagne_besoins")
public class CampagneBesoins extends EntiteCloisonnee {

    @Column(name = "annee_id", nullable = false, updatable = false)
    private UUID anneeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16, updatable = false)
    private TypeCampagne type;

    @Column(name = "libelle", nullable = false, length = 120)
    private String libelle;

    @Column(name = "date_limite")
    private LocalDate dateLimite;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 10)
    private StatutCampagne statut = StatutCampagne.OUVERTE;

    @Column(name = "ouverte_par", updatable = false)
    private UUID ouvertePar;

    @Column(name = "ouverte_le", nullable = false, updatable = false)
    private Instant ouverteLe;

    @Column(name = "transmise_le")
    private Instant transmiseLe;

    @Column(name = "close_le")
    private Instant closeLe;

    @Column(name = "observations", length = 1000)
    private String observations;

    protected CampagneBesoins() {
    }

    CampagneBesoins(UUID anneeId, TypeCampagne type, UUID par, Instant maintenant) {
        this.anneeId = anneeId;
        this.type = type;
        this.ouvertePar = par;
        this.ouverteLe = maintenant;
    }

    void definir(String libelle, LocalDate dateLimite, String observations) {
        this.libelle = libelle;
        this.dateLimite = dateLimite;
        this.observations = observations;
    }

    void exigerOuverte() {
        if (statut != StatutCampagne.OUVERTE) {
            throw new RegleMetierException("CAMPAGNE_TRANSMISE",
                    "La campagne est transmise à la direction régionale : les besoins ne changent plus");
        }
    }

    void transmettre(Instant maintenant) {
        exigerOuverte();
        statut = StatutCampagne.TRANSMISE;
        transmiseLe = maintenant;
    }

    void clore(Instant maintenant) {
        if (statut != StatutCampagne.TRANSMISE) {
            throw new RegleMetierException("CAMPAGNE_NON_TRANSMISE", "Seule une campagne transmise peut être close");
        }
        statut = StatutCampagne.CLOSE;
        closeLe = maintenant;
    }

    public UUID getAnneeId() {
        return anneeId;
    }

    public TypeCampagne getType() {
        return type;
    }

    public String getLibelle() {
        return libelle;
    }

    public LocalDate getDateLimite() {
        return dateLimite;
    }

    public StatutCampagne getStatut() {
        return statut;
    }

    public Instant getOuverteLe() {
        return ouverteLe;
    }

    public Instant getTransmiseLe() {
        return transmiseLe;
    }

    public Instant getCloseLe() {
        return closeLe;
    }

    public String getObservations() {
        return observations;
    }
}
