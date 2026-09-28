package bf.edutech.plateforme.eleves;

/** Colonnes du fichier d'import des élèves (feuille « Eleves » du modèle). */
final class FormatImport {

    static final String FEUILLE = "Eleves";
    static final int LIGNES_MAX = 3000;

    static final int MATRICULE = 0;
    static final int NOM = 1;
    static final int PRENOMS = 2;
    static final int SEXE = 3;
    static final int DATE_NAISSANCE = 4;
    static final int LIEU_NAISSANCE = 5;
    static final int CLASSE = 6;
    static final int REDOUBLANT = 7;
    static final int BOURSE = 8;
    static final int PARENT_NOM = 9;
    static final int PARENT_PRENOMS = 10;
    static final int PARENT_TELEPHONE = 11;
    static final int PARENT_LIEN = 12;

    static final String[] ENTETES = {
        "Matricule", "Nom *", "Prénoms *", "Sexe (M/F) *", "Date de naissance *", "Lieu de naissance",
        "Classe *", "Redoublant (O/N)", "Bourse (B/SB/NB)", "Nom du parent", "Prénoms du parent",
        "Téléphone du parent", "Lien (PERE/MERE/TUTEUR/AUTRE)"
    };

    private FormatImport() {
    }
}
