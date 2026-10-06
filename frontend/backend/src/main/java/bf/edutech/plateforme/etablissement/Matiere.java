package bf.edutech.plateforme.etablissement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Matière (discipline) ou module de compétences, commun à toutes les années de l'établissement. */
@Entity
@Table(name = "matiere")
public class Matiere extends EntiteCloisonnee {

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

    @Column(name = "libelle", nullable = false, length = 120)
    private String libelle;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TypeMatiere type;

    @Column(name = "actif", nullable = false)
    private boolean actif = true;

    protected Matiere() {
    }

    Matiere(String code, String libelle, TypeMatiere type) {
        this.code = code;
        this.libelle = libelle;
        this.type = type;
    }

    void modifier(String libelle, TypeMatiere type, boolean actif) {
        this.libelle = libelle;
        this.type = type;
        this.actif = actif;
    }

    String getCode() {
        return code;
    }

    String getLibelle() {
        return libelle;
    }

    TypeMatiere getType() {
        return type;
    }

    boolean isActif() {
        return actif;
    }
}
