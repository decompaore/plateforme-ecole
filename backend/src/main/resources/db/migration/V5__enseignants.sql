-- =====================================================================
-- V5 : enseignants
--   enseignant            : identité UNIQUE sur toute la plateforme (sans tenant_id)
--   engagement_enseignant : titulaire ou vacataire dans un établissement (cloisonné)
--   classe_matiere        : + enseignant affecté (engagement dans CETTE école)
--
-- Règle de gestion : un enseignant est titulaire dans un seul établissement
-- à la fois, et vacataire dans un ou plusieurs autres. Les contraintes
-- d'exclusion portent sur TOUTES les lignes, y compris celles des autres
-- écoles que la Row-Level Security rend invisibles : la règle est garantie
-- sans qu'aucune école ne voie où l'enseignant travaille ailleurs.
-- =====================================================================

CREATE TABLE enseignant (
    id             UUID         PRIMARY KEY,
    utilisateur_id UUID         NOT NULL UNIQUE REFERENCES utilisateur (id),  -- compte de connexion unique
    telephone      VARCHAR(20)  NOT NULL UNIQUE,                               -- identique à celui du compte
    matricule_fp   VARCHAR(30)  UNIQUE,                                        -- matricule fonction publique
    nom            VARCHAR(80)  NOT NULL,
    prenoms        VARCHAR(120) NOT NULL,
    sexe           CHAR(1)      NOT NULL CHECK (sexe IN ('M', 'F')),
    specialite     VARCHAR(80),
    cree_le        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE engagement_enseignant (
    tenant_id     UUID         NOT NULL REFERENCES tenant (id),
    id            UUID         NOT NULL,
    enseignant_id UUID         NOT NULL REFERENCES enseignant (id),
    type          VARCHAR(10)  NOT NULL CHECK (type IN ('TITULAIRE', 'VACATAIRE')),
    statut        VARCHAR(10)  NOT NULL DEFAULT 'INVITE'
                  CHECK (statut IN ('INVITE', 'ACTIF', 'TERMINE', 'REFUSE')),
    debut         DATE         NOT NULL,
    fin           DATE,                                   -- NULL : sans date de fin
    taux_horaire  NUMERIC(8,0) CHECK (taux_horaire > 0),  -- FCFA / heure, vacataires uniquement
    motif_fin     VARCHAR(200),
    cree_le       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    CHECK (fin IS NULL OR fin >= debut),
    CHECK (type = 'VACATAIRE' OR taux_horaire IS NULL),
    -- Règle 1 : un seul poste de titulaire à la fois, toutes écoles confondues
    CONSTRAINT un_seul_poste_titulaire EXCLUDE USING gist (
        enseignant_id WITH =, daterange(debut, fin, '[]') WITH &&
    ) WHERE (type = 'TITULAIRE' AND statut IN ('INVITE', 'ACTIF')),
    -- Règle 2 : un seul engagement à la fois dans un même établissement
    CONSTRAINT un_engagement_par_ecole EXCLUDE USING gist (
        enseignant_id WITH =, tenant_id WITH =, daterange(debut, fin, '[]') WITH &&
    ) WHERE (statut IN ('INVITE', 'ACTIF'))
);
CREATE INDEX ix_engagement_tenant ON engagement_enseignant (tenant_id, statut);
CREATE INDEX ix_engagement_enseignant ON engagement_enseignant (enseignant_id);

SELECT activer_isolation('engagement_enseignant');

-- ---------------------------------------------------------------------
-- Identité : une école ne voit que les enseignants engagés (ou invités) chez elle
-- ---------------------------------------------------------------------
ALTER TABLE enseignant ENABLE ROW LEVEL SECURITY;
ALTER TABLE enseignant FORCE ROW LEVEL SECURITY;
CREATE POLICY lecture_si_engage ON enseignant FOR SELECT
    USING (EXISTS (SELECT 1 FROM engagement_enseignant e
                   WHERE e.enseignant_id = enseignant.id AND e.tenant_id = tenant_courant()));
CREATE POLICY modification_si_engage ON enseignant FOR UPDATE
    USING (EXISTS (SELECT 1 FROM engagement_enseignant e
                   WHERE e.enseignant_id = enseignant.id AND e.tenant_id = tenant_courant()));
-- Création par une école (l'engagement est créé dans la même transaction)
CREATE POLICY creation_par_une_ecole ON enseignant FOR INSERT
    WITH CHECK (tenant_courant() IS NOT NULL);
REVOKE DELETE ON enseignant FROM ${app_role};

-- ---------------------------------------------------------------------
-- Affectation : l'enseignant d'une matière dans une classe
-- ---------------------------------------------------------------------
ALTER TABLE classe_matiere ADD COLUMN engagement_id UUID;
ALTER TABLE classe_matiere ADD CONSTRAINT classe_matiere_engagement_fkey
    FOREIGN KEY (tenant_id, engagement_id) REFERENCES engagement_enseignant (tenant_id, id);
CREATE INDEX ix_classe_matiere_engagement ON classe_matiere (tenant_id, engagement_id)
    WHERE engagement_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- Fonctions de niveau plateforme (SECURITY DEFINER : lisent hors cloisonnement,
-- mais ne renvoient que le strict nécessaire)
-- ---------------------------------------------------------------------

-- Recherche d'une identité existante (téléphone ou matricule) : l'API ne
-- renvoie que « trouvé / non trouvé », l'identifiant sert à créer l'invitation.
CREATE FUNCTION enseignant_par_identifiant(p_telephone VARCHAR, p_matricule VARCHAR)
    RETURNS UUID
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT id FROM enseignant
    WHERE (p_telephone IS NOT NULL AND telephone = p_telephone)
       OR (p_matricule IS NOT NULL AND matricule_fp = p_matricule)
    ORDER BY (telephone = p_telephone) DESC NULLS LAST
    LIMIT 1
$$;

-- L'enseignant occupe-t-il déjà un poste de titulaire sur la période (toutes écoles) ?
CREATE FUNCTION poste_titulaire_occupe(p_enseignant UUID, p_debut DATE, p_fin DATE)
    RETURNS BOOLEAN
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1 FROM engagement_enseignant
        WHERE enseignant_id = p_enseignant
          AND type = 'TITULAIRE' AND statut IN ('INVITE', 'ACTIF')
          AND daterange(debut, fin, '[]') && daterange(p_debut, p_fin, '[]'))
$$;

-- Invitations en attente d'un compte (utilisées à la connexion et par l'espace enseignant)
CREATE FUNCTION invitations_enseignant(p_utilisateur UUID)
    RETURNS TABLE (engagement_id UUID, tenant_id UUID, tenant_nom VARCHAR, type VARCHAR,
                   debut DATE, fin DATE, taux_horaire NUMERIC)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT e.id, e.tenant_id, t.nom, e.type, e.debut, e.fin, e.taux_horaire
    FROM engagement_enseignant e
    JOIN enseignant s ON s.id = e.enseignant_id
    JOIN tenant t ON t.id = e.tenant_id
    WHERE s.utilisateur_id = p_utilisateur
      AND e.statut = 'INVITE'
      AND t.statut = 'ACTIF'
    ORDER BY e.cree_le
$$;

REVOKE ALL ON FUNCTION enseignant_par_identifiant(VARCHAR, VARCHAR) FROM PUBLIC;
REVOKE ALL ON FUNCTION poste_titulaire_occupe(UUID, DATE, DATE) FROM PUBLIC;
REVOKE ALL ON FUNCTION invitations_enseignant(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION enseignant_par_identifiant(VARCHAR, VARCHAR) TO ${app_role};
GRANT EXECUTE ON FUNCTION poste_titulaire_occupe(UUID, DATE, DATE) TO ${app_role};
GRANT EXECUTE ON FUNCTION invitations_enseignant(UUID) TO ${app_role};
