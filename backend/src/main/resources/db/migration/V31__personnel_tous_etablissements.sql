-- =====================================================================
-- V31 : un compte a-t-il un rôle de personnel dans un établissement, quel
-- que soit l'état de l'établissement (suspendu) ou de l'appartenance
-- (désactivée) ? Sert à refuser qu'un membre du personnel devienne
-- administrateur pays ou compte de direction (v0.41) ; un parent d'élève,
-- lui, peut l'être. Ne renvoie qu'un booléen.
-- =====================================================================

CREATE FUNCTION compte_personnel_etablissement(p_utilisateur UUID)
    RETURNS BOOLEAN
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT EXISTS (SELECT 1 FROM membre_etablissement m WHERE m.utilisateur_id = p_utilisateur AND m.role <> 'PARENT')
$$;
REVOKE ALL ON FUNCTION compte_personnel_etablissement(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION compte_personnel_etablissement(UUID) TO ${app_role};
