package bf.edutech.plateforme.ateliers;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import bf.edutech.plateforme.ateliers.CatalogueService.Photo;
import bf.edutech.plateforme.ateliers.Vues.ArticleVue;
import bf.edutech.plateforme.ateliers.Vues.AtelierResumeVue;
import bf.edutech.plateforme.ateliers.Vues.AtelierVue;
import bf.edutech.plateforme.ateliers.Vues.CandidatVue;
import bf.edutech.plateforme.ateliers.Vues.DemandeCloturePanne;
import bf.edutech.plateforme.ateliers.Vues.DemandeFinMandat;
import bf.edutech.plateforme.ateliers.Vues.DemandeInventaire;
import bf.edutech.plateforme.ateliers.Vues.DemandeMandat;
import bf.edutech.plateforme.ateliers.Vues.DemandeMouvement;
import bf.edutech.plateforme.ateliers.Vues.DemandePanne;
import bf.edutech.plateforme.ateliers.Vues.DemandeSeuil;
import bf.edutech.plateforme.ateliers.Vues.DonneesArticle;
import bf.edutech.plateforme.ateliers.Vues.DonneesAtelier;
import bf.edutech.plateforme.ateliers.Vues.DonneesEquipement;
import bf.edutech.plateforme.ateliers.Vues.EquipementVue;
import bf.edutech.plateforme.ateliers.Vues.InventaireResumeVue;
import bf.edutech.plateforme.ateliers.Vues.InventaireVue;
import bf.edutech.plateforme.ateliers.Vues.LigneStockVue;
import bf.edutech.plateforme.ateliers.Vues.MouvementVue;
import bf.edutech.plateforme.ateliers.Vues.PanneVue;
import bf.edutech.plateforme.ateliers.Vues.ParametresVue;
import bf.edutech.plateforme.ateliers.Vues.SaisieInventaire;

/**
 * Ateliers : catalogue des prix, ateliers et responsables, équipements et pannes, stock de matière
 * d'œuvre, inventaires. Les droits fins (direction, responsable, enseignant de l'atelier) sont
 * contrôlés par les services.
 */
@RestController
public class AteliersController {

    static final String LECTURE = "hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','INTENDANT','ENSEIGNANT')";

    private final ParametresAteliersService parametres;
    private final CatalogueService catalogue;
    private final AteliersService ateliers;
    private final EquipementsService equipements;
    private final StockService stock;
    private final InventairesService inventaires;

    AteliersController(ParametresAteliersService parametres, CatalogueService catalogue, AteliersService ateliers,
            EquipementsService equipements, StockService stock, InventairesService inventaires) {
        this.parametres = parametres;
        this.catalogue = catalogue;
        this.ateliers = ateliers;
        this.equipements = equipements;
        this.stock = stock;
        this.inventaires = inventaires;
    }

    // ---------------- Paramètres

    @GetMapping("/api/v1/parametres/ateliers")
    @PreAuthorize(LECTURE)
    public ParametresVue parametres() {
        return parametres.lire();
    }

    @PutMapping("/api/v1/parametres/ateliers")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX')")
    public ParametresVue modifierParametres(@RequestBody ParametresVue p) {
        return parametres.modifier(p);
    }

    // ---------------- Catalogue des prix

    @GetMapping("/api/v1/catalogue")
    @PreAuthorize(LECTURE)
    public List<ArticleVue> catalogue() {
        return catalogue.lister();
    }

    @PostMapping("/api/v1/catalogue")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(LECTURE)
    public ArticleVue creerArticle(@RequestBody DonneesArticle d) {
        return catalogue.creer(d);
    }

    @PutMapping("/api/v1/catalogue/{id}")
    @PreAuthorize(LECTURE)
    public ArticleVue modifierArticle(@PathVariable UUID id, @RequestBody DonneesArticle d) {
        return catalogue.modifier(id, d);
    }

    @PostMapping(path = "/api/v1/catalogue/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(LECTURE)
    public ArticleVue enregistrerPhoto(@PathVariable UUID id, @RequestParam("fichier") MultipartFile fichier)
            throws IOException {
        return catalogue.enregistrerPhoto(id, fichier.getContentType(), fichier.getBytes());
    }

    @GetMapping("/api/v1/catalogue/{id}/photo")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> photo(@PathVariable UUID id) {
        Photo p = catalogue.photo(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(p.type()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate()).body(p.contenu());
    }

    @DeleteMapping("/api/v1/catalogue/{id}/photo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(LECTURE)
    public void supprimerPhoto(@PathVariable UUID id) {
        catalogue.supprimerPhoto(id);
    }

    // ---------------- Ateliers et responsables

    @GetMapping("/api/v1/ateliers")
    @PreAuthorize(LECTURE)
    public List<AtelierResumeVue> ateliers() {
        return ateliers.lister();
    }

    @PostMapping("/api/v1/ateliers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX')")
    public AtelierVue creerAtelier(@RequestBody DonneesAtelier d) {
        return ateliers.creer(d);
    }

    @GetMapping("/api/v1/ateliers/{id}")
    @PreAuthorize(LECTURE)
    public AtelierVue atelier(@PathVariable UUID id) {
        return ateliers.fiche(id);
    }

    @PutMapping("/api/v1/ateliers/{id}")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX')")
    public AtelierVue modifierAtelier(@PathVariable UUID id, @RequestBody DonneesAtelier d) {
        return ateliers.modifier(id, d);
    }

    @GetMapping("/api/v1/ateliers/{id}/candidats")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX')")
    public List<CandidatVue> candidats(@PathVariable UUID id) {
        return ateliers.candidats(id);
    }

    @PostMapping("/api/v1/ateliers/{id}/responsable")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX')")
    public AtelierVue designer(@PathVariable UUID id, @RequestBody DemandeMandat d) {
        return ateliers.designer(id, d);
    }

    @PostMapping("/api/v1/ateliers/{id}/responsable/fin")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX')")
    public AtelierVue terminerMandat(@PathVariable UUID id, @RequestBody(required = false) DemandeFinMandat d) {
        return ateliers.terminerMandat(id, d);
    }

    // ---------------- Équipements et pannes

    @GetMapping("/api/v1/ateliers/{id}/equipements")
    @PreAuthorize(LECTURE)
    public List<EquipementVue> equipements(@PathVariable UUID id) {
        return equipements.lister(id);
    }

    @PostMapping("/api/v1/ateliers/{id}/equipements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(LECTURE)
    public EquipementVue creerEquipement(@PathVariable UUID id, @RequestBody DonneesEquipement d) {
        return equipements.creer(id, d);
    }

    @PutMapping("/api/v1/equipements/{id}")
    @PreAuthorize(LECTURE)
    public EquipementVue modifierEquipement(@PathVariable UUID id, @RequestBody DonneesEquipement d) {
        return equipements.modifier(id, d);
    }

    @GetMapping("/api/v1/equipements/{id}/pannes")
    @PreAuthorize(LECTURE)
    public List<PanneVue> pannes(@PathVariable UUID id) {
        return equipements.pannes(id);
    }

    @PostMapping("/api/v1/equipements/{id}/pannes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(LECTURE)
    public PanneVue signaler(@PathVariable UUID id, @RequestBody DemandePanne d) {
        return equipements.signaler(id, d);
    }

    @PostMapping("/api/v1/pannes/{id}/cloture")
    @PreAuthorize(LECTURE)
    public PanneVue cloturer(@PathVariable UUID id, @RequestBody DemandeCloturePanne d) {
        return equipements.cloturer(id, d);
    }

    // ---------------- Matière d'œuvre

    @GetMapping("/api/v1/ateliers/{id}/stock")
    @PreAuthorize(LECTURE)
    public List<LigneStockVue> stock(@PathVariable UUID id) {
        return stock.stock(id);
    }

    @PostMapping("/api/v1/ateliers/{id}/mouvements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(LECTURE)
    public MouvementVue mouvement(@PathVariable UUID id, @RequestBody DemandeMouvement d) {
        return stock.mouvement(id, d);
    }

    @GetMapping("/api/v1/ateliers/{id}/mouvements")
    @PreAuthorize(LECTURE)
    public List<MouvementVue> mouvements(@PathVariable UUID id, @RequestParam(required = false) UUID articleId) {
        return stock.mouvements(id, articleId);
    }

    @PutMapping("/api/v1/ateliers/{id}/stock/{articleId}/seuil")
    @PreAuthorize(LECTURE)
    public LigneStockVue seuil(@PathVariable UUID id, @PathVariable UUID articleId, @RequestBody DemandeSeuil d) {
        return stock.definirSeuil(id, articleId, d);
    }

    // ---------------- Inventaires

    @GetMapping("/api/v1/ateliers/{id}/inventaires")
    @PreAuthorize(LECTURE)
    public List<InventaireResumeVue> inventaires(@PathVariable UUID id) {
        return inventaires.lister(id);
    }

    @PostMapping("/api/v1/ateliers/{id}/inventaires")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(LECTURE)
    public InventaireVue ouvrirInventaire(@PathVariable UUID id, @RequestBody(required = false) DemandeInventaire d) {
        return inventaires.ouvrir(id, d);
    }

    @GetMapping("/api/v1/inventaires/{id}")
    @PreAuthorize(LECTURE)
    public InventaireVue inventaire(@PathVariable UUID id) {
        return inventaires.fiche(id);
    }

    @PutMapping("/api/v1/inventaires/{id}/lignes")
    @PreAuthorize(LECTURE)
    public InventaireVue saisirInventaire(@PathVariable UUID id, @RequestBody SaisieInventaire d) {
        return inventaires.saisir(id, d);
    }

    @PostMapping("/api/v1/inventaires/{id}/cloture")
    @PreAuthorize(LECTURE)
    public InventaireVue clore(@PathVariable UUID id) {
        return inventaires.clore(id);
    }
}
