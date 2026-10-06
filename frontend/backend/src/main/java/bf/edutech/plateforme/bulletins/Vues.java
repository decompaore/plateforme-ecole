package bf.edutech.plateforme.bulletins;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Objets échangés par l'API du module Bulletins. */
public final class Vues {

    private Vues() {
    }

    /** Appréciation saisie ; un texte vide l'efface. */
    public record SaisieAppreciation(UUID inscriptionId, String texte) {
    }

    public record AppreciationVue(UUID inscriptionId, String nom, String prenoms, String texte) {
    }

    /** Avis du conseil ; {@code distinction} null : la distinction proposée par les seuils s'appliquera. */
    public record SaisieAvis(UUID inscriptionId, Distinction distinction, String appreciation) {
    }

    public record AvisVue(UUID inscriptionId, String nom, String prenoms, BigDecimal moyenne, Integer rang,
            BigDecimal tauxMaitrise, Distinction distinctionProposee, Distinction distinction, String appreciation) {
    }

    public record BulletinResumeVue(UUID id, UUID inscriptionId, String matricule, String nom, String prenoms,
            BigDecimal moyenne, Integer rang, boolean admis, BigDecimal tauxMaitrise, Distinction distinction,
            String codeVerification) {
    }

    public record GenerationVue(UUID id, UUID classeId, String classeCode, UUID periodeId, String periodeLibelle,
            StatutGeneration statut, int effectif, BigDecimal moyenneClasse, BigDecimal tauxReussite,
            Instant genereLe, Instant publieLe, List<BulletinResumeVue> bulletins) {
    }

    /** Bulletin publié, vu par le parent. */
    public record BulletinEleveVue(UUID id, String periode, String classe, BigDecimal moyenne, Integer rang,
            int effectif, BigDecimal tauxMaitrise, Distinction distinction, Instant publieLe) {
    }

    /** Réponse de la vérification publique : ce qui figure déjà sur le document papier. */
    public record VerificationVue(String etablissement, String eleve, String matricule, String classe,
            String periode, String annee, BigDecimal moyenne, Integer rang, int effectif, Instant publieLe) {
    }
}
