package bf.edutech.plateforme.eleves;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import bf.edutech.plateforme.etablissement.AnneesService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.Vues.AnneeVue;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;

/**
 * Années et classes consultées pendant un traitement, mémorisées pour ne pas
 * interroger plusieurs fois le module Établissement. Durée de vie : un appel.
 */
final class Libelles {

    private final AnneesService annees;
    private final ClassesService classes;
    private final Map<UUID, AnneeVue> anneesLues = new HashMap<>();
    private final Map<UUID, ClasseVue> classesLues = new HashMap<>();

    Libelles(AnneesService annees, ClassesService classes) {
        this.annees = annees;
        this.classes = classes;
    }

    AnneeVue annee(UUID id) {
        return anneesLues.computeIfAbsent(id, annees::trouver);
    }

    ClasseVue classe(UUID id) {
        return classesLues.computeIfAbsent(id, classes::trouver);
    }

    /** Inscriptions de la plus récente à la plus ancienne, selon le début de l'année scolaire. */
    List<Inscription> parAnneeDecroissante(List<Inscription> inscriptions) {
        return inscriptions.stream()
                .sorted(Comparator.comparing((Inscription i) -> annee(i.getAnneeId()).debut()).reversed())
                .toList();
    }
}
