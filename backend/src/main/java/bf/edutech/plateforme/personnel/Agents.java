package bf.edutech.plateforme.personnel;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import bf.edutech.plateforme.personnel.Vues.Categorie;
import bf.edutech.plateforme.personnel.Vues.Situation;
import bf.edutech.plateforme.socle.erreurs.AccesRefuseException;
import bf.edutech.plateforme.socle.erreurs.RessourceIntrouvableException;
import bf.edutech.plateforme.socle.securite.UtilisateurConnecte;

/** Lecture d'un dossier (ligne de la table {@code agent}), partagée par les services du module. */
@Component
class Agents {

    record Agent(UUID id, UUID utilisateurId, UUID enseignantId, Categorie categorie, String nom, String prenoms,
            String sexe, LocalDate dateNaissance, String lieuNaissance, String telephone, String adresse,
            String matricule, Situation situation, String emploi, String grade, String categorieEchelle,
            String echelon, String fonction, LocalDate datePremierePriseService, LocalDate dateEntreeGrade,
            LocalDate datePriseService, LocalDate dateCessation, String motifCessation, String observations,
            Instant modifieLe) {

        String nomComplet() {
            return nom + " " + prenoms;
        }
    }

    static final String COLONNES = """
            id, utilisateur_id, enseignant_id, categorie, nom, prenoms, sexe, date_naissance, lieu_naissance, telephone,
            adresse, matricule, situation, emploi, grade, categorie_echelle, echelon, fonction,
            date_premiere_prise_service, date_entree_grade, date_prise_service, date_cessation, motif_cessation,
            observations, modifie_le""";

    private final JdbcTemplate jdbc;

    Agents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Agent charger(UUID id) {
        return jdbc.query("select " + COLONNES + " from agent where id = ?", (r, n) -> lire(r), id).stream().findFirst()
                .orElseThrow(() -> new RessourceIntrouvableException("Dossier introuvable"));
    }

    Optional<Agent> duCompte(UUID utilisateurId) {
        return jdbc.query("select " + COLONNES + " from agent where utilisateur_id = ?", (r, n) -> lire(r), utilisateurId)
                .stream().findFirst();
    }

    /** Le dossier de l'utilisateur connecté (« Mon dossier »). */
    Agent moi() {
        return duCompte(UtilisateurConnecte.id()).orElseThrow(() -> new RessourceIntrouvableException(
                "Votre dossier n'est pas encore ouvert : adressez-vous au secrétariat"));
    }

    /** Le dossier, s'il appartient à l'utilisateur connecté ou s'il peut consulter les dossiers. */
    Agent lisible(UUID id) {
        Agent a = charger(id);
        if (!UtilisateurConnecte.aUnRole("ADMIN_ECOLE", "SECRETARIAT", "CENSEUR")
                && !UtilisateurConnecte.id().equals(a.utilisateurId())) {
            throw new AccesRefuseException("Ce dossier ne vous concerne pas");
        }
        return a;
    }

    static Agent lire(ResultSet r) throws SQLException {
        String situation = r.getString(13);
        return new Agent(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class),
                Categorie.valueOf(r.getString(4)), r.getString(5), r.getString(6), r.getString(7),
                r.getObject(8, LocalDate.class), r.getString(9), r.getString(10), r.getString(11), r.getString(12),
                situation == null ? null : Situation.valueOf(situation), r.getString(14), r.getString(15),
                r.getString(16), r.getString(17), r.getString(18), r.getObject(19, LocalDate.class),
                r.getObject(20, LocalDate.class), r.getObject(21, LocalDate.class), r.getObject(22, LocalDate.class),
                r.getString(23), r.getString(24), r.getTimestamp(25).toInstant());
    }
}
