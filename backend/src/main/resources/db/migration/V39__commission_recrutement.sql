-- =====================================================================
-- V39 : commission de recrutement (complément d'effectif)
--
--   commission_recrutement : session de l'année (date, lieu, critères : moyenne
--                            minimale, poids de la note d'examen, taille de la
--                            liste d'attente) ; préparée, délibérée puis close
--   membre_commission      : président (chef d'établissement), vice-président
--                            (censeur), rapporteur (CPE), membres (intendant,
--                            représentant de l'APE, enseignants…) ; compte de
--                            l'établissement ou personne extérieure
--   offre_commission       : places offertes par niveau et filière
--   candidature            : candidat, responsable, établissement d'origine,
--                            moyenne et note d'examen ; recevabilité, rang,
--                            décision (retenu, liste d'attente, non retenu),
--                            puis inscription (mode COMPLEMENT_EFFECTIF) ou
--                            désistement avec repêchage sur la liste d'attente
-- =====================================================================

CREATE TABLE commission_recrutement (
    tenant_id            UUID          NOT NULL REFERENCES tenant (id),
    id                   UUID          NOT NULL,
    annee_id             UUID          NOT NULL,
    numero               VARCHAR(20)   NOT NULL,
    intitule             VARCHAR(200)  NOT NULL,
    date_session         DATE          NOT NULL,
    lieu                 VARCHAR(120),
    date_limite_depot    DATE,
    criteres             VARCHAR(1000),
    moyenne_minimale     NUMERIC(4,2)  NOT NULL DEFAULT 10 CHECK (moyenne_minimale BETWEEN 0 AND 20),
    poids_examen         NUMERIC(3,2)  NOT NULL DEFAULT 0 CHECK (poids_examen BETWEEN 0 AND 1),
    taille_liste_attente SMALLINT      NOT NULL DEFAULT 5 CHECK (taille_liste_attente BETWEEN 0 AND 100),
    statut               VARCHAR(12)   NOT NULL DEFAULT 'PREPARATION'
                         CHECK (statut IN ('PREPARATION', 'DELIBEREE', 'CLOSE')),
    observations_pv      VARCHAR(4000),
    cree_par             UUID          REFERENCES utilisateur (id),
    cree_le              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deliberee_par        UUID          REFERENCES utilisateur (id),
    deliberee_le         TIMESTAMPTZ,
    close_le             TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, numero),
    CHECK ((statut = 'PREPARATION') = (deliberee_le IS NULL)),
    CHECK ((statut = 'CLOSE') = (close_le IS NOT NULL)),
    FOREIGN KEY (tenant_id, annee_id) REFERENCES annee_scolaire (tenant_id, id)
);
SELECT activer_isolation('commission_recrutement');

CREATE TABLE membre_commission (
    tenant_id      UUID          NOT NULL,
    id             UUID          NOT NULL,
    commission_id  UUID          NOT NULL,
    qualite        VARCHAR(15)   NOT NULL CHECK (qualite IN ('PRESIDENT', 'VICE_PRESIDENT', 'RAPPORTEUR', 'MEMBRE')),
    utilisateur_id UUID          REFERENCES utilisateur (id),
    nom            VARCHAR(160)  NOT NULL,
    fonction       VARCHAR(100)  NOT NULL,
    present        BOOLEAN       NOT NULL DEFAULT true,
    ordre          SMALLINT      NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, commission_id) REFERENCES commission_recrutement (tenant_id, id) ON DELETE CASCADE
);
SELECT activer_isolation('membre_commission');
CREATE INDEX membre_commission_commission ON membre_commission (tenant_id, commission_id);
-- Un seul président, un seul vice-président, un seul rapporteur
CREATE UNIQUE INDEX membre_commission_qualite_unique ON membre_commission (tenant_id, commission_id, qualite)
    WHERE qualite <> 'MEMBRE';

CREATE TABLE offre_commission (
    tenant_id     UUID          NOT NULL,
    id            UUID          NOT NULL,
    commission_id UUID          NOT NULL,
    niveau        VARCHAR(20)   NOT NULL,
    filiere_id    UUID          NOT NULL,
    places        INT           NOT NULL DEFAULT 0 CHECK (places >= 0),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, commission_id, niveau, filiere_id),
    FOREIGN KEY (tenant_id, commission_id) REFERENCES commission_recrutement (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, filiere_id) REFERENCES filiere (tenant_id, id)
);
SELECT activer_isolation('offre_commission');

CREATE TABLE candidature (
    tenant_id              UUID          NOT NULL,
    id                     UUID          NOT NULL,
    commission_id          UUID          NOT NULL,
    offre_id               UUID          NOT NULL,
    numero                 VARCHAR(20)   NOT NULL,
    nom                    VARCHAR(80)   NOT NULL,
    prenoms                VARCHAR(120)  NOT NULL,
    sexe                   CHAR(1)       NOT NULL CHECK (sexe IN ('M', 'F')),
    date_naissance         DATE          NOT NULL,
    lieu_naissance         VARCHAR(120),
    responsable_nom        VARCHAR(80)   NOT NULL,
    responsable_prenoms    VARCHAR(120)  NOT NULL,
    responsable_telephone  VARCHAR(20)   NOT NULL,
    responsable_lien       VARCHAR(10)   NOT NULL CHECK (responsable_lien IN ('PERE', 'MERE', 'TUTEUR', 'AUTRE')),
    etablissement_origine  VARCHAR(200)  NOT NULL,
    classe_origine         VARCHAR(40),
    moyenne_annuelle       NUMERIC(5,2)  NOT NULL CHECK (moyenne_annuelle BETWEEN 0 AND 20),
    note_examen            NUMERIC(5,2)  CHECK (note_examen BETWEEN 0 AND 20),
    redoublant             BOOLEAN       NOT NULL DEFAULT false,
    observations           VARCHAR(500),
    statut                 VARCHAR(13)   NOT NULL DEFAULT 'DEPOSEE'
                           CHECK (statut IN ('DEPOSEE', 'IRRECEVABLE', 'RETENUE', 'LISTE_ATTENTE', 'NON_RETENUE',
                                             'INSCRITE', 'DESISTEE')),
    motif                  VARCHAR(300),
    score                  NUMERIC(6,3),
    rang                   INT,
    repechee               BOOLEAN       NOT NULL DEFAULT false,
    saisie_par             UUID          REFERENCES utilisateur (id),
    saisie_le              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    inscription_id         UUID,
    inscrite_le            TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, numero),
    CHECK ((statut = 'INSCRITE') = (inscription_id IS NOT NULL)),
    FOREIGN KEY (tenant_id, commission_id) REFERENCES commission_recrutement (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, offre_id) REFERENCES offre_commission (tenant_id, id)
);
SELECT activer_isolation('candidature');
CREATE INDEX candidature_offre ON candidature (tenant_id, offre_id);
