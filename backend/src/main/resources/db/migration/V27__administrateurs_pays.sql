-- =====================================================================
-- V27 : administrateurs pays
--
-- Sous le super administrateur de la plateforme, un ou plusieurs administrateurs
-- par pays gèrent les ministères, les directions et les établissements de leur pays
-- (création, rattachement, suspension, modules, dépannage, exports, adoption).
-- Le super administrateur crée les pays, nomme leurs administrateurs et garde la
-- vue globale ; lui seul résilie un établissement.
-- Un compte administre au plus un pays ; c'est un compte dédié (ni super
-- administrateur, ni membre d'un établissement).
-- =====================================================================

CREATE TABLE administrateur_pays (
    utilisateur_id UUID        PRIMARY KEY REFERENCES utilisateur (id),
    pays_id        UUID        NOT NULL REFERENCES pays (id),
    actif          BOOLEAN     NOT NULL DEFAULT true,
    nomme_le       TIMESTAMPTZ NOT NULL DEFAULT now(),
    nomme_par      UUID        REFERENCES utilisateur (id),
    retire_le      TIMESTAMPTZ
);
CREATE INDEX administrateur_pays_pays ON administrateur_pays (pays_id);
