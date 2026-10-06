package bf.edutech.plateforme.ateliers;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/**
 * Inventaire d'un atelier : à l'ouverture, le stock et les équipements du moment sont recopiés ;
 * le responsable saisit ce qu'il constate ; la clôture corrige le stock et l'état des équipements.
 */
@Entity
@Table(name = "inventaire")
public class Inventaire extends EntiteCloisonnee {

    @Column(name = "atelier_id", nullable = false, updatable = false)
    private UUID atelierId;

    @Column(name = "libelle", nullable = false, length = 80)
    private String libelle;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 8)
    private StatutInventaire statut = StatutInventaire.EN_COURS;

    @Column(name = "ouvert_par", updatable = false)
    private UUID ouvertPar;

    @Column(name = "ouvert_le", nullable = false, updatable = false)
    private Instant ouvertLe;

    @Column(name = "clos_par")
    private UUID closPar;

    @Column(name = "clos_le")
    private Instant closLe;

    @Column(name = "observations", length = 1000)
    private String observations;

    protected Inventaire() {
    }

    Inventaire(UUID atelierId, String libelle, UUID ouvertPar, Instant maintenant) {
        this.atelierId = atelierId;
        this.libelle = libelle;
        this.ouvertPar = ouvertPar;
        this.ouvertLe = maintenant;
    }

    void exigerEnCours() {
        if (statut != StatutInventaire.EN_COURS) {
            throw new RegleMetierException("INVENTAIRE_CLOS", "Cet inventaire est clos : il ne se modifie plus");
        }
    }

    void observer(String observations) {
        this.observations = observations;
    }

    void clore(UUID par, Instant maintenant) {
        exigerEnCours();
        this.statut = StatutInventaire.CLOS;
        this.closPar = par;
        this.closLe = maintenant;
    }

    public UUID getAtelierId() {
        return atelierId;
    }

    public String getLibelle() {
        return libelle;
    }

    public StatutInventaire getStatut() {
        return statut;
    }

    public UUID getOuvertPar() {
        return ouvertPar;
    }

    public Instant getOuvertLe() {
        return ouvertLe;
    }

    public UUID getClosPar() {
        return closPar;
    }

    public Instant getClosLe() {
        return closLe;
    }

    public String getObservations() {
        return observations;
    }
}
