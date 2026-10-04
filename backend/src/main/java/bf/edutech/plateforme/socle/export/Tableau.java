package bf.edutech.plateforme.socle.export;

import java.util.ArrayList;
import java.util.List;

/**
 * Tableau à exporter en Excel ou en PDF : titre, sous-titre, colonnes, lignes, ligne de total,
 * notes et cadres de signature. Une cellule est un texte, un nombre (affiché sans décimale inutile),
 * un {@link Montant} (francs CFA) ou une {@link Image} (PDF seulement ; « photo » en Excel).
 */
public final class Tableau {

    public enum Type {
        TEXTE,
        NOMBRE,
        MONTANT,
        IMAGE
    }

    /** {@code poids} : largeur relative de la colonne. */
    public record Colonne(String titre, float poids, Type type) {

        public static Colonne texte(String titre, float poids) {
            return new Colonne(titre, poids, Type.TEXTE);
        }

        public static Colonne nombre(String titre, float poids) {
            return new Colonne(titre, poids, Type.NOMBRE);
        }

        public static Colonne montant(String titre, float poids) {
            return new Colonne(titre, poids, Type.MONTANT);
        }

        public static Colonne image(String titre, float poids) {
            return new Colonne(titre, poids, Type.IMAGE);
        }
    }

    /** Montant en francs CFA. */
    public record Montant(long valeur) {
    }

    /** Image JPEG ou PNG (les autres formats sont ignorés dans le PDF). */
    public record Image(byte[] contenu) {
    }

    private final String nomFeuille;
    private final String titre;
    private String sousTitre;
    private final List<Colonne> colonnes = new ArrayList<>();
    private final List<List<Object>> lignes = new ArrayList<>();
    private final List<Integer> intertitres = new ArrayList<>();
    private List<Object> total;
    private final List<String> notes = new ArrayList<>();
    private final List<String> signatures = new ArrayList<>();
    private boolean paysage = true;

    public Tableau(String nomFeuille, String titre) {
        this.nomFeuille = nomFeuille;
        this.titre = titre;
    }

    public Tableau sousTitre(String texte) {
        this.sousTitre = texte;
        return this;
    }

    public Tableau colonnes(Colonne... cs) {
        colonnes.addAll(List.of(cs));
        return this;
    }

    public Tableau ligne(Object... valeurs) {
        lignes.add(java.util.Arrays.asList(valeurs));
        return this;
    }

    /** Ligne de regroupement (filière, catégorie…) sur toute la largeur. */
    public Tableau intertitre(String texte) {
        intertitres.add(lignes.size());
        List<Object> l = new ArrayList<>();
        l.add(texte);
        lignes.add(l);
        return this;
    }

    public Tableau total(Object... valeurs) {
        this.total = java.util.Arrays.asList(valeurs);
        return this;
    }

    public Tableau note(String texte) {
        notes.add(texte);
        return this;
    }

    public Tableau signatures(String... qui) {
        signatures.addAll(List.of(qui));
        return this;
    }

    public Tableau portrait() {
        this.paysage = false;
        return this;
    }

    public String nomFeuille() {
        return nomFeuille;
    }

    public String titre() {
        return titre;
    }

    public String sousTitre() {
        return sousTitre;
    }

    public List<Colonne> colonnes() {
        return colonnes;
    }

    public List<List<Object>> lignes() {
        return lignes;
    }

    public boolean estIntertitre(int indice) {
        return intertitres.contains(indice);
    }

    public List<Object> total() {
        return total;
    }

    public List<String> notes() {
        return notes;
    }

    public List<String> signatures() {
        return signatures;
    }

    public boolean paysage() {
        return paysage;
    }
}
