-- =====================================================================
-- V16 : chef des travaux et fiches de progression
--
--   Rôle CHEF_TRAVAUX : supervise les matières techniques et pratiques (le
--   censeur supervise les matières générales).
--   fiche_progression    : progression annuelle d'une matière dans une classe,
--                          préparée par l'enseignant, visée par la direction
--   sequence_progression : séquences de la fiche, dans l'ordre
-- =====================================================================

-- La contrainte CHECK d'origine sur le rôle n'a pas de nom choisi : on la retrouve par sa colonne
DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey)
        WHERE con.contype = 'c'
          AND con.conrelid = 'membre_etablissement'::regclass
          AND att.attname = 'role'
    LOOP
        EXECUTE format('ALTER TABLE membre_etablissement DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE membre_etablissement ADD CONSTRAINT membre_role_valide
    CHECK (role IN ('ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'SECRETARIAT', 'INTENDANT',
                    'SURVEILLANT', 'ENSEIGNANT', 'PARENT', 'ELEVE'));

CREATE TABLE fiche_progression (
    tenant_id        UUID         NOT NULL,
    id               UUID         NOT NULL,
    classe_id        UUID         NOT NULL,
    matiere_id       UUID         NOT NULL,
    engagement_id    UUID         NOT NULL,              -- enseignant auteur
    statut           VARCHAR(10)  NOT NULL DEFAULT 'BROUILLON'
                     CHECK (statut IN ('BROUILLON', 'SOUMISE', 'VISEE', 'A_REVOIR')),
    modifiee_le      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    soumise_le       TIMESTAMPTZ,
    vise_par         UUID         REFERENCES utilisateur (id),
    vise_le          TIMESTAMPTZ,
    commentaire_visa VARCHAR(500),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, classe_id, matiere_id),
    CHECK ((statut IN ('VISEE', 'A_REVOIR')) = (vise_le IS NOT NULL)),
    CHECK (statut <> 'A_REVOIR' OR commentaire_visa IS NOT NULL),
    FOREIGN KEY (tenant_id, classe_id)     REFERENCES classe (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, matiere_id)    REFERENCES matiere (tenant_id, id),
    FOREIGN KEY (tenant_id, engagement_id) REFERENCES engagement_enseignant (tenant_id, id)
);

CREATE TABLE sequence_progression (
    tenant_id      UUID          NOT NULL,
    id             UUID          NOT NULL,
    fiche_id       UUID          NOT NULL,
    ordre          SMALLINT      NOT NULL CHECK (ordre BETWEEN 1 AND 200),
    titre          VARCHAR(150)  NOT NULL,
    contenu        VARCHAR(2000),
    competences    VARCHAR(500),
    heures_prevues NUMERIC(5,1)  NOT NULL CHECK (heures_prevues > 0 AND heures_prevues <= 999),
    semaine_debut  DATE,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, fiche_id, ordre),
    FOREIGN KEY (tenant_id, fiche_id) REFERENCES fiche_progression (tenant_id, id) ON DELETE CASCADE
);

SELECT activer_isolation('fiche_progression');
SELECT activer_isolation('sequence_progression');
