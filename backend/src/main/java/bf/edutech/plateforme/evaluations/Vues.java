package bf.edutech.plateforme.evaluations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import bf.edutech.plateforme.pedagogie.CodeModele;

/** Objets échangés par l'API du module Évaluations. */
public final class Vues {

    private Vues() {
    }

    public record EvaluationVue(UUID id, UUID classeId, UUID matiereId, String matiereCode, UUID periodeId,
            String libelle, TypeEvaluation type, LocalDate date, BigDecimal bareme, BigDecimal poids,
            int notesSaisies, int effectif) {
    }

    /** Note saisie : une valeur, ou {@code absent} ; ni l'une ni l'autre efface la note. */
    public record SaisieNote(UUID inscriptionId, BigDecimal valeur, Boolean absent) {

        public SaisieNote {
            absent = Boolean.TRUE.equals(absent); // champ facultatif dans le JSON
        }
    }

    public record LigneNoteVue(UUID inscriptionId, String matricule, String nom, String prenoms, BigDecimal valeur,
            boolean absent) {
    }

    /** Feuille de notes d'une évaluation : tous les élèves actifs de la classe, notés ou non. */
    public record FeuilleNotesVue(EvaluationVue evaluation, List<LigneNoteVue> lignes, List<UUID> ignorees) {
    }

    public record CompetenceVue(UUID id, UUID matiereId, String code, String libelle, int ordre, boolean actif) {
    }

    /** Niveau saisi pour une compétence ; {@code niveau} null efface l'évaluation. */
    public record SaisieCompetence(UUID inscriptionId, UUID competenceId, NiveauMaitrise niveau) {
    }

    public record NiveauVue(UUID competenceId, NiveauMaitrise niveau) {
    }

    public record LigneCompetencesVue(UUID inscriptionId, String nom, String prenoms, List<NiveauVue> niveaux) {
    }

    /** Grille d'un module : compétences en colonnes, apprenants en lignes. */
    public record GrilleCompetencesVue(UUID matiereId, UUID periodeId, List<CompetenceVue> competences,
            List<LigneCompetencesVue> lignes) {
    }

    // ------------------------------------------------------------------
    // Résultats d'une période
    // ------------------------------------------------------------------

    public record AlerteVue(String code, String message) {
    }

    public record MoyenneMatiereVue(UUID matiereId, String code, String libelle, String groupe,
            BigDecimal coefficient, BigDecimal moyenne, BigDecimal points, int notes, Integer rang,
            boolean sansNote) {
    }

    public record MoyenneGroupeVue(String groupe, BigDecimal moyenne, BigDecimal coefficients) {
    }

    public record ModuleVue(UUID matiereId, String code, String libelle, int competences, int evaluees,
            int acquises, BigDecimal taux, StatutModule statut) {
    }

    public record ResultatEleveVue(UUID inscriptionId, String matricule, String nom, String prenoms,
            BigDecimal moyenne, Integer rang, boolean admis, BigDecimal tauxMaitrise,
            List<String> matieresEliminatoires, List<MoyenneMatiereVue> matieres, List<MoyenneGroupeVue> groupes,
            List<ModuleVue> modules) {
    }

    public record ResultatsPeriodeVue(UUID classeId, String classeCode, UUID periodeId, String periodeLibelle,
            CodeModele modele, int effectif, BigDecimal moyenneClasse, BigDecimal plusForte, BigDecimal plusFaible,
            BigDecimal tauxReussite, List<AlerteVue> alertes, List<ResultatEleveVue> eleves) {
    }
}
