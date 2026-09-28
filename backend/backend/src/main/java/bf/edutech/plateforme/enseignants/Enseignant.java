package bf.edutech.plateforme.enseignants;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteUuid;
import bf.edutech.plateforme.socle.referentiel.Sexe;

/**
 * Identité de l'enseignant, unique sur toute la plateforme (pas d'établissement).
 * La Row-Level Security ne la montre qu'aux établissements où il est engagé ou invité.
 */
@Entity
@Table(name = "enseignant")
public class Enseignant extends EntiteUuid {

    @Column(name = "utilisateur_id", nullable = false, updatable = false)
    private UUID utilisateurId;

    @Column(name = "telephone", nullable = false, length = 20)
    private String telephone;

    @Column(name = "matricule_fp", length = 30)
    private String matriculeFp;

    @Column(name = "nom", nullable = false, length = 80)
    private String nom;

    @Column(name = "prenoms", nullable = false, length = 120)
    private String prenoms;

    @Enumerated(EnumType.STRING)
    @Column(name = "sexe", nullable = false, length = 1)
    private Sexe sexe;

    @Column(name = "specialite", length = 80)
    private String specialite;

    @Column(name = "cree_le", insertable = false, updatable = false)
    private Instant creeLe;

    protected Enseignant() {
    }

    Enseignant(UUID utilisateurId, String telephone, String matriculeFp, String nom, String prenoms, Sexe sexe,
            String specialite) {
        this.utilisateurId = utilisateurId;
        this.telephone = telephone;
        modifier(matriculeFp, nom, prenoms, sexe, specialite);
    }

    void modifier(String matriculeFp, String nom, String prenoms, Sexe sexe, String specialite) {
        this.matriculeFp = matriculeFp;
        this.nom = nom;
        this.prenoms = prenoms;
        this.sexe = sexe;
        this.specialite = specialite;
    }

    UUID getUtilisateurId() {
        return utilisateurId;
    }

    String getTelephone() {
        return telephone;
    }

    String getMatriculeFp() {
        return matriculeFp;
    }

    String getNom() {
        return nom;
    }

    String getPrenoms() {
        return prenoms;
    }

    Sexe getSexe() {
        return sexe;
    }

    String getSpecialite() {
        return specialite;
    }
}
