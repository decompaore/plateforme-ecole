package bf.edutech.plateforme.ateliers;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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

import bf.edutech.plateforme.ateliers.VuesBesoins.BesoinAtelierResumeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.BesoinVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.CampagneResumeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.CampagneVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.CommandeVue;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeAnnulation;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeArbitrage;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeCampagne;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeCommande;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeLigne;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeLivraison;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeRenvoi;
import bf.edutech.plateforme.ateliers.VuesBesoins.DemandeRepartition;
import bf.edutech.plateforme.ateliers.VuesBesoins.LivraisonVue;
import bf.edutech.plateforme.socle.export.ExportTableaux.Format;

/**
 * Circuit des besoins (campagnes, besoins des ateliers, arbitrage et validation par la direction,
 * transmission à la direction régionale), commandes, réception et répartition entre les ateliers.
 * Expose aussi les exports Excel et PDF des pages du chef des travaux et du chef d'atelier.
 */
@RestController
public class BesoinsController {

    private static final String LECTURE = AteliersController.LECTURE;

    private final BesoinsService besoins;
    private final CommandesService commandes;
    private final ExportsAteliers exports;

    BesoinsController(BesoinsService besoins, CommandesService commandes, ExportsAteliers exports) {
        this.besoins = besoins;
        this.commandes = commandes;
        this.exports = exports;
    }

    // ---------------- Campagnes

    @GetMapping("/api/v1/campagnes-besoins")
    @PreAuthorize(LECTURE)
    public List<CampagneResumeVue> campagnes() {
        return besoins.lister();
    }

    @PostMapping("/api/v1/campagnes-besoins")
    @PreAuthorize(LECTURE)
    @ResponseStatus(HttpStatus.CREATED)
    public CampagneVue ouvrir(@RequestBody DemandeCampagne demande) {
        return besoins.ouvrir(demande);
    }

    @GetMapping("/api/v1/campagnes-besoins/{id}")
    @PreAuthorize(LECTURE)
    public CampagneVue campagne(@PathVariable UUID id) {
        return besoins.fiche(id);
    }

    @PutMapping("/api/v1/campagnes-besoins/{id}")
    @PreAuthorize(LECTURE)
    public CampagneVue modifier(@PathVariable UUID id, @RequestBody DemandeCampagne demande) {
        return besoins.modifier(id, demande);
    }

    @PostMapping("/api/v1/campagnes-besoins/{id}/transmission")
    @PreAuthorize(LECTURE)
    public CampagneVue transmettre(@PathVariable UUID id) {
        return besoins.transmettre(id);
    }

    @PostMapping("/api/v1/campagnes-besoins/{id}/cloture")
    @PreAuthorize(LECTURE)
    public CampagneVue clore(@PathVariable UUID id) {
        return besoins.clore(id);
    }

    // ---------------- Besoins d'un atelier

    @GetMapping("/api/v1/ateliers/{id}/besoins")
    @PreAuthorize(LECTURE)
    public List<BesoinAtelierResumeVue> besoinsDeLAtelier(@PathVariable UUID id) {
        return besoins.besoinsDeLAtelier(id);
    }

    @GetMapping("/api/v1/besoins-ateliers/{id}")
    @PreAuthorize(LECTURE)
    public BesoinVue besoin(@PathVariable UUID id) {
        return besoins.besoin(id);
    }

    @PutMapping("/api/v1/besoins-ateliers/{id}/lignes/{articleId}")
    @PreAuthorize(LECTURE)
    public BesoinVue proposer(@PathVariable UUID id, @PathVariable UUID articleId, @RequestBody DemandeLigne demande) {
        return besoins.proposer(id, articleId, demande);
    }

    @DeleteMapping("/api/v1/besoins-ateliers/{id}/lignes/{articleId}")
    @PreAuthorize(LECTURE)
    public BesoinVue retirer(@PathVariable UUID id, @PathVariable UUID articleId) {
        return besoins.retirer(id, articleId);
    }

    @PostMapping("/api/v1/besoins-ateliers/{id}/transmission")
    @PreAuthorize(LECTURE)
    public BesoinVue transmettreBesoin(@PathVariable UUID id) {
        return besoins.transmettreBesoin(id);
    }

    @PostMapping("/api/v1/besoins-ateliers/{id}/renvoi")
    @PreAuthorize(LECTURE)
    public BesoinVue renvoyer(@PathVariable UUID id, @RequestBody DemandeRenvoi demande) {
        return besoins.renvoyer(id, demande);
    }

    @PutMapping("/api/v1/besoins-ateliers/{id}/arbitrage")
    @PreAuthorize(LECTURE)
    public BesoinVue arbitrer(@PathVariable UUID id, @RequestBody DemandeArbitrage demande) {
        return besoins.arbitrer(id, demande);
    }

    @PostMapping("/api/v1/besoins-ateliers/{id}/validation")
    @PreAuthorize(LECTURE)
    public BesoinVue valider(@PathVariable UUID id) {
        return besoins.valider(id);
    }

    // ---------------- Commandes et livraisons

    @PostMapping("/api/v1/campagnes-besoins/{id}/commandes")
    @PreAuthorize(LECTURE)
    @ResponseStatus(HttpStatus.CREATED)
    public CommandeVue commander(@PathVariable UUID id, @RequestBody DemandeCommande demande) {
        return commandes.creer(id, demande);
    }

    @GetMapping("/api/v1/commandes/{id}")
    @PreAuthorize(LECTURE)
    public CommandeVue commande(@PathVariable UUID id) {
        return commandes.fiche(id);
    }

    @PostMapping("/api/v1/commandes/{id}/annulation")
    @PreAuthorize(LECTURE)
    public CommandeVue annuler(@PathVariable UUID id, @RequestBody DemandeAnnulation demande) {
        return commandes.annuler(id, demande);
    }

    @PostMapping("/api/v1/commandes/{id}/livraisons")
    @PreAuthorize(LECTURE)
    @ResponseStatus(HttpStatus.CREATED)
    public LivraisonVue recevoir(@PathVariable UUID id, @RequestBody DemandeLivraison demande) {
        return commandes.recevoir(id, demande);
    }

    @GetMapping("/api/v1/livraisons/{id}")
    @PreAuthorize(LECTURE)
    public LivraisonVue livraison(@PathVariable UUID id) {
        return commandes.livraison(id);
    }

    @PutMapping("/api/v1/livraisons/{id}/repartition")
    @PreAuthorize(LECTURE)
    public LivraisonVue repartir(@PathVariable UUID id, @RequestBody DemandeRepartition demande) {
        return commandes.enregistrerRepartition(id, demande);
    }

    @PostMapping("/api/v1/livraisons/{id}/repartition/validation")
    @PreAuthorize(LECTURE)
    public LivraisonVue validerRepartition(@PathVariable UUID id) {
        return commandes.validerRepartition(id);
    }

    // ---------------- Exports Excel (format=xlsx, par défaut) et PDF (format=pdf)

    @GetMapping("/api/v1/ateliers/export")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> exporterAteliers(@RequestParam(defaultValue = "xlsx") String format) {
        return exports.tableauDeBord(Format.lire(format));
    }

    @GetMapping("/api/v1/catalogue/export")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> exporterCatalogue(@RequestParam(defaultValue = "xlsx") String format) {
        return exports.catalogue(Format.lire(format));
    }

    @GetMapping("/api/v1/ateliers/stock-par-filiere/export")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> exporterStockParFiliere(@RequestParam(defaultValue = "xlsx") String format) {
        return exports.stockParFiliere(Format.lire(format));
    }

    @GetMapping("/api/v1/ateliers/{id}/equipements/export")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> exporterEquipements(@PathVariable UUID id,
            @RequestParam(defaultValue = "xlsx") String format) {
        return exports.equipements(id, Format.lire(format));
    }

    @GetMapping("/api/v1/ateliers/{id}/stock/export")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> exporterStock(@PathVariable UUID id,
            @RequestParam(defaultValue = "xlsx") String format) {
        return exports.stock(id, Format.lire(format));
    }

    @GetMapping("/api/v1/inventaires/{id}/export")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> exporterInventaire(@PathVariable UUID id,
            @RequestParam(defaultValue = "xlsx") String format) {
        return exports.inventaire(id, Format.lire(format));
    }

    @GetMapping("/api/v1/besoins-ateliers/{id}/export")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> exporterBesoin(@PathVariable UUID id,
            @RequestParam(defaultValue = "xlsx") String format) {
        return exports.besoin(id, Format.lire(format));
    }

    @GetMapping("/api/v1/campagnes-besoins/{id}/export")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> exporterCampagne(@PathVariable UUID id,
            @RequestParam(defaultValue = "xlsx") String format) {
        return exports.etatCampagne(id, Format.lire(format));
    }

    @GetMapping("/api/v1/commandes/{id}/export")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> exporterCommande(@PathVariable UUID id,
            @RequestParam(defaultValue = "xlsx") String format) {
        return exports.commande(id, Format.lire(format));
    }

    @GetMapping("/api/v1/livraisons/{id}/export")
    @PreAuthorize(LECTURE)
    public ResponseEntity<byte[]> exporterLivraison(@PathVariable UUID id,
            @RequestParam(defaultValue = "xlsx") String format) {
        return exports.livraison(id, Format.lire(format));
    }
}
