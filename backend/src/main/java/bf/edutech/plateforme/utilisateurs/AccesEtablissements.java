package bf.edutech.plateforme.utilisateurs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Liste les établissements accessibles à un compte, avant tout choix
 * d'établissement. S'appuie sur la fonction SQL SECURITY DEFINER
 * {@code auth_etablissements_utilisateur}, seule autorisée à lire les
 * appartenances hors cloisonnement, et uniquement pour ce compte.
 */
@Component
class AccesEtablissements {

    private final JdbcTemplate jdbc;

    AccesEtablissements(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<EtablissementAccessible> pour(UUID utilisateurId) {
        Map<UUID, EtablissementAccessible> parEtablissement = new LinkedHashMap<>();
        jdbc.query("select tenant_id, tenant_code, tenant_nom, role from auth_etablissements_utilisateur(?)",
                ligne -> {
                    UUID id = ligne.getObject("tenant_id", UUID.class);
                    parEtablissement
                            .computeIfAbsent(id, cle -> new EtablissementAccessible(cle,
                                    texte(ligne, "tenant_code"), texte(ligne, "tenant_nom"),
                                    new ArrayList<>()))
                            .roles().add(texte(ligne, "role"));
                },
                utilisateurId);
        return parEtablissement.values().stream()
                .map(e -> new EtablissementAccessible(e.id(), e.code(), e.nom(), List.copyOf(e.roles())))
                .toList();
    }

    Optional<EtablissementAccessible> trouver(UUID utilisateurId, UUID tenantId) {
        return pour(utilisateurId).stream().filter(e -> e.id().equals(tenantId)).findFirst();
    }

    private static String texte(java.sql.ResultSet ligne, String colonne) {
        try {
            return ligne.getString(colonne);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
