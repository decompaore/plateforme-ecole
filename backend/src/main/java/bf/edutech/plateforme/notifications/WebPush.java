package bf.edutech.plateforme.notifications;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push sans bibliothèque externe, avec la cryptographie du JDK :
 * <ul>
 * <li>chiffrement du message pour l'appareil (RFC 8291, codage {@code aes128gcm} de la RFC 8188) :
 * échange ECDH P-256 avec la clé de l'appareil, dérivation HKDF-SHA256, AES-128-GCM ;</li>
 * <li>identification du serveur auprès du service push (VAPID, RFC 8292) : jeton JWT signé ES256.</li>
 * </ul>
 * Les clés VAPID sont fournies par la configuration ({@code app.push.*}), jamais par le dépôt.
 */
final class WebPush {

    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    static final Base64.Decoder D64 = Base64.getUrlDecoder();
    private static final SecureRandom ALEA = new SecureRandom();
    private static final int TAILLE_ENREGISTREMENT = 4096;

    /** Corps chiffré et en-têtes à envoyer au service push. */
    record Requete(URI endpoint, byte[] corps, String autorisation) {
    }

    private final ECPublicKey clePublique;
    private final ECPrivateKey clePrivee;
    private final String clePubliqueB64;
    private final String sujet;

    /**
     * @param clePublique clé publique VAPID (point non compressé de 65 octets, base64url)
     * @param clePrivee   clé privée VAPID (scalaire de 32 octets, base64url)
     * @param sujet       contact de l'exploitant ({@code mailto:} ou {@code https:})
     */
    WebPush(String clePublique, String clePrivee, String sujet) {
        try {
            this.clePublique = publique(D64.decode(clePublique.strip()));
            this.clePrivee = privee(D64.decode(clePrivee.strip()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Clés VAPID invalides (app.push.cle-publique / app.push.cle-privee)", e);
        }
        this.clePubliqueB64 = B64.encodeToString(nonCompresse(this.clePublique));
        this.sujet = sujet;
        // Vérifie que les deux clés vont ensemble
        try {
            byte[] essai = "vapid".getBytes(StandardCharsets.US_ASCII);
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(this.clePrivee);
            s.update(essai);
            byte[] signature = s.sign();
            s.initVerify(this.clePublique);
            s.update(essai);
            if (!s.verify(signature)) {
                throw new IllegalStateException("Les clés VAPID publique et privée ne correspondent pas");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    String clePublique() {
        return clePubliqueB64;
    }

    /** Prépare l'envoi de {@code message} (JSON) à l'appareil abonné. */
    Requete preparer(String endpoint, String p256dh, String auth, byte[] message, Instant maintenant) {
        URI uri = URI.create(endpoint);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Adresse du service push non sécurisée");
        }
        try {
            byte[] corps = chiffrer(message, D64.decode(p256dh), D64.decode(auth));
            String audience = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            String jeton = jeton(audience, maintenant.plusSeconds(12 * 3600).getEpochSecond());
            return new Requete(uri, corps, "vapid t=" + jeton + ", k=" + clePubliqueB64);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Abonnement push invalide : " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ VAPID (RFC 8292)

    String jeton(String audience, long expiration) throws GeneralSecurityException {
        String entete = B64.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String charge = B64.encodeToString(("{\"aud\":\"" + audience + "\",\"exp\":" + expiration + ",\"sub\":\""
                + sujet.replace("\"", "") + "\"}").getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initSign(clePrivee);
        s.update((entete + "." + charge).getBytes(StandardCharsets.US_ASCII));
        return entete + "." + charge + "." + B64.encodeToString(s.sign());
    }

    // ------------------------------------------------------------------ chiffrement (RFC 8291)

    static byte[] chiffrer(byte[] message, byte[] cleAppareil, byte[] secretAuth) throws GeneralSecurityException {
        if (message.length > TAILLE_ENREGISTREMENT - 17 - 86) {
            throw new IllegalArgumentException("Message trop long pour une notification");
        }
        ECPublicKey appareil = publique(cleAppareil);
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), ALEA);
        KeyPair ephemere = g.generateKeyPair();
        byte[] sel = new byte[16];
        ALEA.nextBytes(sel);
        return chiffrer(message, appareil, cleAppareil, secretAuth, ephemere, sel);
    }

    static byte[] chiffrer(byte[] message, ECPublicKey appareil, byte[] cleAppareil, byte[] secretAuth,
            KeyPair ephemere, byte[] sel) throws GeneralSecurityException {
        byte[] cleServeur = nonCompresse((ECPublicKey) ephemere.getPublic());
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(ephemere.getPrivate());
        ka.doPhase(appareil, true);
        byte[] secretEcdh = ka.generateSecret();

        byte[][] cles = deriver(secretEcdh, secretAuth, cleAppareil, cleServeur, sel);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cles[0], "AES"), new GCMParameterSpec(128, cles[1]));
        byte[] clair = Arrays.copyOf(message, message.length + 1);
        clair[message.length] = 0x02; // délimiteur du dernier (et seul) enregistrement
        byte[] chiffre = c.doFinal(clair);

        ByteBuffer corps = ByteBuffer.allocate(16 + 4 + 1 + cleServeur.length + chiffre.length);
        corps.put(sel).putInt(TAILLE_ENREGISTREMENT).put((byte) cleServeur.length).put(cleServeur).put(chiffre);
        return corps.array();
    }

    /** Clé de chiffrement (16 octets) et nonce (12 octets). */
    static byte[][] deriver(byte[] secretEcdh, byte[] secretAuth, byte[] cleAppareil, byte[] cleServeur, byte[] sel)
            throws GeneralSecurityException {
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.writeBytes("WebPush: info".getBytes(StandardCharsets.US_ASCII));
        info.write(0);
        info.writeBytes(cleAppareil);
        info.writeBytes(cleServeur);
        byte[] ikm = hkdf(secretAuth, secretEcdh, info.toByteArray(), 32);
        byte[] cek = hkdf(sel, ikm, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdf(sel, ikm, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        return new byte[][] { cek, nonce };
    }

    /** HKDF-SHA256 (RFC 5869) pour une sortie d'au plus 32 octets. */
    static byte[] hkdf(byte[] sel, byte[] ikm, byte[] info, int longueur) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(sel, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), longueur);
    }

    // ------------------------------------------------------------------ clés P-256

    static ECParameterSpec p256() throws GeneralSecurityException {
        AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
        p.init(new ECGenParameterSpec("secp256r1"));
        return p.getParameterSpec(ECParameterSpec.class);
    }

    static ECPublicKey publique(byte[] point) throws GeneralSecurityException {
        if (point.length != 65 || point[0] != 0x04) {
            throw new GeneralSecurityException("Clé publique P-256 non compressée attendue (65 octets)");
        }
        ECPoint w = new ECPoint(new BigInteger(1, Arrays.copyOfRange(point, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(point, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, p256()));
    }

    static ECPrivateKey privee(byte[] d) throws GeneralSecurityException {
        if (d.length != 32) {
            throw new GeneralSecurityException("Clé privée P-256 de 32 octets attendue");
        }
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), p256()));
    }

    static byte[] nonCompresse(ECPublicKey cle) {
        byte[] r = new byte[65];
        r[0] = 0x04;
        copier(cle.getW().getAffineX(), r, 1);
        copier(cle.getW().getAffineY(), r, 33);
        return r;
    }

    static byte[] scalaire(ECPrivateKey cle) {
        byte[] r = new byte[32];
        copier(cle.getS(), r, 0);
        return r;
    }

    private static void copier(BigInteger n, byte[] dest, int position) {
        byte[] b = n.toByteArray();
        int debut = Math.max(0, b.length - 32);
        int longueur = b.length - debut;
        System.arraycopy(b, debut, dest, position + 32 - longueur, longueur);
    }

    /** Paire de clés VAPID {publique, privée} en base64url (tests ; en exploitation : scripts/push/generer-cles-vapid.mjs). */
    static String[] genererCles() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), ALEA);
        KeyPair k = g.generateKeyPair();
        return new String[] { B64.encodeToString(nonCompresse((ECPublicKey) k.getPublic())),
                B64.encodeToString(scalaire((ECPrivateKey) k.getPrivate())) };
    }
}
