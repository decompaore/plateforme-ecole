-- =====================================================================
-- V35 : secrétariat de l'établissement (v0.47.1)
--   - registre du courrier (arrivée, départ) et son suivi ;
--   - documents administratifs (notes de service, décisions, communiqués,
--     attestations…) rédigés puis signés, et le tableau d'affichage ;
--   - actes administratifs du personnel (demandes de mutation,
--     d'affectation, d'autorisation d'absence…) et l'avis du chef ;
--   - registre de l'accueil (visites, appels téléphoniques) ;
--   - comptes rendus des réunions statutaires ;
--   - archives des dossiers (élèves, personnel, établissement) ;
--   - registre des certificats de scolarité délivrés.
-- Les colonnes « …_par » gardent l'auteur sans clé étrangère (comptes
-- supprimés ou anonymisés : la trace reste).
-- =====================================================================

CREATE TABLE courrier (
    tenant_id      UUID         NOT NULL REFERENCES tenant (id),
    id             UUID         NOT NULL,
    sens           VARCHAR(7)   NOT NULL CHECK (sens IN ('ARRIVEE', 'DEPART')),
    numero         VARCHAR(20)  NOT NULL,
    date_courrier  DATE         NOT NULL,
    date_document  DATE,
    reference      VARCHAR(80),
    correspondant  VARCHAR(200) NOT NULL,
    categorie      VARCHAR(25)  NOT NULL CHECK (categorie IN ('DIRECTION_PROVINCIALE', 'DIRECTION_REGIONALE',
                                'MINISTERE', 'PARENTS', 'PERSONNEL', 'PARTENAIRE', 'NOTE_SERVICE', 'AUTRE')),
    objet          VARCHAR(300) NOT NULL,
    service        VARCHAR(20)  CHECK (service IN ('DIRECTION', 'CENSORAT', 'CHEF_TRAVAUX', 'INTENDANCE',
                                'VIE_SCOLAIRE', 'SECRETARIAT', 'ENSEIGNANTS')),
    statut         VARCHAR(12)  NOT NULL DEFAULT 'ENREGISTRE'
                                CHECK (statut IN ('ENREGISTRE', 'TRANSMIS', 'TRAITE', 'CLASSE')),
    suivi          VARCHAR(500),
    cote           VARCHAR(40),
    reponse_a      UUID,
    fichier        BYTEA,
    fichier_type   VARCHAR(20)  CHECK (fichier_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    fichier_nom    VARCHAR(150),
    enregistre_par UUID,
    enregistre_le  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    modifie_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, numero),
    FOREIGN KEY (tenant_id, reponse_a) REFERENCES courrier (tenant_id, id),
    CHECK ((fichier IS NULL) = (fichier_type IS NULL))
);
CREATE INDEX ix_courrier_date ON courrier (tenant_id, sens, date_courrier DESC);
SELECT activer_isolation('courrier');

CREATE TABLE document_administratif (
    tenant_id        UUID          NOT NULL REFERENCES tenant (id),
    id               UUID          NOT NULL,
    type             VARCHAR(15)   NOT NULL CHECK (type IN ('NOTE_SERVICE', 'DECISION', 'COMMUNIQUE', 'ATTESTATION',
                                   'CONVOCATION', 'LETTRE', 'AUTRE')),
    numero           VARCHAR(40),
    titre            VARCHAR(200)  NOT NULL,
    destinataires    VARCHAR(300),
    corps            VARCHAR(20000) NOT NULL,
    signataire       VARCHAR(120)  NOT NULL,
    statut           VARCHAR(10)   NOT NULL DEFAULT 'BROUILLON' CHECK (statut IN ('BROUILLON', 'SIGNE', 'ANNULE')),
    redige_par       UUID,
    redige_le        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    modifie_le       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    signe_par        UUID,
    signe_le         TIMESTAMPTZ,
    affichage_public VARCHAR(10)   CHECK (affichage_public IN ('PERSONNEL', 'TOUS')),
    affiche_du       DATE,
    affiche_au       DATE,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, numero),
    CHECK ((statut = 'BROUILLON') = (numero IS NULL)),
    CHECK ((affichage_public IS NULL) = (affiche_du IS NULL) AND (affiche_du IS NULL) = (affiche_au IS NULL)),
    CHECK (affiche_au IS NULL OR affiche_au >= affiche_du)
);
CREATE INDEX ix_document_affichage ON document_administratif (tenant_id, affiche_au) WHERE affiche_du IS NOT NULL;
SELECT activer_isolation('document_administratif');

CREATE TABLE demande_administrative (
    tenant_id     UUID         NOT NULL REFERENCES tenant (id),
    id            UUID         NOT NULL,
    numero        VARCHAR(20)  NOT NULL,
    type          VARCHAR(25)  NOT NULL CHECK (type IN ('MUTATION', 'AFFECTATION', 'AUTORISATION_ABSENCE', 'CONGE',
                               'DISPONIBILITE', 'ATTESTATION_SERVICE', 'AUTRE')),
    demandeur_id  UUID,
    demandeur     VARCHAR(200) NOT NULL,
    recue_le      DATE         NOT NULL,
    objet         VARCHAR(300) NOT NULL,
    du            DATE,
    au            DATE,
    statut        VARCHAR(22)  NOT NULL DEFAULT 'RECUE' CHECK (statut IN ('RECUE', 'TRANSMISE', 'AVIS_FAVORABLE',
                               'AVIS_DEFAVORABLE', 'TRANSMISE_HIERARCHIE', 'CLOTUREE')),
    avis          VARCHAR(500),
    avis_par      UUID,
    avis_le       TIMESTAMPTZ,
    suite         VARCHAR(500),
    courrier_id   UUID,
    fichier       BYTEA,
    fichier_type  VARCHAR(20)  CHECK (fichier_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    fichier_nom   VARCHAR(150),
    saisie_par    UUID,
    saisie_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, numero),
    FOREIGN KEY (tenant_id, courrier_id) REFERENCES courrier (tenant_id, id),
    CHECK (au IS NULL OR (du IS NOT NULL AND au >= du))
);
CREATE INDEX ix_demande_demandeur ON demande_administrative (tenant_id, demandeur_id);
SELECT activer_isolation('demande_administrative');

CREATE TABLE registre_accueil (
    tenant_id     UUID          NOT NULL REFERENCES tenant (id),
    id            UUID          NOT NULL,
    canal         VARCHAR(7)    NOT NULL CHECK (canal IN ('VISITE', 'APPEL')),
    arrivee       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    depart        TIMESTAMPTZ,
    nom           VARCHAR(150)  NOT NULL,
    telephone     VARCHAR(20),
    qualite       VARCHAR(12)   NOT NULL CHECK (qualite IN ('PARENT', 'VISITEUR', 'PARTENAIRE', 'AUTORITE',
                                'PERSONNEL', 'AUTRE')),
    objet         VARCHAR(300)  NOT NULL,
    service       VARCHAR(20)   CHECK (service IN ('DIRECTION', 'CENSORAT', 'CHEF_TRAVAUX', 'INTENDANCE',
                                'VIE_SCOLAIRE', 'SECRETARIAT', 'ENSEIGNANTS')),
    personne_id   UUID,
    personne      VARCHAR(200),
    message       VARCHAR(1000),
    urgent        BOOLEAN       NOT NULL DEFAULT false,
    transmis_le   TIMESTAMPTZ,
    saisi_par     UUID,
    PRIMARY KEY (tenant_id, id),
    CHECK (depart IS NULL OR depart >= arrivee)
);
CREATE INDEX ix_accueil_arrivee ON registre_accueil (tenant_id, arrivee DESC);
SELECT activer_isolation('registre_accueil');

CREATE TABLE reunion (
    tenant_id          UUID           NOT NULL REFERENCES tenant (id),
    id                 UUID           NOT NULL,
    instance           VARCHAR(25)    NOT NULL CHECK (instance IN ('CONSEIL_ETABLISSEMENT', 'CONSEIL_CLASSE',
                                      'CONSEIL_DISCIPLINE', 'CONSEIL_ENSEIGNEMENT', 'CONSEIL_ECOLE', 'ASSEMBLEE_APE',
                                      'REUNION_PERSONNEL', 'AUTRE')),
    titre              VARCHAR(200)   NOT NULL,
    date_reunion       DATE           NOT NULL,
    heure              TIME,
    lieu               VARCHAR(150),
    president          VARCHAR(150),
    secretaire_seance  VARCHAR(150),
    participants       VARCHAR(4000),
    ordre_du_jour      VARCHAR(4000),
    compte_rendu       VARCHAR(20000),
    statut             VARCHAR(9)     NOT NULL DEFAULT 'BROUILLON' CHECK (statut IN ('BROUILLON', 'VALIDE')),
    redige_par         UUID,
    modifie_le         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    valide_par         UUID,
    valide_le          TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX ix_reunion_date ON reunion (tenant_id, date_reunion DESC);
SELECT activer_isolation('reunion');

CREATE TABLE archive_document (
    tenant_id      UUID         NOT NULL REFERENCES tenant (id),
    id             UUID         NOT NULL,
    dossier        VARCHAR(13)  NOT NULL CHECK (dossier IN ('ELEVE', 'PERSONNEL', 'ETABLISSEMENT')),
    eleve_id       UUID,
    utilisateur_id UUID,
    categorie      VARCHAR(60)  NOT NULL,
    titre          VARCHAR(200) NOT NULL,
    cote           VARCHAR(40),
    date_document  DATE,
    fichier        BYTEA        NOT NULL,
    fichier_type   VARCHAR(20)  NOT NULL CHECK (fichier_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    fichier_nom    VARCHAR(150) NOT NULL,
    taille         INTEGER      NOT NULL,
    ajoute_par     UUID,
    ajoute_le      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    CHECK ((dossier = 'ELEVE') = (eleve_id IS NOT NULL)),
    CHECK ((dossier = 'PERSONNEL') = (utilisateur_id IS NOT NULL))
);
CREATE INDEX ix_archive_eleve ON archive_document (tenant_id, eleve_id) WHERE eleve_id IS NOT NULL;
CREATE INDEX ix_archive_personnel ON archive_document (tenant_id, utilisateur_id) WHERE utilisateur_id IS NOT NULL;
SELECT activer_isolation('archive_document');

CREATE TABLE certificat_delivre (
    tenant_id      UUID         NOT NULL REFERENCES tenant (id),
    id             UUID         NOT NULL,
    numero         VARCHAR(20)  NOT NULL,
    inscription_id UUID         NOT NULL,
    eleve          VARCHAR(220) NOT NULL,
    classe         VARCHAR(40)  NOT NULL,
    annee          VARCHAR(9)   NOT NULL,
    delivre_le     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    delivre_par    UUID,
    PRIMARY KEY (tenant_id, id),
    UNIQUE (tenant_id, numero),
    FOREIGN KEY (tenant_id, inscription_id) REFERENCES inscription (tenant_id, id)
);
SELECT activer_isolation('certificat_delivre');
