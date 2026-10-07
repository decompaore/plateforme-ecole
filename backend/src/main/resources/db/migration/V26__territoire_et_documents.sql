-- =====================================================================
-- V26 : rattachement territorial des établissements et documents officiels
--
-- Pays → ministère → directions (niveaux paramétrables par ministère, par
-- exemple « Direction régionale » puis « Direction provinciale ») → établissement.
-- Tables de niveau plateforme (non cloisonnées) saisies par le super administrateur.
-- Le logo de chaque établissement est cloisonné (Row-Level Security).
-- =====================================================================

CREATE TABLE pays (
    id                  UUID         PRIMARY KEY,
    code                CHAR(2)      NOT NULL UNIQUE CHECK (code ~ '^[A-Z]{2}$'),   -- ISO 3166-1 alpha-2
    nom                 VARCHAR(100) NOT NULL,
    devise_nationale    VARCHAR(200),                                               -- en-tête des documents
    indicatif_telephone VARCHAR(5)   NOT NULL CHECK (indicatif_telephone ~ '^\+[0-9]{1,4}$'),
    longueur_numero     SMALLINT     NOT NULL CHECK (longueur_numero BETWEEN 6 AND 12), -- numéro national
    fuseau_horaire      VARCHAR(60)  NOT NULL,                                      -- IANA, ex. Africa/Ouagadougou
    monnaie             CHAR(3)      NOT NULL CHECK (monnaie ~ '^[A-Z]{3}$'),       -- ISO 4217, ex. XOF
    langue              VARCHAR(10)  NOT NULL DEFAULT 'fr',
    cree_le             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE ministere (
    id       UUID         PRIMARY KEY,
    pays_id  UUID         NOT NULL REFERENCES pays (id),
    sigle    VARCHAR(30)  NOT NULL,
    nom      VARCHAR(250) NOT NULL,
    actif    BOOLEAN      NOT NULL DEFAULT true,
    cree_le  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (pays_id, sigle)
);

-- Noms des niveaux de directions d'un ministère : 1 = le plus haut (ex. Direction régionale)
CREATE TABLE niveau_direction (
    ministere_id UUID        NOT NULL REFERENCES ministere (id) ON DELETE CASCADE,
    rang         SMALLINT    NOT NULL CHECK (rang BETWEEN 1 AND 5),
    libelle      VARCHAR(80) NOT NULL,
    PRIMARY KEY (ministere_id, rang)
);

CREATE TABLE direction (
    id           UUID         PRIMARY KEY,
    ministere_id UUID         NOT NULL REFERENCES ministere (id),
    parent_id    UUID         REFERENCES direction (id),
    rang         SMALLINT     NOT NULL CHECK (rang BETWEEN 1 AND 5),
    code         VARCHAR(30)  NOT NULL,
    nom          VARCHAR(200) NOT NULL,                  -- nom complet, tel qu'il figure sur les documents
    actif        BOOLEAN      NOT NULL DEFAULT true,
    cree_le      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (ministere_id, code),
    CHECK ((rang = 1) = (parent_id IS NULL))
);
CREATE INDEX direction_parent ON direction (parent_id);

-- Établissement rattaché à une direction du dernier niveau de son ministère
ALTER TABLE tenant ADD COLUMN direction_id UUID REFERENCES direction (id);
CREATE INDEX tenant_direction ON tenant (direction_id);

-- Directions au-dessus d'une direction (elle comprise), du niveau 1 au plus bas
CREATE FUNCTION directions_ascendantes(p_direction UUID)
    RETURNS TABLE (id UUID, rang SMALLINT, code VARCHAR, nom VARCHAR, ministere_id UUID)
    LANGUAGE sql STABLE
AS $$
    WITH RECURSIVE c AS (
        SELECT d.id, d.parent_id, d.rang, d.code, d.nom, d.ministere_id FROM direction d WHERE d.id = p_direction
        UNION ALL
        SELECT p.id, p.parent_id, p.rang, p.code, p.nom, p.ministere_id FROM direction p JOIN c ON c.parent_id = p.id
    )
    SELECT c.id, c.rang, c.code, c.nom, c.ministere_id FROM c ORDER BY c.rang
$$;

-- Rattachement en une ligne : « Burkina Faso · MESFPT · Direction régionale … · Direction provinciale … »
CREATE FUNCTION chemin_direction(p_direction UUID)
    RETURNS TEXT
    LANGUAGE sql STABLE
AS $$
    SELECT p.nom || ' · ' || m.sigle || ' · ' || string_agg(a.nom, ' · ' ORDER BY a.rang)
      FROM directions_ascendantes(p_direction) a
      JOIN ministere m ON m.id = a.ministere_id
      JOIN pays p ON p.id = m.pays_id
     GROUP BY p.nom, m.sigle
$$;

-- Une direction et toutes celles qui en dépendent
CREATE FUNCTION directions_descendantes(p_direction UUID)
    RETURNS SETOF UUID
    LANGUAGE sql STABLE
AS $$
    WITH RECURSIVE c AS (
        SELECT d.id FROM direction d WHERE d.id = p_direction
        UNION ALL
        SELECT e.id FROM direction e JOIN c ON e.parent_id = c.id
    )
    SELECT id FROM c
$$;

-- Adoption (v0.34) limitée à des établissements (ceux d'une direction) : personnes actives par jour
CREATE FUNCTION adoption_quotidienne_etablissements(p_debut DATE, p_fin DATE, p_tenants UUID[])
    RETURNS TABLE (jour DATE, actifs INT)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT a.jour, count(DISTINCT a.utilisateur_id)::int
      FROM activite_jour a
     WHERE a.jour BETWEEN p_debut AND p_fin AND a.tenant_id = ANY (p_tenants)
     GROUP BY a.jour
$$;
REVOKE ALL ON FUNCTION adoption_quotidienne_etablissements(DATE, DATE, UUID[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION adoption_quotidienne_etablissements(DATE, DATE, UUID[]) TO ${app_role};

-- Logo de l'établissement, imprimé sur ses documents officiels
CREATE TABLE logo_etablissement (
    tenant_id    UUID        PRIMARY KEY REFERENCES tenant (id),
    type_contenu VARCHAR(20) NOT NULL CHECK (type_contenu IN ('image/png', 'image/jpeg')),
    contenu      BYTEA       NOT NULL,
    modifie_le   TIMESTAMPTZ NOT NULL DEFAULT now(),
    modifie_par  UUID        REFERENCES utilisateur (id)
);
SELECT activer_isolation('logo_etablissement');

-- Pays de la première installation : ses paramètres sont ceux utilisés jusqu'ici par la
-- plateforme (indicatif +226, numéros à 8 chiffres, heure de Ouagadougou, franc CFA).
-- La devise nationale, les ministères et les directions sont saisis par le super administrateur.
INSERT INTO pays (id, code, nom, indicatif_telephone, longueur_numero, fuseau_horaire, monnaie, langue)
VALUES (gen_random_uuid(), 'BF', 'Burkina Faso', '+226', 8, 'Africa/Ouagadougou', 'XOF', 'fr');
