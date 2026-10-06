-- =====================================================================
-- V19 : circuit des besoins en matière d'œuvre et en équipements
--
--   campagne_besoins   : deux par an (année scolaire en cours, examens de fin d'études)
--   besoin_atelier     : expression des besoins d'un atelier dans une campagne
--   ligne_besoin       : article demandé (quantité, justification), quantité retenue par la
--                        direction (chef des travaux avec le proviseur et l'intendant), prix figé
--   commande           : passée par la direction régionale ou par l'établissement
--   ligne_commande     : article, quantité, prix unitaire
--   livraison          : réception d'une commande (bon de livraison)
--   ligne_livraison    : quantité reçue et quantité conforme aux spécifications et normes
--   ligne_repartition  : répartition de la quantité conforme entre les ateliers
-- =====================================================================

CREATE TABLE campagne_besoins (
    tenant_id    UUID          NOT NULL REFERENCES tenant (id),
    id           UUID          NOT NULL,
    annee_id     UUID          NOT NULL,
    type         VARCHAR(16)   NOT NULL CHECK (type IN ('ANNEE_EN_COURS', 'EXAMENS')),
    libelle      VARCHAR(120)  NOT NULL,
    date_limite  DATE,
    statut       VARCHAR(10)   NOT NULL DEFAULT 'OUVERTE' CHECK (statut IN ('OUVERTE', 'TRANSMISE', 'CLOSE')),
    ouverte_par  UUID          REFERENCES utilisateur (id),
    ouverte_le   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    transmise_le TIMESTAMPTZ,
    close_le     TIMESTAMPTZ,
    observations VARCHAR(1000),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, annee_id, type),
    CHECK ((statut = 'OUVERTE') = (transmise_le IS NULL)),
    FOREIGN KEY (tenant_id, annee_id) REFERENCES annee_scolaire (tenant_id, id)
);

CREATE TABLE besoin_atelier (
    tenant_id    UUID          NOT NULL,
    id           UUID          NOT NULL,
    campagne_id  UUID          NOT NULL,
    atelier_id   UUID          NOT NULL,
    statut       VARCHAR(10)   NOT NULL DEFAULT 'BROUILLON' CHECK (statut IN ('BROUILLON', 'TRANSMIS', 'VALIDE')),
    transmis_par UUID          REFERENCES utilisateur (id),
    transmis_le  TIMESTAMPTZ,
    valide_par   UUID          REFERENCES utilisateur (id),
    valide_le    TIMESTAMPTZ,
    commentaire  VARCHAR(500),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, campagne_id, atelier_id),
    CHECK ((statut = 'VALIDE') = (valide_le IS NOT NULL)),
    FOREIGN KEY (tenant_id, campagne_id) REFERENCES campagne_besoins (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, atelier_id)  REFERENCES atelier (tenant_id, id)
);

CREATE TABLE ligne_besoin (
    tenant_id          UUID           NOT NULL,
    id                 UUID           NOT NULL,
    besoin_id          UUID           NOT NULL,
    article_id         UUID           NOT NULL,
    quantite_demandee  NUMERIC(12,2)  NOT NULL CHECK (quantite_demandee > 0),
    justification      VARCHAR(300),
    propose_par        UUID           REFERENCES utilisateur (id),
    modifiee_le        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    quantite_retenue   NUMERIC(12,2)  CHECK (quantite_retenue >= 0),
    prix_unitaire      BIGINT         CHECK (prix_unitaire >= 0),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, besoin_id, article_id),
    FOREIGN KEY (tenant_id, besoin_id)  REFERENCES besoin_atelier (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, article_id) REFERENCES article_catalogue (tenant_id, id)
);

CREATE TABLE commande (
    tenant_id     UUID          NOT NULL,
    id            UUID          NOT NULL,
    campagne_id   UUID          NOT NULL,
    reference     VARCHAR(60)   NOT NULL,
    fournisseur   VARCHAR(150)  NOT NULL,
    passee_par    VARCHAR(20)   NOT NULL CHECK (passee_par IN ('DIRECTION_REGIONALE', 'ETABLISSEMENT')),
    date_commande DATE          NOT NULL,
    statut        VARCHAR(22)   NOT NULL DEFAULT 'EN_COURS'
                  CHECK (statut IN ('EN_COURS', 'LIVREE_PARTIELLEMENT', 'LIVREE', 'ANNULEE')),
    observations  VARCHAR(500),
    motif_annulation VARCHAR(200),
    cree_par      UUID          REFERENCES utilisateur (id),
    cree_le       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, reference),
    FOREIGN KEY (tenant_id, campagne_id) REFERENCES campagne_besoins (tenant_id, id)
);

CREATE TABLE ligne_commande (
    tenant_id     UUID           NOT NULL,
    id            UUID           NOT NULL,
    commande_id   UUID           NOT NULL,
    article_id    UUID           NOT NULL,
    quantite      NUMERIC(12,2)  NOT NULL CHECK (quantite > 0),
    prix_unitaire BIGINT         NOT NULL CHECK (prix_unitaire >= 0),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, commande_id, article_id),
    FOREIGN KEY (tenant_id, commande_id) REFERENCES commande (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, article_id)  REFERENCES article_catalogue (tenant_id, id)
);

CREATE TABLE livraison (
    tenant_id      UUID          NOT NULL,
    id             UUID          NOT NULL,
    commande_id    UUID          NOT NULL,
    date_reception DATE          NOT NULL,
    bon_livraison  VARCHAR(60),
    observations   VARCHAR(500),
    statut         VARCHAR(10)   NOT NULL DEFAULT 'A_REPARTIR' CHECK (statut IN ('A_REPARTIR', 'REPARTIE')),
    recue_par      UUID          REFERENCES utilisateur (id),
    recue_le       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    repartie_par   UUID          REFERENCES utilisateur (id),
    repartie_le    TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    CHECK ((statut = 'REPARTIE') = (repartie_le IS NOT NULL)),
    FOREIGN KEY (tenant_id, commande_id) REFERENCES commande (tenant_id, id)
);

CREATE TABLE ligne_livraison (
    tenant_id            UUID           NOT NULL,
    id                   UUID           NOT NULL,
    livraison_id         UUID           NOT NULL,
    article_id           UUID           NOT NULL,
    quantite_recue       NUMERIC(12,2)  NOT NULL CHECK (quantite_recue >= 0),
    quantite_conforme    NUMERIC(12,2)  NOT NULL CHECK (quantite_conforme >= 0),
    motif_non_conformite VARCHAR(300),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, livraison_id, article_id),
    CHECK (quantite_conforme <= quantite_recue),
    CHECK (quantite_conforme = quantite_recue OR motif_non_conformite IS NOT NULL),
    FOREIGN KEY (tenant_id, livraison_id) REFERENCES livraison (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, article_id)   REFERENCES article_catalogue (tenant_id, id)
);

CREATE TABLE ligne_repartition (
    tenant_id    UUID           NOT NULL,
    id           UUID           NOT NULL,
    livraison_id UUID           NOT NULL,
    article_id   UUID           NOT NULL,
    atelier_id   UUID           NOT NULL,
    quantite     NUMERIC(12,2)  NOT NULL CHECK (quantite > 0),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, livraison_id, article_id, atelier_id),
    FOREIGN KEY (tenant_id, livraison_id) REFERENCES livraison (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, article_id)   REFERENCES article_catalogue (tenant_id, id),
    FOREIGN KEY (tenant_id, atelier_id)   REFERENCES atelier (tenant_id, id)
);

SELECT activer_isolation('campagne_besoins');
SELECT activer_isolation('besoin_atelier');
SELECT activer_isolation('ligne_besoin');
SELECT activer_isolation('commande');
SELECT activer_isolation('ligne_commande');
SELECT activer_isolation('livraison');
SELECT activer_isolation('ligne_livraison');
SELECT activer_isolation('ligne_repartition');
