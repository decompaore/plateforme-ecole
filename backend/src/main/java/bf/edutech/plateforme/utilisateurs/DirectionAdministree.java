package bf.edutech.plateforme.utilisateurs;

import java.util.UUID;

/**
 * Direction (régionale, provinciale…) d'un compte de direction (v0.37), avec son rattachement
 * complet (« Burkina Faso · MESFPT · Direction régionale du Centre »).
 */
public record DirectionAdministree(UUID id, String code, String nom, String chemin, UUID paysId) {
}
