-- =====================================================================
-- V9 : scolarité (frais, bourses, encaissements, reçus, relances)
--   parametres_scolarite : taux de prise en charge par défaut, délai entre relances
--   organisme_financeur  : État, collectivité, ONG, entreprise qui paie des bourses
--   frais_scolarite      : frais d'une année (inscription, scolarité, APE, tenue…)
--   frais_cible          : à qui s'applique un frais (filières, niveaux ou classes)
--   tranche_frais        : découpage d'un frais en tranches avec date limite
--   souscription_frais   : frais facultatifs choisis par un élève (cantine, transport…)
--   prise_en_charge      : bourse d'une inscription (organisme, taux, décision)
--   exoneration          : réduction accordée par l'école sur un frais
--   paiement             : encaissement (famille ou organisme) — jamais supprimé ni modifié,
--                          seulement annulé avec un motif
--   recu                 : reçu numéroté, immuable, avec code de vérification
--   relance              : historique des SMS de relance envoyés aux familles
-- Montants en francs CFA, sans décimales.
-- L'échéancier n'est pas stocké : il est calculé à partir de ces tables
-- (tranches, part organisme / part famille, paiements imputés sur la tranche
-- la plus ancienne).
-- =====================================================================

CREATE TABLE parametres_scolarite (
    tenant_id              UUID         PRIMARY KEY REFERENCES tenant (id),
    taux_boursier          NUMERIC(5,2) NOT NULL DEFAULT 100 CHECK (taux_boursier > 0 AND taux_boursier <= 100),
    taux_semi_boursier     NUMERIC(5,2) NOT NULL DEFAULT 50  CHECK (taux_semi_boursier > 0 AND taux_semi_boursier <= 100),
    delai_relance_jours    SMALLINT     NOT NULL DEFAULT 7   CHECK (delai_relance_jours BETWEEN 1 AND 90)
);

CREATE TABLE organisme_financeur (
    tenant_id  UUID         NOT NULL REFERENCES tenant (id),
    id         UUID         NOT NULL,
    nom        VARCHAR(120) NOT NULL,
    type       VARCHAR(12)  NOT NULL CHECK (type IN ('ETAT', 'COLLECTIVITE', 'ONG', 'ENTREPRISE', 'AUTRE')),
    telephone  VARCHAR(20),
    actif      BOOLEAN      NOT NULL DEFAULT true,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, nom)
);

CREATE TABLE frais_scolarite (
    tenant_id          UUID         NOT NULL,
    id                 UUID         NOT NULL,
    annee_id           UUID         NOT NULL,
    libelle            VARCHAR(80)  NOT NULL,
    montant            BIGINT       NOT NULL CHECK (montant > 0 AND montant <= 100000000),
    obligatoire        BOOLEAN      NOT NULL DEFAULT true,
    couvert_par_bourse BOOLEAN      NOT NULL DEFAULT true,   -- la bourse s'applique à ce frais
    portee             VARCHAR(8)   NOT NULL DEFAULT 'TOUTES'
                       CHECK (portee IN ('TOUTES', 'FILIERES', 'NIVEAUX', 'CLASSES')),
    cree_le            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, annee_id, libelle),
    FOREIGN KEY (tenant_id, annee_id) REFERENCES annee_scolaire (tenant_id, id)
);

CREATE TABLE frais_cible (
    tenant_id  UUID        NOT NULL,
    id         UUID        NOT NULL,
    frais_id   UUID        NOT NULL,
    filiere_id UUID,
    niveau     VARCHAR(30),
    classe_id  UUID,
    PRIMARY KEY (tenant_id, id),
    CHECK (num_nonnulls(filiere_id, niveau, classe_id) = 1),
    FOREIGN KEY (tenant_id, frais_id)   REFERENCES frais_scolarite (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, filiere_id) REFERENCES filiere (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, classe_id)  REFERENCES classe (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX ix_frais_cible_frais ON frais_cible (tenant_id, frais_id);

CREATE TABLE tranche_frais (
    tenant_id   UUID        NOT NULL,
    id          UUID        NOT NULL,
    frais_id    UUID        NOT NULL,
    numero      SMALLINT    NOT NULL CHECK (numero BETWEEN 1 AND 12),
    date_limite DATE        NOT NULL,
    montant     BIGINT      NOT NULL CHECK (montant > 0),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, frais_id, numero),
    FOREIGN KEY (tenant_id, frais_id) REFERENCES frais_scolarite (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE souscription_frais (
    tenant_id      UUID        NOT NULL,
    id             UUID        NOT NULL,
    inscription_id UUID        NOT NULL,
    frais_id       UUID        NOT NULL,
    souscrit_le    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, inscription_id, frais_id),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id),
    FOREIGN KEY (tenant_id, frais_id)       REFERENCES frais_scolarite (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE prise_en_charge (
    tenant_id          UUID         NOT NULL,
    id                 UUID         NOT NULL,
    inscription_id     UUID         NOT NULL,
    organisme_id       UUID         NOT NULL,
    taux               NUMERIC(5,2) NOT NULL CHECK (taux > 0 AND taux <= 100),
    reference_decision VARCHAR(60),
    date_decision      DATE,
    saisi_par          UUID         REFERENCES utilisateur (id),
    saisi_le           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, inscription_id),                  -- une prise en charge par année d'inscription
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id),
    FOREIGN KEY (tenant_id, organisme_id)   REFERENCES organisme_financeur (tenant_id, id)
);

CREATE TABLE exoneration (
    tenant_id      UUID         NOT NULL,
    id             UUID         NOT NULL,
    inscription_id UUID         NOT NULL,
    frais_id       UUID         NOT NULL,
    montant        BIGINT       NOT NULL CHECK (montant > 0),
    motif          VARCHAR(200) NOT NULL,
    accorde_par    UUID         REFERENCES utilisateur (id),
    accorde_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, inscription_id, frais_id),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id),
    FOREIGN KEY (tenant_id, frais_id)       REFERENCES frais_scolarite (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE paiement (
    tenant_id         UUID         NOT NULL,
    id                UUID         NOT NULL,
    inscription_id    UUID         NOT NULL,
    montant           BIGINT       NOT NULL CHECK (montant > 0),
    moyen             VARCHAR(12)  NOT NULL
                      CHECK (moyen IN ('ESPECES', 'ORANGE_MONEY', 'MOOV_MONEY', 'VIREMENT', 'CHEQUE')),
    payeur            VARCHAR(10)  NOT NULL CHECK (payeur IN ('FAMILLE', 'ORGANISME')),
    organisme_id      UUID,
    reference_externe VARCHAR(60),                        -- n° de chèque, de virement, de transaction
    deposant          VARCHAR(120),                       -- personne qui a remis l'argent
    date_paiement     DATE         NOT NULL,
    cle_idempotence   VARCHAR(64)  NOT NULL,              -- un double clic n'encaisse pas deux fois
    encaisse_par      UUID         REFERENCES utilisateur (id),
    enregistre_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    annule_le         TIMESTAMPTZ,
    annule_par        UUID         REFERENCES utilisateur (id),
    motif_annulation  VARCHAR(200),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, cle_idempotence),
    CHECK ((payeur = 'ORGANISME') = (organisme_id IS NOT NULL)),
    CHECK ((annule_le IS NULL) = (motif_annulation IS NULL)),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id),
    FOREIGN KEY (tenant_id, organisme_id)   REFERENCES organisme_financeur (tenant_id, id)
);
CREATE INDEX ix_paiement_inscription ON paiement (tenant_id, inscription_id);

-- Un paiement ne change jamais ; seule l'annulation (une fois, avec motif) est permise
CREATE FUNCTION paiement_immuable() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.annule_le IS NOT NULL THEN
        RAISE EXCEPTION 'Paiement déjà annulé : il ne peut plus être modifié' USING ERRCODE = 'check_violation';
    END IF;
    IF (to_jsonb(NEW) - 'annule_le' - 'annule_par' - 'motif_annulation')
       IS DISTINCT FROM (to_jsonb(OLD) - 'annule_le' - 'annule_par' - 'motif_annulation') THEN
        RAISE EXCEPTION 'Un paiement ne se modifie pas : annulez-le et encaissez à nouveau'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER paiement_immuable BEFORE UPDATE ON paiement
    FOR EACH ROW EXECUTE FUNCTION paiement_immuable();

CREATE TABLE recu (
    tenant_id         UUID        NOT NULL,
    id                UUID        NOT NULL,
    paiement_id       UUID        NOT NULL,
    numero            VARCHAR(20) NOT NULL,
    code_verification VARCHAR(12) NOT NULL UNIQUE,       -- unique sur toute la plateforme
    emis_le           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, paiement_id),
    UNIQUE (tenant_id, numero),
    FOREIGN KEY (tenant_id, paiement_id) REFERENCES paiement (tenant_id, id)
);

CREATE TABLE relance (
    tenant_id      UUID        NOT NULL,
    id             UUID        NOT NULL,
    inscription_id UUID        NOT NULL,
    montant_du     BIGINT      NOT NULL CHECK (montant_du > 0),
    envoye_par     UUID        REFERENCES utilisateur (id),
    envoye_le      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id)
);
CREATE INDEX ix_relance_inscription ON relance (tenant_id, inscription_id, envoye_le);

SELECT activer_isolation('parametres_scolarite');
SELECT activer_isolation('organisme_financeur');
SELECT activer_isolation('frais_scolarite');
SELECT activer_isolation('frais_cible');
SELECT activer_isolation('tranche_frais');
SELECT activer_isolation('souscription_frais');
SELECT activer_isolation('prise_en_charge');
SELECT activer_isolation('exoneration');
SELECT activer_isolation('paiement');
SELECT activer_isolation('recu');
SELECT activer_isolation('relance');

-- Traçabilité comptable : ni paiement ni reçu ne disparaissent, un reçu ne change jamais
REVOKE DELETE ON paiement FROM ${app_role};
REVOKE UPDATE, DELETE ON recu FROM ${app_role};

-- Vérification publique d'un reçu (sans connexion) : ce qui figure déjà sur le reçu papier
CREATE FUNCTION verifier_recu(p_code VARCHAR)
    RETURNS TABLE (etablissement VARCHAR, numero VARCHAR, eleve VARCHAR, matricule VARCHAR, montant BIGINT,
                   payeur VARCHAR, date_paiement DATE, emis_le TIMESTAMPTZ, annule BOOLEAN)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT t.nom, r.numero, e.nom || ' ' || e.prenoms, e.matricule, p.montant, p.payeur, p.date_paiement,
           r.emis_le, p.annule_le IS NOT NULL
    FROM recu r
    JOIN paiement p ON p.tenant_id = r.tenant_id AND p.id = r.paiement_id
    JOIN inscription i ON i.tenant_id = p.tenant_id AND i.id = p.inscription_id
    JOIN eleve e ON e.tenant_id = i.tenant_id AND e.id = i.eleve_id
    JOIN tenant t ON t.id = r.tenant_id
    WHERE r.code_verification = upper(p_code) AND t.statut = 'ACTIF'
$$;
REVOKE ALL ON FUNCTION verifier_recu(VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION verifier_recu(VARCHAR) TO ${app_role};
