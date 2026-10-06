-- =====================================================================
-- V22 : modules activables par établissement
--
-- Le socle (classes, élèves, enseignants, appel, notes, bulletins, comptes) est
-- toujours actif. Les autres modules sont actifs par défaut ; le super administrateur
-- peut en désactiver pour un établissement (un lycée général n'a pas d'ateliers…).
-- Une ligne = un module désactivé. Aucune donnée du module n'est effacée : le
-- réactiver la rend de nouveau accessible.
-- =====================================================================

CREATE TABLE module_desactive (
    tenant_id     UUID        NOT NULL REFERENCES tenant (id),
    module        VARCHAR(30) NOT NULL CHECK (module IN ('ATELIERS', 'EMPLOIS_DU_TEMPS', 'PROGRESSION',
                                  'VIE_SCOLAIRE', 'SCOLARITE', 'MOBILE_MONEY', 'ESPACE_PARENT')),
    desactive_le  TIMESTAMPTZ NOT NULL DEFAULT now(),
    desactive_par UUID        REFERENCES utilisateur (id),
    PRIMARY KEY (tenant_id, module)
);

SELECT activer_isolation('module_desactive');

-- Modules désactivés d'une liste d'établissements. SECURITY DEFINER : lue à la connexion
-- (avant le choix de l'établissement) et par le filtre des requêtes, hors transaction ;
-- ne renvoie que des codes de modules.
CREATE FUNCTION modules_desactives(p_tenants UUID[])
    RETURNS TABLE (tenant_id UUID, module VARCHAR)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT m.tenant_id, m.module FROM module_desactive m WHERE m.tenant_id = ANY (p_tenants)
$$;
REVOKE ALL ON FUNCTION modules_desactives(UUID[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION modules_desactives(UUID[]) TO ${app_role};
