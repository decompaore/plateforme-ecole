# Pipeline CI/CD avec GitHub Actions

## Vue d'ensemble

```
 push / pull request                  push sur main (CI verte)           tag vX.Y.Z
        │                                      │                              │
        ▼                                      ▼                              ▼
 ┌──────────────┐    succès    ┌──────────────────────────────┐   ┌──────────────────────────────┐
 │ CI           │ ───────────▶ │ CD : image sha-xxxxxxx        │   │ CD : image vX.Y.Z             │
 │ – compilation│              │ → GHCR                        │   │ → GHCR                        │
 │ – tests + PG │              │ → déploiement RECETTE (auto)  │   │ → approbation manuelle        │
 │ – couverture │              └──────────────────────────────┘   │ → déploiement PRODUCTION      │
 │ – revue dépendances (PR)    │                                   └──────────────────────────────┘
 └──────────────┘
 CodeQL : chaque PR vers main + chaque lundi.   Dependabot : mises à jour chaque lundi.
```

| Fichier | Rôle |
|---|---|
| `.github/workflows/ci.yml` | Compile et teste le backend sur un vrai PostgreSQL 16 (service Docker), publie les rapports ; bloque les dépendances vulnérables dans les pull requests |
| `.github/workflows/cd.yml` | Construit l'image Docker, la publie sur GitHub Container Registry, déploie par SSH |
| `.github/workflows/codeql.yml` | Analyse de sécurité du code Java |
| `.github/dependabot.yml` | Pull requests automatiques de mise à jour (Maven, actions, image Docker) |

## Configuration du dépôt GitHub (une seule fois)

1. **Settings > Branches > Add branch protection rule** pour `main` et `develop` :
   *Require a pull request before merging*, *Require status checks to pass* → cocher **Backend – compilation et tests**.
2. **Settings > Environments** : créer `recette` et `production`.
   Pour `production` : *Required reviewers* (porteur du projet + référent technique) et *Deployment branches and tags* = tags `v*`.
3. Dans **chaque** environnement, ajouter les secrets :

| Secret | Valeur |
|---|---|
| `SSH_HOTE` | Adresse du serveur d'application |
| `SSH_UTILISATEUR` | Compte de déploiement (ex. `deploiement`) |
| `SSH_CLE_PRIVEE` | Clé privée dédiée au déploiement (ed25519) |
| `SSH_EMPREINTE_HOTE` | Empreinte SHA256 de la clé du serveur (voir ci-dessous) |

4. **Settings > Actions > General** : *Workflow permissions* = *Read repository contents* (les workflows demandent eux-mêmes les droits nécessaires).

## Préparation d'un serveur (recette ou production)

```bash
# Sur le serveur (Ubuntu), en administrateur
curl -fsSL https://get.docker.com | sh
adduser --disabled-password deploiement
usermod -aG docker deploiement
mkdir -p /opt/plateforme && chown deploiement: /opt/plateforme

# Clé de déploiement (sur votre poste) : la publique va sur le serveur, la privée dans GitHub
ssh-keygen -t ed25519 -C "deploiement-github" -f cle_deploiement
# ... copier cle_deploiement.pub dans /home/deploiement/.ssh/authorized_keys

# Empreinte de l'hôte à mettre dans SSH_EMPREINTE_HOTE
ssh-keyscan -t ed25519 ADRESSE_DU_SERVEUR | ssh-keygen -lf -
```

Copier dans `/opt/plateforme` : `deploiement/docker-compose.yml`, `deploiement/Caddyfile`, `deploiement/deployer.sh`,
et créer `.env` à partir de `deploiement/.env.exemple` (droits `600`).

Le registre GitHub (GHCR) est privé par défaut : sur le serveur, se connecter une fois avec un jeton personnel en lecture seule
(`docker login ghcr.io -u UTILISATEUR`, droit `read:packages`).

## Déployer, vérifier, revenir en arrière

- **Recette** : fusionner dans `main`. Suivre l'exécution dans l'onglet *Actions*.
- **Production** : `git tag v1.0.0 && git push origin v1.0.0`, puis approuver dans l'onglet *Actions*.
- **Vérifier** : `https://api.plateforme.bf/actuator/...` n'est pas exposé ; sur le serveur, `docker compose ps` doit montrer deux instances `healthy`.
- **Retour arrière** : sur le serveur, `./deployer.sh <tag précédent>` (les images précédentes restent dans GHCR).

Le script déploie une instance à la fois et s'arrête si elle n'est pas saine : l'autre instance continue de servir les utilisateurs.

## Points d'attention

- Les migrations Flyway s'exécutent au démarrage de la première instance. Elles doivent rester **compatibles avec la version précédente** du code
  (migrations en deux temps : ajouter, migrer, puis supprimer dans une version ultérieure), sinon l'instance non encore mise à jour peut échouer.
- Un tag `v*` déclenche le déploiement sans relancer la CI : ne poser un tag que sur un commit de `main` dont la CI est verte.
- Les versions des actions sont mises à jour par Dependabot ; relire ses pull requests chaque semaine.

## Migrations figées (v0.34.1)

Une migration Flyway déjà appliquée ne doit jamais changer : la base refuserait ensuite de démarrer
(« Migration checksum mismatch »). `MigrationsFigeesTest` (sans base de données) calcule l'empreinte de chaque fichier
`V*.sql` comme Flyway et la compare à `backend/src/test/resources/migrations-figees.txt` ; il échoue dès `mvn verify`.

- Fichier modifié : le remettre (`git checkout develop -- <fichier>`), **jamais** `flyway repair` ; une correction
  passe par une nouvelle migration.
- Nouvelle migration : le test affiche la ligne à ajouter à `migrations-figees.txt`.
