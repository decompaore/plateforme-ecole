package bf.edutech.plateforme.socle.documents;

import java.util.List;

/**
 * En-tête des documents officiels d'un établissement : pays et devise nationale, ministère et
 * directions de rattachement (du niveau le plus haut au plus proche), nom et logo de
 * l'établissement.
 *
 * @param pays          nom du pays (null si l'établissement n'est pas rattaché)
 * @param devise        devise nationale (facultative)
 * @param autorites     ministère puis directions, dans l'ordre de la hiérarchie
 * @param etablissement nom de l'établissement
 * @param logo          image PNG ou JPEG, ou null
 */
public record EnteteOfficiel(String pays, String devise, List<String> autorites, String etablissement, byte[] logo) {

    public EnteteOfficiel {
        autorites = autorites == null ? List.of() : List.copyOf(autorites);
    }

    /** En-tête réduit au nom de l'établissement (documents sans rattachement). */
    public static EnteteOfficiel simple(String etablissement) {
        return new EnteteOfficiel(null, null, List.of(), etablissement, null);
    }

    /** Vrai si l'en-tête porte le pays ou une autorité de tutelle. */
    public boolean officiel() {
        return pays != null || !autorites.isEmpty();
    }

    /** Une seule ligne : « Burkina Faso · MESFPT · Direction régionale du Centre ». */
    public String rattachement() {
        java.util.List<String> morceaux = new java.util.ArrayList<>();
        if (pays != null) {
            morceaux.add(pays);
        }
        morceaux.addAll(autorites);
        return String.join(" · ", morceaux);
    }

    /** Même en-tête avec d'autres lignes de tutelle (ex. réglages propres aux bulletins). */
    public EnteteOfficiel avec(String autrePays, String autreDevise, List<String> autresAutorites) {
        return new EnteteOfficiel(autrePays, autreDevise, autresAutorites, etablissement, logo);
    }
}
