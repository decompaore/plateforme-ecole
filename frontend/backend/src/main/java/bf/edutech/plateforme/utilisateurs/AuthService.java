package bf.edutech.plateforme.utilisateurs;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import bf.edutech.plateforme.socle.audit.AuditService;
import bf.edutech.plateforme.socle.config.ParametresPlateforme;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.AppareilAEffacerException;
import bf.edutech.plateforme.socle.erreurs.AuthentificationException;
import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.securite.SecuriteProperties;
import bf.edutech.plateforme.socle.telephone.NumeroTelephone;
import bf.edutech.plateforme.socle.tenant.TenantContext;

/**
 * Authentification : connexion, choix de l'établissement, rafraîchissement,
 * déconnexion et changement de mot de passe.
 */
@Service
public class AuthService {

    static final String MESSAGE_ECHEC = "Identifiants invalides ou compte temporairement verrouillé";

    /** Résultat interne ; le jeton de rafraîchissement est transmis au contrôleur pour le cookie. */
    public record ResultatConnexion(String jetonAcces, String jetonSelection, String jetonRafraichissement,
            long expireDansSecondes, EtablissementAccessible etablissementActif,
            List<EtablissementAccessible> etablissements, boolean superAdmin, boolean doitChangerMotDePasse,
            String motifChangementMotDePasse) {
    }

    private static final java.time.ZoneId FUSEAU = java.time.ZoneId.of("Africa/Ouagadougou");
    /** Rôles qui ne sont pas du personnel : pas de renouvellement périodique du mot de passe. */
    private static final java.util.Set<String> HORS_PERSONNEL = java.util.Set.of("PARENT", "ELEVE");

    private final UtilisateurRepository utilisateurs;
    private final JetonRafraichissementRepository jetonsRafraichissement;
    private final AccesEtablissements acces;
    private final ServiceJetons jetons;
    private final PasswordEncoder encodeur;
    private final AuditService audit;
    private final SecuriteProperties securite;
    private final ParametresPlateforme parametres;
    private final Clock horloge;
    private final SessionsAppareils sessions;
    private final String hacheFactice;

    AuthService(UtilisateurRepository utilisateurs, JetonRafraichissementRepository jetonsRafraichissement,
            AccesEtablissements acces, ServiceJetons jetons, PasswordEncoder encodeur, AuditService audit,
            SecuriteProperties securite, ParametresPlateforme parametres, Clock horloge, SessionsAppareils sessions) {
        this.sessions = sessions;
        this.utilisateurs = utilisateurs;
        this.jetonsRafraichissement = jetonsRafraichissement;
        this.acces = acces;
        this.jetons = jetons;
        this.encodeur = encodeur;
        this.audit = audit;
        this.securite = securite;
        this.parametres = parametres;
        this.horloge = horloge;
        // Haché de référence : même durée de vérification que le compte existe ou non
        this.hacheFactice = encodeur.encode("compte-inexistant");
    }

    /** Étape 1 : vérification du téléphone et du mot de passe. */
    public ResultatConnexion connexion(String telephoneSaisi, String motDePasse) {
        String telephone;
        try {
            telephone = NumeroTelephone.normaliser(telephoneSaisi, parametres.indicatifTelephone());
        } catch (IllegalArgumentException e) {
            throw new AuthentificationException(MESSAGE_ECHEC);
        }
        Instant maintenant = horloge.instant();
        Optional<Utilisateur> trouve = utilisateurs.findByTelephone(telephone);
        if (trouve.isEmpty()) {
            encodeur.matches(motDePasse, hacheFactice);
            audit.enregistrerPour(null, "CONNEXION_ECHEC", NumeroTelephone.masquer(telephone),
                    Map.of("raison", "compte inconnu"));
            throw new AuthentificationException(MESSAGE_ECHEC);
        }
        Utilisateur utilisateur = trouve.get();
        if (!utilisateur.isActif() || utilisateur.estVerrouille(maintenant)) {
            audit.enregistrerPour(utilisateur.getId(), "CONNEXION_REFUSEE", null,
                    Map.of("raison", utilisateur.isActif() ? "verrouillé" : "inactif"));
            throw new AuthentificationException(MESSAGE_ECHEC);
        }
        if (!encodeur.matches(motDePasse, utilisateur.getMotDePasseHache())) {
            utilisateur.enregistrerEchec(maintenant, securite.maxEchecsConnexion(), securite.dureeVerrouillage());
            utilisateurs.save(utilisateur);
            audit.enregistrerPour(utilisateur.getId(), "CONNEXION_ECHEC", null, Map.of("raison", "mot de passe"));
            throw new AuthentificationException(MESSAGE_ECHEC);
        }
        utilisateur.enregistrerConnexionReussie(maintenant);
        utilisateurs.save(utilisateur);

        if (utilisateur.isSuperAdmin()) {
            audit.enregistrerPour(utilisateur.getId(), "CONNEXION", "plateforme", null);
            return sessionComplete(utilisateur, null, List.of(), null);
        }
        List<EtablissementAccessible> etablissements = acces.pour(utilisateur.getId());
        if (etablissements.isEmpty() && acces.aDesInvitations(utilisateur.getId())) {
            // Enseignant invité sans autre établissement : session sans établissement,
            // limitée à son compte (/moi) pour répondre aux invitations
            audit.enregistrerPour(utilisateur.getId(), "CONNEXION", "invitations en attente", null);
            return sessionComplete(utilisateur, null, List.of(), null);
        }
        if (etablissements.isEmpty()) {
            audit.enregistrerPour(utilisateur.getId(), "CONNEXION_REFUSEE", null,
                    Map.of("raison", "aucun établissement actif"));
            throw new AuthentificationException("Aucun établissement actif n'est associé à ce compte");
        }
        if (etablissements.size() == 1) {
            EtablissementAccessible unique = etablissements.get(0);
            auditerPour(unique.id(), utilisateur.getId(), "CONNEXION");
            return sessionComplete(utilisateur, unique, etablissements, null);
        }
        audit.enregistrerPour(utilisateur.getId(), "CONNEXION_SELECTION", null,
                Map.of("etablissements", etablissements.size()));
        return new ResultatConnexion(null, jetons.jetonSelection(utilisateur), null,
                securite.dureeJetonSelection().toSeconds(), null, etablissements, false,
                utilisateur.isDoitChangerMotDePasse(), utilisateur.getMotifChangement());
    }

    /**
     * Étape 2 (ou changement d'établissement) : ouvre une session sur l'établissement choisi.
     * Volontairement non transactionnelle : l'audit doit s'écrire dans une transaction
     * ouverte APRÈS avoir fixé l'établissement (voir {@link #auditerPour}).
     */
    public ResultatConnexion choisirEtablissement(UUID utilisateurId, UUID etablissementId,
            String ancienJetonRafraichissement) {
        Utilisateur utilisateur = utilisateurs.findById(utilisateurId)
                .filter(Utilisateur::isActif)
                .orElseThrow(() -> new AuthentificationException(MESSAGE_ECHEC));
        List<EtablissementAccessible> etablissements = acces.pour(utilisateurId);
        EtablissementAccessible choisi = etablissements.stream()
                .filter(e -> e.id().equals(etablissementId))
                .findFirst()
                .orElseThrow(() -> new AccesRefuseException("Établissement non accessible pour ce compte"));
        UUID famillePrecedente = null;
        if (ancienJetonRafraichissement != null) {
            Optional<JetonRafraichissement> ancien = jetonsRafraichissement
                    .findByHache(ServiceJetons.hacher(ancienJetonRafraichissement));
            if (ancien.isPresent()) {
                famillePrecedente = ancien.get().getFamille();
                jetonsRafraichissement.revoquerFamille(famillePrecedente, horloge.instant());
            }
        }
        auditerPour(choisi.id(), utilisateurId, "CHOIX_ETABLISSEMENT");
        return sessionComplete(utilisateur, choisi, etablissements, famillePrecedente);
    }

    /**
     * Rotation du jeton de rafraîchissement. La réutilisation d'un jeton déjà
     * révoqué révoque toute la famille (vol probable) ; cette révocation est
     * conservée malgré l'erreur renvoyée.
     * <p>
     * Exception : un jeton renouvelé il y a moins de quelques secondes
     * ({@code app.securite.grace-rafraichissement}) dont le successeur n'a encore
     * jamais servi. C'est le cas d'une réponse perdue sur un réseau faible : le
     * téléphone n'a jamais reçu le nouveau cookie. Le successeur inutilisé est alors
     * révoqué et un autre est émis, dans la même famille. Un jeton volé rejoué plus
     * tard, ou après que le successeur a servi, reste détecté comme un vol.
     */
    @Transactional(noRollbackFor = AuthentificationException.class)
    public ResultatConnexion rafraichir(String valeur) {
        if (valeur == null || valeur.isBlank()) {
            throw new AuthentificationException("Session expirée");
        }
        Instant maintenant = horloge.instant();
        JetonRafraichissement jeton = jetonsRafraichissement.findByHache(ServiceJetons.hacher(valeur))
                .orElseThrow(() -> new AuthentificationException("Session expirée"));
        // Appareil déconnecté à distance (et, s'il a été perdu, à effacer)
        Optional<SessionAppareil> session = sessions.deFamille(jeton.getFamille());
        if (session.isPresent() && session.get().estFermee()) {
            if (session.get().isEffacementDemande()) {
                audit.enregistrerPour(jeton.getUtilisateurId(), "APPAREIL_EFFACE", session.get().getAppareil(), null);
                throw new AppareilAEffacerException();
            }
            throw new AuthentificationException("Session expirée");
        }
        if (jeton.estRevoque()) {
            Optional<JetonRafraichissement> successeurInutilise = jeton.renouveleRecemment(maintenant,
                    securite.graceRafraichissement())
                            ? jetonsRafraichissement.findById(jeton.getRemplacePar()).filter(j -> !j.estRevoque())
                            : Optional.empty();
            if (successeurInutilise.isEmpty()) {
                jetonsRafraichissement.revoquerFamille(jeton.getFamille(), maintenant);
                sessions.fermer(jeton.getFamille(), SessionsAppareils.VOL);
                audit.enregistrerPour(jeton.getUtilisateurId(), "REUTILISATION_JETON", null,
                        Map.of("famille", jeton.getFamille()));
                throw new AuthentificationException("Session expirée");
            }
            successeurInutilise.get().revoquer(maintenant);
            audit.enregistrerPour(jeton.getUtilisateurId(), "RENOUVELLEMENT_REJOUE", null,
                    Map.of("famille", jeton.getFamille()));
        }
        if (jeton.estExpire(maintenant)) {
            throw new AuthentificationException("Session expirée");
        }
        Utilisateur utilisateur = utilisateurs.findById(jeton.getUtilisateurId())
                .filter(Utilisateur::isActif)
                .orElseThrow(() -> new AuthentificationException("Session expirée"));
        EtablissementAccessible etablissement = null;
        if (jeton.getTenantId() != null) {
            etablissement = acces.trouver(utilisateur.getId(), jeton.getTenantId())
                    .orElseThrow(() -> new AuthentificationException("L'accès à cet établissement a été retiré"));
        }
        exigerRenouvellementSiNouvellePeriode(utilisateur, etablissement);
        ServiceJetons.JetonEmis nouveau = jetons.emettreJetonRafraichissement(utilisateur.getId(),
                jeton.getTenantId(), jeton.getFamille());
        jeton.remplacer(nouveau.id(), maintenant);
        sessions.utiliser(jeton.getFamille());
        return new ResultatConnexion(jetons.jetonAcces(utilisateur, etablissement), null, nouveau.valeur(),
                jetons.dureeAccesEnSecondes(), etablissement, List.of(), utilisateur.isSuperAdmin(),
                utilisateur.isDoitChangerMotDePasse(), utilisateur.getMotifChangement());
    }

    @Transactional
    public void deconnexion(String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return;
        }
        jetonsRafraichissement.findByHache(ServiceJetons.hacher(valeur)).ifPresent(j -> {
            jetonsRafraichissement.revoquerFamille(j.getFamille(), horloge.instant());
            sessions.fermer(j.getFamille(), SessionsAppareils.DECONNEXION);
        });
    }

    @Transactional
    public void changerMotDePasse(UUID utilisateurId, String actuel, String nouveau) {
        Utilisateur utilisateur = utilisateurs.findById(utilisateurId)
                .orElseThrow(() -> new AuthentificationException(MESSAGE_ECHEC));
        if (!encodeur.matches(actuel, utilisateur.getMotDePasseHache())) {
            throw new AuthentificationException("Mot de passe actuel incorrect");
        }
        if (actuel.equals(nouveau)) {
            throw new RegleMetierException("MOT_DE_PASSE_IDENTIQUE", "Le nouveau mot de passe doit être différent");
        }
        if (!nouveau.matches(".*[A-Za-z].*") || !nouveau.matches(".*[0-9].*")) {
            throw new RegleMetierException("MOT_DE_PASSE_FAIBLE",
                    "Le mot de passe doit contenir au moins une lettre et un chiffre");
        }
        utilisateur.changerMotDePasse(encodeur.encode(nouveau), false);
        utilisateurs.save(utilisateur);
        audit.enregistrerPour(utilisateurId, "MOT_DE_PASSE_CHANGE", null, null);
    }

    /** Appareils où le compte est connecté (sessions ouvertes et encore valides), le plus récent d'abord. */
    @Transactional(readOnly = true)
    public List<AppareilVue> appareils(UUID utilisateurId, String jetonCourant) {
        UUID familleCourante = jetonCourant == null || jetonCourant.isBlank() ? null
                : jetonsRafraichissement.findByHache(ServiceJetons.hacher(jetonCourant))
                        .map(JetonRafraichissement::getFamille).orElse(null);
        Map<UUID, String> noms = new java.util.HashMap<>();
        acces.pour(utilisateurId).forEach(e -> noms.put(e.id(), e.nom()));
        Instant limite = horloge.instant().minus(securite.dureeJetonRafraichissement());
        return sessions.ouvertes(utilisateurId).stream()
                .filter(s -> s.getDernierUsage().isAfter(limite))
                .map(s -> new AppareilVue(s.getId(), s.getAppareil(), s.getOuverteLe(), s.getDernierUsage(),
                        s.getTenantId() == null ? null : noms.get(s.getTenantId()),
                        s.getFamille().equals(familleCourante)))
                .toList();
    }

    /**
     * Déconnecte un de ses appareils à distance ; avec {@code effacer} (téléphone perdu ou volé),
     * l'appareil effacera ses données locales au prochain contact avec le serveur.
     */
    @Transactional
    public void fermerAppareil(UUID utilisateurId, UUID sessionId, boolean effacer) {
        SessionAppareil s = sessions.trouver(sessionId).filter(x -> x.getUtilisateurId().equals(utilisateurId))
                .orElseThrow(() -> new bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException(
                        "Appareil introuvable"));
        sessions.fermerADistance(s, effacer, utilisateurId);
        audit.enregistrerPour(utilisateurId, effacer ? "APPAREIL_A_EFFACER" : "APPAREIL_DECONNECTE", s.getAppareil(),
                null);
    }

    public List<EtablissementAccessible> etablissementsDe(UUID utilisateurId) {
        return acces.pour(utilisateurId);
    }

    /**
     * Renouvellement périodique : un membre du personnel dont le mot de passe date d'avant le début
     * de la période en cours (trimestre ou semestre de l'année active) doit en choisir un nouveau
     * avant tout le reste. Les parents et les élèves ne sont pas concernés.
     */
    private void exigerRenouvellementSiNouvellePeriode(Utilisateur utilisateur, EtablissementAccessible etablissement) {
        if (etablissement == null || utilisateur.isSuperAdmin() || utilisateur.isDoitChangerMotDePasse()
                || etablissement.roles().stream().allMatch(HORS_PERSONNEL::contains)) {
            return;
        }
        java.time.LocalDate aujourdhui = java.time.LocalDate.ofInstant(horloge.instant(), FUSEAU);
        acces.debutPeriodeEnCours(etablissement.id(), aujourdhui)
                .filter(debut -> utilisateur.motDePasseChoisiAvant(debut.atStartOfDay(FUSEAU).toInstant()))
                .ifPresent(debut -> {
                    utilisateur.exigerRenouvellement();
                    utilisateurs.save(utilisateur);
                    audit.enregistrerPour(utilisateur.getId(), "MOT_DE_PASSE_A_RENOUVELER", null,
                            Map.of("debutPeriode", debut.toString()));
                });
    }

    private ResultatConnexion sessionComplete(Utilisateur utilisateur, EtablissementAccessible etablissement,
            List<EtablissementAccessible> etablissements, UUID famillePrecedente) {
        exigerRenouvellementSiNouvellePeriode(utilisateur, etablissement);
        UUID tenant = etablissement != null ? etablissement.id() : null;
        ServiceJetons.JetonEmis emis = jetons.emettreJetonRafraichissement(utilisateur.getId(), tenant, null);
        sessions.ouvrir(utilisateur.getId(), emis.famille(), tenant, famillePrecedente);
        String rafraichissement = emis.valeur();
        return new ResultatConnexion(jetons.jetonAcces(utilisateur, etablissement), null, rafraichissement,
                jetons.dureeAccesEnSecondes(), etablissement, etablissements, utilisateur.isSuperAdmin(),
                utilisateur.isDoitChangerMotDePasse(), utilisateur.getMotifChangement());
    }

    /** Audit rattaché à l'établissement (la Row-Level Security l'exige pour l'écriture). */
    private void auditerPour(UUID etablissementId, UUID utilisateurId, String action) {
        TenantContext.executerPour(etablissementId, () -> {
            audit.enregistrerPour(utilisateurId, action, null, null);
            return null;
        });
    }
}
