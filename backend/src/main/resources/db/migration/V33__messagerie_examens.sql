-- =====================================================================
-- V33 : messagerie de l'établissement et historique des examens
-- nationaux (v0.46).
-- =====================================================================

-- ---------------------------------------------------------------------
-- Message de l'administration à des destinataires : le personnel
-- (enseignants, administration), les parents, une personne ou un groupe.
-- Le message est lu dans l'application ; il peut aussi partir par SMS
-- (file « notification »). Le résumé des cibles est gardé pour l'auteur.
-- ---------------------------------------------------------------------
CREATE TABLE message (
    tenant_id    UUID          NOT NULL REFERENCES tenant (id),
    id           UUID          NOT NULL,
    auteur_id    UUID          NOT NULL REFERENCES utilisateur (id),
    objet        VARCHAR(150)  NOT NULL,
    corps        VARCHAR(2000) NOT NULL,
    sms          BOOLEAN       NOT NULL DEFAULT false,
    cibles       VARCHAR(300)  NOT NULL,
    envoye_le    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX ix_message_envoye ON message (tenant_id, envoye_le DESC);
SELECT activer_isolation('message');

-- ---------------------------------------------------------------------
-- Destinataire d'un message : un compte (lu dans l'application), ou un
-- parent sans compte, joint seulement par SMS (utilisateur_id vide).
-- ---------------------------------------------------------------------
CREATE TABLE message_destinataire (
    tenant_id      UUID         NOT NULL,
    id             UUID         NOT NULL,
    message_id     UUID         NOT NULL,
    utilisateur_id UUID         REFERENCES utilisateur (id),
    responsable_id UUID,
    nom            VARCHAR(220) NOT NULL,
    qualite        VARCHAR(120),
    telephone      VARCHAR(20),
    sms            BOOLEAN      NOT NULL DEFAULT false,
    lu_le          TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, message_id) REFERENCES message (tenant_id, id) ON DELETE CASCADE,
    CHECK (utilisateur_id IS NOT NULL OR (sms AND telephone IS NOT NULL))
);
CREATE UNIQUE INDEX ux_message_destinataire_compte ON message_destinataire (tenant_id, message_id, utilisateur_id)
    WHERE utilisateur_id IS NOT NULL;
CREATE INDEX ix_message_boite ON message_destinataire (tenant_id, utilisateur_id, lu_le)
    WHERE utilisateur_id IS NOT NULL;
CREATE INDEX ix_message_destinataires ON message_destinataire (tenant_id, message_id);
SELECT activer_isolation('message_destinataire');

-- ---------------------------------------------------------------------
-- Résultats d'un examen national saisis à la main pour une année où la
-- plateforme n'était pas utilisée (comparaison sur plusieurs années).
-- Les années suivies dans la plateforme se calculent à partir des
-- résultats saisis élève par élève au conseil de fin d'année.
-- ---------------------------------------------------------------------
CREATE TABLE examen_historique (
    tenant_id        UUID        NOT NULL REFERENCES tenant (id),
    id               UUID        NOT NULL,
    annee            VARCHAR(9)  NOT NULL CHECK (annee ~ '^[0-9]{4}-[0-9]{4}$'),
    examen           VARCHAR(40) NOT NULL,
    candidats        INTEGER     NOT NULL CHECK (candidats >= 0),
    admis            INTEGER     NOT NULL CHECK (admis >= 0),
    candidates_filles INTEGER    CHECK (candidates_filles >= 0),
    admises_filles   INTEGER     CHECK (admises_filles >= 0),
    modifie_le       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CHECK (admis <= candidats),
    CHECK (candidates_filles IS NULL OR candidates_filles <= candidats),
    CHECK (admises_filles IS NULL OR (candidates_filles IS NOT NULL AND admises_filles <= candidates_filles
                                      AND admises_filles <= admis))
);
CREATE UNIQUE INDEX ux_examen_historique ON examen_historique (tenant_id, annee, upper(examen));
SELECT activer_isolation('examen_historique');
