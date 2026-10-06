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

La connexion, `/auth/rafraichir` et `GET /moi` indiquent `motifChangementMotDePasse` : `PROVISOIRE` (nouveau compte ou
réinitialisation par l'administration) ou `RENOUVELLEMENT` (nouvelle période, ci-dessous) ; absent si aucun
changement n'est demandé.

### Renouvellement périodique du mot de passe du personnel (v0.28)

- Concerne tout compte ayant, dans l'établissement de la session, au moins un rôle du personnel (tous sauf `PARENT` et
  `ELEVE`) ; jamais le super administrateur.
- À l'ouverture ou au renouvellement d'une session, si le dernier mot de passe choisi par la personne
  (`mot_de_passe_change_le`) date d'avant le **début de la période en cours** de l'année active de l'établissement,
  un nouveau mot de passe est exigé (`doitChangerMotDePasse=true`, motif `RENOUVELLEMENT`, entrée
  `MOT_DE_PASSE_A_RENOUVELER` au journal d'audit). Le nouveau doit être différent de l'actuel.
- Début de la période en cours : fonction SQL `debut_periode_en_cours(tenant, jour)` (migration V20), la plus récente
  des périodes commencées de l'année active, tous profils confondus (trimestres ou semestres ; dans un établissement
  mixte, la plus récente compte). Sans année active ni période commencée : pas de renouvellement.
- Les mots de passe provisoires ne comptent pas : seule la date d'un mot de passe choisi par la personne est retenue.
  Au déploiement de la v0.28, les mots de passe définitifs existants comptent comme changés le jour de la migration.

## Établissement actif

| Méthode et chemin | Accès | Description |
|---|---|---|
| `GET /api/v1/membres` | ADMIN_ECOLE | Membres de l'établissement |
| `POST /api/v1/membres` | ADMIN_ECOLE | Ajoute un membre (téléphone, nom, prénoms, rôle). Réutilise le compte si le téléphone existe déjà |
| `POST /api/v1/membres/{id}/desactivation` | ADMIN_ECOLE | Désactive une appartenance (le dernier administrateur ne peut pas l'être) |
| `GET /api/v1/audit?page=0&taille=50` | ADMIN_ECOLE | Journal d'audit de l'établissement |
| `GET /api/v1/comptes` | ADMIN_ECOLE | (v0.27) Comptes de l'établissement, un par personne : rôles actifs et retirés, dernière connexion, `verrouilleJusqua` (compte bloqué après 5 essais manqués), `motDePasseProvisoire` (nouveau mot de passe attendu), `moi` ; (v0.28) `motDePasseChangeLe`, `motifChangement` |
| `POST /api/v1/comptes/{utilisateurId}/reinitialisation` | ADMIN_ECOLE | (v0.27) Mot de passe oublié : nouveau mot de passe provisoire renvoyé **une seule fois** (`motDePasseTemporaire`), `autresEtablissements` |
| `POST /api/v1/comptes/{utilisateurId}/deverrouillage` | ADMIN_ECOLE | (v0.27) Lève le blocage sans changer le mot de passe |

### Réinitialisation d'un mot de passe oublié (v0.27)

- Le compte doit avoir un **rôle actif** dans l'établissement (`404` sinon) ; l'administrateur ne réinitialise pas son
  propre compte (`409 MON_PROPRE_COMPTE` : il utilise `POST /moi/mot-de-passe`) ; un compte de la plateforme n'est
  jamais concerné.
- Effets : nouveau mot de passe provisoire (10 caractères sans ambiguïté), `doit_changer_mot_de_passe` remis à vrai
  (la personne doit le changer avant tout, `403 MOT_DE_PASSE_A_CHANGER`), compte déverrouillé, **toutes ses sessions
  fermées** (jetons de rafraîchissement révoqués), entrée `MOT_DE_PASSE_REINITIALISE` au journal d'audit, SMS
  d'information à la personne (sans le mot de passe).
- Une personne n'a qu'un compte sur la plateforme : le nouveau mot de passe vaut pour tous les établissements où elle a
  un rôle (`autresEtablissements` les compte).

## Plateforme

| Méthode et chemin | Accès | Description |
|---|---|---|
| `GET /api/v1/plateforme/etablissements` | SUPER_ADMIN | Liste des établissements |
| `POST /api/v1/plateforme/etablissements` | SUPER_ADMIN | Crée un établissement et son premier administrateur (mot de passe temporaire renvoyé une seule fois) |
| `PATCH /api/v1/plateforme/etablissements/{id}/statut` | SUPER_ADMIN | `ACTIF`, `SUSPENDU` ou `RESILIE` |
| `GET /api/v1/plateforme/etablissements/{id}/administrateurs` | SUPER_ADMIN | (v0.27) Administrateurs actifs de l'établissement |
| `POST /api/v1/plateforme/etablissements/{id}/administrateurs/{utilisateurId}/reinitialisation` | SUPER_ADMIN | (v0.27) Mot de passe oublié par l'administrateur d'un établissement ; mêmes effets que ci-dessus. Seuls les administrateurs (`404` sinon) |

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

## Modules activables (v0.31)

Migration `V22__modules_etablissement.sql`. Le **socle** (classes, élèves, enseignants, appel, notes, bulletins,
comptes) est toujours actif. Les autres modules sont actifs par défaut ; le super administrateur peut en désactiver
pour un établissement. Aucune donnée n'est effacée : un module réactivé retrouve tout.

| Code | Module | Dépend de |
|---|---|---|
| `ATELIERS` | Ateliers et matière d'œuvre (catalogue, équipements, stocks, inventaires, besoins, commandes) | |
| `EMPLOIS_DU_TEMPS` | Grille horaire, emplois du temps, génération, publication | |
| `PROGRESSION` | Fiches de progression et cahier de textes | |
| `VIE_SCOLAIRE` | Incidents, convocations | |
| `SCOLARITE` | Frais, bourses, guichet, reçus, relances | |
| `MOBILE_MONEY` | Paiement des parents par Mobile Money, rapprochement | `SCOLARITE` |
| `ESPACE_PARENT` | Comptes et espace des parents | |

- **Fermeture côté serveur** : chaque module déclare ses chemins d'API (`socle/modules/Module.java`). Pour une
  requête d'un établissement dont le module est désactivé : `403` `{"code":"MODULE_DESACTIVE","module":"ATELIERS"}`.
  Les appels sans établissement (connexion, super administrateur, notifications des opérateurs, vérification des
  reçus) ne sont pas concernés. Le filtre garde la liste 30 secondes en mémoire ; elle est relue après chaque
  modification.
- **Désactiver un module désactive ceux qui en dépendent** (scolarité → Mobile Money).
- **Tâches automatiques** : les relances de scolarité et le rapprochement Mobile Money sautent les établissements
  où le module est désactivé.
- **Application** : `etablissementActif.modulesDesactives` (connexion, choix d'établissement, renouvellement du
  jeton) masque les tuiles, les onglets et les écrans. Un changement est vu par les utilisateurs au prochain
  renouvellement du jeton (15 minutes au plus) ; le serveur l'applique tout de suite.

| Appel | Rôles |
|---|---|
| `GET /api/v1/plateforme/etablissements/{id}/modules` | super administrateur : code, libellé, description, module requis, actif |
| `PUT /api/v1/plateforme/etablissements/{id}/modules` `{"actifs":["EMPLOIS_DU_TEMPS","PROGRESSION","SCOLARITE"]}` | super administrateur ; les modules absents sont désactivés. Journal d'audit `MODULES_ETABLISSEMENT` |

## Appareils connectés et effacement à distance (v0.32)

Migration `V23__sessions_appareils.sql`. Une **session** = un appareil connecté (une famille de jetons de
rafraîchissement), avec son type (« Téléphone Android · Chrome », lu dans l'en-tête `User-Agent`), l'établissement,
la date de connexion et la dernière activité. Un changement d'établissement garde la même session.

| Appel | Rôles |
|---|---|
| `GET /api/v1/auth/appareils` | tout compte connecté : ses appareils (le cookie désigne `courant`) |
| `POST /api/v1/auth/appareils/{id}/fermeture` `{"effacer":true}` | le titulaire du compte : déconnecte un de ses appareils ; `404` pour l'appareil d'un autre compte |
| `POST /api/v1/comptes/{utilisateurId}/appareils/fermeture` `{"effacer":true}` | ADMIN_ECOLE : déconnecte tous les appareils du compte **dans l'établissement** (téléphone perdu, départ) ; renvoie `{"appareils":2}` ; `409 MON_PROPRE_COMPTE` pour soi-même |

- Déconnecter révoque la famille de jetons : l'appareil ne peut plus renouveler sa session. Le jeton d'accès déjà
  émis reste valable au plus 15 minutes.
- **Avec effacement**, le prochain `POST /api/v1/auth/rafraichir` de cet appareil répond `401`
  `{"code":"APPAREIL_A_EFFACER"}` : l'application efface **toutes** ses données locales (listes, saisies non
  envoyées, clés de chiffrement), retire le cookie et affiche « Ce téléphone a été déconnecté à distance ». Journal
  d'audit : `APPAREIL_A_EFFACER`, `APPAREIL_DECONNECTE`, `APPAREILS_A_EFFACER`, `APPAREILS_DECONNECTES`,
  `APPAREIL_EFFACE`.
- La liste des comptes (`GET /api/v1/comptes`) donne `appareilsConnectes` pour l'établissement. La réinitialisation
  d'un mot de passe ferme toutes les sessions du compte.
