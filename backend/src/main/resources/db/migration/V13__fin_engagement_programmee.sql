-- =====================================================================
-- V13 : fin d'engagement programmée (mutation, départ, fin de contrat)
--
-- Un engagement peut être terminé à une date future : il reste ACTIF jusqu'à
-- cette date (l'enseignant continue d'enseigner et d'envoyer appels et notes),
-- puis un traitement quotidien le passe en TERMINE le lendemain.
--
-- Comme la période daterange(debut, fin) est raccourcie dès la programmation,
-- le nouvel établissement peut engager l'enseignant comme titulaire à partir
-- du lendemain de la date de fin, sans attendre (contraintes d'exclusion V5).
--
--   fin_programmee : la date de fin a été fixée par une fin d'engagement
--   fin_contrat    : date de fin prévue avant la programmation (vacataires),
--                    restaurée si la fin programmée est annulée
-- =====================================================================

ALTER TABLE engagement_enseignant
    ADD COLUMN fin_programmee BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN fin_contrat    DATE,
    ADD CONSTRAINT fin_programmee_datee CHECK (NOT fin_programmee OR fin IS NOT NULL);

-- Recherche quotidienne des engagements arrivés à échéance
CREATE INDEX ix_engagement_echeance ON engagement_enseignant (tenant_id, fin) WHERE statut = 'ACTIF';
