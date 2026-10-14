package bf.edutech.plateforme.moyens;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.moyens.DotationsService.DonneesDotation;
import bf.edutech.plateforme.moyens.DotationsService.DotationVue;
import bf.edutech.plateforme.moyens.DotationsService.Dotations;
import bf.edutech.plateforme.moyens.DotationsService.Reception;
import bf.edutech.plateforme.moyens.RemonteesService.DemandeArbitrage;
import bf.edutech.plateforme.moyens.RemonteesService.DemandeRenvoi;
import bf.edutech.plateforme.moyens.RemonteesService.DemandeTransmission;
import bf.edutech.plateforme.moyens.RemonteesService.RemonteeVue;
import bf.edutech.plateforme.moyens.RemonteesService.VueBesoins;
import bf.edutech.plateforme.socle.export.ExportTableaux;

/**
 * Côté directions et niveau central (v0.48) : besoins remontés (arbitrage, renvoi, transmission) et dotations
 * (préparation, envoi, accusé de réception, répartition). La portée est contrôlée par les services (comptes de
 * direction, administrateur pays ; le super administrateur consulte).
 */
@RestController
public class BesoinsPilotageController {

    private final RemonteesService remontees;
    private final DotationsService dotations;
    private final ExportBesoins export;

    BesoinsPilotageController(RemonteesService remontees, DotationsService dotations, ExportBesoins export) {
        this.remontees = remontees;
        this.dotations = dotations;
        this.export = export;
    }

    // ------------------------------------------------------------------ besoins

    @GetMapping("/api/v1/pilotage/besoins")
    public VueBesoins besoins(@RequestParam(required = false) UUID direction,
            @RequestParam(required = false) UUID pays, @RequestParam(required = false) String annee) {
        return remontees.vue(direction, pays, annee);
    }

    @GetMapping("/api/v1/pilotage/besoins/export")
    public ResponseEntity<byte[]> exporter(@RequestParam(required = false) UUID direction,
            @RequestParam(required = false) UUID pays, @RequestParam(required = false) String annee,
            @RequestParam(required = false) String format) {
        Map.Entry<VueBesoins, UUID> v = remontees.vuePourExport(direction, pays, annee);
        return export.reponse(v.getKey(), v.getValue(), ExportTableaux.Format.lire(format));
    }

    @GetMapping("/api/v1/pilotage/besoins/remontees/{id}")
    public RemonteeVue remontee(@PathVariable UUID id, @RequestParam(required = false) UUID direction) {
        return remontees.remontee(id, direction);
    }

    @PutMapping("/api/v1/pilotage/besoins/remontees/{id}/arbitrages")
    public RemonteeVue arbitrer(@PathVariable UUID id, @RequestBody DemandeArbitrage demande) {
        return remontees.arbitrer(id, demande);
    }

    @PostMapping("/api/v1/pilotage/besoins/remontees/{id}/renvoyer")
    public RemonteeVue renvoyer(@PathVariable UUID id, @RequestBody DemandeRenvoi demande) {
        return remontees.renvoyerRemontee(id, demande);
    }

    @PostMapping("/api/v1/pilotage/besoins/transmettre")
    public VueBesoins transmettre(@RequestBody(required = false) DemandeTransmission demande) {
        return remontees.transmettre(demande);
    }

    @PostMapping("/api/v1/pilotage/besoins/directions/{id}/renvoyer")
    public VueBesoins renvoyerDirection(@PathVariable UUID id, @RequestBody DemandeRenvoi demande) {
        return remontees.renvoyerDirection(id, demande);
    }

    // ------------------------------------------------------------------ dotations

    @GetMapping("/api/v1/pilotage/dotations")
    public Dotations dotations(@RequestParam(required = false) UUID direction,
            @RequestParam(required = false) UUID pays, @RequestParam(required = false) String annee) {
        return dotations.lister(direction, pays, annee);
    }

    @GetMapping("/api/v1/pilotage/dotations/{id}")
    public DotationVue dotation(@PathVariable UUID id) {
        return dotations.detail(id);
    }

    @PostMapping("/api/v1/pilotage/dotations")
    @ResponseStatus(HttpStatus.CREATED)
    public DotationVue creer(@RequestBody DonneesDotation donnees) {
        return dotations.creer(donnees);
    }

    @PutMapping("/api/v1/pilotage/dotations/{id}")
    public DotationVue modifier(@PathVariable UUID id, @RequestBody DonneesDotation donnees) {
        return dotations.modifier(id, donnees);
    }

    @DeleteMapping("/api/v1/pilotage/dotations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimer(@PathVariable UUID id) {
        dotations.supprimer(id);
    }

    @PostMapping("/api/v1/pilotage/dotations/{id}/envoyer")
    public DotationVue envoyer(@PathVariable UUID id) {
        return dotations.envoyer(id);
    }

    @PostMapping("/api/v1/pilotage/dotations/{id}/accuser")
    public DotationVue accuser(@PathVariable UUID id, @RequestBody(required = false) Reception reception) {
        return dotations.accuserDirection(id, reception);
    }
}
