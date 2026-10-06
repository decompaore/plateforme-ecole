-- =====================================================================
-- V20 : renouvellement périodique du mot de passe du personnel
--
-- Chaque compte du personnel (tous les rôles sauf PARENT et ELEVE) choisit un nouveau
-- mot de passe au début de chaque période (trimestre ou semestre) de l'année active de
-- l'établissement. La date du dernier changement est conservée sur le compte.
-- =====================================================================

ALTER TABLE utilisateur ADD COLUMN mot_de_passe_change_le TIMESTAMPTZ;
-- Raison pour laquelle un nouveau mot de passe est demandé : provisoire (création ou
-- réinitialisation par l'administration) ou renouvellement de période
ALTER TABLE utilisateur ADD COLUMN motif_changement VARCHAR(14)
    CHECK (motif_changement IN ('PROVISOIRE', 'RENOUVELLEMENT'));

-- Comptes existants : le mot de passe définitif compte comme changé à la mise à jour,
-- pour ne pas bloquer tout le personnel au déploiement en cours de période
UPDATE utilisateur SET mot_de_passe_change_le = now() WHERE NOT doit_changer_mot_de_passe;
UPDATE utilisateur SET motif_changement = 'PROVISOIRE' WHERE doit_changer_mot_de_passe;

-- ---------------------------------------------------------------------
-- Début de la période en cours de l'année active d'un établissement (la plus récente
-- commencée, tous profils pédagogiques confondus : dans un établissement mixte,
-- trimestres et semestres, c'est la période la plus récente qui compte). NULL si
-- aucune année active ou aucune période commencée. SECURITY DEFINER : appelée à la
-- connexion, avant que la session de l'établissement soit ouverte.
-- ---------------------------------------------------------------------
CREATE FUNCTION debut_periode_en_cours(p_tenant UUID, p_jour DATE)
    RETURNS DATE
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT max(p.debut)
    FROM periode p
    JOIN annee_scolaire a ON a.tenant_id = p.tenant_id AND a.id = p.annee_id
    WHERE p.tenant_id = p_tenant
      AND a.etat = 'ACTIVE'
      AND p.debut <= p_jour
$$;
REVOKE ALL ON FUNCTION debut_periode_en_cours(UUID, DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION debut_periode_en_cours(UUID, DATE) TO ${app_role};
