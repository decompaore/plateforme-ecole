-- =====================================================================
-- V7 : évaluations, notes et compétences
--   evaluation          : devoir, interrogation, composition, TP… d'une matière,
--                         dans une classe et une période
--   note                : note d'un élève à une évaluation (ou absence à l'évaluation)
--   competence          : référentiel d'un module de compétences
--   resultat_competence : niveau de maîtrise d'un apprenant, par période
-- Les moyennes et les rangs sont calculés à la demande (stratégie du profil
-- pédagogique) ; les bulletins en figeront une copie (version suivante).
-- =====================================================================

CREATE TABLE evaluation (
    tenant_id  UUID         NOT NULL REFERENCES tenant (id),
    id         UUID         NOT NULL,
    classe_id  UUID         NOT NULL,
    matiere_id UUID         NOT NULL,
    periode_id UUID         NOT NULL,
    libelle    VARCHAR(80)  NOT NULL,
    type       VARCHAR(14)  NOT NULL
               CHECK (type IN ('DEVOIR', 'INTERROGATION', 'COMPOSITION', 'TP', 'ATELIER', 'AUTRE')),
    date_evaluation DATE    NOT NULL,
    bareme     NUMERIC(5,2) NOT NULL DEFAULT 20 CHECK (bareme > 0),
    poids      NUMERIC(4,2) NOT NULL DEFAULT 1  CHECK (poids > 0),
    cree_par   UUID         REFERENCES utilisateur (id),
    cree_le    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    -- La matière doit être enseignée dans la classe
    FOREIGN KEY (tenant_id, classe_id, matiere_id) REFERENCES classe_matiere (tenant_id, classe_id, matiere_id),
    FOREIGN KEY (tenant_id, periode_id) REFERENCES periode (tenant_id, id)
);
CREATE INDEX ix_evaluation_classe_periode ON evaluation (tenant_id, classe_id, periode_id);

CREATE TABLE note (
    tenant_id      UUID         NOT NULL,
    id             UUID         NOT NULL,
    evaluation_id  UUID         NOT NULL,
    inscription_id UUID         NOT NULL,
    valeur         NUMERIC(5,2) CHECK (valeur >= 0),
    absent         BOOLEAN      NOT NULL DEFAULT false,
    saisi_par      UUID         REFERENCES utilisateur (id),
    saisi_le       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, evaluation_id, inscription_id),
    CHECK (absent = (valeur IS NULL)),                  -- une note, ou « absent »
    FOREIGN KEY (tenant_id, evaluation_id)  REFERENCES evaluation (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id)
);
CREATE INDEX ix_note_inscription ON note (tenant_id, inscription_id);

CREATE TABLE competence (
    tenant_id  UUID         NOT NULL REFERENCES tenant (id),
    id         UUID         NOT NULL,
    matiere_id UUID         NOT NULL,                  -- module de compétences
    code       VARCHAR(20)  NOT NULL,
    libelle    VARCHAR(200) NOT NULL,
    ordre      SMALLINT     NOT NULL DEFAULT 1,
    actif      BOOLEAN      NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, matiere_id, code),
    FOREIGN KEY (tenant_id, matiere_id) REFERENCES matiere (tenant_id, id)
);

CREATE TABLE resultat_competence (
    tenant_id      UUID        NOT NULL,
    id             UUID        NOT NULL,
    competence_id  UUID        NOT NULL,
    inscription_id UUID        NOT NULL,
    periode_id     UUID        NOT NULL,
    niveau         VARCHAR(10) NOT NULL CHECK (niveau IN ('ACQUIS', 'EN_COURS', 'NON_ACQUIS')),
    evalue_par     UUID        REFERENCES utilisateur (id),
    evalue_le      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, competence_id, inscription_id, periode_id),
    FOREIGN KEY (tenant_id, competence_id)  REFERENCES competence (tenant_id, id),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id),
    FOREIGN KEY (tenant_id, periode_id)     REFERENCES periode (tenant_id, id)
);
CREATE INDEX ix_resultat_competence_periode ON resultat_competence (tenant_id, periode_id);

SELECT activer_isolation('evaluation');
SELECT activer_isolation('note');
SELECT activer_isolation('competence');
SELECT activer_isolation('resultat_competence');
