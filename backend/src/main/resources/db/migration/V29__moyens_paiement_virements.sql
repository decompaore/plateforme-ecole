-- =====================================================================
-- V29 : moyens de paiement de l'établissement et virements déclarés
--
--   parametres_paiement : moyens acceptés au guichet ; moyens proposés aux parents
--                         (Mobile Money et ses opérateurs, virement bancaire)
--   compte_bancaire     : comptes de l'établissement affichés aux parents
--   virement_declare    : virement annoncé par un parent (référence, date, preuve),
--                         validé par l'intendance (paiement et reçu) ou refusé avec un motif
-- =====================================================================

CREATE TABLE parametres_paiement (
    tenant_id              UUID         PRIMARY KEY REFERENCES tenant (id),
    moyens_guichet         VARCHAR(13)[] NOT NULL
                           DEFAULT '{ESPECES,CHEQUE,VIREMENT,ORANGE_MONEY,MOOV_MONEY,TELECEL_MONEY}',
    mobile_money_parents   BOOLEAN      NOT NULL DEFAULT true,
    operateurs             VARCHAR(13)[] NOT NULL DEFAULT '{ORANGE_MONEY,MOOV_MONEY,TELECEL_MONEY}',
    virement_parents       BOOLEAN      NOT NULL DEFAULT false,
    consigne_virement      VARCHAR(300),
    modifie_par            UUID         REFERENCES utilisateur (id),
    modifie_le             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (moyens_guichet <@ ARRAY['ESPECES','CHEQUE','VIREMENT','ORANGE_MONEY','MOOV_MONEY','TELECEL_MONEY']::varchar[]),
    CHECK (cardinality(moyens_guichet) > 0),
    CHECK (operateurs <@ ARRAY['ORANGE_MONEY','MOOV_MONEY','TELECEL_MONEY']::varchar[])
);

CREATE TABLE compte_bancaire (
    tenant_id  UUID         NOT NULL REFERENCES tenant (id),
    id         UUID         NOT NULL,
    banque     VARCHAR(120) NOT NULL,
    titulaire  VARCHAR(150) NOT NULL,
    numero     VARCHAR(60)  NOT NULL,                 -- RIB ou IBAN, tel qu'il figure sur le relevé d'identité
    code_swift VARCHAR(11),
    agence     VARCHAR(120),
    actif      BOOLEAN      NOT NULL DEFAULT true,
    cree_le    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_par UUID        REFERENCES utilisateur (id),
    modifie_le TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, numero)
);

CREATE TABLE virement_declare (
    tenant_id       UUID         NOT NULL,
    id              UUID         NOT NULL,
    inscription_id  UUID         NOT NULL,
    compte_id       UUID         NOT NULL,
    montant         BIGINT       NOT NULL CHECK (montant > 0),
    date_virement   DATE         NOT NULL,
    reference       VARCHAR(60)  NOT NULL,
    donneur_ordre   VARCHAR(120),
    preuve          BYTEA,
    preuve_type     VARCHAR(20)  CHECK (preuve_type IN ('image/png', 'image/jpeg', 'application/pdf')),
    statut          VARCHAR(10)  NOT NULL DEFAULT 'EN_ATTENTE' CHECK (statut IN ('EN_ATTENTE', 'VALIDE', 'REFUSE')),
    montant_valide  BIGINT       CHECK (montant_valide > 0),
    motif_refus     VARCHAR(300),
    paiement_id     UUID,
    cle_idempotence VARCHAR(64)  NOT NULL,
    declare_par     UUID         REFERENCES utilisateur (id),
    declare_le      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    traite_par      UUID         REFERENCES utilisateur (id),
    traite_le       TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, cle_idempotence),
    CHECK ((preuve IS NULL) = (preuve_type IS NULL)),
    CHECK ((statut = 'VALIDE') = (paiement_id IS NOT NULL)),
    CHECK ((statut = 'VALIDE') = (montant_valide IS NOT NULL)),
    CHECK ((statut = 'REFUSE') = (motif_refus IS NOT NULL)),
    CHECK ((statut = 'EN_ATTENTE') = (traite_le IS NULL)),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id),
    FOREIGN KEY (tenant_id, compte_id)      REFERENCES compte_bancaire (tenant_id, id),
    FOREIGN KEY (tenant_id, paiement_id)    REFERENCES paiement (tenant_id, id)
);
-- Un même virement (compte, référence) n'est déclaré qu'une fois, sauf s'il a été refusé
CREATE UNIQUE INDEX ux_virement_reference ON virement_declare (tenant_id, compte_id, upper(reference))
    WHERE statut <> 'REFUSE';
CREATE INDEX ix_virement_statut ON virement_declare (tenant_id, statut, declare_le);
CREATE INDEX ix_virement_inscription ON virement_declare (tenant_id, inscription_id);

SELECT activer_isolation('parametres_paiement');
SELECT activer_isolation('compte_bancaire');
SELECT activer_isolation('virement_declare');
