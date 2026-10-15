package bf.edutech.plateforme.secretariat;

import java.sql.Date;
import java.sql.Time;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.documents.RenduDocument;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Comptes rendus des réunions statutaires (conseil d'établissement, conseils de classe, de discipline,
 * d'enseignement, conseil de l'école, assemblée de l'APE, réunions du personnel) : le secrétariat prépare (ordre du
 * jour, participants) et rédige ; le chef d'établissement valide — le compte rendu ne change plus.
 */
@Service
public class ReunionsService {

    public enum Instance {
        CONSEIL_ETABLISSEMENT("Conseil d'établissement"), CONSEIL_CLASSE("Conseil de classe"),
        CONSEIL_DISCIPLINE("Conseil de discipline"), CONSEIL_ENSEIGNEMENT("Conseil d'enseignement"),
        CONSEIL_ECOLE("Conseil de l'école"), ASSEMBLEE_APE("Assemblée générale de l'APE"),
        REUNION_PERSONNEL("Réunion du personnel"), AUTRE("Réunion");

        final String libelle;

        Instance(String libelle) {
            this.libelle = libelle;
        }
    }

    public enum Statut { BROUILLON, VALIDE }

    public record Saisie(String instance, String titre, LocalDate date, LocalTime heure, String lieu,
            String president, String secretaireSeance, String participants, String ordreDuJour, String compteRendu) {
    }

    public record ReunionVue(UUID id, Instance instance, String instanceLibelle, String titre, LocalDate date,
            LocalTime heure, String lieu, String president, String secretaireSeance, String participants,
            String ordreDuJour, String compteRendu, Statut statut, String validePar, Instant valideLe) {
    }

    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH'h'mm");

    private final JdbcTemplate jdbc;
    private final RenduDocument rendu;
    private final AuditService audit;
    private final Clock horloge;

    ReunionsService(JdbcTemplate jdbc, RenduDocument rendu, AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.rendu = rendu;
        this.audit = audit;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<ReunionVue> lister() {
        exigerLecture();
        return jdbc.query(SELECT + " order by r.date_reunion desc, r.heure desc nulls last limit 300", VUE);
    }

    @Transactional
    public ReunionVue creer(Saisie s) {
        Secretariat.exigerGestionnaire();
        Valeurs v = valider(s);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into reunion (tenant_id, id, instance, titre, date_reunion, heure, lieu, president, secretaire_seance,
                                     participants, ordre_du_jour, compte_rendu, redige_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, v.instance.name(), v.titre, Date.valueOf(v.date), v.heure == null ? null : Time.valueOf(v.heure),
                v.lieu, v.president, v.secretaire, v.participants, v.ordre, v.compteRendu,
                UtilisateurConnecte.idSiConnecte().orElse(null));
        return charger(id);
    }

    @Transactional
    public ReunionVue modifier(UUID id, Saisie s) {
        Secretariat.exigerGestionnaire();
        if (charger(id).statut() == Statut.VALIDE) {
            throw new RegleMetierException("COMPTE_RENDU_VALIDE", "Le compte rendu est validé : il ne change plus");
        }
        Valeurs v = valider(s);
        jdbc.update("""
                update reunion set instance = ?, titre = ?, date_reunion = ?, heure = ?, lieu = ?, president = ?,
                       secretaire_seance = ?, participants = ?, ordre_du_jour = ?, compte_rendu = ?, modifie_le = now()
                 where id = ?""",
                v.instance.name(), v.titre, Date.valueOf(v.date), v.heure == null ? null : Time.valueOf(v.heure), v.lieu,
                v.president, v.secretaire, v.participants, v.ordre, v.compteRendu, id);
        return charger(id);
    }

    @Transactional
    public void supprimer(UUID id) {
        Secretariat.exigerGestionnaire();
        if (charger(id).statut() == Statut.VALIDE) {
            throw new RegleMetierException("COMPTE_RENDU_VALIDE", "Un compte rendu validé se garde");
        }
        jdbc.update("delete from reunion where id = ?", id);
    }

    @Transactional
    public ReunionVue valider(UUID id) {
        if (!UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            throw new AccesRefuseException("Seul le chef d'établissement valide un compte rendu");
        }
        ReunionVue r = charger(id);
        if (r.statut() == Statut.VALIDE) {
            throw new RegleMetierException("COMPTE_RENDU_VALIDE", "Compte rendu déjà validé");
        }
        if (r.compteRendu() == null || r.compteRendu().isBlank()) {
            throw new RegleMetierException("COMPTE_RENDU_VIDE", "Rédigez le compte rendu avant de le valider");
        }
        if (r.date().isAfter(LocalDate.now(horloge))) {
            throw new RegleMetierException("REUNION_A_VENIR", "La réunion n'a pas encore eu lieu");
        }
        jdbc.update("update reunion set statut = 'VALIDE', valide_par = ?, valide_le = ? where id = ?",
                UtilisateurConnecte.id(), java.sql.Timestamp.from(horloge.instant()), id);
        audit.enregistrer("COMPTE_RENDU_VALIDE", r.titre(), Map.of("instance", r.instance().name()));
        return charger(id);
    }

    @Transactional(readOnly = true)
    public byte[] pdf(UUID id) {
        exigerLecture();
        ReunionVue r = charger(id);
        List<String[]> rubriques = new ArrayList<>();
        rubriques.add(new String[] { "Instance", r.instanceLibelle() });
        rubriques.add(new String[] { "Date", r.date().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                + (r.heure() != null ? " à " + r.heure().format(HEURE) : "") });
        rubriques.add(new String[] { "Lieu", r.lieu() });
        rubriques.add(new String[] { "Président de séance", r.president() });
        rubriques.add(new String[] { "Secrétaire de séance", r.secretaireSeance() });
        rubriques.add(new String[] { "Participants", r.participants() });
        String corps = (r.ordreDuJour() != null ? "ORDRE DU JOUR\n" + r.ordreDuJour() + "\n\n" : "")
                + (r.compteRendu() != null ? "COMPTE RENDU\n" + r.compteRendu() : "");
        return rendu.rendre(new RenduDocument.Contenu(null, "Compte rendu — " + r.titre(), rubriques, corps, r.date(),
                "Le secrétaire de séance", r.secretaireSeance(), null, r.statut() == Statut.BROUILLON,
                r.statut() == Statut.VALIDE ? "Validé par " + r.validePar() : null));
    }

    // ------------------------------------------------------------------

    private record Valeurs(Instance instance, String titre, LocalDate date, LocalTime heure, String lieu,
            String president, String secretaire, String participants, String ordre, String compteRendu) {
    }

    private static Valeurs valider(Saisie s) {
        if (s == null || s.date() == null) {
            throw new IllegalArgumentException("Indiquez la date de la réunion");
        }
        Instance i = Secretariat.valeur(Instance.class, s.instance(), "Instance");
        return new Valeurs(i, s.titre() == null || s.titre().isBlank() ? i.libelle
                : Secretariat.obligatoire(s.titre(), "Le titre", 200), s.date(), s.heure(),
                Secretariat.facultatif(s.lieu(), "Le lieu", 150), Secretariat.facultatif(s.president(), "Le président", 150),
                Secretariat.facultatif(s.secretaireSeance(), "Le secrétaire de séance", 150),
                Secretariat.facultatif(s.participants(), "Les participants", 4000),
                Secretariat.facultatif(s.ordreDuJour(), "L'ordre du jour", 4000),
                Secretariat.facultatif(s.compteRendu(), "Le compte rendu", 20000));
    }

    private static void exigerLecture() {
        if (!UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "SECRETARIAT", "CENSEUR", "CHEF_TRAVAUX")) {
            throw new AccesRefuseException("Comptes rendus réservés à la direction et au secrétariat");
        }
    }

    private ReunionVue charger(UUID id) {
        return jdbc.query(SELECT + " where r.id = ?", VUE, id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Réunion introuvable"));
    }

    private static final String SELECT = """
            select r.id, r.instance, r.titre, r.date_reunion, r.heure, r.lieu, r.president, r.secretaire_seance,
                   r.participants, r.ordre_du_jour, r.compte_rendu, r.statut, u.prenoms || ' ' || u.nom, r.valide_le
              from reunion r left join utilisateur u on u.id = r.valide_par""";

    private static final RowMapper<ReunionVue> VUE = (r, n) -> {
        Instance i = Instance.valueOf(r.getString(2));
        return new ReunionVue(r.getObject(1, UUID.class), i, i.libelle, r.getString(3), r.getDate(4).toLocalDate(),
                r.getTime(5) == null ? null : r.getTime(5).toLocalTime(), r.getString(6), r.getString(7),
                r.getString(8), r.getString(9), r.getString(10), r.getString(11), Statut.valueOf(r.getString(12)),
                r.getString(13),
                r.getObject(14, OffsetDateTime.class) == null ? null : r.getObject(14, OffsetDateTime.class).toInstant());
    };
}
