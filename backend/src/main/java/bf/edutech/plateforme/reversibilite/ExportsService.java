package bf.edutech.plateforme.reversibilite;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.tenant.TenantContext;
import bf.edutech.plateforme.utilisateurs.AuthService;

/**
 * Export complet des données d'un établissement (réversibilité).
 * <ul>
 * <li>La demande exige le mot de passe de la personne connectée ; elle est tracée dans le
 * journal d'audit de l'établissement, qui voit aussi les exports demandés par la plateforme.</li>
 * <li>L'archive est préparée en tâche de fond (un export à la fois sur le serveur), dans une
 * seule transaction en lecture seule « repeatable read » : une photographie cohérente.</li>
 * <li>Elle reste téléchargeable {@code app.exports.duree-conservation} (7 jours), par un lien
 * signé valable quelques minutes, puis elle est effacée du serveur.</li>
 * </ul>
 * Les méthodes publiques s'exécutent pour l'établissement actif ({@link TenantContext}).
 */
@Service
public class ExportsService {

    private static final Logger LOG = LoggerFactory.getLogger(ExportsService.class);
    /** Archives prêtes gardées par établissement : les plus anciennes sont effacées. */
    static final int ARCHIVES_GARDEES = 3;

    public record ExportVue(UUID id, StatutExport statut, Instant demandeLe, String demandePar, boolean parPlateforme,
            Instant termineLe, Instant expireLe, Long taille, String empreinte, Integer nombreTables,
            Long nombreLignes, String erreur, int telechargements, Instant dernierTelechargement) {
    }

    /** Lien de téléchargement : chemin relatif à la racine de l'API (/api/v1). */
    public record Lien(String chemin, Instant expireLe) {
    }

    /** Fichier prêt à envoyer. */
    public record Fichier(Path chemin, String nom, long taille) {
    }

    private final ExportDonneesRepository depot;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate ecriture;
    private final TransactionTemplate photographie;
    private final AuthService auth;
    private final AuditService audit;
    private final LiensTelechargement liens;
    private final ExportsProperties proprietes;
    private final Clock horloge;
    private final String versionApplication;
    private final ExecutorService executeur = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "export-donnees");
        t.setDaemon(true);
        return t;
    });

    ExportsService(ExportDonneesRepository depot, JdbcTemplate jdbc, PlatformTransactionManager gestionnaire,
            AuthService auth, AuditService audit, LiensTelechargement liens, ExportsProperties proprietes,
            Clock horloge, @Value("${info.application.version:inconnue}") String versionApplication) {
        this.depot = depot;
        this.jdbc = jdbc;
        this.ecriture = new TransactionTemplate(gestionnaire);
        this.photographie = new TransactionTemplate(gestionnaire);
        this.photographie.setReadOnly(true);
        this.photographie.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.auth = auth;
        this.audit = audit;
        this.liens = liens;
        this.proprietes = proprietes;
        this.horloge = horloge;
        this.versionApplication = versionApplication;
    }

    @PreDestroy
    void arreter() {
        executeur.shutdownNow();
    }

    /** Super administrateur : exécute une action pour un établissement existant. */
    public <T> T pour(UUID etablissementId, java.util.function.Supplier<T> action) {
        Integer n = jdbc.queryForObject("select count(*) from tenant where id = ?", Integer.class, etablissementId);
        if (n == null || n == 0) {
            throw new RessourceIntrouvableException("Établissement introuvable");
        }
        return TenantContext.executerPour(etablissementId, action);
    }

    // ------------------------------------------------------------------ Consultation

    public List<ExportVue> lister() {
        exigerEtablissement();
        return ecriture.execute(s -> {
            List<ExportDonnees> exports = depot.findAllByOrderByDemandeLeDesc();
            Map<UUID, String> noms = noms(exports.stream().map(ExportDonnees::getDemandePar)
                    .filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
            Instant maintenant = horloge.instant();
            return exports.stream().map(e -> vue(e, noms, maintenant)).toList();
        });
    }

    // ------------------------------------------------------------------ Demande

    /**
     * Demande un nouvel export après avoir vérifié le mot de passe de la personne connectée.
     * Refusé si un export est déjà en préparation ou si la limite quotidienne est atteinte.
     */
    public ExportVue demander(UUID utilisateurId, String motDePasse, boolean parPlateforme) {
        UUID tenantId = exigerEtablissement();
        auth.confirmerMotDePasse(utilisateurId, motDePasse);
        Instant maintenant = horloge.instant();
        ExportDonnees cree;
        try {
            cree = ecriture.execute(s -> {
                // Exports restés « en cours » après un redémarrage : marqués en échec
                depot.findAllByStatut(StatutExport.EN_COURS).stream()
                        .filter(e -> e.etat(maintenant, proprietes.dureeMax()) == StatutExport.INTERROMPU)
                        .forEach(e -> e.echouer(maintenant, "Préparation interrompue (redémarrage du serveur)"));
                depot.flush();
                if (!depot.findAllByStatut(StatutExport.EN_COURS).isEmpty()) {
                    throw new RegleMetierException("EXPORT_EN_COURS",
                            "Un export est déjà en préparation. Attendez qu'il soit prêt.");
                }
                if (depot.countByDemandeLeAfter(maintenant.minus(Duration.ofDays(1))) >= proprietes.maxParJour()) {
                    throw new RegleMetierException("EXPORTS_TROP_NOMBREUX",
                            "Limite atteinte : " + proprietes.maxParJour() + " exports par 24 heures.");
                }
                ExportDonnees e = depot.saveAndFlush(new ExportDonnees(utilisateurId, parPlateforme, maintenant));
                audit.enregistrer("EXPORT_COMPLET_DEMANDE", e.getId().toString(),
                        Map.of("parPlateforme", parPlateforme));
                return e;
            });
        } catch (DataIntegrityViolationException ex) {
            throw new RegleMetierException("EXPORT_EN_COURS",
                    "Un export est déjà en préparation. Attendez qu'il soit prêt.");
        }
        UUID id = cree.getId();
        executeur.submit(() -> TenantContext.executerPour(tenantId, () -> {
            preparer(tenantId, id);
            return null;
        }));
        return vue(cree, noms(cree.getDemandePar() == null ? Set.of() : Set.of(cree.getDemandePar())), maintenant);
    }

    /** Préparation de l'archive (tâche de fond, établissement fixé par l'appelant). */
    void preparer(UUID tenantId, UUID id) {
        Path dossier = proprietes.repertoire().resolve(tenantId.toString());
        Path partiel = dossier.resolve(id + ".zip.partiel");
        Path fin = dossier.resolve(id + ".zip");
        try {
            Files.createDirectories(dossier);
            MessageDigest empreinte = ArchiveEtablissement.sha256();
            ArchiveEtablissement.Resultat r;
            try (OutputStream fichier = Files.newOutputStream(partiel);
                    DigestOutputStream sortie = new DigestOutputStream(new java.io.BufferedOutputStream(fichier, 256 * 1024),
                            empreinte)) {
                ArchiveEtablissement archive = new ArchiveEtablissement(jdbc, versionApplication, horloge.instant());
                r = photographie.execute(s -> {
                    try {
                        return archive.ecrire(tenantId, sortie);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            Files.move(partiel, fin, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            long taille = Files.size(fin);
            String hex = java.util.HexFormat.of().formatHex(empreinte.digest());
            Instant maintenant = horloge.instant();
            ecriture.executeWithoutResult(s -> {
                depot.findById(id).ifPresent(e -> e.terminer(maintenant, maintenant.plus(proprietes.dureeConservation()),
                        taille, hex, r.tables(), r.lignes()));
                // Les archives plus anciennes sont effacées : seules les plus récentes restent
                depot.findAllByOrderByDemandeLeDesc().stream()
                        .filter(e -> e.getStatut() == StatutExport.PRET && !e.getId().equals(id))
                        .skip(ARCHIVES_GARDEES - 1L)
                        .forEach(e -> {
                            e.expirer(maintenant);
                            effacer(tenantId, e.getId());
                        });
            });
            LOG.info("Export complet {} prêt : {} tables, {} lignes, {} octets", id, r.tables(), r.lignes(), taille);
        } catch (RuntimeException | IOException ex) {
            LOG.error("Échec de l'export complet {}", id, ex);
            try {
                Files.deleteIfExists(partiel);
            } catch (IOException ignore) {
                // effacé par la purge
            }
            Instant maintenant = horloge.instant();
            ecriture.executeWithoutResult(s -> depot.findById(id)
                    .ifPresent(e -> e.echouer(maintenant, "La préparation a échoué. Réessayez ou contactez le support.")));
        }
    }

    // ------------------------------------------------------------------ Téléchargement

    /** Lien de téléchargement signé (quelques minutes) d'une archive prête. */
    public Lien lien(UUID utilisateurId, UUID exportId) {
        UUID tenantId = exigerEtablissement();
        ExportDonnees e = ecriture.execute(s -> depot.findById(exportId)
                .orElseThrow(() -> new RessourceIntrouvableException("Export introuvable")));
        exigerTelechargeable(tenantId, e);
        Instant expiration = horloge.instant().plus(proprietes.dureeLien());
        String jeton = liens.creer(tenantId, exportId, utilisateurId, expiration);
        return new Lien("/telechargements/exports/" + exportId + "?jeton=" + jeton, expiration);
    }

    /** Archive désignée par un lien signé ; compte le téléchargement (sauf reprise). */
    public Fichier ouvrir(UUID exportId, String jeton, boolean reprise) {
        LiensTelechargement.Lien l = liens.verifier(jeton, exportId);
        return TenantContext.executerPour(l.tenantId(), () -> {
            String code = jdbc.queryForObject("select code from tenant where id = ?", String.class, l.tenantId());
            ExportDonnees e = ecriture.execute(s -> depot.findById(exportId)
                    .orElseThrow(() -> new RessourceIntrouvableException("Export introuvable")));
            Path chemin = exigerTelechargeable(l.tenantId(), e);
            if (!reprise) {
                Instant maintenant = horloge.instant();
                ecriture.executeWithoutResult(s -> depot.findById(exportId).ifPresent(x -> x.compterTelechargement(maintenant)));
                audit.enregistrerPour(l.utilisateurId(), "EXPORT_COMPLET_TELECHARGE", exportId.toString(), null);
            }
            String nom = "export-" + code + "-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                    .withZone(java.time.ZoneId.of("Africa/Ouagadougou")).format(e.getDemandeLe()) + ".zip";
            try {
                return new Fichier(chemin, nom, Files.size(chemin));
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    private Path exigerTelechargeable(UUID tenantId, ExportDonnees e) {
        StatutExport etat = e.etat(horloge.instant(), proprietes.dureeMax());
        if (etat == StatutExport.EXPIRE) {
            throw new RegleMetierException("EXPORT_EXPIRE",
                    "Cette archive a été effacée du serveur. Demandez un nouvel export.");
        }
        if (etat != StatutExport.PRET) {
            throw new RegleMetierException("EXPORT_PAS_PRET", "Cette archive n'est pas prête.");
        }
        Path chemin = proprietes.repertoire().resolve(tenantId.toString()).resolve(e.getId() + ".zip");
        if (!Files.isRegularFile(chemin)) {
            throw new RegleMetierException("EXPORT_EXPIRE",
                    "Cette archive n'est plus sur le serveur. Demandez un nouvel export.");
        }
        return chemin;
    }

    // ------------------------------------------------------------------ Purge

    /** Chaque heure : efface les archives dont la durée de conservation est dépassée. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void purger() {
        if (!proprietes.purgeAutomatique()) {
            return;
        }
        purger(horloge.instant());
    }

    /** Efface les archives (et préparations abandonnées) plus anciennes que leur durée de vie. */
    int purger(Instant maintenant) {
        Path racine = proprietes.repertoire();
        if (!Files.isDirectory(racine)) {
            return 0;
        }
        int effaces = 0;
        try (DirectoryStream<Path> etablissements = Files.newDirectoryStream(racine, Files::isDirectory)) {
            for (Path dossier : etablissements) {
                try (DirectoryStream<Path> fichiers = Files.newDirectoryStream(dossier)) {
                    for (Path f : fichiers) {
                        Instant modifie = Files.getLastModifiedTime(f).toInstant();
                        String nom = f.getFileName().toString();
                        boolean perime = nom.endsWith(".zip")
                                ? modifie.plus(proprietes.dureeConservation()).isBefore(maintenant)
                                : nom.endsWith(".partiel") && modifie.plus(proprietes.dureeMax()).isBefore(maintenant);
                        if (perime && Files.deleteIfExists(f)) {
                            effaces++;
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Purge des exports incomplète : {}", e.getMessage());
        }
        if (effaces > 0) {
            LOG.info("{} archive(s) d'export effacée(s)", effaces);
        }
        return effaces;
    }

    private void effacer(UUID tenantId, UUID exportId) {
        try {
            Files.deleteIfExists(proprietes.repertoire().resolve(tenantId.toString()).resolve(exportId + ".zip"));
        } catch (IOException e) {
            LOG.warn("Archive {} non effacée : {}", exportId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Outils

    private static UUID exigerEtablissement() {
        return TenantContext.courant()
                .orElseThrow(() -> new IllegalStateException("Aucun établissement actif"));
    }

    private Map<UUID, String> noms(Set<UUID> ids) {
        Map<UUID, String> noms = new HashMap<>();
        if (ids.isEmpty()) {
            return noms;
        }
        String tableau = ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        jdbc.query("select id, nom, prenoms from utilisateur where id = any (?::uuid[])",
                (java.sql.ResultSet rs) -> {
                    noms.put(rs.getObject(1, UUID.class), rs.getString(2) + " " + rs.getString(3));
                }, tableau);
        return noms;
    }

    private ExportVue vue(ExportDonnees e, Map<UUID, String> noms, Instant maintenant) {
        StatutExport etat = e.etat(maintenant, proprietes.dureeMax());
        String par = e.isParPlateforme() ? "Plateforme (super administrateur)" : noms.get(e.getDemandePar());
        return new ExportVue(e.getId(), etat, e.getDemandeLe(), par, e.isParPlateforme(), e.getTermineLe(),
                e.getExpireLe(), e.getTaille(), e.getEmpreinte(), e.getNombreTables(), e.getNombreLignes(),
                etat == StatutExport.INTERROMPU ? "Préparation interrompue (redémarrage du serveur)" : e.getErreur(),
                e.getTelechargements(), e.getDernierTelechargement());
    }
}
