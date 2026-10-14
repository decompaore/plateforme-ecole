-- =====================================================================
-- V34 : organisation des rôles vue avec les établissements (v0.47)
--   - mode d'admission de l'inscription et pièces du dossier ;
--   - visa mensuel du cahier de textes (censeur, chef des travaux) ;
--   - intérim du chef d'établissement.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Mode d'admission : il détermine les pièces attendues (un élève affecté
-- par l'État n'apporte que la décision d'affectation et son état civil).
-- Vide pour les inscriptions antérieures à la v0.47.
-- ---------------------------------------------------------------------
ALTER TABLE inscription ADD COLUMN mode_admission VARCHAR(20)
    CHECK (mode_admission IN ('AFFECTATION_ETAT', 'COMPLEMENT_EFFECTIF', 'TRANSFERT', 'REINSCRIPTION', 'PRIVE',
                              'AUTRE'));

-- Pièces attendues, réglées par établissement ; « modes » : modes d'admission
-- concernés, séparés par des virgules. Une pièce retirée de la liste reste
-- (actif = false) pour les dossiers où elle a été reçue.
CREATE TABLE piece_dossier (
    tenant_id   UUID         NOT NULL REFERENCES tenant (id),
    id          UUID         NOT NULL,
    libelle     VARCHAR(150) NOT NULL,
    modes       VARCHAR(200) NOT NULL,
    obligatoire BOOLEAN      NOT NULL DEFAULT true,
    ordre       SMALLINT     NOT NULL,
    actif       BOOLEAN      NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, id)
);
CREATE UNIQUE INDEX ux_piece_dossier_id ON piece_dossier (id);
CREATE UNIQUE INDEX ux_piece_dossier_libelle ON piece_dossier (tenant_id, upper(libelle));
SELECT activer_isolation('piece_dossier');

CREATE TABLE piece_recue (
    tenant_id      UUID NOT NULL,
    inscription_id UUID NOT NULL,
    piece_id       UUID NOT NULL,
    recue_le       DATE NOT NULL DEFAULT current_date,
    saisie_par     UUID,
    PRIMARY KEY (tenant_id, inscription_id, piece_id),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, piece_id) REFERENCES piece_dossier (tenant_id, id) ON DELETE CASCADE
);
SELECT activer_isolation('piece_recue');

-- ---------------------------------------------------------------------
-- Visa mensuel du cahier de textes d'une matière d'une classe. Un mois
-- visé est figé : l'enseignant n'y modifie plus de séance.
-- ---------------------------------------------------------------------
CREATE TABLE visa_cahier (
    tenant_id   UUID         NOT NULL,
    id          UUID         NOT NULL,
    classe_id   UUID         NOT NULL,
    matiere_id  UUID         NOT NULL,
    mois        DATE         NOT NULL CHECK (extract(day FROM mois) = 1),
    seances     INTEGER      NOT NULL CHECK (seances >= 0),
    observation VARCHAR(500),
    vise_par    UUID         NOT NULL REFERENCES utilisateur (id),
    vise_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, classe_id, matiere_id, mois),
    FOREIGN KEY (tenant_id, classe_id)  REFERENCES classe (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, matiere_id) REFERENCES matiere (tenant_id, id)
);
SELECT activer_isolation('visa_cahier');

-- ---------------------------------------------------------------------
-- Intérim du chef d'établissement : du « du » au « au » (ou à la veille de
-- « termine_le » + 1 en cas de retour anticipé), l'intérimaire reçoit le
-- rôle ADMIN_ECOLE dans ses jetons.
-- ---------------------------------------------------------------------
CREATE TABLE interim_direction (
    tenant_id      UUID         NOT NULL REFERENCES tenant (id),
    id             UUID         NOT NULL,
    utilisateur_id UUID         NOT NULL REFERENCES utilisateur (id),
    du             DATE         NOT NULL,
    au             DATE         NOT NULL,
    termine_le     DATE,
    motif          VARCHAR(200) NOT NULL,
    designe_par    UUID         NOT NULL REFERENCES utilisateur (id),
    designe_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CHECK (au >= du),
    CHECK (termine_le IS NULL OR termine_le >= du - 1)
);
CREATE INDEX ix_interim_utilisateur ON interim_direction (utilisateur_id, du);
SELECT activer_isolation('interim_direction');

-- Les établissements et rôles d'un compte, intérim en cours compris
CREATE OR REPLACE FUNCTION auth_etablissements_utilisateur(p_utilisateur UUID)
    RETURNS TABLE (tenant_id UUID, tenant_code VARCHAR, tenant_nom VARCHAR, role VARCHAR)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT x.tenant_id, x.code, x.nom, x.role
    FROM (
        SELECT m.tenant_id, t.code, t.nom, m.role
        FROM membre_etablissement m
        JOIN tenant t ON t.id = m.tenant_id
        WHERE m.utilisateur_id = p_utilisateur
          AND m.actif
          AND t.statut = 'ACTIF'
        UNION
        SELECT i.tenant_id, t.code, t.nom, 'ADMIN_ECOLE'::VARCHAR
        FROM interim_direction i
        JOIN tenant t ON t.id = i.tenant_id
        JOIN membre_etablissement m ON m.tenant_id = i.tenant_id AND m.utilisateur_id = i.utilisateur_id AND m.actif
        WHERE i.utilisateur_id = p_utilisateur
          AND t.statut = 'ACTIF'
          AND current_date BETWEEN i.du AND least(i.au, coalesce(i.termine_le, i.au))
    ) x
    ORDER BY x.nom, x.role
$$;
