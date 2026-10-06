package bf.edutech.plateforme.etablissement;

/** Cycle de vie d'une année scolaire (voir le diagramme d'états du dossier de conception). */
public enum EtatAnnee {
    /** Paramétrage, frais, inscriptions et réinscriptions. */
    PREPARATION,
    /** Année en cours ; une seule par établissement. */
    ACTIVE,
    /** Saisies interdites ; décisions de fin d'année. */
    CLOTUREE,
    /** Lecture seule. */
    ARCHIVEE
}
