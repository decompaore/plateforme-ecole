package bf.edutech.plateforme.socle.modules;

import java.util.List;

/**
 * Modules qu'un établissement peut ne pas utiliser. Le socle (classes, élèves,
 * enseignants, appel, notes, bulletins, comptes) est toujours actif. Chaque module
 * déclare les chemins de l'API qui lui appartiennent : un module désactivé les ferme.
 */
public enum Module {

    ATELIERS("Ateliers et matière d'œuvre",
            "Catalogue des prix, ateliers et responsables, équipements, stocks, inventaires, besoins et commandes",
            null,
            "/api/v1/ateliers/**", "/api/v1/besoins-ateliers/**", "/api/v1/campagnes-besoins/**",
            "/api/v1/catalogue/**", "/api/v1/commandes/**", "/api/v1/equipements/**", "/api/v1/inventaires/**",
            "/api/v1/livraisons/**", "/api/v1/pannes/**", "/api/v1/parametres/ateliers"),

    EMPLOIS_DU_TEMPS("Emplois du temps",
            "Grille horaire, emplois du temps des classes, génération automatique, publication aux enseignants",
            null,
            "/api/v1/annees/*/creneaux", "/api/v1/annees/*/emploi-du-temps/**", "/api/v1/seances-emploi/**",
            "/api/v1/espace-enseignant/emploi-du-temps/**"),

    PROGRESSION("Progressions et cahier de textes",
            "Fiches de progression des enseignants, visa du censeur et du chef des travaux, cahier de textes",
            null,
            "/api/v1/classes/*/matieres/*/progression/**", "/api/v1/annees/*/progressions",
            "/api/v1/espace-enseignant/progressions", "/api/v1/classes/*/matieres/*/cahier-textes",
            "/api/v1/cahier-textes/**"),

    VIE_SCOLAIRE("Vie scolaire",
            "Retards, avertissements, blâmes, exclusions, convocations des parents",
            null,
            "/api/v1/classes/*/vie-scolaire", "/api/v1/convocations/**", "/api/v1/eleves/*/vie-scolaire",
            "/api/v1/espace-parent/enfants/*/vie-scolaire", "/api/v1/incidents/**",
            "/api/v1/inscriptions/*/convocations", "/api/v1/inscriptions/*/incidents",
            "/api/v1/inscriptions/*/vie-scolaire"),

    SCOLARITE("Scolarité et paiements",
            "Frais, bourses et exonérations, guichet et reçus, retards de paiement, relances SMS",
            null,
            "/api/v1/annees/*/frais", "/api/v1/classes/*/scolarite/**", "/api/v1/espace-parent/enfants/*/scolarite",
            "/api/v1/espace-parent/paiements/**", "/api/v1/frais/**", "/api/v1/inscriptions/*/exonerations/**",
            "/api/v1/inscriptions/*/frais/**", "/api/v1/inscriptions/*/paiements",
            "/api/v1/inscriptions/*/prise-en-charge", "/api/v1/inscriptions/*/scolarite", "/api/v1/organismes/**",
            "/api/v1/paiements/**", "/api/v1/parametres/scolarite", "/api/v1/scolarite/**"),

    MOBILE_MONEY("Paiement Mobile Money",
            "Paiement des frais par les parents (Orange Money, Moov Money, Telecel Money), rapprochement quotidien",
            SCOLARITE,
            "/api/v1/espace-parent/inscriptions/*/mobile-money", "/api/v1/espace-parent/mobile-money/**",
            "/api/v1/mobile-money/**", "/api/v1/parametres/mobile-money"),

    ESPACE_PARENT("Espace parent",
            "Comptes des parents : absences, notes, bulletins, scolarité de leurs enfants sur téléphone",
            null,
            "/api/v1/espace-parent/**", "/api/v1/responsables/*/espace-parent", "/api/v1/classes/*/espaces-parents");

    private final String libelle;
    private final String description;
    private final Module requis;
    private final List<String> chemins;

    Module(String libelle, String description, Module requis, String... chemins) {
        this.libelle = libelle;
        this.description = description;
        this.requis = requis;
        this.chemins = List.of(chemins);
    }

    public String libelle() {
        return libelle;
    }

    public String description() {
        return description;
    }

    /** Module sans lequel celui-ci ne fonctionne pas (null si aucun). */
    public Module requis() {
        return requis;
    }

    public List<String> chemins() {
        return chemins;
    }
}
