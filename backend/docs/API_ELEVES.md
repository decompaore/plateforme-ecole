# Domaine Élèves et inscriptions (v0.3)

Module `eleves` : dossier de l'élève, responsables (parents, tuteurs), inscriptions, réinscriptions, statut de bourse,
import Excel et espace parent. Migration `V4__eleves_inscriptions.sql`, cloisonnée par établissement (RLS).

## Modèle

| Table | Rôle | Règles garanties par la base |
|---|---|---|
| `eleve` | Dossier permanent, suit l'élève toute sa scolarité | matricule unique, identifiant national unique (par établissement) |
| `responsable` | Parent ou tuteur, identifié par son téléphone | un seul responsable par téléphone : partagé par les frères et sœurs |
| `lien_responsable_eleve` | Père, mère, tuteur, autre ; responsable légal ; contact prioritaire | un seul contact prioritaire (destinataire des SMS) par élève |
| `inscription` | Élève × classe × année : statut, redoublant, bourse, sortie | une inscription par élève et par année ; la classe appartient à l'année ; sortie ⇔ date de sortie |
| `compteur` | Numérotation par établissement (matricules, plus tard reçus) | incrément atomique, sans doublon ni trou |

Matricule généré : `AAAA-NNNNN` (ex. `2026-00001`), numérotation propre à chaque établissement. Un matricule attribué
par l'établissement peut aussi être saisi.

## Qui fait quoi (v0.47)

Organisation vue avec les établissements : **l'intendant inscrit** (il reçoit le dossier et les frais) ; une fois
l'inscription enregistrée, **seul le chef d'établissement modifie** (identité, parents, bourse, sortie, mode
d'admission, pièces retirées) ; **le censeur répartit** les élèves dans les classes (même niveau ; un changement de
niveau revient au chef d'établissement, `409 CHANGEMENT_DE_NIVEAU`) ; le secrétariat consulte et édite les documents
remis aux familles (cartes scolaires, accès à l'espace parent).

| Groupe | Rôles | Opérations |
|---|---|---|
| Saisie | ADMIN_ECOLE, INTENDANT | nouveau dossier, inscription, import Excel, dépôt de la photo, pièces reçues |
| Modification | ADMIN_ECOLE | `PUT` élève et responsables, ajout et retrait d'un responsable, bourse, sortie, retrait de la photo, liste des pièces |
| Répartition | ADMIN_ECOLE, CENSEUR | changement de classe, réinscriptions par classe, plan de passage |
| Documents | ADMIN_ECOLE, INTENDANT, SECRETARIAT | cartes scolaires, espace parent (un parent, une classe) |

## Parcours type de la rentrée

| # | Appel | Rôle |
|---|---|---|
| 1 | `GET /api/v1/annees/{id}/eleves/import/modele` : modèle Excel avec la liste des classes | ADMIN_ECOLE, INTENDANT |
| 2 | `POST /api/v1/annees/{id}/eleves/import?simulation=true` (multipart, champ `fichier`) : rapport ligne par ligne | ADMIN_ECOLE, INTENDANT |
| 3 | Même appel avec `simulation=false` : les lignes valides sont enregistrées, les autres restent dans le rapport | ADMIN_ECOLE, INTENDANT |
| 4 | Nouveaux élèves isolés : `POST /api/v1/eleves` puis `POST /api/v1/inscriptions` (avec `modeAdmission`) | ADMIN_ECOLE, INTENDANT |
| 5 | Pièces du dossier : `PUT /api/v1/inscriptions/{id}/dossier` | ADMIN_ECOLE, INTENDANT |
| 6 | Répartition : `PATCH /api/v1/inscriptions/{id}/classe` ; anciens élèves : `POST /api/v1/classes/{classeCible}/reinscriptions` | ADMIN_ECOLE, CENSEUR |
| 7 | Espace parent : `POST /api/v1/responsables/{id}/espace-parent` → mot de passe temporaire à transmettre | ADMIN_ECOLE, INTENDANT, SECRETARIAT |

Exemple de création d'un dossier :

```json
POST /api/v1/eleves
{ "nom": "Ouedraogo", "prenoms": "Awa", "sexe": "F", "dateNaissance": "2014-02-14", "lieuNaissance": "Koudougou",
  "responsables": [ { "nom": "Ouedraogo", "prenoms": "Issa", "telephone": "70 12 34 56", "lien": "PERE" } ] }
```

## Points d'accès

| Ressource | Méthodes | Rôles |
|---|---|---|
| Élèves | `GET /api/v1/eleves?q=&page=0&taille=20`, `GET /api/v1/eleves/{id}` (dossier complet) | personnel administratif* |
| | `POST /api/v1/eleves` | ADMIN_ECOLE, INTENDANT |
| | `PUT /api/v1/eleves/{id}` | ADMIN_ECOLE |
| Responsables | `POST /api/v1/eleves/{id}/responsables`, `DELETE /api/v1/eleves/{id}/responsables/{responsableId}`, `PUT /api/v1/responsables/{id}` | ADMIN_ECOLE |
| Inscriptions | `POST /api/v1/inscriptions` `{"eleveId":…,"classeId":…,"modeAdmission":"AFFECTATION_ETAT"}` | ADMIN_ECOLE, INTENDANT |
| | `PATCH /api/v1/inscriptions/{id}/classe` (censeur : même niveau) | ADMIN_ECOLE, CENSEUR |
| | `POST /api/v1/inscriptions/{id}/sortie` | ADMIN_ECOLE |
| | `GET /api/v1/inscriptions/{id}` | personnel administratif* |
| Bourse | `PATCH /api/v1/inscriptions/{id}/bourse` `{"statutBourse":"SEMI_BOURSIER"}` (à l'inscription, le statut est saisi avec elle) | ADMIN_ECOLE |
| Liste de classe | `GET /api/v1/classes/{id}/inscriptions?sorties=false` | personnel administratif*, ENSEIGNANT |
| Réinscriptions | `POST /api/v1/classes/{id}/reinscriptions` | ADMIN_ECOLE, CENSEUR |
| Import | `GET /api/v1/annees/{id}/eleves/import/modele`, `POST /api/v1/annees/{id}/eleves/import` | ADMIN_ECOLE, INTENDANT |
| Espace parent | `POST /api/v1/responsables/{id}/espace-parent` | ADMIN_ECOLE, INTENDANT, SECRETARIAT |
| | `POST /api/v1/classes/{classeId}/espaces-parents[?format=pdf\|xlsx]` (v0.29) : ouvre l'espace parent des responsables légaux et des contacts prioritaires des élèves inscrits (un compte pour les frères et sœurs) et renvoie la **fiche de remise des accès** (PDF par défaut) avec le mot de passe provisoire de chaque nouveau compte, « compte existant » sinon ; `409 AUCUN_RESPONSABLE` si personne à traiter | ADMIN_ECOLE, INTENDANT, SECRETARIAT |
| | `GET /api/v1/espace-parent/enfants` | PARENT |

\* ADMIN_ECOLE, CENSEUR, CHEF_TRAVAUX, SECRETARIAT, INTENDANT, SURVEILLANT (CPE). Les dossiers concernent des mineurs : les enseignants voient
les listes de leurs classes seulement (celles où ils ont au moins une matière), pas les dossiers.

## Dossier d'inscription : mode d'admission et pièces (v0.47)

Migration `V34`. Le **mode d'admission** de l'inscription détermine les pièces attendues : `AFFECTATION_ETAT`
(entrée en 6e ou en 2nde après le CEP ou le BEPC : la décision d'orientation remplace l'étude du dossier),
`COMPLEMENT_EFFECTIF` (place attribuée par la commission de recrutement), `TRANSFERT`, `REINSCRIPTION` (posé
automatiquement par la réinscription et le plan de passage), `PRIVE`, `AUTRE`. Vide pour les inscriptions antérieures.

| Appel | Rôles |
|---|---|
| `GET /api/v1/pieces-dossier` : pièces attendues de l'établissement (liste par défaut créée à la première lecture) | personnel administratif* |
| `PUT /api/v1/pieces-dossier` `[{"id":…,"libelle":"…","modes":["TRANSFERT"],"obligatoire":true}]` : remplace la liste (une pièce absente n'est plus demandée) | ADMIN_ECOLE |
| `GET /api/v1/inscriptions/{id}/dossier` → mode, pièces attendues (reçue, date), `complet`, `manquantes` | personnel administratif* |
| `PUT /api/v1/inscriptions/{id}/dossier` `{"modeAdmission":null,"piecesRecues":["…"]}` | ADMIN_ECOLE, INTENDANT |
| `GET /api/v1/annees/{id}/dossiers-inscription?incomplets=true` : élèves inscrits et pièces manquantes | personnel administratif* |

Liste par défaut (pratique des lycées du Burkina Faso) : demande de place adressée au chef d'établissement ; extrait
d'acte de naissance légalisé (ou jugement supplétif) ; décision d'affectation de l'État ; bulletins de l'année
écoulée ; certificat de scolarité de l'établissement d'origine ; copie légalisée du CEP ou du BEPC (facultative) ;
certificat de non-redevance ; photos d'identité. L'intendant enregistre le mode s'il est vide et coche les pièces
reçues ; changer un mode enregistré ou retirer une pièce reçue : chef d'établissement (`403` sinon). Sans mode
d'admission, le dossier n'est pas complet (« Mode d'admission à préciser »).

## Photos d'identité et cartes scolaires (v0.44)

| Appel | Rôles |
|---|---|
| `GET /api/v1/eleves/{id}/photo[?miniature=true]` (JPEG ; miniature 96 × 120 pour les listes) | personnel administratif*, ENSEIGNANT d'une classe actuelle de l'élève |
| `POST /api/v1/eleves/{id}/photo` (multipart, champ `fichier`) | ADMIN_ECOLE, INTENDANT |
| `DELETE /api/v1/eleves/{id}/photo` | ADMIN_ECOLE |
| `GET /api/v1/classes/{id}/photos` → identifiants des élèves de la classe qui ont une photo | personnel administratif*, ENSEIGNANT de la classe |
| `GET /api/v1/eleves/photos?ids=a,b,…` (200 au plus, v0.44.1) → ceux de ces élèves qui ont une photo (page de la recherche) | personnel administratif* |
| `GET /api/v1/classes/{id}/cartes` (élèves actifs, ordre alphabétique) ; `GET /api/v1/eleves/{id}/carte` | ADMIN_ECOLE, INTENDANT, SECRETARIAT |

- Formats acceptés : JPEG, PNG (5 Mo, 8000 pixels de côté au plus). L'image est **ré-encodée** par le serveur
  (JPEG, 480 × 600 au plus) : les métadonnées (position GPS, appareil) disparaissent. Le navigateur réduit déjà la
  photo avant l'envoi (réseau lent).
- La photo s'imprime sur le **bulletin** (au moment de la génération) et la **carte d'identité scolaire** : format
  carte bancaire (ID-1), huit par page A4, avec logo, nom, matricule, date de naissance, classe et année scolaire.
- Elle n'est **pas** montrée dans l'espace parent (choix v0.44). Elle est jointe à l'export de réversibilité
  (`fichiers/photo_eleve/`), et supprimée avec le dossier de l'élève.

## Règles de gestion

| Code d'erreur (409) | Règle |
|---|---|
| `MATRICULE_EXISTANT`, `IDENTIFIANT_EXISTANT` | Matricule et identifiant national uniques dans l'établissement |
| `RESPONSABLE_DEJA_LIE` | Un responsable n'est rattaché qu'une fois au même élève |
| `TELEPHONE_EXISTANT`, `ESPACE_PARENT_OUVERT` | Téléphone d'un responsable : unique ; non modifiable par l'école une fois l'espace parent ouvert |
| `DEJA_INSCRIT` | Une seule inscription par élève et par année |
| `CLASSE_COMPLETE` | Effectif maximal de la classe (verrou transactionnel : pas de dépassement par deux saisies simultanées) |
| `ANNEE_FIGEE` | Inscriptions possibles seulement en préparation ou pendant l'année active |
| `CLASSE_AUTRE_ANNEE` | Un changement de classe reste dans la même année (sinon : réinscription) |
| `INSCRIPTION_TERMINEE` | Un élève transféré ou ayant abandonné n'est plus modifiable |
| `CLASSE_NON_VIDE` | Une classe avec des inscrits ne se supprime pas (module Établissement) |

Autres comportements :

- **Fratrie** : un responsable saisi avec un téléphone déjà connu est réutilisé ; le premier responsable rattaché devient
  contact prioritaire, un nouveau contact prioritaire remplace l'ancien.
- **Statut de bourse** : fixé par inscription (donc par année) ; remis à « non boursier » à la réinscription sauf
  `"conserverStatutBourse": true`. Chaque modification est journalisée (ancien → nouveau).
- **Réinscription** : chaque élève non réinscrit est listé avec sa raison (sorti, déjà inscrit, classe complète…),
  sans bloquer les autres ; l'inscription précédente est conservée (`inscription_precedente_id`) pour le parcours.
- **Espace parent** : un seul compte par téléphone sur toute la plateforme. Le parent qui a des enfants dans deux écoles
  choisit l'établissement à la connexion ; il doit changer son mot de passe temporaire à la première connexion.

## Import Excel

Feuille « Eleves » du modèle (colonnes `*` obligatoires) : Matricule, Nom\*, Prénoms\*, Sexe\*, Date de naissance\*,
Lieu de naissance, Classe\*, Redoublant (O/N), Bourse (B/SB/NB), Nom, Prénoms, Téléphone et Lien du parent.

- 3 000 élèves et 5 Mo au plus par fichier. Listes déroulantes dans le modèle (sexe, classe, redoublant, bourse, lien).
- Dates acceptées : cellule date Excel, `14/02/2014`, `14-02-2014`, `2014-02-14`. Codes de classe sans tenir compte
  des majuscules ni des accents.
- Contrôles : champs obligatoires, valeurs, classe de l'année, téléphone, places restantes, doublons dans le fichier et
  avec la base (même nom, mêmes prénoms, même date de naissance). Un élève déjà connu s'importe avec son matricule.
- Import réel : les lignes valides sont enregistrées en **une seule transaction** ; réimporter un fichier corrigé ne
  crée pas de doublon.

Rapport :

```json
{ "simulation": true, "lignes": 412, "valides": 405, "importees": 0,
  "erreurs": [ { "ligne": 17, "nomComplet": "NIKIEMA Rose", "erreurs": ["Sexe invalide : « X » (M ou F)"] } ] }
```
