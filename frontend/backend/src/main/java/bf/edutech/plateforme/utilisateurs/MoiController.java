package bf.edutech.plateforme.utilisateurs;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import bf.edutech.plateforme.socle.erreurs.AuthentificationException;
import bf.edutech.plateforme.socle.securite.SecuriteConfig;
import bf.edutech.plateforme.socle.tenant.TenantFilter;
import bf.edutech.plateforme.utilisateurs.AuthDtos.DemandeChangementMotDePasse;
import bf.edutech.plateforme.utilisateurs.AuthDtos.ProfilConnecte;

/** Informations sur le compte connecté. */
@RestController
@RequestMapping("/api/v1/moi")
public class MoiController {

    private final UtilisateurRepository utilisateurs;
    private final AuthService service;

    MoiController(UtilisateurRepository utilisateurs, AuthService service) {
        this.utilisateurs = utilisateurs;
        this.service = service;
    }

    @GetMapping
    public ProfilConnecte profil(@AuthenticationPrincipal Jwt jwt) {
        Utilisateur u = utilisateurs.findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new AuthentificationException("Compte introuvable"));
        String tenant = jwt.getClaimAsString(TenantFilter.CLAIM_TENANT_ID);
        List<String> roles = jwt.getClaimAsStringList(SecuriteConfig.CLAIM_ROLES);
        return new ProfilConnecte(u.getId(), u.getNom(), u.getPrenoms(), u.getTelephone(), u.isSuperAdmin(),
                tenant != null ? UUID.fromString(tenant) : null, jwt.getClaimAsString(TenantFilter.CLAIM_TENANT_CODE),
                roles != null ? roles : List.of(), u.isDoitChangerMotDePasse(),
                u.getMotifChangement());
    }

    /** Établissements accessibles (pour le sélecteur d'établissement de l'interface). */
    @GetMapping("/etablissements")
    public List<EtablissementAccessible> etablissements(@AuthenticationPrincipal Jwt jwt) {
        return service.etablissementsDe(UUID.fromString(jwt.getSubject()));
    }

    /** Après le changement, le client appelle /auth/rafraichir pour obtenir un jeton à jour. */
    @PostMapping("/mot-de-passe")
    public ResponseEntity<Void> changerMotDePasse(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DemandeChangementMotDePasse demande) {
        service.changerMotDePasse(UUID.fromString(jwt.getSubject()), demande.motDePasseActuel(),
                demande.nouveauMotDePasse());
        return ResponseEntity.noContent().build();
    }
}
