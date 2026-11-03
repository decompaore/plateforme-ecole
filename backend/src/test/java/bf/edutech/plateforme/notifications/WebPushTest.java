package bf.edutech.plateforme.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

/**
 * Web Push : le message chiffré pour l'appareil se déchiffre côté appareil selon la RFC 8291
 * (déchiffrement écrit ici indépendamment), et le jeton VAPID est signé par la clé du serveur.
 */
class WebPushTest {

    @Test
    void leMessageSeDechiffreCoteAppareilEtLeJetonVapidEstValide() throws Exception {
        String[] vapid = WebPush.genererCles();
        WebPush wp = new WebPush(vapid[0], vapid[1], "mailto:test@exemple.bf");

        // L'appareil : sa paire de clés et son secret d'authentification
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair appareil = g.generateKeyPair();
        byte[] cleAppareil = WebPush.nonCompresse((ECPublicKey) appareil.getPublic());
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);

        byte[] message = "{\"notification\":{\"title\":\"Absence d'Awa\",\"body\":\"Awa était absente…\"}}"
                .getBytes(StandardCharsets.UTF_8);
        WebPush.Requete r = wp.preparer("https://fcm.googleapis.com/fcm/send/abc123", WebPush.B64.encodeToString(cleAppareil),
                WebPush.B64.encodeToString(auth), message, Instant.parse("2026-11-03T10:00:00Z"));

        // En-tête aes128gcm : sel (16), taille d'enregistrement (4), longueur de clé (1), clé du serveur (65)
        ByteBuffer b = ByteBuffer.wrap(r.corps());
        byte[] sel = new byte[16];
        b.get(sel);
        assertThat(b.getInt()).isEqualTo(4096);
        assertThat(b.get()).isEqualTo((byte) 65);
        byte[] cleServeur = new byte[65];
        b.get(cleServeur);
        byte[] chiffre = new byte[b.remaining()];
        b.get(chiffre);

        // Déchiffrement côté appareil (RFC 8291, § 3.3 et 3.4)
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(appareil.getPrivate());
        ka.doPhase(WebPush.publique(cleServeur), true);
        byte[] ecdh = ka.generateSecret();
        byte[] info = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), cleAppareil, cleServeur);
        byte[] ikm = WebPush.hkdf(auth, ecdh, info, 32);
        byte[] cek = WebPush.hkdf(sel, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = WebPush.hkdf(sel, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] clair = c.doFinal(chiffre);
        assertThat(clair[clair.length - 1]).isEqualTo((byte) 2);
        assertThat(Arrays.copyOf(clair, clair.length - 1)).isEqualTo(message);

        // VAPID : « vapid t=<jwt>, k=<clé publique> », audience = origine du service push
        assertThat(r.autorisation()).startsWith("vapid t=").endsWith(", k=" + vapid[0]);
        String jwt = r.autorisation().substring("vapid t=".length(), r.autorisation().indexOf(", k="));
        String[] parties = jwt.split("\\.");
        String charge = new String(WebPush.D64.decode(parties[1]), StandardCharsets.UTF_8);
        assertThat(charge).contains("\"aud\":\"https://fcm.googleapis.com\"").contains("\"sub\":\"mailto:test@exemple.bf\"")
                .contains("\"exp\":" + Instant.parse("2026-11-03T22:00:00Z").getEpochSecond());
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initVerify(WebPush.publique(WebPush.D64.decode(vapid[0])));
        s.update((parties[0] + "." + parties[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(s.verify(WebPush.D64.decode(parties[2]))).isTrue();
    }

    @Test
    void clesIncoherentesOuAdresseNonSecuriseeRefusees() throws Exception {
        String[] a = WebPush.genererCles();
        String[] b = WebPush.genererCles();
        assertThatThrownBy(() -> new WebPush(a[0], b[1], "mailto:x@y.bf")).isInstanceOf(IllegalStateException.class);
        WebPush wp = new WebPush(a[0], a[1], "mailto:x@y.bf");
        assertThatThrownBy(() -> wp.preparer("http://push.exemple/abc", a[0], "AAAAAAAAAAAAAAAAAAAAAA", new byte[1],
                Instant.now())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chargeJsonPourLeServiceWorkerAngular() {
        String json = new String(EnvoiPush.chargeJson("Retard d'Ali", "Ali est arrivé \"en retard\"", "/parent/enfants/e1",
                "retard:1"), StandardCharsets.UTF_8);
        assertThat(json).startsWith("{\"notification\":{\"title\":\"Retard d'Ali\"")
                .contains("\"body\":\"Ali est arrivé \\\"en retard\\\"\"")
                .contains("\"operation\":\"navigateLastFocusedOrOpen\",\"url\":\"/parent/enfants/e1\"");
    }

    private static byte[] concat(byte[]... t) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (byte[] x : t) {
            o.writeBytes(x);
        }
        return o.toByteArray();
    }
}
