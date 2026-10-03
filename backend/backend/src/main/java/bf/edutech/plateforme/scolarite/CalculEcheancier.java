package bf.edutech.plateforme.scolarite;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calcul de l'échéancier d'une inscription, sans accès à la base (testable seul).
 * <ol>
 * <li>Chaque tranche d'un frais applicable donne une échéance.</li>
 * <li>L'exonération d'un frais réduit d'abord ses dernières tranches.</li>
 * <li>Si le frais est couvert par la bourse, la part de l'organisme est
 * {@code taux %} du montant restant (arrondi au franc) ; la famille paie le reste.</li>
 * <li>Les paiements de la famille, puis ceux de l'organisme, soldent les échéances
 * dans l'ordre des dates limites (la tranche la plus ancienne d'abord).</li>
 * </ol>
 */
final class CalculEcheancier {

    private static final BigDecimal CENT = BigDecimal.valueOf(100);

    private CalculEcheancier() {
    }

    record Tranche(int numero, LocalDate dateLimite, long montant) {
    }

    record FraisApplicable(UUID fraisId, String libelle, boolean couvertParBourse, List<Tranche> tranches) {
    }

    record Ligne(UUID fraisId, String libelle, int numero, int nombreTranches, LocalDate dateLimite, long montant,
            long exoneration, long partOrganisme, long partFamille, long payeFamille, long payeOrganisme,
            long resteFamille, long resteOrganisme, boolean enRetard) {
    }

    record Resultat(List<Ligne> lignes, long total, long exonere, long totalFamille, long totalOrganisme,
            long payeFamille, long payeOrganisme, long resteFamille, long resteOrganisme, long retardFamille,
            long retardOrganisme, long avanceFamille, long avanceOrganisme, LocalDate prochaineEcheance) {
    }

    private static long part(long montant, BigDecimal taux) {
        return BigDecimal.valueOf(montant).multiply(taux).divide(CENT, 0, RoundingMode.HALF_UP).longValue();
    }

    /**
     * @param exonerations montant exonéré par frais
     * @param taux         part de l'organisme en % (0 pour un non-boursier)
     * @param payeFamille  total des paiements non annulés de la famille
     */
    static Resultat calculer(List<FraisApplicable> frais, Map<UUID, Long> exonerations, BigDecimal taux,
            long payeFamille, long payeOrganisme, LocalDate aujourdhui) {
        record Brute(UUID fraisId, String libelle, int numero, int nombre, LocalDate date, long montant, long exo,
                long organisme) {
        }
        List<Brute> brutes = new ArrayList<>();
        for (FraisApplicable f : frais) {
            List<Tranche> tranches = f.tranches().stream().sorted(Comparator.comparingInt(Tranche::numero)).toList();
            long total = tranches.stream().mapToLong(Tranche::montant).sum();
            long aExonerer = Math.min(exonerations.getOrDefault(f.fraisId(), 0L), total);
            long[] exo = new long[tranches.size()];
            for (int i = tranches.size() - 1; i >= 0 && aExonerer > 0; i--) {
                exo[i] = Math.min(aExonerer, tranches.get(i).montant());
                aExonerer -= exo[i];
            }
            // Part de l'organisme arrondie une seule fois sur le frais ; la dernière tranche reçoit le reste
            long netFrais = total - Arrays.stream(exo).sum();
            long organismeFrais = f.couvertParBourse() && taux.signum() > 0 ? part(netFrais, taux) : 0;
            long organismeReparti = 0;
            for (int i = 0; i < tranches.size(); i++) {
                Tranche t = tranches.get(i);
                long net = t.montant() - exo[i];
                long organisme = i == tranches.size() - 1 ? organismeFrais - organismeReparti
                        : Math.min(part(net, taux), organismeFrais - organismeReparti);
                organismeReparti += organisme;
                brutes.add(new Brute(f.fraisId(), f.libelle(), t.numero(), tranches.size(), t.dateLimite(),
                        t.montant(), exo[i], Math.max(0, Math.min(organisme, net))));
            }
        }
        brutes.sort(Comparator.comparing(Brute::date).thenComparing(Brute::libelle).thenComparingInt(Brute::numero));

        long resteAImputerFamille = payeFamille;
        long resteAImputerOrganisme = payeOrganisme;
        List<Ligne> lignes = new ArrayList<>();
        long total = 0, exonere = 0, totalFamille = 0, totalOrganisme = 0, retardFamille = 0, retardOrganisme = 0;
        LocalDate prochaine = null;
        for (Brute b : brutes) {
            long famille = b.montant() - b.exo() - b.organisme();
            long payeF = Math.min(resteAImputerFamille, famille);
            resteAImputerFamille -= payeF;
            long payeO = Math.min(resteAImputerOrganisme, b.organisme());
            resteAImputerOrganisme -= payeO;
            long resteF = famille - payeF;
            long resteO = b.organisme() - payeO;
            boolean echue = b.date().isBefore(aujourdhui);
            if (echue) {
                retardFamille += resteF;
                retardOrganisme += resteO;
            } else if (resteF + resteO > 0 && (prochaine == null || b.date().isBefore(prochaine))) {
                prochaine = b.date();
            }
            total += b.montant();
            exonere += b.exo();
            totalFamille += famille;
            totalOrganisme += b.organisme();
            lignes.add(new Ligne(b.fraisId(), b.libelle(), b.numero(), b.nombre(), b.date(), b.montant(), b.exo(),
                    b.organisme(), famille, payeF, payeO, resteF, resteO, echue && resteF > 0));
        }
        long imputeFamille = payeFamille - resteAImputerFamille;
        long imputeOrganisme = payeOrganisme - resteAImputerOrganisme;
        return new Resultat(lignes, total, exonere, totalFamille, totalOrganisme, payeFamille, payeOrganisme,
                totalFamille - imputeFamille, totalOrganisme - imputeOrganisme, retardFamille, retardOrganisme,
                resteAImputerFamille, resteAImputerOrganisme, prochaine);
    }
}
