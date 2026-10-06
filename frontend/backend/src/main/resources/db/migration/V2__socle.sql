-- =====================================================================
-- V2 : socle – établissements, comptes, appartenances, jetons, audit
-- =====================================================================

-- Établissement (tenant) : table de niveau plateforme, sans RLS
CREATE TABLE tenant (
    id       UUID PRIMARY KEY,
    code     VARCHAR(30)  NOT NULL UNIQUE
             CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$'),   -- sert de sous-domaine
    nom      VARCHAR(200) NOT NULL,
    statut   VARCHAR(20)  NOT NULL DEFAULT 'ACTIF'
             CHECK (statut IN ('ACTIF', 'SUSPENDU', 'RESILIE')),
    cree_le  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Compte de connexion : un seul par personne sur toute la plateforme
CREATE TABLE utilisateur (
    id                        UUID PRIMARY KEY,
    telephone                 VARCHAR(20)  NOT NULL UNIQUE,   -- format international (+226...)
    email                     VARCHAR(200),
    nom                       VARCHAR(80)  NOT NULL,
    prenoms                   VARCHAR(120) NOT NULL,
    mot_de_passe_hache        VARCHAR(100) NOT NULL,
    super_admin               BOOLEAN      NOT NULL DEFAULT false,
    actif                     BOOLEAN      NOT NULL DEFAULT true,
    doit_changer_mot_de_passe BOOLEAN      NOT NULL DEFAULT true,
    echecs_connexion          INTEGER      NOT NULL DEFAULT 0,
    verrouille_jusqua         TIMESTAMPTZ,
    derniere_connexion        TIMESTAMPTZ,
    cree_le                   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Appartenance d'un compte à un établissement, avec un rôle (cloisonnée)
CREATE TABLE membre_etablissement (
    tenant_id      UUID        NOT NULL REFERENCES tenant (id),
    id             UUID        NOT NULL,
    utilisateur_id UUID        NOT NULL REFERENCES utilisateur (id),
    role           VARCHAR(20) NOT NULL
                   CHECK (role IN ('ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT', 'INTENDANT',
                                   'SURVEILLANT', 'ENSEIGNANT', 'PARENT', 'ELEVE')),
    actif          BOOLEAN     NOT NULL DEFAULT true,
    cree_le        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, utilisateur_id, role)
);
CREATE INDEX ix_membre_utilisateur ON membre_etablissement (utilisateur_id);

-- Jetons de rafraîchissement (seul le haché SHA-256 est stocké)
CREATE TABLE jeton_rafraichissement (
    id             UUID PRIMARY KEY,
    utilisateur_id UUID        NOT NULL REFERENCES utilisateur (id),
    tenant_id      UUID        REFERENCES tenant (id),          -- NULL pour le super administrateur
    hache          CHAR(64)    NOT NULL UNIQUE,
    famille        UUID        NOT NULL,                        -- chaîne de rotation
    expire_le      TIMESTAMPTZ NOT NULL,
    revoque_le     TIMESTAMPTZ,
    cree_le        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_jeton_famille ON jeton_rafraichissement (famille);

-- Journal d'audit (partitionnement par mois prévu au palier 3)
CREATE TABLE journal_audit (
    id             UUID PRIMARY KEY,
    tenant_id      UUID REFERENCES tenant (id),                 -- NULL : action de niveau plateforme
    utilisateur_id UUID,
    action         VARCHAR(60)  NOT NULL,
    cible          VARCHAR(120),
    details        TEXT,
    adresse_ip     VARCHAR(45),
    horodatage     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_tenant_date ON journal_audit (tenant_id, horodatage DESC);

-- ---------------------------------------------------------------------
-- Row-Level Security
-- ---------------------------------------------------------------------
ALTER TABLE membre_etablissement ENABLE ROW LEVEL SECURITY;
ALTER TABLE membre_etablissement FORCE ROW LEVEL SECURITY;
CREATE POLICY isolation_tenant ON membre_etablissement
    USING (tenant_id = tenant_courant())
    WITH CHECK (tenant_id = tenant_courant());

ALTER TABLE journal_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE journal_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY lecture_audit ON journal_audit FOR SELECT
    USING (tenant_id = tenant_courant());
CREATE POLICY ecriture_audit ON journal_audit FOR INSERT
    WITH CHECK (tenant_id IS NULL OR tenant_id = tenant_courant());

-- Le journal d'audit n'est jamais modifié ni supprimé par l'application
REVOKE UPDATE, DELETE ON journal_audit FROM ${app_role};
-- Les établissements ne sont jamais supprimés (statut RESILIE)
REVOKE DELETE ON tenant FROM ${app_role};

-- ---------------------------------------------------------------------
-- Établissements accessibles à un compte (utilisée à la connexion,
-- avant qu'un établissement soit choisi). SECURITY DEFINER : exécutée
-- avec les droits du propriétaire (BYPASSRLS) ; ne renvoie que les
-- établissements du compte demandé.
-- ---------------------------------------------------------------------
CREATE FUNCTION auth_etablissements_utilisateur(p_utilisateur UUID)
    RETURNS TABLE (tenant_id UUID, tenant_code VARCHAR, tenant_nom VARCHAR, role VARCHAR)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT m.tenant_id, t.code, t.nom, m.role
    FROM membre_etablissement m
    JOIN tenant t ON t.id = m.tenant_id
    WHERE m.utilisateur_id = p_utilisateur
      AND m.actif
      AND t.statut = 'ACTIF'
    ORDER BY t.nom, m.role
$$;
REVOKE ALL ON FUNCTION auth_etablissements_utilisateur(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_etablissements_utilisateur(UUID) TO ${app_role};
