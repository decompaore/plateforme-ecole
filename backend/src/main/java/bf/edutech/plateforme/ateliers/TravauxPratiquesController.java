package bf.edutech.plateforme.ateliers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.ateliers.ProductionService.DemandeStatut;
import bf.edutech.plateforme.ateliers.ProductionService.DonneesProduction;
import bf.edutech.plateforme.ateliers.ProductionService.ProductionVue;
import bf.edutech.plateforme.ateliers.SeancesTpService.DemandeCompteRendu;
import bf.edutech.plateforme.ateliers.SeancesTpService.DemandeGeneration;
import bf.edutech.plateforme.ateliers.SeancesTpService.DemandeMotif;
import bf.edutech.plateforme.ateliers.SeancesTpService.DemandePreparation;
import bf.edutech.plateforme.ateliers.SeancesTpService.DemandeValidation;
import bf.edutech.plateforme.ateliers.SeancesTpService.DonneesSeanceTp;
import bf.edutech.plateforme.ateliers.SeancesTpService.ResultatGeneration;
import bf.edutech.plateforme.ateliers.SeancesTpService.SeanceTpVue;
import bf.edutech.plateforme.ateliers.SecuriteAteliersService.ConsignesVue;
import bf.edutech.plateforme.ateliers.SecuriteAteliersService.DonneesConsigne;
import bf.edutech.plateforme.ateliers.SecuriteAteliersService.DonneesEvenement;
import bf.edutech.plateforme.ateliers.SecuriteAteliersService.EvenementVue;
import bf.edutech.plateforme.ateliers.SecuriteAteliersService.LectureVue;

/**
 * Travaux pratiques dans les ateliers (v0.47.2) : séances (planning, préparation, compte rendu, bilan), consignes de
 * sécurité et journal HSE, commandes de production. Les droits fins (chef d'atelier, enseignant de l'atelier,
 * enseignant de la séance) sont contrôlés par les services.
 */
@RestController
public class TravauxPratiquesController {

    static final String MODULE = "hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','INTENDANT','ENSEIGNANT')";

    private final SeancesTpService seances;
    private final SecuriteAteliersService securite;
    private final ProductionService production;

    TravauxPratiquesController(SeancesTpService seances, SecuriteAteliersService securite,
            ProductionService production) {
        this.seances = seances;
        this.securite = securite;
        this.production = production;
    }

    // ------------------------------------------------------------------ séances

    @GetMapping("/api/v1/ateliers/{id}/seances-tp")
    @PreAuthorize(MODULE)
    public List<SeanceTpVue> planning(@PathVariable UUID id, @RequestParam(required = false) LocalDate du,
            @RequestParam(required = false) LocalDate au) {
        return seances.planning(id, du, au);
    }

    @GetMapping("/api/v1/ateliers/{id}/seances-tp/options")
    @PreAuthorize(MODULE)
    public List<SeancesTpService.OptionSeance> options(@PathVariable UUID id) {
        return seances.options(id);
    }

    @GetMapping("/api/v1/seances-tp")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX')")
    public List<SeanceTpVue> planningGeneral(@RequestParam(required = false) LocalDate du,
            @RequestParam(required = false) LocalDate au) {
        return seances.planningGeneral(du, au);
    }

    @GetMapping("/api/v1/seances-tp/miennes")
    @PreAuthorize("hasRole('ENSEIGNANT')")
    public List<SeanceTpVue> miennes(@RequestParam(required = false) LocalDate du,
            @RequestParam(required = false) LocalDate au) {
        return seances.miennes(du, au);
    }

    @GetMapping("/api/v1/seances-tp/achats")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','INTENDANT')")
    public List<Map<String, Object>> achats() {
        return seances.achats();
    }

    @GetMapping("/api/v1/seances-tp/{id}")
    @PreAuthorize(MODULE)
    public SeanceTpVue seance(@PathVariable UUID id) {
        return seances.trouver(id);
    }

    @PostMapping("/api/v1/ateliers/{id}/seances-tp")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public SeanceTpVue creer(@PathVariable UUID id, @RequestBody DonneesSeanceTp d) {
        return seances.creer(id, d);
    }

    @PostMapping("/api/v1/ateliers/{id}/seances-tp/generation")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public ResultatGeneration generer(@PathVariable UUID id, @RequestBody DemandeGeneration d) {
        return seances.generer(id, d);
    }

    @PutMapping("/api/v1/seances-tp/{id}")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public SeanceTpVue modifier(@PathVariable UUID id, @RequestBody DonneesSeanceTp d) {
        return seances.modifier(id, d);
    }

    @PostMapping("/api/v1/seances-tp/{id}/validation")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public SeanceTpVue valider(@PathVariable UUID id, @RequestBody(required = false) DemandeValidation d) {
        return seances.valider(id, d);
    }

    @PostMapping("/api/v1/seances-tp/{id}/refus")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public SeanceTpVue refuser(@PathVariable UUID id, @RequestBody DemandeMotif d) {
        return seances.refuser(id, d);
    }

    @PostMapping("/api/v1/seances-tp/{id}/annulation")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public SeanceTpVue annuler(@PathVariable UUID id, @RequestBody DemandeMotif d) {
        return seances.annuler(id, d);
    }

    @PostMapping("/api/v1/seances-tp/{id}/preparation")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public SeanceTpVue preparer(@PathVariable UUID id, @RequestBody(required = false) DemandePreparation d) {
        return seances.preparer(id, d);
    }

    @PostMapping("/api/v1/seances-tp/{id}/compte-rendu")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public SeanceTpVue compteRendu(@PathVariable UUID id, @RequestBody DemandeCompteRendu d) {
        return seances.compteRendu(id, d);
    }

    @PostMapping("/api/v1/seances-tp/{id}/bilan")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public SeanceTpVue bilan(@PathVariable UUID id) {
        return seances.bilan(id);
    }

    // ------------------------------------------------------------------ sécurité

    @GetMapping("/api/v1/ateliers/{id}/consignes")
    @PreAuthorize(MODULE)
    public ConsignesVue consignes(@PathVariable UUID id) {
        return securite.consignes(id);
    }

    @PutMapping("/api/v1/ateliers/{id}/consignes")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public ConsignesVue definirConsignes(@PathVariable UUID id, @RequestBody List<DonneesConsigne> consignes) {
        return securite.definir(id, consignes);
    }

    @PostMapping("/api/v1/ateliers/{id}/consignes/lecture")
    @PreAuthorize("hasRole('ENSEIGNANT')")
    public ConsignesVue lireConsignes(@PathVariable UUID id) {
        return securite.lire(id);
    }

    @GetMapping("/api/v1/ateliers/{id}/consignes/lectures")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public List<LectureVue> lectures(@PathVariable UUID id) {
        return securite.lectures(id);
    }

    @GetMapping("/api/v1/ateliers/{id}/hse")
    @PreAuthorize(MODULE)
    public List<EvenementVue> journal(@PathVariable UUID id) {
        return securite.journal(id);
    }

    @PostMapping("/api/v1/ateliers/{id}/hse")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public EvenementVue consigner(@PathVariable UUID id, @RequestBody DonneesEvenement d) {
        return securite.consigner(id, d);
    }

    // ------------------------------------------------------------------ production

    @GetMapping("/api/v1/productions")
    @PreAuthorize(MODULE)
    public List<ProductionVue> productions(@RequestParam(required = false) UUID atelier) {
        return production.lister(atelier);
    }

    @GetMapping("/api/v1/productions/{id}")
    @PreAuthorize(MODULE)
    public ProductionVue productionUne(@PathVariable UUID id) {
        return production.trouver(id);
    }

    @PostMapping("/api/v1/productions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public ProductionVue creerProduction(@RequestBody DonneesProduction d) {
        return production.creer(d);
    }

    @PutMapping("/api/v1/productions/{id}")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public ProductionVue modifierProduction(@PathVariable UUID id, @RequestBody DonneesProduction d) {
        return production.modifier(id, d);
    }

    @PostMapping("/api/v1/productions/{id}/statut")
    @PreAuthorize("hasAnyRole('ADMIN_ECOLE','CHEF_TRAVAUX','ENSEIGNANT')")
    public ProductionVue statutProduction(@PathVariable UUID id, @RequestBody DemandeStatut d) {
        return production.changerStatut(id, d);
    }
}
