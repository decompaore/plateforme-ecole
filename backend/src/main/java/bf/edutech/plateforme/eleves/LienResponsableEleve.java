package bf.edutech.plateforme.eleves;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import bf.edutech.plateforme.socle.persistance.EntiteCloisonnee;

/** Lien entre un élève et l'un de ses responsables ; un seul contact prioritaire par élève. */
@Entity
@Table(name = "lien_responsable_eleve")
public class LienResponsableEleve extends EntiteCloisonnee {

    @Column(name = "eleve_id", nullable = false, updatable = false)
    private UUID eleveId;

    @Column(name = "responsable_id", nullable = false, updatable = false)
    private UUID responsableId;

    @Enumerated(EnumType.STRING)
    @Column(name = "lien", nullable = false, length = 10)
    private LienParente lien;

    @Column(name = "responsable_legal", nullable = false)
    private boolean responsableLegal;

    @Column(name = "contact_prioritaire", nullable = false)
    private boolean contactPrioritaire;

    protected LienResponsableEleve() {
    }

    LienResponsableEleve(UUID eleveId, UUID responsableId, LienParente lien, boolean responsableLegal,
            boolean contactPrioritaire) {
        this.eleveId = eleveId;
        this.responsableId = responsableId;
        this.lien = lien;
        this.responsableLegal = responsableLegal;
        this.contactPrioritaire = contactPrioritaire;
    }

    void definirContactPrioritaire(boolean valeur) {
        this.contactPrioritaire = valeur;
    }

    UUID getEleveId() {
        return eleveId;
    }

    UUID getResponsableId() {
        return responsableId;
    }

    LienParente getLien() {
        return lien;
    }

    boolean isResponsableLegal() {
        return responsableLegal;
    }

    boolean isContactPrioritaire() {
        return contactPrioritaire;
    }
}
