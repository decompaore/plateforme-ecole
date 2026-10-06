package bf.edutech.plateforme.socle.persistance;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * Classe de base des entités identifiées par un UUID généré par l'application.
 * <p>
 * Implémente {@link Persistable} pour que Spring Data fasse un INSERT direct
 * (et non un SELECT suivi d'un MERGE) lors du premier enregistrement.
 */
@MappedSuperclass
public abstract class EntiteUuid implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Transient
    private boolean nouveau = true;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return nouveau;
    }

    /** Permet d'imposer un identifiant connu à l'avance (ex. création d'un établissement). */
    protected void imposerId(UUID identifiant) {
        this.id = Objects.requireNonNull(identifiant);
    }

    @PostLoad
    @PostPersist
    void marquerCommeExistant() {
        this.nouveau = false;
    }

    @Override
    public boolean equals(Object autre) {
        if (this == autre) {
            return true;
        }
        if (autre == null || getClass() != autre.getClass()) {
            return false;
        }
        return id.equals(((EntiteUuid) autre).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
