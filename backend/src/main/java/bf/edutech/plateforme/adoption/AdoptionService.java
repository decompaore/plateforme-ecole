package bf.edutech.plateforme.adoption;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Mesure de l'adoption.
 * <ul>
 * <li>Activité : un jour d'activité par personne et par établissement (ouverture ou
 * renouvellement d'une session, voir {@code SessionsAppareils}).</li>
 * <li>Actions : chaque nuit, la fonction SQL {@code calculer_adoption} compte les actions de
 * la veille (appels, notes, cahier de textes, paiements, SMS…) par établissement. Le jour en
 * cours est recalculé à la demande, au plus toutes les 10 minutes.</li>
 * <li>Le super administrateur ne lit que des nombres, par des fonctions SECURITY DEFINER ;
 * l'administrateur d'un établissement voit en plus l'activité de son personnel (RLS).</li>
 * </ul>
 */
@Service
public class AdoptionService {

    private static final Logger LOG = LoggerFactory.getLogger(AdoptionService.class);
    static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");
    private static final long FRAICHEUR_SECONDES = 600;
    /** Rôles qui ne font pas partie du personnel. */
    private static final Set<String> HORS_PERSONNEL = Set.of("PARENT", "ELEVE");

    public record IndicateurVue(String code, String libelle) {
    }

    public record JourActif(LocalDate jour, int actifs) {
    }

    public record Taux(int total, int actifs) {
    }

    public record EtablissementAdoption(UUID id, String code, String nom, String statut, int comptes, int actifs,
            Taux enseignants, Taux parents, int joursActifs, LocalDate derniereActivite, Map<String, Long> actions,
            List<String> alertes) {
    }

    public record VuePlateforme(LocalDate debut, LocalDate fin, Instant calculeLe, List<IndicateurVue> indicateurs,
            int etablissements, int etablissementsActifs, int comptes, int actifs, Taux enseignants, Taux parents,
            Map<String, Long> actions, List<JourActif> quotidien, List<EtablissementAdoption> details) {
    }

    public record PersonneActivite(UUID utilisateurId, String nom, String prenoms, List<String> roles,
            int joursActifs, LocalDate derniereActivite, long appels, long notes, long cahier) {
    }

    public record VueEtablissement(LocalDate debut, LocalDate fin, Instant calculeLe, List<IndicateurVue> indicateurs,
            int comptes, int actifs, Taux personnel, Taux enseignants, Taux parents, Map<String, Long> actions,
            List<JourActif> quotidien, List<PersonneActivite> personnes) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AdoptionProperties proprietes;
    private final Clock horloge;
    private final AtomicReference<Instant> dernierCalculDuJour = new AtomicReference<>(Instant.EPOCH);

    AdoptionService(JdbcTemplate jdbc, PlatformTransactionManager gestionnaire, AdoptionProperties proprietes,
            Clock horloge) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(gestionnaire);
        this.proprietes = proprietes;
        this.horloge = horloge;
    }

    // ------------------------------------------------------------------ Calcul

    LocalDate aujourdhui() {
        return LocalDate.ofInstant(horloge.instant(), FUSEAU);
    }

    /** Calcule (ou recalcule) les mesures d'un jour pour tous les établissements. */
    public int calculer(LocalDate jour) {
        Integer n = transaction.execute(s -> jdbc.queryForObject("select calculer_adoption(?)", Integer.class, jour));
        return n == null ? 0 : n;
    }

    /** Recalcule les {@code jours} derniers jours (super administrateur). */
    public void recalculer(int jours) {
        LocalDate fin = aujourdhui();
        for (LocalDate d = fin.minusDays(borner(jours) - 1L); !d.isAfter(fin); d = d.plusDays(1)) {
            calculer(d);
        }
        dernierCalculDuJour.set(horloge.instant());
    }

    /** Chaque nuit : la veille (définitive) et l'avant-veille (saisies faites sans réseau, envoyées en retard). */
    @Scheduled(cron = "${app.adoption.cron:0 20 0 * * *}", zone = "Africa/Ouagadougou")
    void calculNocturne() {
        if (!proprietes.calculAutomatique()) {
            return;
        }
        LocalDate hier = aujourdhui().minusDays(1);
        calculer(hier.minusDays(1));
        calculer(hier);
    }

    /** Au démarrage : les jours passés jamais calculés (première installation de la v0.34). */
    @EventListener(ApplicationReadyEvent.class)
    void rattraper() {
        if (!proprietes.calculAutomatique()) {
            return;
        }
        Thread.ofVirtual().name("adoption-rattrapage").start(() -> {
            try {
                List<LocalDate> manquants = jdbc.queryForList("""
                        select d::date from generate_series(
                            greatest(?::date - ?::int, (select min(cree_le)::date from tenant)), ?::date - 1, interval '1 day') d
                        where not exists (select 1 from calcul_adoption c where c.jour = d::date)
                        order by 1""", LocalDate.class, aujourdhui(), proprietes.historiqueJours(), aujourdhui());
                for (LocalDate d : manquants) {
                    calculer(d);
                }
                if (!manquants.isEmpty()) {
                    LOG.info("Mesure de l'adoption : {} jour(s) passé(s) calculé(s)", manquants.size());
                }
            } catch (RuntimeException e) {
                LOG.warn("Rattrapage de la mesure de l'adoption interrompu : {}", e.getMessage());
            }
        });
    }

    /** Le jour en cours, recalculé au plus toutes les 10 minutes avant une consultation. */
    private void rafraichirAujourdhui() {
        Instant maintenant = horloge.instant();
        Instant precedent = dernierCalculDuJour.get();
        if (precedent.plusSeconds(FRAICHEUR_SECONDES).isBefore(maintenant)
                && dernierCalculDuJour.compareAndSet(precedent, maintenant)) {
            calculer(aujourdhui());
        }
    }

    // ------------------------------------------------------------------ Super administrateur

    public VuePlateforme plateforme(int jours) {
        rafraichirAujourdhui();
        LocalDate fin = aujourdhui();
        LocalDate debut = fin.minusDays(borner(jours) - 1L);
        return transaction.execute(s -> {
            Map<UUID, Map<String, Long>> actionsParEtab = new HashMap<>();
            jdbc.query("select tenant_id, indicateur, total from adoption_indicateurs(?, ?)", (ResultSet rs) -> {
                actionsParEtab.computeIfAbsent(rs.getObject(1, UUID.class), k -> new LinkedHashMap<>())
                        .put(rs.getString(2), rs.getLong(3));
            }, debut, fin);
            Map<UUID, String[]> etablissements = new LinkedHashMap<>();
            jdbc.query("select id, code, nom, statut from tenant order by nom", (ResultSet rs) -> {
                etablissements.put(rs.getObject(1, UUID.class),
                        new String[] { rs.getString(2), rs.getString(3), rs.getString(4) });
            });
            List<EtablissementAdoption> details = new ArrayList<>();
            jdbc.query("select * from adoption_etablissements(?, ?)", (ResultSet rs) -> {
                UUID id = rs.getObject("tenant_id", UUID.class);
                String[] t = etablissements.get(id);
                if (t == null) {
                    return;
                }
                Map<String, Long> actions = ordonner(actionsParEtab.getOrDefault(id, Map.of()));
                Taux enseignants = new Taux(rs.getInt("enseignants"), rs.getInt("enseignants_actifs"));
                LocalDate derniere = rs.getObject("derniere_activite", LocalDate.class);
                details.add(new EtablissementAdoption(id, t[0], t[1], t[2], rs.getInt("comptes"), rs.getInt("actifs"),
                        enseignants, new Taux(rs.getInt("parents"), rs.getInt("parents_actifs")),
                        rs.getInt("jours_actifs"), derniere, actions, alertes(t[2], derniere, enseignants, actions, fin)));
            }, debut, fin);
            // À accompagner d'abord, puis par nom
            details.sort(Comparator.comparing((EtablissementAdoption e) -> e.alertes().isEmpty())
                    .thenComparing(EtablissementAdoption::nom, String.CASE_INSENSITIVE_ORDER));

            List<JourActif> quotidien = new ArrayList<>();
            Map<LocalDate, Integer> parJour = new HashMap<>();
            jdbc.query("select jour, actifs from adoption_quotidienne(?, ?)", (ResultSet rs) -> {
                parJour.put(rs.getObject(1, LocalDate.class), rs.getInt(2));
            }, debut, fin);
            for (LocalDate d = debut; !d.isAfter(fin); d = d.plusDays(1)) {
                quotidien.add(new JourActif(d, parJour.getOrDefault(d, 0)));
            }

            Map<String, Long> totaux = new LinkedHashMap<>();
            details.forEach(e -> e.actions().forEach((k, v) -> totaux.merge(k, v, Long::sum)));
            List<EtablissementAdoption> ouverts = details.stream().filter(e -> "ACTIF".equals(e.statut())).toList();
            return new VuePlateforme(debut, fin, dernierCalculDuJour.get(), catalogue(),
                    ouverts.size(),
                    (int) ouverts.stream().filter(e -> e.actifs() > 0).count(),
                    ouverts.stream().mapToInt(EtablissementAdoption::comptes).sum(),
                    ouverts.stream().mapToInt(EtablissementAdoption::actifs).sum(),
                    new Taux(ouverts.stream().mapToInt(e -> e.enseignants().total()).sum(),
                            ouverts.stream().mapToInt(e -> e.enseignants().actifs()).sum()),
                    new Taux(ouverts.stream().mapToInt(e -> e.parents().total()).sum(),
                            ouverts.stream().mapToInt(e -> e.parents().actifs()).sum()),
                    ordonner(totaux), quotidien, details);
        });
    }

    /**
     * Établissements à accompagner : aucune activité depuis 7 jours, moins de la moitié des
     * enseignants actifs sur la période, ou enseignants actifs mais aucun appel fait dans l'application.
     */
    static List<String> alertes(String statut, LocalDate derniere, Taux enseignants, Map<String, Long> actions,
            LocalDate fin) {
        List<String> alertes = new ArrayList<>();
        if (!"ACTIF".equals(statut)) {
            return alertes;
        }
        if (derniere == null || derniere.isBefore(fin.minusDays(6))) {
            alertes.add("SANS_ACTIVITE");
            return alertes;
        }
        if (enseignants.total() > 0 && enseignants.actifs() * 2 < enseignants.total()) {
            alertes.add("PEU_D_ENSEIGNANTS");
        }
        if (enseignants.actifs() > 0 && actions.getOrDefault(Indicateur.APPELS.name(), 0L) == 0) {
            alertes.add("SANS_APPEL");
        }
        return alertes;
    }

    // ------------------------------------------------------------------ Administrateur d'un établissement

    public VueEtablissement etablissement(int jours) {
        TenantContext.courant().orElseThrow(() -> new IllegalStateException("Aucun établissement actif"));
        rafraichirAujourdhui();
        LocalDate fin = aujourdhui();
        LocalDate debut = fin.minusDays(borner(jours) - 1L);
        OffsetDateTime depuis = debut.atStartOfDay(FUSEAU).toOffsetDateTime();
        return transaction.execute(s -> {
            // Membres actifs de l'établissement et leurs rôles (Row-Level Security)
            Map<UUID, Object[]> membres = new LinkedHashMap<>();
            jdbc.query("""
                    select m.utilisateur_id, u.nom, u.prenoms, array_agg(distinct m.role order by m.role)
                    from membre_etablissement m join utilisateur u on u.id = m.utilisateur_id
                    where m.actif group by m.utilisateur_id, u.nom, u.prenoms order by u.nom, u.prenoms""",
                    (ResultSet rs) -> {
                        membres.put(rs.getObject(1, UUID.class),
                                new Object[] { rs.getString(2), rs.getString(3), roles(rs.getArray(4)) });
                    });
            Map<UUID, Integer> joursActifs = new HashMap<>();
            jdbc.query("select utilisateur_id, count(*) from activite_jour where jour between ? and ? group by 1",
                    (ResultSet rs) -> {
                        joursActifs.put(rs.getObject(1, UUID.class), rs.getInt(2));
                    }, debut, fin);
            Map<UUID, LocalDate> derniere = new HashMap<>();
            jdbc.query("select utilisateur_id, max(jour) from activite_jour group by 1", (ResultSet rs) -> {
                derniere.put(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class));
            });
            Map<UUID, Long> appels = compter("select fait_par, count(*) from appel where saisi_le >= ? group by 1", depuis);
            Map<UUID, Long> notes = compter("select saisi_par, count(*) from note where saisi_le >= ? group by 1", depuis);
            Map<UUID, Long> cahier = compter(
                    "select saisi_par, count(*) from seance_cahier where saisi_le >= ? group by 1", depuis);

            List<PersonneActivite> personnes = new ArrayList<>();
            int[] compteurs = new int[8]; // comptes, actifs, personnel, personnel actifs, ens, ens actifs, parents, parents actifs
            membres.forEach((id, m) -> {
                @SuppressWarnings("unchecked")
                List<String> roles = (List<String>) m[2];
                boolean actif = joursActifs.containsKey(id);
                boolean personnel = roles.stream().anyMatch(r -> !HORS_PERSONNEL.contains(r));
                compteurs[0]++;
                compteurs[1] += actif ? 1 : 0;
                if (personnel) {
                    compteurs[2]++;
                    compteurs[3] += actif ? 1 : 0;
                    personnes.add(new PersonneActivite(id, (String) m[0], (String) m[1],
                            roles.stream().filter(r -> !HORS_PERSONNEL.contains(r)).toList(),
                            joursActifs.getOrDefault(id, 0), derniere.get(id), appels.getOrDefault(id, 0L),
                            notes.getOrDefault(id, 0L), cahier.getOrDefault(id, 0L)));
                }
                if (roles.contains("ENSEIGNANT")) {
                    compteurs[4]++;
                    compteurs[5] += actif ? 1 : 0;
                }
                if (roles.contains("PARENT")) {
                    compteurs[6]++;
                    compteurs[7] += actif ? 1 : 0;
                }
            });

            Map<String, Long> actions = new HashMap<>();
            jdbc.query("""
                    select indicateur, sum(valeur) from mesure_adoption
                    where jour between ? and ? and indicateur <> 'ACTIFS' group by 1""", (ResultSet rs) -> {
                actions.put(rs.getString(1), rs.getLong(2));
            }, debut, fin);
            Map<LocalDate, Integer> parJour = new HashMap<>();
            jdbc.query("select jour, count(*) from activite_jour where jour between ? and ? group by 1",
                    (ResultSet rs) -> {
                        parJour.put(rs.getObject(1, LocalDate.class), rs.getInt(2));
                    }, debut, fin);
            List<JourActif> quotidien = new ArrayList<>();
            for (LocalDate d = debut; !d.isAfter(fin); d = d.plusDays(1)) {
                quotidien.add(new JourActif(d, parJour.getOrDefault(d, 0)));
            }
            return new VueEtablissement(debut, fin, dernierCalculDuJour.get(), catalogue(), compteurs[0], compteurs[1],
                    new Taux(compteurs[2], compteurs[3]), new Taux(compteurs[4], compteurs[5]),
                    new Taux(compteurs[6], compteurs[7]), ordonner(actions), quotidien, personnes);
        });
    }

    // ------------------------------------------------------------------ Outils

    private Map<UUID, Long> compter(String sql, OffsetDateTime depuis) {
        Map<UUID, Long> r = new HashMap<>();
        jdbc.query(sql, (ResultSet rs) -> {
            UUID id = rs.getObject(1, UUID.class);
            if (id != null) {
                r.put(id, rs.getLong(2));
            }
        }, depuis);
        return r;
    }

    private static List<String> roles(Array tableau) throws SQLException {
        return tableau == null ? List.of() : Arrays.asList((String[]) tableau.getArray());
    }

    /** Actions dans l'ordre du catalogue, sans les indicateurs inconnus. */
    private static Map<String, Long> ordonner(Map<String, Long> actions) {
        Map<String, Long> ordonnees = new LinkedHashMap<>();
        for (Indicateur i : Indicateur.values()) {
            Long v = actions.get(i.name());
            if (v != null && v > 0) {
                ordonnees.put(i.name(), v);
            }
        }
        return ordonnees;
    }

    private static List<IndicateurVue> catalogue() {
        Map<Indicateur, String> m = new EnumMap<>(Indicateur.class);
        for (Indicateur i : Indicateur.values()) {
            m.put(i, i.libelle());
        }
        return m.entrySet().stream().map(e -> new IndicateurVue(e.getKey().name(), e.getValue())).toList();
    }

    static int borner(int jours) {
        return Math.max(1, Math.min(jours, 366));
    }
}
