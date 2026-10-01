package bf.edutech.plateforme.enseignants;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Engagement d'un enseignant dans l'établissement : titulaire ou vacataire. */
@Entity
@Table(name = "engagement_enseignant")
public class Engagement extends EntiteCloisonnee {

    @Column(name = "enseignant_id", nullable = false, updatable = false)
    private UUID enseignantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 10, updatable = false)
    private TypeEngagement type;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 10)
    private StatutEngagement statut;

    @Column(name = "debut", nullable = false, updatable = false)
    private LocalDate debut;

    @Column(name = "fin")
    private LocalDate fin;

    @Column(name = "taux_horaire", precision = 8, scale = 0)
    private BigDecimal tauxHoraire;

    @Column(name = "motif_fin", length = 200)
    private String motifFin;

    /** La date de fin a été fixée par une fin d'engagement (l'engagement reste actif jusque-là). */
    @Column(name = "fin_programmee", nullable = false)
    private boolean finProgrammee;

    /** Date de fin prévue avant la programmation (contrat de vacataire), restaurée en cas d'annulation. */
    @Column(name = "fin_contrat")
    private LocalDate finContrat;

    protected Engagement() {
    }

    Engagement(UUID enseignantId, TypeEngagement type, StatutEngagement statut, LocalDate debut, LocalDate fin,
            BigDecimal tauxHoraire) {
        this.enseignantId = enseignantId;
        this.type = type;
        this.statut = statut;
        this.debut = debut;
        this.fin = fin;
        this.tauxHoraire = tauxHoraire;
    }

    void accepter() {
        exigerStatut(StatutEngagement.INVITE);
        this.statut = StatutEngagement.ACTIF;
    }

    void refuser() {
        exigerStatut(StatutEngagement.INVITE);
        this.statut = StatutEngagement.REFUSE;
    }

    /** Fin immédiate : la date de fin est déjà passée. */
    void terminer(LocalDate date, String motif) {
        exigerStatut(StatutEngagement.ACTIF);
        verifierDateFin(date);
        this.statut = StatutEngagement.TERMINE;
        this.fin = date;
        this.motifFin = motif;
        this.finProgrammee = false;
        this.finContrat = null;
    }

    /**
     * Fin programmée : l'engagement reste actif jusqu'au soir de {@code date}, puis le
     * traitement quotidien le clôt. La période est raccourcie tout de suite, ce qui libère
     * le poste de titulaire à partir du lendemain pour un autre établissement.
     */
    void programmerFin(LocalDate date, String motif) {
        exigerStatut(StatutEngagement.ACTIF);
        verifierDateFin(date);
        LocalDate prevue = finProgrammee ? finContrat : fin;
        if (prevue != null && date.isAfter(prevue)) {
            throw new RegleMetierException("FIN_APRES_CONTRAT",
                    "La date de fin ne peut dépasser la fin prévue de l'engagement (" + prevue + ")");
        }
        if (!finProgrammee) {
            this.finContrat = fin;
            this.finProgrammee = true;
        }
        this.fin = date;
        this.motifFin = motif;
    }

    /** Annule une fin programmée : l'engagement retrouve sa date de fin d'origine. */
    void annulerFin() {
        exigerStatut(StatutEngagement.ACTIF);
        if (!finProgrammee) {
            throw new RegleMetierException("AUCUNE_FIN_PROGRAMMEE", "Aucune fin d'engagement n'est programmée");
        }
        this.fin = finContrat;
        this.motifFin = null;
        this.finProgrammee = false;
        this.finContrat = null;
    }

    /** Clôture par le traitement quotidien : la date de fin (programmée ou de contrat) est passée. */
    void cloturer() {
        exigerStatut(StatutEngagement.ACTIF);
        this.statut = StatutEngagement.TERMINE;
        if (motifFin == null) {
            this.motifFin = "Fin de contrat";
        }
        this.finProgrammee = false;
        this.finContrat = null;
    }

    private void verifierDateFin(LocalDate date) {
        if (date.isBefore(debut)) {
            throw new IllegalArgumentException("La date de fin ne peut précéder le début de l'engagement");
        }
    }

    private void exigerStatut(StatutEngagement attendu) {
        if (statut != attendu) {
            throw new RegleMetierException("STATUT_ENGAGEMENT", "Opération impossible : l'engagement est "
                    + statut.name().toLowerCase() + " (attendu : " + attendu.name().toLowerCase() + ")");
        }
    }

    UUID getEnseignantId() {
        return enseignantId;
    }

    TypeEngagement getType() {
        return type;
    }

    StatutEngagement getStatut() {
        return statut;
    }

    LocalDate getDebut() {
        return debut;
    }

    LocalDate getFin() {
        return fin;
    }

    BigDecimal getTauxHoraire() {
        return tauxHoraire;
    }

    String getMotifFin() {
        return motifFin;
    }

    boolean isFinProgrammee() {
        return finProgrammee;
    }
}
