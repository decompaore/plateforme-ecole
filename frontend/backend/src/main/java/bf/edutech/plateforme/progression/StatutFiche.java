package bf.edutech.plateforme.progression;

/** Cycle de vie d'une fiche de progression. */
public enum StatutFiche {
    /** En préparation par l'enseignant. */
    BROUILLON,
    /** Envoyée pour visa : l'enseignant ne la modifie plus. */
    SOUMISE,
    /** Visée par la direction ; une modification la repasse en brouillon. */
    VISEE,
    /** Renvoyée avec un commentaire : l'enseignant la corrige puis la soumet à nouveau. */
    A_REVOIR
}
