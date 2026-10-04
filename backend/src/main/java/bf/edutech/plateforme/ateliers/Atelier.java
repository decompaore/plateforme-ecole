package bf.edutech.plateforme.ateliers;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Atelier de l'établissement ; les filières qu'il sert sont dans {@link AtelierFiliere}. */
@Entity
@Table(name = "atelier")
public class Atelier extends EntiteCloisonnee {

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "nom", nullable = false, length = 120)
    private String nom;

    @Column(name = "emplacement", length = 120)
    private String emplacement;

    @Column(name = "postes")
    private Short postes;

    @Column(name = "ouvert", nullable = false)
    private boolean ouvert = true;

    @Column(name = "observations", length = 500)
    private String observations;

    protected Atelier() {
    }

    Atelier(String code) {
        this.code = code;
    }

    void definir(String code, String nom, String emplacement, Short postes, boolean ouvert, String observations) {
        this.code = code;
        this.nom = nom;
        this.emplacement = emplacement;
        this.postes = postes;
        this.ouvert = ouvert;
        this.observations = observations;
    }

    public String getCode() {
        return code;
    }

    public String getNom() {
        return nom;
    }

    public String getEmplacement() {
        return emplacement;
    }

    public Short getPostes() {
        return postes;
    }

    public boolean isOuvert() {
        return ouvert;
    }

    public String getObservations() {
        return observations;
    }
}
