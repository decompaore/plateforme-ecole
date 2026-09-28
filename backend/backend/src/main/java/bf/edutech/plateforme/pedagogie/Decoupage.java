package bf.edutech.plateforme.pedagogie;

/** Découpage de l'année pour un profil. */
public enum Decoupage {
    TRIMESTRE(3),
    SEMESTRE(2),
    /** Modules de durée variable : les périodes sont saisies une à une. */
    MODULE(0);

    private final int nombrePeriodes;

    Decoupage(int nombrePeriodes) {
        this.nombrePeriodes = nombrePeriodes;
    }

    /** Nombre de périodes générées automatiquement (0 = saisie manuelle). */
    public int nombrePeriodes() {
        return nombrePeriodes;
    }
}
