package bf.edutech.plateforme.etablissement;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.etablissement.Vues.FiliereVue;
import bf.edutech.plateforme.etablissement.Vues.MatiereVue;

/** Filières et catalogue des matières. Modification : administrateur d'école ou censeur. */
@RestController
public class FilieresEtMatieresController {

    private static final String GESTION = "hasAnyRole('ADMIN_ECOLE','CENSEUR')";
    private static final String FORMAT_CODE = "^[A-Za-z0-9_-]{1,20}$";

    public record DemandeFiliere(
            @NotBlank @Pattern(regexp = FORMAT_CODE) String code,
            @NotBlank @Size(max = 120) String libelle,
            @NotBlank @Size(max = 30) String cycle,
            @Size(max = 40) String diplomeVise,
            @NotNull UUID profilId) {
    }

    public record DemandeModificationFiliere(
            @NotBlank @Size(max = 120) String libelle,
            @NotBlank @Size(max = 30) String cycle,
            @Size(max = 40) String diplomeVise,
            @NotNull UUID profilId) {
    }

    public record DemandeMatiere(
            @NotBlank @Pattern(regexp = FORMAT_CODE) String code,
            @NotBlank @Size(max = 120) String libelle,
            @NotNull TypeMatiere type) {
    }

    public record DemandeModificationMatiere(
            @NotBlank @Size(max = 120) String libelle,
            @NotNull TypeMatiere type,
            Boolean actif) {

        public DemandeModificationMatiere {
            actif = actif == null || actif; // facultatif : la matière reste active
        }
    }

    private final FilieresService filieres;
    private final MatieresService matieres;

    FilieresEtMatieresController(FilieresService filieres, MatieresService matieres) {
        this.filieres = filieres;
        this.matieres = matieres;
    }

    @GetMapping("/api/v1/filieres")
    public List<FiliereVue> filieres() {
        return filieres.lister();
    }

    @PostMapping("/api/v1/filieres")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(GESTION)
    public FiliereVue creerFiliere(@Valid @RequestBody DemandeFiliere d) {
        return filieres.creer(d.code(), d.libelle(), d.cycle(), d.diplomeVise(), d.profilId());
    }

    @PutMapping("/api/v1/filieres/{id}")
    @PreAuthorize(GESTION)
    public FiliereVue modifierFiliere(@PathVariable UUID id, @Valid @RequestBody DemandeModificationFiliere d) {
        return filieres.modifier(id, d.libelle(), d.cycle(), d.diplomeVise(), d.profilId());
    }

    @GetMapping("/api/v1/matieres")
    public List<MatiereVue> matieres() {
        return matieres.lister();
    }

    @PostMapping("/api/v1/matieres")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(GESTION)
    public MatiereVue creerMatiere(@Valid @RequestBody DemandeMatiere d) {
        return matieres.creer(d.code(), d.libelle(), d.type());
    }

    @PutMapping("/api/v1/matieres/{id}")
    @PreAuthorize(GESTION)
    public MatiereVue modifierMatiere(@PathVariable UUID id, @Valid @RequestBody DemandeModificationMatiere d) {
        return matieres.modifier(id, d.libelle(), d.type(), d.actif());
    }
}
