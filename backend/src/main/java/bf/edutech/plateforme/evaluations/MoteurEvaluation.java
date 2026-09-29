package bf.edutech.plateforme.evaluations;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.pedagogie.CodeModele;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;

/** Registre des stratégies : Spring injecte toutes les implémentations disponibles. */
@Component
class MoteurEvaluation {

    private final Map<CodeModele, ModeleEvaluation> strategies = new EnumMap<>(CodeModele.class);

    MoteurEvaluation(List<ModeleEvaluation> implementations) {
        implementations.forEach(s -> strategies.put(s.code(), s));
    }

    ModeleEvaluation pour(CodeModele modele) {
        ModeleEvaluation strategie = strategies.get(modele);
        if (strategie == null) {
            throw new RegleMetierException("MODELE_NON_SUPPORTE", "Aucun calcul pour le modèle " + modele);
        }
        return strategie;
    }
}
