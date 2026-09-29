# Statistiques (v0.12)

Module `statistiques` : statistiques de l'établissement pour une année scolaire. Tout est calculé à partir des
données déjà saisies, sans ressaisie, et le module n'a aucune table propre.

| Appel | Réponse |
|---|---|
| `GET /api/v1/annees/{anneeId}/statistiques` | Rapport complet (JSON) |
| `GET /api/v1/annees/{anneeId}/statistiques/excel` | Classeur Excel, une feuille par tableau |

Rôles : ADMIN_ECOLE, CENSEUR, SECRETARIAT, INTENDANT.

## Contenu

| Feuille | Contenu |
|---|---|
| Effectifs | Par niveau (6e, 5e, 4e, 3e, 2nde, 1re, Tle, puis les autres) : nombre de classes, élèves G / F / total, redoublants G / F |
| Âges | Par niveau et par âge : G / F. Âge révolu au **31 décembre de l'année de la rentrée** |
| Bourses | Par filière : boursiers, semi-boursiers et non boursiers, G / F |
| Personnel | Enseignants en fonction : titulaires et vacataires, H / F ; personnel administratif par fonction |
| Recouvrement | Par classe : dû et payé par les familles et par les organismes, taux ; total de l'établissement |
| Résultats | Par niveau : décisions de fin d'année (admis, redouble, exclu, orienté, certifié, non certifié), taux d'admission |

- Les effectifs comptent les élèves **inscrits à la date du rapport** (hors transférés et abandons).
- Le recouvrement suit les règles du module Scolarité : échéancier calculé, paiements non annulés.
- Les résultats n'apparaissent qu'une fois les décisions de fin d'année calculées (module Passage).

## Format officiel

Ces tableaux reprennent les rubriques des statistiques de rentrée demandées par le dossier de cadrage. Si votre
direction régionale impose un modèle précis (colonnes, tranches d'âge, ordre des niveaux), transmettez-le : il
suffira d'adapter la feuille Excel correspondante, sans toucher au calcul.
