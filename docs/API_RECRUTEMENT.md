# Commission de recrutement (v0.49)

Complément d'effectif. Quand les élèves affectés par l'État et les réinscrits ne remplissent pas les classes, une
commission de l'établissement recrute sur dossier, dans la limite des places disponibles, puis l'intendance inscrit
les retenus avec le mode d'admission `COMPLEMENT_EFFECTIF`.

## Composition

| Qualité | Par défaut | Remarque |
|---|---|---|
| Président | Le chef d'établissement qui crée la commission (ADMIN_ECOLE) | Obligatoire (`PRESIDENT_REQUIS`) ; seul à délibérer et à clore |
| Vice-président | Le censeur | Une seule personne par qualité (`QUALITE_EN_DOUBLE`) |
| Rapporteur | Le CPE (rôle `SURVEILLANT`) | Nommé au procès-verbal |
| Membres | Les intendants, un « Représentant de l'APE » à désigner, des enseignants | Une personne extérieure n'a pas de compte (nom saisi) |

Chaque membre porte une **fonction** et une **présence** (reprises au procès-verbal). Un enseignant **désigné membre**
lit la commission ; un enseignant qui n'en est pas membre reçoit 403.

## Déroulement

1. **Préparation** (`PREPARATION`) : le chef d'établissement crée la commission de l'année. Les niveaux et filières
   (une **offre** chacun) sont repris des classes de l'année. Les **places disponibles** d'une offre sont la somme,
   sur ses classes, de l'effectif maximal moins les élèves inscrits. Elles valent `null` si une classe n'a pas
   d'effectif maximal. Les places offertes ne dépassent pas les places disponibles (`PLACES_INSUFFISANTES`).
2. **Candidatures** : le secrétariat, le censeur, le CPE ou le chef d'établissement les saisissent. La numérotation
   est `CAND-AAAA-NNNN` et la moyenne annuelle va de 0 à 20 (au-delà : 400). Le censeur ou le chef d'établissement
   déclare une candidature **irrecevable**, avec un motif obligatoire, ou la rend recevable.
3. **Classement au mérite**, par offre :
   - **Score** : la moyenne annuelle ou, si une note d'examen est saisie, `(1 − p) × moyenne + p × note`, où
     `p` est le poids de l'examen (de 0 à 1).
   - Sous la moyenne minimale, la candidature est **non retenue** (« Moyenne inférieure à X/20 »).
   - **Ordre** : score décroissant, puis le plus jeune d'abord, puis l'ordre de saisie.
   - **Décision** :
     - rang ≤ places : **retenue** ;
     - ensuite, jusqu'à la taille de la liste d'attente : **liste d'attente** ;
     - au-delà : **non retenue** (« Places et liste d'attente complètes »).
   - En préparation, le classement est un **aperçu** recalculé à chaque lecture.
4. **Délibération** (`DELIBEREE`) : le président fige les rangs, les scores et les décisions, et ajoute
   les observations du procès-verbal. Les candidatures ne se modifient plus (`COMMISSION_DELIBEREE`).
5. **Résultats** :
   - **procès-verbal** en PDF : en-tête officiel, membres présents et absents, critères, places, retenus, liste
     d'attente, non retenus, irrecevables, observations, rapporteur, signature numérisée du président ;
   - **résultats à afficher** en Excel ou PDF : un tableau par niveau.
   Les deux répondent `COMMISSION_NON_DELIBEREE` tant que la commission est en préparation.
6. **Inscription** par l'intendance (ou le chef d'établissement) d'un candidat **retenu**, dans une classe de
   l'offre. Elle crée l'élève, son responsable (contact principal) et l'inscription en mode `COMPLEMENT_EFFECTIF`.
   - Un candidat non retenu : `CANDIDAT_NON_RETENU`.
   - Une classe hors du niveau ou de la filière : `CLASSE_HORS_OFFRE`.
   - Une classe pleine : `CLASSE_COMPLETE`.
7. **Désistement** d'un retenu (non encore inscrit) ou d'un candidat sur la liste d'attente. Le désistement d'un
   retenu **repêche** le premier de la liste d'attente du même niveau : il devient retenu, marqué « repêché ».
   Un candidat ni retenu ni en attente : `DESISTEMENT_IMPOSSIBLE`.
8. **Clôture** (`CLOSE`) par le président : plus aucune inscription ni désistement (`COMMISSION_CLOSE`).

Numérotation : commission `CR-AAAA-NN`, candidature `CAND-AAAA-NNNN`.

## Données

Tables `commission_recrutement`, `membre_commission`, `offre_commission`, `candidature` (migration
`V39__commission_recrutement.sql`), isolées par établissement (Row-Level Security).

## Points d'accès

| Méthode | Chemin | Rôles | Effet |
|---|---|---|---|
| GET | `/api/v1/annees/{anneeId}/commissions-recrutement` | Lecture* | Commissions de l'année (places, candidatures, retenus, inscrits) |
| POST | `/api/v1/annees/{anneeId}/commissions-recrutement` | ADMIN_ECOLE | Crée la commission, avec ses membres et ses offres par défaut |
| GET | `/api/v1/commissions-recrutement/{id}` | Lecture* | Commission, membres, offres (classes, inscrits, disponibles), droits de l'utilisateur |
| PUT | `/api/v1/commissions-recrutement/{id}` | ADMIN_ECOLE | Paramètres : intitulé, date, lieu, date limite, critères, moyenne minimale, poids de l'examen, liste d'attente |
| PUT | `/api/v1/commissions-recrutement/{id}/membres` | ADMIN_ECOLE | `{ "membres": [{ qualite, utilisateurId, nom, fonction, present }] }` (remplace la liste) |
| PUT | `/api/v1/commissions-recrutement/{id}/offres` | ADMIN_ECOLE, CENSEUR | `{ "offres": [{ offreId, places }] }` |
| POST | `/api/v1/commissions-recrutement/{id}/offres/actualisation` | ADMIN_ECOLE, CENSEUR | Ajoute les niveaux des classes créées depuis |
| GET | `/api/v1/commissions-recrutement/{id}/candidatures` | Lecture* | Candidatures, avec la décision et la classe d'inscription |
| POST | `/api/v1/commissions-recrutement/{id}/candidatures` | ADMIN_ECOLE, CENSEUR, SECRETARIAT, SURVEILLANT | Enregistre une candidature |
| PUT / DELETE | `/api/v1/candidatures/{id}` | idem | Modifie ou supprime (en préparation) |
| POST | `/api/v1/candidatures/{id}/recevabilite` | ADMIN_ECOLE, CENSEUR | `{ "recevable": false, "motif": "…" }` |
| GET | `/api/v1/commissions-recrutement/{id}/classement` | Lecture* | Classement par offre : aperçu, puis résultats figés |
| POST | `/api/v1/commissions-recrutement/{id}/deliberation` | ADMIN_ECOLE (président) | `{ "observations": "…" }` ; `AUCUNE_CANDIDATURE` s'il n'y en a pas |
| GET | `/api/v1/commissions-recrutement/{id}/proces-verbal` | Lecture* | PDF |
| GET | `/api/v1/commissions-recrutement/{id}/resultats?format=xlsx\|pdf` | Lecture* | Résultats à afficher |
| POST | `/api/v1/candidatures/{id}/inscription` | ADMIN_ECOLE, INTENDANT | `{ "classeId": "…" }` |
| POST | `/api/v1/candidatures/{id}/desistement` | ADMIN_ECOLE, INTENDANT | `{ "motif": "…" }` ; réponse `{ desistee, repechee }` |
| POST | `/api/v1/commissions-recrutement/{id}/cloture` | ADMIN_ECOLE (président) | Clôt la commission délibérée |

\* **Lecture** : chef d'établissement, censeur, secrétariat, CPE, intendant ; un enseignant ou un chef des travaux
seulement s'il est désigné membre.

## Application web

- « Commission de recrutement » (`/recrutement`) : commissions de l'année, création par le chef d'établissement.
- `/recrutement/{id}` est organisé en onglets :
  - **Commission** : membres, modifiables par le président ;
  - **Places** : disponibles, offertes, candidatures, retenus et inscrits ;
  - **Candidatures** : saisie et recevabilité ;
  - **Classement**, qui devient **Résultats** après délibération : délibération, procès-verbal, résultats à
    afficher, inscription avec le choix de la classe, désistement, clôture.
