package bf.edutech.plateforme.mobilemoney;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Compte marchand de l'école chez l'agrégateur. Les clés sont chiffrées en base et
 * ne sont jamais renvoyées par l'API (seulement « définie / non définie »).
 */
@Service
public class ConfigurationMobileMoneyService {

    public record ConfigurationVue(String agregateur, String identifiantMarchand, boolean cleApiDefinie,
            boolean secretWebhookDefini, boolean actif, String urlNotification, Set<String> agregateursDisponibles) {
    }

    record Configuration(Agregateur agregateur, Agregateur.Identifiants identifiants, boolean actif) {
    }

    private final JdbcTemplate jdbc;
    private final Coffre coffre;
    private final Agregateurs agregateurs;
    private final AuditService audit;
    private final Clock horloge;

    ConfigurationMobileMoneyService(JdbcTemplate jdbc, Coffre coffre, Agregateurs agregateurs, AuditService audit,
            Clock horloge) {
        this.jdbc = jdbc;
        this.coffre = coffre;
        this.agregateurs = agregateurs;
        this.audit = audit;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public ConfigurationVue lire() {
        UUID ecole = UtilisateurConnecte.etablissementActif();
        String url = "/api/v1/webhooks/mobile-money/" + ecole;
        return lignes().stream().findFirst()
                .map(l -> new ConfigurationVue((String) l.get("agregateur"), (String) l.get("identifiant_marchand"),
                        true, true, (Boolean) l.get("actif"), url, agregateurs.codes()))
                .orElse(new ConfigurationVue(null, null, false, false, false, url, agregateurs.codes()));
    }

    /** Enregistre le compte ; une clé laissée vide conserve la valeur déjà enregistrée. */
    @Transactional
    public ConfigurationVue modifier(String agregateur, String identifiantMarchand, String cleApi,
            String secretWebhook, boolean actif) {
        UUID ecole = UtilisateurConnecte.etablissementActif();
        agregateurs.exiger(agregateur);
        if (identifiantMarchand == null || identifiantMarchand.isBlank() || identifiantMarchand.trim().length() > 80) {
            throw new IllegalArgumentException("L'identifiant marchand est obligatoire (80 caractères au plus)");
        }
        List<Map<String, Object>> existantes = lignes();
        boolean nouvelle = existantes.isEmpty();
        if (nouvelle && (vide(cleApi) || vide(secretWebhook))) {
            throw new IllegalArgumentException("La clé d'API et le secret de notification sont obligatoires");
        }
        String cle = vide(cleApi) ? (String) existantes.get(0).get("cle_api_chiffree")
                : coffre.chiffrer(cleApi.trim(), ecole);
        String secret = vide(secretWebhook) ? (String) existantes.get(0).get("secret_webhook_chiffre")
                : coffre.chiffrer(secretWebhook.trim(), ecole);
        jdbc.update("""
                insert into configuration_mobile_money (tenant_id, agregateur, identifiant_marchand, cle_api_chiffree,
                       secret_webhook_chiffre, actif, modifie_par, modifie_le)
                values (tenant_courant(), ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id) do update set agregateur = excluded.agregateur,
                       identifiant_marchand = excluded.identifiant_marchand,
                       cle_api_chiffree = excluded.cle_api_chiffree,
                       secret_webhook_chiffre = excluded.secret_webhook_chiffre, actif = excluded.actif,
                       modifie_par = excluded.modifie_par, modifie_le = excluded.modifie_le""",
                agregateur, identifiantMarchand.trim(), cle, secret, actif,
                UtilisateurConnecte.idSiConnecte().orElse(null), java.sql.Timestamp.from(horloge.instant()));
        audit.enregistrer("MOBILE_MONEY_CONFIGURE", agregateur, Map.of("actif", actif));
        return lire();
    }

    /** Configuration déchiffrée de l'établissement actif (usage interne). */
    @Transactional(readOnly = true)
    Optional<Configuration> charger() {
        UUID ecole = UtilisateurConnecte.etablissementActif();
        return lignes().stream().findFirst().map(l -> new Configuration(
                agregateurs.exiger((String) l.get("agregateur")),
                new Agregateur.Identifiants((String) l.get("identifiant_marchand"),
                        coffre.dechiffrer((String) l.get("cle_api_chiffree"), ecole),
                        coffre.dechiffrer((String) l.get("secret_webhook_chiffre"), ecole)),
                (Boolean) l.get("actif")));
    }

    /** Configuration active, sinon refus clair. */
    @Transactional(readOnly = true)
    Configuration active() {
        return charger().filter(Configuration::actif).orElseThrow(() -> new RegleMetierException(
                "MOBILE_MONEY_INACTIF", "Le paiement Mobile Money n'est pas activé dans cet établissement"));
    }

    private List<Map<String, Object>> lignes() {
        return jdbc.queryForList("""
                select agregateur, identifiant_marchand, cle_api_chiffree, secret_webhook_chiffre, actif
                from configuration_mobile_money where tenant_id = tenant_courant()""");
    }

    private static boolean vide(String valeur) {
        return valeur == null || valeur.isBlank();
    }
}
