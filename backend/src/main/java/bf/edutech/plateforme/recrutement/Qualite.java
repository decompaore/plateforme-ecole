package bf.edutech.plateforme.recrutement;

/** Qualité d'un membre de la commission de recrutement. */
public enum Qualite {
    PRESIDENT("Président"), VICE_PRESIDENT("Vice-président"), RAPPORTEUR("Rapporteur"), MEMBRE("Membre");

    private final String libelle;

    Qualite(String libelle) {
        this.libelle = libelle;
    }

    public String libelle() {
        return libelle;
    }
}
