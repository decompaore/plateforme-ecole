package bf.edutech.plateforme.eleves;

/** Règles d'accès du module (expressions pour @PreAuthorize). */
final class Roles {

    /** Création et modification des dossiers et des inscriptions. */
    static final String GESTION = "hasAnyRole('ADMIN_ECOLE','SECRETARIAT')";

    /** Consultation des dossiers (données personnelles de mineurs : personnel administratif uniquement). */
    static final String CONSULTATION =
            "hasAnyRole('ADMIN_ECOLE','CENSEUR','SECRETARIAT','INTENDANT','SURVEILLANT')";

    /** Listes de classe : personnel administratif et enseignants. */
    static final String LISTES_DE_CLASSE =
            "hasAnyRole('ADMIN_ECOLE','CENSEUR','SECRETARIAT','INTENDANT','SURVEILLANT','ENSEIGNANT')";

    /** Statut de bourse : a une incidence sur les frais de scolarité. */
    static final String BOURSE = "hasAnyRole('ADMIN_ECOLE','SECRETARIAT','INTENDANT')";

    private Roles() {
    }
}
