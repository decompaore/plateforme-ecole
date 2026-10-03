package bf.edutech.plateforme.passage;

/** Décision de fin d'année. */
public enum Decision {
    ADMIS("admis(e) en classe supérieure"),
    REDOUBLE("autorisé(e) à redoubler"),
    EXCLU("exclu(e)"),
    ORIENTE("orienté(e)"),
    CERTIFIE("certifié(e)"),
    NON_CERTIFIE("non certifié(e)"),
    /** Classe d'examen : la décision attend le résultat de l'examen national. */
    EN_ATTENTE_EXAMEN("en attente du résultat de l'examen");

    private final String libelle;

    Decision(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }
}
