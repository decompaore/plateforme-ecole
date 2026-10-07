# Territoire et documents officiels (v0.35)

Module `territoire` et en-tête officiel commun (`socle.documents`). Chaque établissement est rattaché à la chaîne
administrative de son ministère de tutelle, qui figure sur ses documents officiels avec son logo. Migration
`V26__territoire_et_documents.sql`.

```
Pays (Burkina Faso : indicatif +226, numéros à 8 chiffres, Africa/Ouagadougou, XOF, devise nationale)
 └─ Ministère (sigle et nom complet ; noms des niveaux de directions, 1 à 5)
     └─ Direction de niveau 1 (ex. Direction régionale)
         └─ Direction de niveau 2 (ex. Direction provinciale)   ← dernier niveau
             └─ Établissement
```

Le nombre de niveaux et leurs noms sont propres à chaque ministère : un autre pays peut n'avoir qu'un niveau, ou
trois. Le référentiel n'est pas figé dans le code : le super administrateur le saisit ou l'importe.

## Règles

- Une direction de niveau 1 n'a pas de parent ; une direction de niveau *n* a pour parent une direction de niveau
  *n − 1* du même ministère. Une direction ne change jamais de niveau.
- Le **nombre** de niveaux d'un ministère est figé dès qu'il a des directions (leurs **noms** restent modifiables).
- Un établissement se rattache à une direction **active** du **dernier niveau** d'un ministère actif.
- Rien n'est supprimé : ministères et directions se désactivent (les établissements déjà rattachés le restent).
- La migration crée le pays « Burkina Faso » avec les paramètres utilisés jusqu'ici ; la devise nationale, les
  ministères et les directions sont à saisir.

## Points d'accès du super administrateur (`/api/v1/plateforme/territoire`)

| Méthode | Chemin | Effet |
|---|---|---|
| GET / POST | `/pays` | Liste / création (code ISO à 2 lettres, nom, devise nationale, indicatif, longueur d'un numéro national, fuseau IANA, monnaie ISO, langue) |
| PUT | `/pays/{id}` | Modification |
| GET / POST | `/pays/{paysId}/ministeres` | `{ sigle, nom, actif, niveaux: ["Direction régionale", "Direction provinciale"] }` |
| PUT | `/ministeres/{id}` | Modification (`409 NIVEAUX_FIGES` si le nombre de niveaux change alors qu'il y a des directions) |
| GET / POST | `/ministeres/{id}/directions` | Arbre à plat ; création `{ parentId, code, nom, actif }` |
| PUT | `/directions/{id}` | Modification (`409 NIVEAU_DIRECTION` si le parent change de niveau) |
| POST | `/ministeres/{id}/directions/import` | `{ contenu, simulation }` : import CSV, voir ci-dessous |
| GET | `/directions` | Toutes les directions avec leur chemin complet et `terminale` (filtres, rattachement) |

Établissements (`/api/v1/plateforme/etablissements`) :

- la création accepte `directionId` (facultatif) ;
- `PUT /{id}/rattachement { directionId }` (`409 RATTACHEMENT_INVALIDE` si la direction n'est pas active ou pas du
  dernier niveau) ;
- `GET ?direction=` : seulement les établissements qui dépendent de cette direction, à tout niveau. Chaque
  établissement porte `directionId` et `rattachement` (« Burkina Faso · MESFPT · Direction régionale … · Direction
  provinciale … »).

La page Adoption accepte le même filtre (`GET /api/v1/plateforme/adoption?direction=`).

### Import CSV des directions

Un fichier par ministère (dans Excel : « Enregistrer sous » → CSV), une direction par ligne :

```
code;nom;code_parent
DR-CEN;Direction régionale de l'enseignement … du Centre;
DP-KAD;Direction provinciale de l'enseignement … du Kadiogo;DR-CEN
```

- Séparateur `;`, `,` ou tabulation ; ligne d'en-têtes facultative ; ordre des lignes indifférent.
- `code_parent` vide pour le niveau 1 ; le parent peut être dans le fichier ou déjà enregistré.
- Une direction déjà présente (même code) est mise à jour (nom, parent du même niveau).
- **Tout ou rien** : à la moindre erreur (code invalide ou en double, parent introuvable, niveau trop profond,
  changement de niveau), rien n'est enregistré et le rapport liste les lignes fautives. `simulation: true` vérifie
  sans enregistrer : c'est le bouton « Vérifier » de l'application, qui précède « Importer ».

## Établissement (`/api/v1/identite`, administrateur)

| Méthode | Chemin | Effet |
|---|---|---|
| GET | `/identite` | Nom, pays, devise, autorités (ministère puis directions), `rattache`, logo (type, taille, date) |
| GET | `/identite/logo` | L'image |
| POST | `/identite/logo` | Fichier `fichier` (multipart) : PNG ou JPEG, **500 Ko au plus**, type contrôlé sur le contenu |
| DELETE | `/identite/logo` | Retire le logo |
| GET | `/identite/apercu` | PDF d'exemple avec l'en-tête tel qu'il sera imprimé |

Le rattachement est en lecture seule pour l'établissement : il est fixé par la plateforme.

## En-tête des documents officiels

```
            BURKINA FASO                                     [logo]
         Devise nationale                           Lycée technique …
             ——————                                 Année scolaire …
   Ministère des enseignements …
   Direction régionale de … du Centre
   Direction provinciale de … du Kadiogo
```

- **Bulletins** : en-tête complet ; sans rattachement, les réglages d'en-tête des bulletins de l'établissement
  servent comme avant.
- **Reçus de paiement**, **fiches de remise des accès des parents**, **exports PDF** (ateliers, emplois du temps…) :
  en-tête complet en première page, nom de l'établissement sur les pages suivantes. Les exports Excel portent le nom
  de l'établissement et une ligne de rattachement.
- Un logo illisible n'empêche jamais un document : il est simplement omis.
- Les bulletins déjà produits gardent leur en-tête ; les nouveaux bulletins prennent le nouveau.

## Indicatif téléphonique par pays

Un numéro saisi sans indicatif reçoit celui du **pays de l'établissement** (d'après son rattachement), si sa longueur
est celle d'un numéro national de ce pays (8 chiffres au Burkina Faso, par exemple). Sans rattachement : l'indicatif
de la plateforme (`app.plateforme.indicatif-telephone`, +226). La connexion, faite avant le choix de l'établissement,
utilise l'indicatif de la plateforme : une personne d'un autre pays saisit son numéro avec son indicatif (+225…).

Le fuseau horaire et la monnaie sont enregistrés pour chaque pays mais pas encore appliqués : l'application
fonctionne à l'heure de Ouagadougou et en francs CFA (version à venir).

## Export complet (v0.33)

Le manifeste de l'archive porte le rattachement (`etablissement.rattachement`) et le logo figure dans
`fichiers/logo_etablissement/`.
