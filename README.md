# Plateforme SaaS de gestion des établissements secondaires

Plateforme multi-établissements pour l'enseignement **général, technique et professionnel** (Burkina Faso, puis Afrique francophone).
Architecture : **monolithe modulaire** Spring Boot 4.1 / Java 21, PostgreSQL 16 avec **Row-Level Security**, frontend Angular (à venir).

Références : *Dossier de cadrage technique v1.6* et *Dossier de conception UML v1.0*.

## Contenu du dépôt

```
plateforme-ecoles/
├── backend/                     API Spring Boot (projet Maven « plateforme-api »)
│   ├── src/main/java/bf/edutech/plateforme/
│   │   ├── socle/               tenant + RLS, sécurité JWT, audit, erreurs, persistance
│   │   ├── utilisateurs/        comptes, connexion, établissements, membres et rôles
│   │   └── plateforme/          création / suspension des établissements (super admin)
│   ├── src/main/resources/
│   │   ├── application*.yml     configuration par profil (dev, test, prod)
│   │   └── db/migration/        migrations Flyway (schéma, RLS, fonctions)
│   ├── src/test/java/           tests unitaires, d'intégration et de modularité
│   ├── eclipse/                 formateur et configurations de lancement Eclipse
│   └── Dockerfile
├── infra/postgres/init/         rôles et bases pour le développement et la CI
├── deploiement/                 compose, Caddy, .env modèle, script de déploiement (serveur)
├── .github/                     workflows CI, CD, CodeQL et Dependabot
├── docs/                        guides (Eclipse, configuration, CI/CD, API)
└── docker-compose.yml           PostgreSQL local pour le développement
```

## Démarrage rapide (5 minutes)

```bash
docker compose up -d                                  # 1. base PostgreSQL locale
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev   # 2. API (ou depuis Eclipse)
```

Puis ouvrir http://localhost:8080/swagger-ui.html. Compte de développement : téléphone `70000000`, mot de passe `ChangezMoi-Dev-2026` (à changer à la première connexion).

Pour travailler avec Eclipse, suivre [docs/GUIDE_ECLIPSE.md](docs/GUIDE_ECLIPSE.md).

## Documentation

| Document | Contenu |
|---|---|
| [docs/GUIDE_ECLIPSE.md](docs/GUIDE_ECLIPSE.md) | Installation du poste, import, lancement, tests, débogage |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | Profils, variables d'environnement, rôles PostgreSQL, secrets |
| [docs/CI_CD.md](docs/CI_CD.md) | Pipeline GitHub Actions, préparation des serveurs, déploiement, retour arrière |
| [docs/API_SOCLE.md](docs/API_SOCLE.md) | Points d'accès du socle et parcours d'authentification |

## Règles de l'équipe

- Branches `feature/...` créées depuis `develop` ; fusion par pull request après CI verte et relecture.
- Aucun secret dans le dépôt : variables d'environnement uniquement.
- Toute table métier porte un `tenant_id`, une politique RLS et des clés étrangères composites.
- Une fonction n'est « terminée » que si elle est testée, documentée et utilisable sur un smartphone d'entrée de gamme.
