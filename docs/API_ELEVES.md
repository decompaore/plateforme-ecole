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

## Parcours type de la rentrée

| # | Appel | Rôle |
|---|---|---|
| 1 | `GET /api/v1/annees/{id}/eleves/import/modele` : modèle Excel avec la liste des classes | ADMIN_ECOLE, SECRETARIAT |
| 2 | `POST /api/v1/annees/{id}/eleves/import?simulation=true` (multipart, champ `fichier`) : rapport ligne par ligne | ADMIN_ECOLE, SECRETARIAT |
| 3 | Même appel avec `simulation=false` : les lignes valides sont enregistrées, les autres restent dans le rapport | ADMIN_ECOLE, SECRETARIAT |
| 4 | Nouveaux élèves isolés : `POST /api/v1/eleves` puis `POST /api/v1/inscriptions` | ADMIN_ECOLE, SECRETARIAT |
| 5 | Anciens élèves : `POST /api/v1/classes/{classeCible}/reinscriptions` `{"inscriptions":[…],"redoublant":false}` | ADMIN_ECOLE, SECRETARIAT |
| 6 | Espace parent : `POST /api/v1/responsables/{id}/espace-parent` → mot de passe temporaire à transmettre | ADMIN_ECOLE, SECRETARIAT |

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
| | `POST /api/v1/eleves`, `PUT /api/v1/eleves/{id}` | ADMIN_ECOLE, SECRETARIAT |
| Responsables | `POST /api/v1/eleves/{id}/responsables`, `DELETE /api/v1/eleves/{id}/responsables/{responsableId}`, `PUT /api/v1/responsables/{id}` | ADMIN_ECOLE, SECRETARIAT |
| Inscriptions | `POST /api/v1/inscriptions`, `PATCH /api/v1/inscriptions/{id}/classe`, `POST /api/v1/inscriptions/{id}/sortie` | ADMIN_ECOLE, SECRETARIAT |
| | `GET /api/v1/inscriptions/{id}` | personnel administratif* |
| Bourse | `PATCH /api/v1/inscriptions/{id}/bourse` `{"statutBourse":"SEMI_BOURSIER"}` | ADMIN_ECOLE, SECRETARIAT, INTENDANT |
| Liste de classe | `GET /api/v1/classes/{id}/inscriptions?sorties=false` | personnel administratif*, ENSEIGNANT |
| Réinscriptions | `POST /api/v1/classes/{id}/reinscriptions` | ADMIN_ECOLE, SECRETARIAT |
| Import | `GET /api/v1/annees/{id}/eleves/import/modele`, `POST /api/v1/annees/{id}/eleves/import` | ADMIN_ECOLE, SECRETARIAT |
| Espace parent | `POST /api/v1/responsables/{id}/espace-parent` | ADMIN_ECOLE, SECRETARIAT |
| | `GET /api/v1/espace-parent/enfants` | PARENT |

\* ADMIN_ECOLE, CENSEUR, SECRETARIAT, INTENDANT, SURVEILLANT. Les dossiers concernent des mineurs : les enseignants voient
les listes de classe, pas les dossiers. Quand le module Enseignants existera, un enseignant ne verra que ses classes.

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
