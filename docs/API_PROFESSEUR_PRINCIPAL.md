# Professeur principal (v0.49.1)

Chaque classe a un **professeur principal**, choisi parmi ses enseignants par le chef d'établissement ou le censeur.
Il sert d'interface :

- **entre les enseignants de la classe et l'administration** : il recueille leurs préoccupations et leurs
  revendications, et les porte à la direction ;
- **entre les élèves et les enseignants** : un élève, ou un groupe d'élèves, qui a un souci avec un enseignant se
  tourne vers lui ; il gère la médiation ;
- **pour les notes** : avant le conseil de classe, il vérifie avec les élèves les notes et moyennes de la période,
  recueille les réclamations et fait corriger les erreurs. Il atteste ensuite la vérification.

Module `professeurprincipal`. Migration `V40__professeur_principal.sql` : tables `professeur_principal`,
`saisine_classe`, `suite_saisine`, `verification_notes` et `reclamation_note`, toutes isolées par établissement
(Row-Level Security).

## Désignation

| Méthode | Chemin | Rôles | Effet |
|---|---|---|---|
| GET | `/api/v1/annees/{anneeId}/professeurs-principaux` | ADMIN_ECOLE, CENSEUR, SECRETARIAT, SURVEILLANT, CHEF_TRAVAUX, INTENDANT | Classes de l'année : professeur principal, enseignants de la classe (candidats), dossiers en cours, état de la vérification des notes par période |
| PUT | `/api/v1/classes/{classeId}/professeur-principal` | ADMIN_ECOLE, CENSEUR | `{ "utilisateurId": "…" }`. L'enseignant doit enseigner dans la classe (`ENSEIGNANT_HORS_CLASSE`). Il est prévenu dans sa messagerie, et les dossiers en cours lui passent. |
| DELETE | `/api/v1/classes/{classeId}/professeur-principal` | ADMIN_ECOLE, CENSEUR | Retire la désignation |
| GET | `/api/v1/espace-enseignant/classes-principales?anneeId=` | ENSEIGNANT | Classes dont l'enseignant connecté est le professeur principal |

Un même enseignant peut être professeur principal de plusieurs classes.

## Dossiers : revendications et médiations

Numérotation `PP-AAAA-NNNN`. Un dossier est `OUVERTE`, puis `TRANSMISE` (portée à l'administration), puis `CLOSE`
(avec une conclusion obligatoire).

| Nature | Qui l'ouvre | Déroulement |
|---|---|---|
| `REVENDICATION` | Un enseignant de la classe, ou le professeur principal | Le professeur principal la reçoit dans sa messagerie et la **porte à l'administration** (chef d'établissement et censeur). La direction répond dans le dossier. L'auteur est tenu informé. |
| `MEDIATION` | Le professeur principal seul | Élève(s) concerné(s), enseignant concerné (déduit de la matière si elle est indiquée), exposé. Le professeur principal note ses démarches et **informe l'enseignant**, qui voit alors le dossier et peut donner sa version. Il peut **saisir la direction** (chef d'établissement, censeur et CPE). |

**Cloisonnement.** Un dossier est vu par :
- le professeur principal de la classe ;
- son auteur ;
- l'enseignant concerné, une fois informé ;
- la direction (et le CPE pour une médiation), seulement **après la transmission**.

Les autres reçoivent 403.

**Droits.** Chaque vue porte `droits` : `ecrire`, `informer`, `transmettre` et `clore`.
- **Clôture :** le professeur principal, ou la direction pour un dossier qui lui a été transmis.
- **Transmission :** le professeur principal seul (`TRANSMISSION_IMPOSSIBLE`).
- **Information de l'enseignant :** le professeur principal seul (`INFORMATION_IMPOSSIBLE`).
- **Après la clôture :** plus d'échange possible (`SAISINE_CLOSE`).

| Méthode | Chemin | Rôles | Effet |
|---|---|---|---|
| GET | `/api/v1/saisines?anneeId=&classeId=` | ENSEIGNANT, ADMIN_ECOLE, CENSEUR, SURVEILLANT | Dossiers visibles par l'utilisateur ; ceux en cours d'abord |
| POST | `/api/v1/classes/{classeId}/saisines` | ENSEIGNANT | `{ nature, objet, expose, inscriptions: [], matiereId, enseignantId }`. Exige un professeur principal désigné (`PROFESSEUR_PRINCIPAL_ABSENT`). |
| GET | `/api/v1/saisines/{id}` | idem liste | Dossier et journal des échanges |
| POST | `/api/v1/saisines/{id}/suites` | idem liste | `{ "texte": "…" }` : message ou démarche. La qualité de l'auteur est notée (professeur principal, enseignant, chef d'établissement, censeur, CPE). |
| POST | `/api/v1/saisines/{id}/information-enseignant` | ENSEIGNANT (professeur principal) | Médiation : l'enseignant concerné est informé par message et voit le dossier |
| POST | `/api/v1/saisines/{id}/transmission` | ENSEIGNANT (professeur principal) | `{ "texte": "…" }` facultatif. Messages au chef d'établissement et au censeur, ainsi qu'au CPE pour une médiation. |
| POST | `/api/v1/saisines/{id}/cloture` | idem liste | `{ "texte": "conclusion" }` |

## Vérification des notes et réclamations

Pour chaque classe et chaque période :

1. **Parcours des moyennes.** Le professeur principal parcourt avec les élèves les moyennes par matière, à partir
   des résultats de la classe (`GET /api/v1/classes/{id}/resultats`, lisibles par les enseignants de la classe).
2. **Réclamations.** Il enregistre chaque réclamation : élève, matière, évaluation éventuelle (la note contestée
   est alors relevée) et motif. L'enseignant de la matière la reçoit dans sa messagerie. La période ne doit pas
   être verrouillée (`PERIODE_VERROUILLEE`).
3. **Réponse de l'enseignant de la matière**, ou de la direction des études (chef d'établissement, censeur) :
   - **note corrigée** : la correction se fait d'abord dans la saisie des notes, et la nouvelle note est relevée.
     Si une évaluation est visée et que sa note n'a pas changé, la réponse est refusée (`NOTE_INCHANGEE`).
   - **note maintenue** : la justification est obligatoire (400).
   - Une seule réponse par réclamation (`RECLAMATION_TRAITEE`). Le professeur principal est prévenu.
4. **Attestation.** Quand plus aucune réclamation n'attend (`RECLAMATIONS_EN_ATTENTE`), le professeur principal
   atteste la vérification, avec des observations s'il le souhaite. Le censeur est prévenu. Une réclamation
   enregistrée ensuite remet la vérification `EN_COURS`.

États : `A_FAIRE` (rien encore), `EN_COURS`, `VALIDEE`.

| Méthode | Chemin | Rôles | Effet |
|---|---|---|---|
| GET | `/api/v1/classes/{classeId}/verification-notes?periodeId=` | ENSEIGNANT de la classe, ADMIN_ECOLE, CENSEUR | État, réclamations, droits (`professeurPrincipal`, `peutValider`). Un enseignant qui n'est pas professeur principal ne voit que les réclamations de ses matières. |
| POST | `/api/v1/classes/{classeId}/verification-notes/reclamations` | ENSEIGNANT (professeur principal) | `{ periodeId, inscriptionId, matiereId, evaluationId, motif }` |
| POST | `/api/v1/reclamations-notes/{id}/reponse` | ENSEIGNANT de la matière, ADMIN_ECOLE, CENSEUR | `{ "decision": "CORRIGEE" \| "MAINTENUE", "reponse": "…" }` |
| GET | `/api/v1/espace-enseignant/reclamations-notes` | ENSEIGNANT | Réclamations adressées à l'enseignant ; celles à traiter d'abord |
| POST | `/api/v1/classes/{classeId}/verification-notes/validation` | ENSEIGNANT (professeur principal) | `{ periodeId, observations }` |

### Conseil de classe et bulletins

Dans une classe **qui a un professeur principal**, l'avis du conseil de classe (`PUT …/conseil`) et la génération
des bulletins (`POST …/bulletins`) attendent que la vérification de la période soit attestée. Sinon, ils répondent
`409 VERIFICATION_NOTES_REQUISE`. Une classe sans professeur principal n'est pas bloquée, mais le tableau des
professeurs principaux la signale.

Ordre conseillé :
1. vérification avec les élèves et corrections, pendant que la période est ouverte ;
2. attestation ;
3. verrouillage de la période ;
4. conseil de classe ;
5. génération et publication des bulletins.

## Application web

- **Enseignant** : tuile « Professeur principal » (`/professeur-principal`), qui regroupe :
  - ses classes de professeur principal (vérification des notes, ouverture d'une médiation) ;
  - le formulaire « Confier une revendication » pour une de ses classes ;
  - ses dossiers ;
  - « Réclamations de notes à traiter » (`/professeur-principal/reclamations`).
- **Vérification** : `/professeur-principal/classes/{id}/verification`.
  - Choix de la période et tableau des moyennes par matière.
  - Réclamations : enregistrement, réponses.
  - Attestation.
- **Dossier** : `/professeur-principal/dossiers/{id}`, avec le journal et les actions selon les droits.
- **Direction** : tuile « Professeurs principaux » (`/professeurs-principaux`).
  - Désignation par classe, choisie dans la liste des enseignants de la classe.
  - Classes sans professeur principal.
  - État des vérifications et dossiers transmis.
