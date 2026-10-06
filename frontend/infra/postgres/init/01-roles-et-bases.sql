-- =====================================================================
-- Création des rôles et des bases – DÉVELOPPEMENT ET CI UNIQUEMENT
-- (en production, les rôles sont créés par l'exploitant avec des mots
--  de passe forts stockés dans le gestionnaire de secrets)
-- Exécuté par le superutilisateur PostgreSQL.
-- =====================================================================

-- Propriétaire du schéma : exécute les migrations Flyway.
-- BYPASSRLS lui permet d'exécuter les fonctions SECURITY DEFINER.
CREATE ROLE plateforme_owner LOGIN PASSWORD 'owner_dev' BYPASSRLS;

-- Rôle de l'application : soumis à la Row-Level Security.
CREATE ROLE app_plateforme LOGIN PASSWORD 'app_dev' NOBYPASSRLS;

CREATE DATABASE plateforme      OWNER plateforme_owner;
CREATE DATABASE plateforme_test OWNER plateforme_owner;
