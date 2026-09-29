# Domaine Enseignants (v0.4)

Module `enseignants` : identité de l'enseignant (une seule sur la plateforme), engagements de titulaire ou de vacataire
par établissement, invitation avec consentement, affectation aux matières des classes, charge horaire, espace enseignant.
Migration `V5__enseignants.sql`.

## Règle de gestion et garanties

**Un enseignant est titulaire dans un seul établissement à la fois, et vacataire dans un ou plusieurs autres.**

| Élément | Portée | Garantie |
|---|---|---|
| `enseignant` (identité) | Plateforme : une fiche par personne, liée à son compte | Téléphone et matricule de la fonction publique uniques ; visible seulement par les écoles où il est engagé ou invité (RLS) |
| `engagement_enseignant` | Établissement (cloisonné) | `un_seul_poste_titulaire` : deux postes de titulaire ne se chevauchent jamais, **toutes écoles confondues** ; `un_engagement_par_ecole` : un seul engagement à la fois par école |
| `classe_matiere.engagement_id` | Établissement | L'enseignant affecté est forcément engagé **dans cette école** (clé étrangère composite) |

Les contraintes portent sur toutes les lignes, y compris celles des autres écoles, que la Row-Level Security rend
invisibles : la règle est respectée **sans qu'aucune école ne voie où l'enseignant travaille ailleurs**. L'API vérifie
au préalable et répond par un message neutre (`POSTE_TITULAIRE_OCCUPE`).

## Engager un enseignant

```json
POST /api/v1/enseignants
{ "telephone": "70 11 22 33", "nom": "Sanou", "prenoms": "Paul", "sexe": "M", "specialite": "Mathématiques",
  "type": "TITULAIRE", "debut": "2026-10-01" }
```

| Situation | Résultat |
|---|---|
| Enseignant **inconnu** de la plateforme | Identité et compte créés, engagement **ACTIF** immédiatement, mot de passe temporaire renvoyé une fois |
| Enseignant **déjà connu** (engagé dans une autre école) | **Invitation** (statut INVITE) : l'identité reste masquée jusqu'à son accord ; nom et prénoms saisis ignorés |

Vacataire : ajouter `"tauxHoraire": 2500` (FCFA / heure) et, en général, une date de `fin`.
Avant d'engager, `POST /api/v1/enseignants/recherche` `{"telephone":"…"}` ou `{"matriculeFp":"…"}` répond seulement
`{"trouve": true|false}`.

## Côté enseignant

| Appel | Effet |
|---|---|
| `GET /api/v1/moi/invitations` | Invitations en attente, toutes écoles (nom de l'école, type, dates, taux) |
| `POST /api/v1/moi/invitations/{id}/acceptation` | Engagement ACTIF, rôle ENSEIGNANT dans l'école ; puis `POST /api/v1/auth/etablissement` pour y travailler |
| `POST /api/v1/moi/invitations/{id}/refus` | Engagement REFUSE |
| `GET /api/v1/espace-enseignant/affectations?anneeId=` | Mes matières et classes dans l'école de la session, et ma charge hebdomadaire |

Un enseignant invité qui n'a plus aucun établissement actif (après une mutation, par exemple) peut quand même se
connecter : la session est alors sans établissement et ne sert qu'à répondre aux invitations. Le mot de passe
temporaire doit d'abord être changé.

## Points d'accès de l'établissement

| Ressource | Méthodes | Rôles |
|---|---|---|
| Enseignants | `GET /api/v1/enseignants?statut=ACTIF` | personnel administratif* |
| | `POST /api/v1/enseignants`, `POST /api/v1/enseignants/recherche` | ADMIN_ECOLE |
| Engagement | `GET /api/v1/engagements/{id}?anneeId=` : fiche, matières assurées, charge hebdomadaire | personnel administratif* |
| | `POST /api/v1/engagements/{id}/fin` `{"date":"2027-06-30","motif":"Mutation"}` | ADMIN_ECOLE |
| | `DELETE /api/v1/engagements/{id}` : annule une invitation en attente | ADMIN_ECOLE |
| | `PUT /api/v1/engagements/{id}/identite` : correction du nom, matricule… | ADMIN_ECOLE de l'école où il est **titulaire** |
| Affectation | `PUT /api/v1/classes/{classeId}/matieres/{matiereId}/enseignant` `{"engagementId":"…"}`, `DELETE …` | ADMIN_ECOLE, CENSEUR |
| Alerte | `GET /api/v1/annees/{id}/matieres-sans-enseignant` | tous les membres |

\* ADMIN_ECOLE, CENSEUR, SECRETARIAT, INTENDANT, SURVEILLANT.

La matière d'une classe (`GET /api/v1/classes/{id}/matieres`) indique désormais son `engagementId`.

## Règles de gestion

| Code d'erreur (409) | Règle |
|---|---|
| `POSTE_TITULAIRE_OCCUPE` | Titulaire ailleurs sur la période : engagement possible seulement comme vacataire |
| `DEJA_ENGAGE` | Déjà engagé ou invité dans l'établissement |
| `STATUT_ENGAGEMENT` | Transition impossible (accepter une invitation déjà traitée, terminer un engagement non actif…) |
| `ENGAGEMENT_INACTIF` | Seul un enseignant dont l'engagement est actif peut être affecté |
| `IDENTITE_NON_MODIFIABLE` | Seule l'école où l'enseignant est titulaire corrige son identité |
| `MATRICULE_EXISTANT` | Matricule de la fonction publique déjà attribué |
| `CLASSE_NON_VIDE`, `ANNEE_FIGEE` | Règles du domaine Établissement, inchangées |

Autres comportements :

- **Fin d'engagement** : l'enseignant perd le rôle ENSEIGNANT dans l'école et est retiré des matières des années en
  préparation ou en cours ; les années clôturées gardent l'historique. Une mutation est possible le lendemain de la fin.
- **Listes de classe** : un enseignant ne voit que les classes où il a au moins une matière (le personnel administratif
  les voit toutes).
- **Charge horaire** : somme des volumes hebdomadaires des matières affectées pendant l'année.
