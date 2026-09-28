package bf.edutech.plateforme.etablissement;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Trimestre, semestre ou module d'une année, propre à un profil pédagogique. */
@Entity
@Table(name = "periode")
public class Periode extends EntiteCloisonnee {

    @Column(name = "annee_id", nullable = false, updatable = false)
    private UUID anneeId;

    @Column(name = "profil_id", nullable = false, updatable = false)
    private UUID profilId;

    @Column(name = "libelle", nullable = false, length = 40)
    private String libelle;

    @Column(name = "ordre", nullable = false)
    private short ordre;

    @Column(name = "debut", nullable = false)
    private LocalDate debut;

    @Column(name = "fin", nullable = false)
    private LocalDate fin;

    @Column(name = "verrouillee", nullable = false)
    private boolean verrouillee;

    protected Periode() {
    }

    Periode(UUID anneeId, UUID profilId, String libelle, short ordre, LocalDate debut, LocalDate fin) {
        this.anneeId = anneeId;
        this.profilId = profilId;
        this.libelle = libelle;
        this.ordre = ordre;
        this.debut = debut;
        this.fin = fin;
    }

    void modifier(String libelle, LocalDate debut, LocalDate fin) {
        this.libelle = libelle;
        this.debut = debut;
        this.fin = fin;
    }

    void verrouiller() {
        this.verrouillee = true;
    }

    void deverrouiller() {
        this.verrouillee = false;
    }

    boolean chevauche(LocalDate autreDebut, LocalDate autreFin) {
        return !autreFin.isBefore(debut) && !autreDebut.isAfter(fin);
    }

    UUID getAnneeId() {
        return anneeId;
    }

    UUID getProfilId() {
        return profilId;
    }

    String getLibelle() {
        return libelle;
    }

    short getOrdre() {
        return ordre;
    }

    LocalDate getDebut() {
        return debut;
    }

    LocalDate getFin() {
        return fin;
    }

    boolean isVerrouillee() {
        return verrouillee;
    }
}
