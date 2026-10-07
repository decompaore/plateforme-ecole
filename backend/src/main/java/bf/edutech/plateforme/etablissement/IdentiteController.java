package bf.edutech.plateforme.etablissement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import bf.edutech.plateforme.etablissement.IdentiteService.IdentiteVue;
import bf.edutech.plateforme.etablissement.IdentiteService.Logo;

/** Identité de l'établissement sur ses documents : rattachement (lecture) et logo (administrateur). */
@RestController
@RequestMapping("/api/v1/identite")
@PreAuthorize("hasRole('ADMIN_ECOLE')")
public class IdentiteController {

    private final IdentiteService service;

    IdentiteController(IdentiteService service) {
        this.service = service;
    }

    @GetMapping
    public IdentiteVue identite() {
        return service.identite();
    }

    @GetMapping("/logo")
    public ResponseEntity<byte[]> logo() {
        Logo l = service.logo();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(l.type()))
                .cacheControl(CacheControl.noCache().cachePrivate()).body(l.contenu());
    }

    @PostMapping(path = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IdentiteVue enregistrerLogo(@RequestParam("fichier") MultipartFile fichier) throws IOException {
        return service.enregistrerLogo(fichier.getBytes());
    }

    @DeleteMapping("/logo")
    public IdentiteVue supprimerLogo() {
        return service.supprimerLogo();
    }

    @GetMapping("/apercu")
    public ResponseEntity<byte[]> apercu() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename("apercu-entete.pdf", StandardCharsets.UTF_8).build().toString())
                .body(service.apercu());
    }
}
