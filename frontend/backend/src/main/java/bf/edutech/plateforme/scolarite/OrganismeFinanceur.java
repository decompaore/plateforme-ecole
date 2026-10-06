package bf.edutech.plateforme.scolarite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Organisme qui paie tout ou partie des frais d'élèves boursiers. */
@Entity
@Table(name = "organisme_financeur")
public class OrganismeFinanceur extends EntiteCloisonnee {

    @Column(name = "nom", nullable = false, length = 120)
    private String nom;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 12)
    private TypeOrganisme type;

    @Column(name = "telephone", length = 20)
    private String telephone;

    @Column(name = "actif", nullable = false)
    private boolean actif = true;

    protected OrganismeFinanceur() {
    }

    OrganismeFinanceur(String nom, TypeOrganisme type, String telephone) {
        modifier(nom, type, telephone, true);
    }

    void modifier(String nom, TypeOrganisme type, String telephone, boolean actif) {
        this.nom = nom;
        this.type = type;
        this.telephone = telephone;
        this.actif = actif;
    }

    String getNom() {
        return nom;
    }

    TypeOrganisme getType() {
        return type;
    }

    String getTelephone() {
        return telephone;
    }

    boolean isActif() {
        return actif;
    }
}
