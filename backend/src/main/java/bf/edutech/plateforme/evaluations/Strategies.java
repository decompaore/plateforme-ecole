package bf.edutech.plateforme.evaluations;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.evaluations.CalculPeriode.Options;
import bf.edutech.plateforme.pedagogie.CodeModele;

/** Les quatre stratégies de calcul, une par modèle d'évaluation. */
final class Strategies {

    private Strategies() {
    }

    /** Enseignement général : moyennes de matières, coefficients, moyenne générale, rang. */
    @Component
    static class NotesCoefficients implements ModeleEvaluation {

        @Override
        public CodeModele code() {
            return CodeModele.NOTES_COEFFICIENTS;
        }

        @Override
        public ResultatsCalcules calculer(DonneesCalcul donnees) {
            return CalculPeriode.calculer(donnees, new Options(true, false, false, false));
        }
    }

    /**
     * Enseignement technique : même moyenne générale (coefficients des matières),
     * moyennes par groupe de matières, notes éliminatoires (matières techniques et pratiques).
     */
    @Component
    static class NotesParGroupes implements ModeleEvaluation {

        @Override
        public CodeModele code() {
            return CodeModele.NOTES_PAR_GROUPES;
        }

        @Override
        public ResultatsCalcules calculer(DonneesCalcul donnees) {
            return CalculPeriode.calculer(donnees, new Options(true, true, true, false));
        }
    }

    /** Formation professionnelle : niveaux de maîtrise par module, sans moyenne ni rang. */
    @Component
    static class Competences implements ModeleEvaluation {

        @Override
        public CodeModele code() {
            return CodeModele.COMPETENCES;
        }

        @Override
        public ResultatsCalcules calculer(DonneesCalcul donnees) {
            return CalculPeriode.calculer(donnees, new Options(false, false, false, true));
        }
    }

    /** Mixte : notes et coefficients pour les matières, compétences pour les modules. */
    @Component
    static class Mixte implements ModeleEvaluation {

        @Override
        public CodeModele code() {
            return CodeModele.MIXTE;
        }

        @Override
        public ResultatsCalcules calculer(DonneesCalcul donnees) {
            return CalculPeriode.calculer(donnees, new Options(true, true, true, true));
        }
    }
}
