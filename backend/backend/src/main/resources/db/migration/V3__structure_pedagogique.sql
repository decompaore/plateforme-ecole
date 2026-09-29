-- =====================================================================
-- V3 : structure pédagogique de l'établissement
--   profils pédagogiques, filières, années scolaires, périodes,
--   classes, matières, matières d'une classe (coefficients)
-- Toutes les tables sont cloisonnées par établissement (tenant_id + RLS)
-- et reliées par des clés étrangères composites (tenant_id, id).
-- =====================================================================

-- Outil réutilisable par les migrations suivantes : active la RLS sur une table
CREATE FUNCTION activer_isolation(p_table regclass) RETURNS void
    LANGUAGE plpgsql
AS $$
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', p_table);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', p_table);
    EXECUTE format('CREATE POLICY isolation_tenant ON %s USING (tenant_id = tenant_courant()) '
                   'WITH CHECK (tenant_id = tenant_courant())', p_table);
END
$$;
REVOKE ALL ON FUNCTION activer_isolation(regclass) FROM PUBLIC;

-- ---------------------------------------------------------------------
-- Profil pédagogique : règles propres à un ordre d'enseignement
-- ---------------------------------------------------------------------
CREATE TABLE profil_pedagogique (
    tenant_id          UUID         NOT NULL REFERENCES tenant (id),
    id                 UUID         NOT NULL,
    code               VARCHAR(30)  NOT NULL,
    libelle            VARCHAR(120) NOT NULL,
    ordre_enseignement VARCHAR(15)  NOT NULL
                       CHECK (ordre_enseignement IN ('GENERAL', 'TECHNIQUE', 'PROFESSIONNEL')),
    modele_evaluation  VARCHAR(25)  NOT NULL
                       CHECK (modele_evaluation IN ('NOTES_COEFFICIENTS', 'NOTES_PAR_GROUPES',
                                                    'COMPETENCES', 'MIXTE')),
    decoupage          VARCHAR(10)  NOT NULL
                       CHECK (decoupage IN ('TRIMESTRE', 'SEMESTRE', 'MODULE')),
    gabarit_document   VARCHAR(40)  NOT NULL,
    -- Règles de calcul (paramétrables sans développement)
    seuil_admission    NUMERIC(4,2) NOT NULL DEFAULT 10 CHECK (seuil_admission BETWEEN 0 AND 20),
    decimales_moyenne  SMALLINT     NOT NULL DEFAULT 2  CHECK (decimales_moyenne BETWEEN 0 AND 3),
    note_eliminatoire  NUMERIC(4,2)          CHECK (note_eliminatoire BETWEEN 0 AND 20),
    seuil_maitrise     NUMERIC(5,2)          CHECK (seuil_maitrise BETWEEN 0 AND 100),
    -- Vocabulaire affiché dans l'interface et les documents
    libelle_apprenant  VARCHAR(30)  NOT NULL DEFAULT 'Élève',
    libelle_groupe     VARCHAR(30)  NOT NULL DEFAULT 'Classe',
    libelle_matiere    VARCHAR(30)  NOT NULL DEFAULT 'Matière',
    libelle_enseignant VARCHAR(30)  NOT NULL DEFAULT 'Professeur',
    actif              BOOLEAN      NOT NULL DEFAULT true,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);

-- ---------------------------------------------------------------------
-- Filière (série, spécialité, métier) : porte le profil pédagogique
-- ---------------------------------------------------------------------
CREATE TABLE filiere (
    tenant_id    UUID         NOT NULL,
    id           UUID         NOT NULL,
    code         VARCHAR(20)  NOT NULL,
    libelle      VARCHAR(120) NOT NULL,
    cycle        VARCHAR(30)  NOT NULL,
    diplome_vise VARCHAR(40),
    profil_id    UUID         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code),
    FOREIGN KEY (tenant_id, profil_id) REFERENCES profil_pedagogique (tenant_id, id)
);

-- ---------------------------------------------------------------------
-- Année scolaire et son cycle de vie
-- ---------------------------------------------------------------------
CREATE TABLE annee_scolaire (
    tenant_id UUID        NOT NULL REFERENCES tenant (id),
    id        UUID        NOT NULL,
    libelle   VARCHAR(20) NOT NULL,
    debut     DATE        NOT NULL,
    fin       DATE        NOT NULL,
    etat      VARCHAR(12) NOT NULL DEFAULT 'PREPARATION'
              CHECK (etat IN ('PREPARATION', 'ACTIVE', 'CLOTUREE', 'ARCHIVEE')),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, libelle),
    CHECK (fin > debut),
    CHECK (fin - debut <= 400)
);
-- Une seule année ACTIVE par établissement
CREATE UNIQUE INDEX ux_annee_active ON annee_scolaire (tenant_id) WHERE etat = 'ACTIVE';

-- ---------------------------------------------------------------------
-- Période (trimestre, semestre ou module), propre à un profil
-- ---------------------------------------------------------------------
CREATE TABLE periode (
    tenant_id   UUID        NOT NULL,
    id          UUID        NOT NULL,
    annee_id    UUID        NOT NULL,
    profil_id   UUID        NOT NULL,
    libelle     VARCHAR(40) NOT NULL,
    ordre       SMALLINT    NOT NULL CHECK (ordre > 0),
    debut       DATE        NOT NULL,
    fin         DATE        NOT NULL,
    verrouillee BOOLEAN     NOT NULL DEFAULT false,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, annee_id, profil_id, ordre),
    CHECK (fin >= debut),
    FOREIGN KEY (tenant_id, annee_id)  REFERENCES annee_scolaire (tenant_id, id),
    FOREIGN KEY (tenant_id, profil_id) REFERENCES profil_pedagogique (tenant_id, id),
    -- Deux périodes d'un même profil ne se chevauchent jamais dans une année
    CONSTRAINT periodes_sans_chevauchement EXCLUDE USING gist (
        tenant_id WITH =, annee_id WITH =, profil_id WITH =,
        daterange(debut, fin, '[]') WITH &&)
);

-- ---------------------------------------------------------------------
-- Classe (ou groupe en formation professionnelle)
-- ---------------------------------------------------------------------
CREATE TABLE classe (
    tenant_id    UUID        NOT NULL,
    id           UUID        NOT NULL,
    annee_id     UUID        NOT NULL,
    filiere_id   UUID        NOT NULL,
    code         VARCHAR(30) NOT NULL,
    niveau       VARCHAR(30) NOT NULL,
    effectif_max SMALLINT    CHECK (effectif_max > 0),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, annee_id, code),
    FOREIGN KEY (tenant_id, annee_id)   REFERENCES annee_scolaire (tenant_id, id),
    FOREIGN KEY (tenant_id, filiere_id) REFERENCES filiere (tenant_id, id)
);

-- ---------------------------------------------------------------------
-- Matière (discipline) ou module de compétences
-- ---------------------------------------------------------------------
CREATE TABLE matiere (
    tenant_id UUID         NOT NULL,
    id        UUID         NOT NULL,
    code      VARCHAR(20)  NOT NULL,
    libelle   VARCHAR(120) NOT NULL,
    type      VARCHAR(20)  NOT NULL DEFAULT 'GENERALE'
              CHECK (type IN ('GENERALE', 'TECHNIQUE', 'PRATIQUE', 'MODULE_COMPETENCES')),
    actif     BOOLEAN      NOT NULL DEFAULT true,
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, code)
);

-- ---------------------------------------------------------------------
-- Matière enseignée dans une classe : coefficient, groupe, volume horaire
-- (l'enseignant affecté sera ajouté avec le module Enseignants)
-- ---------------------------------------------------------------------
CREATE TABLE classe_matiere (
    tenant_id    UUID         NOT NULL,
    id           UUID         NOT NULL,
    classe_id    UUID         NOT NULL,
    matiere_id   UUID         NOT NULL,
    coefficient  NUMERIC(4,1) NOT NULL CHECK (coefficient > 0),
    groupe       VARCHAR(40),                     -- groupe de matières (technique)
    volume_hebdo NUMERIC(4,1) CHECK (volume_hebdo > 0),
    volume_total NUMERIC(6,1) CHECK (volume_total > 0),   -- durée d'un module (heures)
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, classe_id, matiere_id),
    FOREIGN KEY (tenant_id, classe_id)  REFERENCES classe (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, matiere_id) REFERENCES matiere (tenant_id, id)
);

CREATE INDEX ix_classe_annee        ON classe (tenant_id, annee_id);
CREATE INDEX ix_periode_annee       ON periode (tenant_id, annee_id, profil_id);
CREATE INDEX ix_classe_matiere_cls  ON classe_matiere (tenant_id, classe_id);

-- ---------------------------------------------------------------------
-- Cloisonnement par établissement
-- ---------------------------------------------------------------------
SELECT activer_isolation('profil_pedagogique');
SELECT activer_isolation('filiere');
SELECT activer_isolation('annee_scolaire');
SELECT activer_isolation('periode');
SELECT activer_isolation('classe');
SELECT activer_isolation('matiere');
SELECT activer_isolation('classe_matiere');
