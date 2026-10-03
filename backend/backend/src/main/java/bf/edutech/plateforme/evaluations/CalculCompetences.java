package bf.edutech.plateforme.evaluations;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import bf.edutech.plateforme.eleves.Vues.InscriptionVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.evaluations.Vues.AlerteVue;
import bf.edutech.plateforme.evaluations.Vues.ModuleVue;

/**
 * Calculs de l'approche par compétences. Pour chaque module : part des
 * compétences du référentiel acquises sur la période ; le module est ACQUIS si
 * cette part atteint le seuil de maîtrise du profil (70 % par défaut).
 */
final class CalculCompetences {

    static final BigDecimal SEUIL_PAR_DEFAUT = BigDecimal.valueOf(70);

    private CalculCompetences() {
    }

    static BigDecimal seuil(DonneesCalcul d) {
        return d.profil().seuilMaitrise() != null ? d.profil().seuilMaitrise() : SEUIL_PAR_DEFAUT;
    }

    /** Modules de chaque élève : inscription → modules. */
    static Map<UUID, List<ModuleVue>> modules(DonneesCalcul d, List<MatiereDeClasseVue> modules) {
        BigDecimal seuil = seuil(d);
        Map<UUID, List<Competence>> parModule = d.competences().stream()
                .collect(Collectors.groupingBy(Competence::getMatiereId));
        Map<UUID, Map<UUID, NiveauMaitrise>> niveaux = new HashMap<>(); // inscription → compétence → niveau
        for (ResultatCompetence r : d.resultatsCompetences()) {
            niveaux.computeIfAbsent(r.getInscriptionId(), k -> new HashMap<>()).put(r.getCompetenceId(), r.getNiveau());
        }
        Map<UUID, List<ModuleVue>> resultat = new HashMap<>();
        for (InscriptionVue eleve : d.eleves()) {
            Map<UUID, NiveauMaitrise> siens = niveaux.getOrDefault(eleve.id(), Map.of());
            List<ModuleVue> liste = new ArrayList<>();
            for (MatiereDeClasseVue m : modules) {
                List<Competence> referentiel = parModule.getOrDefault(m.matiereId(), List.of());
                int evaluees = 0;
                int acquises = 0;
                for (Competence c : referentiel) {
                    NiveauMaitrise n = siens.get(c.getId());
                    if (n != null) {
                        evaluees++;
                        if (n == NiveauMaitrise.ACQUIS) {
                            acquises++;
                        }
                    }
                }
                BigDecimal taux = CalculNotes.pourcentage(acquises, referentiel.size());
                StatutModule statut = evaluees == 0 ? StatutModule.NON_EVALUE
                        : taux.compareTo(seuil) >= 0 ? StatutModule.ACQUIS : StatutModule.NON_ACQUIS;
                liste.add(new ModuleVue(m.matiereId(), m.matiereCode(), m.matiereLibelle(), referentiel.size(),
                        evaluees, acquises, taux, statut));
            }
            resultat.put(eleve.id(), liste);
        }
        return resultat;
    }

    /** Part des compétences acquises, tous modules confondus. */
    static BigDecimal tauxGlobal(List<ModuleVue> modules) {
        int acquises = modules.stream().mapToInt(ModuleVue::acquises).sum();
        int total = modules.stream().mapToInt(ModuleVue::competences).sum();
        return CalculNotes.pourcentage(acquises, total);
    }

    static List<AlerteVue> alertes(DonneesCalcul d, List<MatiereDeClasseVue> modules) {
        List<AlerteVue> alertes = new ArrayList<>();
        Map<UUID, Long> tailles = d.competences().stream()
                .collect(Collectors.groupingBy(Competence::getMatiereId, Collectors.counting()));
        long attendues = 0;
        for (MatiereDeClasseVue m : modules) {
            long taille = tailles.getOrDefault(m.matiereId(), 0L);
            if (taille == 0) {
                alertes.add(new AlerteVue("MODULE_SANS_REFERENTIEL",
                        m.matiereLibelle() + " : aucune compétence dans le référentiel du module"));
            }
            attendues += taille * d.eleves().size();
        }
        long manquantes = attendues - d.resultatsCompetences().size();
        if (manquantes > 0) {
            alertes.add(new AlerteVue("COMPETENCES_NON_EVALUEES",
                    manquantes + " évaluation(s) de compétences non saisie(s) sur la période"));
        }
        return alertes;
    }
}
