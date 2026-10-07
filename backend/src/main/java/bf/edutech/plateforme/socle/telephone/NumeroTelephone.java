package bf.edutech.plateforme.socle.telephone;

import java.util.regex.Pattern;

/**
 * Normalisation des numéros de téléphone au format international.
 * <p>
 * Exemples avec l'indicatif +226 : « 70 12 34 56 » → « +22670123456 »,
 * « 0022670123456 » → « +22670123456 ».
 */
public final class NumeroTelephone {

    private static final Pattern FORMAT = Pattern.compile("^\\+?[0-9]{6,15}$");
    /** Longueur d'un numéro national par défaut (Burkina Faso : 8 chiffres). */
    public static final int LONGUEUR_NATIONALE = 8;

    private NumeroTelephone() {
    }

    public static String normaliser(String saisie, String indicatifParDefaut) {
        return normaliser(saisie, indicatifParDefaut, LONGUEUR_NATIONALE);
    }

    /**
     * Normalise un numéro ; un numéro de {@code longueurNationale} chiffres reçoit l'indicatif
     * du pays (ex. 10 chiffres en Côte d'Ivoire, 8 au Burkina Faso).
     */
    public static String normaliser(String saisie, String indicatifParDefaut, int longueurNationale) {
        if (saisie == null || saisie.isBlank()) {
            throw new IllegalArgumentException("Le numéro de téléphone est obligatoire");
        }
        String numero = saisie.replaceAll("[\\s.\\-()]", "");
        if (numero.startsWith("00")) {
            numero = "+" + numero.substring(2);
        }
        if (!FORMAT.matcher(numero).matches()) {
            throw new IllegalArgumentException("Numéro de téléphone invalide");
        }
        if (numero.startsWith("+")) {
            return numero;
        }
        return numero.length() == longueurNationale ? indicatifParDefaut + numero : "+" + numero;
    }

    /** Version masquée pour les journaux : +226****3456. */
    public static String masquer(String numero) {
        if (numero == null || numero.length() < 8) {
            return "****";
        }
        return numero.substring(0, 4) + "****" + numero.substring(numero.length() - 4);
    }
}
