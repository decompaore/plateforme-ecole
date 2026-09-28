package bf.edutech.plateforme.etablissement;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.etablissement.Vues.ClasseVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereDeClasseVue;

/** Classes d'une année et matières enseignées. Modification : administrateur d'école ou censeur. */
@RestController
public class ClassesController {

    private static final String GESTION = "hasAnyRole('ADMIN_ECOLE','CENSEUR')";

    public record DemandeClasse(
            @NotNull UUID filiereId,
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 30) String niveau,
            @Min(1) Short effectifMax) {
    }

    public record DemandeMatiereDeClasse(
            @NotNull @DecimalMin(value = "0.1") @Digits(integer = 3, fraction = 1) BigDecimal coefficient,
            @Size(max = 40) String groupe,
            @DecimalMin(value = "0.1") @Digits(integer = 3, fraction = 1) BigDecimal volumeHebdo,
            @DecimalMin(value = "0.1") @Digits(integer = 5, fraction = 1) BigDecimal volumeTotal) {
    }

    private final ClassesService service;

    ClassesController(ClassesService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/annees/{anneeId}/classes")
    public List<ClasseVue> lister(@PathVariable UUID anneeId) {
        return service.lister(anneeId);
    }

    @PostMapping("/api/v1/annees/{anneeId}/classes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(GESTION)
    public ClasseVue creer(@PathVariable UUID anneeId, @Valid @RequestBody DemandeClasse d) {
        return service.creer(anneeId, d.filiereId(), d.code(), d.niveau(), d.effectifMax());
    }

    @GetMapping("/api/v1/classes/{id}")
    public ClasseVue trouver(@PathVariable UUID id) {
        return service.trouver(id);
    }

    @PutMapping("/api/v1/classes/{id}")
    @PreAuthorize(GESTION)
    public ClasseVue modifier(@PathVariable UUID id, @Valid @RequestBody DemandeClasse d) {
        return service.modifier(id, d.filiereId(), d.code(), d.niveau(), d.effectifMax());
    }

    @DeleteMapping("/api/v1/classes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(GESTION)
    public void supprimer(@PathVariable UUID id) {
        service.supprimer(id);
    }

    @GetMapping("/api/v1/classes/{id}/matieres")
    public List<MatiereDeClasseVue> matieres(@PathVariable UUID id) {
        return service.matieres(id);
    }

    /** Ajoute la matière à la classe ou met à jour son coefficient, son groupe et ses volumes. */
    @PutMapping("/api/v1/classes/{id}/matieres/{matiereId}")
    @PreAuthorize(GESTION)
    public MatiereDeClasseVue definirMatiere(@PathVariable UUID id, @PathVariable UUID matiereId,
            @Valid @RequestBody DemandeMatiereDeClasse d) {
        return service.definirMatiere(id, matiereId, d.coefficient(), d.groupe(), d.volumeHebdo(), d.volumeTotal());
    }

    @DeleteMapping("/api/v1/classes/{id}/matieres/{matiereId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(GESTION)
    public void retirerMatiere(@PathVariable UUID id, @PathVariable UUID matiereId) {
        service.retirerMatiere(id, matiereId);
    }
}
