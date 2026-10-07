package bf.edutech.plateforme.utilisateurs;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.Portee;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.socle.telephone.NumeroTelephone;
import bf.edutech.plateforme.utilisateurs.ComptesService.ResultatReinitialisation;

/**
 * Administrateurs pays (v0.36), nommés par le super administrateur : un ou plusieurs par pays.
 * Un compte administre au plus un pays ; c'est un compte dédié, ni super administrateur ni
 * membre d'un établissement. Le mot de passe provisoire s'affiche une seule fois.
 */
@Service
public class AdministrateursPaysService {

    public record AdministrateurPaysVue(UUID utilisateurId, String nom, String prenoms, String telephone,
            boolean actif, Instant nommeLe, Instant derniereConnexion, Instant verrouilleJusqua,
            boolean motDePasseProvisoire) {
    }

    public record ResultatNomination(AdministrateurPaysVue administrateur, String motDePasseTemporaire) {
    }

    private final JdbcTemplate jdbc;
    private final UtilisateurRepository utilisateurs;
    private final JetonRafraichissementRepository jetons;
    private final SessionsAppareils sessions;
    private final AccesEtablissements acces;
    private final PasswordEncoder encodeur;
    private final AuditService audit;
    private final Clock horloge;

    AdministrateursPaysService(JdbcTemplate jdbc, UtilisateurRepository utilisateurs, JetonRafraichissementRepository jetons,
            SessionsAppareils sessions, AccesEtablissements acces, PasswordEncoder encodeur, AuditService audit,
            Clock horloge) {
        this.jdbc = jdbc;
        this.utilisateurs = utilisateurs;
        this.jetons = jetons;
        this.sessions = sessions;
        this.acces = acces;
        this.encodeur = encodeur;
        this.audit = audit;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<AdministrateurPaysVue> lister(UUID paysId) {
        Portee.exigerSuperAdmin();
        exigerPays(paysId);
        Instant maintenant = horloge.instant();
        return jdbc.query("""
                select a.utilisateur_id, a.actif, a.nomme_le from administrateur_pays a
                join utilisateur u on u.id = a.utilisateur_id
                where a.pays_id = ? order by a.actif desc, u.nom, u.prenoms""", (l, n) -> new Object[] {
                l.getObject(1, UUID.class), l.getBoolean(2), l.getObject(3, OffsetDateTime.class).toInstant() }, paysId)
                .stream().map(r -> vue(utilisateurs.findById((UUID) r[0]).orElseThrow(), (boolean) r[1], (Instant) r[2],
                        maintenant))
                .toList();
    }

    /** Nomme un administrateur pays ; crée son compte (mot de passe provisoire) s'il n'en a pas. */
    @Transactional
    public ResultatNomination nommer(UUID paysId, String telephoneSaisi, String nom, String prenoms) {
        Portee.exigerSuperAdmin();
        Object[] pays = exigerPays(paysId);
        String telephone = NumeroTelephone.normaliser(telephoneSaisi, (String) pays[1], (int) pays[2]);
        String provisoire = null;
        Utilisateur u = utilisateurs.findByTelephone(telephone).orElse(null);
        if (u == null) {
            provisoire = GenerateurMotDePasse.temporaire();
            // saveAndFlush : la ligne doit exister en base avant l'insertion JDBC qui la référence
            u = utilisateurs.saveAndFlush(new Utilisateur(telephone, nom.trim().toUpperCase(), prenoms.trim(),
                    encodeur.encode(provisoire), false));
        } else {
            if (u.isSuperAdmin()) {
                throw new RegleMetierException("COMPTE_PLATEFORME", "Ce numéro est celui d'un super administrateur");
            }
            if (!acces.pour(u.getId()).isEmpty()) {
                throw new RegleMetierException("COMPTE_ETABLISSEMENT",
                        "Ce numéro a déjà un compte dans un établissement : un administrateur pays a un compte dédié");
            }
            List<UUID> autre = jdbc.queryForList(
                    "select pays_id from administrateur_pays where utilisateur_id = ? and actif and pays_id <> ?",
                    UUID.class, u.getId(), paysId);
            if (!autre.isEmpty()) {
                throw new RegleMetierException("ADMIN_AUTRE_PAYS", "Cette personne administre déjà un autre pays");
            }
        }
        jdbc.update("""
                insert into administrateur_pays (utilisateur_id, pays_id, actif, nomme_le, nomme_par)
                values (?, ?, true, now(), (select id from utilisateur where id = ?))
                on conflict (utilisateur_id) do update set pays_id = excluded.pays_id, actif = true, nomme_le = now(),
                    nomme_par = excluded.nomme_par, retire_le = null""", u.getId(), paysId, UtilisateurConnecte.id());
        audit.enregistrer("ADMIN_PAYS_NOMME", (String) pays[0],
                Map.of("utilisateur", u.getId(), "nouveauCompte", provisoire != null));
        return new ResultatNomination(vue(u, true, horloge.instant(), horloge.instant()), provisoire);
    }

    /** Retire ses droits à un administrateur pays et ferme ses sessions. */
    @Transactional
    public void retirer(UUID paysId, UUID utilisateurId) {
        Portee.exigerSuperAdmin();
        Object[] pays = exigerPays(paysId);
        int n = jdbc.update("update administrateur_pays set actif = false, retire_le = now() where utilisateur_id = ? and pays_id = ?",
                utilisateurId, paysId);
        if (n == 0) {
            throw new RessourceIntrouvableException("Administrateur pays introuvable");
        }
        jetons.revoquerTout(utilisateurId, horloge.instant());
        sessions.fermerTout(utilisateurId, SessionsAppareils.DECONNEXION);
        audit.enregistrer("ADMIN_PAYS_RETIRE", (String) pays[0], Map.of("utilisateur", utilisateurId));
    }

    /** Mot de passe oublié : nouveau mot de passe provisoire (affiché une fois), sessions fermées. */
    @Transactional
    public ResultatReinitialisation reinitialiser(UUID paysId, UUID utilisateurId) {
        Portee.exigerSuperAdmin();
        Object[] pays = exigerPays(paysId);
        Integer actif = jdbc.queryForObject(
                "select count(*) from administrateur_pays where utilisateur_id = ? and pays_id = ? and actif", Integer.class,
                utilisateurId, paysId);
        if (actif == null || actif == 0) {
            throw new RessourceIntrouvableException("Administrateur pays introuvable");
        }
        Utilisateur u = utilisateurs.findById(utilisateurId)
                .orElseThrow(() -> new RessourceIntrouvableException("Compte introuvable"));
        String provisoire = GenerateurMotDePasse.temporaire();
        u.reinitialiserMotDePasse(encodeur.encode(provisoire));
        utilisateurs.save(u);
        jetons.revoquerTout(u.getId(), horloge.instant());
        sessions.fermerTout(u.getId(), SessionsAppareils.REINITIALISATION);
        audit.enregistrer("MOT_DE_PASSE_REINITIALISE", (String) pays[0], Map.of("utilisateur", u.getId()));
        return new ResultatReinitialisation(u.getId(), u.getNom(), u.getPrenoms(), u.getTelephone(), provisoire, 0);
    }

    /** Code, indicatif et longueur des numéros du pays ; 404 s'il n'existe pas. */
    private Object[] exigerPays(UUID paysId) {
        return jdbc.query("select code, indicatif_telephone, longueur_numero from pays where id = ?",
                (l, n) -> new Object[] { l.getString(1), l.getString(2), l.getInt(3) }, paysId).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Pays introuvable"));
    }

    private static AdministrateurPaysVue vue(Utilisateur u, boolean actif, Instant nommeLe, Instant maintenant) {
        return new AdministrateurPaysVue(u.getId(), u.getNom(), u.getPrenoms(), u.getTelephone(), actif, nommeLe,
                u.getDerniereConnexion(), u.estVerrouille(maintenant) ? u.getVerrouilleJusqua() : null,
                u.isDoitChangerMotDePasse());
    }
}
