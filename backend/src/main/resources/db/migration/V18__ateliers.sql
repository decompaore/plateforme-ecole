-- =====================================================================
-- V18 : ateliers, équipements et matière d'œuvre
--
--   parametres_ateliers  : durée du mandat des responsables, fréquence des inventaires
--   article_catalogue    : catalogue des prix (matière d'œuvre et équipements) avec
--                          spécifications et normes fixées par les enseignants spécialistes
--   photo_article        : photo facultative d'un article
--   atelier              : atelier de l'établissement (filières servies dans atelier_filiere)
--   mandat_responsable   : responsable d'atelier (enseignant technique), un seul en cours
--   equipement           : équipement inventorié un par un (numéro d'inventaire, état)
--   panne                : pannes d'un équipement et leur suivi
--   stock_atelier        : quantité de chaque matière d'œuvre dans chaque atelier
--   mouvement_stock      : entrées, sorties et corrections d'inventaire (historique)
--   inventaire           : inventaire périodique d'un atelier, avec ses lignes
-- Montants en francs CFA, sans décimales.
-- =====================================================================

CREATE TABLE parametres_ateliers (
    tenant_id            UUID         PRIMARY KEY REFERENCES tenant (id),
    duree_mandat_mois    SMALLINT     DEFAULT 24 CHECK (duree_mandat_mois BETWEEN 6 AND 120),  -- NULL : sans limite
    frequence_inventaire VARCHAR(12)  NOT NULL DEFAULT 'SEMESTRIELLE'
                         CHECK (frequence_inventaire IN ('SEMESTRIELLE', 'ANNUELLE'))
);

CREATE TABLE article_catalogue (
    tenant_id       UUID          NOT NULL REFERENCES tenant (id),
    id              UUID          NOT NULL,
    code            VARCHAR(30)   NOT NULL,
    designation     VARCHAR(150)  NOT NULL,
    nature          VARCHAR(16)   NOT NULL CHECK (nature IN ('MATIERE_OEUVRE', 'EQUIPEMENT')),
    unite           VARCHAR(20)   NOT NULL,
    filiere_id      UUID,
    specifications  VARCHAR(2000),
    normes          VARCHAR(500),
    prix_reference  BIGINT        CHECK (prix_reference >= 0),
    prix_modifie_le TIMESTAMPTZ,
    actif           BOOLEAN       NOT NULL DEFAULT true,
    photo_type      VARCHAR(20),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, filiere_id) REFERENCES filiere (tenant_id, id)
);

CREATE TABLE photo_article (
    tenant_id  UUID         NOT NULL,
    article_id UUID         NOT NULL,
    type       VARCHAR(20)  NOT NULL CHECK (type IN ('image/jpeg', 'image/png', 'image/webp')),
    contenu    BYTEA        NOT NULL CHECK (octet_length(contenu) <= 2097152),
    PRIMARY KEY (tenant_id, article_id),
    FOREIGN KEY (tenant_id, article_id) REFERENCES article_catalogue (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE atelier (
    tenant_id    UUID          NOT NULL REFERENCES tenant (id),
    id           UUID          NOT NULL,
    code         VARCHAR(20)   NOT NULL,
    nom          VARCHAR(120)  NOT NULL,
    emplacement  VARCHAR(120),
    postes       SMALLINT      CHECK (postes BETWEEN 1 AND 500),
    ouvert       BOOLEAN       NOT NULL DEFAULT true,
    observations VARCHAR(500),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, code)
);

CREATE TABLE atelier_filiere (
    tenant_id  UUID NOT NULL,
    id         UUID NOT NULL,
    atelier_id UUID NOT NULL,
    filiere_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, atelier_id, filiere_id),
    FOREIGN KEY (tenant_id, atelier_id) REFERENCES atelier (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, filiere_id) REFERENCES filiere (tenant_id, id)
);

CREATE TABLE mandat_responsable (
    tenant_id     UUID         NOT NULL,
    id            UUID         NOT NULL,
    atelier_id    UUID         NOT NULL,
    engagement_id UUID         NOT NULL,
    debut         DATE         NOT NULL,
    fin_prevue    DATE,
    fin           DATE,
    motif_fin     VARCHAR(200),
    designe_par   UUID         REFERENCES utilisateur (id),
    designe_le    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CHECK (fin_prevue IS NULL OR fin_prevue > debut),
    CHECK (fin IS NULL OR fin >= debut),
    FOREIGN KEY (tenant_id, atelier_id)    REFERENCES atelier (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, engagement_id) REFERENCES engagement_enseignant (tenant_id, id)
);
CREATE UNIQUE INDEX ux_mandat_en_cours ON mandat_responsable (tenant_id, atelier_id) WHERE fin IS NULL;

CREATE TABLE equipement (
    tenant_id         UUID          NOT NULL,
    id                UUID          NOT NULL,
    atelier_id        UUID          NOT NULL,
    article_id        UUID,
    designation       VARCHAR(150)  NOT NULL,
    numero_inventaire VARCHAR(40)   NOT NULL,
    marque            VARCHAR(60),
    numero_serie      VARCHAR(60),
    date_acquisition  DATE,
    valeur            BIGINT        CHECK (valeur >= 0),
    etat              VARCHAR(10)   NOT NULL DEFAULT 'BON'
                      CHECK (etat IN ('BON', 'EN_PANNE', 'MANQUANT', 'REFORME')),
    observations      VARCHAR(500),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, numero_inventaire),
    FOREIGN KEY (tenant_id, atelier_id) REFERENCES atelier (tenant_id, id),
    FOREIGN KEY (tenant_id, article_id) REFERENCES article_catalogue (tenant_id, id)
);

CREATE TABLE panne (
    tenant_id     UUID          NOT NULL,
    id            UUID          NOT NULL,
    equipement_id UUID          NOT NULL,
    description   VARCHAR(500)  NOT NULL,
    signalee_par  UUID          REFERENCES utilisateur (id),
    signalee_le   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    statut        VARCHAR(12)   NOT NULL DEFAULT 'OUVERTE' CHECK (statut IN ('OUVERTE', 'REPAREE', 'IRREPARABLE')),
    intervention  VARCHAR(500),
    cout          BIGINT        CHECK (cout >= 0),
    cloturee_par  UUID          REFERENCES utilisateur (id),
    cloturee_le   TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    CHECK ((statut = 'OUVERTE') = (cloturee_le IS NULL)),
    FOREIGN KEY (tenant_id, equipement_id) REFERENCES equipement (tenant_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_panne_ouverte ON panne (tenant_id, equipement_id) WHERE statut = 'OUVERTE';

CREATE TABLE stock_atelier (
    tenant_id    UUID           NOT NULL,
    id           UUID           NOT NULL,
    atelier_id   UUID           NOT NULL,
    article_id   UUID           NOT NULL,
    quantite     NUMERIC(12,2)  NOT NULL DEFAULT 0 CHECK (quantite >= 0),
    seuil_alerte NUMERIC(12,2)  CHECK (seuil_alerte >= 0),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, atelier_id, article_id),
    FOREIGN KEY (tenant_id, atelier_id) REFERENCES atelier (tenant_id, id),
    FOREIGN KEY (tenant_id, article_id) REFERENCES article_catalogue (tenant_id, id)
);

CREATE TABLE inventaire (
    tenant_id    UUID          NOT NULL,
    id           UUID          NOT NULL,
    atelier_id   UUID          NOT NULL,
    libelle      VARCHAR(80)   NOT NULL,
    statut       VARCHAR(8)    NOT NULL DEFAULT 'EN_COURS' CHECK (statut IN ('EN_COURS', 'CLOS')),
    ouvert_par   UUID          REFERENCES utilisateur (id),
    ouvert_le    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    clos_par     UUID          REFERENCES utilisateur (id),
    clos_le      TIMESTAMPTZ,
    observations VARCHAR(1000),
    PRIMARY KEY (tenant_id, id),
    CHECK ((statut = 'CLOS') = (clos_le IS NOT NULL)),
    FOREIGN KEY (tenant_id, atelier_id) REFERENCES atelier (tenant_id, id)
);
CREATE UNIQUE INDEX ux_inventaire_en_cours ON inventaire (tenant_id, atelier_id) WHERE statut = 'EN_COURS';

CREATE TABLE ligne_inventaire_matiere (
    tenant_id          UUID           NOT NULL,
    id                 UUID           NOT NULL,
    inventaire_id      UUID           NOT NULL,
    article_id         UUID           NOT NULL,
    quantite_theorique NUMERIC(12,2)  NOT NULL,
    quantite_constatee NUMERIC(12,2)  CHECK (quantite_constatee >= 0),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, inventaire_id, article_id),
    FOREIGN KEY (tenant_id, inventaire_id) REFERENCES inventaire (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, article_id)    REFERENCES article_catalogue (tenant_id, id)
);

CREATE TABLE ligne_inventaire_equipement (
    tenant_id      UUID          NOT NULL,
    id             UUID          NOT NULL,
    inventaire_id  UUID          NOT NULL,
    equipement_id  UUID          NOT NULL,
    etat_theorique VARCHAR(10)   NOT NULL,
    etat_constate  VARCHAR(10)   CHECK (etat_constate IN ('BON', 'EN_PANNE', 'MANQUANT', 'REFORME')),
    observation    VARCHAR(300),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, inventaire_id, equipement_id),
    FOREIGN KEY (tenant_id, inventaire_id) REFERENCES inventaire (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, equipement_id) REFERENCES equipement (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE mouvement_stock (
    tenant_id     UUID           NOT NULL,
    id            UUID           NOT NULL,
    atelier_id    UUID           NOT NULL,
    article_id    UUID           NOT NULL,
    type          VARCHAR(10)    NOT NULL CHECK (type IN ('ENTREE', 'SORTIE', 'INVENTAIRE')),
    quantite      NUMERIC(12,2)  NOT NULL,                 -- variation du stock (négative pour une sortie)
    stock_apres   NUMERIC(12,2)  NOT NULL CHECK (stock_apres >= 0),
    date_mouvement DATE          NOT NULL,
    motif         VARCHAR(200),
    inventaire_id UUID,
    auteur        UUID           REFERENCES utilisateur (id),
    cree_le       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CHECK (quantite <> 0),
    FOREIGN KEY (tenant_id, atelier_id)    REFERENCES atelier (tenant_id, id),
    FOREIGN KEY (tenant_id, article_id)    REFERENCES article_catalogue (tenant_id, id),
    FOREIGN KEY (tenant_id, inventaire_id) REFERENCES inventaire (tenant_id, id)
);
CREATE INDEX ix_mouvement_stock_article ON mouvement_stock (tenant_id, atelier_id, article_id, date_mouvement);

SELECT activer_isolation('parametres_ateliers');
SELECT activer_isolation('article_catalogue');
SELECT activer_isolation('photo_article');
SELECT activer_isolation('atelier');
SELECT activer_isolation('atelier_filiere');
SELECT activer_isolation('mandat_responsable');
SELECT activer_isolation('equipement');
SELECT activer_isolation('panne');
SELECT activer_isolation('stock_atelier');
SELECT activer_isolation('inventaire');
SELECT activer_isolation('ligne_inventaire_matiere');
SELECT activer_isolation('ligne_inventaire_equipement');
SELECT activer_isolation('mouvement_stock');
