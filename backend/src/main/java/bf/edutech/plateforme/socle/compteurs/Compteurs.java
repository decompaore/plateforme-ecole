package bf.edutech.plateforme.socle.compteurs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Compteurs par établissement (matricules, numéros de reçus...).
 * <p>
 * Un seul ordre SQL « INSERT ... ON CONFLICT DO UPDATE ... RETURNING » :
 * la ligne du compteur est verrouillée jusqu'à la fin de la transaction, donc
 * deux inscriptions simultanées n'obtiennent jamais le même numéro. Si la
 * transaction est annulée, le numéro est « rendu » (pas de trou).
 * <p>
 * Doit être appelé dans une transaction ouverte pour un établissement :
 * JdbcTemplate utilise alors la même connexion que JPA, sur laquelle
 * l'établissement a été positionné (Row-Level Security).
 */
@Component
public class Compteurs {

    private static final String SUIVANT = """
            insert into compteur (tenant_id, nom, valeur) values (tenant_courant(), ?, 1)
            on conflict (tenant_id, nom) do update set valeur = compteur.valeur + 1
            returning valeur""";

    private final JdbcTemplate jdbc;

    Compteurs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Valeur suivante du compteur (1 au premier appel). */
    public long suivant(String nom) {
        UtilisateurConnecte.etablissementActif();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Les compteurs s'utilisent dans une transaction");
        }
        Long valeur = jdbc.queryForObject(SUIVANT, Long.class, nom);
        if (valeur == null) {
            throw new IllegalStateException("Compteur " + nom + " indisponible");
        }
        return valeur;
    }

    /**
     * Verrou exclusif jusqu'à la fin de la transaction, sur une clé libre
     * (ex. « classe:<id> » pour contrôler un effectif sans risque de dépassement
     * par deux inscriptions simultanées).
     */
    public void verrouiller(String cle) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Un verrou s'utilise dans une transaction");
        }
        jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))", cle);
    }
}
