package bf.edutech.plateforme.socle.modules;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.AntPathMatcher;

import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Modules activés de chaque établissement. Lus par le filtre de chaque requête (avec un
 * cache de 30 secondes, vidé à chaque modification) et à la connexion, pour que
 * l'application n'affiche que ce que l'établissement utilise.
 */
@Service
public class ModulesEtablissement {

    static final long DUREE_CACHE_SECONDES = 30;

    private record EnCache(Set<Module> desactives, Instant lu) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock horloge;
    private final AntPathMatcher chemins = new AntPathMatcher();
    private final Map<UUID, EnCache> cache = new ConcurrentHashMap<>();

    ModulesEtablissement(JdbcTemplate jdbc, PlatformTransactionManager gestionnaire, Clock horloge) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(gestionnaire);
        this.horloge = horloge;
    }

    /** Modules désactivés (y compris ceux qui dépendent d'un module désactivé). */
    public Set<Module> desactives(UUID tenantId) {
        Instant maintenant = horloge.instant();
        EnCache c = cache.get(tenantId);
        if (c != null && c.lu().plusSeconds(DUREE_CACHE_SECONDES).isAfter(maintenant)) {
            return c.desactives();
        }
        Set<Module> lus = desactives(List.of(tenantId)).getOrDefault(tenantId, EnumSet.noneOf(Module.class));
        cache.put(tenantId, new EnCache(lus, maintenant));
        return lus;
    }

    /** Modules désactivés de plusieurs établissements (connexion). */
    public Map<UUID, Set<Module>> desactives(Collection<UUID> tenants) {
        Map<UUID, Set<Module>> parEtablissement = new HashMap<>();
        if (tenants.isEmpty()) {
            return parEtablissement;
        }
        String tableau = tenants.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        jdbc.query("select tenant_id, module from modules_desactives(?::uuid[])", ligne -> {
            parEtablissement.computeIfAbsent(ligne.getObject(1, UUID.class), k -> EnumSet.noneOf(Module.class))
                    .add(Module.valueOf(ligne.getString(2)));
        }, tableau);
        parEtablissement.replaceAll((k, v) -> completer(v));
        return parEtablissement;
    }

    public boolean actif(UUID tenantId, Module module) {
        return !desactives(tenantId).contains(module);
    }

    /** Premier module désactivé auquel appartient ce chemin d'API, s'il y en a un. */
    public Module moduleFerme(UUID tenantId, String chemin) {
        Set<Module> fermes = desactives(tenantId);
        if (fermes.isEmpty()) {
            return null;
        }
        for (Module m : fermes) {
            for (String motif : m.chemins()) {
                if (chemins.match(motif, chemin)) {
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * Remplace la liste des modules désactivés d'un établissement (super administrateur).
     * Désactiver un module désactive aussi ceux qui en dépendent.
     */
    public Set<Module> definir(UUID tenantId, Set<Module> desactives, UUID par) {
        Set<Module> complets = completer(desactives);
        TenantContext.executerPour(tenantId, () -> transaction.execute(statut -> {
            jdbc.update("delete from module_desactive where tenant_id = ?", tenantId);
            for (Module m : complets) {
                jdbc.update("insert into module_desactive (tenant_id, module, desactive_par) values (?, ?, ?)",
                        tenantId, m.name(), par);
            }
            return null;
        }));
        cache.remove(tenantId);
        return complets;
    }

    static Set<Module> completer(Set<Module> desactives) {
        Set<Module> complets = desactives.isEmpty() ? EnumSet.noneOf(Module.class) : EnumSet.copyOf(desactives);
        for (Module m : Module.values()) {
            if (m.requis() != null && complets.contains(m.requis())) {
                complets.add(m);
            }
        }
        return complets;
    }
}
