package bf.edutech.plateforme.utilisateurs;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Sessions par appareil : ouverture à la connexion, dernier usage à chaque renouvellement,
 * fermeture (déconnexion, à distance, avec effacement, réinitialisation, vol détecté).
 * À appeler dans une transaction.
 */
@Component
class SessionsAppareils {

    static final String DECONNEXION = "DECONNEXION";
    static final String A_DISTANCE = "A_DISTANCE";
    static final String EFFACEMENT = "EFFACEMENT";
    static final String REINITIALISATION = "REINITIALISATION";
    static final String VOL = "VOL";

    private final SessionAppareilRepository sessions;
    private final JetonRafraichissementRepository jetons;
    private final Clock horloge;

    SessionsAppareils(SessionAppareilRepository sessions, JetonRafraichissementRepository jetons, Clock horloge) {
        this.sessions = sessions;
        this.jetons = jetons;
        this.horloge = horloge;
    }

    /** Nouvelle connexion ; si une famille précédente est donnée (changement d'établissement), même appareil. */
    void ouvrir(UUID utilisateurId, UUID famille, UUID tenantId, UUID famillePrecedente) {
        Instant maintenant = horloge.instant();
        Optional<SessionAppareil> precedente = famillePrecedente == null ? Optional.empty()
                : sessions.findByFamille(famillePrecedente).filter(s -> !s.estFermee()
                        && s.getUtilisateurId().equals(utilisateurId));
        if (precedente.isPresent()) {
            precedente.get().changerFamille(famille, tenantId, maintenant);
            sessions.save(precedente.get());
            return;
        }
        sessions.save(new SessionAppareil(utilisateurId, famille, tenantId, appareilCourant(), maintenant));
    }

    Optional<SessionAppareil> deFamille(UUID famille) {
        return sessions.findByFamille(famille);
    }

    void utiliser(UUID famille) {
        sessions.findByFamille(famille).ifPresent(s -> s.utiliser(horloge.instant()));
    }

    void fermer(UUID famille, String motif) {
        sessions.findByFamille(famille).ifPresent(s -> s.fermer(motif, false, null, horloge.instant()));
    }

    /** Ferme une session : ses jetons sont révoqués ; avec effacement, l'appareil s'effacera au prochain contact. */
    void fermerADistance(SessionAppareil s, boolean effacer, UUID par) {
        Instant maintenant = horloge.instant();
        s.fermer(effacer ? EFFACEMENT : A_DISTANCE, effacer, par, maintenant);
        sessions.save(s);
        jetons.revoquerFamille(s.getFamille(), maintenant);
    }

    /** Toutes les sessions ouvertes d'un compte (réinitialisation du mot de passe). */
    void fermerTout(UUID utilisateurId, String motif) {
        Instant maintenant = horloge.instant();
        sessions.findByUtilisateurIdAndFermeeLeIsNullOrderByDernierUsageDesc(utilisateurId)
                .forEach(s -> s.fermer(motif, false, null, maintenant));
    }

    List<SessionAppareil> ouvertes(UUID utilisateurId) {
        return sessions.findByUtilisateurIdAndFermeeLeIsNullOrderByDernierUsageDesc(utilisateurId);
    }

    /** Nombre d'appareils connectés par compte dans cet établissement. */
    java.util.Map<UUID, Integer> compterParCompte(UUID tenantId, Instant depuis) {
        java.util.Map<UUID, Integer> n = new java.util.HashMap<>();
        for (Object[] l : sessions.compterParCompte(tenantId, depuis)) {
            n.put((UUID) l[0], ((Number) l[1]).intValue());
        }
        return n;
    }

    Optional<SessionAppareil> trouver(UUID id) {
        return sessions.findById(id);
    }

    // ------------------------------------------------------------------ appareil

    private static String appareilCourant() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributs)) {
            return "Appareil non identifié";
        }
        HttpServletRequest requete = attributs.getRequest();
        return decrire(requete.getHeader("User-Agent"));
    }

    /** « Téléphone Android · Chrome », « iPhone · Safari », « Ordinateur Windows · Edge »… */
    static String decrire(String agent) {
        if (agent == null || agent.isBlank()) {
            return "Appareil non identifié";
        }
        String a = agent.toLowerCase(Locale.ROOT);
        String appareil;
        if (a.contains("iphone")) {
            appareil = "iPhone";
        } else if (a.contains("ipad")) {
            appareil = "iPad";
        } else if (a.contains("android")) {
            appareil = a.contains("mobile") ? "Téléphone Android" : "Tablette Android";
        } else if (a.contains("windows")) {
            appareil = "Ordinateur Windows";
        } else if (a.contains("mac os")) {
            appareil = "Mac";
        } else if (a.contains("linux")) {
            appareil = "Ordinateur Linux";
        } else {
            appareil = "Appareil";
        }
        String navigateur;
        if (a.contains("edg/")) {
            navigateur = "Edge";
        } else if (a.contains("opr/") || a.contains("opera")) {
            navigateur = "Opera";
        } else if (a.contains("samsungbrowser")) {
            navigateur = "Samsung Internet";
        } else if (a.contains("firefox") || a.contains("fxios")) {
            navigateur = "Firefox";
        } else if (a.contains("chrome") || a.contains("crios")) {
            navigateur = "Chrome";
        } else if (a.contains("safari")) {
            navigateur = "Safari";
        } else {
            navigateur = null;
        }
        String texte = navigateur == null ? appareil : appareil + " · " + navigateur;
        return texte.length() > 120 ? texte.substring(0, 120) : texte;
    }
}
