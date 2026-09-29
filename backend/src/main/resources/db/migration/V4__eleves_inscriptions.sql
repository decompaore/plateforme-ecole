-- =====================================================================
-- V4 : élèves, responsables (parents, tuteurs) et inscriptions
--   compteur                : numérotation par établissement (matricules)
--   eleve                   : dossier permanent de l'élève (suit toute sa scolarité)
--   responsable             : parent ou tuteur, identifié par son téléphone
--   lien_responsable_eleve  : un responsable peut suivre plusieurs enfants (fratrie)
--   inscription             : présence d'un élève dans une classe pour une année
-- =====================================================================

-- ---------------------------------------------------------------------
-- Compteurs par établissement (matricules, numéros de reçus, ...)
-- ---------------------------------------------------------------------
CREATE TABLE compteur (
    tenant_id UUID        NOT NULL REFERENCES tenant (id),
    nom       VARCHAR(40) NOT NULL,
    valeur    BIGINT      NOT NULL CHECK (valeur > 0),
    PRIMARY KEY (tenant_id, nom)
);

-- ---------------------------------------------------------------------
-- Élève
-- ---------------------------------------------------------------------
CREATE TABLE eleve (
    tenant_id            UUID         NOT NULL REFERENCES tenant (id),
    id                   UUID         NOT NULL,
    matricule            VARCHAR(30)  NOT NULL,
    nom                  VARCHAR(80)  NOT NULL,
    prenoms              VARCHAR(120) NOT NULL,
    sexe                 CHAR(1)      NOT NULL CHECK (sexe IN ('M', 'F')),
    date_naissance       DATE         NOT NULL,
    lieu_naissance       VARCHAR(80),
    telephone            VARCHAR(20),
    identifiant_national VARCHAR(40),              -- numéro d'acte, identifiant ministériel...
    adresse              VARCHAR(200),
    cree_le              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, matricule),
    UNIQUE (tenant_id, identifiant_national),       -- plusieurs NULL autorisés
    CHECK (date_naissance > DATE '1950-01-01')
);
CREATE INDEX ix_eleve_nom ON eleve (tenant_id, lower(nom), lower(prenoms));

-- ---------------------------------------------------------------------
-- Responsable (parent, tuteur) : un seul par numéro de téléphone et par
-- établissement, partagé entre frères et sœurs
-- ---------------------------------------------------------------------
CREATE TABLE responsable (
    tenant_id      UUID         NOT NULL REFERENCES tenant (id),
    id             UUID         NOT NULL,
    nom            VARCHAR(80)  NOT NULL,
    prenoms        VARCHAR(120) NOT NULL,
    telephone      VARCHAR(20)  NOT NULL,           -- format international (+226...)
    profession     VARCHAR(80),
    langue_sms     VARCHAR(10)  NOT NULL DEFAULT 'FR'
                   CHECK (langue_sms IN ('FR', 'MOORE', 'DIOULA', 'FULFULDE')),
    utilisateur_id UUID         REFERENCES utilisateur (id),   -- compte « espace parent »
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, telephone)
);
CREATE INDEX ix_responsable_utilisateur ON responsable (utilisateur_id) WHERE utilisateur_id IS NOT NULL;

CREATE TABLE lien_responsable_eleve (
    tenant_id           UUID        NOT NULL,
    id                  UUID        NOT NULL,
    eleve_id            UUID        NOT NULL,
    responsable_id      UUID        NOT NULL,
    lien                VARCHAR(10) NOT NULL CHECK (lien IN ('PERE', 'MERE', 'TUTEUR', 'AUTRE')),
    responsable_legal   BOOLEAN     NOT NULL DEFAULT true,
    contact_prioritaire BOOLEAN     NOT NULL DEFAULT false,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, eleve_id, responsable_id),
    FOREIGN KEY (tenant_id, eleve_id)       REFERENCES eleve (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, responsable_id) REFERENCES responsable (tenant_id, id)
);
-- Un seul contact prioritaire (destinataire des SMS) par élève
CREATE UNIQUE INDEX ux_contact_prioritaire ON lien_responsable_eleve (tenant_id, eleve_id)
    WHERE contact_prioritaire;
CREATE INDEX ix_lien_responsable ON lien_responsable_eleve (tenant_id, responsable_id);

-- ---------------------------------------------------------------------
-- Inscription : un élève est dans une seule classe par année
-- ---------------------------------------------------------------------
-- Permet de garantir que la classe appartient bien à l'année de l'inscription
ALTER TABLE classe ADD CONSTRAINT classe_tenant_id_annee_key UNIQUE (tenant_id, id, annee_id);

CREATE TABLE inscription (
    tenant_id                UUID         NOT NULL,
    id                       UUID         NOT NULL,
    eleve_id                 UUID         NOT NULL,
    annee_id                 UUID         NOT NULL,
    classe_id                UUID         NOT NULL,
    statut                   VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE'
                             CHECK (statut IN ('ACTIVE', 'TRANSFEREE', 'ABANDON')),
    redoublant               BOOLEAN      NOT NULL DEFAULT false,
    statut_bourse            VARCHAR(14)  NOT NULL DEFAULT 'NON_BOURSIER'
                             CHECK (statut_bourse IN ('BOURSIER', 'SEMI_BOURSIER', 'NON_BOURSIER')),
    inscrit_le               DATE         NOT NULL DEFAULT current_date,
    date_sortie              DATE,
    motif_sortie             VARCHAR(200),
    inscription_precedente_id UUID,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, eleve_id, annee_id),
    FOREIGN KEY (tenant_id, eleve_id)  REFERENCES eleve (tenant_id, id),
    FOREIGN KEY (tenant_id, annee_id)  REFERENCES annee_scolaire (tenant_id, id),
    FOREIGN KEY (tenant_id, classe_id, annee_id) REFERENCES classe (tenant_id, id, annee_id),
    FOREIGN KEY (tenant_id, inscription_precedente_id) REFERENCES inscription (tenant_id, id),
    -- Une inscription active n'a pas de date de sortie ; une sortie en a toujours une
    CHECK ((statut = 'ACTIVE') = (date_sortie IS NULL)),
    CHECK (date_sortie IS NULL OR date_sortie >= inscrit_le)
);
CREATE INDEX ix_inscription_classe ON inscription (tenant_id, classe_id, statut);
CREATE INDEX ix_inscription_eleve  ON inscription (tenant_id, eleve_id);

-- ---------------------------------------------------------------------
-- Cloisonnement par établissement
-- ---------------------------------------------------------------------
SELECT activer_isolation('compteur');
SELECT activer_isolation('eleve');
SELECT activer_isolation('responsable');
SELECT activer_isolation('lien_responsable_eleve');
SELECT activer_isolation('inscription');
