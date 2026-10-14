package bf.edutech.plateforme.utilisateurs;

/**
 * Rôle d'un compte dans un établissement. Un même compte peut avoir des rôles
 * différents dans plusieurs établissements (ex. enseignant titulaire dans l'un,
 * vacataire dans un autre ; parent d'enfants scolarisés dans deux écoles).
 * <p>
 * Le super administrateur n'est pas un rôle d'établissement : c'est un
 * attribut du compte ({@link Utilisateur#isSuperAdmin()}).
 */
public enum Role {
    ADMIN_ECOLE,
    CENSEUR,
    /** Chef des travaux : supervise les matières techniques et pratiques, les ateliers. */
    CHEF_TRAVAUX,
    SECRETARIAT,
    INTENDANT,
    /**
     * Conseiller principal d'éducation (CPE), qui remplace le surveillant général : seul à modifier la vie scolaire
     * (incidents, convocations, justificatifs). Le nom technique est conservé pour ne pas migrer les comptes.
     */
    SURVEILLANT,
    ENSEIGNANT,
    PARENT,
    ELEVE
}
