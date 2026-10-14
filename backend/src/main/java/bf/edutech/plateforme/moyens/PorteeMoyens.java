package bf.edutech.plateforme.moyens;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.Portee;

/**
 * Portée des comptes territoriaux dans le circuit des besoins et des dotations.
 * <ul>
 * <li>Un compte de direction agit au nom de sa direction ; il consulte aussi les directions qui en dépendent
 * (lecture seule), jamais au-dessus.</li>
 * <li>L'administrateur pays agit au niveau central de son pays.</li>
 * <li>Le super administrateur consulte (lecture seule).</li>
 * </ul>
 */
@Component
class PorteeMoyens {

    /** Une instance du circuit : niveau central, direction ou établissement. */
    record Instance(TypeInstance type, UUID id, String nom) {

        boolean est(TypeInstance t, UUID autre) {
            return type == t && id.equals(autre);
        }
    }

    /** Personne connectée : l'instance au nom de laquelle elle agit (vide pour le super administrateur). */
    record Acteur(Instance instance, boolean superAdmin) {

        boolean agit(Instance i) {
            return instance != null && instance.type() == i.type() && instance.id().equals(i.id());
        }

        boolean estDirection() {
            return instance != null && instance.type() == TypeInstance.DIRECTION;
        }

        boolean estPays() {
            return instance != null && instance.type() == TypeInstance.PAYS;
        }
    }

    private final JdbcTemplate jdbc;

    PorteeMoyens(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Acteur acteur() {
        Optional<UUID> direction = Portee.direction();
        if (direction.isPresent()) {
            return new Acteur(instance(TypeInstance.DIRECTION, direction.get()), false);
        }
        if (Portee.superAdmin()) {
            return new Acteur(null, true);
        }
        UUID pays = Portee.pays().orElseThrow(() -> new AccesRefuseException("Réservé au pilotage"));
        return new Acteur(instance(TypeInstance.PAYS, pays), false);
    }

    /**
     * Instance consultée : la direction demandée (dans la portée), sinon celle de la personne connectée. Le super
     * administrateur précise une direction ou un pays.
     */
    Instance cible(Acteur a, UUID direction, UUID pays) {
        if (direction != null) {
            if (!voitDirection(a, direction)) {
                throw new AccesRefuseException("Cette direction n'est pas dans votre ressort");
            }
            return instance(TypeInstance.DIRECTION, direction);
        }
        if (pays != null) {
            if (!a.superAdmin() && !(a.estPays() && a.instance().id().equals(pays))) {
                throw new AccesRefuseException("Hors de votre ressort");
            }
            return instance(TypeInstance.PAYS, pays);
        }
        if (a.superAdmin()) {
            throw new IllegalArgumentException("Précisez la direction ou le pays");
        }
        return a.instance();
    }

    boolean voitDirection(Acteur a, UUID direction) {
        if (a.superAdmin()) {
            return true;
        }
        if (a.estPays()) {
            return a.instance().id().equals(paysDeDirection(direction));
        }
        return descendantes(a.instance().id()).contains(direction);
    }

    boolean voitEtablissement(Acteur a, UUID tenant) {
        if (a.superAdmin()) {
            return true;
        }
        UUID direction = directionDe(tenant);
        return direction != null && voitDirection(a, direction);
    }

    boolean voit(Acteur a, TypeInstance type, UUID id) {
        return switch (type) {
            case PAYS -> a.superAdmin() || (a.estPays() && a.instance().id().equals(id));
            case DIRECTION -> voitDirection(a, id);
            case ETABLISSEMENT -> voitEtablissement(a, id);
        };
    }

    Instance instance(TypeInstance type, UUID id) {
        String sql = switch (type) {
            case PAYS -> "select nom from pays where id = ?";
            case DIRECTION -> "select nom from direction where id = ?";
            case ETABLISSEMENT -> "select nom from tenant where id = ?";
        };
        List<String> nom = jdbc.queryForList(sql, String.class, id);
        if (nom.isEmpty()) {
            throw new RessourceIntrouvableException(switch (type) {
                case PAYS -> "Pays introuvable";
                case DIRECTION -> "Direction introuvable";
                case ETABLISSEMENT -> "Établissement introuvable";
            });
        }
        return new Instance(type, id, type == TypeInstance.PAYS ? "Niveau central — " + nom.get(0) : nom.get(0));
    }

    /** Une direction et toutes celles qui en dépendent. */
    Set<UUID> descendantes(UUID direction) {
        return new HashSet<>(jdbc.queryForList("select directions_descendantes(?)", UUID.class, direction));
    }

    /** Directions d'un pays. */
    Set<UUID> directionsDuPays(UUID pays) {
        return new HashSet<>(jdbc.queryForList(
                "select d.id from direction d join ministere m on m.id = d.ministere_id where m.pays_id = ?", UUID.class,
                pays));
    }

    /** Directions d'une instance (direction et ses dépendances, ou toutes celles du pays). */
    Set<UUID> directionsDe(Instance i) {
        return i.type() == TypeInstance.PAYS ? directionsDuPays(i.id()) : descendantes(i.id());
    }

    /** Du premier rang jusqu'à la direction elle-même. */
    List<UUID> ascendantes(UUID direction) {
        return jdbc.queryForList("select id from directions_ascendantes(?)", UUID.class, direction);
    }

    UUID paysDeDirection(UUID direction) {
        List<UUID> r = jdbc.queryForList(
                "select m.pays_id from direction d join ministere m on m.id = d.ministere_id where d.id = ?", UUID.class,
                direction);
        if (r.isEmpty()) {
            throw new RessourceIntrouvableException("Direction introuvable");
        }
        return r.get(0);
    }

    UUID directionDe(UUID tenant) {
        List<UUID> r = jdbc.queryForList("select direction_id from tenant where id = ?", UUID.class, tenant);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Parent d'une direction (vide au premier rang). */
    Optional<UUID> parent(UUID direction) {
        return jdbc.queryForList("select parent_id from direction where id = ?", UUID.class, direction).stream()
                .filter(x -> x != null).findFirst();
    }

    /** Directions immédiatement inférieures : celles du premier rang pour le niveau central. */
    List<Instance> enfants(Instance i) {
        if (i.type() == TypeInstance.PAYS) {
            return jdbc.query("""
                    select d.id, d.nom from direction d join ministere m on m.id = d.ministere_id
                    where m.pays_id = ? and d.parent_id is null order by d.nom""",
                    (l, n) -> new Instance(TypeInstance.DIRECTION, l.getObject(1, UUID.class), l.getString(2)), i.id());
        }
        return jdbc.query("select id, nom from direction where parent_id = ? order by nom",
                (l, n) -> new Instance(TypeInstance.DIRECTION, l.getObject(1, UUID.class), l.getString(2)), i.id());
    }

    /** Établissements rattachés directement à une direction (hors résiliés). */
    List<Instance> etablissementsRattaches(UUID direction) {
        return jdbc.query("select id, nom from tenant where direction_id = ? and statut <> 'RESILIE' order by nom",
                (l, n) -> new Instance(TypeInstance.ETABLISSEMENT, l.getObject(1, UUID.class), l.getString(2)),
                direction);
    }

    /** Chemin lisible d'une instance (« Burkina Faso · MESFPT · DR Centre »). */
    String chemin(Instance i) {
        return switch (i.type()) {
            case PAYS -> jdbc.queryForObject("select nom from pays where id = ?", String.class, i.id());
            case DIRECTION -> jdbc.queryForObject("select chemin_direction(?)", String.class, i.id());
            case ETABLISSEMENT -> {
                UUID d = directionDe(i.id());
                yield d == null ? i.nom() : jdbc.queryForObject("select chemin_direction(?)", String.class, d);
            }
        };
    }

    /** Pays de l'instance (pour l'en-tête des exports). */
    UUID paysDe(Instance i) {
        return switch (i.type()) {
            case PAYS -> i.id();
            case DIRECTION -> paysDeDirection(i.id());
            case ETABLISSEMENT -> {
                UUID d = directionDe(i.id());
                yield d == null ? null : paysDeDirection(d);
            }
        };
    }
}
