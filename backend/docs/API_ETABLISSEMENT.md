# Domaine Établissement (v0.2)

Modules `pedagogie` (profils pédagogiques) et `etablissement` (années, périodes, filières, classes, matières).
Toutes les données sont cloisonnées par établissement (Row-Level Security, migration `V3__structure_pedagogique.sql`).

## Mise en place d'une école en 7 appels

| # | Appel | Rôle |
|---|---|---|
| 1 | `POST /api/v1/profils/initialisation` : crée les profils GENERAL, TECHNIQUE, PROFESSIONNEL | ADMIN_ECOLE |
| 2 | `POST /api/v1/annees` `{"libelle":"2026-2027","debut":"2026-10-01","fin":"2027-07-31"}` | ADMIN_ECOLE |
| 3 | `POST /api/v1/filieres` `{"code":"D","libelle":"Série D","cycle":"Second cycle","diplomeVise":"BAC D","profilId":"…"}` | ADMIN_ECOLE, CENSEUR |
| 4 | `POST /api/v1/matieres` `{"code":"MATH","libelle":"Mathématiques","type":"GENERALE"}` | ADMIN_ECOLE, CENSEUR |
| 5 | `POST /api/v1/annees/{id}/classes` `{"filiereId":"…","code":"Tle D","niveau":"Terminale","effectifMax":60}` | ADMIN_ECOLE, CENSEUR |
| 6 | `PUT /api/v1/classes/{id}/matieres/{matiereId}` `{"coefficient":4}` | ADMIN_ECOLE, CENSEUR |
| 7 | `POST /api/v1/annees/{id}/periodes/generation` `{"profilId":"…"}` puis `POST /api/v1/annees/{id}/ouverture` | ADMIN_ECOLE (CENSEUR pour les périodes) |

La lecture (`GET`) est ouverte à tous les membres de l'établissement.

## Points d'accès

| Ressource | Méthodes |
|---|---|
| Profils | `GET /api/v1/profils`, `GET /{id}`, `POST`, `PUT /{id}`, `PATCH /{id}/activation?actif=`, `POST /initialisation` |
| Années | `GET /api/v1/annees`, `GET /active`, `GET /{id}`, `POST`, `POST /{id}/ouverture`, `/cloture`, `/archivage`, `/copie` |
| Périodes | `GET /api/v1/annees/{id}/periodes`, `POST /api/v1/annees/{id}/periodes/generation`, `POST /api/v1/annees/{id}/periodes`, `PUT /api/v1/periodes/{id}`, `DELETE /api/v1/periodes/{id}`, `POST /api/v1/periodes/{id}/verrouillage`, `/deverrouillage` |
| Filières | `GET /api/v1/filieres`, `POST`, `PUT /{id}` |
| Matières | `GET /api/v1/matieres`, `POST`, `PUT /{id}` (désactivation plutôt que suppression) |
| Classes | `GET /api/v1/annees/{id}/classes`, `POST /api/v1/annees/{id}/classes`, `GET/PUT/DELETE /api/v1/classes/{id}` |
| Matières d'une classe | `GET /api/v1/classes/{id}/matieres`, `PUT /api/v1/classes/{id}/matieres/{matiereId}`, `DELETE …` |

## Règles de gestion appliquées

| Code d'erreur (409) | Règle |
|---|---|
| `ANNEE_ACTIVE_EXISTE` | Une seule année active par établissement (garanti aussi par un index unique) |
| `AUCUNE_CLASSE`, `PERIODES_MANQUANTES` | Ouverture : au moins une classe, et des périodes pour chaque profil utilisé par les classes |
| `PERIODES_NON_VERROUILLEES` | Clôture : toutes les périodes doivent être verrouillées |
| `TRANSITION_INVALIDE` | Préparation → active → clôturée → archivée, dans cet ordre |
| `ANNEE_FIGEE` | Une année clôturée ou archivée ne se modifie plus |
| `ANNEE_EN_COURS` | Supprimer une classe ou changer sa filière : seulement pendant la préparation |
| `CHEVAUCHEMENT`, `HORS_ANNEE` | Périodes d'un même profil sans chevauchement, comprises dans l'année (contrainte d'exclusion en base) |
| `DECOUPAGE_MANUEL` | Profil organisé en modules : périodes saisies une à une |
| `PERIODE_VERROUILLEE` | Une période verrouillée ne se modifie ni ne se supprime (déverrouillage exceptionnel journalisé) |
| `GROUPE_OBLIGATOIRE` | Profil « notes par groupes » (technique) : chaque matière d'une classe a un groupe |
| `VOLUME_TOTAL_OBLIGATOIRE` | Profil en modules (professionnel) : chaque module a une durée totale en heures |
| `PROFIL_UTILISE`, `FILIERE_UTILISEE` | Le modèle d'évaluation d'un profil, ou le profil d'une filière, ne change plus une fois utilisé |

## Copie vers l'année suivante

`POST /api/v1/annees/{id}/copie` avec le libellé et les dates de la nouvelle année : crée l'année en préparation et copie
les classes, leurs matières et coefficients, et les périodes décalées d'un an. C'est la première brique du passage d'année ;
les réinscriptions viendront avec le module Élèves.

## Choix de conception

- Les règles d'un profil (seuil d'admission, arrondi, note éliminatoire, seuil de maîtrise) et son vocabulaire sont des
  **colonnes typées** plutôt qu'un document JSON libre : validation par la base, lisibilité, pas de dépendance à Jackson.
  Un champ JSON pourra s'ajouter si des règles vraiment spécifiques apparaissent.
- Les modules communiquent par des **vues publiques** (`ProfilVue`) et des services, jamais par les entités d'un autre module.
- La fonction SQL `activer_isolation(table)` active la RLS en une ligne : à utiliser dans toutes les migrations suivantes.
