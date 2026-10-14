package bf.edutech.plateforme.secretariat;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.compteurs.Compteurs;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.utilisateurs.Signatures;

/**
 * Documents administratifs : le secrétariat rédige (notes de service, décisions, communiqués, attestations,
 * convocations, lettres), le chef d'établissement signe — le document reçoit alors son numéro (2026-0012/NS) et ne
 * change plus. Un document signé s'affiche au tableau d'affichage pour le personnel ou pour tous (parents
 * compris), entre deux dates.
 */
@Service
public class DocumentsService {

    public enum Type {
        NOTE_SERVICE("NS", "Note de service"), DECISION("DEC", "Décision"), COMMUNIQUE("COM", "Communiqué"),
        ATTESTATION("ATT", "Attestation"), CONVOCATION("CONV", "Convocation"), LETTRE("L", "Lettre"),
        AUTRE("DOC", "Document");

        final String sigle;
        final String libelle;

        Type(String sigle, String libelle) {
            this.sigle = sigle;
            this.libelle = libelle;
        }
    }

    public enum Statut { BROUILLON, SIGNE, ANNULE }

    public enum Public { PERSONNEL, TOUS }

    public record Saisie(String type, String titre, String destinataires, String corps, String signataire) {
    }

    public record DocumentVue(UUID id, Type type, String numero, String titre, String destinataires, String corps,
            String signataire, Statut statut, String redigePar, Instant redigeLe, String signePar, Instant signeLe,
            Public affichage, LocalDate afficheDu, LocalDate afficheAu) {
    }

    /** Ce que montre le tableau d'affichage (sans le texte : on ouvre le PDF). */
    public record AfficheVue(UUID id, Type type, String typeLibelle, String numero, String titre, Instant signeLe,
            LocalDate afficheAu) {
    }

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");

    private final JdbcTemplate jdbc;
    private final Compteurs compteurs;
    private final Signatures signatures;
    private final RenduDocument rendu;
    private final AuditService audit;
    private final Clock horloge;

    DocumentsService(JdbcTemplate jdbc, Compteurs compteurs, Signatures signatures, RenduDocument rendu,
            AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.compteurs = compteurs;
        this.signatures = signatures;
        this.rendu = rendu;
        this.audit = audit;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<DocumentVue> lister(String type, String statut) {
        Secretariat.exigerGestionnaire();
        return jdbc.query(SELECT + " where (cast(? as varchar) is null or d.type = ?) "
                + "and (cast(? as varchar) is null or d.statut = ?) order by d.modifie_le desc limit 500", VUE,
                type, type == null ? null : Secretariat.valeur(Type.class, type, "Type").name(),
                statut, statut == null ? null : Secretariat.valeur(Statut.class, statut, "Statut").name());
    }

    @Transactional(readOnly = true)
    public DocumentVue trouver(UUID id) {
        Secretariat.exigerGestionnaire();
        return charger(id);
    }

    @Transactional
    public DocumentVue rediger(Saisie s) {
        Secretariat.exigerGestionnaire();
        Valeurs v = valider(s);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into document_administratif (tenant_id, id, type, titre, destinataires, corps, signataire, redige_par)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?)""",
                id, v.type.name(), v.titre, v.destinataires, v.corps, v.signataire,
                UtilisateurConnecte.idSiConnecte().orElse(null));
        return charger(id);
    }

    @Transactional
    public DocumentVue modifier(UUID id, Saisie s) {
        Secretariat.exigerGestionnaire();
        exigerBrouillon(charger(id));
        Valeurs v = valider(s);
        jdbc.update("""
                update document_administratif set type = ?, titre = ?, destinataires = ?, corps = ?, signataire = ?,
                       modifie_le = now() where id = ?""",
                v.type.name(), v.titre, v.destinataires, v.corps, v.signataire, id);
        return charger(id);
    }

    @Transactional
    public void supprimer(UUID id) {
        Secretariat.exigerGestionnaire();
        exigerBrouillon(charger(id));
        jdbc.update("delete from document_administratif where id = ?", id);
    }

    /** Signature du chef d'établissement : numéro attribué, texte figé. */
    @Transactional
    public DocumentVue signer(UUID id) {
        if (!UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            throw new AccesRefuseException("Seul le chef d'établissement signe un document");
        }
        DocumentVue d = charger(id);
        exigerBrouillon(d);
        int annee = LocalDate.now(horloge).getYear();
        String numero = annee + "-" + String.format("%04d", compteurs.suivant("document:" + d.type().name() + ":" + annee))
                + "/" + d.type().sigle;
        jdbc.update("""
                update document_administratif set statut = 'SIGNE', numero = ?, signe_par = ?, signe_le = ?,
                       modifie_le = now() where id = ?""",
                numero, UtilisateurConnecte.id(), java.sql.Timestamp.from(horloge.instant()), id);
        audit.enregistrer("DOCUMENT_SIGNE", numero, Map.of("type", d.type().name()));
        return charger(id);
    }

    /** Annulation d'un document signé (il garde son numéro, quitte l'affichage). */
    @Transactional
    public DocumentVue annuler(UUID id) {
        if (!UtilisateurConnecte.aUnRole("ADMIN_ECOLE")) {
            throw new AccesRefuseException("Seul le chef d'établissement annule un document signé");
        }
        DocumentVue d = charger(id);
        if (d.statut() != Statut.SIGNE) {
            throw new RegleMetierException("DOCUMENT_NON_SIGNE", "Un brouillon se supprime ; seul un document signé s'annule");
        }
        jdbc.update("""
                update document_administratif set statut = 'ANNULE', affichage_public = null, affiche_du = null,
                       affiche_au = null, modifie_le = now() where id = ?""", id);
        audit.enregistrer("DOCUMENT_ANNULE", d.numero(), Map.of());
        return charger(id);
    }

    /** Met au tableau d'affichage (ou en retire, {@code public == null}) un document signé. */
    @Transactional
    public DocumentVue afficher(UUID id, String publicVise, LocalDate du, LocalDate au) {
        Secretariat.exigerGestionnaire();
        DocumentVue d = charger(id);
        if (publicVise == null || publicVise.isBlank()) {
            jdbc.update("""
                    update document_administratif set affichage_public = null, affiche_du = null, affiche_au = null,
                           modifie_le = now() where id = ?""", id);
            return charger(id);
        }
        if (d.statut() != Statut.SIGNE) {
            throw new RegleMetierException("DOCUMENT_NON_SIGNE", "Seul un document signé s'affiche");
        }
        Public p = Secretariat.valeur(Public.class, publicVise, "Public");
        LocalDate debut = du != null ? du : LocalDate.now(horloge);
        if (au == null || au.isBefore(debut)) {
            throw new IllegalArgumentException("Indiquez jusqu'à quand le document reste affiché (après le début)");
        }
        if (au.isAfter(debut.plusDays(366))) {
            throw new IllegalArgumentException("Un affichage dure un an au plus");
        }
        jdbc.update("""
                update document_administratif set affichage_public = ?, affiche_du = ?, affiche_au = ?, modifie_le = now()
                 where id = ?""", p.name(), Date.valueOf(debut), Date.valueOf(au), id);
        return charger(id);
    }

    /** Tableau d'affichage : documents affichés aujourd'hui ; le personnel voit tout, les parents ce qui est pour tous. */
    @Transactional(readOnly = true)
    public List<AfficheVue> affichage() {
        UtilisateurConnecte.etablissementActif();
        boolean personnel = estPersonnel();
        LocalDate j = LocalDate.now(horloge);
        return jdbc.query("""
                select id, type, numero, titre, signe_le, affiche_au from document_administratif
                 where statut = 'SIGNE' and affiche_du <= ? and affiche_au >= ? and (? or affichage_public = 'TOUS')
                 order by signe_le desc""",
                (r, n) -> {
                    Type t = Type.valueOf(r.getString(2));
                    return new AfficheVue(r.getObject(1, UUID.class), t, t.libelle, r.getString(3), r.getString(4),
                            r.getObject(5, OffsetDateTime.class).toInstant(), r.getDate(6).toLocalDate());
                }, Date.valueOf(j), Date.valueOf(j), personnel);
    }

    /** PDF : le secrétariat et le chef toujours ; les autres seulement un document affiché qui leur est destiné. */
    @Transactional(readOnly = true)
    public byte[] pdf(UUID id) {
        UtilisateurConnecte.etablissementActif();
        DocumentVue d = charger(id);
        if (!Secretariat.gestionnaire()) {
            boolean visible = affichage().stream().anyMatch(a -> a.id().equals(id));
            if (!visible) {
                throw new RessourceIntrouvableException("Document introuvable");
            }
        }
        UUID signePar = d.statut() == Statut.BROUILLON ? null
                : jdbc.queryForObject("select signe_par from document_administratif where id = ?", UUID.class, id);
        byte[] signature = signePar == null ? null : signatures.signature(signePar).orElse(null);
        LocalDate date = d.signeLe() != null ? d.signeLe().atZone(FUSEAU).toLocalDate() : LocalDate.now(horloge);
        return rendu.rendre(new RenduDocument.Contenu(d.numero(), d.titre(),
                List.<String[]>of(new String[] { "Destinataires", d.destinataires() }), d.corps(), date, d.signataire(),
                d.signePar(), signature, d.statut() == Statut.BROUILLON,
                d.statut() == Statut.ANNULE ? "Document annulé" : null));
    }

    // ------------------------------------------------------------------

    private record Valeurs(Type type, String titre, String destinataires, String corps, String signataire) {
    }

    private static Valeurs valider(Saisie s) {
        if (s == null) {
            throw new IllegalArgumentException("Document vide");
        }
        return new Valeurs(Secretariat.valeur(Type.class, s.type(), "Type de document"),
                Secretariat.obligatoire(s.titre(), "L'objet du document", 200),
                Secretariat.facultatif(s.destinataires(), "Les destinataires", 300),
                Secretariat.obligatoire(s.corps(), "Le texte du document", 20000),
                s.signataire() == null || s.signataire().isBlank() ? "Le Chef d'établissement"
                        : Secretariat.obligatoire(s.signataire(), "La fonction du signataire", 120));
    }

    private static void exigerBrouillon(DocumentVue d) {
        if (d.statut() != Statut.BROUILLON) {
            throw new RegleMetierException("DOCUMENT_SIGNE", "Le document " + d.numero() + " est signé : il ne change plus");
        }
    }

    private static boolean estPersonnel() {
        return UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "CENSEUR", "CHEF_TRAVAUX", "SECRETARIAT", "INTENDANT",
                "SURVEILLANT", "ENSEIGNANT");
    }

    private DocumentVue charger(UUID id) {
        return jdbc.query(SELECT + " where d.id = ?", VUE, id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Document introuvable"));
    }

    private static final String SELECT = """
            select d.id, d.type, d.numero, d.titre, d.destinataires, d.corps, d.signataire, d.statut,
                   r.nom || ' ' || r.prenoms, d.redige_le, s.prenoms || ' ' || s.nom, d.signe_le,
                   d.affichage_public, d.affiche_du, d.affiche_au
              from document_administratif d
              left join utilisateur r on r.id = d.redige_par
              left join utilisateur s on s.id = d.signe_par""";

    private static final RowMapper<DocumentVue> VUE = (r, n) -> new DocumentVue(r.getObject(1, UUID.class),
            Type.valueOf(r.getString(2)), r.getString(3), r.getString(4), r.getString(5), r.getString(6),
            r.getString(7), Statut.valueOf(r.getString(8)), r.getString(9),
            r.getObject(10, OffsetDateTime.class).toInstant(), r.getString(11),
            r.getObject(12, OffsetDateTime.class) == null ? null : r.getObject(12, OffsetDateTime.class).toInstant(),
            r.getString(13) == null ? null : Public.valueOf(r.getString(13)),
            r.getDate(14) == null ? null : r.getDate(14).toLocalDate(),
            r.getDate(15) == null ? null : r.getDate(15).toLocalDate());
}
