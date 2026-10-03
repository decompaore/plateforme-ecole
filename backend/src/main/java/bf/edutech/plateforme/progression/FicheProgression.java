package bf.edutech.plateforme.progression;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Fiche de progression d'une matière dans une classe (une seule par classe et matière). */
@Entity
@Table(name = "fiche_progression")
public class FicheProgression extends EntiteCloisonnee {

    @Column(name = "classe_id", nullable = false, updatable = false)
    private UUID classeId;

    @Column(name = "matiere_id", nullable = false, updatable = false)
    private UUID matiereId;

    @Column(name = "engagement_id", nullable = false)
    private UUID engagementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 10)
    private StatutFiche statut = StatutFiche.BROUILLON;

    @Column(name = "modifiee_le", nullable = false)
    private Instant modifieeLe;

    @Column(name = "soumise_le")
    private Instant soumiseLe;

    @Column(name = "vise_par")
    private UUID visePar;

    @Column(name = "vise_le")
    private Instant viseLe;

    @Column(name = "commentaire_visa", length = 500)
    private String commentaireVisa;

    protected FicheProgression() {
    }

    FicheProgression(UUID classeId, UUID matiereId, UUID engagementId, Instant maintenant) {
        this.classeId = classeId;
        this.matiereId = matiereId;
        this.engagementId = engagementId;
        this.modifieeLe = maintenant;
    }

    /**
     * L'enseignant modifie ses séquences. Une fiche en attente de visa ne bouge pas ; une fiche
     * visée repasse en brouillon (la modification devra être visée à son tour).
     */
    void modifier(UUID engagementId, Instant maintenant) {
        if (statut == StatutFiche.SOUMISE) {
            throw new RegleMetierException("FICHE_SOUMISE",
                    "La fiche attend le visa : elle ne peut pas être modifiée avant la réponse");
        }
        if (statut == StatutFiche.VISEE) {
            statut = StatutFiche.BROUILLON;
            visePar = null;
            viseLe = null;
            commentaireVisa = null;
        }
        this.engagementId = engagementId;
        this.modifieeLe = maintenant;
    }

    void soumettre(Instant maintenant) {
        if (statut == StatutFiche.SOUMISE || statut == StatutFiche.VISEE) {
            throw new RegleMetierException("FICHE_DEJA_SOUMISE",
                    statut == StatutFiche.VISEE ? "La fiche est déjà visée" : "La fiche est déjà soumise");
        }
        statut = StatutFiche.SOUMISE;
        soumiseLe = maintenant;
        visePar = null;
        viseLe = null;
        commentaireVisa = null;
    }

    void viser(boolean accepte, String commentaire, UUID par, Instant maintenant) {
        if (statut != StatutFiche.SOUMISE) {
            throw new RegleMetierException("FICHE_NON_SOUMISE", "Seule une fiche soumise peut être visée ou renvoyée");
        }
        if (!accepte && (commentaire == null || commentaire.isBlank())) {
            throw new RegleMetierException("COMMENTAIRE_OBLIGATOIRE",
                    "Dites à l'enseignant ce qu'il faut revoir");
        }
        statut = accepte ? StatutFiche.VISEE : StatutFiche.A_REVOIR;
        visePar = par;
        viseLe = maintenant;
        commentaireVisa = commentaire == null || commentaire.isBlank() ? null : commentaire.strip();
    }

    public UUID getClasseId() {
        return classeId;
    }

    public UUID getMatiereId() {
        return matiereId;
    }

    public UUID getEngagementId() {
        return engagementId;
    }

    public StatutFiche getStatut() {
        return statut;
    }

    public Instant getModifieeLe() {
        return modifieeLe;
    }

    public Instant getSoumiseLe() {
        return soumiseLe;
    }

    public UUID getVisePar() {
        return visePar;
    }

    public Instant getViseLe() {
        return viseLe;
    }

    public String getCommentaireVisa() {
        return commentaireVisa;
    }
}
