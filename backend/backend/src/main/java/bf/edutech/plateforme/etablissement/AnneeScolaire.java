package bf.edutech.plateforme.etablissement;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Année scolaire d'un établissement et ses transitions d'état. */
@Entity
@Table(name = "annee_scolaire")
public class AnneeScolaire extends EntiteCloisonnee {

    @Column(name = "libelle", nullable = false, length = 20, updatable = false)
    private String libelle;

    @Column(name = "debut", nullable = false)
    private LocalDate debut;

    @Column(name = "fin", nullable = false)
    private LocalDate fin;

    @Enumerated(EnumType.STRING)
    @Column(name = "etat", nullable = false, length = 12)
    private EtatAnnee etat = EtatAnnee.PREPARATION;

    protected AnneeScolaire() {
    }

    AnneeScolaire(String libelle, LocalDate debut, LocalDate fin) {
        this.libelle = libelle;
        this.debut = debut;
        this.fin = fin;
    }

    void ouvrir() {
        exigerEtat(EtatAnnee.PREPARATION, "ouvrir");
        etat = EtatAnnee.ACTIVE;
    }

    void cloturer() {
        exigerEtat(EtatAnnee.ACTIVE, "clôturer");
        etat = EtatAnnee.CLOTUREE;
    }

    void archiver() {
        exigerEtat(EtatAnnee.CLOTUREE, "archiver");
        etat = EtatAnnee.ARCHIVEE;
    }

    /** Structure, périodes et classes modifiables tant que l'année n'est ni clôturée ni archivée. */
    boolean estModifiable() {
        return etat == EtatAnnee.PREPARATION || etat == EtatAnnee.ACTIVE;
    }

    /** Vrai si la date appartient à l'année. */
    boolean contient(LocalDate date) {
        return !date.isBefore(debut) && !date.isAfter(fin);
    }

    private void exigerEtat(EtatAnnee attendu, String action) {
        if (etat != attendu) {
            throw new RegleMetierException("TRANSITION_INVALIDE",
                    "Impossible de " + action + " l'année " + libelle + " : elle est à l'état " + etat);
        }
    }

    String getLibelle() {
        return libelle;
    }

    LocalDate getDebut() {
        return debut;
    }

    LocalDate getFin() {
        return fin;
    }

    EtatAnnee getEtat() {
        return etat;
    }
}
