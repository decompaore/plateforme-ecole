package bf.edutech.plateforme.progression;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Séquence d'une fiche de progression (chapitre, module ou compétence à faire acquérir). */
@Entity
@Table(name = "sequence_progression")
public class SequenceProgression extends EntiteCloisonnee {

    @Column(name = "fiche_id", nullable = false, updatable = false)
    private UUID ficheId;

    @Column(name = "ordre", nullable = false)
    private short ordre;

    @Column(name = "titre", nullable = false, length = 150)
    private String titre;

    @Column(name = "contenu", length = 2000)
    private String contenu;

    @Column(name = "competences", length = 500)
    private String competences;

    @Column(name = "heures_prevues", nullable = false, precision = 5, scale = 1)
    private BigDecimal heuresPrevues;

    @Column(name = "semaine_debut")
    private LocalDate semaineDebut;

    protected SequenceProgression() {
    }

    SequenceProgression(UUID ficheId, int ordre, String titre, String contenu, String competences,
            BigDecimal heuresPrevues, LocalDate semaineDebut) {
        this.ficheId = ficheId;
        this.ordre = (short) ordre;
        this.titre = titre;
        this.contenu = contenu;
        this.competences = competences;
        this.heuresPrevues = heuresPrevues;
        this.semaineDebut = semaineDebut;
    }

    public UUID getFicheId() {
        return ficheId;
    }

    public int getOrdre() {
        return ordre;
    }

    public String getTitre() {
        return titre;
    }

    public String getContenu() {
        return contenu;
    }

    public String getCompetences() {
        return competences;
    }

    public BigDecimal getHeuresPrevues() {
        return heuresPrevues;
    }

    public LocalDate getSemaineDebut() {
        return semaineDebut;
    }
}
