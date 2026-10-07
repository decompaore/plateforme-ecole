-- =====================================================================
-- V25 : mesure de l'adoption
--
-- Deux sources :
--  * activite_jour : chaque jour où une personne a utilisé l'application dans un
--    établissement (connexion ou session renouvelée, donc aussi l'enseignant qui
--    synchronise ses appels faits sans réseau) ;
--  * mesure_adoption : chaque nuit, pour chaque établissement, le nombre d'actions
--    de la veille par fonction (appels, notes, cahier de textes, paiements, SMS…).
--    Uniquement des nombres : aucune donnée personnelle.
-- Le super administrateur lit les agrégats de tous les établissements par des
-- fonctions SECURITY DEFINER qui ne renvoient que des nombres.
-- =====================================================================

CREATE TABLE activite_jour (
    tenant_id      UUID NOT NULL REFERENCES tenant (id),
    utilisateur_id UUID NOT NULL REFERENCES utilisateur (id),
    jour           DATE NOT NULL,
    PRIMARY KEY (tenant_id, utilisateur_id, jour)
);
CREATE INDEX activite_jour_par_jour ON activite_jour (tenant_id, jour);
SELECT activer_isolation('activite_jour');

CREATE TABLE mesure_adoption (
    tenant_id  UUID        NOT NULL REFERENCES tenant (id),
    jour       DATE        NOT NULL,
    indicateur VARCHAR(30) NOT NULL,
    valeur     INTEGER     NOT NULL CHECK (valeur >= 0),
    PRIMARY KEY (tenant_id, jour, indicateur)
);
SELECT activer_isolation('mesure_adoption');

-- Jours déjà calculés (niveau plateforme)
CREATE TABLE calcul_adoption (
    jour       DATE        PRIMARY KEY,
    calcule_le TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Activité d'une personne : appelée à l'ouverture et au renouvellement d'une
-- session (avant que l'établissement soit fixé dans la transaction).
-- ---------------------------------------------------------------------
CREATE FUNCTION noter_activite(p_tenant UUID, p_utilisateur UUID, p_jour DATE)
    RETURNS void
    LANGUAGE sql VOLATILE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    INSERT INTO activite_jour (tenant_id, utilisateur_id, jour)
    VALUES (p_tenant, p_utilisateur, p_jour)
    ON CONFLICT DO NOTHING
$$;
REVOKE ALL ON FUNCTION noter_activite(UUID, UUID, DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION noter_activite(UUID, UUID, DATE) TO ${app_role};

-- ---------------------------------------------------------------------
-- Calcul des mesures d'un jour pour tous les établissements (idempotent ;
-- verrou pour que deux instances ne calculent pas le même jour en même temps).
-- Les jours sont ceux de Ouagadougou (UTC).
-- ---------------------------------------------------------------------
CREATE FUNCTION calculer_adoption(p_jour DATE)
    RETURNS integer
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    n integer;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('calculer_adoption'));
    DELETE FROM mesure_adoption WHERE jour = p_jour;
    INSERT INTO mesure_adoption (tenant_id, jour, indicateur, valeur)
    SELECT tenant_id, p_jour, indicateur, valeur FROM (
        SELECT tenant_id, 'ACTIFS' AS indicateur, count(DISTINCT utilisateur_id)::int AS valeur
          FROM activite_jour WHERE jour = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'APPELS', count(*)::int FROM appel
         WHERE (saisi_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'EVALUATIONS', count(*)::int FROM evaluation
         WHERE (cree_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'NOTES', count(*)::int FROM note
         WHERE (saisi_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'CAHIER', count(*)::int FROM seance_cahier
         WHERE (saisi_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'JUSTIFICATIFS', count(*)::int FROM justificatif
         WHERE (cree_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'INCIDENTS', count(*)::int FROM incident
         WHERE (saisi_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'PAIEMENTS', count(*)::int FROM paiement
         WHERE (enregistre_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'MOBILE_MONEY', count(*)::int FROM transaction_mobile_money
         WHERE statut IN ('CONFIRMEE', 'REGULARISEE')
           AND (termine_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'SMS', count(*)::int FROM notification
         WHERE statut = 'ENVOYE' AND (envoye_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'BULLETINS', count(*)::int FROM generation_bulletins
         WHERE (publie_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'PROGRESSIONS', count(*)::int FROM fiche_progression
         WHERE (soumise_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'EMPLOI_DU_TEMPS', count(*)::int FROM seance_emploi
         WHERE (modifie_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'ATELIERS', count(*)::int FROM mouvement_stock
         WHERE (cree_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
        UNION ALL
        SELECT tenant_id, 'ELEVES', count(*)::int FROM eleve
         WHERE (cree_le AT TIME ZONE 'Africa/Ouagadougou')::date = p_jour GROUP BY tenant_id
    ) m
    WHERE valeur > 0;
    GET DIAGNOSTICS n = ROW_COUNT;
    INSERT INTO calcul_adoption (jour, calcule_le) VALUES (p_jour, now())
    ON CONFLICT (jour) DO UPDATE SET calcule_le = excluded.calcule_le;
    RETURN n;
END
$$;
REVOKE ALL ON FUNCTION calculer_adoption(DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION calculer_adoption(DATE) TO ${app_role};

-- ---------------------------------------------------------------------
-- Lecture pour le super administrateur : uniquement des nombres.
-- ---------------------------------------------------------------------

-- Par établissement : comptes, personnes actives sur la période, enseignants et parents
CREATE FUNCTION adoption_etablissements(p_debut DATE, p_fin DATE)
    RETURNS TABLE (tenant_id UUID, comptes INT, actifs INT, enseignants INT, enseignants_actifs INT,
                   parents INT, parents_actifs INT, jours_actifs INT, derniere_activite DATE)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    WITH membres AS (
        SELECT m.tenant_id, m.utilisateur_id,
               bool_or(m.role = 'ENSEIGNANT') AS enseignant,
               bool_or(m.role = 'PARENT') AS parent
          FROM membre_etablissement m
         WHERE m.actif
         GROUP BY m.tenant_id, m.utilisateur_id
    ), actifs AS (
        SELECT DISTINCT a.tenant_id, a.utilisateur_id
          FROM activite_jour a WHERE a.jour BETWEEN p_debut AND p_fin
    ), jours AS (
        SELECT a.tenant_id, count(DISTINCT a.jour)::int AS jours_actifs, max(a.jour) AS derniere
          FROM activite_jour a WHERE a.jour <= p_fin GROUP BY a.tenant_id
    )
    SELECT t.id,
           count(m.utilisateur_id)::int,
           count(m.utilisateur_id) FILTER (WHERE x.utilisateur_id IS NOT NULL)::int,
           count(m.utilisateur_id) FILTER (WHERE m.enseignant)::int,
           count(m.utilisateur_id) FILTER (WHERE m.enseignant AND x.utilisateur_id IS NOT NULL)::int,
           count(m.utilisateur_id) FILTER (WHERE m.parent)::int,
           count(m.utilisateur_id) FILTER (WHERE m.parent AND x.utilisateur_id IS NOT NULL)::int,
           coalesce((SELECT count(DISTINCT a.jour)::int FROM activite_jour a
                      WHERE a.tenant_id = t.id AND a.jour BETWEEN p_debut AND p_fin), 0),
           j.derniere
      FROM tenant t
      LEFT JOIN membres m ON m.tenant_id = t.id
      LEFT JOIN actifs x ON x.tenant_id = m.tenant_id AND x.utilisateur_id = m.utilisateur_id
      LEFT JOIN jours j ON j.tenant_id = t.id
     GROUP BY t.id, j.derniere
$$;
REVOKE ALL ON FUNCTION adoption_etablissements(DATE, DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION adoption_etablissements(DATE, DATE) TO ${app_role};

-- Actions par établissement et par indicateur sur la période
CREATE FUNCTION adoption_indicateurs(p_debut DATE, p_fin DATE)
    RETURNS TABLE (tenant_id UUID, indicateur VARCHAR, total BIGINT)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT m.tenant_id, m.indicateur, sum(m.valeur)
      FROM mesure_adoption m
     WHERE m.jour BETWEEN p_debut AND p_fin AND m.indicateur <> 'ACTIFS'
     GROUP BY m.tenant_id, m.indicateur
$$;
REVOKE ALL ON FUNCTION adoption_indicateurs(DATE, DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION adoption_indicateurs(DATE, DATE) TO ${app_role};

-- Personnes actives chaque jour sur toute la plateforme
CREATE FUNCTION adoption_quotidienne(p_debut DATE, p_fin DATE)
    RETURNS TABLE (jour DATE, actifs INT, etablissements INT)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT a.jour, count(DISTINCT a.utilisateur_id)::int, count(DISTINCT a.tenant_id)::int
      FROM activite_jour a
     WHERE a.jour BETWEEN p_debut AND p_fin
     GROUP BY a.jour
$$;
REVOKE ALL ON FUNCTION adoption_quotidienne(DATE, DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION adoption_quotidienne(DATE, DATE) TO ${app_role};

-- ---------------------------------------------------------------------
-- Historique : l'activité passée est reconstituée à partir des connexions du
-- journal d'audit et des saisies (appels, notes, cahier de textes). Les mesures
-- quotidiennes des jours passés sont calculées par l'application au démarrage.
-- ---------------------------------------------------------------------
INSERT INTO activite_jour (tenant_id, utilisateur_id, jour)
SELECT DISTINCT tenant_id, utilisateur_id, (horodatage AT TIME ZONE 'Africa/Ouagadougou')::date
  FROM journal_audit
 WHERE action = 'CONNEXION' AND tenant_id IS NOT NULL AND utilisateur_id IS NOT NULL
   AND EXISTS (SELECT 1 FROM utilisateur u WHERE u.id = journal_audit.utilisateur_id)
UNION
SELECT tenant_id, fait_par, (saisi_le AT TIME ZONE 'Africa/Ouagadougou')::date FROM appel WHERE fait_par IS NOT NULL
UNION
SELECT tenant_id, saisi_par, (saisi_le AT TIME ZONE 'Africa/Ouagadougou')::date FROM note WHERE saisi_par IS NOT NULL
UNION
SELECT tenant_id, saisi_par, (saisi_le AT TIME ZONE 'Africa/Ouagadougou')::date FROM seance_cahier WHERE saisi_par IS NOT NULL
ON CONFLICT DO NOTHING;
