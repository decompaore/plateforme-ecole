package bf.edutech.plateforme.evaluations;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.eleves.InscriptionsService;
import bf.edutech.plateforme.enseignants.EnseignantsService;
import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.etablissement.PeriodesService;
import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;
import bf.edutech.plateforme.etablissement.Vues.PeriodeVue;
import bf.edutech.plateforme.evaluations.Vues.SuiviEvaluationVue;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Suivi des évaluations par classe, matière et enseignant : nombre et types d'évaluations
 * faites, dernière évaluation, avancement de la saisie des notes. Pour la direction (contrôle
 * pédagogique, préparation des conseils de classe) et pour l'enseignant (ses propres matières).
 */
@Service
public class SuiviEvaluationsService {

    private final EvaluationRepository evaluations;
    private final NoteRepository notes;
    private final ClassesService classes;
    private final PeriodesService periodes;
    private final InscriptionsService inscriptions;
    private final EnseignantsService enseignants;

    SuiviEvaluationsService(EvaluationRepository evaluations, NoteRepository notes, ClassesService classes,
            PeriodesService periodes, InscriptionsService inscriptions, EnseignantsService enseignants) {
        this.evaluations = evaluations;
        this.notes = notes;
        this.classes = classes;
        this.periodes = periodes;
        this.inscriptions = inscriptions;
        this.enseignants = enseignants;
    }

    /**
     * Une ligne par matière du programme de chaque classe de l'année (matières sans
     * évaluation comprises). {@code ordre} : seulement la période de ce rang dans chaque classe
     * (1 = premier trimestre ou semestre) ; null : toute l'année. Un enseignant ne voit que les
     * matières qui lui sont confiées.
     */
    @Transactional(readOnly = true)
    public List<SuiviEvaluationVue> suivi(UUID anneeId, Integer ordre) {
        UtilisateurConnecte.etablissementActif();
        Optional<UUID> monEngagement = Optional.empty();
        if (!UtilisateurConnecte.aUnRole(AutorisationsEvaluations.CONSULTATION)) {
            monEngagement = UtilisateurConnecte.idSiConnecte().flatMap(enseignants::engagementActif);
            if (monEngagement.isEmpty()) {
                throw new AccesRefuseException("Le suivi des évaluations est réservé à la direction et aux enseignants");
            }
        }
        List<ClasseVue> lesClasses = classes.lister(anneeId);
        if (lesClasses.isEmpty()) {
            return List.of();
        }
        // Périodes retenues : celles du rang demandé (chaque profil a les siennes), ou toutes
        Set<UUID> periodesRetenues = periodes.lister(anneeId).stream()
                .filter(p -> ordre == null || p.ordre() == ordre)
                .map(PeriodeVue::id)
                .collect(Collectors.toSet());

        Map<String, List<Evaluation>> parClasseMatiere = evaluations
                .findByClasseIdIn(lesClasses.stream().map(ClasseVue::id).toList()).stream()
                .filter(e -> periodesRetenues.contains(e.getPeriodeId()))
                .collect(Collectors.groupingBy(e -> cle(e.getClasseId(), e.getMatiereId())));
        Map<UUID, Long> notesParEvaluation = compterNotes(
                parClasseMatiere.values().stream().flatMap(List::stream).map(Evaluation::getId).toList());

        List<Ligne> lignes = new ArrayList<>();
        for (ClasseVue classe : lesClasses) {
            for (MatiereDeClasseVue m : classes.matieres(classe.id())) {
                if (monEngagement.isPresent() && !monEngagement.get().equals(m.engagementId())) {
                    continue;
                }
                lignes.add(new Ligne(classe, m));
            }
        }
        Map<UUID, String> noms = enseignants.nomsParEngagement(
                lignes.stream().map(l -> l.matiere().engagementId()).filter(id -> id != null).distinct().toList());
        Map<UUID, Integer> effectifs = new HashMap<>();

        return lignes.stream()
                .map(l -> {
                    List<Evaluation> evs = parClasseMatiere.getOrDefault(cle(l.classe().id(), l.matiere().matiereId()),
                            List.of());
                    Map<TypeEvaluation, Integer> parType = new EnumMap<>(TypeEvaluation.class);
                    evs.forEach(e -> parType.merge(e.getType(), 1, Integer::sum));
                    int effectif = effectifs.computeIfAbsent(l.classe().id(),
                            id -> inscriptions.listerParClasse(id, false).size());
                    int saisies = evs.stream().mapToInt(e -> notesParEvaluation.getOrDefault(e.getId(), 0L).intValue())
                            .sum();
                    LocalDate derniere = evs.stream().map(Evaluation::getDateEvaluation).max(Comparator.naturalOrder())
                            .orElse(null);
                    return new SuiviEvaluationVue(l.classe().id(), l.classe().code(), l.classe().niveau(),
                            l.matiere().matiereId(), l.matiere().matiereCode(), l.matiere().matiereLibelle(),
                            l.matiere().engagementId(),
                            l.matiere().engagementId() == null ? null : noms.get(l.matiere().engagementId()), evs.size(), parType,
                            derniere, saisies, effectif * evs.size());
                })
                .sorted(Comparator.comparing(SuiviEvaluationVue::classeCode)
                        .thenComparing(SuiviEvaluationVue::matiereLibelle))
                .toList();
    }

    private Map<UUID, Long> compterNotes(Collection<UUID> evaluationIds) {
        if (evaluationIds.isEmpty()) {
            return Map.of();
        }
        return notes.findByEvaluationIdIn(evaluationIds).stream()
                .collect(Collectors.groupingBy(Note::getEvaluationId, Collectors.counting()));
    }

    private static String cle(UUID classeId, UUID matiereId) {
        return classeId + "/" + matiereId;
    }

    private record Ligne(ClasseVue classe, MatiereDeClasseVue matiere) {
    }
}
