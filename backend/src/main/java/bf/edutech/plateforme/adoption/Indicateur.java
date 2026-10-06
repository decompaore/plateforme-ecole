package bf.edutech.plateforme.adoption;

/** Actions comptées chaque jour pour chaque établissement (fonction SQL calculer_adoption). */
public enum Indicateur {
    APPELS("Appels faits"),
    NOTES("Notes saisies"),
    EVALUATIONS("Évaluations créées"),
    CAHIER("Séances du cahier de textes"),
    JUSTIFICATIFS("Justificatifs d'absence"),
    INCIDENTS("Incidents de vie scolaire"),
    PAIEMENTS("Paiements au guichet"),
    MOBILE_MONEY("Paiements Mobile Money"),
    SMS("SMS envoyés"),
    BULLETINS("Publications de bulletins"),
    PROGRESSIONS("Fiches de progression soumises"),
    EMPLOI_DU_TEMPS("Séances d'emploi du temps placées"),
    ATELIERS("Mouvements de stock des ateliers"),
    ELEVES("Élèves ajoutés");

    private final String libelle;

    Indicateur(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }
}
