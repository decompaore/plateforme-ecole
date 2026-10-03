package bf.edutech.plateforme.progression;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import bf.edutech.plateforme.etablissement.TypeMatiere;

/** Objets échangés par l'API de la progression pédagogique. */
public final class Vues {

    private Vues() {
    }

    /** Domaine de supervision : le censeur suit le général, le chef des travaux le technique et le pratique. */
    public enum Domaine {
        GENERAL,
        TECHNIQUE;

        static Domaine de(TypeMatiere type) {
            return type == TypeMatiere.GENERALE ? GENERAL : TECHNIQUE;
        }
    }

    public record SaisieSequence(String titre, String contenu, String competences, BigDecimal heuresPrevues,
            LocalDate semaineDebut) {
    }

    public record DonneesFiche(List<SaisieSequence> sequences) {
    }

    public record DemandeVisa(Boolean accepte, String commentaire) {
    }

    public record SequenceVue(int ordre, String titre, String contenu, String competences, BigDecimal heuresPrevues,
            LocalDate semaineDebut) {
    }

    /**
     * Fiche d'une matière dans une classe ; {@code id} et {@code statut} sont null tant que
     * l'enseignant ne l'a pas commencée. {@code volumeHebdo} et {@code volumeTotal} viennent du
     * programme de la classe, pour comparer avec les heures prévues.
     */
    public record FicheVue(UUID id, UUID classeId, String classeCode, UUID matiereId, String matiereCode,
            String matiereLibelle, TypeMatiere type, Domaine domaine, UUID engagementId, String enseignant,
            StatutFiche statut, List<SequenceVue> sequences, BigDecimal heuresPrevues, BigDecimal volumeHebdo,
            BigDecimal volumeTotal, Instant modifieeLe, Instant soumiseLe, Instant viseLe, String visePar,
            String commentaireVisa, boolean modifiable, boolean visable) {
    }

    /** Une ligne du suivi : chaque matière du programme de chaque classe, fiche commencée ou non. */
    public record SuiviProgressionVue(UUID classeId, String classeCode, String niveau, UUID matiereId,
            String matiereCode, String matiereLibelle, TypeMatiere type, Domaine domaine, UUID engagementId,
            String enseignant, StatutFiche statut, int sequences, BigDecimal heuresPrevues, BigDecimal volumeHebdo,
            Instant soumiseLe, Instant viseLe) {
    }
}
