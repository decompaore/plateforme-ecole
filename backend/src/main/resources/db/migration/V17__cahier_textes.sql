-- =====================================================================
-- V17 : cahier de textes
--
--   seance_cahier : ce qui a été fait à chaque cours (contenu, travail à faire),
--                   rattaché si possible à une séquence de la fiche de progression.
--   L'identifiant est créé par le téléphone : un renvoi après une coupure
--   retrouve la même séance au lieu d'en créer une seconde.
-- =====================================================================

CREATE TABLE seance_cahier (
    tenant_id       UUID          NOT NULL,
    id              UUID          NOT NULL,
    classe_id       UUID          NOT NULL,
    matiere_id      UUID          NOT NULL,
    engagement_id   UUID          NOT NULL,
    date_seance     DATE          NOT NULL,
    heure_debut     TIME          NOT NULL,
    heure_fin       TIME          NOT NULL,
    sequence_ordre  SMALLINT      CHECK (sequence_ordre BETWEEN 1 AND 200),
    sequence_titre  VARCHAR(150),
    contenu         VARCHAR(2000) NOT NULL,
    travail_a_faire VARCHAR(1000),
    saisi_par       UUID          REFERENCES utilisateur (id),
    saisi_le        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modifie_le      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, classe_id, matiere_id, date_seance, heure_debut),
    CHECK (heure_fin > heure_debut),
    CHECK ((sequence_ordre IS NULL) = (sequence_titre IS NULL)),
    FOREIGN KEY (tenant_id, classe_id)     REFERENCES classe (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, matiere_id)    REFERENCES matiere (tenant_id, id),
    FOREIGN KEY (tenant_id, engagement_id) REFERENCES engagement_enseignant (tenant_id, id)
);
CREATE INDEX ix_seance_cahier_matiere ON seance_cahier (tenant_id, classe_id, matiere_id, date_seance);

SELECT activer_isolation('seance_cahier');
