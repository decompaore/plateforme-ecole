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
    private final bf.edutech.plateforme.socle.modules.ModulesEtablissement modules;

    AccesEtablissements(JdbcTemplate jdbc, bf.edutech.plateforme.socle.modules.ModulesEtablissement modules) {
        this.jdbc = jdbc;
        this.modules = modules;
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
        Map<UUID, java.util.Set<bf.edutech.plateforme.socle.modules.Module>> fermes = modules
                .desactives(parEtablissement.keySet());
        return parEtablissement.values().stream()
                .map(e -> new EtablissementAccessible(e.id(), e.code(), e.nom(), List.copyOf(e.roles()),
                        fermes.getOrDefault(e.id(), java.util.Set.of()).stream().map(Enum::name).sorted().toList()))
                .toList();
    }

    /**
     * Invitations d'enseignant en attente pour ce compte (fonction SQL du module
     * Enseignants, SECURITY DEFINER). Permet à un enseignant invité, qui n'a encore
     * aucun établissement actif, de se connecter pour répondre.
     */
    boolean aDesInvitations(UUID utilisateurId) {
        Boolean existe = jdbc.queryForObject("select exists(select 1 from invitations_enseignant(?))",
                Boolean.class, utilisateurId);
        return Boolean.TRUE.equals(existe);
    }

    /**
     * Début de la période en cours de l'année active de l'établissement (fonction SQL
     * SECURITY DEFINER, appelée avant l'ouverture de la session) ; vide sans année active.
     */
    Optional<java.time.LocalDate> debutPeriodeEnCours(UUID tenantId, java.time.LocalDate jour) {
        return Optional.ofNullable(jdbc.queryForObject("select debut_periode_en_cours(?, ?)", java.time.LocalDate.class,
                tenantId, jour));
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
