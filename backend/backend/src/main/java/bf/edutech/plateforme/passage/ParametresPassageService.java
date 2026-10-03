package bf.edutech.plateforme.passage;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.etablissement.ClassesService;
import bf.edutech.plateforme.pedagogie.ProfilsService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Réglages du passage : seuil d'exclusion et nombre maximal de redoublements (chacun peut être
 * désactivé), poids des périodes dans la moyenne annuelle (1 par défaut), classes d'examen.
 */
@Service
public class ParametresPassageService {

    /** {@code seuilExclusion} ou {@code redoublementsMax} null : règle désactivée. */
    public record ParametresPassage(BigDecimal seuilExclusion, Integer redoublementsMax) {
    }

    public record PoidsPeriode(int ordre, BigDecimal poids) {
    }

    private static final ParametresPassage DEFAUT = new ParametresPassage(new BigDecimal("8.50"), 2);

    private final JdbcTemplate jdbc;
    private final ClassesService classes;
    private final ProfilsService profils;
    private final AuditService audit;

    ParametresPassageService(JdbcTemplate jdbc, ClassesService classes, ProfilsService profils, AuditService audit) {
        this.jdbc = jdbc;
        this.classes = classes;
        this.profils = profils;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ParametresPassage lire() {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("""
                select seuil_exclusion, redoublements_max from parametres_passage where tenant_id = tenant_courant()""",
                (l, n) -> new ParametresPassage(l.getBigDecimal(1), l.getObject(2, Integer.class)))
                .stream().findFirst().orElse(DEFAUT);
    }

    @Transactional
    public ParametresPassage modifier(ParametresPassage p) {
        UtilisateurConnecte.etablissementActif();
        if (p.seuilExclusion() != null && (p.seuilExclusion().signum() < 0
                || p.seuilExclusion().compareTo(BigDecimal.valueOf(20)) > 0 || p.seuilExclusion().scale() > 2)) {
            throw new IllegalArgumentException("Le seuil d'exclusion est compris entre 0 et 20");
        }
        if (p.redoublementsMax() != null && (p.redoublementsMax() < 1 || p.redoublementsMax() > 5)) {
            throw new IllegalArgumentException("Le nombre maximal de redoublements est compris entre 1 et 5");
        }
        jdbc.update("""
                insert into parametres_passage (tenant_id, seuil_exclusion, redoublements_max)
                values (tenant_courant(), ?, ?)
                on conflict (tenant_id) do update set seuil_exclusion = excluded.seuil_exclusion,
                       redoublements_max = excluded.redoublements_max""",
                p.seuilExclusion(), p.redoublementsMax());
        audit.enregistrer("PARAMETRES_PASSAGE_MODIFIES", null, null);
        return lire();
    }

    /** Poids des périodes d'un profil, par ordre (les périodes absentes pèsent 1). */
    @Transactional(readOnly = true)
    public List<PoidsPeriode> poids(UUID profilId) {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("select ordre, poids from poids_periode where profil_id = ? order by ordre",
                (l, n) -> new PoidsPeriode(l.getInt(1), l.getBigDecimal(2)), profilId);
    }

    /** Remplace les poids d'un profil (ex. 1, 2, 2 pour des trimestres). */
    @Transactional
    public List<PoidsPeriode> definirPoids(UUID profilId, List<PoidsPeriode> poids) {
        UtilisateurConnecte.etablissementActif();
        profils.trouver(profilId);
        if (poids == null || poids.size() > 12) {
            throw new IllegalArgumentException("Douze périodes au plus");
        }
        for (PoidsPeriode p : poids) {
            if (p == null || p.ordre() < 1 || p.poids() == null || p.poids().signum() <= 0
                    || p.poids().compareTo(BigDecimal.TEN) > 0 || p.poids().scale() > 2) {
                throw new IllegalArgumentException("Chaque poids est compris entre 0,01 et 10, pour une période donnée");
            }
        }
        if (poids.stream().map(PoidsPeriode::ordre).distinct().count() != poids.size()) {
            throw new IllegalArgumentException("Un seul poids par période");
        }
        jdbc.update("delete from poids_periode where profil_id = ?", profilId);
        for (PoidsPeriode p : poids) {
            jdbc.update("insert into poids_periode (tenant_id, profil_id, ordre, poids) values (tenant_courant(), ?, ?, ?)",
                    profilId, p.ordre(), p.poids());
        }
        audit.enregistrer("POIDS_PERIODES_DEFINIS", profilId.toString(), null);
        return poids(profilId);
    }

    @Transactional(readOnly = true)
    public Optional<String> examen(UUID classeId) {
        UtilisateurConnecte.etablissementActif();
        return jdbc.queryForList("select examen from classe_examen where classe_id = ?", String.class, classeId)
                .stream().findFirst();
    }

    /** La classe prépare un examen national (BEPC, BAC, CAP, CQP…) ; {@code examen} vide : ce n'est plus le cas. */
    @Transactional
    public Optional<String> definirExamen(UUID classeId, String examen) {
        UtilisateurConnecte.etablissementActif();
        String code = classes.trouver(classeId).code();
        if (examen == null || examen.isBlank()) {
            jdbc.update("delete from classe_examen where classe_id = ?", classeId);
            audit.enregistrer("CLASSE_EXAMEN_RETIREE", code, null);
            return Optional.empty();
        }
        String propre = examen.trim();
        if (propre.length() > 40) {
            throw new IllegalArgumentException("Nom de l'examen : 40 caractères au plus");
        }
        jdbc.update("""
                insert into classe_examen (tenant_id, classe_id, examen) values (tenant_courant(), ?, ?)
                on conflict (tenant_id, classe_id) do update set examen = excluded.examen""", classeId, propre);
        audit.enregistrer("CLASSE_EXAMEN_DEFINIE", code, Map.of("examen", propre));
        return Optional.of(propre);
    }

    /** Examens des classes données (classe → examen). */
    Map<UUID, String> examens(Collection<UUID> classeIds) {
        Map<UUID, String> resultat = new HashMap<>();
        if (!classeIds.isEmpty()) {
            String marques = String.join(", ", java.util.Collections.nCopies(classeIds.size(), "?"));
            jdbc.query("select classe_id, examen from classe_examen where classe_id in (" + marques + ")",
                    (l, n) -> Map.entry(l.getObject(1, UUID.class), l.getString(2)), classeIds.toArray())
                    .forEach(e -> resultat.put(e.getKey(), e.getValue()));
        }
        return resultat;
    }
}
