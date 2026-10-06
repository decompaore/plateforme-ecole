-- =====================================================================
-- V11 : passage à l'année suivante
--   parametres_passage : seuil d'exclusion, nombre maximal de redoublements
--   poids_periode      : poids de chaque période dans la moyenne annuelle (par profil)
--   classe_examen      : classes qui préparent un examen national (BEPC, BAC, CAP, CQP…)
--   decision_fin_annee : moyenne annuelle, proposition du moteur, décision du conseil
-- =====================================================================

CREATE TABLE parametres_passage (
    tenant_id          UUID         PRIMARY KEY REFERENCES tenant (id),
    seuil_exclusion    NUMERIC(4,2) DEFAULT 8.5 CHECK (seuil_exclusion >= 0 AND seuil_exclusion <= 20),
    redoublements_max  SMALLINT     DEFAULT 2   CHECK (redoublements_max BETWEEN 1 AND 5)
);

CREATE TABLE poids_periode (
    tenant_id UUID         NOT NULL,
    profil_id UUID         NOT NULL,
    ordre     SMALLINT     NOT NULL CHECK (ordre > 0),
    poids     NUMERIC(4,2) NOT NULL CHECK (poids > 0 AND poids <= 10),
    PRIMARY KEY (tenant_id, profil_id, ordre),
    FOREIGN KEY (tenant_id, profil_id) REFERENCES profil_pedagogique (tenant_id, id)
);

CREATE TABLE classe_examen (
    tenant_id UUID        NOT NULL,
    classe_id UUID        NOT NULL,
    examen    VARCHAR(40) NOT NULL,
    PRIMARY KEY (tenant_id, classe_id),
    FOREIGN KEY (tenant_id, classe_id) REFERENCES classe (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE decision_fin_annee (
    tenant_id        UUID         NOT NULL,
    id               UUID         NOT NULL,
    inscription_id   UUID         NOT NULL,
    classe_id        UUID         NOT NULL,
    moyenne_annuelle NUMERIC(6,3),              -- jusqu'à 3 décimales selon le profil
    rang             INTEGER,
    taux_maitrise    NUMERIC(5,2),
    redoublements    SMALLINT     NOT NULL DEFAULT 0,
    resultat_examen  VARCHAR(8)   CHECK (resultat_examen IN ('ADMIS', 'AJOURNE')),
    proposition      VARCHAR(18)  NOT NULL,
    decision         VARCHAR(18)  NOT NULL,
    motif            VARCHAR(300),
    calcule_le       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_par      UUID         REFERENCES utilisateur (id),
    valide_par       UUID         REFERENCES utilisateur (id),
    valide_le        TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, inscription_id),
    CHECK (proposition IN ('ADMIS', 'REDOUBLE', 'EXCLU', 'CERTIFIE', 'NON_CERTIFIE', 'EN_ATTENTE_EXAMEN')),
    CHECK (decision IN ('ADMIS', 'REDOUBLE', 'EXCLU', 'ORIENTE', 'CERTIFIE', 'NON_CERTIFIE', 'EN_ATTENTE_EXAMEN')),
    -- Le conseil peut modifier la proposition, mais toujours avec un motif
    CHECK (decision = proposition OR motif IS NOT NULL),
    -- Une décision validée est définitive (plus d'attente d'examen)
    CHECK (valide_le IS NULL OR decision <> 'EN_ATTENTE_EXAMEN'),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id),
    FOREIGN KEY (tenant_id, classe_id)      REFERENCES classe (tenant_id, id)
);
CREATE INDEX ix_decision_classe ON decision_fin_annee (tenant_id, classe_id);

SELECT activer_isolation('parametres_passage');
SELECT activer_isolation('poids_periode');
SELECT activer_isolation('classe_examen');
SELECT activer_isolation('decision_fin_annee');
