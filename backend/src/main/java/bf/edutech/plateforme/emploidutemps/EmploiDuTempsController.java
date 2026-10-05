package bf.edutech.plateforme.emploidutemps;

import java.util.List;
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

import bf.edutech.plateforme.emploidutemps.Vues.CreneauVue;
import bf.edutech.plateforme.emploidutemps.Vues.DemandeGeneration;
import bf.edutech.plateforme.emploidutemps.Vues.DonneesGrille;
import bf.edutech.plateforme.emploidutemps.Vues.DonneesSeance;
import bf.edutech.plateforme.emploidutemps.Vues.EmploiDuTempsVue;
import bf.edutech.plateforme.emploidutemps.Vues.MonEmploiVue;
import bf.edutech.plateforme.emploidutemps.Vues.ResultatGeneration;
import bf.edutech.plateforme.socle.export.ExportTableaux.Format;

/**
 * Emplois du temps : la direction consulte tout ; le censeur place les matières générales,
 * le chef des travaux les matières techniques et pratiques (contrôlé par le service).
 */
@RestController
public class EmploiDuTempsController {

    private static final String BASE = "/api/v1/annees/{anneeId}/emploi-du-temps";
    private static final String PLACEMENT = "hasAnyRole('ADMIN_ECOLE','CENSEUR','CHEF_TRAVAUX')";
    private static final String GRILLE = "hasAnyRole('ADMIN_ECOLE','CENSEUR')";

    private final EmploiDuTempsService service;
    private final ExportsEmploi exports;

    EmploiDuTempsController(EmploiDuTempsService service, ExportsEmploi exports) {
        this.service = service;
        this.exports = exports;
    }

    @GetMapping("/api/v1/annees/{anneeId}/creneaux")
    @PreAuthorize(Responsabilites.LECTURE)
    public List<CreneauVue> creneaux(@PathVariable UUID anneeId) {
        return service.creneaux(anneeId);
    }

    @PutMapping("/api/v1/annees/{anneeId}/creneaux")
    @PreAuthorize(GRILLE)
    public List<CreneauVue> definirGrille(@PathVariable UUID anneeId, @RequestBody DonneesGrille d) {
        return service.definirGrille(anneeId, d);
    }

    @GetMapping(BASE)
    @PreAuthorize(Responsabilites.LECTURE)
    public EmploiDuTempsVue emploi(@PathVariable UUID anneeId) {
        return service.emploi(anneeId);
    }

    @GetMapping(BASE + "/export")
    @PreAuthorize(Responsabilites.LECTURE)
    public ResponseEntity<byte[]> exporter(@PathVariable UUID anneeId, @RequestParam(required = false) String format,
            @RequestParam(required = false) UUID classe, @RequestParam(required = false) UUID enseignant,
            @RequestParam(required = false) UUID atelier) {
        return exports.emploi(anneeId, classe, enseignant, atelier, Format.lire(format));
    }

    @PostMapping(BASE + "/generation")
    @PreAuthorize(PLACEMENT)
    public ResultatGeneration generer(@PathVariable UUID anneeId, @RequestBody(required = false) DemandeGeneration d) {
        return service.generer(anneeId, d);
    }

    @PostMapping(BASE + "/publication")
    @PreAuthorize(GRILLE)
    public EmploiDuTempsVue publier(@PathVariable UUID anneeId) {
        return service.publier(anneeId);
    }

    @DeleteMapping(BASE + "/publication")
    @PreAuthorize(GRILLE)
    public EmploiDuTempsVue retirerPublication(@PathVariable UUID anneeId) {
        return service.retirerPublication(anneeId);
    }

    /** Crée ou déplace une séance ; l'identifiant est choisi par l'application (renvoi sans doublon). */
    @PutMapping("/api/v1/seances-emploi/{id}")
    @PreAuthorize(PLACEMENT)
    public EmploiDuTempsVue enregistrer(@PathVariable UUID id, @RequestBody DonneesSeance d) {
        return service.enregistrer(id, d);
    }

    @DeleteMapping("/api/v1/seances-emploi/{id}")
    @PreAuthorize(PLACEMENT)
    public EmploiDuTempsVue supprimer(@PathVariable UUID id) {
        return service.supprimer(id);
    }

    // ---------------- Espace enseignant

    @GetMapping("/api/v1/espace-enseignant/emploi-du-temps")
    @PreAuthorize("hasRole('ENSEIGNANT')")
    public MonEmploiVue monEmploi() {
        return service.monEmploi();
    }

    @GetMapping("/api/v1/espace-enseignant/emploi-du-temps/export")
    @PreAuthorize("hasRole('ENSEIGNANT')")
    public ResponseEntity<byte[]> exporterMonEmploi(@RequestParam(required = false) String format) {
        return exports.monEmploi(Format.lire(format));
    }
}
