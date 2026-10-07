-- =====================================================================
-- V28 : comptes des directions et tableau de bord de pilotage
--
-- Chaque direction (régionale, provinciale…) a un ou plusieurs comptes, nommés
-- par l'administrateur de son pays ou par le super administrateur. Une direction
-- voit uniquement des nombres : ceux de chaque établissement qui en dépend (à tout
-- niveau) et leurs cumuls par direction. Aucune donnée d'élève ne sort de
-- l'établissement.
--
-- Les fonctions SECURITY DEFINER ci-dessous ne renvoient que des comptages par
-- établissement ; l'application vérifie que les établissements demandés sont dans
-- la portée de la personne connectée. Les années scolaires des établissements
-- sont rapprochées par leur libellé (ex. « 2025-2026 »).
-- =====================================================================

CREATE TABLE administrateur_direction (
    utilisateur_id UUID        PRIMARY KEY REFERENCES utilisateur (id),
    direction_id   UUID        NOT NULL REFERENCES direction (id),
    actif          BOOLEAN     NOT NULL DEFAULT true,
    nomme_le       TIMESTAMPTZ NOT NULL DEFAULT now(),
    nomme_par      UUID        REFERENCES utilisateur (id),
    retire_le      TIMESTAMPTZ
);
CREATE INDEX administrateur_direction_direction ON administrateur_direction (direction_id);

-- ---------------------------------------------------------------------
-- Années scolaires connues des établissements (libellé, nombre d'établissements,
-- dont ceux pour qui c'est l'année active)
-- ---------------------------------------------------------------------
CREATE FUNCTION pilotage_annees(p_tenants UUID[])
    RETURNS TABLE (libelle VARCHAR, etablissements INT, actives INT, debut DATE)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT a.libelle, count(DISTINCT a.tenant_id)::int,
           count(DISTINCT a.tenant_id) FILTER (WHERE a.etat = 'ACTIVE')::int, min(a.debut)
      FROM annee_scolaire a
     WHERE a.tenant_id = ANY (p_tenants)
     GROUP BY a.libelle
$$;
REVOKE ALL ON FUNCTION pilotage_annees(UUID[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pilotage_annees(UUID[]) TO ${app_role};

-- ---------------------------------------------------------------------
-- Effectifs par établissement et par niveau : classes, élèves inscrits (actifs)
-- et redoublants, par sexe
-- ---------------------------------------------------------------------
CREATE FUNCTION pilotage_effectifs(p_tenants UUID[], p_annee VARCHAR)
    RETURNS TABLE (tenant_id UUID, niveau VARCHAR, classes INT, garcons INT, filles INT,
                   redoublants_garcons INT, redoublants_filles INT)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT c.tenant_id, c.niveau,
           count(DISTINCT c.id)::int,
           count(i.id) FILTER (WHERE e.sexe = 'M')::int,
           count(i.id) FILTER (WHERE e.sexe = 'F')::int,
           count(i.id) FILTER (WHERE e.sexe = 'M' AND i.redoublant)::int,
           count(i.id) FILTER (WHERE e.sexe = 'F' AND i.redoublant)::int
      FROM classe c
      JOIN annee_scolaire a ON a.tenant_id = c.tenant_id AND a.id = c.annee_id AND a.libelle = p_annee
      LEFT JOIN inscription i ON i.tenant_id = c.tenant_id AND i.classe_id = c.id AND i.statut = 'ACTIVE'
      LEFT JOIN eleve e ON e.tenant_id = i.tenant_id AND e.id = i.eleve_id
     WHERE c.tenant_id = ANY (p_tenants)
     GROUP BY c.tenant_id, c.niveau
$$;
REVOKE ALL ON FUNCTION pilotage_effectifs(UUID[], VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pilotage_effectifs(UUID[], VARCHAR) TO ${app_role};

-- ---------------------------------------------------------------------
-- Enseignants en poste (engagement actif) par établissement, par type et par sexe
-- ---------------------------------------------------------------------
CREATE FUNCTION pilotage_enseignants(p_tenants UUID[])
    RETURNS TABLE (tenant_id UUID, titulaires_hommes INT, titulaires_femmes INT,
                   vacataires_hommes INT, vacataires_femmes INT)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT g.tenant_id,
           count(*) FILTER (WHERE g.type = 'TITULAIRE' AND e.sexe = 'M')::int,
           count(*) FILTER (WHERE g.type = 'TITULAIRE' AND e.sexe = 'F')::int,
           count(*) FILTER (WHERE g.type = 'VACATAIRE' AND e.sexe = 'M')::int,
           count(*) FILTER (WHERE g.type = 'VACATAIRE' AND e.sexe = 'F')::int
      FROM engagement_enseignant g
      JOIN enseignant e ON e.id = g.enseignant_id
     WHERE g.tenant_id = ANY (p_tenants) AND g.statut = 'ACTIF'
     GROUP BY g.tenant_id
$$;
REVOKE ALL ON FUNCTION pilotage_enseignants(UUID[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pilotage_enseignants(UUID[]) TO ${app_role};

-- ---------------------------------------------------------------------
-- Décisions de fin d'année VALIDÉES par niveau et par sexe : décidés et admis
-- (admis en classe supérieure ou certifiés)
-- ---------------------------------------------------------------------
CREATE FUNCTION pilotage_resultats(p_tenants UUID[], p_annee VARCHAR)
    RETURNS TABLE (tenant_id UUID, niveau VARCHAR, decides_garcons INT, decides_filles INT,
                   admis_garcons INT, admis_filles INT)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT c.tenant_id, c.niveau,
           count(*) FILTER (WHERE e.sexe = 'M')::int,
           count(*) FILTER (WHERE e.sexe = 'F')::int,
           count(*) FILTER (WHERE e.sexe = 'M' AND d.decision IN ('ADMIS', 'CERTIFIE'))::int,
           count(*) FILTER (WHERE e.sexe = 'F' AND d.decision IN ('ADMIS', 'CERTIFIE'))::int
      FROM decision_fin_annee d
      JOIN classe c ON c.tenant_id = d.tenant_id AND c.id = d.classe_id
      JOIN annee_scolaire a ON a.tenant_id = c.tenant_id AND a.id = c.annee_id AND a.libelle = p_annee
      JOIN inscription i ON i.tenant_id = d.tenant_id AND i.id = d.inscription_id
      JOIN eleve e ON e.tenant_id = i.tenant_id AND e.id = i.eleve_id
     WHERE d.tenant_id = ANY (p_tenants) AND d.valide_le IS NOT NULL
     GROUP BY c.tenant_id, c.niveau
$$;
REVOKE ALL ON FUNCTION pilotage_resultats(UUID[], VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pilotage_resultats(UUID[], VARCHAR) TO ${app_role};

-- ---------------------------------------------------------------------
-- Examens de fin d'études (BEPC, BAC, CAP, BEP, BT, CQP…) par établissement et par
-- examen : candidats (inscrits actifs des classes d'examen), résultats connus et
-- admis, par sexe. Le nom de l'examen est celui que l'établissement a saisi,
-- rapproché sans tenir compte de la casse ni des espaces.
-- ---------------------------------------------------------------------
CREATE FUNCTION pilotage_examens(p_tenants UUID[], p_annee VARCHAR)
    RETURNS TABLE (tenant_id UUID, examen VARCHAR, candidats_garcons INT, candidats_filles INT,
                   resultats_garcons INT, resultats_filles INT, admis_garcons INT, admis_filles INT)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT c.tenant_id, upper(regexp_replace(btrim(x.examen), '\s+', ' ', 'g'))::varchar,
           count(*) FILTER (WHERE e.sexe = 'M')::int,
           count(*) FILTER (WHERE e.sexe = 'F')::int,
           count(*) FILTER (WHERE e.sexe = 'M' AND d.resultat_examen IS NOT NULL)::int,
           count(*) FILTER (WHERE e.sexe = 'F' AND d.resultat_examen IS NOT NULL)::int,
           count(*) FILTER (WHERE e.sexe = 'M' AND d.resultat_examen = 'ADMIS')::int,
           count(*) FILTER (WHERE e.sexe = 'F' AND d.resultat_examen = 'ADMIS')::int
      FROM classe_examen x
      JOIN classe c ON c.tenant_id = x.tenant_id AND c.id = x.classe_id
      JOIN annee_scolaire a ON a.tenant_id = c.tenant_id AND a.id = c.annee_id AND a.libelle = p_annee
      JOIN inscription i ON i.tenant_id = c.tenant_id AND i.classe_id = c.id AND i.statut = 'ACTIVE'
      JOIN eleve e ON e.tenant_id = i.tenant_id AND e.id = i.eleve_id
      LEFT JOIN decision_fin_annee d ON d.tenant_id = i.tenant_id AND d.inscription_id = i.id
     WHERE x.tenant_id = ANY (p_tenants)
     GROUP BY c.tenant_id, upper(regexp_replace(btrim(x.examen), '\s+', ' ', 'g'))
$$;
REVOKE ALL ON FUNCTION pilotage_examens(UUID[], VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pilotage_examens(UUID[], VARCHAR) TO ${app_role};

-- ---------------------------------------------------------------------
-- Moyennes des bulletins PUBLIÉS par établissement et par période (trimestre,
-- semestre ou module, repérés par leur rang) : nombre de bulletins avec une
-- moyenne, somme des moyennes (pour une moyenne exacte une fois cumulée) et
-- nombre de bulletins à la moyenne, par sexe
-- ---------------------------------------------------------------------
CREATE FUNCTION pilotage_periodes(p_tenants UUID[], p_annee VARCHAR)
    RETURNS TABLE (tenant_id UUID, decoupage VARCHAR, ordre SMALLINT, bulletins_garcons INT, bulletins_filles INT,
                   somme_moyennes NUMERIC, admis_garcons INT, admis_filles INT)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT g.tenant_id, pp.decoupage, p.ordre,
           count(*) FILTER (WHERE e.sexe = 'M')::int,
           count(*) FILTER (WHERE e.sexe = 'F')::int,
           sum(b.moyenne),
           count(*) FILTER (WHERE e.sexe = 'M' AND b.admis)::int,
           count(*) FILTER (WHERE e.sexe = 'F' AND b.admis)::int
      FROM generation_bulletins g
      JOIN periode p ON p.tenant_id = g.tenant_id AND p.id = g.periode_id
      JOIN annee_scolaire a ON a.tenant_id = p.tenant_id AND a.id = p.annee_id AND a.libelle = p_annee
      JOIN profil_pedagogique pp ON pp.tenant_id = p.tenant_id AND pp.id = p.profil_id
      JOIN bulletin b ON b.tenant_id = g.tenant_id AND b.generation_id = g.id AND b.moyenne IS NOT NULL
      JOIN inscription i ON i.tenant_id = b.tenant_id AND i.id = b.inscription_id
      JOIN eleve e ON e.tenant_id = i.tenant_id AND e.id = i.eleve_id
     WHERE g.tenant_id = ANY (p_tenants) AND g.statut = 'PUBLIEE'
     GROUP BY g.tenant_id, pp.decoupage, p.ordre
$$;
REVOKE ALL ON FUNCTION pilotage_periodes(UUID[], VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION pilotage_periodes(UUID[], VARCHAR) TO ${app_role};
