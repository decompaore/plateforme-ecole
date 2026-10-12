package bf.edutech.plateforme.passage;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ordre des niveaux d'une filière, pour trouver le niveau « supérieur » d'une classe (v0.45).
 * <ul>
 * <li>Enseignement général : 6e, 5e, 4e, 3e, puis 2nde, 1re, Tle (Terminale).</li>
 * <li>Niveaux numérotés : « 1re année » &lt; « 2e année » &lt; « 3e année », « CAP 1 » &lt; « CAP 2 »,
 * « BEP1 » &lt; « BEP2 »… (même libellé, numéro croissant).</li>
 * </ul>
 * Deux niveaux de familles différentes (ex. « 3e » et « CAP 1 ») ne se comparent pas : la plateforme ne
 * devine pas un changement de cycle, la direction choisit alors la classe à la main.
 */
final class Niveaux {

    private static final List<String> GENERAL = List.of("6", "5", "4", "3", "2", "1", "t");
    private static final Pattern NUMERO = Pattern.compile("(\\d+)\\s*(?:ere|re|er|eme|e|nde|de|d)?\\b");

    /** Position d'un niveau : famille (texte sans numéro) et rang dans la famille. */
    record Position(String famille, int rang) {
    }

    private Niveaux() {
    }

    static Optional<Position> position(String niveau) {
        if (niveau == null || niveau.isBlank()) {
            return Optional.empty();
        }
        String n = Normalizer.normalize(niveau, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        if (n.equals("tle") || n.equals("terminale") || n.equals("term")) {
            return Optional.of(new Position("", GENERAL.indexOf("t")));
        }
        if (n.equals("seconde")) {
            return Optional.of(new Position("", GENERAL.indexOf("2")));
        }
        if (n.equals("premiere")) {
            return Optional.of(new Position("", GENERAL.indexOf("1")));
        }
        Matcher m = NUMERO.matcher(n);
        if (!m.find()) {
            return Optional.empty();
        }
        String famille = (n.substring(0, m.start()) + " " + n.substring(m.end())).replaceAll("\\s+", " ").trim();
        int numero = Integer.parseInt(m.group(1));
        if (famille.isEmpty()) {
            // 6e…3e, 2nde, 1re : l'ordre descend avec le numéro
            int i = GENERAL.indexOf(String.valueOf(numero));
            return i < 0 ? Optional.empty() : Optional.of(new Position("", i));
        }
        return Optional.of(new Position(famille, numero));
    }

    /**
     * Le niveau immédiatement supérieur à {@code source} parmi {@code candidats} (même famille, plus petit
     * rang au-dessus). Vide pour une classe terminale, ou si aucun niveau ne se compare.
     */
    static Optional<String> suivant(String source, List<String> candidats) {
        Optional<Position> p = position(source);
        if (p.isEmpty()) {
            return Optional.empty();
        }
        String meilleur = null;
        int rang = Integer.MAX_VALUE;
        for (String c : candidats) {
            Optional<Position> q = position(c);
            if (q.isPresent() && q.get().famille().equals(p.get().famille()) && q.get().rang() > p.get().rang()
                    && q.get().rang() < rang) {
                rang = q.get().rang();
                meilleur = c;
            }
        }
        return Optional.ofNullable(meilleur);
    }

    /** Même niveau (redoublement) : comparaison sans casse, accents ni ponctuation. */
    static boolean meme(String a, String b) {
        Optional<Position> p = position(a);
        Optional<Position> q = position(b);
        if (p.isPresent() && q.isPresent()) {
            return p.get().equals(q.get());
        }
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }
}
