-- =====================================================================
-- V15 : Telecel Money, troisième opérateur Mobile Money du Burkina Faso
--
-- Ajouté à la liste des opérateurs (transactions Mobile Money) et des moyens
-- de paiement (encaissements, reçus). Les colonnes passent de 12 à 20
-- caractères : « TELECEL_MONEY » en compte 13.
-- =====================================================================

-- Les contraintes CHECK d'origine n'ont pas de nom choisi : on les retrouve par leur colonne
DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN
        SELECT con.conrelid::regclass AS table_nom, con.conname
        FROM pg_constraint con
        JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey)
        WHERE con.contype = 'c'
          AND ((con.conrelid = 'paiement'::regclass AND att.attname = 'moyen')
            OR (con.conrelid = 'transaction_mobile_money'::regclass AND att.attname = 'operateur'))
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', c.table_nom, c.conname);
    END LOOP;
END $$;

ALTER TABLE paiement ALTER COLUMN moyen TYPE VARCHAR(20);
ALTER TABLE paiement ADD CONSTRAINT paiement_moyen_valide
    CHECK (moyen IN ('ESPECES', 'ORANGE_MONEY', 'MOOV_MONEY', 'TELECEL_MONEY', 'VIREMENT', 'CHEQUE'));

ALTER TABLE transaction_mobile_money ALTER COLUMN operateur TYPE VARCHAR(20);
ALTER TABLE transaction_mobile_money ADD CONSTRAINT transaction_operateur_valide
    CHECK (operateur IN ('ORANGE_MONEY', 'MOOV_MONEY', 'TELECEL_MONEY'));
