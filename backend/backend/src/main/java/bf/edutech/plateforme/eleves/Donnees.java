package bf.edutech.plateforme.eleves;

import java.time.LocalDate;

import bf.edutech.plateforme.socle.referentiel.Sexe;

/** Données saisies (avant normalisation) pour les services du module. */
public final class Donnees {

    private Donnees() {
    }

    /** Identité de l'élève. Le matricule est facultatif : il est généré s'il est absent. */
    public record DonneesEleve(String matricule, String nom, String prenoms, Sexe sexe, LocalDate dateNaissance,
            String lieuNaissance, String telephone, String identifiantNational, String adresse) {
    }

    /**
     * Responsable à rattacher à un élève. S'il existe déjà un responsable avec ce
     * téléphone (frère ou sœur déjà inscrit), il est réutilisé tel quel.
     */
    public record DonneesResponsable(String nom, String prenoms, String telephone, LienParente lien,
            String profession, LangueSms langueSms, Boolean responsableLegal, Boolean contactPrioritaire) {
    }
}
