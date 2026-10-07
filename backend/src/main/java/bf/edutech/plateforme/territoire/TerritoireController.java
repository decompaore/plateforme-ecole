package bf.edutech.plateforme.territoire;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.territoire.Vues.DemandeImport;
import bf.edutech.plateforme.territoire.Vues.DirectionChemin;
import bf.edutech.plateforme.territoire.Vues.DirectionVue;
import bf.edutech.plateforme.territoire.Vues.DonneesDirection;
import bf.edutech.plateforme.territoire.Vues.DonneesMinistere;
import bf.edutech.plateforme.territoire.Vues.DonneesPays;
import bf.edutech.plateforme.territoire.Vues.MinistereVue;
import bf.edutech.plateforme.territoire.Vues.PaysVue;
import bf.edutech.plateforme.territoire.Vues.RapportImport;

/** Référentiel territorial : réservé au super administrateur (règle de /api/v1/plateforme/**). */
@RestController
@RequestMapping("/api/v1/plateforme/territoire")
public class TerritoireController {

    private final TerritoireService service;

    TerritoireController(TerritoireService service) {
        this.service = service;
    }

    @GetMapping("/pays")
    public List<PaysVue> pays() {
        return service.pays();
    }

    @PostMapping("/pays")
    @ResponseStatus(HttpStatus.CREATED)
    public PaysVue creerPays(@Valid @RequestBody DonneesPays d) {
        return service.creerPays(d);
    }

    @PutMapping("/pays/{id}")
    public PaysVue modifierPays(@PathVariable UUID id, @Valid @RequestBody DonneesPays d) {
        return service.modifierPays(id, d);
    }

    @GetMapping("/pays/{paysId}/ministeres")
    public List<MinistereVue> ministeres(@PathVariable UUID paysId) {
        return service.ministeres(paysId);
    }

    @PostMapping("/pays/{paysId}/ministeres")
    @ResponseStatus(HttpStatus.CREATED)
    public MinistereVue creerMinistere(@PathVariable UUID paysId, @Valid @RequestBody DonneesMinistere d) {
        return service.creerMinistere(paysId, d);
    }

    @PutMapping("/ministeres/{id}")
    public MinistereVue modifierMinistere(@PathVariable UUID id, @Valid @RequestBody DonneesMinistere d) {
        return service.modifierMinistere(id, d);
    }

    @GetMapping("/ministeres/{ministereId}/directions")
    public List<DirectionVue> directions(@PathVariable UUID ministereId) {
        return service.directions(ministereId);
    }

    @PostMapping("/ministeres/{ministereId}/directions")
    @ResponseStatus(HttpStatus.CREATED)
    public DirectionVue creerDirection(@PathVariable UUID ministereId, @Valid @RequestBody DonneesDirection d) {
        return service.creerDirection(ministereId, d);
    }

    @PutMapping("/directions/{id}")
    public DirectionVue modifierDirection(@PathVariable UUID id, @Valid @RequestBody DonneesDirection d) {
        return service.modifierDirection(id, d);
    }

    /** Import CSV des directions d'un ministère ({@code code;nom;code_parent}). */
    @PostMapping("/ministeres/{ministereId}/directions/import")
    public RapportImport importer(@PathVariable UUID ministereId, @Valid @RequestBody DemandeImport d) {
        return service.importer(ministereId, d.contenu(), d.simulation());
    }

    /** Toutes les directions avec leur chemin (filtres par direction, choix du rattachement). */
    @GetMapping("/directions")
    public List<DirectionChemin> directionsAvecChemin() {
        return service.directionsAvecChemin();
    }
}
