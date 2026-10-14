package bf.edutech.plateforme.eleves;

/**
 * Règles d'accès du module (expressions pour @PreAuthorize).
 * <p>
 * Organisation retenue avec les établissements (v0.47) : l'intendant reçoit le dossier et inscrit ; une fois
 * l'inscription enregistrée, seul le chef d'établissement modifie ; le censeur répartit les élèves dans les classes ;
 * le secrétariat consulte et édite les documents (cartes, accès parents).
 */
final class Roles {

    /** Saisie d'un nouveau dossier et de l'inscription : l'intendant (et le chef d'établissement). */
    static final String SAISIE = "hasAnyRole('ADMIN_ECOLE','INTENDANT')";

    /** Modification d'un dossier ou d'une inscription déjà enregistrés : le chef d'établissement seul. */
    static final String MODIFICATION = "hasRole('ADMIN_ECOLE')";

    /** Répartition des élèves dans les classes : le censeur (et le chef d'établissement). */
    static final String REPARTITION = "hasAnyRole('ADMIN_ECOLE','CENSEUR')";

    /** Documents remis aux familles (cartes scolaires, accès à l'espace parent). */
    static final String DOCUMENTS = "hasAnyRole('ADMIN_ECOLE','INTENDANT','SECRETARIAT')";

    /** Consultation des dossiers (données personnelles de mineurs : personnel administratif uniquement). */
    static final String CONSULTATION =
            "hasAnyRole('ADMIN_ECOLE','CENSEUR','CHEF_TRAVAUX','SECRETARIAT','INTENDANT','SURVEILLANT')";

    /** Personnel administratif (rôles sans préfixe), qui consulte toutes les classes. */
    static final String[] PERSONNEL = { "ADMIN_ECOLE", "CENSEUR", "CHEF_TRAVAUX", "SECRETARIAT", "INTENDANT",
            "SURVEILLANT" };

    /** Listes de classe : personnel administratif, et enseignants pour leurs classes. */
    static final String LISTES_DE_CLASSE = "hasAnyRole('ADMIN_ECOLE','CENSEUR','CHEF_TRAVAUX','SECRETARIAT',"
            + "'INTENDANT','SURVEILLANT','ENSEIGNANT')";

    private Roles() {
    }
}
