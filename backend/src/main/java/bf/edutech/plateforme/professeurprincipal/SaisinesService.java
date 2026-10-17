package bf.edutech.plateforme.professeurprincipal;

import static bf.edutech.plateforme.professeurprincipal.ProfesseursPrincipauxService.instant;
import static bf.edutech.plateforme.professeurprincipal.ProfesseursPrincipauxService.texte;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.professeurprincipal.ProfesseursPrincipauxService.Classe;
import bf.edutech.plateforme.professeurprincipal.Vues.DonneesSaisine;
import bf.edutech.plateforme.professeurprincipal.Vues.Droits;
import bf.edutech.plateforme.professeurprincipal.Vues.EleveConcerne;
import bf.edutech.plateforme.professeurprincipal.Vues.Nature;
import bf.edutech.plateforme.professeurprincipal.Vues.SaisineVue;
import bf.edutech.plateforme.professeurprincipal.Vues.StatutSaisine;
import bf.edutech.plateforme.professeurprincipal.Vues.SuiteVue;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.Role;

/**
 * Dossiers suivis par le professeur principal (v0.49.1).
 * <ul>
 * <li><b>Revendication</b> : un enseignant de la classe (ou le professeur principal lui-même) expose une préoccupation
 * des enseignants ; le professeur principal la porte à l'administration (chef d'établissement, censeur), qui répond
 * dans le dossier.</li>
 * <li><b>Médiation</b> : un élève ou un groupe d'élèves vient voir le professeur principal au sujet d'un enseignant ;
 * il ouvre le dossier, note ses démarches, informe l'enseignant (qui peut alors donner sa version) et saisit si besoin
 * la direction et le CPE.</li>
 * </ul>
 * Cloisonnement : le dossier est vu par le professeur principal de la classe, son auteur, l'enseignant concerné une fois
 * informé, et la direction seulement après la transmission. Numérotation PP-AAAA-NNNN.
 */
@Service
public class SaisinesService {

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");

    private final JdbcTemplate jdbc;
    private final ProfesseursPrincipauxService pp;
    private final Compteurs compteurs;
    private final AuditService audit;
    private final Clock horloge;

    SaisinesService(JdbcTemplate jdbc, ProfesseursPrincipauxService pp, Compteurs compteurs, AuditService audit,
            Clock horloge) {
        this.jdbc = jdbc;
        this.pp = pp;
        this.compteurs = compteurs;
        this.audit = audit;
        this.horloge = horloge;
    }

    // ================================================================== ouverture

    @Transactional
    public SaisineVue ouvrir(UUID classeId, DonneesSaisine d) {
        UtilisateurConnecte.etablissementActif();
        if (d == null || d.nature() == null) {
            throw new IllegalArgumentException("Nature du dossier obligatoire");
        }
        Classe c = pp.classe(classeId);
        UUID professeur = pp.professeurDe(classeId).orElseThrow(() -> new RegleMetierException(
                "PROFESSEUR_PRINCIPAL_ABSENT", "La " + c.code() + " n'a pas encore de professeur principal"));
        UUID moi = UtilisateurConnecte.id();
        boolean estPp = moi.equals(professeur);
        String objet = texte(d.objet(), 200, "Objet");
        String expose = texte(d.expose(), 4000, "Exposé");
        List<UUID> eleves = d.inscriptions() == null ? List.of() : List.copyOf(new LinkedHashSet<>(d.inscriptions()));
        UUID matiere = d.matiereId();
        UUID enseignant = d.enseignantId();
        if (d.nature() == Nature.REVENDICATION) {
            if (!estPp && !pp.enseigneDans(moi, classeId)) {
                throw new AccesRefuseException("Seuls les enseignants de la " + c.code()
                        + " confient une revendication à son professeur principal");
            }
            if (!eleves.isEmpty() || enseignant != null) {
                throw new IllegalArgumentException("Une revendication ne vise ni élève ni enseignant");
            }
        } else {
            if (!estPp) {
                throw new AccesRefuseException("Le professeur principal ouvre les dossiers de médiation de sa classe");
            }
            if (eleves.isEmpty()) {
                throw new IllegalArgumentException("Indiquez l'élève ou les élèves concernés");
            }
            Integer dansClasse = jdbc.queryForObject(
                    "select count(*) from inscription where classe_id = ? and id = any (?::uuid[])", Integer.class,
                    classeId, tableau(eleves));
            if (dansClasse == null || dansClasse != eleves.size()) {
                throw new IllegalArgumentException("Élève hors de la " + c.code());
            }
            if (matiere != null) {
                List<UUID> titulaire = jdbc.queryForList("""
                        select s.utilisateur_id from classe_matiere cm
                        left join engagement_enseignant g on g.tenant_id = cm.tenant_id and g.id = cm.engagement_id
                        left join enseignant s on s.id = g.enseignant_id
                        where cm.classe_id = ? and cm.matiere_id = ?""", UUID.class, classeId, matiere);
                if (titulaire.isEmpty()) {
                    throw new IllegalArgumentException("Matière hors de la " + c.code());
                }
                if (enseignant == null) {
                    enseignant = titulaire.get(0);
                }
            }
            if (enseignant != null && !pp.enseigneDans(enseignant, classeId)) {
                throw new RegleMetierException("ENSEIGNANT_HORS_CLASSE",
                        "L'enseignant concerné n'enseigne pas dans la " + c.code());
            }
        }
        UUID id = UUID.randomUUID();
        int annee = LocalDate.now(horloge.withZone(FUSEAU)).getYear();
        String numero = "PP-" + annee + "-" + String.format("%04d", compteurs.suivant("SAISINE_PP_" + annee));
        jdbc.update("""
                insert into saisine_classe (tenant_id, id, numero, classe_id, nature, objet, expose, auteur_id,
                    professeur_id, inscriptions, matiere_id, enseignant_id)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid[], ?, ?)""",
                id, numero, classeId, d.nature().name(), objet, expose, moi, professeur, tableau(eleves), matiere,
                enseignant);
        if (!estPp) {
            pp.informer(professeur, "Revendication " + numero + " — " + c.code(),
                    pp.nom(moi) + ", enseignant(e) de la " + c.code() + ", vous confie une revendication.\n\nObjet : "
                            + objet + "\n\n" + expose + "\n\nRetrouvez le dossier dans « Professeur principal ».");
        }
        audit.enregistrer("SAISINE_OUVERTE", numero, Map.of("classe", c.code(), "nature", d.nature().name()));
        return detail(id);
    }

    // ================================================================== consultation

    @Transactional(readOnly = true)
    public List<SaisineVue> lister(UUID anneeId, UUID classeId) {
        UtilisateurConnecte.etablissementActif();
        UUID moi = UtilisateurConnecte.id();
        boolean direction = UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "CENSEUR");
        boolean cpe = UtilisateurConnecte.aUnRole("SURVEILLANT");
        // La direction voit les dossiers transmis ; le CPE, les médiations transmises
        String saisie = direction ? "s.transmise_le is not null"
                : cpe ? "(s.transmise_le is not null and s.nature = 'MEDIATION')" : "false";
        List<Object> args = new ArrayList<>(List.of(anneeId, moi, moi, moi));
        String filtre = "";
        if (classeId != null) {
            filtre = " and s.classe_id = ?";
            args.add(classeId);
        }
        String sql = SELECT + " where k.annee_id = ? and (p.utilisateur_id = ? or s.auteur_id = ?"
                + " or (s.enseignant_id = ? and s.enseignant_informe) or " + saisie + ")" + filtre
                + " order by (s.statut = 'CLOSE'), s.cree_le desc";
        List<Ligne> lignes = jdbc.query(sql, (l, n) -> ligne(l), args.toArray());
        Map<UUID, String> noms = nomsEleves(lignes.stream().flatMap(x -> x.eleves.stream()).distinct().toList());
        return lignes.stream().map(x -> vue(x, noms, null)).toList();
    }

    @Transactional
    public SaisineVue detail(UUID id) {
        UtilisateurConnecte.etablissementActif();
        Ligne x = exigerVisible(id);
        List<SuiteVue> journal = jdbc.query("""
                select t.id, u.nom || ' ' || u.prenoms, t.qualite, t.texte, t.cree_le from suite_saisine t
                join utilisateur u on u.id = t.auteur_id where t.saisine_id = ? order by t.cree_le, t.id""",
                (l, n) -> new SuiteVue(l.getObject(1, UUID.class), l.getString(2), l.getString(3), l.getString(4),
                        instant(l.getTimestamp(5))), id);
        return vue(x, nomsEleves(x.eleves), journal);
    }

    // ================================================================== suite du dossier

    /** Échange ou démarche notée au dossier (professeur principal, auteur, enseignant informé, direction saisie). */
    @Transactional
    public SaisineVue ajouterSuite(UUID id, String texteSaisi) {
        UtilisateurConnecte.etablissementActif();
        Ligne x = exigerVisible(id);
        if (x.statut == StatutSaisine.CLOSE) {
            throw new RegleMetierException("SAISINE_CLOSE", "Le dossier " + x.numero + " est clos");
        }
        String t = texte(texteSaisi, 2000, "Texte");
        UUID moi = UtilisateurConnecte.id();
        jdbc.update("""
                insert into suite_saisine (tenant_id, id, saisine_id, auteur_id, qualite, texte)
                values (tenant_courant(), ?, ?, ?, ?, ?)""", UUID.randomUUID(), id, moi, qualite(x), t);
        String objet = "Dossier " + x.numero + " — " + x.classeCode;
        String corps = pp.nom(moi) + " (" + qualite(x).toLowerCase() + ") a écrit dans le dossier « " + x.objet
                + " » :\n\n" + t;
        if (x.estPp()) {
            if (x.nature == Nature.REVENDICATION) {
                pp.informer(x.auteurId, objet, corps);
            }
            if (x.enseignantInforme) {
                pp.informer(x.enseignantId, objet, corps);
            }
        } else {
            pp.informer(x.professeurActuel, objet, corps);
        }
        return detail(id);
    }

    /** Médiation : le professeur principal informe l'enseignant concerné, qui voit alors le dossier et y répond. */
    @Transactional
    public SaisineVue informerEnseignant(UUID id) {
        UtilisateurConnecte.etablissementActif();
        Ligne x = exigerVisible(id);
        if (!x.droits().informer()) {
            throw new RegleMetierException("INFORMATION_IMPOSSIBLE",
                    "Seul le professeur principal informe l'enseignant concerné d'une médiation en cours");
        }
        jdbc.update("update saisine_classe set enseignant_informe = true where id = ?", id);
        pp.informer(x.enseignantId, "Médiation " + x.numero + " — " + x.classeCode,
                "En tant que professeur principal de la " + x.classeCode + ", " + pp.nom(UtilisateurConnecte.id())
                        + " vous informe d'un souci exprimé par des élèves de la classe à votre sujet.\n\nObjet : "
                        + x.objet + "\n\n" + x.expose
                        + "\n\nVous pouvez donner votre version dans le dossier (« Professeur principal »).");
        audit.enregistrer("SAISINE_ENSEIGNANT_INFORME", x.numero, null);
        return detail(id);
    }

    /** Le professeur principal porte le dossier à l'administration (et au CPE pour une médiation). */
    @Transactional
    public SaisineVue transmettre(UUID id, String note) {
        UtilisateurConnecte.etablissementActif();
        Ligne x = exigerVisible(id);
        if (!x.droits().transmettre()) {
            throw new RegleMetierException("TRANSMISSION_IMPOSSIBLE",
                    "Seul le professeur principal transmet un dossier ouvert à l'administration");
        }
        UUID moi = UtilisateurConnecte.id();
        jdbc.update("update saisine_classe set statut = 'TRANSMISE', transmise_le = now(), transmise_par = ? where id = ?",
                moi, id);
        String complement = note == null || note.isBlank() ? null : texte(note, 2000, "Note");
        if (complement != null) {
            jdbc.update("""
                    insert into suite_saisine (tenant_id, id, saisine_id, auteur_id, qualite, texte)
                    values (tenant_courant(), ?, ?, ?, 'Professeur principal', ?)""", UUID.randomUUID(), id, moi,
                    complement);
        }
        List<UUID> destinataires = new ArrayList<>(x.nature == Nature.MEDIATION
                ? pp.ayantRole(Role.ADMIN_ECOLE, Role.CENSEUR, Role.SURVEILLANT)
                : pp.ayantRole(Role.ADMIN_ECOLE, Role.CENSEUR));
        String quoi = x.nature == Nature.MEDIATION ? "Médiation" : "Revendication des enseignants";
        String corps = pp.nom(moi) + ", professeur principal de la " + x.classeCode + ", porte à votre connaissance le "
                + "dossier " + x.numero + ".\n\nObjet : " + x.objet + "\n\n" + x.expose
                + (complement == null ? "" : "\n\n" + complement)
                + "\n\nRépondez dans le dossier (« Professeurs principaux »).";
        destinataires.forEach(dst -> pp.informer(dst, quoi + " " + x.numero + " — " + x.classeCode, corps));
        if (x.nature == Nature.REVENDICATION) {
            pp.informer(x.auteurId, "Revendication " + x.numero + " transmise",
                    "Le professeur principal de la " + x.classeCode + " a porté votre revendication « " + x.objet
                            + " » à l'administration.");
        }
        audit.enregistrer("SAISINE_TRANSMISE", x.numero, Map.of("destinataires", destinataires.size()));
        return detail(id);
    }

    /** Clôture avec la conclusion : le professeur principal, ou la direction pour un dossier qui lui a été transmis. */
    @Transactional
    public SaisineVue clore(UUID id, String conclusionSaisie) {
        UtilisateurConnecte.etablissementActif();
        Ligne x = exigerVisible(id);
        if (!x.droits().clore()) {
            throw new RegleMetierException("CLOTURE_IMPOSSIBLE", x.statut == StatutSaisine.CLOSE
                    ? "Le dossier " + x.numero + " est déjà clos"
                    : "Le professeur principal clôt le dossier (la direction, une fois saisie)");
        }
        String conclusion = texte(conclusionSaisie, 1000, "Conclusion");
        UUID moi = UtilisateurConnecte.id();
        jdbc.update("""
                update saisine_classe set statut = 'CLOSE', conclusion = ?, close_le = now(), close_par = ?
                where id = ?""", conclusion, moi, id);
        String corps = "Le dossier " + x.numero + " (« " + x.objet + " », " + x.classeCode + ") est clos.\n\n"
                + "Conclusion : " + conclusion;
        Set<UUID> informes = new LinkedHashSet<>();
        informes.add(x.professeurActuel);
        if (x.nature == Nature.REVENDICATION) {
            informes.add(x.auteurId);
        }
        if (x.enseignantInforme) {
            informes.add(x.enseignantId);
        }
        informes.forEach(dst -> pp.informer(dst, "Dossier " + x.numero + " clos", corps));
        audit.enregistrer("SAISINE_CLOSE", x.numero, null);
        return detail(id);
    }

    // ================================================================== lecture interne

    private static final String SELECT = """
            select s.id, s.numero, s.classe_id, k.code, s.nature, s.objet, s.expose, s.auteur_id,
                   ua.nom || ' ' || ua.prenoms, up.nom || ' ' || up.prenoms, s.inscriptions, s.matiere_id, m.libelle,
                   s.enseignant_id, ue.nom || ' ' || ue.prenoms, s.enseignant_informe, s.statut, s.transmise_le,
                   s.conclusion, s.close_le, uc.nom || ' ' || uc.prenoms, s.cree_le, p.utilisateur_id,
                   (select count(*) from suite_saisine t where t.tenant_id = s.tenant_id and t.saisine_id = s.id)
            from saisine_classe s
            join classe k on k.tenant_id = s.tenant_id and k.id = s.classe_id
            join utilisateur ua on ua.id = s.auteur_id
            left join professeur_principal p on p.tenant_id = s.tenant_id and p.classe_id = s.classe_id
            left join utilisateur up on up.id = coalesce(p.utilisateur_id, s.professeur_id)
            left join matiere m on m.tenant_id = s.tenant_id and m.id = s.matiere_id
            left join utilisateur ue on ue.id = s.enseignant_id
            left join utilisateur uc on uc.id = s.close_par""";

    /** Une ligne lue, avec les droits de l'utilisateur connecté. */
    private record Ligne(UUID id, String numero, UUID classeId, String classeCode, Nature nature, String objet,
            String expose, UUID auteurId, String auteur, String professeur, List<UUID> eleves, UUID matiereId,
            String matiere, UUID enseignantId, String enseignant, boolean enseignantInforme, StatutSaisine statut,
            java.time.Instant transmiseLe, String conclusion, java.time.Instant closeLe, String closePar,
            java.time.Instant creeLe, UUID professeurActuel, int suites) {

        UUID moi() {
            return UtilisateurConnecte.idSiConnecte().orElse(null);
        }

        boolean estPp() {
            return professeurActuel != null && professeurActuel.equals(moi());
        }

        boolean directionSaisie() {
            return transmiseLe != null && (UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "CENSEUR")
                    || (nature == Nature.MEDIATION && UtilisateurConnecte.aUnRole("SURVEILLANT")));
        }

        boolean visible() {
            UUID moi = moi();
            return estPp() || (moi != null && moi.equals(auteurId))
                    || (enseignantInforme && moi != null && moi.equals(enseignantId)) || directionSaisie();
        }

        Droits droits() {
            boolean ouvert = statut != StatutSaisine.CLOSE;
            return new Droits(ouvert && visible(),
                    ouvert && estPp() && nature == Nature.MEDIATION && enseignantId != null && !enseignantInforme,
                    estPp() && statut == StatutSaisine.OUVERTE,
                    ouvert && (estPp() || (transmiseLe != null && UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "CENSEUR"))));
        }
    }

    private Ligne ligne(ResultSet l) throws SQLException {
        Array a = l.getArray(11);
        List<UUID> eleves = a == null ? List.of() : Arrays.stream((Object[]) a.getArray()).map(o -> (UUID) o).toList();
        return new Ligne(l.getObject(1, UUID.class), l.getString(2), l.getObject(3, UUID.class), l.getString(4),
                Nature.valueOf(l.getString(5)), l.getString(6), l.getString(7), l.getObject(8, UUID.class),
                l.getString(9), l.getString(10), eleves, l.getObject(12, UUID.class), l.getString(13),
                l.getObject(14, UUID.class), l.getString(15), l.getBoolean(16), StatutSaisine.valueOf(l.getString(17)),
                instant(l.getTimestamp(18)), l.getString(19), instant(l.getTimestamp(20)), l.getString(21),
                instant(l.getTimestamp(22)), l.getObject(23, UUID.class), l.getInt(24));
    }

    private Ligne exigerVisible(UUID id) {
        Ligne x = jdbc.query(SELECT + " where s.id = ?", (l, n) -> ligne(l), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Dossier introuvable"));
        if (!x.visible()) {
            throw new AccesRefuseException("Ce dossier ne vous est pas adressé");
        }
        return x;
    }

    private SaisineVue vue(Ligne x, Map<UUID, String> noms, List<SuiteVue> journal) {
        return new SaisineVue(x.id, x.numero, x.classeId, x.classeCode, x.nature, x.objet, x.expose, x.auteur,
                x.professeur, x.eleves.stream().map(e -> new EleveConcerne(e, noms.getOrDefault(e, ""))).toList(),
                x.matiereId, x.matiere, x.enseignantId, x.enseignant, x.enseignantInforme, x.statut, x.transmiseLe,
                x.conclusion, x.closeLe, x.closePar, x.creeLe, x.suites, journal, x.droits());
    }

    private String qualite(Ligne x) {
        if (x.estPp()) {
            return "Professeur principal";
        }
        if (UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            return "Chef d'établissement";
        }
        if (UtilisateurConnecte.aUnRole("CENSEUR")) {
            return "Censeur";
        }
        if (UtilisateurConnecte.aUnRole("SURVEILLANT")) {
            return "Conseiller principal d'éducation";
        }
        return "Enseignant";
    }

    private Map<UUID, String> nomsEleves(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> noms = new HashMap<>();
        jdbc.query("""
                select i.id, e.nom || ' ' || e.prenoms from inscription i
                join eleve e on e.tenant_id = i.tenant_id and e.id = i.eleve_id where i.id = any (?::uuid[])""",
                (org.springframework.jdbc.core.RowCallbackHandler) l -> noms.put(l.getObject(1, UUID.class),
                        l.getString(2)), tableau(ids));
        return noms;
    }

    static String tableau(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
    }
}
