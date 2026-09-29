package bf.edutech.plateforme.viescolaire;

/** Nature d'un incident ; les sanctions lourdes sont réservées à la direction. */
public enum TypeIncident {
    RETARD("retard", false, false),
    AVERTISSEMENT("avertissement", true, false),
    BLAME("blâme", true, true),
    EXCLUSION_TEMPORAIRE("exclusion temporaire", true, true);

    private final String libelle;
    private final boolean famillePrevenueParDefaut;
    private final boolean reserveDirection;

    TypeIncident(String libelle, boolean famillePrevenueParDefaut, boolean reserveDirection) {
        this.libelle = libelle;
        this.famillePrevenueParDefaut = famillePrevenueParDefaut;
        this.reserveDirection = reserveDirection;
    }

    public String libelle() {
        return libelle;
    }

    boolean famillePrevenueParDefaut() {
        return famillePrevenueParDefaut;
    }

    boolean reserveDirection() {
        return reserveDirection;
    }
}
