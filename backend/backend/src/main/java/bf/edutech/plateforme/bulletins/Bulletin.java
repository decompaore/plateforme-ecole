package bf.edutech.plateforme.bulletins;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Bulletin d'un élève : résultats figés au moment de la génération, et son PDF. */
@Entity
@Table(name = "bulletin")
public class Bulletin extends EntiteCloisonnee {

    @Column(name = "generation_id", nullable = false, updatable = false)
    private UUID generationId;

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "matricule", nullable = false, length = 30, updatable = false)
    private String matricule;

    @Column(name = "nom", nullable = false, length = 80, updatable = false)
    private String nom;

    @Column(name = "prenoms", nullable = false, length = 120, updatable = false)
    private String prenoms;

    @Column(name = "moyenne", precision = 5, scale = 2, updatable = false)
    private BigDecimal moyenne;

    @Column(name = "rang", updatable = false)
    private Integer rang;

    @Column(name = "admis", nullable = false, updatable = false)
    private boolean admis;

    @Column(name = "taux_maitrise", precision = 5, scale = 2, updatable = false)
    private BigDecimal tauxMaitrise;

    @Enumerated(EnumType.STRING)
    @Column(name = "distinction", nullable = false, length = 24, updatable = false)
    private Distinction distinction;

    @Column(name = "appreciation_generale", length = 300, updatable = false)
    private String appreciationGenerale;

    @Column(name = "heures_absence", nullable = false, precision = 6, scale = 1, updatable = false)
    private BigDecimal heuresAbsence;

    @Column(name = "heures_non_justifiees", nullable = false, precision = 6, scale = 1, updatable = false)
    private BigDecimal heuresNonJustifiees;

    @Column(name = "retards", nullable = false, updatable = false)
    private int retards;

    @Column(name = "code_verification", nullable = false, length = 12, updatable = false)
    private String codeVerification;

    @Column(name = "pdf", nullable = false)
    private byte[] pdf;

    protected Bulletin() {
    }

    Bulletin(UUID generationId, UUID inscriptionId, String matricule, String nom, String prenoms, BigDecimal moyenne, Integer rang, boolean admis,
            BigDecimal tauxMaitrise, Distinction distinction, String appreciationGenerale, BigDecimal heuresAbsence,
            BigDecimal heuresNonJustifiees, int retards, String codeVerification) {
        this.generationId = generationId;
        this.inscriptionId = inscriptionId;
        this.matricule = matricule;
        this.nom = nom;
        this.prenoms = prenoms;
        this.moyenne = moyenne;
        this.rang = rang;
        this.admis = admis;
        this.tauxMaitrise = tauxMaitrise;
        this.distinction = distinction;
        this.appreciationGenerale = appreciationGenerale;
        this.heuresAbsence = heuresAbsence;
        this.heuresNonJustifiees = heuresNonJustifiees;
        this.retards = retards;
        this.codeVerification = codeVerification;
    }

    void joindrePdf(byte[] contenu) {
        this.pdf = contenu;
    }

    UUID getGenerationId() {
        return generationId;
    }

    UUID getInscriptionId() {
        return inscriptionId;
    }

    String getMatricule() {
        return matricule;
    }

    String getNom() {
        return nom;
    }

    String getPrenoms() {
        return prenoms;
    }

    BigDecimal getMoyenne() {
        return moyenne;
    }

    Integer getRang() {
        return rang;
    }

    boolean isAdmis() {
        return admis;
    }

    BigDecimal getTauxMaitrise() {
        return tauxMaitrise;
    }

    Distinction getDistinction() {
        return distinction;
    }

    String getAppreciationGenerale() {
        return appreciationGenerale;
    }

    BigDecimal getHeuresAbsence() {
        return heuresAbsence;
    }

    BigDecimal getHeuresNonJustifiees() {
        return heuresNonJustifiees;
    }

    int getRetards() {
        return retards;
    }

    String getCodeVerification() {
        return codeVerification;
    }

    byte[] getPdf() {
        return pdf;
    }
}
