package bf.edutech.plateforme.utilisateurs;

import java.util.UUID;

/** Membre d'un établissement, avec son identité (vue de lecture). */
public record MembreVue(UUID id, UUID utilisateurId, String nom, String prenoms, String telephone, Role role,
        boolean actif) {
}
