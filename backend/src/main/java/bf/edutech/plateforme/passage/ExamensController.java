package bf.edutech.plateforme.passage;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.passage.ExamensService.SaisieHistorique;
import bf.edutech.plateforme.passage.ExamensService.TableauExamensVue;
import bf.edutech.plateforme.socle.documents.EntetesOfficiels;
import bf.edutech.plateforme.socle.export.ExportTableaux;

/** Taux de réussite aux examens nationaux (v0.46) : direction de l'établissement. */
@RestController
public class ExamensController {

    private static final String LECTURE = "hasAnyRole('ADMIN_ECOLE','CENSEUR')";
    private static final String SAISIE = "hasRole('ADMIN_ECOLE')";

    private final ExamensService examens;
    private final EntetesOfficiels entetes;

    ExamensController(ExamensService examens, EntetesOfficiels entetes) {
        this.examens = examens;
        this.entetes = entetes;
    }

    @GetMapping("/api/v1/examens/resultats")
    @PreAuthorize(LECTURE)
    public TableauExamensVue resultats() {
        return examens.tableau();
    }

    @GetMapping("/api/v1/examens/resultats/export")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> exporter(@RequestParam(required = false) String format) {
        return ExportTableaux.reponse(entetes.courant(), "examens-" + LocalDate.now(ZoneId.of("Africa/Ouagadougou")),
                ExportTableaux.Format.lire(format), examens.exporter());
    }

    /** {"annee":"2023-2024","examen":"BEPC","candidats":120,"admis":78,"candidatesFilles":55,"admisesFilles":40}. */
    @PostMapping("/api/v1/examens/historique")
    @PreAuthorize(SAISIE)
    public TableauExamensVue ajouter(@RequestBody SaisieHistorique s) {
        return examens.ajouter(s);
    }

    @PutMapping("/api/v1/examens/historique/{id}")
    @PreAuthorize(SAISIE)
    public TableauExamensVue modifier(@PathVariable UUID id, @RequestBody SaisieHistorique s) {
        return examens.modifier(id, s);
    }

    @DeleteMapping("/api/v1/examens/historique/{id}")
    @PreAuthorize(SAISIE)
    public TableauExamensVue supprimer(@PathVariable UUID id) {
        return examens.supprimer(id);
    }
}
