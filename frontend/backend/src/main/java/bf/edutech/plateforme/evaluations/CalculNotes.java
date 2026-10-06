package bf.edutech.plateforme.evaluations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.TypeMatiere;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.evaluations.Vues.AlerteVue;
import bf.edutech.plateforme.evaluations.Vues.MoyenneGroupeVue;
import bf.edutech.plateforme.evaluations.Vues.MoyenneMatiereVue;

/**
 * Calculs communs aux profils à notes.
 * <ul>
 * <li>Chaque note est ramenée sur 20 puis pondérée par le poids de l'évaluation ;
 * une absence à l'évaluation n'est pas comptée.</li>
 * <li>Une matière sans aucune note pour l'élève sur la période compte ZÉRO avec
 * son coefficient (règle retenue par l'établissement pilote).</li>
 * <li>Moyenne générale = Σ(moyenne × coefficient) / Σ coefficients, calculée sur
 * les moyennes de matières non arrondies, puis arrondie au nombre de décimales
 * du profil (arrondi au plus proche).</li>
 * <li>Rang : les ex-æquo ont le même rang et le rang suivant est sauté
 * (1, 2, 2, 4), sur les moyennes arrondies.</li>
 * </ul>
 */
final class CalculNotes {

    static final int ECHELLE = 6;
    private static final BigDecimal VINGT = BigDecimal.valueOf(20);
    private static final BigDecimal CENT = BigDecimal.valueOf(100);

    /** Moyenne d'un élève dans une matière (non arrondie ; null s'il n'a aucune note). */
    record MoyenneBrute(BigDecimal moyenne, int notes) {
    }

    private CalculNotes() {
    }

    /** Moyennes brutes : inscription → matière → moyenne. */
    static Map<UUID, Map<UUID, MoyenneBrute>> moyennesParMatiere(DonneesCalcul d) {
        Map<UUID, Evaluation> evaluations = d.evaluations().stream()
                .collect(Collectors.toMap(Evaluation::getId, Function.identity()));
        Map<UUID, Map<UUID, BigDecimal[]>> cumuls = new HashMap<>(); // [somme pondérée, somme des poids, nb]
        for (Note note : d.notes()) {
            Evaluation e = evaluations.get(note.getEvaluationId());
            if (e == null || note.isAbsent() || note.getValeur() == null) {
                continue;
            }
            BigDecimal sur20 = note.getValeur().multiply(VINGT).divide(e.getBareme(), ECHELLE, RoundingMode.HALF_UP);
            BigDecimal[] c = cumuls.computeIfAbsent(note.getInscriptionId(), k -> new HashMap<>())
                    .computeIfAbsent(e.getMatiereId(), k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO });
            c[0] = c[0].add(sur20.multiply(e.getPoids()));
            c[1] = c[1].add(e.getPoids());
            c[2] = c[2].add(BigDecimal.ONE);
        }
        Map<UUID, Map<UUID, MoyenneBrute>> resultat = new HashMap<>();
        cumuls.forEach((inscription, parMatiere) -> parMatiere.forEach((matiere, c) -> resultat
                .computeIfAbsent(inscription, k -> new HashMap<>())
                .put(matiere, new MoyenneBrute(c[0].divide(c[1], ECHELLE, RoundingMode.HALF_UP), c[2].intValue()))));
        return resultat;
    }

    /** Lignes « matières » d'un élève ; une matière sans note compte zéro. */
    static List<MoyenneMatiereVue> lignesMatieres(List<MatiereDeClasseVue> matieres,
            Map<UUID, MoyenneBrute> moyennesEleve) {
        List<MoyenneMatiereVue> lignes = new ArrayList<>();
        for (MatiereDeClasseVue m : matieres) {
            MoyenneBrute brute = moyennesEleve.get(m.matiereId());
            boolean sansNote = brute == null || brute.moyenne() == null;
            BigDecimal moyenne = sansNote ? BigDecimal.ZERO : brute.moyenne();
            lignes.add(new MoyenneMatiereVue(m.matiereId(), m.matiereCode(), m.matiereLibelle(), m.groupe(),
                    m.coefficient(), moyenne, moyenne.multiply(m.coefficient()), sansNote ? 0 : brute.notes(), null,
                    sansNote));
        }
        return lignes;
    }

    /** Moyenne pondérée par les coefficients (non arrondie), ou null s'il n'y a aucun coefficient. */
    static BigDecimal moyennePonderee(List<MoyenneMatiereVue> lignes) {
        BigDecimal points = BigDecimal.ZERO;
        BigDecimal coefficients = BigDecimal.ZERO;
        for (MoyenneMatiereVue l : lignes) {
            points = points.add(l.moyenne().multiply(l.coefficient()));
            coefficients = coefficients.add(l.coefficient());
        }
        return coefficients.signum() == 0 ? null : points.divide(coefficients, ECHELLE, RoundingMode.HALF_UP);
    }

    /** Moyennes par groupe de matières (ordre d'apparition des groupes). */
    static List<MoyenneGroupeVue> groupes(List<MoyenneMatiereVue> lignes, int decimales) {
        Map<String, List<MoyenneMatiereVue>> parGroupe = new LinkedHashMap<>();
        for (MoyenneMatiereVue l : lignes) {
            if (l.groupe() != null) {
                parGroupe.computeIfAbsent(l.groupe(), k -> new ArrayList<>()).add(l);
            }
        }
        List<MoyenneGroupeVue> groupes = new ArrayList<>();
        parGroupe.forEach((groupe, liste) -> {
            BigDecimal moyenne = moyennePonderee(liste);
            BigDecimal coefficients = liste.stream().map(MoyenneMatiereVue::coefficient)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            groupes.add(new MoyenneGroupeVue(groupe, arrondi(moyenne, decimales), coefficients));
        });
        return groupes;
    }

    /** Matières (techniques ou pratiques) dont la moyenne est sous la note éliminatoire du profil. */
    static List<String> eliminatoires(List<MatiereDeClasseVue> matieres, List<MoyenneMatiereVue> lignes,
            BigDecimal noteEliminatoire) {
        if (noteEliminatoire == null) {
            return List.of();
        }
        Set<UUID> concernees = matieres.stream()
                .filter(m -> m.type() == TypeMatiere.TECHNIQUE || m.type() == TypeMatiere.PRATIQUE)
                .map(MatiereDeClasseVue::matiereId).collect(Collectors.toSet());
        return lignes.stream()
                .filter(l -> concernees.contains(l.matiereId()) && l.moyenne().compareTo(noteEliminatoire) < 0)
                .map(MoyenneMatiereVue::code)
                .toList();
    }

    /** Arrondit les moyennes de matières à 2 décimales (affichage). */
    static List<MoyenneMatiereVue> arrondirLignes(List<MoyenneMatiereVue> lignes) {
        return lignes.stream()
                .map(l -> new MoyenneMatiereVue(l.matiereId(), l.code(), l.libelle(), l.groupe(), l.coefficient(),
                        arrondi(l.moyenne(), 2), arrondi(l.points(), 2), l.notes(), l.rang(), l.sansNote()))
                .toList();
    }

    static BigDecimal arrondi(BigDecimal valeur, int decimales) {
        return valeur == null ? null : valeur.setScale(decimales, RoundingMode.HALF_UP);
    }

    /**
     * Rangs « olympiques » : même rang pour les ex-æquo, rang suivant sauté.
     * Les valeurs null (non classés) n'ont pas de rang.
     */
    static <T> Map<T, Integer> rangs(Map<T, BigDecimal> valeurs) {
        List<Map.Entry<T, BigDecimal>> triees = valeurs.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .sorted(Map.Entry.<T, BigDecimal>comparingByValue(Comparator.reverseOrder()))
                .toList();
        Map<T, Integer> rangs = new HashMap<>();
        BigDecimal precedente = null;
        int rang = 0;
        for (int i = 0; i < triees.size(); i++) {
            BigDecimal valeur = triees.get(i).getValue();
            if (precedente == null || valeur.compareTo(precedente) != 0) {
                rang = i + 1;
                precedente = valeur;
            }
            rangs.put(triees.get(i).getKey(), rang);
        }
        return rangs;
    }

    /** Rang de chaque élève dans chaque matière (sur la moyenne arrondie à 2 décimales). */
    static Map<UUID, Map<UUID, Integer>> rangsParMatiere(Map<UUID, List<MoyenneMatiereVue>> lignesParEleve) {
        Map<UUID, Map<UUID, BigDecimal>> parMatiere = new HashMap<>();
        lignesParEleve.forEach((inscription, lignes) -> lignes.forEach(l -> parMatiere
                .computeIfAbsent(l.matiereId(), k -> new HashMap<>()).put(inscription, arrondi(l.moyenne(), 2))));
        Map<UUID, Map<UUID, Integer>> resultat = new HashMap<>();
        parMatiere.forEach((matiere, valeurs) -> resultat.put(matiere, rangs(valeurs)));
        return resultat;
    }

    /** Alertes de complétude : matières sans évaluation (comptées zéro), notes manquantes. */
    static List<AlerteVue> alertes(DonneesCalcul d, List<MatiereDeClasseVue> matieresANotes) {
        List<AlerteVue> alertes = new ArrayList<>();
        Set<UUID> evaluees = d.evaluations().stream().map(Evaluation::getMatiereId).collect(Collectors.toSet());
        for (MatiereDeClasseVue m : matieresANotes) {
            if (!evaluees.contains(m.matiereId())) {
                alertes.add(new AlerteVue("MATIERE_SANS_EVALUATION", m.matiereLibelle()
                        + " : aucune évaluation sur la période (moyenne comptée zéro pour tous les élèves)"));
            }
        }
        Set<UUID> eleves = d.eleves().stream().map(InscriptionVue::id).collect(Collectors.toSet());
        Map<UUID, Long> notesParEvaluation = d.notes().stream()
                .filter(n -> eleves.contains(n.getInscriptionId()))
                .collect(Collectors.groupingBy(Note::getEvaluationId, Collectors.counting()));
        for (Evaluation e : d.evaluations()) {
            long manquantes = eleves.size() - notesParEvaluation.getOrDefault(e.getId(), 0L);
            if (manquantes > 0) {
                alertes.add(new AlerteVue("NOTES_MANQUANTES", e.getLibelle() + " (" + libelleMatiere(d, e.getMatiereId())
                        + ") : " + manquantes + " élève(s) sans note ni absence saisie"));
            }
        }
        return alertes;
    }

    static BigDecimal pourcentage(long partie, long total) {
        return total == 0 ? null
                : BigDecimal.valueOf(partie).multiply(CENT).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private static String libelleMatiere(DonneesCalcul d, UUID matiereId) {
        return d.matieres().stream().filter(m -> m.matiereId().equals(matiereId)).map(MatiereDeClasseVue::matiereCode)
                .findFirst().orElse("?");
    }
}
