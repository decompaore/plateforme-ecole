package bf.edutech.plateforme.pedagogie;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/**
 * Profil pédagogique : rattaché aux filières, il détermine comment une classe
 * est évaluée, comment l'année est découpée, et quels mots l'interface emploie.
 */
@Entity
@Table(name = "profil_pedagogique")
public class ProfilPedagogique extends EntiteCloisonnee {

    @Column(name = "code", nullable = false, length = 30, updatable = false)
    private String code;

    @Column(name = "libelle", nullable = false, length = 120)
    private String libelle;

    @Enumerated(EnumType.STRING)
    @Column(name = "ordre_enseignement", nullable = false, length = 15)
    private OrdreEnseignement ordre;

    @Enumerated(EnumType.STRING)
    @Column(name = "modele_evaluation", nullable = false, length = 25)
    private CodeModele modele;

    @Enumerated(EnumType.STRING)
    @Column(name = "decoupage", nullable = false, length = 10)
    private Decoupage decoupage;

    @Column(name = "gabarit_document", nullable = false, length = 40)
    private String gabaritDocument;

    @Column(name = "seuil_admission", nullable = false, precision = 4, scale = 2)
    private BigDecimal seuilAdmission = BigDecimal.TEN;

    @Column(name = "decimales_moyenne", nullable = false)
    private short decimalesMoyenne = 2;

    @Column(name = "note_eliminatoire", precision = 4, scale = 2)
    private BigDecimal noteEliminatoire;

    @Column(name = "seuil_maitrise", precision = 5, scale = 2)
    private BigDecimal seuilMaitrise;

    @Column(name = "libelle_apprenant", nullable = false, length = 30)
    private String libelleApprenant = "Élève";

    @Column(name = "libelle_groupe", nullable = false, length = 30)
    private String libelleGroupe = "Classe";

    @Column(name = "libelle_matiere", nullable = false, length = 30)
    private String libelleMatiere = "Matière";

    @Column(name = "libelle_enseignant", nullable = false, length = 30)
    private String libelleEnseignant = "Professeur";

    @Column(name = "actif", nullable = false)
    private boolean actif = true;

    protected ProfilPedagogique() {
    }

    ProfilPedagogique(String code, String libelle, OrdreEnseignement ordre, CodeModele modele, Decoupage decoupage,
            String gabaritDocument) {
        this.code = code;
        this.libelle = libelle;
        this.ordre = ordre;
        this.modele = modele;
        this.decoupage = decoupage;
        this.gabaritDocument = gabaritDocument;
    }

    void modifierStructure(OrdreEnseignement ordre, CodeModele modele, Decoupage decoupage) {
        this.ordre = ordre;
        this.modele = modele;
        this.decoupage = decoupage;
    }

    void modifierRegles(String libelle, String gabaritDocument, BigDecimal seuilAdmission, short decimalesMoyenne,
            BigDecimal noteEliminatoire, BigDecimal seuilMaitrise) {
        this.libelle = libelle;
        this.gabaritDocument = gabaritDocument;
        this.seuilAdmission = seuilAdmission;
        this.decimalesMoyenne = decimalesMoyenne;
        this.noteEliminatoire = noteEliminatoire;
        this.seuilMaitrise = seuilMaitrise;
    }

    void modifierVocabulaire(String apprenant, String groupe, String matiere, String enseignant) {
        this.libelleApprenant = apprenant;
        this.libelleGroupe = groupe;
        this.libelleMatiere = matiere;
        this.libelleEnseignant = enseignant;
    }

    void changerActivation(boolean actif) {
        this.actif = actif;
    }

    ProfilVue versVue() {
        return new ProfilVue(getId(), code, libelle, ordre, modele, decoupage, gabaritDocument, seuilAdmission,
                decimalesMoyenne, noteEliminatoire, seuilMaitrise,
                new ProfilVue.Vocabulaire(libelleApprenant, libelleGroupe, libelleMatiere, libelleEnseignant), actif);
    }

    String getCode() {
        return code;
    }

    OrdreEnseignement getOrdre() {
        return ordre;
    }

    CodeModele getModele() {
        return modele;
    }

    Decoupage getDecoupage() {
        return decoupage;
    }
}
