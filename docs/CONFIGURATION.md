# Configuration de l'API

## Profils Spring

| Profil | Usage | Particularités |
|---|---|---|
| `dev` | Poste développeur (Eclipse) | Base Docker locale, Swagger activé, cookie non sécurisé (HTTP), secret JWT de développement, super admin `70000000` |
| `test` | Tests automatisés (Eclipse, Maven, GitHub Actions) | Base `plateforme_test`, surchargeable par variables `TEST_*` |
| `prod` | Recette et production | Tout vient des variables d'environnement ; supervision sur le port interne 8081 ; journaux JSON |

Le fichier `application.yml` contient la configuration commune. Les valeurs `${VARIABLE}` sans valeur par défaut sont **obligatoires** : l'application refuse de démarrer si elles manquent.

## Variables d'environnement (profil prod)

| Variable | Obligatoire | Description |
|---|---|---|
| `DB_URL` | oui | URL JDBC, ex. `jdbc:postgresql://10.0.0.2:5432/plateforme?sslmode=require` |
| `DB_USER`, `DB_PASSWORD` | oui | Rôle applicatif (**sans** BYPASSRLS, non propriétaire des tables) |
| `FLYWAY_USER`, `FLYWAY_PASSWORD` | oui | Rôle propriétaire du schéma (migrations) |
| `JWT_SECRET` | oui | Clé HMAC en Base64, 32 octets minimum : `openssl rand -base64 48` |
| `JWT_EMETTEUR` | non | Valeur « iss » des jetons (défaut `https://api.plateforme.bf`) |
| `ORIGINES_AUTORISEES` | non | Origines CORS de l'application Angular, ex. `https://*.plateforme.bf` |
| `VERIFIER_SOUS_DOMAINE` | non | `true` pour exiger que le sous-domaine corresponde à l'établissement du jeton |
| `DB_POOL_MAX` | non | Taille du groupe de connexions (défaut 20) |
| `SUPER_ADMIN_TELEPHONE`, `SUPER_ADMIN_MOT_DE_PASSE` | première installation | Création du premier super administrateur (12 caractères minimum), à retirer ensuite |
| `MOBILE_MONEY_CLE_CHIFFREMENT` | pour Mobile Money | Clé AES-256 qui chiffre en base les clés des agrégateurs : `openssl rand -base64 32`. Vide : paiement en ligne inactif. **À conserver précieusement** : si elle change, chaque école doit ressaisir ses clés |
| `EXPORTS_REPERTOIRE` | non | Dossier des archives d'export complet (défaut dans l'image : `/var/lib/plateforme/exports`, volume `exports` **partagé par les deux instances**). Voir [API_REVERSIBILITE.md](API_REVERSIBILITE.md) |
| `RELANCES_AUTOMATIQUES` | non | `false` pour couper les relances SMS automatiques du lundi sur tout le serveur (défaut `true`) |

## Rôles PostgreSQL en production

À créer par l'exploitant (superutilisateur), avec des mots de passe forts :

```sql
CREATE ROLE plateforme_owner LOGIN PASSWORD '...' BYPASSRLS;   -- migrations
CREATE ROLE app_plateforme   LOGIN PASSWORD '...' NOBYPASSRLS; -- application
CREATE DATABASE plateforme OWNER plateforme_owner;
```

Pourquoi deux rôles ? Le propriétaire des tables contourne la Row-Level Security ; l'application doit donc se connecter avec un autre rôle pour que le cloisonnement entre établissements s'applique toujours, même en cas d'oubli dans le code.

## Paramètres de sécurité modifiables (`app.securite`)

| Clé | Défaut | Effet |
|---|---|---|
| `duree-jeton-acces` | 15m | Durée de vie du jeton d'accès |
| `duree-jeton-selection` | 5m | Délai pour choisir l'établissement après connexion |
| `duree-jeton-rafraichissement` | 30d | Durée d'une session sans nouvelle connexion |
| `max-echecs-connexion` | 5 | Échecs avant verrouillage |
| `duree-verrouillage` | 15m | Durée du verrouillage |

## Tâches planifiées

| Tâche | Quand | Réglage |
|---|---|---|
| Envoi des SMS | toutes les 10 s | `app.notifications.intervalle-ms` |
| Transactions Mobile Money sans confirmation | chaque minute | `app.mobile-money.intervalle-expiration-ms`, `duree-validite` (15 min) |
| Rapprochement Mobile Money de la veille | 2 h 30 (heure de Ouagadougou) | `app.mobile-money.cron-rapprochement` |
| Relances des familles en retard | lundi 7 h 30 | `app.scolarite.cron-relances` ; chaque école peut les désactiver |
| Clôture des engagements échus (fins programmées, contrats de vacataires) | chaque nuit à 0 h 15 | `app.enseignants.cron-fins` |
| Effacement des archives d'export expirées (7 jours) | chaque heure | `app.exports.duree-conservation`, `purge-automatique` |

Chaque tâche est idempotente : plusieurs instances de l'API peuvent tourner sans doublon.
L'agrégateur `SIMULATEUR` n'est accepté que si `app.mobile-money.simulateur-autorise=true` (profils `dev` et `test`) :
en production il est refusé.

## Choix de Spring Boot 4.1

La branche 3.x n'est plus maintenue depuis le 30 juin 2026. Spring Boot 4 impose quelques changements, déjà appliqués :
`spring-boot-starter-webmvc` (au lieu de `-web`), `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-flyway`
(obligatoire en plus de `flyway-database-postgresql`), `spring-boot-starter-security-test`, Jackson 3, et `@MockitoBean` au lieu de `@MockBean` dans les tests.

## Session : réponse de renouvellement perdue (v0.18)

`app.securite.grace-rafraichissement` (20 s par défaut). Sur un réseau faible, la réponse d'un renouvellement de
session peut se perdre : le téléphone renvoie alors l'ancien cookie. Pendant ce délai, et tant que le cookie émis
entre-temps n'a jamais servi, le serveur l'accepte (journal `RENOUVELLEMENT_REJOUE`) au lieu de fermer la session
pour vol. Un ancien cookie rejoué plus tard, ou après que son successeur a servi, ferme toujours la session
(journal `REUTILISATION_JETON`). Migration `V14__grace_rafraichissement.sql`.
