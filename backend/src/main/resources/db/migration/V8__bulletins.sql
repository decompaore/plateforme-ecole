-- =====================================================================
-- V8 : bulletins
--   parametres_bulletin : en-tête du document et seuils des distinctions
--   appreciation        : appréciation d'un enseignant, par élève, matière et période
--   avis_conseil        : appréciation générale et distinction décidées en conseil
--   generation_bulletins: une génération par classe et par période (figée, puis publiée)
--   bulletin            : résultats figés d'un élève + PDF + code de vérification
--   bulletin_ligne      : lignes du bulletin (matières, groupes, modules)
-- =====================================================================

CREATE TABLE parametres_bulletin (
    tenant_id              UUID         PRIMARY KEY REFERENCES tenant (id),
    entete_pays            VARCHAR(120) NOT NULL DEFAULT 'BURKINA FASO',
    entete_devise          VARCHAR(120),
    entete_ministere       VARCHAR(200),
    entete_direction       VARCHAR(200),
    adresse                VARCHAR(200),
    seuil_tableau_honneur  NUMERIC(4,2) NOT NULL DEFAULT 12,
    seuil_encouragements   NUMERIC(4,2) NOT NULL DEFAULT 14,
    seuil_felicitations    NUMERIC(4,2) NOT NULL DEFAULT 16,
    seuil_avertissement    NUMERIC(4,2) NOT NULL DEFAULT 8,
    CHECK (seuil_avertissement < seuil_tableau_honneur),
    CHECK (seuil_tableau_honneur <= seuil_encouragements AND seuil_encouragements <= seuil_felicitations),
    CHECK (seuil_felicitations <= 20)
);

CREATE TABLE appreciation (
    tenant_id      UUID         NOT NULL,
    id             UUID         NOT NULL,
    inscription_id UUID         NOT NULL,
    periode_id     UUID         NOT NULL,
    matiere_id     UUID         NOT NULL,
    texte          VARCHAR(200) NOT NULL,
    saisi_par      UUID         REFERENCES utilisateur (id),
    saisi_le       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, inscription_id, periode_id, matiere_id),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id),
    FOREIGN KEY (tenant_id, periode_id)     REFERENCES periode (tenant_id, id),
    FOREIGN KEY (tenant_id, matiere_id)     REFERENCES matiere (tenant_id, id)
);

CREATE TABLE avis_conseil (
    tenant_id      UUID         NOT NULL,
    id             UUID         NOT NULL,
    inscription_id UUID         NOT NULL,
    periode_id     UUID         NOT NULL,
    distinction    VARCHAR(24)  CHECK (distinction IN ('AUCUNE', 'TABLEAU_HONNEUR', 'ENCOURAGEMENTS',
                                  'FELICITATIONS', 'AVERTISSEMENT_TRAVAIL', 'AVERTISSEMENT_CONDUITE', 'BLAME')),
    appreciation   VARCHAR(300),
    saisi_par      UUID         REFERENCES utilisateur (id),
    saisi_le       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, inscription_id, periode_id),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id),
    FOREIGN KEY (tenant_id, periode_id)     REFERENCES periode (tenant_id, id)
);

CREATE TABLE generation_bulletins (
    tenant_id      UUID         NOT NULL,
    id             UUID         NOT NULL,
    classe_id      UUID         NOT NULL,
    periode_id     UUID         NOT NULL,
    statut         VARCHAR(8)   NOT NULL DEFAULT 'GENEREE' CHECK (statut IN ('GENEREE', 'PUBLIEE')),
    effectif       INTEGER      NOT NULL,
    moyenne_classe NUMERIC(5,2),
    plus_forte     NUMERIC(5,2),
    plus_faible    NUMERIC(5,2),
    taux_reussite  NUMERIC(5,2),
    genere_par     UUID         REFERENCES utilisateur (id),
    genere_le      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    publie_par     UUID         REFERENCES utilisateur (id),
    publie_le      TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, classe_id, periode_id),
    CHECK ((statut = 'PUBLIEE') = (publie_le IS NOT NULL)),
    FOREIGN KEY (tenant_id, classe_id)  REFERENCES classe (tenant_id, id),
    FOREIGN KEY (tenant_id, periode_id) REFERENCES periode (tenant_id, id)
);

CREATE TABLE bulletin (
    tenant_id             UUID         NOT NULL,
    id                    UUID         NOT NULL,
    generation_id         UUID         NOT NULL,
    inscription_id        UUID         NOT NULL,
    matricule             VARCHAR(30)  NOT NULL,           -- identité figée à la génération
    nom                   VARCHAR(80)  NOT NULL,
    prenoms               VARCHAR(120) NOT NULL,
    moyenne               NUMERIC(5,2),
    rang                  INTEGER,
    admis                 BOOLEAN      NOT NULL,
    taux_maitrise         NUMERIC(5,2),
    distinction           VARCHAR(24)  NOT NULL DEFAULT 'AUCUNE',
    appreciation_generale VARCHAR(300),
    heures_absence        NUMERIC(6,1) NOT NULL DEFAULT 0,
    heures_non_justifiees NUMERIC(6,1) NOT NULL DEFAULT 0,
    retards               INTEGER      NOT NULL DEFAULT 0,
    code_verification     VARCHAR(12)  NOT NULL UNIQUE,   -- unique sur toute la plateforme
    pdf                   BYTEA        NOT NULL,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, generation_id, inscription_id),
    FOREIGN KEY (tenant_id, generation_id)  REFERENCES generation_bulletins (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id)
);

CREATE TABLE bulletin_ligne (
    tenant_id     UUID         NOT NULL,
    id            UUID         NOT NULL,
    bulletin_id   UUID         NOT NULL,
    ordre         SMALLINT     NOT NULL,
    nature        VARCHAR(8)   NOT NULL CHECK (nature IN ('MATIERE', 'GROUPE', 'MODULE')),
    code          VARCHAR(20),
    libelle       VARCHAR(120) NOT NULL,
    groupe        VARCHAR(40),
    coefficient   NUMERIC(4,1),
    moyenne       NUMERIC(5,2),
    points        NUMERIC(7,2),
    rang          INTEGER,
    sans_note     BOOLEAN      NOT NULL DEFAULT false,
    appreciation  VARCHAR(200),
    enseignant    VARCHAR(210),
    competences   INTEGER,
    acquises      INTEGER,
    taux          NUMERIC(5,2),
    statut_module VARCHAR(10),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, bulletin_id, ordre),
    FOREIGN KEY (tenant_id, bulletin_id) REFERENCES bulletin (tenant_id, id) ON DELETE CASCADE
);

SELECT activer_isolation('parametres_bulletin');
SELECT activer_isolation('appreciation');
SELECT activer_isolation('avis_conseil');
SELECT activer_isolation('generation_bulletins');
SELECT activer_isolation('bulletin');
SELECT activer_isolation('bulletin_ligne');

-- Vérification publique d'un bulletin (document papier présenté à un tiers) :
-- ne renvoie que ce qui figure déjà sur le document, et seulement s'il est publié.
CREATE FUNCTION verifier_bulletin(p_code VARCHAR)
    RETURNS TABLE (etablissement VARCHAR, eleve VARCHAR, matricule VARCHAR, classe VARCHAR, periode VARCHAR,
                   annee VARCHAR, moyenne NUMERIC, rang INTEGER, effectif INTEGER, publie_le TIMESTAMPTZ)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT t.nom, b.nom || ' ' || b.prenoms, b.matricule, c.code, p.libelle, a.libelle,
           b.moyenne, b.rang, g.effectif, g.publie_le
    FROM bulletin b
    JOIN generation_bulletins g ON g.tenant_id = b.tenant_id AND g.id = b.generation_id
    JOIN classe c ON c.tenant_id = g.tenant_id AND c.id = g.classe_id
    JOIN periode p ON p.tenant_id = g.tenant_id AND p.id = g.periode_id
    JOIN annee_scolaire a ON a.tenant_id = c.tenant_id AND a.id = c.annee_id
    JOIN tenant t ON t.id = b.tenant_id
    WHERE b.code_verification = upper(p_code) AND g.statut = 'PUBLIEE' AND t.statut = 'ACTIF'
$$;
REVOKE ALL ON FUNCTION verifier_bulletin(VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION verifier_bulletin(VARCHAR) TO ${app_role};
