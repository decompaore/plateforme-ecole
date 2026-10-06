package bf.edutech.plateforme.reversibilite;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Téléchargement d'une archive par lien signé (ouvert sans jeton d'accès : le lien EST
 * l'autorisation). Les reprises (en-tête Range) sont acceptées.
 */
@RestController
@RequestMapping("/api/v1/telechargements/exports")
public class TelechargementController {

    private final ExportsService service;

    TelechargementController(ExportsService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> telecharger(@PathVariable UUID id, @RequestParam(required = false) String jeton,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String plage) {
        boolean reprise = plage != null && !plage.startsWith("bytes=0-");
        ExportsService.Fichier f = service.ouvrir(id, jeton, reprise);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.nom(), StandardCharsets.UTF_8).build().toString())
                .header("Referrer-Policy", "no-referrer")
                .body(new FileSystemResource(f.chemin()));
    }
}
