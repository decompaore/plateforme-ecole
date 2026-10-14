# Secrétariat de l'établissement (v0.47.1)

Module `secretariat`, migration `V35__secretariat.sql` (tables cloisonnées par établissement). Le secrétariat
assure la gestion administrative et le courrier, l'accueil et la communication, l'appui à la scolarité et aux
examens, et la préparation des réunions statutaires. **Il ne voit plus les évaluations ni les bulletins.**

Rôles : `GESTION` = ADMIN_ECOLE, SECRETARIAT ; `CHEF` = ADMIN_ECOLE (signature, avis, validation).

## 1. Courrier (arrivée et départ)

Registre numéroté par année civile : arrivée `A-2026-0001`, départ `D-2026-0001`. Catégories : direction
provinciale, direction régionale, ministère, parents, personnel, partenaire, note de service, autre. Le secrétariat
enregistre, joint la pièce scannée (PDF, JPEG, PNG, 5 Mo), **transmet** au service concerné (direction, censorat,
chef des travaux, intendance, vie scolaire, secrétariat, enseignants) et suit le traitement :
`ENREGISTRE → TRANSMIS → TRAITE → CLASSE` (avec une cote de classement).

| Appel | Rôles |
|---|---|
| `GET /api/v1/courriers?sens=&q=` | gestion ; les autres services voient le courrier qui leur est transmis |
| `GET /api/v1/courriers/{id}`, `GET …/{id}/fichier` | idem |
| `POST /api/v1/courriers`, `PUT …/{id}`, `POST …/{id}/fichier` (multipart) | GESTION |
| `POST …/{id}/suivi` (transmettre, traité, classer, note de suivi) | GESTION ; le service destinataire marque « traité » |
| `GET /api/v1/courriers/registre?annee=&format=xlsx\|pdf` : registre imprimable | GESTION |

## 2. Documents administratifs et tableau d'affichage

Notes de service, décisions, communiqués, attestations, convocations, lettres : le secrétariat **rédige** (brouillon
modifiable ou supprimable), le chef d'établissement **signe** — le document reçoit son numéro (`2026-0012/NS`) et
son texte est figé ; il peut ensuite être **annulé** (il garde son numéro). Un document signé s'affiche au
**tableau d'affichage**, entre deux dates, pour le personnel ou pour tous (parents compris).

| Appel | Rôles |
|---|---|
| `GET/POST /api/v1/documents-administratifs`, `GET/PUT/DELETE …/{id}` (brouillon) | GESTION |
| `POST …/{id}/signature`, `POST …/{id}/annulation` | CHEF |
| `PUT …/{id}/affichage` `{"affichage":"TOUS","du":"…","au":"…"}` (`null` : retire) | GESTION |
| `GET …/{id}/pdf` | gestion ; les autres, un document affiché qui leur est destiné |
| `GET /api/v1/affichage` : documents affichés aujourd'hui | tout le personnel, les parents (ce qui est « pour tous ») |

## 3. Actes administratifs du personnel

Demandes de mutation, d'affectation, d'autorisation d'absence, de congé, de disponibilité, d'attestation de
service… recueillies par le secrétariat (numéro, pièce jointe) :

```
RECUE → TRANSMISE (au chef) → AVIS_FAVORABLE | AVIS_DEFAVORABLE → TRANSMISE_HIERARCHIE (courrier départ) → CLOTUREE
```

Le chef d'établissement seul donne l'avis (motivé) ; la transmission à la hiérarchie (direction provinciale…)
enregistre automatiquement le courrier de départ. Le demandeur suit ses demandes (« Mes demandes »).

| Appel | Rôles |
|---|---|
| `GET /api/v1/demandes-administratives`, `POST …`, `POST …/{id}/transmission`, `…/transmission-hierarchie`, `…/cloture`, fichier | GESTION |
| `POST …/{id}/avis` | CHEF |
| `GET /api/v1/demandes-administratives/miennes` | tout le personnel |
| `GET /api/v1/secretariat/personnel` : personnel actif (pour rattacher une demande, orienter un visiteur) | GESTION |

## 4. Accueil et permanence téléphonique

Registre de la journée : visites (parents, visiteurs, partenaires, autorités) orientées vers le service compétent,
avec heure d'arrivée et de départ ; appels reçus. Un message pour une personne du personnel lui est **transmis dans
sa messagerie** (« URGENT » en tête si besoin).

| Appel | Rôles |
|---|---|
| `GET /api/v1/accueil?jour=`, `POST /api/v1/accueil`, `POST …/{id}/transmission`, `POST …/{id}/depart` | GESTION |

## 5. Réunions statutaires

Conseil d'établissement, conseils de classe, de discipline, d'enseignement, conseil de l'école, assemblée de l'APE,
réunions du personnel : le secrétariat prépare (ordre du jour, participants) et rédige le compte rendu ; le chef
d'établissement le **valide** une fois la réunion tenue — il ne change plus ; PDF.

| Appel | Rôles |
|---|---|
| `GET /api/v1/reunions`, `GET …/{id}/pdf` | ADMIN_ECOLE, SECRETARIAT, CENSEUR, CHEF_TRAVAUX |
| `POST/PUT/DELETE /api/v1/reunions[/{id}]` (brouillon) | GESTION |
| `POST …/{id}/validation` | CHEF |

## 6. Archives et classement

Pièces scannées classées au dossier d'un élève (acte de naissance, bulletins antérieurs, décision d'orientation…),
d'un membre du personnel (arrêté d'affectation, procès-verbal de prise de service, diplômes…) ou de l'établissement
(arrêtés, conventions), avec catégorie et cote. `GET/POST /api/v1/archives`, `GET …/{id}/fichier`,
`DELETE …/{id}` : GESTION. Jointes à l'export de réversibilité.

## 7. Certificats de scolarité et dossiers d'examens

- `POST /api/v1/inscriptions/{id}/certificat-scolarite` → PDF numéroté `CS-2026-0001`, inscrit au registre ;
  `GET /api/v1/certificats-scolarite?annee=` : registre des certificats délivrés (GESTION).
- `GET /api/v1/examens/candidats?classes=…&examen=BEPC` et `…/export?format=xlsx` : candidats des classes choisies et
  ce qui manque à leur dossier (lieu de naissance, photo d'identité, identifiant national) — ADMIN_ECOLE,
  SECRETARIAT, CENSEUR.

## Ce que le secrétariat ne voit plus (v0.47.1)

Évaluations et notes, résultats, bulletins et visas des bulletins (`403`). Il garde la consultation des dossiers des
élèves, des listes de classe, des absences, de la vie scolaire, des statistiques et de la scolarité.

## Écrans

« Secrétariat » (`/secretariat`) : Courrier, Documents administratifs, Demandes du personnel, Accueil et téléphone,
Réunions, Archives, Certificats et examens. « Tableau d'affichage » (`/affichage`) pour tout le personnel, avec
« Mes demandes ».
