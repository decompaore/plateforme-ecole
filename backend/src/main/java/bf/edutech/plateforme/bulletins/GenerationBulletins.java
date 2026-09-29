package bf.edutech.plateforme.bulletins;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.erreurs.RegleMetierException;
import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Génération des bulletins d'une classe pour une période : résultats figés, puis publication. */
@Entity
@Table(name = "generation_bulletins")
public class GenerationBulletins extends EntiteCloisonnee {

    @Column(name = "classe_id", nullable = false, updatable = false)
    private UUID classeId;

    @Column(name = "periode_id", nullable = false, updatable = false)
    private UUID periodeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 8)
    private StatutGeneration statut = StatutGeneration.GENEREE;

    @Column(name = "effectif", nullable = false, updatable = false)
    private int effectif;

    @Column(name = "moyenne_classe", precision = 5, scale = 2, updatable = false)
    private BigDecimal moyenneClasse;

    @Column(name = "plus_forte", precision = 5, scale = 2, updatable = false)
    private BigDecimal plusForte;

    @Column(name = "plus_faible", precision = 5, scale = 2, updatable = false)
    private BigDecimal plusFaible;

    @Column(name = "taux_reussite", precision = 5, scale = 2, updatable = false)
    private BigDecimal tauxReussite;

    @Column(name = "genere_par", updatable = false)
    private UUID generePar;

    @Column(name = "genere_le", nullable = false, updatable = false)
    private Instant genereLe;

    @Column(name = "publie_par")
    private UUID publiePar;

    @Column(name = "publie_le")
    private Instant publieLe;

    protected GenerationBulletins() {
    }

    GenerationBulletins(UUID classeId, UUID periodeId, int effectif, BigDecimal moyenneClasse, BigDecimal plusForte,
            BigDecimal plusFaible, BigDecimal tauxReussite, UUID generePar, Instant genereLe) {
        this.classeId = classeId;
        this.periodeId = periodeId;
        this.effectif = effectif;
        this.moyenneClasse = moyenneClasse;
        this.plusForte = plusForte;
        this.plusFaible = plusFaible;
        this.tauxReussite = tauxReussite;
        this.generePar = generePar;
        this.genereLe = genereLe;
    }

    void publier(UUID par, Instant le) {
        if (statut == StatutGeneration.PUBLIEE) {
            throw new RegleMetierException("BULLETINS_PUBLIES", "Les bulletins sont déjà publiés");
        }
        this.statut = StatutGeneration.PUBLIEE;
        this.publiePar = par;
        this.publieLe = le;
    }

    boolean estPubliee() {
        return statut == StatutGeneration.PUBLIEE;
    }

    UUID getClasseId() {
        return classeId;
    }

    UUID getPeriodeId() {
        return periodeId;
    }

    StatutGeneration getStatut() {
        return statut;
    }

    int getEffectif() {
        return effectif;
    }

    BigDecimal getMoyenneClasse() {
        return moyenneClasse;
    }

    BigDecimal getPlusForte() {
        return plusForte;
    }

    BigDecimal getPlusFaible() {
        return plusFaible;
    }

    BigDecimal getTauxReussite() {
        return tauxReussite;
    }

    Instant getGenereLe() {
        return genereLe;
    }

    Instant getPublieLe() {
        return publieLe;
    }
}
