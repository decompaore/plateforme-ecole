-- =====================================================================
-- V1 : extensions et privilèges par défaut
-- Exécuté par le rôle propriétaire du schéma (FLYWAY_USER), qui possède
-- l'attribut BYPASSRLS. L'application se connecte avec ${app_role},
-- sans BYPASSRLS et sans propriété des tables : la Row-Level Security
-- s'applique donc toujours à elle.
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Toute nouvelle table créée par le propriétaire est accessible à l'application
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ${app_role};
GRANT USAGE ON SCHEMA public TO ${app_role};

-- Fonction utilitaire : établissement actif de la transaction (NULL si aucun).
-- NULLIF est indispensable : une fois défini dans une session, un paramètre
-- personnalisé vaut '' (et non NULL) après la fin de la transaction.
CREATE FUNCTION tenant_courant() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::uuid $$;
