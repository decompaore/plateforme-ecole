package bf.edutech.plateforme.eleves;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Dossier permanent de l'élève dans l'établissement (il suit toute sa scolarité). */
@Entity
@Table(name = "eleve")
public class Eleve extends EntiteCloisonnee {

    @Column(name = "matricule", nullable = false, length = 30)
    private String matricule;

    @Column(name = "nom", nullable = false, length = 80)
    private String nom;

    @Column(name = "prenoms", nullable = false, length = 120)
    private String prenoms;

    @Enumerated(EnumType.STRING)
    @Column(name = "sexe", nullable = false, length = 1)
    private Sexe sexe;

    @Column(name = "date_naissance", nullable = false)
    private LocalDate dateNaissance;

    @Column(name = "lieu_naissance", length = 80)
    private String lieuNaissance;

    @Column(name = "telephone", length = 20)
    private String telephone;

    @Column(name = "identifiant_national", length = 40)
    private String identifiantNational;

    @Column(name = "adresse", length = 200)
    private String adresse;

    @Column(name = "cree_le", insertable = false, updatable = false)
    private Instant creeLe;

    protected Eleve() {
    }

    Eleve(String matricule, IdentiteEleve identite) {
        this.matricule = matricule;
        definir(identite);
    }

    void definir(IdentiteEleve identite) {
        this.nom = identite.nom();
        this.prenoms = identite.prenoms();
        this.sexe = identite.sexe();
        this.dateNaissance = identite.dateNaissance();
        this.lieuNaissance = identite.lieuNaissance();
        this.telephone = identite.telephone();
        this.identifiantNational = identite.identifiantNational();
        this.adresse = identite.adresse();
    }

    void changerMatricule(String matricule) {
        this.matricule = matricule;
    }

    String getMatricule() {
        return matricule;
    }

    String getNom() {
        return nom;
    }

    String getPrenoms() {
        return prenoms;
    }

    String nomComplet() {
        return nom + " " + prenoms;
    }

    Sexe getSexe() {
        return sexe;
    }

    LocalDate getDateNaissance() {
        return dateNaissance;
    }

    String getLieuNaissance() {
        return lieuNaissance;
    }

    String getTelephone() {
        return telephone;
    }

    String getIdentifiantNational() {
        return identifiantNational;
    }

    String getAdresse() {
        return adresse;
    }

    /**
     * Identité normalisée (noms nettoyés, téléphone au format international).
     * Construite par {@link RegistreEleves#normaliser}.
     */
    record IdentiteEleve(String nom, String prenoms, Sexe sexe, LocalDate dateNaissance, String lieuNaissance,
            String telephone, String identifiantNational, String adresse) {
    }
}
