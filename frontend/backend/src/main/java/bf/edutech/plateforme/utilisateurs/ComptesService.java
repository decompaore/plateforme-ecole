package bf.edutech.plateforme.utilisateurs;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.notifications.NotificationsService;
import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/**
 * Contrôle des comptes de l'établissement actif : liste des comptes (rôles, dernière connexion,
 * verrouillage), réinitialisation du mot de passe en cas d'oubli, déverrouillage.
 * <p>
 * Une personne n'a qu'un compte sur la plateforme : la réinitialisation vaut pour tous les
 * établissements où elle a un rôle. Le mot de passe provisoire n'est renvoyé qu'une fois, à
 * l'administrateur qui le transmet ; la personne reçoit un SMS qui l'informe de la
 * réinitialisation (sans le mot de passe) et devra le changer à sa prochaine connexion.
 */
@Service
public class ComptesService {

    private static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");
    private static final DateTimeFormatter DATE_HEURE = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH'h'mm");

    /**
     * Compte d'une personne ayant (ou ayant eu) un rôle dans l'établissement ; {@code motifChangement} :
     * PROVISOIRE ou RENOUVELLEMENT (nouvelle période) quand un nouveau mot de passe est demandé.
     */
    public record CompteVue(UUID utilisateurId, String nom, String prenoms, String telephone, List<Role> roles,
            List<Role> rolesRetires, boolean actif, Instant derniereConnexion, Instant verrouilleJusqua,
            boolean motDePasseProvisoire, boolean moi, Instant motDePasseChangeLe, String motifChangement,
            int appareilsConnectes) {
    }

    /** Résultat d'une réinitialisation : le mot de passe provisoire n'est renvoyé qu'ici. */
    public record ResultatReinitialisation(UUID utilisateurId, String nom, String prenoms, String telephone,
            String motDePasseTemporaire, int autresEtablissements) {
    }

    private final MembreEtablissementRepository membres;
    private final UtilisateurRepository utilisateurs;
    private final JetonRafraichissementRepository jetons;
    private final AccesEtablissements acces;
    private final PasswordEncoder encodeur;
    private final AuditService audit;
    private final NotificationsService notifications;
    private final Clock horloge;
    private final SessionsAppareils sessions;
    private final bf.edutech.plateforme.socle.securite.SecuriteProperties securite;

    ComptesService(MembreEtablissementRepository membres, UtilisateurRepository utilisateurs,
            JetonRafraichissementRepository jetons, AccesEtablissements acces, PasswordEncoder encodeur,
            AuditService audit, NotificationsService notifications, Clock horloge, SessionsAppareils sessions,
            bf.edutech.plateforme.socle.securite.SecuriteProperties securite) {
        this.sessions = sessions;
        this.securite = securite;
        this.membres = membres;
        this.utilisateurs = utilisateurs;
        this.jetons = jetons;
        this.acces = acces;
        this.encodeur = encodeur;
        this.audit = audit;
        this.notifications = notifications;
        this.horloge = horloge;
    }

    /** Tous les comptes de l'établissement, actifs d'abord, par nom. */
    @Transactional(readOnly = true)
    public List<CompteVue> lister() {
        UtilisateurConnecte.etablissementActif();
        UUID moi = UtilisateurConnecte.idSiConnecte().orElse(null);
        Map<UUID, List<MembreVue>> parCompte = membres.listerAvecIdentite().stream()
                .collect(Collectors.groupingBy(MembreVue::utilisateurId, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, Utilisateur> comptes = utilisateurs.findAllById(parCompte.keySet()).stream()
                .collect(Collectors.toMap(Utilisateur::getId, Function.identity()));
        Instant maintenant = horloge.instant();
        Map<UUID, Integer> appareils = sessions.compterParCompte(UtilisateurConnecte.etablissementActif(),
                maintenant.minus(securite.dureeJetonRafraichissement()));
        return parCompte.entrySet().stream().filter(e -> comptes.containsKey(e.getKey())).map(e -> {
            Utilisateur u = comptes.get(e.getKey());
            List<Role> actifs = e.getValue().stream().filter(MembreVue::actif).map(MembreVue::role).distinct().sorted().toList();
            List<Role> retires = e.getValue().stream().filter(m -> !m.actif()).map(MembreVue::role)
                    .filter(r -> !actifs.contains(r)).distinct().sorted().toList();
            return new CompteVue(u.getId(), u.getNom(), u.getPrenoms(), u.getTelephone(), actifs, retires,
                    !actifs.isEmpty(), u.getDerniereConnexion(), u.estVerrouille(maintenant) ? u.getVerrouilleJusqua() : null,
                    u.isDoitChangerMotDePasse(), u.getId().equals(moi), u.getMotDePasseChangeLe(), u.getMotifChangement(),
                    appareils.getOrDefault(u.getId(), 0));
        }).sorted(Comparator.comparing((CompteVue c) -> !c.actif()).thenComparing(CompteVue::nom)
                .thenComparing(CompteVue::prenoms)).toList();
    }

    /** Réinitialisation par l'administrateur de l'établissement. */
    @Transactional
    public ResultatReinitialisation reinitialiser(UUID utilisateurId) {
        Utilisateur u = chargerMembre(utilisateurId, false);
        return reinitialiser(u);
    }

    /**
     * Réinitialisation d'un administrateur d'établissement par le super administrateur ; à appeler
     * dans le contexte de cet établissement.
     */
    @Transactional
    public ResultatReinitialisation reinitialiserAdministrateur(UUID utilisateurId) {
        Utilisateur u = chargerMembre(utilisateurId, true);
        return reinitialiser(u);
    }

    /** Administrateurs actifs de l'établissement du contexte (page Plateforme). */
    @Transactional(readOnly = true)
    public List<CompteVue> administrateurs() {
        UtilisateurConnecte.etablissementActif();
        Instant maintenant = horloge.instant();
        List<MembreVue> admins = membres.listerAvecIdentite().stream()
                .filter(m -> m.actif() && m.role() == Role.ADMIN_ECOLE).toList();
        Map<UUID, Utilisateur> comptes = utilisateurs.findAllById(admins.stream().map(MembreVue::utilisateurId).toList())
                .stream().collect(Collectors.toMap(Utilisateur::getId, Function.identity()));
        return admins.stream().filter(m -> comptes.containsKey(m.utilisateurId())).map(m -> {
            Utilisateur u = comptes.get(m.utilisateurId());
            return new CompteVue(u.getId(), u.getNom(), u.getPrenoms(), u.getTelephone(), List.of(Role.ADMIN_ECOLE),
                    List.of(), true, u.getDerniereConnexion(), u.estVerrouille(maintenant) ? u.getVerrouilleJusqua() : null,
                    u.isDoitChangerMotDePasse(), false, u.getMotDePasseChangeLe(), u.getMotifChangement(), 0);
        }).toList();
    }

    /** Lève le verrouillage dû à trop d'essais de connexion, sans changer le mot de passe. */
    @Transactional
    public CompteVue deverrouiller(UUID utilisateurId) {
        Utilisateur u = chargerMembre(utilisateurId, false);
        u.deverrouiller();
        utilisateurs.save(u);
        audit.enregistrer("COMPTE_DEVERROUILLE", u.getId().toString(), Map.of("utilisateur", u.getId()));
        return lister().stream().filter(c -> c.utilisateurId().equals(u.getId())).findFirst().orElseThrow();
    }

    private ResultatReinitialisation reinitialiser(Utilisateur u) {
        String provisoire = GenerateurMotDePasse.temporaire();
        u.reinitialiserMotDePasse(encodeur.encode(provisoire));
        utilisateurs.save(u);
        Instant maintenant = horloge.instant();
        int sessionsFermees = jetons.revoquerTout(u.getId(), maintenant);
        sessions.fermerTout(u.getId(), SessionsAppareils.REINITIALISATION);
        audit.enregistrer("MOT_DE_PASSE_REINITIALISE", u.getId().toString(),
                Map.of("utilisateur", u.getId(), "sessionsFermees", sessionsFermees));
        notifications.planifierSms("mdp-reinitialise-" + UUID.randomUUID(), u.getTelephone(), "FR",
                notifications.nomEtablissement() + " : le mot de passe de votre compte a été réinitialisé le "
                        + DATE_HEURE.format(maintenant.atZone(FUSEAU))
                        + ". Un mot de passe provisoire vous est remis par l'établissement. "
                        + "Si vous n'avez rien demandé, contactez l'administration.");
        int autres = (int) acces.pour(u.getId()).stream()
                .filter(e -> !e.id().equals(UtilisateurConnecte.etablissementActif())).count();
        return new ResultatReinitialisation(u.getId(), u.getNom(), u.getPrenoms(), u.getTelephone(), provisoire, autres);
    }

    /**
     * Téléphone perdu ou volé, ou personne qui quitte l'établissement : déconnecte tous ses appareils
     * connectés à cet établissement ; avec {@code effacer}, chacun effacera ses données locales au
     * prochain contact. Renvoie le nombre d'appareils déconnectés.
     */
    @Transactional
    public int fermerAppareils(UUID utilisateurId, boolean effacer) {
        Utilisateur u = chargerMembre(utilisateurId, false);
        UUID ecole = UtilisateurConnecte.etablissementActif();
        List<SessionAppareil> ouvertes = sessions.ouvertes(u.getId()).stream()
                .filter(s -> ecole.equals(s.getTenantId())).toList();
        UUID par = UtilisateurConnecte.id();
        ouvertes.forEach(s -> sessions.fermerADistance(s, effacer, par));
        audit.enregistrer(effacer ? "APPAREILS_A_EFFACER" : "APPAREILS_DECONNECTES", u.getId().toString(),
                Map.of("utilisateur", u.getId(), "appareils", ouvertes.size()));
        return ouvertes.size();
    }

    /** Compte ayant un rôle actif dans l'établissement (administrateur seulement si demandé). */
    private Utilisateur chargerMembre(UUID utilisateurId, boolean administrateurSeulement) {
        UtilisateurConnecte.etablissementActif();
        if (!administrateurSeulement && utilisateurId.equals(UtilisateurConnecte.idSiConnecte().orElse(null))) {
            throw new RegleMetierException("MON_PROPRE_COMPTE",
                    "Pour votre propre compte, utilisez « Changer mon mot de passe »");
        }
        List<MembreEtablissement> roles = membres.findByUtilisateurIdAndActifTrue(utilisateurId);
        if (roles.isEmpty() || administrateurSeulement && roles.stream().noneMatch(m -> m.getRole() == Role.ADMIN_ECOLE)) {
            throw new RessourceIntrouvableException(administrateurSeulement
                    ? "Ce compte n'est pas administrateur de l'établissement"
                    : "Ce compte n'a aucun rôle actif dans l'établissement");
        }
        Utilisateur u = utilisateurs.findById(utilisateurId)
                .orElseThrow(() -> new RessourceIntrouvableException("Compte introuvable"));
        if (u.isSuperAdmin()) {
            throw new AccesRefuseException("Les comptes de la plateforme ne se gèrent pas depuis un établissement");
        }
        return u;
    }
}
