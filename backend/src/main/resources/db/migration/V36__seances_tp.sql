-- =====================================================================
-- V36 : relation chef d'atelier – enseignants des ateliers (v0.47.2)
--   - commandes de production (tables-bancs, tenues…) exécutées par les
--     ateliers et intégrées aux travaux pratiques ;
--   - séances de travaux pratiques datées : planning d'occupation,
--     fiche de préparation (matière d'œuvre), mise à disposition,
--     compte rendu de l'enseignant, bilan de consommation ;
--   - consignes de sécurité de l'atelier et leur prise de connaissance ;
--   - journal hygiène, sécurité, environnement (HSE) de l'atelier.
-- =====================================================================

CREATE TABLE commande_production (
    tenant_id      UUID           NOT NULL REFERENCES tenant (id),
    id             UUID           NOT NULL,
    numero         VARCHAR(20)    NOT NULL,
    atelier_id     UUID           NOT NULL,
    client         VARCHAR(200)   NOT NULL,
    type_client    VARCHAR(7)     NOT NULL CHECK (type_client IN ('PUBLIC', 'PRIVE', 'INTERNE')),
    objet          VARCHAR(300)   NOT NULL,
    quantite       NUMERIC(12,2)  NOT NULL CHECK (quantite > 0),
    unite          VARCHAR(30)    NOT NULL,
    montant        BIGINT         CHECK (montant >= 0),
    date_commande  DATE           NOT NULL,
    echeance       DATE,
    statut         VARCHAR(9)     NOT NULL DEFAULT 'EN_COURS'
                                  CHECK (statut IN ('EN_COURS', 'TERMINEE', 'LIVREE', 'ANNULEE')),
    livree_le      DATE,
    observations   VARCHAR(1000),
    cree_par       UUID,
    cree_le        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, numero),
    FOREIGN KEY (tenant_id, atelier_id) REFERENCES atelier (tenant_id, id),
    CHECK (echeance IS NULL OR echeance >= date_commande),
    CHECK ((statut = 'LIVREE') = (livree_le IS NOT NULL))
);
SELECT activer_isolation('commande_production');

CREATE TABLE seance_tp (
    tenant_id          UUID           NOT NULL,
    id                 UUID           NOT NULL,
    atelier_id         UUID           NOT NULL,
    classe_id          UUID           NOT NULL,
    matiere_id         UUID           NOT NULL,
    engagement_id      UUID           NOT NULL,
    date_seance        DATE           NOT NULL,
    heure_debut        TIME           NOT NULL,
    heure_fin          TIME           NOT NULL,
    groupe             VARCHAR(20),
    titre              VARCHAR(200)   NOT NULL,
    effectif           SMALLINT       CHECK (effectif BETWEEN 1 AND 200),
    production_id      UUID,
    origine            VARCHAR(16)    NOT NULL DEFAULT 'DEMANDE' CHECK (origine IN ('DEMANDE', 'EMPLOI_DU_TEMPS')),
    statut             VARCHAR(9)     NOT NULL DEFAULT 'DEMANDEE'
                                      CHECK (statut IN ('DEMANDEE', 'PLANIFIEE', 'REFUSEE', 'REALISEE', 'ANNULEE')),
    motif              VARCHAR(500),              -- refus ou annulation
    demandee_par       UUID,
    demandee_le        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    planifiee_par      UUID,
    planifiee_le       TIMESTAMPTZ,
    -- Mise à disposition par le chef d'atelier
    preparee_par       UUID,
    preparee_le        TIMESTAMPTZ,
    observations_preparation VARCHAR(500),
    -- Compte rendu de l'enseignant
    realisee_le        TIMESTAMPTZ,
    nettoyage          BOOLEAN,
    rangement          BOOLEAN,
    epi_respectes      BOOLEAN,
    quantite_produite  NUMERIC(12,2)  CHECK (quantite_produite >= 0),
    observations       VARCHAR(1000),
    -- Bilan de consommation par le chef d'atelier
    bilan_par          UUID,
    bilan_le           TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    CHECK (heure_fin > heure_debut),
    FOREIGN KEY (tenant_id, atelier_id)    REFERENCES atelier (tenant_id, id),
    FOREIGN KEY (tenant_id, classe_id)     REFERENCES classe (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, matiere_id)    REFERENCES matiere (tenant_id, id),
    FOREIGN KEY (tenant_id, engagement_id) REFERENCES engagement_enseignant (tenant_id, id),
    FOREIGN KEY (tenant_id, production_id) REFERENCES commande_production (tenant_id, id)
);
CREATE INDEX ix_seance_tp_atelier ON seance_tp (tenant_id, atelier_id, date_seance);
CREATE INDEX ix_seance_tp_enseignant ON seance_tp (tenant_id, engagement_id, date_seance);
-- Une séance de l'emploi du temps n'est générée qu'une fois pour une date
CREATE UNIQUE INDEX ux_seance_tp_generee ON seance_tp (tenant_id, atelier_id, date_seance, heure_debut, classe_id,
    coalesce(groupe, '')) WHERE statut <> 'ANNULEE' AND statut <> 'REFUSEE';
SELECT activer_isolation('seance_tp');

-- Fiche de préparation : matière d'œuvre demandée, accordée, servie, utilisée
CREATE TABLE besoin_seance_tp (
    tenant_id           UUID           NOT NULL,
    id                  UUID           NOT NULL,
    seance_id           UUID           NOT NULL,
    article_id          UUID           NOT NULL,
    quantite_demandee   NUMERIC(12,2)  NOT NULL CHECK (quantite_demandee > 0),
    quantite_accordee   NUMERIC(12,2)  CHECK (quantite_accordee >= 0),
    quantite_a_acheter  NUMERIC(12,2)  CHECK (quantite_a_acheter >= 0),
    quantite_servie     NUMERIC(12,2)  CHECK (quantite_servie >= 0),
    quantite_utilisee   NUMERIC(12,2)  CHECK (quantite_utilisee >= 0),
    quantite_rendue     NUMERIC(12,2)  CHECK (quantite_rendue >= 0),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, seance_id, article_id),
    FOREIGN KEY (tenant_id, seance_id)  REFERENCES seance_tp (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, article_id) REFERENCES article_catalogue (tenant_id, id)
);
SELECT activer_isolation('besoin_seance_tp');

-- La panne signalée pendant une séance garde la trace de la séance
ALTER TABLE panne ADD COLUMN seance_tp_id UUID;

CREATE TABLE consigne_securite (
    tenant_id      UUID          NOT NULL,
    id             UUID          NOT NULL,
    atelier_id     UUID          NOT NULL,
    categorie      VARCHAR(14)   NOT NULL CHECK (categorie IN ('ZONE_DANGER', 'EXTINCTEUR', 'ARRET_URGENCE', 'EPI',
                                 'MACHINE', 'HYGIENE', 'AUTRE')),
    equipement_id  UUID,
    texte          VARCHAR(500)  NOT NULL,
    ordre          SMALLINT      NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, atelier_id)    REFERENCES atelier (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, equipement_id) REFERENCES equipement (tenant_id, id) ON DELETE SET NULL (equipement_id)
);
SELECT activer_isolation('consigne_securite');

-- Date de la dernière mise à jour des consignes : une prise de connaissance antérieure est à refaire
ALTER TABLE atelier ADD COLUMN consignes_modifiees_le TIMESTAMPTZ;

CREATE TABLE lecture_consignes (
    tenant_id      UUID         NOT NULL,
    atelier_id     UUID         NOT NULL,
    engagement_id  UUID         NOT NULL,
    lu_le          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, atelier_id, engagement_id),
    FOREIGN KEY (tenant_id, atelier_id)    REFERENCES atelier (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, engagement_id) REFERENCES engagement_enseignant (tenant_id, id)
);
SELECT activer_isolation('lecture_consignes');

CREATE TABLE evenement_hse (
    tenant_id    UUID          NOT NULL,
    id           UUID          NOT NULL,
    atelier_id   UUID          NOT NULL,
    seance_id    UUID,
    survenu_le   TIMESTAMPTZ   NOT NULL,
    gravite      VARCHAR(15)   NOT NULL CHECK (gravite IN ('OBSERVATION', 'DANGER_IMMINENT', 'ACCIDENT')),
    description  VARCHAR(1000) NOT NULL,
    mesures      VARCHAR(1000),
    auteur       UUID,
    cree_le      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, atelier_id) REFERENCES atelier (tenant_id, id),
    FOREIGN KEY (tenant_id, seance_id)  REFERENCES seance_tp (tenant_id, id)
);
CREATE INDEX ix_evenement_hse ON evenement_hse (tenant_id, atelier_id, survenu_le DESC);
SELECT activer_isolation('evenement_hse');
