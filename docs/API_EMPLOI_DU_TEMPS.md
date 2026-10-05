# Emplois du temps (v0.30)

Module `emploidutemps` : grille horaire de l'année, séances de chaque classe, contrôle des conflits,
couverture des volumes horaires, génération automatique, publication aux enseignants. Migration
`V21__emplois_du_temps.sql`.

## Qui fait quoi

| Rôle | Grille horaire | Place et génère | Publie | Consulte |
|---|---|---|---|---|
| ADMIN_ECOLE | oui | toutes les matières | oui | tout |
| CENSEUR | oui | matières **générales** ; aussi les matières techniques et pratiques **tant qu'aucun chef des travaux n'est en fonction** | oui | tout |
| CHEF_TRAVAUX | non | matières **techniques, pratiques et modules de compétences** | non | tout |
| SURVEILLANT | non | non | non | tout |
| ENSEIGNANT | non | non | non | son emploi du temps, une fois publié |

Le domaine d'une matière vient de son type, comme pour les progressions : `GENERALE` → `GENERAL` ; `TECHNIQUE`,
`PRATIQUE`, `MODULE_COMPETENCES` → `TECHNIQUE`. Une séance d'une matière hors de son domaine ne peut être ni
créée, ni déplacée, ni retirée (`403`) ; `modifiable` le dit pour chaque séance.

## Modèle

- **Créneau** (`creneau_horaire`) : heure de début et de fin (4 h au plus, entre 06h00 et 20h00) et jours où il
  existe (`jours` : 1 = lundi … 7 = dimanche ; mercredi et samedi après-midi libres, par exemple). 16 créneaux au plus,
  sans chevauchement. Les récréations et la pause de midi sont les intervalles entre deux créneaux.
- **Séance** (`seance_emploi`) : classe, matière (du programme de la classe), jour, créneau ; facultatifs : groupe
  (demi-classe, 20 caractères), atelier (ouvert), salle (40 caractères). **L'enseignant n'est pas recopié** : c'est
  celui affecté à la matière dans la classe ; changer l'affectation met l'emploi du temps à jour (et peut faire
  apparaître un conflit, signalé).
- **Publication** (`publication_emploi`) : date de la dernière publication ; tant qu'elle n'existe pas, les
  enseignants ne voient rien.

## Conflits

Refusés à l'enregistrement (`409 CONFLIT_EMPLOI_DU_TEMPS`, message explicite) et listés dans `conflits` s'ils
apparaissent après coup :

| Type | Règle |
|---|---|
| `CLASSE` | une seule séance par case pour une classe ; deux **groupes différents** peuvent avoir cours en même temps |
| `ENSEIGNANT` | un enseignant n'est pas dans deux classes à la même heure |
| `ENSEIGNANT_AILLEURS` | un vacataire n'est pas placé à une heure où il a cours dans un **autre établissement** (chevauchement d'horaires, même si les grilles diffèrent) |
| `ATELIER` | un atelier n'accueille qu'une séance à la fois |

Les heures prises ailleurs viennent de la fonction `occupations_ailleurs(engagements[], debut, fin)` (SECURITY
DEFINER) : seulement le jour et la plage horaire, jamais l'établissement, la classe ni la matière ; seulement pour
les engagements de l'établissement courant et les années (en préparation ou en cours) qui chevauchent la sienne.

## Couverture des volumes

Pour chaque matière d'une classe : `minutesPrevues` = volume hebdomadaire du programme × 60 ; `minutesPlacees` =
séances de toute la classe + (si la classe travaille en groupes) le minimum reçu par chacun de ses groupes.

## Génération automatique

`POST …/generation` place, dans les cases libres, les heures qui manquent aux matières demandées, sans jamais créer
de conflit. Glouton et déterministe :

1. séances pratiques et modules d'abord, en blocs de 2 à 4 heures consécutives (récréation de 20 min au plus entre
   deux heures) dans un **atelier de la filière** de la classe ; puis matières techniques ; puis générales (blocs de
   1 ou 2 heures) ;
2. enseignants les plus chargés d'abord ;
3. une seule séance d'une même matière par jour quand c'est possible, journées équilibrées et sans trous, matières
   générales plutôt le matin.

Ce qui n'a pas trouvé de place est renvoyé dans `manques` (classe, matière, minutes, raison). Avec
`"remplacer":true`, les séances existantes des matières concernées dans les classes concernées sont d'abord
retirées. Les séances de l'autre domaine ne sont jamais touchées. Conseil : le chef des travaux génère d'abord (les
blocs pratiques sont les plus difficiles à placer), puis le censeur.

## Appels

| Appel | Rôles |
|---|---|
| `GET /api/v1/annees/{anneeId}/creneaux` | consultation |
| `PUT /api/v1/annees/{anneeId}/creneaux` `{"creneaux":[{"id":null,"heureDebut":"07:00","heureFin":"08:00","jours":[1,2,3,4,5,6]}]}` | ADMIN_ECOLE, CENSEUR. Remplace la grille ; un créneau gardé (`id`) conserve ses séances même si ses heures changent ; supprimer un créneau, ou un de ses jours, qui porte des séances : `409 CRENEAU_UTILISE` |
| `GET /api/v1/annees/{anneeId}/emploi-du-temps` | consultation : grille, classes (matières, enseignant, minutes prévues et placées, groupes), séances, enseignants, ateliers ouverts, heures prises ailleurs, conflits, droits, date de publication |
| `PUT /api/v1/seances-emploi/{id}` `{"anneeId":"…","classeId":"…","matiereId":"…","jour":2,"creneauId":"…","groupe":null,"atelierId":null,"salle":"Salle 4"}` | ADMIN_ECOLE, CENSEUR, CHEF_TRAVAUX selon le domaine. Crée ou déplace (identifiant choisi par l'application : un renvoi ne crée pas de doublon). Renvoie l'emploi du temps complet |
| `DELETE /api/v1/seances-emploi/{id}` | idem |
| `POST /api/v1/annees/{anneeId}/emploi-du-temps/generation` `{"classes":[],"domaines":[],"remplacer":false}` | ADMIN_ECOLE, CENSEUR, CHEF_TRAVAUX ; `classes` vide = toutes, `domaines` vide = ceux de la personne. `409 GRILLE_VIDE` sans grille |
| `POST …/emploi-du-temps/publication` | ADMIN_ECOLE, CENSEUR ; refusée s'il reste des conflits (`409 CONFLITS_A_REGLER`) ou aucune séance |
| `DELETE …/emploi-du-temps/publication` | ADMIN_ECOLE, CENSEUR |
| `GET …/emploi-du-temps/export?format=xlsx\|pdf[&classe=…\|&enseignant={engagementId}\|&atelier=…]` | consultation. Grille (heures en lignes, jours en colonnes) ; sans filtre, une page par classe avec le volume placé et les matières à compléter |
| `GET /api/v1/espace-enseignant/emploi-du-temps` | ENSEIGNANT : ses séances de l'année active (vide tant que ce n'est pas publié) et ses heures dans d'autres établissements |
| `GET /api/v1/espace-enseignant/emploi-du-temps/export?format=pdf\|xlsx` | ENSEIGNANT ; `409 NON_PUBLIE` avant publication |

Une année close ou archivée ne se modifie plus (`409 ANNEE_FERMEE`). Journal d'audit : `GRILLE_HORAIRE_MODIFIEE`,
`EMPLOI_DU_TEMPS_GENERE`, `EMPLOI_DU_TEMPS_PUBLIE`, `EMPLOI_DU_TEMPS_DEPUBLIE`.
