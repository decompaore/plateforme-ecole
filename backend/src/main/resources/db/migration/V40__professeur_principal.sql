-- =====================================================================
-- V40 : professeur principal de la classe (v0.49.1)
--
--   professeur_principal : un enseignant de la classe, désigné par le chef
--                          d'établissement ou le censeur, interface entre les
--                          enseignants, les élèves et l'administration
--   saisine_classe       : dossier suivi par le professeur principal :
--                          REVENDICATION (voix des enseignants de la classe,
--                          portée à l'administration) ou MEDIATION (souci d'un
--                          élève ou d'un groupe d'élèves avec un enseignant)
--   suite_saisine        : échanges et démarches d'un dossier (journal)
--   verification_notes   : vérification des notes et moyennes d'une période
--                          avec les élèves, attestée par le professeur
--                          principal avant le conseil de classe et les bulletins
--   reclamation_note     : réclamation d'un élève sur une note, transmise à
--                          l'enseignant de la matière, qui corrige ou maintient
-- =====================================================================

CREATE TABLE professeur_principal (
    tenant_id      UUID        NOT NULL REFERENCES tenant (id),
    classe_id      UUID        NOT NULL,
    utilisateur_id UUID        NOT NULL REFERENCES utilisateur (id),
    designe_par    UUID        REFERENCES utilisateur (id),
    designe_le     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, classe_id),
    FOREIGN KEY (tenant_id, classe_id) REFERENCES classe (tenant_id, id) ON DELETE CASCADE
);
SELECT activer_isolation('professeur_principal');
CREATE INDEX professeur_principal_utilisateur ON professeur_principal (tenant_id, utilisateur_id);

CREATE TABLE saisine_classe (
    tenant_id          UUID          NOT NULL REFERENCES tenant (id),
    id                 UUID          NOT NULL,
    numero             VARCHAR(20)   NOT NULL,
    classe_id          UUID          NOT NULL,
    nature             VARCHAR(15)   NOT NULL CHECK (nature IN ('REVENDICATION', 'MEDIATION')),
    objet              VARCHAR(200)  NOT NULL,
    expose             VARCHAR(4000) NOT NULL,
    auteur_id          UUID          NOT NULL REFERENCES utilisateur (id),
    professeur_id      UUID          NOT NULL REFERENCES utilisateur (id),  -- professeur principal en charge
    inscriptions       UUID[]        NOT NULL DEFAULT '{}',                 -- élèves concernés (médiation)
    matiere_id         UUID,
    enseignant_id      UUID          REFERENCES utilisateur (id),           -- enseignant concerné (médiation)
    enseignant_informe BOOLEAN       NOT NULL DEFAULT false,
    statut             VARCHAR(10)   NOT NULL DEFAULT 'OUVERTE'
                       CHECK (statut IN ('OUVERTE', 'TRANSMISE', 'CLOSE')),
    transmise_le       TIMESTAMPTZ,
    transmise_par      UUID          REFERENCES utilisateur (id),
    conclusion         VARCHAR(1000),
    close_le           TIMESTAMPTZ,
    close_par          UUID          REFERENCES utilisateur (id),
    cree_le            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, numero),
    CHECK ((statut = 'CLOSE') = (close_le IS NOT NULL)),
    CHECK (statut <> 'TRANSMISE' OR transmise_le IS NOT NULL),
    CHECK (nature = 'MEDIATION' OR (enseignant_id IS NULL AND cardinality(inscriptions) = 0)),
    FOREIGN KEY (tenant_id, classe_id) REFERENCES classe (tenant_id, id),
    FOREIGN KEY (tenant_id, matiere_id) REFERENCES matiere (tenant_id, id)
);
SELECT activer_isolation('saisine_classe');
CREATE INDEX saisine_classe_classe ON saisine_classe (tenant_id, classe_id, statut);

CREATE TABLE suite_saisine (
    tenant_id  UUID          NOT NULL REFERENCES tenant (id),
    id         UUID          NOT NULL,
    saisine_id UUID          NOT NULL,
    auteur_id  UUID          NOT NULL REFERENCES utilisateur (id),
    qualite    VARCHAR(60)   NOT NULL,
    texte      VARCHAR(2000) NOT NULL,
    cree_le    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, saisine_id) REFERENCES saisine_classe (tenant_id, id) ON DELETE CASCADE
);
SELECT activer_isolation('suite_saisine');
CREATE INDEX suite_saisine_saisine ON suite_saisine (tenant_id, saisine_id, cree_le);

CREATE TABLE verification_notes (
    tenant_id    UUID          NOT NULL REFERENCES tenant (id),
    classe_id    UUID          NOT NULL,
    periode_id   UUID          NOT NULL,
    statut       VARCHAR(10)   NOT NULL DEFAULT 'EN_COURS' CHECK (statut IN ('EN_COURS', 'VALIDEE')),
    ouverte_le   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    validee_le   TIMESTAMPTZ,
    validee_par  UUID          REFERENCES utilisateur (id),
    observations VARCHAR(2000),
    PRIMARY KEY (tenant_id, classe_id, periode_id),
    CHECK ((statut = 'VALIDEE') = (validee_le IS NOT NULL)),
    FOREIGN KEY (tenant_id, classe_id) REFERENCES classe (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, periode_id) REFERENCES periode (tenant_id, id) ON DELETE CASCADE
);
SELECT activer_isolation('verification_notes');

CREATE TABLE reclamation_note (
    tenant_id       UUID          NOT NULL REFERENCES tenant (id),
    id              UUID          NOT NULL,
    classe_id       UUID          NOT NULL,
    periode_id      UUID          NOT NULL,
    inscription_id  UUID          NOT NULL,
    matiere_id      UUID          NOT NULL,
    evaluation_id   UUID,
    note_contestee  NUMERIC(5,2),
    motif           VARCHAR(1000) NOT NULL,
    enseignant_id   UUID          REFERENCES utilisateur (id),            -- enseignant de la matière
    enregistree_par UUID          NOT NULL REFERENCES utilisateur (id),
    enregistree_le  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    statut          VARCHAR(10)   NOT NULL DEFAULT 'OUVERTE'
                    CHECK (statut IN ('OUVERTE', 'CORRIGEE', 'MAINTENUE')),
    note_corrigee   NUMERIC(5,2),
    reponse         VARCHAR(1000),
    repondue_par    UUID          REFERENCES utilisateur (id),
    repondue_le     TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    CHECK ((statut = 'OUVERTE') = (repondue_le IS NULL)),
    FOREIGN KEY (tenant_id, classe_id) REFERENCES classe (tenant_id, id),
    FOREIGN KEY (tenant_id, periode_id) REFERENCES periode (tenant_id, id),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id),
    FOREIGN KEY (tenant_id, matiere_id) REFERENCES matiere (tenant_id, id),
    FOREIGN KEY (tenant_id, evaluation_id) REFERENCES evaluation (tenant_id, id)
);
SELECT activer_isolation('reclamation_note');
CREATE INDEX reclamation_note_classe ON reclamation_note (tenant_id, classe_id, periode_id, statut);
CREATE INDEX reclamation_note_enseignant ON reclamation_note (tenant_id, enseignant_id, statut);
