package bf.edutech.plateforme.utilisateurs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

import bf.edutech.plateforme.socle.documents.CartesPdf;
import bf.edutech.plateforme.socle.documents.EntetesOfficiels;

/**
 * Photos des membres de l'établissement, cartes professionnelles, et signature du compte connecté (v0.44).
 */
@RestController
public class PhotosEtSignaturesController {

    private static final Map<Role, String> FONCTIONS = Map.of(
            Role.ADMIN_ECOLE, "Chef d'établissement", Role.CENSEUR, "Censeur", Role.CHEF_TRAVAUX, "Chef des travaux",
            Role.SECRETARIAT, "Secrétariat", Role.INTENDANT, "Intendant", Role.SURVEILLANT, "CPE",
            Role.ENSEIGNANT, "Enseignant");

    private final PhotosPersonnes photos;
    private final Signatures signatures;
    private final MembresService membres;
    private final EntetesOfficiels entetes;

    PhotosEtSignaturesController(PhotosPersonnes photos, Signatures signatures, MembresService membres,
            EntetesOfficiels entetes) {
        this.photos = photos;
        this.signatures = signatures;
        this.membres = membres;
        this.entetes = entetes;
    }

    // ---------------- Photos des membres (enseignants et personnel)

    /** {@code miniature=true} : 96 × 120 pixels, pour les listes. */
    @GetMapping("/api/v1/personnes/{utilisateurId}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable UUID utilisateurId,
            @RequestParam(defaultValue = "false") boolean miniature) {
        PhotosPersonnes.Photo p = photos.lire(utilisateurId, miniature);
        return image(p.type(), p.contenu());
    }

    @PostMapping(path = "/api/v1/personnes/{utilisateurId}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enregistrerPhoto(@PathVariable UUID utilisateurId, @RequestParam("fichier") MultipartFile fichier)
            throws IOException {
        photos.enregistrer(utilisateurId, fichier.getBytes());
    }

    @DeleteMapping("/api/v1/personnes/{utilisateurId}/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supprimerPhoto(@PathVariable UUID utilisateurId) {
        photos.supprimer(utilisateurId);
    }

    /** Comptes qui ont une photo dans l'établissement. */
    @GetMapping("/api/v1/personnes/photos")
    public Set<UUID> avecPhoto() {
        return photos.avecPhoto();
    }

    /**
     * Cartes professionnelles des membres actifs (enseignants et personnel), huit par page A4.
     * {@code utilisateur} : la carte d'une seule personne.
     */
    @GetMapping("/api/v1/personnes/cartes")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','SECRETARIAT')")
    public ResponseEntity<byte[]> cartes(@RequestParam(required = false) UUID utilisateur) {
        Map<UUID, List<MembreVue>> parPersonne = new LinkedHashMap<>();
        membres.lister().stream()
                .filter(MembreVue::actif)
                .filter(m -> m.role() != Role.PARENT && m.role() != Role.ELEVE)
                .filter(m -> utilisateur == null || m.utilisateurId().equals(utilisateur))
                .sorted(Comparator.comparing(MembreVue::nom).thenComparing(MembreVue::prenoms))
                .forEach(m -> parPersonne.computeIfAbsent(m.utilisateurId(), k -> new ArrayList<>()).add(m));
        Map<UUID, byte[]> images = photos.photos(new ArrayList<>(parPersonne.keySet()));
        List<CartesPdf.Carte> cartes = new ArrayList<>();
        for (Map.Entry<UUID, List<MembreVue>> e : parPersonne.entrySet()) {
            MembreVue m = e.getValue().get(0);
            String fonctions = e.getValue().stream().map(x -> FONCTIONS.getOrDefault(x.role(), x.role().name()))
                    .distinct().collect(Collectors.joining(", "));
            List<String[]> lignes = new ArrayList<>();
            lignes.add(new String[] { "Fonction", fonctions });
            lignes.add(new String[] { "Téléphone", m.telephone() });
            cartes.add(new CartesPdf.Carte(m.nom(), m.prenoms(), lignes, images.get(e.getKey()),
                    "Le titulaire de cette carte est membre de l'établissement."));
        }
        byte[] pdf = CartesPdf.rendre(entetes.courant(), "CARTE PROFESSIONNELLE", cartes);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("cartes-personnel.pdf").build().toString())
                .body(pdf);
    }

    // ---------------- Signature du compte connecté

    @GetMapping("/api/v1/moi/signature/etat")
    public Signatures.EtatSignature etatSignature() {
        return signatures.etat();
    }

    @GetMapping("/api/v1/moi/signature")
    public ResponseEntity<byte[]> signature() {
        return image("image/png", signatures.mienne());
    }

    @PostMapping(path = "/api/v1/moi/signature", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Signatures.EtatSignature enregistrerSignature(@RequestParam("fichier") MultipartFile fichier) throws IOException {
        return signatures.enregistrer(fichier.getBytes());
    }

    @DeleteMapping("/api/v1/moi/signature")
    public Signatures.EtatSignature supprimerSignature() {
        return signatures.supprimer();
    }

    private static ResponseEntity<byte[]> image(String type, byte[] contenu) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(type))
                .cacheControl(CacheControl.noCache().cachePrivate()).body(contenu);
    }
}
