-- =====================================================================
-- V23 : sessions par appareil, déconnexion et effacement à distance
--
-- Une session = un appareil connecté (une famille de jetons de rafraîchissement).
-- L'utilisateur voit ses appareils et peut en déconnecter un ; l'administrateur de
-- l'établissement peut déconnecter tous les appareils d'un compte (téléphone perdu).
-- Avec « effacement », l'appareil efface ses données locales au prochain contact.
-- Table de niveau plateforme (comme les jetons) : pas de cloisonnement par établissement ;
-- l'application filtre par compte et, pour l'administrateur, par établissement.
-- =====================================================================

CREATE TABLE session_appareil (
    id                 UUID        PRIMARY KEY,
    utilisateur_id     UUID        NOT NULL REFERENCES utilisateur (id),
    famille            UUID        NOT NULL UNIQUE,
    tenant_id          UUID        REFERENCES tenant (id),
    appareil           VARCHAR(120) NOT NULL,
    ouverte_le         TIMESTAMPTZ NOT NULL DEFAULT now(),
    dernier_usage      TIMESTAMPTZ NOT NULL DEFAULT now(),
    fermee_le          TIMESTAMPTZ,
    motif_fermeture    VARCHAR(20) CHECK (motif_fermeture IN ('DECONNEXION', 'A_DISTANCE', 'EFFACEMENT',
                                         'REINITIALISATION', 'VOL')),
    effacement_demande BOOLEAN     NOT NULL DEFAULT false,
    fermee_par         UUID        REFERENCES utilisateur (id),
    CHECK ((fermee_le IS NULL) = (motif_fermeture IS NULL))
);
CREATE INDEX ix_session_appareil_utilisateur ON session_appareil (utilisateur_id) WHERE fermee_le IS NULL;

-- Sessions déjà ouvertes au déploiement : une par famille de jetons encore valide
INSERT INTO session_appareil (id, utilisateur_id, famille, tenant_id, appareil, ouverte_le, dernier_usage)
SELECT gen_random_uuid(), j.utilisateur_id, j.famille, j.tenant_id, 'Appareil non identifié', min(j.cree_le),
       max(j.cree_le)
FROM jeton_rafraichissement j
WHERE j.revoque_le IS NULL AND j.expire_le > now()
GROUP BY j.utilisateur_id, j.famille, j.tenant_id;
