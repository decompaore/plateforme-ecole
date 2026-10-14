package bf.edutech.plateforme.eleves;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Photos d'identité des élèves et cartes scolaires (v0.44). */
@RestController
public class PhotosElevesController {

    private final PhotosEleves photos;

    PhotosElevesController(PhotosEleves photos) {
        this.photos = photos;
    }

    /** {@code miniature=true} : 96 × 120 pixels, pour les listes de classe. */
    @GetMapping("/api/v1/eleves/{id}/photo")
    @PreAuthorize(Roles.LISTES_DE_CLASSE)
    public ResponseEntity<byte[]> photo(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean miniature) {
        PhotosEleves.Photo p = photos.lire(id, miniature);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(p.type()))
                .cacheControl(CacheControl.noCache().cachePrivate()).body(p.contenu());
    }

    @PostMapping(path = "/api/v1/eleves/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Roles.SAISIE)
    public void enregistrer(@PathVariable UUID id, @RequestParam("fichier") MultipartFile fichier) throws IOException {
        photos.enregistrer(id, fichier.getBytes());
    }

    @DeleteMapping("/api/v1/eleves/{id}/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(Roles.MODIFICATION)
    public void supprimer(@PathVariable UUID id) {
        photos.supprimer(id);
    }

    /** Parmi ces élèves (une page de la recherche, 200 au plus), ceux qui ont une photo (v0.44.1). */
    @GetMapping("/api/v1/eleves/photos")
    @PreAuthorize(Roles.CONSULTATION)
    public Set<UUID> parmi(@RequestParam(defaultValue = "") List<UUID> ids) {
        return photos.parmi(ids);
    }

    /** Élèves de la classe qui ont une photo (les autres n'ont pas besoin d'être demandés). */
    @GetMapping("/api/v1/classes/{id}/photos")
    @PreAuthorize(Roles.LISTES_DE_CLASSE)
    public Set<UUID> avecPhoto(@PathVariable UUID id) {
        return photos.avecPhoto(id);
    }

    @GetMapping("/api/v1/classes/{id}/cartes")
    @PreAuthorize(Roles.DOCUMENTS)
    public ResponseEntity<byte[]> cartesClasse(@PathVariable UUID id) {
        return pdf(photos.cartesClasse(id), "cartes-scolaires.pdf");
    }

    @GetMapping("/api/v1/eleves/{id}/carte")
    @PreAuthorize(Roles.DOCUMENTS)
    public ResponseEntity<byte[]> carte(@PathVariable UUID id) {
        return pdf(photos.carte(id), "carte-scolaire.pdf");
    }

    private static ResponseEntity<byte[]> pdf(byte[] contenu, String nom) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(nom).build().toString())
                .body(contenu);
    }
}
