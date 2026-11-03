package bf.edutech.plateforme.notifications;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Envoi Web Push par HTTP (RFC 8030), message chiffré (RFC 8291) et serveur identifié par VAPID (RFC 8292). */
@Component
class PasserellePushHttp implements PasserellePush {

    private static final Logger LOG = LoggerFactory.getLogger(PasserellePushHttp.class);

    private final PushProperties proprietes;
    private final Clock horloge;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile WebPush webPush;

    PasserellePushHttp(PushProperties proprietes, Clock horloge) {
        this.proprietes = proprietes;
        this.horloge = horloge;
    }

    WebPush webPush() {
        WebPush w = webPush;
        if (w == null) {
            w = new WebPush(proprietes.clePublique(), proprietes.clePrivee(), proprietes.sujet());
            webPush = w;
        }
        return w;
    }

    @Override
    public Resultat envoyer(Appareil appareil, byte[] messageJson) {
        WebPush.Requete r;
        try {
            r = webPush().preparer(appareil.endpoint(), appareil.p256dh(), appareil.auth(), messageJson, horloge.instant());
        } catch (IllegalArgumentException e) {
            LOG.info("Abonnement push inutilisable : {}", e.getMessage());
            return Resultat.EXPIRE;
        }
        HttpRequest requete = HttpRequest.newBuilder(r.endpoint())
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", r.autorisation())
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", String.valueOf(proprietes.dureeVie().toSeconds()))
                .header("Urgency", "high")
                .POST(HttpRequest.BodyPublishers.ofByteArray(r.corps()))
                .build();
        try {
            int code = client.send(requete, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code >= 200 && code < 300) {
                return Resultat.REMIS;
            }
            if (code == 404 || code == 410) {
                LOG.info("Service push : abonnement refusé ({}) pour {}", code, r.endpoint().getHost());
                return Resultat.EXPIRE;
            }
            LOG.warn("Service push {} : réponse {}", r.endpoint().getHost(), code);
            return Resultat.ERREUR;
        } catch (IOException e) {
            LOG.warn("Service push {} injoignable : {}", r.endpoint().getHost(), e.getMessage());
            return Resultat.ERREUR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Resultat.ERREUR;
        }
    }
}
