package bf.edutech.plateforme.secretariat;

import java.time.LocalDate;
import java.util.Locale;

import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.images.Images;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Règles communes du module : rôles, services de l'établissement, pièces jointes, textes. */
final class Secretariat {

    /** Gestion administrative et courrier : le secrétariat, sous l'autorité du chef d'établissement. */
    static final String GESTION = "hasAnyRole('ADMIN_ECOLE','SECRETARIAT')";
    static final String CHEF = "hasRole('ADMIN_ECOLE')";

    /** Services vers lesquels on oriente un courrier ou un visiteur ; rôle qui le traite. */
    enum Service {
        DIRECTION("Direction", "ADMIN_ECOLE"),
        CENSORAT("Censorat", "CENSEUR"),
        CHEF_TRAVAUX("Chef des travaux", "CHEF_TRAVAUX"),
        INTENDANCE("Intendance", "INTENDANT"),
        VIE_SCOLAIRE("Vie scolaire (CPE)", "SURVEILLANT"),
        SECRETARIAT("Secrétariat", "SECRETARIAT"),
        ENSEIGNANTS("Enseignants", "ENSEIGNANT");

        final String libelle;
        final String role;

        Service(String libelle, String role) {
            this.libelle = libelle;
            this.role = role;
        }

        /** Service du compte connecté (hors direction et secrétariat), s'il en a un. */
        static Service duConnecte() {
            for (Service s : values()) {
                if (s != DIRECTION && s != SECRETARIAT && UtilisateurConnecte.aUnRole(s.role)) {
                    return s;
                }
            }
            return null;
        }
    }

    record Fichier(byte[] contenu, String type, String nom) {
    }

    private Secretariat() {
    }

    static boolean gestionnaire() {
        return UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "SECRETARIAT");
    }

    static void exigerGestionnaire() {
        if (!gestionnaire()) {
            throw new AccesRefuseException("Réservé au secrétariat et au chef d'établissement");
        }
    }

    /** Pièce jointe : PDF, JPEG ou PNG, 5 Mo au plus. */
    static Fichier fichier(byte[] contenu, String nom) {
        if (contenu == null || contenu.length == 0) {
            throw new IllegalArgumentException("Fichier vide");
        }
        if (contenu.length > Images.TAILLE_MAX) {
            throw new IllegalArgumentException("Fichier de 5 Mo au plus");
        }
        String type;
        if (contenu.length > 4 && contenu[0] == '%' && contenu[1] == 'P' && contenu[2] == 'D' && contenu[3] == 'F') {
            type = "application/pdf";
        } else if ((contenu[0] & 0xff) == 0xff && (contenu[1] & 0xff) == 0xd8) {
            type = "image/jpeg";
        } else if ((contenu[0] & 0xff) == 0x89 && contenu[1] == 'P' && contenu[2] == 'N' && contenu[3] == 'G') {
            type = "image/png";
        } else {
            throw new IllegalArgumentException("Formats acceptés : PDF, JPEG, PNG (document scanné ou photographié)");
        }
        String n = nom == null || nom.isBlank() ? "document" : nom.strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
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
        if (valeur == null || valeur.isBlank()) {
            return null;
        }
        return obligatoire(valeur, quoi, max);
    }

    static <E extends Enum<E>> E valeur(Class<E> type, String texte, String quoi) {
        if (texte == null || texte.isBlank()) {
            throw new IllegalArgumentException(quoi + " obligatoire");
        }
        try {
            return Enum.valueOf(type, texte.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(quoi + " inconnu : " + texte);
        }
    }

    static String annee(LocalDate jour) {
        return String.valueOf(jour.getYear());
    }

    /** Extension du fichier téléchargé selon son type. */
    static String extension(String type) {
        return switch (type) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            default -> ".jpg";
        };
    }
}
