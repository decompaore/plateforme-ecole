package bf.edutech.plateforme.utilisateurs;

import java.security.SecureRandom;

/**
 * Mots de passe temporaires lisibles (sans caractères ambigus 0/O, 1/l/I),
 * à changer obligatoirement à la première connexion.
 */
final class GenerateurMotDePasse {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom ALEA = new SecureRandom();

    private GenerateurMotDePasse() {
    }

    static String temporaire() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(ALPHABET.charAt(ALEA.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
