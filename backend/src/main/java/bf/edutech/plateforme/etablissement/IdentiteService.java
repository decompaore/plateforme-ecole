package bf.edutech.plateforme.etablissement;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.documents.EnteteOfficiel;
import bf.edutech.plateforme.socle.documents.EntetesOfficiels;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.export.EntetePdf;
import bf.edutech.plateforme.socle.export.ExportTableaux;
import bf.edutech.plateforme.socle.export.Tableau;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Identité de l'établissement sur ses documents officiels : rattachement (lecture seule, fixé
 * par la plateforme) et logo (choisi par l'administrateur de l'établissement).
 */
@Service
public class IdentiteService {

    /** Taille maximale d'un logo : il est imprimé petit, et stocké dans chaque bulletin. */
    static final int TAILLE_MAX_LOGO = 500 * 1024;

    public record IdentiteVue(String nom, String pays, String devise, List<String> autorites, boolean rattache,
            LogoVue logo) {
    }

    public record LogoVue(String type, int taille, Instant modifieLe) {
    }

    public record Logo(String type, byte[] contenu) {
    }

    private final JdbcTemplate jdbc;
    private final EntetesOfficiels entetes;
    private final AuditService audit;

    IdentiteService(JdbcTemplate jdbc, EntetesOfficiels entetes, AuditService audit) {
        this.jdbc = jdbc;
        this.entetes = entetes;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public IdentiteVue identite() {
        UtilisateurConnecte.etablissementActif();
        EnteteOfficiel e = entetes.courant();
        LogoVue logo = jdbc.query("""
                select type_contenu, octet_length(contenu), modifie_le from logo_etablissement
                where tenant_id = tenant_courant()""",
                (l, n) -> new LogoVue(l.getString(1), l.getInt(2), l.getObject(3, OffsetDateTime.class).toInstant()))
                .stream().findFirst().orElse(null);
        return new IdentiteVue(e.etablissement(), e.pays(), e.devise(), e.autorites(), e.officiel(), logo);
    }

    @Transactional(readOnly = true)
    public Logo logo() {
        UtilisateurConnecte.etablissementActif();
        return jdbc.query("select type_contenu, contenu from logo_etablissement where tenant_id = tenant_courant()",
                (l, n) -> new Logo(l.getString(1), l.getBytes(2))).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Aucun logo"));
    }

    /** Enregistre le logo (PNG ou JPEG, 500 Ko au plus) ; il figure ensuite sur tous les documents. */
    @Transactional
    public IdentiteVue enregistrerLogo(byte[] contenu) {
        UtilisateurConnecte.etablissementActif();
        if (contenu == null || contenu.length == 0) {
            throw new IllegalArgumentException("Fichier vide");
        }
        if (contenu.length > TAILLE_MAX_LOGO) {
            throw new IllegalArgumentException("Logo trop lourd : 500 Ko au plus (réduisez l'image)");
        }
        String type = type(contenu);
        EntetePdf.verifierImage(contenu);
        jdbc.update("""
                insert into logo_etablissement (tenant_id, type_contenu, contenu, modifie_le, modifie_par)
                values (tenant_courant(), ?, ?, now(), ?)
                on conflict (tenant_id) do update set type_contenu = excluded.type_contenu, contenu = excluded.contenu,
                    modifie_le = excluded.modifie_le, modifie_par = excluded.modifie_par""",
                type, contenu, UtilisateurConnecte.id());
        audit.enregistrer("LOGO_MODIFIE", null, Map.of("taille", contenu.length));
        return identite();
    }

    @Transactional
    public IdentiteVue supprimerLogo() {
        UtilisateurConnecte.etablissementActif();
        jdbc.update("delete from logo_etablissement where tenant_id = tenant_courant()");
        audit.enregistrer("LOGO_SUPPRIME", null, null);
        return identite();
    }

    /** Petit document PDF pour voir l'en-tête tel qu'il sera imprimé. */
    @Transactional(readOnly = true)
    public byte[] apercu() {
        UtilisateurConnecte.etablissementActif();
        Tableau t = new Tableau("Aperçu", "Aperçu de l'en-tête des documents officiels")
                .sousTitre("Cet en-tête figure sur les bulletins, les reçus, les fiches et les exports PDF.")
                .colonnes(Tableau.Colonne.texte("Document", 3), Tableau.Colonne.texte("En-tête", 5))
                .ligne("Bulletins", "Complet (pays, tutelle, logo, établissement)")
                .ligne("Reçus de paiement", "Complet")
                .ligne("Fiches et exports PDF", "Complet en première page, nom de l'établissement ensuite")
                .portrait();
        return ExportTableaux.pdf(entetes.courant(), t);
    }

    /** Type d'après les premiers octets (pas d'après le nom ou le type annoncé). */
    static String type(byte[] o) {
        if (o.length >= 8 && (o[0] & 0xFF) == 0x89 && o[1] == 'P' && o[2] == 'N' && o[3] == 'G') {
            return "image/png";
        }
        if (o.length >= 3 && (o[0] & 0xFF) == 0xFF && (o[1] & 0xFF) == 0xD8 && (o[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        throw new IllegalArgumentException("Format non pris en charge : utilisez une image PNG ou JPEG");
    }
}
