-- =====================================================================
-- V10 : paiement Mobile Money, rapprochement quotidien, relances automatiques
--   configuration_mobile_money : compte marchand de l'école chez l'agrégateur
--                                (clés chiffrées par l'application, jamais en clair)
--   transaction_mobile_money   : cycle de vie d'un paiement initié par un parent
--   rapprochement              : comparaison quotidienne avec le relevé de l'agrégateur
--   ecart_rapprochement        : écarts à examiner par l'intendance
-- =====================================================================

ALTER TABLE parametres_scolarite
    ADD COLUMN relances_automatiques BOOLEAN NOT NULL DEFAULT true;

CREATE TABLE configuration_mobile_money (
    tenant_id              UUID         PRIMARY KEY REFERENCES tenant (id),
    agregateur             VARCHAR(20)  NOT NULL,
    identifiant_marchand   VARCHAR(80)  NOT NULL,
    cle_api_chiffree       TEXT         NOT NULL,
    secret_webhook_chiffre TEXT         NOT NULL,
    actif                  BOOLEAN      NOT NULL DEFAULT true,
    modifie_par            UUID         REFERENCES utilisateur (id),
    modifie_le             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE transaction_mobile_money (
    tenant_id            UUID         NOT NULL,
    id                   UUID         NOT NULL,
    inscription_id       UUID         NOT NULL,
    montant              BIGINT       NOT NULL CHECK (montant > 0),
    operateur            VARCHAR(12)  NOT NULL CHECK (operateur IN ('ORANGE_MONEY', 'MOOV_MONEY')),
    telephone            VARCHAR(20)  NOT NULL,
    statut               VARCHAR(12)  NOT NULL DEFAULT 'INITIEE'
                         CHECK (statut IN ('INITIEE', 'EN_ATTENTE', 'CONFIRMEE', 'ECHOUEE', 'EXPIREE',
                                           'A_VERIFIER', 'REGULARISEE')),
    reference            VARCHAR(40)  NOT NULL,           -- notre référence, transmise à l'agrégateur
    reference_agregateur VARCHAR(100),
    cle_idempotence      VARCHAR(64)  NOT NULL,
    montant_recu         BIGINT,
    paiement_id          UUID,
    message              VARCHAR(300),
    initiee_par          UUID         REFERENCES utilisateur (id),
    cree_le              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expire_le            TIMESTAMPTZ  NOT NULL,
    termine_le           TIMESTAMPTZ,
    derniere_tentative   TIMESTAMPTZ,                     -- vérification échouée (agrégateur injoignable)
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, reference),
    UNIQUE (tenant_id, cle_idempotence),
    UNIQUE (tenant_id, paiement_id),
    CHECK ((statut = 'CONFIRMEE') = (paiement_id IS NOT NULL)),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id),
    FOREIGN KEY (tenant_id, paiement_id)    REFERENCES paiement (tenant_id, id)
);
CREATE INDEX ix_transaction_mm_en_cours ON transaction_mobile_money (expire_le)
    WHERE statut IN ('INITIEE', 'EN_ATTENTE');
CREATE INDEX ix_transaction_mm_inscription ON transaction_mobile_money (tenant_id, inscription_id);

CREATE TABLE rapprochement (
    tenant_id   UUID         NOT NULL REFERENCES tenant (id),
    id          UUID         NOT NULL,
    date_releve DATE         NOT NULL,
    lignes      INTEGER      NOT NULL DEFAULT 0,
    ecarts      INTEGER      NOT NULL DEFAULT 0,
    statut      VARCHAR(8)   NOT NULL CHECK (statut IN ('OK', 'ECARTS', 'ECHEC')),
    message     VARCHAR(300),
    execute_le  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, date_releve)
);

CREATE TABLE ecart_rapprochement (
    tenant_id        UUID         NOT NULL,
    id               UUID         NOT NULL,
    rapprochement_id UUID         NOT NULL,
    type             VARCHAR(20)  NOT NULL
                     CHECK (type IN ('NON_ENREGISTRE', 'ABSENT_DU_RELEVE', 'MONTANT_DIFFERENT', 'DOUBLON')),
    reference        VARCHAR(100) NOT NULL,
    montant_attendu  BIGINT,
    montant_recu     BIGINT,
    transaction_id   UUID,
    traite           BOOLEAN      NOT NULL DEFAULT false,
    traite_par       UUID         REFERENCES utilisateur (id),
    traite_le        TIMESTAMPTZ,
    commentaire      VARCHAR(300),
    PRIMARY KEY (tenant_id, id),
    CHECK (traite = (traite_le IS NOT NULL)),
    FOREIGN KEY (tenant_id, rapprochement_id) REFERENCES rapprochement (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, transaction_id)   REFERENCES transaction_mobile_money (tenant_id, id)
);

SELECT activer_isolation('configuration_mobile_money');
SELECT activer_isolation('transaction_mobile_money');
SELECT activer_isolation('rapprochement');
SELECT activer_isolation('ecart_rapprochement');

-- ---------------------------------------------------------------------
-- Tâches planifiées : leurs seuls accès hors cloisonnement (identifiants
-- d'écoles et de transactions uniquement, jamais de données nominatives)
-- ---------------------------------------------------------------------

-- Écoles actives (relances automatiques)
CREATE FUNCTION ecoles_actives()
    RETURNS TABLE (tenant_id UUID)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT id FROM tenant WHERE statut = 'ACTIF' ORDER BY id
$$;

-- Écoles dont le paiement Mobile Money est actif (rapprochement quotidien)
CREATE FUNCTION ecoles_mobile_money()
    RETURNS TABLE (tenant_id UUID)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT c.tenant_id FROM configuration_mobile_money c JOIN tenant t ON t.id = c.tenant_id
    WHERE c.actif AND t.statut = 'ACTIF' ORDER BY c.tenant_id
$$;

-- Transactions en cours dont le délai de confirmation est dépassé. Une transaction dont la
-- vérification vient d'échouer n'est reprise qu'après 5 minutes, et passe après les autres :
-- quelques transactions bloquées n'empêchent jamais de traiter celles des autres écoles.
CREATE FUNCTION transactions_mobile_money_echues(p_avant TIMESTAMPTZ, p_limite INTEGER)
    RETURNS TABLE (tenant_id UUID, id UUID)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT tenant_id, id FROM transaction_mobile_money
    WHERE statut IN ('INITIEE', 'EN_ATTENTE') AND expire_le < p_avant
      AND (derniere_tentative IS NULL OR derniere_tentative < p_avant - interval '5 minutes')
    ORDER BY derniere_tentative NULLS FIRST, expire_le
    LIMIT p_limite
$$;

REVOKE ALL ON FUNCTION ecoles_actives() FROM PUBLIC;
REVOKE ALL ON FUNCTION ecoles_mobile_money() FROM PUBLIC;
REVOKE ALL ON FUNCTION transactions_mobile_money_echues(TIMESTAMPTZ, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ecoles_actives() TO ${app_role};
GRANT EXECUTE ON FUNCTION ecoles_mobile_money() TO ${app_role};
GRANT EXECUTE ON FUNCTION transactions_mobile_money_echues(TIMESTAMPTZ, INTEGER) TO ${app_role};
