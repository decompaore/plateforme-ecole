package bf.edutech.plateforme.personnel;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import bf.edutech.plateforme.personnel.Vues.Duree;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.images.Images;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Règles communes du module : rôles, pièces jointes, textes, durées. */
final class Personnel {

    static final ZoneId FUSEAU = ZoneId.of("Africa/Ouagadougou");

    /** Tient les dossiers : le secrétariat, sous l'autorité du chef d'établissement. */
    static final String GESTION = "hasAnyRole('ADMIN_ECOLE','SECRETARIAT')";
    /** Consulte les dossiers : la gestion, et le censeur (personnel enseignant). */
    static final String LECTURE = "hasAnyRole('ADMIN_ECOLE','SECRETARIAT','CENSEUR')";
    /** Signe les actes, accorde les absences. */
    static final String CHEF = "hasRole('ADMIN_ECOLE')";
    /** Tout membre du personnel qui a un compte. */
    static final String PERSONNEL = "hasAnyRole('ADMIN_ECOLE','CENSEUR','CHEF_TRAVAUX','SECRETARIAT','INTENDANT',"
            + "'SURVEILLANT','ENSEIGNANT')";

    record Fichier(byte[] contenu, String type, String nom) {
    }

    private Personnel() {
    }

    static boolean gestionnaire() {
        return UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "SECRETARIAT");
    }

    static boolean chef() {
        return UtilisateurConnecte.aUnRole("ADMIN_ECOLE");
    }

    static void exigerGestionnaire() {
        if (!gestionnaire()) {
            throw new AccesRefuseException("Réservé au secrétariat et au chef d'établissement");
        }
    }

    static void exigerChef() {
        if (!chef()) {
            throw new AccesRefuseException("Réservé au chef d'établissement");
        }
    }

    static void exigerLecture() {
        if (!UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "SECRETARIAT", "CENSEUR")) {
            throw new AccesRefuseException("Dossiers du personnel : direction et secrétariat");
        }
    }

    /** Pièce jointe : PDF, JPEG ou PNG, 5 Mo au plus (contrôle du contenu, pas du nom). */
    static Fichier fichier(byte[] contenu, String nom) {
        if (contenu == null || contenu.length < 8) {
            throw new IllegalArgumentException("Fichier vide");
        }
        if (contenu.length > Images.TAILLE_MAX) {
            throw new IllegalArgumentException("Fichier de 5 Mo au plus");
        }
        String type;
        if (contenu[0] == '%' && contenu[1] == 'P' && contenu[2] == 'D' && contenu[3] == 'F') {
            type = "application/pdf";
        } else if ((contenu[0] & 0xff) == 0xff && (contenu[1] & 0xff) == 0xd8) {
            type = "image/jpeg";
        } else if ((contenu[0] & 0xff) == 0x89 && contenu[1] == 'P' && contenu[2] == 'N' && contenu[3] == 'G') {
            type = "image/png";
        } else {
            throw new IllegalArgumentException("Formats acceptés : PDF, JPEG, PNG (document scanné ou photographié)");
        }
        String n = nom == null || nom.isBlank() ? "piece" : nom.strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        return new Fichier(contenu, type, n.length() > 150 ? n.substring(n.length() - 150) : n);
    }

    static String obligatoire(String valeur, String quoi, int max) {
        String v = valeur == null ? "" : valeur.strip();
        if (v.isEmpty()) {
            throw new IllegalArgumentException(quoi + " obligatoire");
        }
        if (v.length() > max) {
            throw new IllegalArgumentException(quoi + " : " + max + " caractères au plus");
        }
        return v;
    }

    static String facultatif(String valeur, String quoi, int max) {
        return valeur == null || valeur.isBlank() ? null : obligatoire(valeur, quoi, max);
    }

    static <E extends Enum<E>> E valeur(Class<E> type, Object texte, String quoi) {
        if (texte == null || texte.toString().isBlank()) {
            throw new IllegalArgumentException(quoi + " obligatoire");
        }
        try {
            return Enum.valueOf(type, texte.toString().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(quoi + " inconnu : " + texte);
        }
    }

    /** Durée de {@code depuis} à {@code jusqua} (incluse : un agent qui a pris service hier a 2 jours). */
    static Duree duree(LocalDate depuis, LocalDate jusqua) {
        if (depuis == null || jusqua == null || jusqua.isBefore(depuis)) {
            return null;
        }
        Period p = Period.between(depuis, jusqua);
        List<String> morceaux = new ArrayList<>();
        if (p.getYears() > 0) {
            morceaux.add(p.getYears() + (p.getYears() > 1 ? " ans" : " an"));
        }
        if (p.getMonths() > 0) {
            morceaux.add(p.getMonths() + " mois");
        }
        if (p.getDays() > 0 || morceaux.isEmpty()) {
            morceaux.add(p.getDays() + (p.getDays() > 1 ? " jours" : " jour"));
        }
        String texte = morceaux.size() == 1 ? morceaux.get(0)
                : String.join(", ", morceaux.subList(0, morceaux.size() - 1)) + " et " + morceaux.get(morceaux.size() - 1);
        return new Duree(p.getYears(), p.getMonths(), p.getDays(), texte);
    }

    static String civilite(String sexe) {
        return "F".equals(sexe) ? "Madame" : "Monsieur";
    }

    static String accord(String sexe, String masculin, String feminin) {
        return "F".equals(sexe) ? feminin : masculin;
    }
}
