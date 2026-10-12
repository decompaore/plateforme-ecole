-- =====================================================================
-- V32 : photos d'identité, signature des enseignants, visa des matières
-- sur les bulletins (v0.44).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Photo d'identité d'un élève (fiche, listes, bulletin, carte scolaire).
-- Image normalisée par l'application : JPEG, 480 × 600 pixels au plus, et une
-- miniature de 96 × 120 pixels pour les listes (quelques Ko sur un réseau lent).
-- ---------------------------------------------------------------------
CREATE TABLE photo_eleve (
    tenant_id    UUID        NOT NULL REFERENCES tenant (id),
    eleve_id     UUID        NOT NULL,
    type_contenu VARCHAR(20) NOT NULL CHECK (type_contenu IN ('image/jpeg', 'image/png')),
    contenu      BYTEA       NOT NULL,
    miniature    BYTEA       NOT NULL,
    modifie_le   TIMESTAMPTZ NOT NULL DEFAULT now(),
    modifie_par  UUID        REFERENCES utilisateur (id),
    PRIMARY KEY (tenant_id, eleve_id),
    FOREIGN KEY (tenant_id, eleve_id) REFERENCES eleve (tenant_id, id) ON DELETE CASCADE
);
SELECT activer_isolation('photo_eleve');

-- ---------------------------------------------------------------------
-- Photo d'un membre de l'établissement (enseignant, personnel) : propre à
-- chaque établissement, un enseignant partagé peut avoir deux photos.
-- ---------------------------------------------------------------------
CREATE TABLE photo_personne (
    tenant_id      UUID        NOT NULL REFERENCES tenant (id),
    utilisateur_id UUID        NOT NULL REFERENCES utilisateur (id),
    type_contenu   VARCHAR(20) NOT NULL CHECK (type_contenu IN ('image/jpeg', 'image/png')),
    contenu        BYTEA       NOT NULL,
    miniature      BYTEA       NOT NULL,
    modifie_le     TIMESTAMPTZ NOT NULL DEFAULT now(),
    modifie_par    UUID        REFERENCES utilisateur (id),
    PRIMARY KEY (tenant_id, utilisateur_id)
);
SELECT activer_isolation('photo_personne');

-- ---------------------------------------------------------------------
-- Signature manuscrite numérisée d'un compte (dessinée ou photographiée) :
-- elle appartient à la personne, quel que soit l'établissement. Seul son
-- titulaire la dépose ou la retire ; les bulletins la lisent au moment où
-- ils sont produits.
-- ---------------------------------------------------------------------
CREATE TABLE signature_utilisateur (
    utilisateur_id UUID        PRIMARY KEY REFERENCES utilisateur (id),
    type_contenu   VARCHAR(20) NOT NULL CHECK (type_contenu IN ('image/png', 'image/jpeg')),
    contenu        BYTEA       NOT NULL,
    modifie_le     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Visa d'une matière sur les bulletins d'une classe pour une période :
-- l'enseignant atteste ses notes et ses appréciations. Une appréciation
-- modifiée après le visa l'annule (l'application le retire).
-- ---------------------------------------------------------------------
CREATE TABLE visa_matiere (
    tenant_id     UUID        NOT NULL REFERENCES tenant (id),
    id            UUID        NOT NULL,
    classe_id     UUID        NOT NULL,
    matiere_id    UUID        NOT NULL,
    periode_id    UUID        NOT NULL,
    engagement_id UUID        NOT NULL,
    vise_par      UUID        NOT NULL REFERENCES utilisateur (id),
    vise_le       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, classe_id, matiere_id, periode_id),
    FOREIGN KEY (tenant_id, classe_id)     REFERENCES classe (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, matiere_id)    REFERENCES matiere (tenant_id, id),
    FOREIGN KEY (tenant_id, periode_id)    REFERENCES periode (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, engagement_id) REFERENCES engagement_enseignant (tenant_id, id)
);
SELECT activer_isolation('visa_matiere');
