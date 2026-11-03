package bf.edutech.plateforme.notifications;

/**
 * Remise d'une notification à un appareil par son service push (Google, Mozilla, Apple…).
 * L'implémentation par défaut est {@link PasserellePushHttp} ; les tests la remplacent.
 */
public interface PasserellePush {

    enum Resultat {
        /** Accepté par le service push. */
        REMIS,
        /** Abonnement expiré ou révoqué (404, 410) : l'appareil est retiré. */
        EXPIRE,
        /** Service indisponible ou refus passager : nouvelle tentative plus tard. */
        ERREUR
    }

    record Appareil(String endpoint, String p256dh, String auth) {
    }

    Resultat envoyer(Appareil appareil, byte[] messageJson);
}
