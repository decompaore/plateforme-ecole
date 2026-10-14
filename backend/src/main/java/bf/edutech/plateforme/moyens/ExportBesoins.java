package bf.edutech.plateforme.moyens;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.moyens.RemonteesService.DirectionBesoins;
import bf.edutech.plateforme.moyens.RemonteesService.EtablissementBesoins;
import bf.edutech.plateforme.moyens.RemonteesService.LigneConsolidee;
import bf.edutech.plateforme.moyens.RemonteesService.VueBesoins;
import bf.edutech.plateforme.socle.documents.EnteteOfficiel;
import bf.edutech.plateforme.socle.export.ExportTableaux;
import bf.edutech.plateforme.socle.export.Tableau;
import bf.edutech.plateforme.socle.export.Tableau.Colonne;

/** Export Excel ou PDF des besoins consolidés d'une direction (ou du niveau central). */
@Component
class ExportBesoins {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy")
            .withZone(ZoneId.of("Africa/Ouagadougou"));

    private final JdbcTemplate jdbc;

    ExportBesoins(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    ResponseEntity<byte[]> reponse(VueBesoins v, UUID pays, ExportTableaux.Format format) {
        EnteteOfficiel entete = EnteteOfficiel.simple(v.perimetre().nom());
        if (pays != null) {
            List<String[]> p = jdbc.query("select nom, devise_nationale from pays where id = ?",
                    (l, n) -> new String[] { l.getString(1), l.getString(2) }, pays);
            if (!p.isEmpty()) {
                entete = new EnteteOfficiel(p.get(0)[0], p.get(0)[1], List.of(), v.perimetre().nom(), null);
            }
        }
        String sousTitre = "Année scolaire " + v.annee() + " · " + v.perimetre().chemin();

        Tableau consolidation = new Tableau("Besoins", "Besoins consolidés — " + v.perimetre().nom())
                .sousTitre(sousTitre)
                .colonnes(Colonne.texte("Catégorie", 1.4f), Colonne.texte("Désignation", 3), Colonne.texte("Unité", 1),
                        Colonne.nombre("Demandé", 1), Colonne.nombre("Retenu", 1),
                        Colonne.nombre("Établissements", 1.2f), Colonne.texte("Priorité", 1));
        for (LigneConsolidee l : v.consolidation()) {
            consolidation.ligne(categorie(l.categorie()), l.designation(), l.unite(), l.demandee(), l.retenue(),
                    l.etablissements(), priorite(l.priorite()));
        }
        consolidation.note("Quantités retenues : arbitrage de ce niveau, sinon celui du niveau inférieur, sinon la "
                + "quantité demandée par l'établissement.");

        Tableau etablissements = new Tableau("Établissements", "États des besoins des établissements — "
                + v.perimetre().nom()).sousTitre(sousTitre)
                .colonnes(Colonne.texte("Établissement", 3), Colonne.texte("Direction", 2), Colonne.texte("État", 2),
                        Colonne.texte("Transmis le", 1.2f), Colonne.nombre("Lignes", 0.8f));
        for (EtablissementBesoins e : v.etablissements()) {
            etablissements.ligne(e.nom(), e.direction(), statut(e), e.transmiseLe() == null ? ""
                    : JOUR.format(e.transmiseLe()), e.lignes());
        }

        Tableau directions = new Tableau("Directions", "Transmissions des directions — " + v.perimetre().nom())
                .sousTitre(sousTitre)
                .colonnes(Colonne.texte("Direction", 3), Colonne.texte("Transmission", 1.5f),
                        Colonne.texte("Le", 1.2f), Colonne.nombre("Établissements", 1.2f),
                        Colonne.nombre("États reçus", 1.2f));
        for (DirectionBesoins d : v.directions()) {
            directions.ligne(d.nom(), switch (d.statut()) {
                case TRANSMISE -> "Transmise";
                case RENVOYEE -> "Renvoyée";
                case NON_TRANSMISE -> "Non transmise";
            }, d.transmiseLe() == null ? "" : JOUR.format(d.transmiseLe()), d.etablissements(), d.recues());
        }
        String nom = "besoins-" + v.perimetre().nom().toLowerCase(Locale.ROOT)
                .replaceAll("[àâä]", "a").replaceAll("[éèêë]", "e").replaceAll("[îï]", "i").replaceAll("[ôö]", "o")
                .replaceAll("[ùûü]", "u").replace("ç", "c").replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "")
                + "-" + v.annee();
        return v.directions().isEmpty()
                ? ExportTableaux.reponse(entete, nom, format, consolidation, etablissements)
                : ExportTableaux.reponse(entete, nom, format, consolidation, etablissements, directions);
    }

    static String categorie(Categorie c) {
        return switch (c) {
            case INFRASTRUCTURE -> "Infrastructures";
            case PERSONNEL -> "Personnel";
            case EQUIPEMENT_OUTILS -> "Équipements et outils";
            case MATIERE -> "Matières";
        };
    }

    private static String priorite(int p) {
        return switch (p) {
            case 1 -> "Urgente";
            case 2 -> "Normale";
            default -> "Souhaitable";
        };
    }

    private static String statut(EtablissementBesoins e) {
        return switch (e.statut()) {
            case NON_TRANSMIS -> "Non transmis";
            case RENVOYE -> "Renvoyé pour correction";
            case RECU -> "Reçu";
            case EN_COURS -> "En attente : " + e.etape();
        };
    }
}
