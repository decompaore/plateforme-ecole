package bf.edutech.plateforme.passage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Règles du passage, sans accès à la base (testables seules).
 * <ul>
 * <li>Moyenne annuelle = Σ (poids × moyenne de la période) / Σ poids, sur les périodes où l'élève a une moyenne ;
 * arrondie au nombre de décimales du profil.</li>
 * <li>Rang annuel : ex-æquo au même rang, rang suivant sauté.</li>
 * <li>Proposition : voir {@link #proposer}.</li>
 * </ul>
 */
final class MoteurPassage {

    private MoteurPassage() {
    }

    /** Moyennes de l'élève par période (null si pas de moyenne) et poids de chaque période. */
    static BigDecimal moyenneAnnuelle(List<BigDecimal> moyennes, List<BigDecimal> poids, int decimales) {
        BigDecimal somme = BigDecimal.ZERO;
        BigDecimal totalPoids = BigDecimal.ZERO;
        for (int i = 0; i < moyennes.size(); i++) {
            if (moyennes.get(i) != null) {
                somme = somme.add(moyennes.get(i).multiply(poids.get(i)));
                totalPoids = totalPoids.add(poids.get(i));
            }
        }
        return totalPoids.signum() == 0 ? null : somme.divide(totalPoids, decimales, RoundingMode.HALF_UP);
    }

    /** Rangs par moyenne décroissante ; les élèves sans moyenne n'ont pas de rang. */
    static Map<UUID, Integer> rangs(Map<UUID, BigDecimal> moyennes) {
        List<Map.Entry<UUID, BigDecimal>> classees = new ArrayList<>(moyennes.entrySet().stream()
                .filter(e -> e.getValue() != null).toList());
        classees.sort(Map.Entry.<UUID, BigDecimal>comparingByValue(Comparator.reverseOrder()));
        Map<UUID, Integer> rangs = new HashMap<>();
        for (int i = 0; i < classees.size(); i++) {
            BigDecimal m = classees.get(i).getValue();
            int rang = i > 0 && m.compareTo(classees.get(i - 1).getValue()) == 0
                    ? rangs.get(classees.get(i - 1).getKey()) : i + 1;
            rangs.put(classees.get(i).getKey(), rang);
        }
        return rangs;
    }

    record Regles(BigDecimal seuilAdmission, BigDecimal seuilExclusion, Integer redoublementsMax) {
    }

    /**
     * Proposition de décision.
     * <ol>
     * <li>Classe d'examen : en attente du résultat ; admis à l'examen → ADMIS (CERTIFIE en compétences) ;
     * ajourné → REDOUBLE (NON_CERTIFIE en compétences), sauf exclusion ci-dessous.</li>
     * <li>Autres classes : ADMIS si la moyenne annuelle atteint le seuil d'admission du profil (en compétences :
     * tous les modules acquis à la dernière période), sinon REDOUBLE, sauf exclusion.</li>
     * <li>Exclusion d'un élève non admis : moyenne annuelle sous le seuil d'exclusion, ou déjà
     * {@code redoublementsMax} redoublements dans ce niveau.</li>
     * </ol>
     */
    static Decision proposer(boolean competences, BigDecimal moyenne, boolean admisCompetences, boolean examen,
            ResultatExamen resultat, int redoublements, Regles regles) {
        Decision echec;
        if (examen) {
            if (resultat == null) {
                return Decision.EN_ATTENTE_EXAMEN;
            }
            if (resultat == ResultatExamen.ADMIS) {
                return competences ? Decision.CERTIFIE : Decision.ADMIS;
            }
            echec = competences ? Decision.NON_CERTIFIE : Decision.REDOUBLE;
        } else {
            boolean admis = competences ? admisCompetences
                    : moyenne != null && moyenne.compareTo(regles.seuilAdmission()) >= 0;
            if (admis) {
                return Decision.ADMIS;
            }
            echec = Decision.REDOUBLE;
        }
        if (!competences && regles.seuilExclusion() != null && moyenne != null
                && moyenne.compareTo(regles.seuilExclusion()) < 0) {
            return Decision.EXCLU;
        }
        if (regles.redoublementsMax() != null && redoublements >= regles.redoublementsMax()) {
            return Decision.EXCLU;
        }
        return echec;
    }
}
