# Dossier administratif du personnel (v0.51)

Un dossier par membre du personnel de l'établissement :
- les **enseignants** ;
- le **personnel administratif** (chef d'établissement, censeur, chef des travaux, secrétariat, intendance, CPE) ;
- les **agents de soutien** sans compte sur la plateforme (gardien, planton, agent d'entretien…).

Module `personnel` (`DossiersService`, `ActesService`, `AbsencesPersonnelService`). Migration
`V42__dossier_personnel.sql` : tables `agent`, `diplome_agent`, `piece_agent`, `acte_agent`, `absence_agent` et
`signalement_dossier`, toutes isolées par établissement.

## Contenu du dossier

| Rubrique | Contenu |
|---|---|
| Identité | Nom, prénoms, sexe, date et lieu de naissance, téléphone, adresse |
| Situation administrative | Situation (fonctionnaire, contractuel, vacataire, autre), matricule (unique dans l'établissement), emploi ou corps, grade, catégorie et échelle, échelon, fonction dans l'établissement |
| Service | Première prise de service (entrée dans la fonction), entrée dans le grade, prise de service dans l'établissement, cessation et motif |
| Ancienneté | Calculée **dans la fonction**, **dans le grade** et **dans l'établissement**, en années, mois et jours, jusqu'à aujourd'hui ou jusqu'à la cessation |
| Diplômes | Académiques et professionnels : intitulé, spécialité, délivré par, année, copie classée |
| Pièces | Arrêtés, décisions, notes de service, certificats, diplômes, état civil et CNIB, contrats : PDF, JPEG ou PNG de 5 Mo au plus. Le type est vérifié sur le contenu du fichier, pas sur son nom |
| Actes | Établis par l'établissement, voir ci-dessous |
| Congés et absences | Voir ci-dessous |
| Signalements | Erreurs signalées par l'agent, avec la réponse du secrétariat |
| Observations | Notes internes, jamais montrées à l'agent |

**À compléter.** La liste et le dossier signalent ce qui manque :
- la date de naissance ;
- la date de prise de service ;
- la situation administrative ;
- le matricule d'un fonctionnaire ;
- les diplômes (sauf pour les agents de soutien).

**Reprise.** `POST /api/v1/personnel/reprise` ouvre un dossier pour chaque enseignant engagé et chaque compte du
personnel qui n'en a pas. Ce qui est repris :
- pour un enseignant : l'identité, le matricule, la date d'engagement et la situation de vacataire ;
- pour les autres comptes : la fonction, déduite du rôle.

Le sexe des comptes administratifs n'est pas connu de la plateforme : il est mis à « homme » et doit être
vérifié. Une reprise répétée n'ajoute rien.

## Actes établis par l'établissement

| Acte | Texte |
|---|---|
| Certificat de prise de service | « … a effectivement pris service dans l'établissement le … », avec l'acte d'affectation s'il est indiqué |
| Certificat de cessation de service | « … a cessé service le … » ; le motif est obligatoire (mutation, retraite, fin de contrat…) |
| Attestation de présence au poste | « … est en service dans l'établissement depuis le … et y exerce effectivement ses fonctions » ; refusée à un agent parti (`AGENT_PARTI`) |
| Attestation de travail | « … travaille (ou a travaillé) dans l'établissement depuis le (du) … » |
| Autorisation d'absence | Établie automatiquement quand le chef d'établissement accorde une absence |

**Circuit.**
- **Préparation.** Le secrétariat prépare l'acte. C'est alors un **projet** : PDF marqué « PROJET — NON SIGNÉ »,
  invisible pour l'agent.
- **Signature.** Le **chef d'établissement le signe**. L'acte reçoit son numéro (`AP-2026-0001`), le nom du
  signataire et sa signature numérisée si elle est déposée.
- **Établi par le chef d'établissement**, l'acte est signé aussitôt.
- **Texte figé.** Le texte est figé à la préparation, pour que chaque réimpression soit identique.
- **Annulation.** Un acte signé ne se modifie pas : il s'annule, avec un motif, et la mention figure en pied du
  PDF.
- **Effets sur le dossier.** La signature d'une prise ou d'une cessation de service renseigne la date
  correspondante si elle manque.

## Congés et absences

Types :
- autorisation d'absence ;
- permission ;
- congé annuel, de maladie, de maternité ;
- mission ;
- formation ;
- absence irrégulière.

**Circuit.**
1. **Demande.** L'agent demande depuis « Mon dossier », ou le secrétariat demande pour lui. Le chef
   d'établissement est prévenu dans sa messagerie.
2. **Contrôles.** Une demande qui chevauche une autre absence est refusée (`ABSENCE_CHEVAUCHEMENT`). Une absence
   dure un an au plus.
3. **Décision.** Le **chef d'établissement accorde** (autorisation d'absence signée et numérotée) ou **refuse**
   (motif obligatoire). L'agent est prévenu dans sa messagerie.
4. **Annulation.** L'agent retire sa demande tant qu'elle n'est pas traitée. Le chef d'établissement annule une
   absence accordée, et l'autorisation est annulée avec elle.

**Absence irrégulière.** Elle est constatée par la gestion, sans demande.

**Jour de reprise.** C'est le lendemain de la fin, ou le lundi si ce lendemain est un dimanche.

## Droits

| Qui | Peut |
|---|---|
| Secrétariat, chef d'établissement | Tenir les dossiers, classer les pièces, préparer les actes, enregistrer les absences, répondre aux signalements |
| Chef d'établissement | En plus : signer et annuler les actes, accorder ou refuser les absences |
| Censeur | Consulter la liste et les dossiers ; pas les pièces |
| Chaque agent qui a un compte | Consulter son dossier, sans les observations internes ; télécharger ses pièces et ses actes signés ; demander une absence ; signaler une erreur |

Chaque consultation d'une pièce est inscrite au journal d'audit, car il s'agit de données personnelles.

## Points d'accès

| Méthode | Chemin | Rôles |
|---|---|---|
| GET | `/api/v1/personnel` | ADMIN_ECOLE, SECRETARIAT, CENSEUR : `{ agents, comptesSansDossier }` |
| POST | `/api/v1/personnel/reprise` | ADMIN_ECOLE, SECRETARIAT : `{ crees, dejaPresents }` |
| POST | `/api/v1/personnel` | ADMIN_ECOLE, SECRETARIAT : nouveau dossier (agent de soutien sans compte) |
| GET / PUT | `/api/v1/personnel/{id}` | lecture : gestion, censeur, l'agent lui-même ; modification : gestion. `MATRICULE_EXISTANT` (409) |
| POST, DELETE | `/api/v1/personnel/{id}/diplomes`, `/api/v1/personnel/diplomes/{id}` | gestion |
| POST (multipart) | `/api/v1/personnel/{id}/pieces` | gestion : `fichier`, `nature`, `objet`, `reference`, `datePiece` |
| GET, DELETE | `/api/v1/personnel/pieces/{id}` | téléchargement : gestion, l'agent lui-même ; retrait : gestion |
| POST | `/api/v1/personnel/{id}/actes` | gestion : `{ type, dateEffet, complement }` |
| POST | `/api/v1/personnel/actes/{id}/signature`, `…/annulation` | ADMIN_ECOLE |
| GET | `/api/v1/personnel/actes/{id}/pdf` | gestion ; l'agent lui-même pour un acte signé |
| GET | `/api/v1/personnel/absences` | ADMIN_ECOLE, SECRETARIAT, CENSEUR : demandes à décider, absences en cours et à venir |
| POST | `/api/v1/personnel/{id}/absences` | gestion |
| POST | `/api/v1/personnel/absences/{id}/decision` | ADMIN_ECOLE : `{ accorder, reponse }` |
| POST | `/api/v1/personnel/absences/{id}/annulation` | l'agent (demande non traitée), la gestion, le chef d'établissement (absence accordée, motif) |
| POST | `/api/v1/personnel/signalements/{id}/traitement` | gestion : `{ texte }` |
| GET | `/api/v1/mon-dossier` | tout membre du personnel qui a un compte |
| POST | `/api/v1/mon-dossier/absences`, `/api/v1/mon-dossier/signalements` | idem |

## Application web

- **Tuile « Dossiers du personnel »** (`/personnel`) :
  - liste par catégorie, avec les agents partis à part ;
  - recherche ;
  - ancienneté dans l'établissement ;
  - agents absents aujourd'hui ;
  - ce qui manque à chaque dossier ;
  - « Ouvrir leurs dossiers » pour les comptes sans dossier ;
  - « Ajouter un agent ».
- **Dossier** (`/personnel/{id}`) : rubriques identité et carrière, diplômes, pièces, actes, congés et absences,
  signalements.
- **Congés et absences** (`/personnel/absences`) : demandes à décider, absents aujourd'hui, absences à venir.
- **Tuile « Mon dossier »** (`/mon-dossier`), pour chaque membre du personnel.
