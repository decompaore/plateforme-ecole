# Bilan des heures dues et réalisées (v0.50)

Pour chaque matière ou module de chaque classe, le bilan donne :

| Grandeur | D'où elle vient |
|---|---|
| **Dû annuel** | Volume horaire annuel fixé par le référentiel, saisi dans le programme de la classe (« H / an ») |
| **Dû à ce jour** | Dû annuel × jours de cours écoulés ÷ jours de cours de l'année |
| **Réalisé** | Heures des séances notées au cahier de textes (fin − début) |
| **Reste** | Dû annuel − réalisé, jamais négatif |
| **Taux** | Réalisé ÷ dû annuel ; « à date » : réalisé ÷ dû à ce jour |
| **Écart** | Réalisé − dû à ce jour (négatif : retard) |

Les cumuls sont donnés par **enseignant**, par **matière** (discipline, toutes classes) et par **classe**.

**Jours de cours.** Ils se comptent sur les périodes (trimestres, semestres ou modules) du profil pédagogique de
la classe. Les vacances placées entre deux périodes ne comptent donc pas. Sans périodes, l'année scolaire fait
foi.

**Volume estimé.** Si le volume annuel n'est pas saisi mais que le volume hebdomadaire l'est, le volume annuel est
estimé : heures par semaine × semaines de cours. La ligne porte `sourceVolume: ESTIME`. Sans aucun volume, elle
porte `AUCUN` et l'état `SANS_VOLUME`.

**États d'une ligne.**

| État | Condition |
|---|---|
| `TERMINE` | Réalisé ≥ dû annuel |
| `EN_RETARD` | Réalisé < 90 % du dû à ce jour, ou rien de fait alors que du temps est dû |
| `A_JOUR` | Entre 90 et 110 % du dû à ce jour |
| `EN_AVANCE` | Au-delà de 110 % |
| `PAS_COMMENCE` | Rien n'est dû encore |
| `SANS_VOLUME` | Aucun volume saisi |

**Changement d'enseignant en cours d'année.** Les heures de chaque enseignant sont gardées : la ligne indique les
`intervenants`.
- Le cumul d'un enseignant compte le dû des matières qui lui sont confiées aujourd'hui.
- Il compte aussi toutes les heures qu'il a faites dans l'année. C'est la base du futur calcul des vacations.

| Méthode | Chemin | Rôles | Effet |
|---|---|---|---|
| GET | `/api/v1/annees/{anneeId}/bilan-heures` | ADMIN_ECOLE, CENSEUR (matières générales ; tout sans chef des travaux), CHEF_TRAVAUX (techniques et pratiques) | Lignes, cumuls par enseignant, matière et classe, total, nombre de matières sans volume, sans enseignant, en retard |
| GET | `/api/v1/annees/{anneeId}/bilan-heures/export?format=xlsx\|pdf` | idem | Classeur ou PDF : feuilles « Par enseignant », « Détail », « Par matière », « Par classe » |
| GET | `/api/v1/espace-enseignant/bilan-heures` | ENSEIGNANT | Ses matières de l'année active (et celles où il a fait des heures avant un changement d'enseignant) |
| GET | `/api/v1/espace-enseignant/bilan-heures/export?format=` | ENSEIGNANT | Idem, en fichier |
| PUT | `/api/v1/annees/{anneeId}/volumes-annuels` | ADMIN_ECOLE, CENSEUR | `{ filiereId, niveau, matiereId, volumeAnnuel }` : reporte le volume du référentiel sur toutes les classes de la filière et du niveau (facultatif) qui ont la matière. Renvoie `{ classes }`, le nombre de classes mises à jour |

Le volume annuel se saisit aussi classe par classe :
- champ `volumeTotal` de `PUT /api/v1/classes/{id}/matieres/{matiereId}` ;
- facultatif, sauf pour les profils organisés en modules, où il donne la durée du module.

## Application web

- **Direction** : tuile « Bilan des heures » (`/progression/heures`), aussi accessible depuis « Progressions ».
  - Chiffres clés.
  - Regroupement par enseignant, classe ou matière, puis détail.
  - Jauge du réalisé avec le repère du dû à ce jour.
  - Exports Excel et PDF.
- **Enseignant** : tuile « Mes heures » (`/progression/mes-heures`).
- **Programme d'une classe** (`/admin/classes/{id}`) :
  - colonne « H / an » pour tous les profils ;
  - bouton « ⇉ » pour reporter le volume sur les classes de la même filière et du même niveau.
