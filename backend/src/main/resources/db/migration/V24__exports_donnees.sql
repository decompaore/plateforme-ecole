-- =====================================================================
-- V24 : export complet des données d'un établissement (réversibilité)
--
-- L'administrateur de l'établissement (ou le super administrateur) demande une
-- archive ZIP de toutes les données de l'établissement : une table = un fichier
-- CSV, plus un manifeste qui décrit les colonnes et l'empreinte de chaque fichier.
-- L'archive est préparée en tâche de fond, gardée quelques jours sur le serveur,
-- puis effacée. Cette table n'en garde que la trace.
-- =====================================================================

CREATE TABLE export_donnees (
    tenant_id              UUID         NOT NULL REFERENCES tenant (id),
    id                     UUID         PRIMARY KEY,
    demande_par            UUID         REFERENCES utilisateur (id),
    par_plateforme         BOOLEAN      NOT NULL DEFAULT false,
    demande_le             TIMESTAMPTZ  NOT NULL,
    statut                 VARCHAR(10)  NOT NULL CHECK (statut IN ('EN_COURS', 'PRET', 'ECHEC')),
    termine_le             TIMESTAMPTZ,
    expire_le              TIMESTAMPTZ,
    taille                 BIGINT,
    empreinte              CHAR(64),                 -- SHA-256 de l'archive (hexadécimal)
    nombre_tables          INTEGER,
    nombre_lignes          BIGINT,
    erreur                 VARCHAR(300),
    telechargements        INTEGER      NOT NULL DEFAULT 0,
    dernier_telechargement TIMESTAMPTZ
);

CREATE INDEX export_donnees_recents ON export_donnees (tenant_id, demande_le DESC);
-- Un seul export en préparation à la fois par établissement
CREATE UNIQUE INDEX export_donnees_un_en_cours ON export_donnees (tenant_id) WHERE statut = 'EN_COURS';

SELECT activer_isolation('export_donnees');
