package bf.edutech.plateforme.mobilemoney;

/**
 * Cycle de vie d'une transaction : INITIEE → EN_ATTENTE (le parent confirme sur son
 * téléphone) → CONFIRMEE (paiement et reçu enregistrés), ECHOUEE ou EXPIREE.
 * A_VERIFIER : argent reçu mais non enregistré automatiquement (montant différent,
 * plus rien à payer) ; l'intendance régularise (REGULARISEE).
 */
public enum StatutTransaction {
    INITIEE,
    EN_ATTENTE,
    CONFIRMEE,
    ECHOUEE,
    EXPIREE,
    A_VERIFIER,
    REGULARISEE;

    boolean enCours() {
        return this == INITIEE || this == EN_ATTENTE;
    }
}
