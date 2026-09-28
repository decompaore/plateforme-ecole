package bf.edutech.plateforme.eleves;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/**
 * Parent ou tuteur. Identifié par son numéro de téléphone dans l'établissement :
 * les frères et sœurs partagent le même responsable.
 */
@Entity
@Table(name = "responsable")
public class Responsable extends EntiteCloisonnee {

    @Column(name = "nom", nullable = false, length = 80)
    private String nom;

    @Column(name = "prenoms", nullable = false, length = 120)
    private String prenoms;

    @Column(name = "telephone", nullable = false, length = 20)
    private String telephone;

    @Column(name = "profession", length = 80)
    private String profession;

    @Enumerated(EnumType.STRING)
    @Column(name = "langue_sms", nullable = false, length = 10)
    private LangueSms langueSms = LangueSms.FR;

    /** Compte « espace parent » (null tant que l'espace n'est pas ouvert). */
    @Column(name = "utilisateur_id")
    private UUID utilisateurId;

    protected Responsable() {
    }

    Responsable(String nom, String prenoms, String telephone, String profession, LangueSms langueSms) {
        this.telephone = telephone;
        modifier(nom, prenoms, profession, langueSms);
    }

    void modifier(String nom, String prenoms, String profession, LangueSms langueSms) {
        this.nom = nom;
        this.prenoms = prenoms;
        this.profession = profession;
        this.langueSms = langueSms != null ? langueSms : LangueSms.FR;
    }

    void changerTelephone(String telephone) {
        this.telephone = telephone;
    }

    void lierCompte(UUID utilisateurId) {
        this.utilisateurId = utilisateurId;
    }

    String getNom() {
        return nom;
    }

    String getPrenoms() {
        return prenoms;
    }

    String getTelephone() {
        return telephone;
    }

    String getProfession() {
        return profession;
    }

    LangueSms getLangueSms() {
        return langueSms;
    }

    UUID getUtilisateurId() {
        return utilisateurId;
    }
}
