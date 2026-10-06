package bf.edutech.plateforme.mobilemoney;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;

/**
 * Chiffrement des clés d'agrégateur en base (AES-256-GCM, vecteur aléatoire par valeur).
 * Une fuite de la base seule ne révèle donc aucune clé : il faut aussi la clé du serveur
 * ({@code MOBILE_MONEY_CLE_CHIFFREMENT}).
 */
@Component
class Coffre {

    private static final String VERSION = "v1:";
    private static final int IV = 12;
    private static final SecureRandom HASARD = new SecureRandom();

    private final SecretKeySpec cle;

    Coffre(MobileMoneyProperties proprietes) {
        String brute = proprietes.cleChiffrement();
        if (brute == null || brute.isBlank()) {
            this.cle = null;
        } else {
            byte[] octets = Base64.getDecoder().decode(brute.trim());
            if (octets.length != 32) {
                throw new IllegalStateException("app.mobile-money.cle-chiffrement : 32 octets en Base64 attendus "
                        + "(openssl rand -base64 32)");
            }
            this.cle = new SecretKeySpec(octets, "AES");
        }
    }

    /** Chiffre pour une école : la valeur ne se déchiffre que pour cette même école. */
    String chiffrer(String clair, java.util.UUID ecole) {
        try {
            byte[] iv = new byte[IV];
            HASARD.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, exiger(), new GCMParameterSpec(128, iv));
            c.updateAAD(ecole.toString().getBytes(StandardCharsets.US_ASCII));
            byte[] chiffre = c.doFinal(clair.getBytes(StandardCharsets.UTF_8));
            byte[] tout = new byte[IV + chiffre.length];
            System.arraycopy(iv, 0, tout, 0, IV);
            System.arraycopy(chiffre, 0, tout, IV, chiffre.length);
            return VERSION + Base64.getEncoder().encodeToString(tout);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Chiffrement impossible", e);
        }
    }

    String dechiffrer(String valeur, java.util.UUID ecole) {
        if (valeur == null || !valeur.startsWith(VERSION)) {
            throw new IllegalStateException("Valeur chiffrée illisible");
        }
        try {
            byte[] tout = Base64.getDecoder().decode(valeur.substring(VERSION.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, exiger(), new GCMParameterSpec(128, tout, 0, IV));
            c.updateAAD(ecole.toString().getBytes(StandardCharsets.US_ASCII));
            return new String(c.doFinal(tout, IV, tout.length - IV), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Déchiffrement impossible (clé du serveur changée ?)", e);
        }
    }

    private SecretKeySpec exiger() {
        if (cle == null) {
            throw new RegleMetierException("CHIFFREMENT_NON_CONFIGURE",
                    "Le paiement Mobile Money n'est pas encore activé sur ce serveur : contactez l'exploitant");
        }
        return cle;
    }
}
