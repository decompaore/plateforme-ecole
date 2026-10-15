# Besoins et dotations (v0.48, cloisonnement v0.48.1)

Le circuit suit l'organisation du terrain.

**Les besoins remontent.** Chaque établissement prépare **un état des besoins par année scolaire** :
- le **censeur** renseigne les besoins du domaine **général** ;
- le **chef des travaux** renseigne ceux du domaine **technique** ;
- le **proviseur** (chef d'établissement) complète, corrige et **transmet** l'état à sa direction de rattachement (direction provinciale).

Chaque direction **arbitre** les quantités puis **transmet** à la direction supérieure. Le circuit va ainsi de la direction provinciale à la direction régionale, puis au **niveau central** (administrateur pays).

**Les dotations descendent**, du niveau central vers les directions régionales, puis vers les directions provinciales et enfin vers les établissements. Chaque destinataire **accuse réception** en indiquant les quantités reçues et conformes. Aucune entrée en stock n'est faite automatiquement.

Catégories : `INFRASTRUCTURE`, `PERSONNEL`, `EQUIPEMENT_OUTILS`, `MATIERE`.
Domaines : `GENERAL`, `TECHNIQUE`.
Priorités : 1 (urgente), 2 (normale), 3 (souhaitable).

> Ce circuit est distinct des **campagnes de besoins des ateliers** (v0.25, `docs/API_ATELIERS.md`), qui servent à commander la matière d'œuvre et les équipements de l'établissement.

## Données et isolation

| Où | Tables | Isolation |
|---|---|---|
| Établissement | `etat_besoins`, `ligne_etat_besoins` | Row-Level Security (tenant du jeton) |
| Territoire | `remontee_besoins`, `ligne_remontee`, `arbitrage_besoin`, `transmission_besoins`, `dotation`, `ligne_dotation` | Pas de tenant. La portée est contrôlée par le service (`PorteeMoyens`). |

À la transmission, les lignes de l'état sont **copiées** dans `ligne_remontee`. La direction ne lit jamais les tables de l'établissement.

Si l'état est **renvoyé** pour correction, l'établissement le modifie, puis le retransmet. Les lignes copiées sont alors remplacées, et les arbitrages déjà faits sur ces lignes disparaissent.

## Côté établissement

| Méthode | Chemin | Rôles | Effet |
|---|---|---|---|
| GET | `/api/v1/etat-besoins?anneeId=` | ADMIN_ECOLE, CENSEUR, CHEF_TRAVAUX, INTENDANT | État de l'année : l'année active par défaut |
| POST | `/api/v1/etat-besoins/{anneeId}/lignes` | ADMIN_ECOLE, CENSEUR, CHEF_TRAVAUX | Ajoute une ligne. L'état est créé à la première ligne. |
| PUT | `/api/v1/etat-besoins/lignes/{id}` | idem | Modifie une ligne |
| DELETE | `/api/v1/etat-besoins/lignes/{id}` | idem | Supprime une ligne |
| POST | `/api/v1/etat-besoins/{anneeId}/transmettre` | ADMIN_ECOLE | Transmet l'état à la direction de rattachement. Corps : `{ "observations": "…" }`. |
| GET | `/api/v1/dotations-recues` | ADMIN_ECOLE, CENSEUR, CHEF_TRAVAUX, INTENDANT | Dotations envoyées à l'établissement |
| GET | `/api/v1/dotations-recues/{id}` | idem | Détail d'une dotation |
| POST | `/api/v1/dotations-recues/{id}/accuser` | ADMIN_ECOLE, INTENDANT | Accusé de réception |

Ligne : `{ "categorie", "domaine", "designation", "unite", "quantite", "priorite", "justification" }`. Les espaces superflus de la désignation et de l'unité sont retirés.

**Droits par domaine**
- Le censeur ne touche qu'au domaine `GENERAL`, le chef des travaux qu'au domaine `TECHNIQUE` ; dans les deux cas, une ligne de l'autre domaine donne **403**.
- Le proviseur touche aux deux domaines.

La vue renvoie aussi :
- `domaines` : les domaines que la personne connectée peut renseigner ;
- `peutTransmettre` : vrai pour le proviseur, si l'état n'est pas vide et que l'établissement est rattaché à une direction.

**Statuts de l'état**
- `BROUILLON` : l'état n'a pas encore été transmis ;
- `TRANSMIS` : l'état est verrouillé ;
- `RENVOYE` : l'état est de nouveau modifiable. La vue donne le `motifRenvoi`.

**Erreurs**

| Code | Statut HTTP | Cas |
|---|---|---|
| `ETAT_TRANSMIS` | 409 | Modification d'un état transmis |
| `ETAT_VIDE` | 409 | Transmission sans ligne |
| `SANS_DIRECTION` | 409 | Établissement non rattaché à une direction |
| `AUCUNE_ANNEE` | 409 | Aucune année scolaire |

## Côté directions et niveau central

Toutes ces routes sont sous `/api/v1/pilotage/`. Elles sont ouvertes aux rôles DIRECTION, ADMIN_PAYS et SUPER_ADMIN.

**Qui fait quoi : cloisonnement strict (v0.48.1)**
- L'**administrateur pays** agit au niveau central de son pays, et seulement à ce niveau.
- Un **compte de direction** agit au nom de sa direction, et seulement à ce niveau : la direction régionale ne voit que ce qui lui est arrivé, la direction provinciale de même, et ainsi de suite.
- Une direction ne descend pas consulter une direction inférieure : un paramètre `direction` (ou `pays`) qui désigne une autre instance que la sienne donne **403**.
- Le **super administrateur de la plateforme n'a aucun accès** à ces données : toute route de besoins ou de dotations lui renvoie **403**.
- **Avant son arrivée**, un état apparaît dans la liste des établissements avec son seul emplacement (`EN_COURS` et `etape`). On n'y voit ni lignes, ni `remonteeId`, ni motif de renvoi. Son détail donne **403** tant qu'il n'est pas arrivé au niveau de la personne connectée.

### Besoins

| Méthode | Chemin | Effet |
|---|---|---|
| GET | `/pilotage/besoins?annee=` | Vue d'ensemble du niveau de la personne connectée. Détail des champs ci-dessous. |
| GET | `/pilotage/besoins/export?format=xlsx\|pdf` | Classeur : besoins consolidés, établissements, directions |
| GET | `/pilotage/besoins/remontees/{id}` | État d'un établissement arrivé à ce niveau : lignes et chaîne des arbitrages des niveaux inférieurs |
| PUT | `/pilotage/besoins/remontees/{id}/arbitrages` | Arbitrage au niveau de la personne connectée |
| POST | `/pilotage/besoins/remontees/{id}/renvoyer` | Renvoi à l'établissement. Corps : `{ "motif" }`. |
| POST | `/pilotage/besoins/transmettre` | Transmission de la direction à la direction supérieure. Corps : `{ "annee", "observations" }`. |
| POST | `/pilotage/besoins/directions/{id}/renvoyer` | Renvoi de sa transmission à une direction inférieure. Corps : `{ "annee", "motif" }`. |

La vue d'ensemble (`GET /pilotage/besoins`) contient :
- `etablissements` : l'état de chacun ;
- `directions` : les directions immédiatement inférieures et leur transmission ;
- `consolidation` : les lignes regroupées par catégorie, désignation et unité, sans tenir compte de la casse ni des espaces ;
- `destination` : la direction supérieure, ou le niveau central ;
- `peutArbitrer` et `peutTransmettre`.

Corps d'un arbitrage :

```json
{ "lignes": [ { "ligneId": "…", "quantiteRetenue": 2, "observation": "Un tour réparable" } ] }
```

Avec `quantiteRetenue: null`, l'arbitrage de ce niveau est retiré : la quantité du niveau inférieur s'applique de nouveau.

**Règles**
- **Arrivée.** Un état arrive d'abord à la direction de rattachement de l'établissement. Il n'arrive à une direction supérieure (ou au niveau central) qu'une fois que chaque direction intermédiaire a transmis les besoins de l'année. Avant, il apparaît `EN_COURS`, avec l'`etape` : la direction où il attend.
- **Quantité retenue.** Un niveau qui n'a pas arbitré reprend la quantité du niveau inférieur. S'il n'y en a pas, c'est la quantité demandée.
- **Plafond.** Un niveau retient **au plus** la quantité du niveau inférieur (`maximum`). Au-delà : `QUANTITE_SUPERIEURE` (409).
- **Après transmission.** Une direction qui a transmis n'arbitre plus, ne renvoie plus et ne retransmet pas (`BESOINS_DEJA_TRANSMIS`). Seule la direction supérieure peut lui renvoyer sa transmission. Le niveau central ne transmet pas (403).
- **Renvoi à l'établissement.** Seule la direction de rattachement peut renvoyer l'état d'un établissement, tant qu'elle n'a pas transmis.

**Erreurs**

| Code | Statut HTTP | Cas |
|---|---|---|
| `ETAT_NON_RECU` | 409 | Arbitrage d'un état qui n'est pas encore arrivé à ce niveau |
| `DEJA_RENVOYE` | 409 | Renvoi d'un état déjà renvoyé |
| `NON_TRANSMIS` | 409 | Renvoi d'une transmission qui n'a pas eu lieu |
| `AUCUN_BESOIN` | 409 | Transmission alors qu'aucun état n'est arrivé |

### Dotations

| Méthode | Chemin | Effet |
|---|---|---|
| GET | `/pilotage/dotations?annee=` | Dotations reçues et émises par la personne connectée (son niveau), et destinataires possibles |
| GET | `/pilotage/dotations/{id}` | Détail : lignes, quantités répartie et disponible, répartitions |
| POST | `/pilotage/dotations` | Prépare une dotation (`BROUILLON`), avec une référence `DOT-AAAA-NNNNN` |
| PUT | `/pilotage/dotations/{id}` | Modifie un brouillon |
| DELETE | `/pilotage/dotations/{id}` | Supprime un brouillon |
| POST | `/pilotage/dotations/{id}/envoyer` | Envoie la dotation (`ENVOYEE`) |
| POST | `/pilotage/dotations/{id}/accuser` | Accusé de réception d'une direction destinataire (`RECUE`) |

Corps d'une dotation :

```json
{ "annee": "2026-2027", "destinataireType": "DIRECTION", "destinataireId": "…", "origineId": null,
  "objet": "Dotation de rentrée",
  "lignes": [ { "ligneOrigineId": null, "categorie": "MATIERE", "designation": "Rond d'acier Ø 20",
                "unite": "barre", "quantite": 100, "valeurUnitaire": 4500 } ] }
```

**Destinataires possibles**
- Le niveau central dote les directions du premier rang (les régionales).
- Une direction dote :
  - les directions qui en dépendent directement ;
  - les établissements qui lui sont rattachés.
- Tout autre destinataire donne `DESTINATAIRE_HORS_RESSORT` (409).

**Répartition** (`origineId`)
- La dotation d'origine doit avoir été reçue par l'émetteur, et sa réception accusée. Sinon : `ORIGINE_INVALIDE` (409).
- Chaque ligne reprend une ligne d'origine (`ligneOrigineId`). La catégorie, la désignation et l'unité sont recopiées.
- Le total réparti par ligne, toutes répartitions comprises (y compris les brouillons), ne dépasse pas la quantité **conforme** reçue. Sinon : `QUANTITE_DEPASSEE` (409). Le contrôle est refait à l'envoi.

**Accusé de réception**

Corps :

```json
{ "observations": "…", "lignes": [ { "ligneId": "…", "quantiteRecue": 3, "quantiteConforme": 2, "observation": "…" } ] }
```

- Une ligne omise est considérée comme entièrement reçue et conforme.
- Une quantité conforme supérieure à la quantité reçue donne **400**. Un second accusé donne `DEJA_ACCUSEE` (409).

**Visibilité**
- Un brouillon n'est visible que du côté de l'émetteur : pour le destinataire, il donne **404**.
- Une dotation n'est visible que de son émetteur et, une fois envoyée, de son destinataire (v0.48.1). La direction régionale ne voit donc pas les dotations qu'une direction provinciale envoie à ses établissements.
- La répartition d'une dotation reçue (`repartitions`, `repartie`, `disponible`) n'est donnée qu'au destinataire qui répartit. L'émetteur voit les quantités reçues et conformes, mais pas la suite.

Seul l'émetteur modifie ou envoie une dotation : sinon **403**. Une dotation envoyée n'est plus modifiable : `DOTATION_ENVOYEE` (409).

## Interface

| Écran | Route | Pour qui | Contenu |
|---|---|---|---|
| Besoins et dotations | `/besoins` | Établissement | État des besoins (par catégorie, saisie selon le domaine, transmission), dotations reçues (accusé de réception) |
| Besoins | `/pilotage/besoins` | Directions, niveau central | Établissements, directions, besoins consolidés, détail et arbitrage d'un état, renvoi, transmission, export Excel ou PDF |
| Dotations | `/pilotage/dotations` | Directions, niveau central | Dotations reçues et émises, préparation libre ou répartition d'une dotation reçue, envoi, accusé de réception |
