-- =====================================================================
-- V38 : interpellation des enseignants en retard sur leur progression, et
-- armoiries des pays (filigrane des écrans)
--
--   interpellation  : rappel ou demande d'explication écrite adressée par le
--                     censeur (matières générales), le chef des travaux
--                     (matières techniques et pratiques) ou le chef
--                     d'établissement à l'enseignant d'une matière d'une classe,
--                     au vu de sa progression et de son cahier de textes ; le
--                     constat chiffré est figé à l'émission ; l'enseignant répond
--                     dans l'application ; la direction donne une suite et clôt.
--   armoiries_pays  : image officielle des armoiries d'un pays, téléversée par
--                     l'administrateur du pays ou le super administrateur
-- =====================================================================

CREATE TABLE interpellation (
    tenant_id          UUID          NOT NULL REFERENCES tenant (id),
    id                 UUID          NOT NULL,
    numero             VARCHAR(20),
    type               VARCHAR(20)   NOT NULL CHECK (type IN ('RAPPEL', 'DEMANDE_EXPLICATION')),
    classe_id          UUID          NOT NULL,
    matiere_id         UUID          NOT NULL,
    engagement_id      UUID          NOT NULL,
    destinataire_id    UUID          NOT NULL REFERENCES utilisateur (id),
    domaine            VARCHAR(10)   NOT NULL CHECK (domaine IN ('GENERAL', 'TECHNIQUE')),
    objet              VARCHAR(200)  NOT NULL,
    constat            VARCHAR(4000) NOT NULL,
    demande            VARCHAR(2000),
    heures_prevues     NUMERIC(7,2),
    heures_realisees   NUMERIC(7,2),
    delai_reponse      DATE,
    qualite_emetteur   VARCHAR(60)   NOT NULL,
    emise_par          UUID          NOT NULL REFERENCES utilisateur (id),
    emise_le           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    statut             VARCHAR(10)   NOT NULL DEFAULT 'ENVOYEE'
                       CHECK (statut IN ('ENVOYEE', 'LUE', 'REPONDUE', 'CLOSE')),
    lue_le             TIMESTAMPTZ,
    reponse            VARCHAR(4000),
    repondue_le        TIMESTAMPTZ,
    suite              VARCHAR(1000),
    close_par          UUID          REFERENCES utilisateur (id),
    close_le           TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, numero),
    CHECK ((type = 'DEMANDE_EXPLICATION') = (numero IS NOT NULL)),
    CHECK (type = 'RAPPEL' OR delai_reponse IS NOT NULL),
    CHECK ((reponse IS NULL) = (repondue_le IS NULL)),
    CHECK ((statut = 'CLOSE') = (close_le IS NOT NULL)),
    FOREIGN KEY (tenant_id, classe_id) REFERENCES classe (tenant_id, id),
    FOREIGN KEY (tenant_id, matiere_id) REFERENCES matiere (tenant_id, id),
    FOREIGN KEY (tenant_id, engagement_id) REFERENCES engagement_enseignant (tenant_id, id)
);
SELECT activer_isolation('interpellation');
CREATE INDEX interpellation_classe_matiere ON interpellation (tenant_id, classe_id, matiere_id);
CREATE INDEX interpellation_destinataire ON interpellation (tenant_id, destinataire_id);

CREATE TABLE armoiries_pays (
    pays_id      UUID        PRIMARY KEY REFERENCES pays (id),
    type_contenu VARCHAR(20) NOT NULL CHECK (type_contenu IN ('image/png', 'image/jpeg')),
    contenu      BYTEA       NOT NULL,
    modifie_le   TIMESTAMPTZ NOT NULL DEFAULT now(),
    modifie_par  UUID        REFERENCES utilisateur (id)
);
