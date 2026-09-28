package bf.edutech.plateforme.etablissement;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Filière (série, spécialité ou métier) ; elle porte le profil pédagogique de ses classes. */
@Entity
@Table(name = "filiere")
public class Filiere extends EntiteCloisonnee {

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

    @Column(name = "libelle", nullable = false, length = 120)
    private String libelle;

    @Column(name = "cycle", nullable = false, length = 30)
    private String cycle;

    @Column(name = "diplome_vise", length = 40)
    private String diplomeVise;

    @Column(name = "profil_id", nullable = false)
    private UUID profilId;

    protected Filiere() {
    }

    Filiere(String code, String libelle, String cycle, String diplomeVise, UUID profilId) {
        this.code = code;
        this.libelle = libelle;
        this.cycle = cycle;
        this.diplomeVise = diplomeVise;
        this.profilId = profilId;
    }

    void modifier(String libelle, String cycle, String diplomeVise, UUID profilId) {
        this.libelle = libelle;
        this.cycle = cycle;
        this.diplomeVise = diplomeVise;
        this.profilId = profilId;
    }

    String getCode() {
        return code;
    }

    String getLibelle() {
        return libelle;
    }

    String getCycle() {
        return cycle;
    }

    String getDiplomeVise() {
        return diplomeVise;
    }

    UUID getProfilId() {
        return profilId;
    }
}
