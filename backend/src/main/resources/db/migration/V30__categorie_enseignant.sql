-- =====================================================================
-- V30 : catégorie de l'enseignant dans l'établissement
--
-- GENERAL : enseignement général ; TECHNIQUE : enseignement technique et
-- professionnel. Choisie par l'administration à l'engagement (modifiable). Seul un
-- enseignant TECHNIQUE peut être rattaché à un atelier (responsable, enseignant de
-- l'atelier) et voir les ateliers.
-- Reprise : les engagements qui assurent déjà une matière technique, pratique ou un
-- module de compétences deviennent TECHNIQUE.
-- =====================================================================

ALTER TABLE engagement_enseignant
    ADD COLUMN categorie VARCHAR(9) NOT NULL DEFAULT 'GENERAL' CHECK (categorie IN ('GENERAL', 'TECHNIQUE'));

UPDATE engagement_enseignant g SET categorie = 'TECHNIQUE'
 WHERE EXISTS (SELECT 1 FROM classe_matiere cm
                 JOIN matiere m ON m.tenant_id = cm.tenant_id AND m.id = cm.matiere_id
                WHERE cm.tenant_id = g.tenant_id AND cm.engagement_id = g.id AND m.type <> 'GENERALE')
    OR EXISTS (SELECT 1 FROM mandat_responsable r WHERE r.tenant_id = g.tenant_id AND r.engagement_id = g.id);
