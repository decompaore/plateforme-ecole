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
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;
import bf.edutech.plateforme.socle.telephone.NumeroTelephone;
import bf.edutech.plateforme.territoire.PorteeTerritoire;
import bf.edutech.plateforme.utilisateurs.ComptesService.ResultatReinitialisation;

/**
 * Comptes des directions (v0.37), nommés par l'administrateur du pays ou par le super
 * administrateur : un ou plusieurs par direction. Un compte appartient à une seule direction ;
 * c'est un compte dédié (ni administration de la plateforme, ni membre d'un établissement) qui
 * ne voit que des nombres. Le mot de passe provisoire s'affiche une seule fois.
 */
@Service
public class AdministrateursDirectionService {

    public record CompteDirectionVue(UUID utilisateurId, String nom, String prenoms, String telephone,
            boolean actif, Instant nommeLe, Instant derniereConnexion, Instant verrouilleJusqua,
            boolean motDePasseProvisoire) {
    }

    public record ResultatNominationDirection(CompteDirectionVue compte, String motDePasseTemporaire) {
    }

    /** Code de la direction, indicatif et longueur des numéros de son pays. */
    private record Direction(String code, String indicatif, int longueur) {
    }

    private final JdbcTemplate jdbc;
    private final UtilisateurRepository utilisateurs;
    private final JetonRafraichissementRepository jetons;
    private final SessionsAppareils sessions;
    private final AccesEtablissements acces;
    private final AdministrateursPays administrateursPays;
    private final PorteeTerritoire portee;
    private final PasswordEncoder encodeur;
    private final AuditService audit;
    private final Clock horloge;

    AdministrateursDirectionService(JdbcTemplate jdbc, UtilisateurRepository utilisateurs,
            JetonRafraichissementRepository jetons, SessionsAppareils sessions, AccesEtablissements acces,
            AdministrateursPays administrateursPays, PorteeTerritoire portee, PasswordEncoder encodeur,
            AuditService audit, Clock horloge) {
        this.jdbc = jdbc;
        this.utilisateurs = utilisateurs;
        this.jetons = jetons;
        this.sessions = sessions;
        this.acces = acces;
        this.administrateursPays = administrateursPays;
        this.portee = portee;
        this.encodeur = encodeur;
        this.audit = audit;
        this.horloge = horloge;
    }

    @Transactional(readOnly = true)
    public List<CompteDirectionVue> lister(UUID directionId) {
        exigerDirection(directionId);
        Instant maintenant = horloge.instant();
        return jdbc.query("""
                select a.utilisateur_id, a.actif, a.nomme_le from administrateur_direction a
                join utilisateur u on u.id = a.utilisateur_id
                where a.direction_id = ? order by a.actif desc, u.nom, u.prenoms""", (l, n) -> new Object[] {
                l.getObject(1, UUID.class), l.getBoolean(2), l.getObject(3, OffsetDateTime.class).toInstant() },
                directionId)
                .stream().map(r -> vue(utilisateurs.findById((UUID) r[0]).orElseThrow(), (boolean) r[1], (Instant) r[2],
                        maintenant))
                .toList();
    }

    /** Nomme un compte pour la direction ; crée le compte (mot de passe provisoire) s'il n'existe pas. */
    @Transactional
    public ResultatNominationDirection nommer(UUID directionId, String telephoneSaisi, String nom, String prenoms) {
        Direction direction = exigerDirection(directionId);
        String telephone = NumeroTelephone.normaliser(telephoneSaisi, direction.indicatif(), direction.longueur());
        String provisoire = null;
        Utilisateur u = utilisateurs.findByTelephone(telephone).orElse(null);
        if (u == null) {
            provisoire = GenerateurMotDePasse.temporaire();
            // saveAndFlush : la ligne doit exister en base avant l'insertion JDBC qui la référence
            u = utilisateurs.saveAndFlush(new Utilisateur(telephone, nom.trim().toUpperCase(), prenoms.trim(),
                    encodeur.encode(provisoire), false));
        } else {
            if (u.isSuperAdmin() || administrateursPays.de(u.getId()).isPresent()) {
                throw new RegleMetierException("COMPTE_PLATEFORME",
                        "Ce numéro est celui d'un compte d'administration de la plateforme");
            }
            if (!acces.pour(u.getId()).isEmpty()) {
                throw new RegleMetierException("COMPTE_ETABLISSEMENT",
                        "Ce numéro a déjà un compte dans un établissement : une direction a un compte dédié");
            }
            List<UUID> autre = jdbc.queryForList(
                    "select direction_id from administrateur_direction where utilisateur_id = ? and actif and direction_id <> ?",
                    UUID.class, u.getId(), directionId);
            if (!autre.isEmpty()) {
                throw new RegleMetierException("COMPTE_AUTRE_DIRECTION",
                        "Ce numéro est déjà celui du compte d'une autre direction");
            }
        }
        jdbc.update("""
                insert into administrateur_direction (utilisateur_id, direction_id, actif, nomme_le, nomme_par)
                values (?, ?, true, now(), (select id from utilisateur where id = ?))
                on conflict (utilisateur_id) do update set direction_id = excluded.direction_id, actif = true,
                    nomme_le = now(), nomme_par = excluded.nomme_par, retire_le = null""",
                u.getId(), directionId, UtilisateurConnecte.id());
        audit.enregistrer("COMPTE_DIRECTION_NOMME", direction.code(),
                Map.of("utilisateur", u.getId(), "nouveauCompte", provisoire != null));
        return new ResultatNominationDirection(vue(u, true, horloge.instant(), horloge.instant()), provisoire);
    }

    /** Retire ses droits à un compte de direction et ferme ses sessions. */
    @Transactional
    public void retirer(UUID directionId, UUID utilisateurId) {
        Direction direction = exigerDirection(directionId);
        int n = jdbc.update("""
                update administrateur_direction set actif = false, retire_le = now()
                where utilisateur_id = ? and direction_id = ? and actif""", utilisateurId, directionId);
        if (n == 0) {
            throw new RessourceIntrouvableException("Compte de direction introuvable");
        }
        jetons.revoquerTout(utilisateurId, horloge.instant());
        sessions.fermerTout(utilisateurId, SessionsAppareils.DECONNEXION);
        audit.enregistrer("COMPTE_DIRECTION_RETIRE", direction.code(), Map.of("utilisateur", utilisateurId));
    }

    /** Mot de passe oublié : nouveau mot de passe provisoire (affiché une fois), sessions fermées. */
    @Transactional
    public ResultatReinitialisation reinitialiser(UUID directionId, UUID utilisateurId) {
        Direction direction = exigerDirection(directionId);
        Integer actif = jdbc.queryForObject(
                "select count(*) from administrateur_direction where utilisateur_id = ? and direction_id = ? and actif",
                Integer.class, utilisateurId, directionId);
        if (actif == null || actif == 0) {
            throw new RessourceIntrouvableException("Compte de direction introuvable");
        }
        Utilisateur u = utilisateurs.findById(utilisateurId)
                .orElseThrow(() -> new RessourceIntrouvableException("Compte introuvable"));
        String provisoire = GenerateurMotDePasse.temporaire();
        u.reinitialiserMotDePasse(encodeur.encode(provisoire));
        utilisateurs.save(u);
        jetons.revoquerTout(u.getId(), horloge.instant());
        sessions.fermerTout(u.getId(), SessionsAppareils.REINITIALISATION);
        audit.enregistrer("MOT_DE_PASSE_REINITIALISE", direction.code(), Map.of("utilisateur", u.getId()));
        return new ResultatReinitialisation(u.getId(), u.getNom(), u.getPrenoms(), u.getTelephone(), provisoire, 0);
    }

    /** 404 si la direction n'existe pas ; 403 si elle est hors du pays de l'administrateur pays. */
    private Direction exigerDirection(UUID directionId) {
        Direction d = jdbc.query("""
                select d.code, p.indicatif_telephone, p.longueur_numero from direction d
                join ministere m on m.id = d.ministere_id join pays p on p.id = m.pays_id where d.id = ?""",
                (l, n) -> new Direction(l.getString(1), l.getString(2), l.getInt(3)), directionId).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Direction introuvable"));
        portee.verifierDirection(directionId);
        return d;
    }

    private static CompteDirectionVue vue(Utilisateur u, boolean actif, Instant nommeLe, Instant maintenant) {
        return new CompteDirectionVue(u.getId(), u.getNom(), u.getPrenoms(), u.getTelephone(), actif, nommeLe,
                u.getDerniereConnexion(), u.estVerrouille(maintenant) ? u.getVerrouilleJusqua() : null,
                u.isDoitChangerMotDePasse());
    }
}
