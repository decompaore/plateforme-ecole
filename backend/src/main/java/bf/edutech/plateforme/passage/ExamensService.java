package bf.edutech.plateforme.passage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.export.Tableau;
import bf.edutech.plateforme.socle.export.Tableau.Colonne;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Taux de réussite aux examens nationaux (v0.46), à titre indicatif pour la direction.
 * <ul>
 * <li>Années suivies dans la plateforme : calcul à partir des classes d'examen et des résultats saisis élève par
 * élève au conseil de fin d'année (taux = admis / résultats connus).</li>
 * <li>Années antérieures : chiffres globaux saisis à la main (candidats présents, admis, filles), pour comparer
 * sur plusieurs années.</li>
 * </ul>
 */
@Service
public class ExamensService {

    public enum Source {
        CALCULE, SAISI
    }

    public record ClasseExamenVue(UUID classeId, String classeCode, int inscrits, int resultatsConnus, int admis,
            BigDecimal taux) {
    }

    /**
     * Résultats d'un examen une année. {@code inscrits} : élèves des classes d'examen (calculé) ou candidats
     * (saisi) ; {@code resultatsConnus} : candidats dont le résultat est connu ; filles : null si non saisi.
     */
    public record ResultatExamenVue(String annee, String examen, Source source, UUID historiqueId, int inscrits,
            int resultatsConnus, int admis, Integer resultatsFilles, Integer admisFilles, BigDecimal taux,
            BigDecimal tauxFilles, BigDecimal tauxGarcons, List<ClasseExamenVue> classes) {
    }

    public record TableauExamensVue(List<String> examens, List<String> annees, List<ResultatExamenVue> resultats) {
    }

    public record SaisieHistorique(String annee, String examen, Integer candidats, Integer admis,
            Integer candidatesFilles, Integer admisesFilles) {
    }

    private static final Pattern ANNEE = Pattern.compile("^(\\d{4})-(\\d{4})$");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    ExamensService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Tous les examens de l'établissement, toutes années (les plus récentes d'abord). */
    @Transactional(readOnly = true)
    public TableauExamensVue tableau() {
        UtilisateurConnecte.etablissementActif();
        record Ligne(String annee, String examen, UUID classeId, String classe, int inscrits, int connus, int connusF,
                int admis, int admisF) {
        }
        List<Ligne> lignes = jdbc.query("""
                select a.libelle, upper(regexp_replace(btrim(x.examen), '\\s+', ' ', 'g')), c.id, c.code,
                       count(i.id)::int,
                       count(d.resultat_examen)::int,
                       count(d.resultat_examen) filter (where e.sexe = 'F')::int,
                       count(*) filter (where d.resultat_examen = 'ADMIS')::int,
                       count(*) filter (where d.resultat_examen = 'ADMIS' and e.sexe = 'F')::int
                  from classe_examen x
                  join classe c on c.tenant_id = x.tenant_id and c.id = x.classe_id
                  join annee_scolaire a on a.tenant_id = c.tenant_id and a.id = c.annee_id
                  join inscription i on i.tenant_id = c.tenant_id and i.classe_id = c.id and i.statut = 'ACTIVE'
                  join eleve e on e.tenant_id = i.tenant_id and e.id = i.eleve_id
                  left join decision_fin_annee d on d.tenant_id = i.tenant_id and d.inscription_id = i.id
                 group by a.libelle, 2, c.id, c.code""",
                (r, n) -> new Ligne(r.getString(1), r.getString(2), r.getObject(3, UUID.class), r.getString(4),
                        r.getInt(5), r.getInt(6), r.getInt(7), r.getInt(8), r.getInt(9)));

        Map<String, List<Ligne>> parAnneeExamen = new LinkedHashMap<>();
        lignes.forEach(l -> parAnneeExamen.computeIfAbsent(l.annee() + "|" + l.examen(), k -> new ArrayList<>()).add(l));
        List<ResultatExamenVue> resultats = new ArrayList<>();
        for (List<Ligne> groupe : parAnneeExamen.values()) {
            int inscrits = 0;
            int connus = 0;
            int connusF = 0;
            int admis = 0;
            int admisF = 0;
            List<ClasseExamenVue> classes = new ArrayList<>();
            for (Ligne l : groupe) {
                inscrits += l.inscrits();
                connus += l.connus();
                connusF += l.connusF();
                admis += l.admis();
                admisF += l.admisF();
                classes.add(new ClasseExamenVue(l.classeId(), l.classe(), l.inscrits(), l.connus(), l.admis(),
                        taux(l.admis(), l.connus())));
            }
            classes.sort(Comparator.comparing(ClasseExamenVue::classeCode));
            Ligne l0 = groupe.get(0);
            resultats.add(new ResultatExamenVue(l0.annee(), l0.examen(), Source.CALCULE, null, inscrits, connus, admis,
                    connusF, admisF, taux(admis, connus), taux(admisF, connusF), taux(admis - admisF, connus - connusF),
                    classes));
        }
        jdbc.query("""
                select id, annee, examen, candidats, admis, candidates_filles, admises_filles
                  from examen_historique""", r -> {
            Integer cf = (Integer) r.getObject(6);
            Integer af = (Integer) r.getObject(7);
            int candidats = r.getInt(4);
            int admis = r.getInt(5);
            resultats.add(new ResultatExamenVue(r.getString(2), r.getString(3), Source.SAISI, r.getObject(1, UUID.class),
                    candidats, candidats, admis, cf, af, taux(admis, candidats),
                    cf == null || af == null ? null : taux(af, cf),
                    cf == null || af == null ? null : taux(admis - af, candidats - cf), List.of()));
        });
        resultats.sort(Comparator.comparing(ResultatExamenVue::annee).reversed().thenComparing(ResultatExamenVue::examen));
        List<String> examens = resultats.stream().map(ResultatExamenVue::examen).distinct().sorted().toList();
        List<String> annees = resultats.stream().map(ResultatExamenVue::annee).distinct()
                .sorted(Comparator.reverseOrder()).toList();
        return new TableauExamensVue(examens, annees, resultats);
    }

    /** Saisie des résultats d'une année antérieure (chef d'établissement). */
    @Transactional
    public TableauExamensVue ajouter(SaisieHistorique s) {
        UtilisateurConnecte.etablissementActif();
        Valide v = valider(s);
        verifierDoublon(v, null);
        jdbc.update("""
                insert into examen_historique (tenant_id, id, annee, examen, candidats, admis, candidates_filles, admises_filles)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), v.annee, v.examen, v.candidats, v.admis, v.candidatesFilles, v.admisesFilles);
        audit.enregistrer("EXAMEN_HISTORIQUE_SAISI", v.annee + " / " + v.examen, Map.of("candidats", v.candidats, "admis", v.admis));
        return tableau();
    }

    @Transactional
    public TableauExamensVue modifier(UUID id, SaisieHistorique s) {
        UtilisateurConnecte.etablissementActif();
        existe(id);
        Valide v = valider(s);
        verifierDoublon(v, id);
        jdbc.update("""
                update examen_historique set annee = ?, examen = ?, candidats = ?, admis = ?, candidates_filles = ?,
                       admises_filles = ?, modifie_le = now() where id = ?""",
                v.annee, v.examen, v.candidats, v.admis, v.candidatesFilles, v.admisesFilles, id);
        audit.enregistrer("EXAMEN_HISTORIQUE_MODIFIE", v.annee + " / " + v.examen, null);
        return tableau();
    }

    @Transactional
    public TableauExamensVue supprimer(UUID id) {
        UtilisateurConnecte.etablissementActif();
        existe(id);
        jdbc.update("delete from examen_historique where id = ?", id);
        audit.enregistrer("EXAMEN_HISTORIQUE_SUPPRIME", id.toString(), null);
        return tableau();
    }

    /** Export : une ligne par année et par examen, puis le détail par classe des années calculées. */
    @Transactional(readOnly = true)
    public Tableau[] exporter() {
        TableauExamensVue t = tableau();
        Tableau synthese = new Tableau("Examens", "Résultats aux examens nationaux")
                .sousTitre("Taux = admis / candidats dont le résultat est connu")
                .colonnes(Colonne.texte("Année", 1), Colonne.texte("Examen", 1.4f), Colonne.nombre("Candidats", 1),
                        Colonne.nombre("Admis", 1), Colonne.texte("Taux", 0.9f), Colonne.texte("Filles", 0.9f),
                        Colonne.texte("Garçons", 0.9f), Colonne.texte("Source", 1.2f));
        for (ResultatExamenVue r : t.resultats()) {
            synthese.ligne(r.annee(), r.examen(), r.resultatsConnus(), r.admis(), pourcent(r.taux()),
                    pourcent(r.tauxFilles()), pourcent(r.tauxGarcons()),
                    r.source() == Source.CALCULE ? "Plateforme" : "Saisie de l'établissement");
        }
        Tableau classes = new Tableau("Par classe", "Résultats par classe d'examen")
                .colonnes(Colonne.texte("Année", 1), Colonne.texte("Examen", 1.4f), Colonne.texte("Classe", 1.2f),
                        Colonne.nombre("Inscrits", 1), Colonne.nombre("Résultats connus", 1.2f), Colonne.nombre("Admis", 1),
                        Colonne.texte("Taux", 0.9f));
        for (ResultatExamenVue r : t.resultats()) {
            for (ClasseExamenVue c : r.classes()) {
                classes.ligne(r.annee(), r.examen(), c.classeCode(), c.inscrits(), c.resultatsConnus(), c.admis(),
                        pourcent(c.taux()));
            }
        }
        return new Tableau[] { synthese, classes };
    }

    // ------------------------------------------------------------------

    private record Valide(String annee, String examen, int candidats, int admis, Integer candidatesFilles,
            Integer admisesFilles) {
    }

    private static Valide valider(SaisieHistorique s) {
        if (s == null) {
            throw new IllegalArgumentException("Indiquez l'année, l'examen, les candidats et les admis");
        }
        String annee = s.annee() == null ? "" : s.annee().replace(" ", "");
        var m = ANNEE.matcher(annee);
        if (!m.matches() || Integer.parseInt(m.group(2)) != Integer.parseInt(m.group(1)) + 1) {
            throw new IllegalArgumentException("Année scolaire au format 2023-2024");
        }
        String examen = normaliser(s.examen());
        if (examen.isEmpty() || examen.length() > 40) {
            throw new IllegalArgumentException("Nom de l'examen (BEPC, BAC F3, CAP…) : 40 caractères au plus");
        }
        if (s.candidats() == null || s.admis() == null || s.candidats() < 0 || s.admis() < 0) {
            throw new IllegalArgumentException("Nombre de candidats et d'admis requis");
        }
        if (s.admis() > s.candidats()) {
            throw new IllegalArgumentException("Plus d'admis que de candidats");
        }
        if (s.admisesFilles() != null && s.candidatesFilles() == null) {
            throw new IllegalArgumentException("Indiquez aussi le nombre de candidates");
        }
        if (s.candidatesFilles() != null && (s.candidatesFilles() < 0 || s.candidatesFilles() > s.candidats())) {
            throw new IllegalArgumentException("Candidates : entre 0 et le nombre de candidats");
        }
        if (s.admisesFilles() != null && (s.admisesFilles() < 0 || s.admisesFilles() > s.candidatesFilles()
                || s.admisesFilles() > s.admis())) {
            throw new IllegalArgumentException("Admises : pas plus que les candidates ni que les admis");
        }
        return new Valide(annee, examen, s.candidats(), s.admis(), s.candidatesFilles(), s.admisesFilles());
    }

    /** Une année suivie dans la plateforme se calcule : pas de saisie à la main pour le même examen. */
    private void verifierDoublon(Valide v, UUID sauf) {
        Integer calcules = jdbc.queryForObject("""
                select count(*) from classe_examen x
                  join classe c on c.tenant_id = x.tenant_id and c.id = x.classe_id
                  join annee_scolaire a on a.tenant_id = c.tenant_id and a.id = c.annee_id
                 where a.libelle = ? and upper(regexp_replace(btrim(x.examen), '\\s+', ' ', 'g')) = ?""",
                Integer.class, v.annee, v.examen);
        if (calcules != null && calcules > 0) {
            throw new RegleMetierException("EXAMEN_SUIVI_DANS_LA_PLATEFORME", "Le " + v.examen + " " + v.annee
                    + " se calcule à partir des résultats saisis au conseil de fin d'année");
        }
        Optional<UUID> existant = jdbc.queryForList("select id from examen_historique where annee = ? and upper(examen) = ?",
                UUID.class, v.annee, v.examen).stream().filter(id -> !id.equals(sauf)).findFirst();
        if (existant.isPresent()) {
            throw new RegleMetierException("EXAMEN_DEJA_SAISI", "Les résultats du " + v.examen + " " + v.annee
                    + " sont déjà saisis : modifiez-les");
        }
    }

    private void existe(UUID id) {
        Integer n = jdbc.queryForObject("select count(*) from examen_historique where id = ?", Integer.class, id);
        if (n == null || n == 0) {
            throw new RessourceIntrouvableException("Résultats introuvables");
        }
    }

    static String normaliser(String examen) {
        return examen == null ? "" : examen.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    static BigDecimal taux(int admis, int candidats) {
        return candidats <= 0 ? null
                : BigDecimal.valueOf(admis * 100L).divide(BigDecimal.valueOf(candidats), 1, RoundingMode.HALF_UP);
    }

    private static String pourcent(BigDecimal t) {
        return t == null ? "" : t.toPlainString().replace('.', ',') + " %";
    }
}
