package bf.edutech.plateforme.evaluations;

import java.util.List;

import bf.edutech.plateforme.evaluations.Vues.AlerteVue;
import bf.edutech.plateforme.evaluations.Vues.ResultatEleveVue;

/** Sortie d'une stratégie : résultats par élève (rangs compris) et alertes de complétude. */
record ResultatsCalcules(List<ResultatEleveVue> eleves, List<AlerteVue> alertes) {
}
