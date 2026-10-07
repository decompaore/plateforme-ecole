package bf.edutech.plateforme.pilotage;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.pilotage.Vues.TableauPilotage;
import bf.edutech.plateforme.socle.documents.EnteteOfficiel;
import bf.edutech.plateforme.socle.export.ExportTableaux;

/**
 * Tableau de bord de pilotage (comptes de direction, administrateurs pays, super administrateur).
 * Sans paramètre : la direction du compte, ou le pays de l'administrateur pays.
 */
@RestController
@RequestMapping("/api/v1/pilotage")
public class PilotageController {

    private final PilotageService service;

    PilotageController(PilotageService service) {
        this.service = service;
    }

    @GetMapping
    public TableauPilotage tableau(@RequestParam(required = false) UUID pays,
            @RequestParam(required = false) UUID direction, @RequestParam(required = false) String annee) {
        return service.tableau(pays, direction, annee);
    }

    /** Excel (par défaut) ou PDF : synthèse, directions, établissements et examens. */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exporter(@RequestParam(required = false) UUID pays,
            @RequestParam(required = false) UUID direction, @RequestParam(required = false) String annee,
            @RequestParam(required = false) String format) {
        ExportTableaux.Format f = ExportTableaux.Format.lire(format);
        Map.Entry<TableauPilotage, EnteteOfficiel> r = service.tableauEtEntete(pays, direction, annee);
        return ExportTableaux.reponse(r.getValue(), ExportPilotage.nomFichier(r.getKey()), f,
                ExportPilotage.tableaux(r.getKey()));
    }
}
