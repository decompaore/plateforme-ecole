package bf.edutech.plateforme.evaluations;

import bf.edutech.plateforme.pedagogie.CodeModele;

/**
 * Stratégie de calcul des résultats d'une période, choisie d'après le profil
 * pédagogique de la classe. Ajouter un mode d'évaluation revient à ajouter une
 * implémentation, sans modifier les autres.
 */
interface ModeleEvaluation {

    CodeModele code();

    /** Résultats de chaque élève, dans l'ordre des élèves reçus (le classement est fait ensuite). */
    ResultatsCalcules calculer(DonneesCalcul donnees);
}
