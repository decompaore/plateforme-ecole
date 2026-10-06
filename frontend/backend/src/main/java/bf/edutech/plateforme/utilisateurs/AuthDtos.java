package bf.edutech.plateforme.utilisateurs;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Objets échangés par les points d'accès d'authentification. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record DemandeConnexion(
            @NotBlank(message = "Le téléphone est obligatoire") String telephone,
            @NotBlank(message = "Le mot de passe est obligatoire") @Size(max = 100) String motDePasse) {
    }

    public record DemandeChoixEtablissement(
            @NotNull(message = "L'établissement est obligatoire") UUID etablissementId) {
    }

    public record DemandeChangementMotDePasse(
            @NotBlank String motDePasseActuel,
            @NotBlank @Size(min = 8, max = 100, message = "Le mot de passe doit faire entre 8 et 100 caractères")
            String nouveauMotDePasse) {
    }

    /**
     * Réponse de connexion. Si {@code selectionRequise} est vrai, le client doit
     * appeler /auth/etablissement avec {@code jetonSelection} et l'établissement choisi.
     * Le jeton de rafraîchissement n'apparaît jamais ici : il est posé en cookie HttpOnly.
     */
    public record ReponseConnexion(
            String jetonAcces,
            String jetonSelection,
            long expireDansSecondes,
            boolean selectionRequise,
            EtablissementAccessible etablissementActif,
            List<EtablissementAccessible> etablissements,
            boolean superAdmin,
            boolean doitChangerMotDePasse,
            /** PROVISOIRE ou RENOUVELLEMENT (nouvelle période) ; null si aucun changement n'est demandé. */
            String motifChangementMotDePasse) {

        static ReponseConnexion depuis(AuthService.ResultatConnexion r) {
            return new ReponseConnexion(r.jetonAcces(), r.jetonSelection(), r.expireDansSecondes(),
                    r.jetonSelection() != null, r.etablissementActif(), r.etablissements(), r.superAdmin(),
                    r.doitChangerMotDePasse(), r.motifChangementMotDePasse());
        }
    }

    public record ProfilConnecte(
            UUID id,
            String nom,
            String prenoms,
            String telephone,
            boolean superAdmin,
            UUID etablissementId,
            String etablissementCode,
            List<String> roles,
            boolean doitChangerMotDePasse,
            String motifChangementMotDePasse) {
    }
}
