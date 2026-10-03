-- =====================================================================
-- V12 : vie scolaire (version simple)
--   incident    : retard, avertissement, blâme, exclusion temporaire (jamais supprimé, annulable avec motif)
--   convocation : rendez-vous demandé aux parents, puis honoré ou non
-- =====================================================================

CREATE TABLE incident (
    tenant_id        UUID         NOT NULL,
    id               UUID         NOT NULL,
    inscription_id   UUID         NOT NULL,
    type             VARCHAR(22)  NOT NULL
                     CHECK (type IN ('RETARD', 'AVERTISSEMENT', 'BLAME', 'EXCLUSION_TEMPORAIRE')),
    date_faits       DATE         NOT NULL,
    motif            VARCHAR(300) NOT NULL,
    minutes_retard   SMALLINT     CHECK (minutes_retard BETWEEN 1 AND 600),
    debut_exclusion  DATE,
    jours_exclusion  SMALLINT     CHECK (jours_exclusion BETWEEN 1 AND 30),
    famille_prevenue BOOLEAN      NOT NULL DEFAULT false,
    saisi_par        UUID         REFERENCES utilisateur (id),
    saisi_le         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    annule_le        TIMESTAMPTZ,
    annule_par       UUID         REFERENCES utilisateur (id),
    motif_annulation VARCHAR(300),
    PRIMARY KEY (tenant_id, id),
    CHECK ((type = 'RETARD') = (minutes_retard IS NOT NULL)),
    CHECK ((type = 'EXCLUSION_TEMPORAIRE') = (jours_exclusion IS NOT NULL AND debut_exclusion IS NOT NULL)),
    CHECK ((annule_le IS NULL) = (motif_annulation IS NULL)),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id)
);
CREATE INDEX ix_incident_inscription ON incident (tenant_id, inscription_id, date_faits);

CREATE TABLE convocation (
    tenant_id      UUID         NOT NULL,
    id             UUID         NOT NULL,
    inscription_id UUID         NOT NULL,
    incident_id    UUID,
    rendez_vous    TIMESTAMP    NOT NULL,                -- heure locale de l'établissement
    motif          VARCHAR(300) NOT NULL,
    statut         VARCHAR(12)  NOT NULL DEFAULT 'PREVUE'
                   CHECK (statut IN ('PREVUE', 'HONOREE', 'NON_HONOREE', 'ANNULEE')),
    compte_rendu   VARCHAR(500),
    saisi_par      UUID         REFERENCES utilisateur (id),
    saisi_le       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    cloture_par    UUID         REFERENCES utilisateur (id),
    cloture_le     TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    CHECK ((statut = 'PREVUE') = (cloture_le IS NULL)),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id),
    FOREIGN KEY (tenant_id, incident_id)    REFERENCES incident (tenant_id, id)
);
CREATE INDEX ix_convocation_inscription ON convocation (tenant_id, inscription_id);
CREATE INDEX ix_convocation_prevues ON convocation (tenant_id, rendez_vous) WHERE statut = 'PREVUE';

SELECT activer_isolation('incident');
SELECT activer_isolation('convocation');

-- L'historique disciplinaire ne se supprime pas (annulation avec motif seulement)
REVOKE DELETE ON incident FROM ${app_role};
REVOKE DELETE ON convocation FROM ${app_role};
