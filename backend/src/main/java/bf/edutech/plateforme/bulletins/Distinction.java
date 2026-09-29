package bf.edutech.plateforme.bulletins;

/** Distinction ou sanction décidée en conseil de classe. */
public enum Distinction {
    AUCUNE("—"),
    TABLEAU_HONNEUR("Tableau d'honneur"),
    ENCOURAGEMENTS("Encouragements"),
    FELICITATIONS("Félicitations"),
    AVERTISSEMENT_TRAVAIL("Avertissement (travail)"),
    AVERTISSEMENT_CONDUITE("Avertissement (conduite)"),
    BLAME("Blâme");

    private final String libelle;

    Distinction(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }
}
