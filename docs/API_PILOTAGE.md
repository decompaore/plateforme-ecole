# Pilotage des directions (v0.37)

Chaque direction (régionale, provinciale…) a un ou plusieurs **comptes** qui voient **uniquement des nombres** :
ceux de chaque établissement qui dépend d'elle (à tout niveau) et leurs cumuls par direction. Les données par élève
restent dans les établissements. Le même tableau de bord sert à l'administrateur pays (son pays) et au super
administrateur (tout pays).

## Comptes des directions

Créés par l'administrateur du pays de la direction ou par le super administrateur.

| Méthode | Chemin | Rôle |
|---|---|---|
| `GET` | `/api/v1/plateforme/territoire/directions/{directionId}/comptes` | comptes de la direction |
| `POST` | `/api/v1/plateforme/territoire/directions/{directionId}/comptes` | `{ telephone, nom, prenoms }` → `201 { compte, motDePasseTemporaire }` |
| `POST` | `…/comptes/{utilisateurId}/reinitialisation` | nouveau mot de passe provisoire, sessions fermées |
| `DELETE` | `…/comptes/{utilisateurId}` | droits retirés, sessions fermées (`204`) |

- Compte **dédié** : un compte appartient à une seule direction ; il n'est ni super administrateur, ni administrateur
  pays, ni membre d'un établissement (refus `409` : `COMPTE_PLATEFORME`, `COMPTE_ETABLISSEMENT`,
  `COMPTE_AUTRE_DIRECTION` ; un établissement refuse aussi d'ajouter ce numéro comme membre).
- Le numéro reçoit l'indicatif du pays de la direction ; le mot de passe provisoire s'affiche une seule fois et se
  change à la première connexion.
- Hors du pays de l'administrateur pays : `403`.
- Connexion : session sans établissement ; la réponse porte `direction { id, code, nom, chemin, paysId }` ; le jeton
  porte le rôle `DIRECTION` et `direction_id`. Seul `/api/v1/pilotage/**` lui est ouvert (avec `/moi`, mot de passe,
  appareils).

## Tableau de bord

`GET /api/v1/pilotage?direction=&pays=&annee=` — comptes de direction, administrateurs pays, super administrateur.

- Sans paramètre : la direction du compte, ou le pays de l'administrateur pays (le super administrateur : le pays qui
  a le plus d'établissements, ou `pays=`).
- `direction=` : descendre vers une direction. Un compte de direction ne voit que la sienne et celles qui en dépendent
  (`403` sinon) ; `parent` permet de remonter, jamais au-dessus de sa direction.
- `annee=` : libellé de l'année scolaire (ex. `2025-2026`), **le même dans tous les établissements**. Par défaut :
  l'année active du plus grand nombre d'établissements, sinon la plus récente. Une année inconnue dans ce ressort est
  remplacée par celle par défaut.

Réponse :

| Champ | Contenu |
|---|---|
| `perimetre` | `{ type: PAYS \| DIRECTION, id, nom, niveau, chemin }` |
| `parent` | niveau au-dessus si la personne peut y remonter, sinon `null` |
| `annees`, `annee` | années disponibles (plus récente d'abord) et année affichée |
| `synthese` | indicateurs du périmètre (ci-dessous) |
| `niveauDirections`, `directions[]` | directions du niveau inférieur et leurs indicateurs cumulés |
| `etablissements[]` | `{ id, code, nom, statut, directionId, direction, sousDirectionId, indicateurs }` |

Indicateurs (par établissement, par direction, pour le périmètre) :

| Champ | Source |
|---|---|
| `etablissements`, `classes`, `eleves`, `redoublants`, `niveaux[]` | classes de l'année ; inscriptions actives (garçons, filles) |
| `enseignants`, `titulaires`, `vacataires`, `elevesParEnseignant` | engagements actifs (hommes, femmes) |
| `decides`, `admis`, `tauxAdmission` | décisions de fin d'année **validées** ; admis = passage ou certification |
| `examens[]` | par examen (nom saisi par l'établissement, sans tenir compte de la casse) : candidats (inscrits des classes d'examen), résultats connus, admis, taux = admis / résultats connus, par sexe |
| `periodes[]` | bulletins **publiés** par trimestre, semestre ou module : moyenne des moyennes, part à la moyenne |
| `utilisation` | comptes, actifs sur 30 jours, enseignants actifs, dernière activité |

Les établissements résiliés sont exclus ; les suspendus restent comptés (avec leur statut).

`GET /api/v1/pilotage/export?format=xlsx|pdf&direction=&pays=&annee=` : niveaux, examens, périodes, directions et
établissements, avec l'en-tête officiel (pays, devise, ministère, directions au-dessus) et le nom du périmètre.

## Base de données

Migration `V28__pilotage_directions.sql` : table `administrateur_direction` ; fonctions `SECURITY DEFINER` qui ne
renvoient que des comptages par établissement : `pilotage_annees`, `pilotage_effectifs`, `pilotage_enseignants`,
`pilotage_resultats`, `pilotage_examens`, `pilotage_periodes`. L'application vérifie la portée avant de les appeler.
