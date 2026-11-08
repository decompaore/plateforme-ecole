-- =====================================================================
-- V42 : dossier administratif du personnel (v0.51)
--
--   agent             : dossier de chaque membre du personnel de l'établissement
--                       (enseignants, personnel administratif, agents de soutien
--                       qui n'ont pas de compte : gardien, planton, entretien…)
--   diplome_agent     : diplômes académiques et professionnels
--   piece_agent       : pièces classées au dossier (arrêtés, notes de service,
--                       diplômes et actes d'état civil scannés…)
--   acte_agent        : actes établis par l'établissement (certificats de prise
--                       et de cessation de service, attestations, autorisations
--                       d'absence), numérotés, signés par le chef d'établissement
--   absence_agent     : congés, autorisations d'absence, missions, absences
--   signalement_dossier : erreurs signalées par l'agent sur son dossier
-- =====================================================================

CREATE TABLE agent (
    tenant_id                   UUID          NOT NULL REFERENCES tenant (id),
    id                          UUID          NOT NULL,
    utilisateur_id              UUID          REFERENCES utilisateur (id),   -- compte de connexion, s'il en a un
    enseignant_id               UUID          REFERENCES enseignant (id),
    categorie                   VARCHAR(14)   NOT NULL CHECK (categorie IN ('ENSEIGNANT', 'ADMINISTRATIF', 'SOUTIEN')),
    nom                         VARCHAR(80)   NOT NULL,
    prenoms                     VARCHAR(120)  NOT NULL,
    sexe                        CHAR(1)       NOT NULL CHECK (sexe IN ('M', 'F')),
    date_naissance              DATE,
    lieu_naissance              VARCHAR(100),
    telephone                   VARCHAR(20),
    adresse                     VARCHAR(200),
    matricule                   VARCHAR(30),                                   -- matricule de la fonction publique
    situation                   VARCHAR(14)   CHECK (situation IN ('FONCTIONNAIRE', 'CONTRACTUEL', 'VACATAIRE', 'AUTRE')),
    emploi                      VARCHAR(120),                                  -- corps ou emploi
    grade                       VARCHAR(60),
    categorie_echelle           VARCHAR(20),                                   -- catégorie et échelle (A1, B2…)
    echelon                     VARCHAR(10),
    fonction                    VARCHAR(120),                                  -- poste occupé dans l'établissement
    date_premiere_prise_service DATE,                                          -- entrée dans la fonction
    date_entree_grade           DATE,
    date_prise_service          DATE,                                          -- dans l'établissement
    date_cessation              DATE,
    motif_cessation             VARCHAR(200),
    observations                VARCHAR(1000),
    cree_le                     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modifie_le                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modifie_par                 UUID          REFERENCES utilisateur (id),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, utilisateur_id),
    UNIQUE (tenant_id, matricule),
    CHECK (date_cessation IS NULL OR date_prise_service IS NULL OR date_cessation >= date_prise_service)
);
SELECT activer_isolation('agent');
CREATE INDEX ix_agent_nom ON agent (tenant_id, nom, prenoms);

CREATE TABLE piece_agent (
    tenant_id     UUID         NOT NULL REFERENCES tenant (id),
    id            UUID         NOT NULL,
    agent_id      UUID         NOT NULL,
    nature        VARCHAR(14)  NOT NULL CHECK (nature IN ('ARRETE', 'DECISION', 'NOTE_SERVICE', 'CERTIFICAT',
                                                          'DIPLOME', 'ETAT_CIVIL', 'CONTRAT', 'AUTRE')),
    reference     VARCHAR(80),
    date_piece    DATE,
    objet         VARCHAR(200) NOT NULL,
    fichier       BYTEA        NOT NULL,
    type_contenu  VARCHAR(20)  NOT NULL CHECK (type_contenu IN ('application/pdf', 'image/jpeg', 'image/png')),
    nom_fichier   VARCHAR(150) NOT NULL,
    taille        INTEGER      NOT NULL,
    depose_par    UUID         REFERENCES utilisateur (id),
    depose_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, agent_id) REFERENCES agent (tenant_id, id) ON DELETE CASCADE
);
SELECT activer_isolation('piece_agent');
CREATE INDEX ix_piece_agent ON piece_agent (tenant_id, agent_id);

CREATE TABLE diplome_agent (
    tenant_id   UUID         NOT NULL REFERENCES tenant (id),
    id          UUID         NOT NULL,
    agent_id    UUID         NOT NULL,
    nature      VARCHAR(14)  NOT NULL CHECK (nature IN ('ACADEMIQUE', 'PROFESSIONNEL')),
    intitule    VARCHAR(150) NOT NULL,
    specialite  VARCHAR(120),
    delivre_par VARCHAR(150),
    annee       SMALLINT     CHECK (annee BETWEEN 1950 AND 2100),
    piece_id    UUID,                                        -- copie scannée classée au dossier
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, agent_id) REFERENCES agent (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, piece_id) REFERENCES piece_agent (tenant_id, id) ON DELETE SET NULL (piece_id)
);
SELECT activer_isolation('diplome_agent');
CREATE INDEX ix_diplome_agent ON diplome_agent (tenant_id, agent_id);

CREATE TABLE absence_agent (
    tenant_id    UUID         NOT NULL REFERENCES tenant (id),
    id           UUID         NOT NULL,
    agent_id     UUID         NOT NULL,
    type         VARCHAR(20)  NOT NULL CHECK (type IN ('AUTORISATION_ABSENCE', 'PERMISSION', 'CONGE_ANNUEL',
                                                      'CONGE_MALADIE', 'CONGE_MATERNITE', 'MISSION', 'FORMATION',
                                                      'ABSENCE_IRREGULIERE')),
    debut        DATE         NOT NULL,
    fin          DATE         NOT NULL,
    motif        VARCHAR(400) NOT NULL,
    statut       VARCHAR(10)  NOT NULL CHECK (statut IN ('DEMANDEE', 'ACCORDEE', 'REFUSEE', 'ANNULEE', 'CONSTATEE')),
    demandee_par UUID         REFERENCES utilisateur (id),
    demandee_le  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decidee_par  UUID         REFERENCES utilisateur (id),
    decidee_le   TIMESTAMPTZ,
    reponse      VARCHAR(400),
    piece_id     UUID,                                       -- justificatif (certificat médical…)
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, agent_id) REFERENCES agent (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, piece_id) REFERENCES piece_agent (tenant_id, id) ON DELETE SET NULL (piece_id),
    CHECK (fin >= debut)
);
SELECT activer_isolation('absence_agent');
CREATE INDEX ix_absence_agent ON absence_agent (tenant_id, agent_id, debut DESC);
CREATE INDEX ix_absence_agent_periode ON absence_agent (tenant_id, debut, fin) WHERE statut IN ('ACCORDEE', 'CONSTATEE');

CREATE TABLE acte_agent (
    tenant_id          UUID          NOT NULL REFERENCES tenant (id),
    id                 UUID          NOT NULL,
    agent_id           UUID          NOT NULL,
    type               VARCHAR(20)   NOT NULL CHECK (type IN ('PRISE_SERVICE', 'CESSATION_SERVICE', 'PRESENCE_POSTE',
                                                            'ATTESTATION_TRAVAIL', 'AUTORISATION_ABSENCE')),
    numero             VARCHAR(40),                          -- attribué à la signature
    titre              VARCHAR(120)  NOT NULL,
    texte              TEXT          NOT NULL,               -- texte figé à l'établissement de l'acte
    date_effet         DATE,
    absence_id         UUID,
    etabli_par         UUID          REFERENCES utilisateur (id),
    etabli_le          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    signe_par          UUID          REFERENCES utilisateur (id),
    signe_le           TIMESTAMPTZ,
    signataire         VARCHAR(160),
    annule_le          TIMESTAMPTZ,
    annule_par         UUID          REFERENCES utilisateur (id),
    motif_annulation   VARCHAR(300),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, numero),
    FOREIGN KEY (tenant_id, agent_id) REFERENCES agent (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, absence_id) REFERENCES absence_agent (tenant_id, id) ON DELETE SET NULL (absence_id)
);
SELECT activer_isolation('acte_agent');
CREATE INDEX ix_acte_agent ON acte_agent (tenant_id, agent_id, etabli_le DESC);

CREATE TABLE signalement_dossier (
    tenant_id  UUID          NOT NULL REFERENCES tenant (id),
    id         UUID          NOT NULL,
    agent_id   UUID          NOT NULL,
    texte      VARCHAR(1000) NOT NULL,
    signale_par UUID         REFERENCES utilisateur (id),
    signale_le TIMESTAMPTZ   NOT NULL DEFAULT now(),
    traite_par UUID          REFERENCES utilisateur (id),
    traite_le  TIMESTAMPTZ,
    reponse    VARCHAR(600),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, agent_id) REFERENCES agent (tenant_id, id) ON DELETE CASCADE
);
SELECT activer_isolation('signalement_dossier');
CREATE INDEX ix_signalement_dossier ON signalement_dossier (tenant_id, agent_id);
