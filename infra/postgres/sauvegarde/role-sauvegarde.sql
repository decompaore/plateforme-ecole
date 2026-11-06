-- =====================================================================
-- Rôle des sauvegardes (v0.50) – à exécuter UNE FOIS par l'exploitant,
-- en superutilisateur, sur le serveur de base de données :
--   sudo -u postgres psql -v mot_de_passe="'...'" -f role-sauvegarde.sql
-- Lecture seule de toutes les données (pg_read_all_data), sans être
-- soumis au cloisonnement par établissement (BYPASSRLS) : pg_dump doit
-- tout lire. Il ne peut rien modifier.
-- Mot de passe à placer dans ~/.pgpass de l'utilisateur système des
-- sauvegardes (jamais dans le dépôt).
-- =====================================================================
CREATE ROLE plateforme_sauvegarde LOGIN BYPASSRLS PASSWORD :mot_de_passe;
GRANT pg_read_all_data TO plateforme_sauvegarde;
GRANT CONNECT ON DATABASE plateforme TO plateforme_sauvegarde;
