package bf.edutech.plateforme.eleves;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import bf.edutech.plateforme.eleves.ImportElevesService.Fichier;
import bf.edutech.plateforme.eleves.ImportElevesService.RapportImport;

/** Import des élèves d'une année depuis Excel : modèle, simulation, import réel. */
@RestController
@PreAuthorize(Roles.GESTION)
public class ImportElevesController {

    static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ImportElevesService service;

    ImportElevesController(ImportElevesService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/annees/{anneeId}/eleves/import/modele")
    public ResponseEntity<byte[]> modele(@PathVariable UUID anneeId) {
        Fichier fichier = service.modele(anneeId);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fichier.nom()).build().toString())
                .body(fichier.contenu());
    }

    /**
     * Contrôle (simulation=true, par défaut) ou importe (simulation=false) le
     * fichier. Le rapport liste les lignes en erreur avec leurs motifs.
     */
    @PostMapping(path = "/api/v1/annees/{anneeId}/eleves/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RapportImport importer(@PathVariable UUID anneeId, @RequestParam("fichier") MultipartFile fichier,
            @RequestParam(defaultValue = "true") boolean simulation) throws IOException {
        if (fichier.isEmpty()) {
            throw new IllegalArgumentException("Le fichier est vide");
        }
        try (InputStream flux = fichier.getInputStream()) {
            return service.importer(anneeId, flux, simulation);
        }
    }
}
