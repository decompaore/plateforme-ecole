package bf.edutech.plateforme.bulletins;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Ligne figée d'un bulletin (matière, moyenne de rubrique ou module de compétences). */
@Entity
@Table(name = "bulletin_ligne")
public class BulletinLigne extends EntiteCloisonnee {

    @Column(name = "bulletin_id", nullable = false, updatable = false)
    private UUID bulletinId;

    @Column(name = "ordre", nullable = false, updatable = false)
    private short ordre;

    @Enumerated(EnumType.STRING)
    @Column(name = "nature", nullable = false, length = 8, updatable = false)
    private NatureLigne nature;

    @Column(name = "code", length = 20, updatable = false)
    private String code;

    @Column(name = "libelle", nullable = false, length = 120, updatable = false)
    private String libelle;

    @Column(name = "groupe", length = 40, updatable = false)
    private String groupe;

    @Column(name = "coefficient", precision = 4, scale = 1, updatable = false)
    private BigDecimal coefficient;

    @Column(name = "moyenne", precision = 5, scale = 2, updatable = false)
    private BigDecimal moyenne;

    @Column(name = "points", precision = 7, scale = 2, updatable = false)
    private BigDecimal points;

    @Column(name = "rang", updatable = false)
    private Integer rang;

    @Column(name = "sans_note", nullable = false, updatable = false)
    private boolean sansNote;

    @Column(name = "appreciation", length = 200, updatable = false)
    private String appreciation;

    @Column(name = "enseignant", length = 160, updatable = false)
    private String enseignant;

    @Column(name = "competences", updatable = false)
    private Integer competences;

    @Column(name = "acquises", updatable = false)
    private Integer acquises;

    @Column(name = "taux", precision = 5, scale = 2, updatable = false)
    private BigDecimal taux;

    @Column(name = "statut_module", length = 10, updatable = false)
    private String statutModule;

    protected BulletinLigne() {
    }

    BulletinLigne(UUID bulletinId, LigneBulletin l) {
        this.bulletinId = bulletinId;
        this.ordre = (short) l.ordre();
        this.nature = l.nature();
        this.code = l.code();
        this.libelle = l.libelle();
        this.groupe = l.groupe();
        this.coefficient = l.coefficient();
        this.moyenne = l.moyenne();
        this.points = l.points();
        this.rang = l.rang();
        this.sansNote = l.sansNote();
        this.appreciation = l.appreciation();
        this.enseignant = l.enseignant();
        this.competences = l.competences();
        this.acquises = l.acquises();
        this.taux = l.taux();
        this.statutModule = l.statutModule();
    }

    LigneBulletin vers() {
        return new LigneBulletin(ordre, nature, code, libelle, groupe, coefficient, moyenne, points, rang, sansNote,
                appreciation, enseignant, competences, acquises, taux, statutModule);
    }

    UUID getBulletinId() {
        return bulletinId;
    }
}
