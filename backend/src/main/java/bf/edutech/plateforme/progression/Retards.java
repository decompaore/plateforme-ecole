package bf.edutech.plateforme.progression;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import bf.edutech.plateforme.progression.Vues.Retard;

/**
 * Calcul du retard d'une matière sur sa progression (v0.48.1). Une séquence est échue quand la suivante a commencé
 * (sa semaine de début est passée) ; la dernière, après autant de semaines que ses heures le demandent au volume
 * hebdomadaire du programme. Les séquences sans semaine prévue ne comptent pas.
 */
final class Retards {

    private static final BigDecimal SEUIL_PAR_DEFAUT = BigDecimal.valueOf(2);

    private Retards() {
    }

    static Retard calculer(List<SequenceProgression> sequences, List<SeanceCahier> faites, BigDecimal volumeHebdo,
            LocalDate jour) {
        List<SequenceProgression> triees = sequences.stream().sorted(Comparator.comparingInt(SequenceProgression::getOrdre))
                .toList();
        Set<Integer> traitees = faites.stream().map(SeanceCahier::getSequenceOrdre).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        BigDecimal prevu = BigDecimal.ZERO;
        int enRetard = 0;
        boolean planifiee = false;
        for (int i = 0; i < triees.size(); i++) {
            SequenceProgression s = triees.get(i);
            if (s.getSemaineDebut() == null) {
                continue;
            }
            planifiee = true;
            LocalDate echeance = echeance(triees, i, volumeHebdo);
            if (!echeance.isAfter(jour)) {
                prevu = prevu.add(s.getHeuresPrevues() == null ? BigDecimal.ZERO : s.getHeuresPrevues());
                if (!traitees.contains(s.getOrdre())) {
                    enRetard++;
                }
            }
        }
        BigDecimal realise = ProgressionService.somme(faites);
        BigDecimal ecart = prevu.subtract(realise).max(BigDecimal.ZERO);
        BigDecimal seuil = volumeHebdo != null && volumeHebdo.signum() > 0 ? volumeHebdo : SEUIL_PAR_DEFAUT;
        LocalDate derniere = faites.stream().map(SeanceCahier::getDate).max(Comparator.naturalOrder()).orElse(null);
        Integer jours = derniere == null ? null : (int) ChronoUnit.DAYS.between(derniere, jour);
        return new Retard(prevu, realise, ecart, enRetard, jours, planifiee && (ecart.compareTo(seuil) >= 0 || enRetard > 0));
    }

    /** Séquences échues à ce jour sans aucune séance au cahier de textes, dans l'ordre de la fiche. */
    static List<SequenceProgression> echuesSansSeance(List<SequenceProgression> sequences, List<SeanceCahier> faites,
            BigDecimal volumeHebdo, LocalDate jour) {
        List<SequenceProgression> triees = sequences.stream().sorted(Comparator.comparingInt(SequenceProgression::getOrdre))
                .toList();
        Set<Integer> traitees = faites.stream().map(SeanceCahier::getSequenceOrdre).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<SequenceProgression> r = new java.util.ArrayList<>();
        for (int i = 0; i < triees.size(); i++) {
            SequenceProgression s = triees.get(i);
            if (s.getSemaineDebut() != null && !traitees.contains(s.getOrdre())
                    && !echeance(triees, i, volumeHebdo).isAfter(jour)) {
                r.add(s);
            }
        }
        return r;
    }

    private static LocalDate echeance(List<SequenceProgression> triees, int i, BigDecimal volumeHebdo) {
        SequenceProgression s = triees.get(i);
        return i + 1 < triees.size() && triees.get(i + 1).getSemaineDebut() != null
                ? triees.get(i + 1).getSemaineDebut()
                : s.getSemaineDebut().plusWeeks(semaines(s.getHeuresPrevues(), volumeHebdo));
    }

    private static long semaines(BigDecimal heures, BigDecimal volumeHebdo) {
        if (heures == null || volumeHebdo == null || volumeHebdo.signum() <= 0) {
            return 1;
        }
        return Math.max(1, heures.divide(volumeHebdo, 0, RoundingMode.CEILING).longValue());
    }
}
