package bf.edutech.plateforme.ateliers;

import java.math.BigDecimal;
import java.util.Locale;

/** Contrôles de saisie communs au module. */
final class Textes {

    private Textes() {
    }

    static String obligatoire(String valeur, String quoi, int max) {
        if (valeur == null || valeur.isBlank()) {
            throw new IllegalArgumentException(quoi + " est obligatoire");
        }
        return facultatif(valeur, quoi, max);
    }

    static String facultatif(String valeur, String quoi, int max) {
        if (valeur == null || valeur.isBlank()) {
            return null;
        }
        String v = valeur.strip();
        if (v.length() > max) {
            throw new IllegalArgumentException(quoi + " : " + max + " caractères au plus");
        }
        return v;
    }

    static String code(String valeur, String quoi, int max) {
        String v = obligatoire(valeur, quoi, max).toUpperCase(Locale.ROOT);
        if (!v.matches("[A-Z0-9][A-Z0-9_./-]*")) {
            throw new IllegalArgumentException(quoi + " : lettres, chiffres, tirets, points ou barres obliques");
        }
        return v;
    }

    static Long montant(Long valeur, String quoi) {
        if (valeur != null && (valeur < 0 || valeur > 10_000_000_000L)) {
            throw new IllegalArgumentException(quoi + " invalide");
        }
        return valeur;
    }

    /** Quantité positive (ou nulle si {@code zero}) avec deux décimales au plus. */
    static BigDecimal quantite(BigDecimal q, String quoi, boolean zero) {
        if (q == null) {
            throw new IllegalArgumentException(quoi + " est obligatoire");
        }
        if (q.signum() < 0 || (!zero && q.signum() == 0) || q.compareTo(new BigDecimal("9999999999")) > 0) {
            throw new IllegalArgumentException(quoi + (zero ? " doit être positive ou nulle" : " doit être positive"));
        }
        if (q.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(quoi + " : deux décimales au plus");
        }
        return q.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }
}
