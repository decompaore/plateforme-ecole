-- =====================================================================
-- V37 : état annuel des besoins de l'établissement, remontée vers les
-- directions et dotations descendantes
--
-- Dans l'établissement (isolé par tenant) :
--   etat_besoins          : un état par année scolaire
--   ligne_etat_besoins    : besoin exprimé (infrastructures, personnel, équipements
--                           et outils, matières), domaine général (censeur) ou
--                           technique (chef des travaux)
--
-- Au niveau territorial (sans tenant ; la portée est contrôlée par l'application) :
--   remontee_besoins      : copie de l'état transmis par le proviseur à sa direction
--   ligne_remontee        : lignes copiées à la transmission
--   arbitrage_besoin      : quantité retenue par chaque niveau (direction, pays)
--   transmission_besoins  : transmission d'une direction à la direction supérieure
--                           (ou au niveau central pour la direction de premier rang)
--   dotation              : du niveau central à une direction, d'une direction à une
--                           direction inférieure ou à un établissement ; une dotation
--                           peut être la répartition d'une dotation reçue
--   ligne_dotation        : article doté ; quantités reçue et conforme saisies à
--                           l'accusé de réception (aucune entrée en stock automatique)
-- =====================================================================

CREATE TABLE etat_besoins (
    tenant_id    UUID          NOT NULL REFERENCES tenant (id),
    id           UUID          NOT NULL,
    annee_id     UUID          NOT NULL,
    observations VARCHAR(1000),
    cree_le      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, annee_id),
    FOREIGN KEY (tenant_id, annee_id) REFERENCES annee_scolaire (tenant_id, id)
);
SELECT activer_isolation('etat_besoins');

CREATE TABLE ligne_etat_besoins (
    tenant_id     UUID          NOT NULL,
    id            UUID          NOT NULL,
    etat_id       UUID          NOT NULL,
    categorie     VARCHAR(20)   NOT NULL
                  CHECK (categorie IN ('INFRASTRUCTURE', 'PERSONNEL', 'EQUIPEMENT_OUTILS', 'MATIERE')),
    domaine       VARCHAR(10)   NOT NULL CHECK (domaine IN ('GENERAL', 'TECHNIQUE')),
    designation   VARCHAR(200)  NOT NULL,
    unite         VARCHAR(30)   NOT NULL,
    quantite      NUMERIC(12,2) NOT NULL CHECK (quantite > 0),
    priorite      SMALLINT      NOT NULL DEFAULT 2 CHECK (priorite BETWEEN 1 AND 3),
    justification VARCHAR(500),
    propose_par   UUID          REFERENCES utilisateur (id),
    modifiee_le   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, etat_id) REFERENCES etat_besoins (tenant_id, id) ON DELETE CASCADE
);
SELECT activer_isolation('ligne_etat_besoins');
CREATE INDEX ligne_etat_besoins_etat ON ligne_etat_besoins (tenant_id, etat_id);

-- ---------------------------------------------------------------------
-- Remontée (niveau territorial)
-- ---------------------------------------------------------------------
CREATE TABLE remontee_besoins (
    id             UUID          PRIMARY KEY,
    tenant_id      UUID          NOT NULL REFERENCES tenant (id),
    etat_id        UUID          NOT NULL,
    annee          VARCHAR(20)   NOT NULL,
    direction_id   UUID          NOT NULL REFERENCES direction (id),
    statut         VARCHAR(10)   NOT NULL DEFAULT 'RECUE' CHECK (statut IN ('RECUE', 'RENVOYEE')),
    observations   VARCHAR(1000),
    transmise_le   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    transmise_par  UUID          REFERENCES utilisateur (id),
    renvoyee_le    TIMESTAMPTZ,
    renvoyee_par   UUID          REFERENCES utilisateur (id),
    motif_renvoi   VARCHAR(500),
    UNIQUE (tenant_id, annee),
    CHECK ((statut = 'RENVOYEE') = (renvoyee_le IS NOT NULL))
);
CREATE INDEX remontee_besoins_direction ON remontee_besoins (direction_id, annee);

CREATE TABLE ligne_remontee (
    id               UUID          PRIMARY KEY,
    remontee_id      UUID          NOT NULL REFERENCES remontee_besoins (id) ON DELETE CASCADE,
    ligne_origine_id UUID          NOT NULL,
    ordre            INT           NOT NULL,
    categorie        VARCHAR(20)   NOT NULL
                     CHECK (categorie IN ('INFRASTRUCTURE', 'PERSONNEL', 'EQUIPEMENT_OUTILS', 'MATIERE')),
    domaine          VARCHAR(10)   NOT NULL CHECK (domaine IN ('GENERAL', 'TECHNIQUE')),
    designation      VARCHAR(200)  NOT NULL,
    unite            VARCHAR(30)   NOT NULL,
    quantite         NUMERIC(12,2) NOT NULL CHECK (quantite > 0),
    priorite         SMALLINT      NOT NULL CHECK (priorite BETWEEN 1 AND 3),
    justification    VARCHAR(500)
);
CREATE INDEX ligne_remontee_remontee ON ligne_remontee (remontee_id);

CREATE TABLE arbitrage_besoin (
    ligne_id         UUID          NOT NULL REFERENCES ligne_remontee (id) ON DELETE CASCADE,
    niveau           VARCHAR(10)   NOT NULL CHECK (niveau IN ('DIRECTION', 'PAYS')),
    instance_id      UUID          NOT NULL,
    quantite_retenue NUMERIC(12,2) NOT NULL CHECK (quantite_retenue >= 0),
    observation      VARCHAR(300),
    arbitre_par      UUID          REFERENCES utilisateur (id),
    arbitre_le       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (ligne_id, instance_id)
);

CREATE TABLE transmission_besoins (
    id            UUID          PRIMARY KEY,
    direction_id  UUID          NOT NULL REFERENCES direction (id),
    annee         VARCHAR(20)   NOT NULL,
    statut        VARCHAR(10)   NOT NULL DEFAULT 'TRANSMISE' CHECK (statut IN ('TRANSMISE', 'RENVOYEE')),
    observations  VARCHAR(1000),
    transmise_le  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    transmise_par UUID          REFERENCES utilisateur (id),
    renvoyee_le   TIMESTAMPTZ,
    renvoyee_par  UUID          REFERENCES utilisateur (id),
    motif_renvoi  VARCHAR(500),
    UNIQUE (direction_id, annee),
    CHECK ((statut = 'RENVOYEE') = (renvoyee_le IS NOT NULL))
);

-- ---------------------------------------------------------------------
-- Dotations (niveau territorial)
-- ---------------------------------------------------------------------
CREATE SEQUENCE dotation_numero;

CREATE TABLE dotation (
    id                     UUID          PRIMARY KEY,
    reference              VARCHAR(30)   NOT NULL UNIQUE,
    annee                  VARCHAR(20)   NOT NULL,
    emettrice_type         VARCHAR(10)   NOT NULL CHECK (emettrice_type IN ('PAYS', 'DIRECTION')),
    emettrice_id           UUID          NOT NULL,
    destinataire_type      VARCHAR(13)   NOT NULL CHECK (destinataire_type IN ('DIRECTION', 'ETABLISSEMENT')),
    destinataire_id        UUID          NOT NULL,
    origine_id             UUID          REFERENCES dotation (id),
    objet                  VARCHAR(200)  NOT NULL,
    statut                 VARCHAR(10)   NOT NULL DEFAULT 'BROUILLON'
                           CHECK (statut IN ('BROUILLON', 'ENVOYEE', 'RECUE')),
    creee_par              UUID          REFERENCES utilisateur (id),
    creee_le               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    envoyee_par            UUID          REFERENCES utilisateur (id),
    envoyee_le             TIMESTAMPTZ,
    accusee_par            UUID          REFERENCES utilisateur (id),
    recue_le               TIMESTAMPTZ,
    observations_reception VARCHAR(1000),
    CHECK ((statut = 'BROUILLON') = (envoyee_le IS NULL)),
    CHECK ((statut = 'RECUE') = (recue_le IS NOT NULL))
);
CREATE INDEX dotation_emettrice ON dotation (emettrice_id, annee);
CREATE INDEX dotation_destinataire ON dotation (destinataire_id, annee);
CREATE INDEX dotation_origine ON dotation (origine_id);

CREATE TABLE ligne_dotation (
    id                UUID          PRIMARY KEY,
    dotation_id       UUID          NOT NULL REFERENCES dotation (id) ON DELETE CASCADE,
    ligne_origine_id  UUID          REFERENCES ligne_dotation (id),
    ordre             INT           NOT NULL,
    categorie         VARCHAR(20)   NOT NULL
                      CHECK (categorie IN ('INFRASTRUCTURE', 'PERSONNEL', 'EQUIPEMENT_OUTILS', 'MATIERE')),
    designation       VARCHAR(200)  NOT NULL,
    unite             VARCHAR(30)   NOT NULL,
    quantite          NUMERIC(12,2) NOT NULL CHECK (quantite > 0),
    valeur_unitaire   BIGINT        CHECK (valeur_unitaire >= 0),
    quantite_recue    NUMERIC(12,2) CHECK (quantite_recue >= 0),
    quantite_conforme NUMERIC(12,2) CHECK (quantite_conforme >= 0),
    observation       VARCHAR(300),
    CHECK (quantite_conforme IS NULL OR quantite_recue IS NULL OR quantite_conforme <= quantite_recue)
);
CREATE INDEX ligne_dotation_dotation ON ligne_dotation (dotation_id);
CREATE INDEX ligne_dotation_origine ON ligne_dotation (ligne_origine_id);
