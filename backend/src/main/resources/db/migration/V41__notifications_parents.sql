-- =====================================================================
-- V41 : notifications aux parents dans l'application (v0.50)
--
--   responsable (colonnes)  : canal de notification choisi par
--                             l'établissement (AUTO : l'application si le
--                             parent l'a activée sur son téléphone, sinon
--                             SMS ; SMS : toujours par SMS, parents sans
--                             smartphone) et préférences du parent
--   reglage_notifications   : délai de correction laissé à l'enseignant après
--                             l'appel, SMS pour les retards
--   abonnement_push         : appareils sur lesquels un parent a activé les
--                             notifications (Web Push, norme du W3C)
--   notification_parent     : centre de notifications de l'espace parent et
--                             file d'envoi des notifications « push », avec
--                             le SMS de secours si l'envoi n'aboutit pas
-- =====================================================================

ALTER TABLE responsable
    ADD COLUMN canal_notification      VARCHAR(10) NOT NULL DEFAULT 'AUTO'
                                       CHECK (canal_notification IN ('AUTO', 'SMS')),
    ADD COLUMN notifier_retards        BOOLEAN     NOT NULL DEFAULT true,
    ADD COLUMN notifications_discretes BOOLEAN     NOT NULL DEFAULT false;

CREATE TABLE reglage_notifications (
    tenant_id                UUID        NOT NULL REFERENCES tenant (id),
    delai_correction_minutes SMALLINT    NOT NULL DEFAULT 0 CHECK (delai_correction_minutes BETWEEN 0 AND 60),
    sms_retards              BOOLEAN     NOT NULL DEFAULT false,
    modifie_le               TIMESTAMPTZ NOT NULL DEFAULT now(),
    modifie_par              UUID        REFERENCES utilisateur (id),
    PRIMARY KEY (tenant_id)
);
SELECT activer_isolation('reglage_notifications');

CREATE TABLE abonnement_push (
    tenant_id      UUID          NOT NULL REFERENCES tenant (id),
    id             UUID          NOT NULL,
    utilisateur_id UUID          NOT NULL REFERENCES utilisateur (id),
    endpoint       VARCHAR(1000) NOT NULL,          -- adresse du service push du navigateur
    cle_p256dh     VARCHAR(120)  NOT NULL,          -- clé publique de l'appareil (base64url)
    cle_auth       VARCHAR(40)   NOT NULL,          -- secret d'authentification (base64url)
    appareil       VARCHAR(120),
    actif          BOOLEAN       NOT NULL DEFAULT true,
    echecs         INTEGER       NOT NULL DEFAULT 0,
    cree_le        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    dernier_envoi  TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, endpoint)
);
SELECT activer_isolation('abonnement_push');
CREATE INDEX ix_abonnement_push_utilisateur ON abonnement_push (tenant_id, utilisateur_id) WHERE actif;

CREATE TABLE notification_parent (
    tenant_id          UUID         NOT NULL REFERENCES tenant (id),
    id                 UUID         NOT NULL,
    utilisateur_id     UUID         NOT NULL REFERENCES utilisateur (id),   -- compte du parent
    eleve_id           UUID,
    cle                VARCHAR(120) NOT NULL,          -- ex. absence:<id>, retard:<id>
    type               VARCHAR(12)  NOT NULL CHECK (type IN ('ABSENCE', 'RETARD', 'ESSAI')),
    titre              VARCHAR(120) NOT NULL,
    message            VARCHAR(480) NOT NULL,
    message_discret    VARCHAR(160) NOT NULL,          -- affiché sur l'écran verrouillé si le parent le préfère
    lien               VARCHAR(200),
    cree_le            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    visible_le         TIMESTAMPTZ  NOT NULL,          -- après le délai de correction
    lue_le             TIMESTAMPTZ,
    corrigee_le        TIMESTAMPTZ,                    -- l'établissement est revenu sur l'information
    push_statut        VARCHAR(16)  NOT NULL
                       CHECK (push_statut IN ('A_ENVOYER', 'EN_COURS', 'ENVOYE', 'SANS_ABONNEMENT', 'ECHEC',
                                              'ANNULE', 'NON_DEMANDE')),
    push_tentatives    INTEGER      NOT NULL DEFAULT 0,
    push_verrou        TIMESTAMPTZ,
    push_envoye_le     TIMESTAMPTZ,
    push_erreur        VARCHAR(300),
    -- SMS de secours (contact prioritaire seulement) : planifié si aucun appareil ne reçoit la notification
    sms_cle            VARCHAR(120),
    sms_destinataire   VARCHAR(20),
    sms_langue         VARCHAR(10),
    sms_message        VARCHAR(480),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, utilisateur_id, cle)
);
SELECT activer_isolation('notification_parent');
CREATE INDEX ix_notification_parent_centre ON notification_parent (tenant_id, utilisateur_id, visible_le DESC);
CREATE INDEX ix_notification_parent_a_envoyer ON notification_parent (visible_le)
    WHERE push_statut IN ('A_ENVOYER', 'EN_COURS');

-- Le worker d'envoi parcourt toutes les écoles : cette fonction lui donne les
-- notifications dues (établissement et identifiant), il les traite ensuite dans
-- le cloisonnement de chaque établissement.
CREATE FUNCTION notifications_parents_dues(p_limite INTEGER)
    RETURNS TABLE (tenant_id UUID, id UUID)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT n.tenant_id, n.id FROM notification_parent n
     WHERE (n.push_statut = 'A_ENVOYER' AND n.visible_le <= now())
        OR (n.push_statut = 'EN_COURS' AND n.push_verrou < now())
     ORDER BY n.visible_le
     LIMIT p_limite
$$;
REVOKE ALL ON FUNCTION notifications_parents_dues(INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notifications_parents_dues(INTEGER) TO ${app_role};
