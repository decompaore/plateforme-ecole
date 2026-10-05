# Plateforme SaaS de gestion des établissements secondaires

Plateforme multi-établissements pour l'enseignement **général, technique et professionnel** (Burkina Faso, puis Afrique francophone).
Architecture : **monolithe modulaire** Spring Boot 4.1 / Java 21, PostgreSQL 16 avec **Row-Level Security**, application web Angular installable sur smartphone (PWA).

Références : *Dossier de cadrage technique v1.6* et *Dossier de conception UML v1.0*.

## Contenu du dépôt

```
plateforme-ecoles/
├── backend/                     API Spring Boot (projet Maven « plateforme-api »)
│   ├── src/main/java/bf/edutech/plateforme/
│   │   ├── socle/               tenant + RLS, sécurité JWT, audit, erreurs, persistance
│   │   ├── utilisateurs/        comptes, connexion, établissements, membres et rôles
│   │   ├── plateforme/          création / suspension des établissements (super admin)
│   │   ├── pedagogie/           profils pédagogiques (général, technique, professionnel)
│   │   └── etablissement/       années scolaires, périodes, filières, classes, matières
│   ├── src/main/resources/
│   │   ├── application*.yml     configuration par profil (dev, test, prod)
│   │   └── db/migration/        migrations Flyway (schéma, RLS, fonctions)
│   ├── src/test/java/           tests unitaires, d'intégration et de modularité
│   ├── eclipse/                 formateur et configurations de lancement Eclipse
│   └── Dockerfile
├── frontend/                    application Angular 22 (PWA) : connexion, appel et notes sans réseau, administration
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

Puis ouvrir http://localhost:8080/swagger-ui.html. Pour l'application web : `cd frontend && npm ci && npm start`, puis http://localhost:4200 (Node.js 24). Compte de développement : téléphone `70000000`, mot de passe `ChangezMoi-Dev-2026` (à changer à la première connexion).

Pour travailler avec Eclipse, suivre [docs/GUIDE_ECLIPSE.md](docs/GUIDE_ECLIPSE.md).

## Documentation

| Document | Contenu |
|---|---|
| [docs/GUIDE_ECLIPSE.md](docs/GUIDE_ECLIPSE.md) | Installation du poste, import, lancement, tests, débogage |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | Profils, variables d'environnement, rôles PostgreSQL, secrets |
| [docs/CI_CD.md](docs/CI_CD.md) | Pipeline GitHub Actions, préparation des serveurs, déploiement, retour arrière |
| [docs/FRONTEND.md](docs/FRONTEND.md) | Application web : démarrage, test sur téléphone, sécurité de la session, appel et notes sans réseau, espace d'administration |
| [docs/API_SOCLE.md](docs/API_SOCLE.md) | Points d'accès du socle et parcours d'authentification |
| [docs/API_ETABLISSEMENT.md](docs/API_ETABLISSEMENT.md) | Profils pédagogiques, années, périodes, filières, classes, matières |
| [docs/API_ELEVES.md](docs/API_ELEVES.md) | Élèves, responsables, inscriptions, réinscriptions, bourses, import Excel, espace parent |
| [docs/API_ENSEIGNANTS.md](docs/API_ENSEIGNANTS.md) | Enseignants titulaires et vacataires, invitations, affectations, charge horaire |
| [docs/API_ABSENCES.md](docs/API_ABSENCES.md) | Appel hors connexion, absences, justificatifs, SMS aux familles |
| [docs/API_EVALUATIONS.md](docs/API_EVALUATIONS.md) | Évaluations, notes, compétences, moyennes, rangs par profil pédagogique |
| [docs/API_BULLETINS.md](docs/API_BULLETINS.md) | Appréciations, conseil de classe, bulletins PDF, publication, espace parent, vérification |
| [docs/API_SCOLARITE.md](docs/API_SCOLARITE.md) | Frais, tranches, bourses, exonérations, encaissements, reçus, retards, relances SMS |
| [docs/API_MOBILE_MONEY.md](docs/API_MOBILE_MONEY.md) | Paiement Mobile Money par les parents, notifications signées, rapprochement quotidien, relances automatiques |
| [docs/API_PASSAGE.md](docs/API_PASSAGE.md) | Moyenne annuelle, décisions de fin d'année, conseil de classe, année suivante, réinscriptions en masse |
| [docs/API_VIE_SCOLAIRE.md](docs/API_VIE_SCOLAIRE.md) | Retards, avertissements, blâmes, exclusions temporaires, convocations, historique, espace parent |
| [docs/API_STATISTIQUES.md](docs/API_STATISTIQUES.md) | Statistiques de rentrée, personnel, recouvrement, résultats, export Excel |
| [docs/API_EMPLOI_DU_TEMPS.md](docs/API_EMPLOI_DU_TEMPS.md) | Grille horaire, emplois du temps (censeur et chef des travaux), conflits, génération automatique, publication, exports |

## Règles de l'équipe

- Branches `feature/...` créées depuis `develop` ; fusion par pull request après CI verte et relecture.
- Aucun secret dans le dépôt : variables d'environnement uniquement.
- Toute table métier porte un `tenant_id`, une politique RLS et des clés étrangères composites.
- Une fonction n'est « terminée » que si elle est testée, documentée et utilisable sur un smartphone d'entrée de gamme.
