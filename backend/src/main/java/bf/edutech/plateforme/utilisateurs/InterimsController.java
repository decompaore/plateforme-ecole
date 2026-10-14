package bf.edutech.plateforme.utilisateurs;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.utilisateurs.InterimsService.InterimVue;

/** Intérim du chef d'établissement (v0.47). */
@RestController
public class InterimsController {

    public record DemandeInterim(UUID utilisateurId, LocalDate du, LocalDate au, String motif) {
    }

    private final InterimsService service;

    InterimsController(InterimsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/interims")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CENSEUR','CHEF_TRAVAUX')")
    public List<InterimVue> lister() {
        return service.lister();
    }

    @PostMapping("/api/v1/interims")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public InterimVue designer(@RequestBody DemandeInterim d) {
        return service.designer(d.utilisateurId(), d.du(), d.au(), d.motif());
    }

    @PostMapping("/api/v1/interims/{id}/fin")
    @PreAuthorize("hasRole('ADMIN_ECOLE')")
    public InterimVue terminer(@PathVariable UUID id) {
        return service.terminer(id);
    }
}
