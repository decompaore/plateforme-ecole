package bf.edutech.plateforme.territoire;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import bf.edutech.plateforme.territoire.ArmoiriesService.ArmoiriesVue;
import bf.edutech.plateforme.territoire.ArmoiriesService.Image;

/**
 * Armoiries des pays (v0.48.1) : téléversées par l'administrateur du pays ou le super administrateur, affichées en
 * filigrane des écrans de toutes les personnes du pays.
 */
@RestController
public class ArmoiriesController {

    private final ArmoiriesService service;

    ArmoiriesController(ArmoiriesService service) {
        this.service = service;
    }

    /** Armoiries du pays de la personne connectée (filigrane) ; 404 s'il n'y en a pas. */
    @GetMapping("/api/v1/armoiries")
    public ResponseEntity<byte[]> courantes() {
        return image(service.armoiriesCourantes(), CacheControl.maxAge(Duration.ofHours(12)).cachePrivate());
    }

    @GetMapping("/api/v1/plateforme/territoire/pays/{id}/armoiries/etat")
    public ArmoiriesVue etat(@PathVariable UUID id) {
        return service.etat(id);
    }

    @GetMapping("/api/v1/plateforme/territoire/pays/{id}/armoiries")
    public ResponseEntity<byte[]> armoiries(@PathVariable UUID id) {
        return image(service.armoiriesDuPays(id), CacheControl.noCache().cachePrivate());
    }

    @PostMapping(path = "/api/v1/plateforme/territoire/pays/{id}/armoiries", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ArmoiriesVue enregistrer(@PathVariable UUID id, @RequestParam("fichier") MultipartFile fichier)
            throws IOException {
        return service.enregistrer(id, fichier.getBytes());
    }

    @DeleteMapping("/api/v1/plateforme/territoire/pays/{id}/armoiries")
    public ArmoiriesVue supprimer(@PathVariable UUID id) {
        return service.supprimer(id);
    }

    private static ResponseEntity<byte[]> image(Image i, CacheControl cache) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(i.type())).cacheControl(cache)
                .body(i.contenu());
    }
}
