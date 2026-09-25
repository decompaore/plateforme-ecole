# Guide du poste de développement avec Eclipse

## 1. Logiciels à installer

| Logiciel | Version | Rôle |
|---|---|---|
| JDK Eclipse Temurin | 21 (LTS) | Compilation et exécution |
| Eclipse IDE for Enterprise Java and Web Developers | dernière version publiée | Environnement de développement (inclut m2e pour Maven et EGit pour Git) |
| Docker Desktop (ou Docker Engine sous Linux) | récente | Base PostgreSQL 16 locale |
| Git | récente | Gestion de versions |
| DBeaver (facultatif) | récente | Consultation de la base |

Maven n'a pas besoin d'être installé séparément : Eclipse embarque le sien (m2e).

### Extensions Eclipse (Help > Eclipse Marketplace)

- **Spring Tools** (dernière version) : tableau de bord Spring Boot, complétion dans les fichiers `application*.yml`.
- **EditorConfig Eclipse** : applique les règles du fichier `.editorconfig`.

Le projet **n'utilise pas Lombok** (les DTO sont des *records* Java) : aucun agent à installer dans Eclipse.

## 2. Réglages de l'espace de travail (une seule fois)

1. **Window > Preferences > Java > Installed JREs** : *Add...* > *Standard VM* > dossier du JDK 21 ; cocher ce JDK comme défaut.
2. **Java > Compiler** : *Compiler compliance level* = **21**.
3. **General > Workspace** : *Text file encoding* = **UTF-8**, *New text file line delimiter* = **Unix**.
4. **Java > Code Style > Formatter** : *Import...* > `backend/eclipse/plateforme-formateur.xml`.
5. **Java > Editor > Save Actions** : cocher *Format source code (Edited lines)* et *Organize imports*.
6. **Maven** : cocher *Download Artifact Sources* (utile pour lire le code de Spring en débogage).

## 3. Récupérer et importer le projet

```bash
git clone https://github.com/VOTRE_ORGANISATION/plateforme-ecoles.git
cd plateforme-ecoles
git checkout develop
```

Dans Eclipse : **File > Import... > Maven > Existing Maven Projects** > *Root Directory* = dossier `backend` > le projet **plateforme-api** apparaît > *Finish*.

Au premier import, m2e télécharge les dépendances (quelques minutes). Ensuite : clic droit sur le projet > **Maven > Update Project...** (Alt+F5) après toute modification du `pom.xml`.

Pour que EGit suive le dépôt : clic droit sur le projet > **Team > Share Project...** > Git (le dépôt existant est détecté).

## 4. Démarrer la base de données

À la racine du dépôt :

```bash
docker compose up -d
docker compose ps          # l'état doit être « healthy »
```

Au premier démarrage, le script `infra/postgres/init/01-roles-et-bases.sql` crée :

| Élément | Rôle |
|---|---|
| `plateforme_owner` / `owner_dev` | Propriétaire du schéma, exécute les migrations Flyway (BYPASSRLS) |
| `app_plateforme` / `app_dev` | Rôle de l'application, **soumis à la Row-Level Security** |
| bases `plateforme` et `plateforme_test` | Développement et tests automatisés |

> Ces mots de passe sont réservés au poste de développement.

## 5. Lancer l'API

**Méthode 1 – configuration partagée** : **Run > Run Configurations... > Java Application > PlateformeApi-dev** > *Run*.

**Méthode 2 – Spring Tools** : dans la vue **Boot Dashboard**, sélectionner `plateforme-api`, ouvrir sa configuration et renseigner *Profile* = `dev`, puis démarrer.

Au démarrage, Flyway applique les migrations, puis le super administrateur de développement est créé. Vérifier :

- http://localhost:8080/actuator/health → `{"status":"UP"}`
- http://localhost:8080/swagger-ui.html → documentation interactive de l'API

## 6. Premier parcours dans Swagger

1. `POST /api/v1/auth/connexion` avec `{"telephone":"70000000","motDePasse":"ChangezMoi-Dev-2026"}` : copier `jetonAcces`.
2. Bouton **Authorize** : coller le jeton.
3. `POST /api/v1/moi/mot-de-passe` : définir un nouveau mot de passe.
4. `POST /api/v1/auth/rafraichir` (le cookie est envoyé automatiquement) : nouveau jeton, à recoller dans *Authorize*.
5. `POST /api/v1/plateforme/etablissements` : créer un établissement et son administrateur (le mot de passe temporaire est renvoyé une seule fois).

## 7. Exécuter les tests

- **Tous les tests** : **Run > Run Configurations... > JUnit > PlateformeApi-tests**, ou clic droit sur `src/test/java` > **Run As > JUnit Test**.
- Les tests d'intégration utilisent la base `plateforme_test` (profil `test`) : la base Docker doit être démarrée.
- `ModulariteTest` vérifie qu'aucun module n'utilise les éléments internes d'un autre.
- En ligne de commande (identique à la CI) : `cd backend && mvn verify`. Rapport de couverture : `backend/target/site/jacoco/index.html`.

## 8. Déboguer

- Clic droit sur `PlateformeApplication` > **Debug As > Java Application** (ou *Debug* dans le Boot Dashboard), avec le profil `dev`.
- Pour observer le cloisonnement : point d'arrêt dans `TenantAwareJpaTransactionManager.doBegin`.
- Pour voir la RLS en action dans DBeaver : se connecter avec `app_plateforme` puis exécuter
  `select set_config('app.tenant_id', '<uuid>', false); select * from membre_etablissement;`

## 9. Problèmes fréquents

| Symptôme | Cause et solution |
|---|---|
| `Could not resolve placeholder 'JWT_SECRET'` | Le profil `dev` n'est pas actif : vérifier `-Dspring.profiles.active=dev` |
| `Connection refused` sur le port 5432 | La base n'est pas démarrée : `docker compose up -d` |
| `password authentication failed` | Le script d'initialisation ne s'exécute qu'à la création du volume : `docker compose down -v` puis `up -d` |
| Erreurs de compilation après une mise à jour du `pom.xml` | **Maven > Update Project...** (Alt+F5), cocher *Force Update* |
| Une liste vide alors que les données existent | Normal sans établissement actif : la RLS filtre tout. Vérifier le jeton (champ `tenant_id`) |
| `Mot de passe à changer` (403) | Changer le mot de passe temporaire puis appeler `/api/v1/auth/rafraichir` |

## 10. Organisation Git

```
feature/xxx  ──PR──▶  develop  ──PR──▶  main  ──tag vX.Y.Z──▶  production
                         │               │
                      CI (tests)   CI + déploiement en recette
```

1. Créer une branche depuis `develop` : `feature/eleves-inscription`.
2. Commits réguliers, messages explicites.
3. Pull request vers `develop` : la CI doit être verte et un collègue relit le code.
4. Fusion de `develop` dans `main` : déploiement automatique en **recette**.
5. Tag `vX.Y.Z` sur `main` : déploiement en **production** après approbation.
