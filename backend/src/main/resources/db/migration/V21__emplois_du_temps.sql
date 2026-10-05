-- =====================================================================
-- V21 : emplois du temps
--
--   creneau_horaire        : grille horaire de l'année (heures de cours et jours où le
--                            créneau existe : mercredi et samedi après-midi libres, etc.)
--   seance_emploi          : une case de l'emploi du temps d'une classe (matière, jour,
--                            créneau, groupe éventuel, atelier ou salle)
--   publication_emploi     : date à laquelle l'emploi du temps a été communiqué aux enseignants
--
-- L'enseignant d'une séance n'est pas recopié : c'est celui affecté à la matière dans la
-- classe (classe_matiere). Un changement d'affectation met donc l'emploi du temps à jour.
-- Les matières générales sont placées par le censeur, les matières techniques et pratiques
-- par le chef des travaux (contrôlé par l'application).
-- =====================================================================

CREATE TABLE creneau_horaire (
    tenant_id   UUID        NOT NULL,
    id          UUID        NOT NULL,
    annee_id    UUID        NOT NULL,
    heure_debut TIME        NOT NULL,
    heure_fin   TIME        NOT NULL,
    -- Jours où le créneau existe, chiffres ISO (1 = lundi … 7 = dimanche), ex. « 12345 »
    jours       VARCHAR(7)  NOT NULL CHECK (jours ~ '^[1-7]{1,7}$'),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, annee_id, heure_debut),
    CHECK (heure_fin > heure_debut),
    CHECK (heure_fin - heure_debut <= INTERVAL '4 hours'),
    FOREIGN KEY (tenant_id, annee_id) REFERENCES annee_scolaire (tenant_id, id) ON DELETE CASCADE
);

SELECT activer_isolation('creneau_horaire');

CREATE TABLE seance_emploi (
    tenant_id   UUID        NOT NULL,
    id          UUID        NOT NULL,
    annee_id    UUID        NOT NULL,
    classe_id   UUID        NOT NULL,
    matiere_id  UUID        NOT NULL,
    jour        SMALLINT    NOT NULL CHECK (jour BETWEEN 1 AND 7),
    creneau_id  UUID        NOT NULL,
    -- Demi-classe (TP en groupes) ; NULL : toute la classe
    groupe      VARCHAR(20),
    atelier_id  UUID,
    salle       VARCHAR(40),
    modifie_par UUID        REFERENCES utilisateur (id),
    modifie_le  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, annee_id)   REFERENCES annee_scolaire (tenant_id, id),
    FOREIGN KEY (tenant_id, classe_id)  REFERENCES classe (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, matiere_id) REFERENCES matiere (tenant_id, id),
    FOREIGN KEY (tenant_id, creneau_id) REFERENCES creneau_horaire (tenant_id, id),
    FOREIGN KEY (tenant_id, atelier_id) REFERENCES atelier (tenant_id, id)
);
-- Une classe (ou un même groupe) n'a qu'une séance par case
CREATE UNIQUE INDEX ux_seance_emploi_classe
    ON seance_emploi (tenant_id, classe_id, jour, creneau_id, coalesce(groupe, ''));
-- Un atelier n'accueille qu'une séance à la fois
CREATE UNIQUE INDEX ux_seance_emploi_atelier
    ON seance_emploi (tenant_id, atelier_id, jour, creneau_id) WHERE atelier_id IS NOT NULL;
CREATE INDEX ix_seance_emploi_annee ON seance_emploi (tenant_id, annee_id, jour, creneau_id);
CREATE INDEX ix_seance_emploi_matiere ON seance_emploi (tenant_id, classe_id, matiere_id);

SELECT activer_isolation('seance_emploi');

CREATE TABLE publication_emploi (
    tenant_id  UUID        NOT NULL,
    id         UUID        NOT NULL,
    annee_id   UUID        NOT NULL,
    publie_le  TIMESTAMPTZ NOT NULL,
    publie_par UUID        REFERENCES utilisateur (id),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, annee_id),
    FOREIGN KEY (tenant_id, annee_id) REFERENCES annee_scolaire (tenant_id, id) ON DELETE CASCADE
);

SELECT activer_isolation('publication_emploi');

-- ---------------------------------------------------------------------
-- Heures où des enseignants de l'établissement courant sont déjà pris dans un AUTRE
-- établissement (vacataires) : jour et plage horaire seulement, jamais l'établissement,
-- la classe ni la matière. Ne porte que sur les engagements de l'établissement courant
-- et sur les années des autres établissements qui chevauchent la période demandée.
-- SECURITY DEFINER : lit au-delà du cloisonnement, mais ne renvoie que le strict nécessaire.
-- ---------------------------------------------------------------------
CREATE FUNCTION occupations_ailleurs(p_engagements UUID[], p_debut DATE, p_fin DATE)
    RETURNS TABLE (engagement_id UUID, jour SMALLINT, heure_debut TIME, heure_fin TIME)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    WITH miens AS (
        SELECT e.id AS local_id, e.enseignant_id
        FROM engagement_enseignant e
        WHERE e.id = ANY (p_engagements)
          AND e.tenant_id = tenant_courant()
    )
    SELECT DISTINCT m.local_id, s.jour, c.heure_debut, c.heure_fin
    FROM miens m
    JOIN engagement_enseignant e ON e.enseignant_id = m.enseignant_id
                                AND e.tenant_id <> tenant_courant()
                                AND e.statut = 'ACTIF'
    JOIN tenant t ON t.id = e.tenant_id AND t.statut = 'ACTIF'
    JOIN classe_matiere cm ON cm.tenant_id = e.tenant_id AND cm.engagement_id = e.id
    JOIN seance_emploi s ON s.tenant_id = cm.tenant_id AND s.classe_id = cm.classe_id
                        AND s.matiere_id = cm.matiere_id
    JOIN creneau_horaire c ON c.tenant_id = s.tenant_id AND c.id = s.creneau_id
    JOIN annee_scolaire a ON a.tenant_id = s.tenant_id AND a.id = s.annee_id
                         AND a.etat IN ('PREPARATION', 'ACTIVE')
                         AND daterange(a.debut, a.fin, '[]') && daterange(p_debut, p_fin, '[]')
$$;
REVOKE ALL ON FUNCTION occupations_ailleurs(UUID[], DATE, DATE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION occupations_ailleurs(UUID[], DATE, DATE) TO ${app_role};
