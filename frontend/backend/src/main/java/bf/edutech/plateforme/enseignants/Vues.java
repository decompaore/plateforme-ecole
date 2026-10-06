package bf.edutech.plateforme.enseignants;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import bf.edutech.plateforme.etablissement.Vues.AffectationVue;
import bf.edutech.plateforme.socle.referentiel.Sexe;

/** Objets de lecture exposés par l'API du module Enseignants. */
public final class Vues {

    private Vues() {
    }

    /**
     * Enseignant vu par l'établissement, à travers son engagement. Tant que
     * l'invitation n'est pas acceptée (ou si elle est refusée), l'identité reste
     * entièrement masquée : l'école ne voit que les conditions qu'elle a proposées.
     * {@code finProgrammee} : engagement encore actif dont la fin est fixée à {@code fin}.
     */
    public record EnseignantVue(UUID engagementId, UUID enseignantId, String nom, String prenoms, String telephone,
            Sexe sexe, String matriculeFp, String specialite, TypeEngagement type, StatutEngagement statut,
            LocalDate debut, LocalDate fin, BigDecimal tauxHoraire, String motifFin, boolean finProgrammee) {

        static EnseignantVue depuis(Engagement e, Enseignant s) {
            boolean visible = e.getStatut() == StatutEngagement.ACTIF || e.getStatut() == StatutEngagement.TERMINE;
            return new EnseignantVue(e.getId(), visible ? e.getEnseignantId() : null, visible ? s.getNom() : null,
                    visible ? s.getPrenoms() : null, visible ? s.getTelephone() : null,
                    visible ? s.getSexe() : null, visible ? s.getMatriculeFp() : null,
                    visible ? s.getSpecialite() : null, e.getType(), e.getStatut(), e.getDebut(), e.getFin(),
                    e.getTauxHoraire(), e.getMotifFin(), e.isFinProgrammee());
        }
    }

    /** Résultat d'un engagement : invitation (identité déjà connue) ou création directe. */
    public record ResultatEngagement(EnseignantVue enseignant, boolean invitation, String motDePasseTemporaire) {
    }

    /** Détail : engagement, matières assurées pendant l'année et charge hebdomadaire (heures). */
    public record FicheEnseignantVue(EnseignantVue enseignant, UUID anneeId, List<AffectationVue> affectations,
            BigDecimal chargeHebdomadaire) {
    }

    /** Invitation reçue par l'enseignant (seule information d'un autre établissement qu'il voit). */
    public record InvitationVue(UUID engagementId, UUID etablissementId, String etablissementNom,
            TypeEngagement type, LocalDate debut, LocalDate fin, BigDecimal tauxHoraire) {
    }

    public record ResultatRecherche(boolean trouve) {
    }
}
