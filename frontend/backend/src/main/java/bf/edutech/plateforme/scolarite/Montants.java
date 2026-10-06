package bf.edutech.plateforme.scolarite;

/** Écriture des montants en francs CFA : « 25 000 FCFA », et en lettres pour les reçus. */
final class Montants {

    private static final String[] UNITES = { "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit",
        "neuf", "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit",
        "dix-neuf" };
    private static final String[] DIZAINES = { "", "", "vingt", "trente", "quarante", "cinquante", "soixante" };

    private Montants() {
    }

    /** 1250000 → « 1 250 000 » (espaces entre les milliers). */
    static String chiffres(long montant) {
        String brut = Long.toString(Math.abs(montant));
        StringBuilder resultat = new StringBuilder();
        for (int i = 0; i < brut.length(); i++) {
            if (i > 0 && (brut.length() - i) % 3 == 0) {
                resultat.append(' ');
            }
            resultat.append(brut.charAt(i));
        }
        return (montant < 0 ? "-" : "") + resultat;
    }

    static String fcfa(long montant) {
        return chiffres(montant) + " FCFA";
    }

    /** 75250 → « soixante-quinze mille deux cent cinquante » (jusqu'à 999 999 999). */
    static String lettres(long montant) {
        if (montant < 0 || montant > 999_999_999L) {
            throw new IllegalArgumentException("Montant hors limites : " + montant);
        }
        if (montant == 0) {
            return UNITES[0];
        }
        int millions = (int) (montant / 1_000_000);
        int milliers = (int) (montant / 1000 % 1000);
        int reste = (int) (montant % 1000);
        StringBuilder s = new StringBuilder();
        if (millions > 0) {
            s.append(millions == 1 ? "un million" : centaines(millions, true) + " millions");
        }
        if (milliers > 0) {
            espace(s).append(milliers == 1 ? "mille" : centaines(milliers, false) + " mille");
        }
        if (reste > 0) {
            espace(s).append(centaines(reste, true));
        }
        return s.toString();
    }

    private static StringBuilder espace(StringBuilder s) {
        return s.isEmpty() ? s : s.append(' ');
    }

    /** 1 à 999 ; {@code final} : « cents » et « quatre-vingts » prennent un s en fin de nombre. */
    private static String centaines(int n, boolean fin) {
        int c = n / 100;
        int r = n % 100;
        StringBuilder s = new StringBuilder();
        if (c > 0) {
            s.append(c == 1 ? "cent" : UNITES[c] + " cent");
            if (c > 1 && r == 0 && fin) {
                s.append('s');
            }
        }
        if (r > 0) {
            espace(s).append(dizaines(r, fin));
        }
        return s.toString();
    }

    private static String dizaines(int n, boolean fin) {
        if (n < 20) {
            return UNITES[n];
        }
        int d = n / 10;
        int u = n % 10;
        if (d == 7 || d == 9) {                       // soixante-dix…, quatre-vingt-dix…
            String base = d == 7 ? "soixante" : "quatre-vingt";
            String liaison = d == 7 && u == 1 ? " et " : "-";
            return base + liaison + UNITES[10 + u];
        }
        if (d == 8) {
            return u == 0 ? (fin ? "quatre-vingts" : "quatre-vingt") : "quatre-vingt-" + UNITES[u];
        }
        if (u == 0) {
            return DIZAINES[d];
        }
        return DIZAINES[d] + (u == 1 ? " et un" : "-" + UNITES[u]);
    }
}
