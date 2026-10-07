package bf.edutech.plateforme.utilisateurs;

import java.util.UUID;

/** Pays administré par un administrateur pays (v0.36). */
public record PaysAdministre(UUID id, String code, String nom) {
}
