# API du socle (v1)

Toutes les réponses d'erreur suivent le format *Problem Details* (RFC 9457) : `title`, `status`, `detail` et, pour les règles de gestion, `code`.

## Authentification

| Méthode et chemin | Accès | Description |
|---|---|---|
| `POST /api/v1/auth/connexion` | public | Téléphone + mot de passe. Pose le cookie de rafraîchissement si un seul établissement ; sinon renvoie `selectionRequise=true` et un `jetonSelection` (5 min) |
| `POST /api/v1/auth/etablissement` | jeton de sélection ou d'accès | Choisit (ou change) l'établissement actif ; renvoie un jeton d'accès pour cet établissement |
| `POST /api/v1/auth/rafraichir` | cookie | Nouveau jeton d'accès + rotation du cookie. Réutiliser un ancien cookie révoque toute la session |
| `POST /api/v1/auth/deconnexion` | cookie | Révoque la session et efface le cookie |

## Compte connecté

| Méthode et chemin | Accès | Description |
|---|---|---|
| `GET /api/v1/moi` | connecté | Profil, établissement actif, rôles |
| `GET /api/v1/moi/etablissements` | connecté | Établissements accessibles (sélecteur) |
| `POST /api/v1/moi/mot-de-passe` | connecté | Changement du mot de passe, puis appeler `/auth/rafraichir` |

Tant que le mot de passe temporaire n'est pas changé, toute autre requête reçoit `403` avec le code `MOT_DE_PASSE_A_CHANGER`.

## Établissement actif

| Méthode et chemin | Accès | Description |
|---|---|---|
| `GET /api/v1/membres` | ADMIN_ECOLE | Membres de l'établissement |
| `POST /api/v1/membres` | ADMIN_ECOLE | Ajoute un membre (téléphone, nom, prénoms, rôle). Réutilise le compte si le téléphone existe déjà |
| `POST /api/v1/membres/{id}/desactivation` | ADMIN_ECOLE | Désactive une appartenance (le dernier administrateur ne peut pas l'être) |
| `GET /api/v1/audit?page=0&taille=50` | ADMIN_ECOLE | Journal d'audit de l'établissement |

## Plateforme

| Méthode et chemin | Accès | Description |
|---|---|---|
| `GET /api/v1/plateforme/etablissements` | SUPER_ADMIN | Liste des établissements |
| `POST /api/v1/plateforme/etablissements` | SUPER_ADMIN | Crée un établissement et son premier administrateur (mot de passe temporaire renvoyé une seule fois) |
| `PATCH /api/v1/plateforme/etablissements/{id}/statut` | SUPER_ADMIN | `ACTIF`, `SUSPENDU` ou `RESILIE` |

## Parcours : enseignant dans deux établissements

```
POST /auth/connexion        → selectionRequise=true, etablissements=[Lycée A, CFP B], jetonSelection
POST /auth/etablissement    (Bearer jetonSelection, {"etablissementId": A}) → jetonAcces (tenant A) + cookie
...travail dans le Lycée A...
POST /auth/etablissement    (Bearer jetonAcces, {"etablissementId": B}) → jetonAcces (tenant B), ancien cookie révoqué
```

## Contenu du jeton d'accès

| Revendication | Exemple | Usage |
|---|---|---|
| `sub` | UUID du compte | Identité |
| `tenant_id`, `tenant_code` | UUID, `lycee-a` | Établissement actif (seule source du cloisonnement) |
| `roles` | `["ENSEIGNANT"]` | Autorisations (`ROLE_ENSEIGNANT`) |
| `typ_jeton` | `ACCES` ou `SELECTION` | Type de jeton |
| `mdp_a_changer` | `true` | Présent tant que le mot de passe est temporaire |
