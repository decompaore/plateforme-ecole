-- =====================================================================
-- V6 : notifications (outbox SMS) et absences
--   notification : messages à envoyer, écrits dans la même transaction que
--                  l'événement (outbox), envoyés par le worker avec reprises
--   appel        : séance d'appel ; identifiant généré par l'appareil (idempotence)
--   absence      : élève absent ou en retard lors d'un appel
--   justificatif : période d'absence justifiée d'un élève
-- =====================================================================

-- ---------------------------------------------------------------------
-- Notifications (outbox)
-- ---------------------------------------------------------------------
CREATE TABLE notification (
    tenant_id             UUID         NOT NULL REFERENCES tenant (id),
    id                    UUID         NOT NULL,
    cle                   VARCHAR(120) NOT NULL,           -- déduplication (ex. absence:<id>)
    canal                 VARCHAR(10)  NOT NULL DEFAULT 'SMS' CHECK (canal IN ('SMS')),
    destinataire          VARCHAR(20)  NOT NULL,
    langue                VARCHAR(10)  NOT NULL DEFAULT 'FR',
    message               VARCHAR(480) NOT NULL,           -- 3 SMS au plus
    statut                VARCHAR(10)  NOT NULL DEFAULT 'EN_ATTENTE'
                          CHECK (statut IN ('EN_ATTENTE', 'EN_COURS', 'ENVOYE', 'ECHEC', 'ANNULE')),
    tentatives            INTEGER      NOT NULL DEFAULT 0,
    prochaine_tentative   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    verrou_jusqua         TIMESTAMPTZ,
    derniere_erreur       VARCHAR(300),
    reference_fournisseur VARCHAR(100),
    cree_le               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    envoye_le             TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, cle)
);
CREATE INDEX ix_notification_a_envoyer ON notification (prochaine_tentative)
    WHERE statut IN ('EN_ATTENTE', 'EN_COURS');

SELECT activer_isolation('notification');

-- Le worker traite toutes les écoles : ces deux fonctions sont ses seuls accès
-- hors cloisonnement. Réservation avec bail : deux instances de l'API ne
-- prennent jamais le même message ; un message réservé par une instance
-- arrêtée redevient disponible à l'expiration du bail.
CREATE FUNCTION reserver_notifications(p_limite INTEGER, p_bail_secondes INTEGER)
    RETURNS TABLE (id UUID, tenant_id UUID, canal VARCHAR, destinataire VARCHAR, message VARCHAR,
                   tentatives INTEGER)
    LANGUAGE sql VOLATILE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    UPDATE notification n
       SET statut = 'EN_COURS',
           verrou_jusqua = now() + make_interval(secs => p_bail_secondes),
           tentatives = n.tentatives + 1
     WHERE (n.tenant_id, n.id) IN (
            SELECT a.tenant_id, a.id FROM notification a
             WHERE (a.statut = 'EN_ATTENTE' AND a.prochaine_tentative <= now())
                OR (a.statut = 'EN_COURS' AND a.verrou_jusqua < now())
             ORDER BY a.prochaine_tentative
             LIMIT p_limite
             FOR UPDATE SKIP LOCKED)
    RETURNING n.id, n.tenant_id, n.canal, n.destinataire, n.message, n.tentatives
$$;

-- Résultat d'un envoi : envoyé, ou nouvelle tentative plus tard (2, 4, 8… minutes),
-- ou échec définitif après p_max_tentatives.
CREATE FUNCTION terminer_notification(p_tenant UUID, p_id UUID, p_succes BOOLEAN, p_reference VARCHAR,
                                      p_erreur VARCHAR, p_max_tentatives INTEGER)
    RETURNS VOID
    LANGUAGE sql VOLATILE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    UPDATE notification
       SET statut = CASE WHEN p_succes THEN 'ENVOYE'
                         WHEN tentatives >= p_max_tentatives THEN 'ECHEC'
                         ELSE 'EN_ATTENTE' END,
           envoye_le = CASE WHEN p_succes THEN now() END,
           reference_fournisseur = p_reference,
           derniere_erreur = CASE WHEN p_succes THEN NULL ELSE left(p_erreur, 300) END,
           prochaine_tentative = CASE WHEN p_succes THEN prochaine_tentative
                                      ELSE now() + make_interval(mins => power(2, least(tentatives, 6))::int) END,
           verrou_jusqua = NULL
     WHERE tenant_id = p_tenant AND id = p_id AND statut = 'EN_COURS'
$$;

-- Rend un message réservé sans compter de tentative (coupe-circuit ouvert pendant un lot)
CREATE FUNCTION liberer_notification(p_tenant UUID, p_id UUID)
    RETURNS VOID
    LANGUAGE sql VOLATILE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    UPDATE notification
       SET statut = 'EN_ATTENTE', tentatives = greatest(tentatives - 1, 0), verrou_jusqua = NULL
     WHERE tenant_id = p_tenant AND id = p_id AND statut = 'EN_COURS'
$$;

REVOKE ALL ON FUNCTION reserver_notifications(INTEGER, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION terminer_notification(UUID, UUID, BOOLEAN, VARCHAR, VARCHAR, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION reserver_notifications(INTEGER, INTEGER) TO ${app_role};
GRANT EXECUTE ON FUNCTION terminer_notification(UUID, UUID, BOOLEAN, VARCHAR, VARCHAR, INTEGER) TO ${app_role};
REVOKE ALL ON FUNCTION liberer_notification(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION liberer_notification(UUID, UUID) TO ${app_role};

-- ---------------------------------------------------------------------
-- Appel : l'identifiant est généré sur l'appareil ; renvoyer le même appel
-- (réseau instable) ne crée jamais de doublon
-- ---------------------------------------------------------------------
CREATE TABLE appel (
    tenant_id   UUID        NOT NULL REFERENCES tenant (id),
    id          UUID        NOT NULL,
    classe_id   UUID        NOT NULL,
    matiere_id  UUID,
    date_appel  DATE        NOT NULL,
    heure_debut TIME        NOT NULL,
    heure_fin   TIME        NOT NULL,
    fait_par    UUID        NOT NULL REFERENCES utilisateur (id),
    saisi_le    TIMESTAMPTZ NOT NULL,                 -- heure de l'appareil
    recu_le     TIMESTAMPTZ NOT NULL DEFAULT now(),
    modifie_par UUID        REFERENCES utilisateur (id),
    modifie_le  TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, classe_id, date_appel, heure_debut),   -- un seul appel par créneau
    CHECK (heure_fin > heure_debut),
    FOREIGN KEY (tenant_id, classe_id)  REFERENCES classe (tenant_id, id),
    FOREIGN KEY (tenant_id, matiere_id) REFERENCES matiere (tenant_id, id)
);
CREATE INDEX ix_appel_classe_date ON appel (tenant_id, classe_id, date_appel);

CREATE TABLE absence (
    tenant_id      UUID        NOT NULL,
    id             UUID        NOT NULL,
    appel_id       UUID        NOT NULL,
    inscription_id UUID        NOT NULL,
    type           VARCHAR(8)  NOT NULL CHECK (type IN ('ABSENCE', 'RETARD')),
    minutes_retard SMALLINT    CHECK (minutes_retard > 0),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, appel_id, inscription_id),
    CHECK (type = 'RETARD' OR minutes_retard IS NULL),
    FOREIGN KEY (tenant_id, appel_id)       REFERENCES appel (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id)
);
CREATE INDEX ix_absence_inscription ON absence (tenant_id, inscription_id);

CREATE TABLE justificatif (
    tenant_id      UUID         NOT NULL,
    id             UUID         NOT NULL,
    inscription_id UUID         NOT NULL,
    du             DATE         NOT NULL,
    au             DATE         NOT NULL,
    type           VARCHAR(12)  NOT NULL CHECK (type IN ('MALADIE', 'FAMILLE', 'CONVOCATION', 'AUTRE')),
    motif          VARCHAR(200),
    saisi_par      UUID         REFERENCES utilisateur (id),
    cree_le        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CHECK (au >= du),
    CHECK (au - du <= 366),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id)
);
CREATE INDEX ix_justificatif_inscription ON justificatif (tenant_id, inscription_id);

SELECT activer_isolation('appel');
SELECT activer_isolation('absence');
SELECT activer_isolation('justificatif');
