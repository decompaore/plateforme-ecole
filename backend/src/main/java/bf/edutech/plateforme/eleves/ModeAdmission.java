package bf.edutech.plateforme.eleves;

/**
 * Comment l'élève est entré dans l'établissement pour l'année (v0.47). Il détermine les pièces du dossier
 * d'inscription : un élève affecté par l'État (entrée en 6e ou en 2nde après le CEP ou le BEPC) n'apporte que la
 * décision d'affectation, un candidat au complément d'effectif un dossier complet examiné par la commission.
 */
public enum ModeAdmission {
    /** Affecté par l'État après un examen national (décision d'orientation). */
    AFFECTATION_ETAT("Affectation de l'État"),
    /** Place attribuée par la commission de recrutement (complément d'effectif). */
    COMPLEMENT_EFFECTIF("Complément d'effectif"),
    /** Venu d'un autre établissement en cours de cycle. */
    TRANSFERT("Transfert"),
    /** Déjà élève de l'établissement l'an passé (passage ou redoublement). */
    REINSCRIPTION("Réinscription"),
    /** Établissement privé : recrutement direct. */
    PRIVE("Recrutement direct (privé)"),
    AUTRE("Autre");

    private final String libelle;

    ModeAdmission(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }
}
