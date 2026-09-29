package bf.edutech.plateforme.pedagogie;

/** Modèle d'évaluation d'un profil ; chaque valeur correspondra à une stratégie de calcul. */
public enum CodeModele {
    /** Notes sur 20, coefficients, moyenne générale, rang (enseignement général). */
    NOTES_COEFFICIENTS,
    /** Notes, moyennes par groupes de matières, notes éliminatoires (enseignement technique). */
    NOTES_PAR_GROUPES,
    /** Approche par compétences : acquis / en cours / non acquis (formation professionnelle). */
    COMPETENCES,
    /** Notes et compétences combinées. */
    MIXTE
}
