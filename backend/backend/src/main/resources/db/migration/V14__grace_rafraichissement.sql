-- =====================================================================
-- V14 : tolérance au renouvellement de session perdu (réseau faible)
--
-- Chaque rotation note le jeton émis en échange. Si la réponse se perd, le
-- téléphone renvoie l'ancien cookie : pendant quelques secondes, et tant que
-- le successeur n'a jamais servi, le serveur l'accepte au lieu de conclure à
-- un vol (voir AuthService.rafraichir).
-- =====================================================================

ALTER TABLE jeton_rafraichissement ADD COLUMN remplace_par UUID;
