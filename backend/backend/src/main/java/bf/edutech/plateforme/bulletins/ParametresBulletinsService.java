package bf.edutech.plateforme.bulletins;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Paramètres des bulletins de l'établissement : en-tête (pays, devise,
 * ministère, direction régionale, adresse) et seuils des distinctions.
 * Valeurs par défaut créées au premier accès.
 */
@Service
public class ParametresBulletinsService {

    public record ParametresBulletins(String entetePays, String enteteDevise, String enteteMinistere,
            String enteteDirection, String adresse, BigDecimal seuilTableauHonneur, BigDecimal seuilEncouragements,
            BigDecimal seuilFelicitations, BigDecimal seuilAvertissement) {

        /** Distinction proposée d'après la moyenne (le conseil de classe peut la changer). */
        Distinction proposer(BigDecimal moyenne) {
            if (moyenne == null) {
                return Distinction.AUCUNE;
            }
            if (moyenne.compareTo(seuilFelicitations) >= 0) {
                return Distinction.FELICITATIONS;
            }
            if (moyenne.compareTo(seuilEncouragements) >= 0) {
                return Distinction.ENCOURAGEMENTS;
            }
            if (moyenne.compareTo(seuilTableauHonneur) >= 0) {
                return Distinction.TABLEAU_HONNEUR;
            }
            if (moyenne.compareTo(seuilAvertissement) < 0) {
                return Distinction.AVERTISSEMENT_TRAVAIL;
            }
            return Distinction.AUCUNE;
        }
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    ParametresBulletinsService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public ParametresBulletins lire() {
        UtilisateurConnecte.etablissementActif();
        jdbc.update("insert into parametres_bulletin (tenant_id) values (tenant_courant()) on conflict do nothing");
        return jdbc.queryForObject("""
                select entete_pays, entete_devise, entete_ministere, entete_direction, adresse,
                       seuil_tableau_honneur, seuil_encouragements, seuil_felicitations, seuil_avertissement
                from parametres_bulletin where tenant_id = tenant_courant()""",
                (l, n) -> new ParametresBulletins(l.getString(1), l.getString(2), l.getString(3), l.getString(4),
                        l.getString(5), l.getBigDecimal(6), l.getBigDecimal(7), l.getBigDecimal(8),
                        l.getBigDecimal(9)));
    }

    @Transactional
    public ParametresBulletins modifier(ParametresBulletins p) {
        lire();
        if (p.entetePays() == null || p.entetePays().isBlank()) {
            throw new IllegalArgumentException("La première ligne de l'en-tête (pays) est obligatoire");
        }
        if (p.seuilAvertissement().compareTo(p.seuilTableauHonneur()) >= 0
                || p.seuilTableauHonneur().compareTo(p.seuilEncouragements()) > 0
                || p.seuilEncouragements().compareTo(p.seuilFelicitations()) > 0
                || p.seuilFelicitations().compareTo(BigDecimal.valueOf(20)) > 0) {
            throw new IllegalArgumentException("Seuils incohérents : avertissement < tableau d'honneur ≤ "
                    + "encouragements ≤ félicitations ≤ 20");
        }
        jdbc.update("""
                update parametres_bulletin set entete_pays = ?, entete_devise = ?, entete_ministere = ?,
                       entete_direction = ?, adresse = ?, seuil_tableau_honneur = ?, seuil_encouragements = ?,
                       seuil_felicitations = ?, seuil_avertissement = ?
                where tenant_id = tenant_courant()""",
                p.entetePays().trim(), vide(p.enteteDevise()), vide(p.enteteMinistere()), vide(p.enteteDirection()),
                vide(p.adresse()), p.seuilTableauHonneur(), p.seuilEncouragements(), p.seuilFelicitations(),
                p.seuilAvertissement());
        audit.enregistrer("PARAMETRES_BULLETINS_MODIFIES", null, null);
        return lire();
    }

    private static String vide(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur.trim();
    }
}
