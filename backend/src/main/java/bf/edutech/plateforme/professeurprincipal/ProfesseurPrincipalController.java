package bf.edutech.plateforme.professeurprincipal;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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

import bf.edutech.plateforme.professeurprincipal.Vues.ClassePrincipale;
import bf.edutech.plateforme.professeurprincipal.Vues.DemandeDesignation;
import bf.edutech.plateforme.professeurprincipal.Vues.DemandeReponseReclamation;
import bf.edutech.plateforme.professeurprincipal.Vues.DemandeTexte;
import bf.edutech.plateforme.professeurprincipal.Vues.DemandeValidation;
import bf.edutech.plateforme.professeurprincipal.Vues.DonneesReclamation;
import bf.edutech.plateforme.professeurprincipal.Vues.DonneesSaisine;
import bf.edutech.plateforme.professeurprincipal.Vues.ReclamationVue;
import bf.edutech.plateforme.professeurprincipal.Vues.SaisineVue;
import bf.edutech.plateforme.professeurprincipal.Vues.VerificationVue;

/**
 * Professeur principal (v0.49.1) : désignation, dossiers (revendications et médiations), vérification des notes et
 * réclamations. Les droits fins (professeur principal de la classe, auteur, enseignant informé, direction saisie,
 * enseignant de la matière) sont contrôlés par les services.
 */
@RestController
public class ProfesseurPrincipalController {

    static final String PERSONNEL = "hasAnyRole('ADMIN_ECOLE','CENSEUR','SECRETARIAT','SURVEILLANT','CHEF_TRAVAUX','INTENDANT')";
    static final String DESIGNATION = "hasAnyRole('ADMIN_ECOLE','CENSEUR')";
    static final String ENSEIGNANT = "hasRole('ENSEIGNANT')";
    static final String DOSSIERS = "hasAnyRole('ENSEIGNANT','ADMIN_ECOLE','CENSEUR','SURVEILLANT')";
    static final String NOTES = "hasAnyRole('ENSEIGNANT','ADMIN_ECOLE','CENSEUR')";

    private final ProfesseursPrincipauxService professeurs;
    private final SaisinesService saisines;
    private final VerificationNotesService verifications;

    ProfesseurPrincipalController(ProfesseursPrincipauxService professeurs, SaisinesService saisines,
            VerificationNotesService verifications) {
        this.professeurs = professeurs;
        this.saisines = saisines;
        this.verifications = verifications;
    }

    // ------------------------------------------------------------------ désignation

    @GetMapping("/api/v1/annees/{anneeId}/professeurs-principaux")
    @PreAuthorize(PERSONNEL)
    public List<ClassePrincipale> classes(@PathVariable UUID anneeId) {
        return professeurs.classes(anneeId);
    }

    @GetMapping("/api/v1/espace-enseignant/classes-principales")
    @PreAuthorize(ENSEIGNANT)
    public List<ClassePrincipale> mesClasses(@RequestParam UUID anneeId) {
        return professeurs.mesClasses(anneeId);
    }

    @PutMapping("/api/v1/classes/{classeId}/professeur-principal")
    @PreAuthorize(DESIGNATION)
    public ClassePrincipale designer(@PathVariable UUID classeId, @RequestBody DemandeDesignation d) {
        return professeurs.designer(classeId, d == null ? null : d.utilisateurId());
    }

    @DeleteMapping("/api/v1/classes/{classeId}/professeur-principal")
    @PreAuthorize(DESIGNATION)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retirer(@PathVariable UUID classeId) {
        professeurs.retirer(classeId);
    }

    // ------------------------------------------------------------------ dossiers

    @GetMapping("/api/v1/saisines")
    @PreAuthorize(DOSSIERS)
    public List<SaisineVue> saisines(@RequestParam UUID anneeId, @RequestParam(required = false) UUID classeId) {
        return saisines.lister(anneeId, classeId);
    }

    @PostMapping("/api/v1/classes/{classeId}/saisines")
    @PreAuthorize(ENSEIGNANT)
    @ResponseStatus(HttpStatus.CREATED)
    public SaisineVue ouvrir(@PathVariable UUID classeId, @RequestBody DonneesSaisine d) {
        return saisines.ouvrir(classeId, d);
    }

    @GetMapping("/api/v1/saisines/{id}")
    @PreAuthorize(DOSSIERS)
    public SaisineVue saisine(@PathVariable UUID id) {
        return saisines.detail(id);
    }

    @PostMapping("/api/v1/saisines/{id}/suites")
    @PreAuthorize(DOSSIERS)
    public SaisineVue suite(@PathVariable UUID id, @RequestBody DemandeTexte d) {
        return saisines.ajouterSuite(id, d == null ? null : d.texte());
    }

    @PostMapping("/api/v1/saisines/{id}/information-enseignant")
    @PreAuthorize(ENSEIGNANT)
    public SaisineVue informer(@PathVariable UUID id) {
        return saisines.informerEnseignant(id);
    }

    @PostMapping("/api/v1/saisines/{id}/transmission")
    @PreAuthorize(ENSEIGNANT)
    public SaisineVue transmettre(@PathVariable UUID id, @RequestBody(required = false) DemandeTexte d) {
        return saisines.transmettre(id, d == null ? null : d.texte());
    }

    @PostMapping("/api/v1/saisines/{id}/cloture")
    @PreAuthorize(DOSSIERS)
    public SaisineVue clore(@PathVariable UUID id, @RequestBody DemandeTexte d) {
        return saisines.clore(id, d == null ? null : d.texte());
    }

    // ------------------------------------------------------------------ vérification des notes

    @GetMapping("/api/v1/classes/{classeId}/verification-notes")
    @PreAuthorize(NOTES)
    public VerificationVue verification(@PathVariable UUID classeId, @RequestParam UUID periodeId) {
        return verifications.verification(classeId, periodeId);
    }

    @PostMapping("/api/v1/classes/{classeId}/verification-notes/reclamations")
    @PreAuthorize(ENSEIGNANT)
    @ResponseStatus(HttpStatus.CREATED)
    public VerificationVue reclamer(@PathVariable UUID classeId, @RequestBody DonneesReclamation d) {
        return verifications.reclamer(classeId, d);
    }

    @PostMapping("/api/v1/classes/{classeId}/verification-notes/validation")
    @PreAuthorize(ENSEIGNANT)
    public VerificationVue valider(@PathVariable UUID classeId, @RequestBody DemandeValidation d) {
        return verifications.valider(classeId, d == null ? null : d.periodeId(), d == null ? null : d.observations());
    }

    @PostMapping("/api/v1/reclamations-notes/{id}/reponse")
    @PreAuthorize(NOTES)
    public ReclamationVue repondre(@PathVariable UUID id, @RequestBody DemandeReponseReclamation d) {
        return verifications.repondre(id, d == null ? null : d.decision(), d == null ? null : d.reponse());
    }

    @GetMapping("/api/v1/espace-enseignant/reclamations-notes")
    @PreAuthorize(ENSEIGNANT)
    public List<ReclamationVue> mesReclamations() {
        return verifications.mesReclamations();
    }
}
