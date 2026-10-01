# Passage d'année (v0.10)

Module `passage` : moyenne annuelle, décisions de fin d'année proposées par la plateforme puis validées en conseil de
classe, classes d'examen, création de l'année suivante, réinscriptions en masse. Migration `V11__passage_annee.sql`.

Le processus suit cinq étapes :

1. **Clôture des notes** : verrouiller toutes les périodes (`POST /api/v1/periodes/{id}/verrouillage`).
2. **Décisions** : calcul, conseil de classe, validation avec SMS aux familles.
3. **Année suivante** : copie de la structure et des frais.
4. **Réinscriptions** en masse d'après les décisions.
5. **Clôture puis archivage** de l'année (`POST /api/v1/annees/{id}/cloture`, puis `…/archivage`), qui reste consultable
   en lecture seule. On peut ensuite ouvrir la nouvelle année.

## Réglages de l'établissement

| Appel | Rôle |
|---|---|
| `GET /api/v1/parametres/passage` ; `PUT …` `{"seuilExclusion":8.5,"redoublementsMax":2}` | ADMIN_ECOLE (modification) |
| `GET /api/v1/profils/{profilId}/poids-periodes` ; `PUT …` `{"poids":[{"ordre":1,"poids":1},{"ordre":2,"poids":2},{"ordre":3,"poids":2}]}` | ADMIN_ECOLE (modification) |
| `PUT /api/v1/classes/{classeId}/examen` `{"examen":"BEPC"}` (vide : plus d'examen) | CENSEUR, ADMIN_ECOLE |

- **Moyenne annuelle** = Σ (poids × moyenne de la période) / Σ poids. Par défaut, chaque période pèse 1 (moyenne
  simple des trimestres). Le poids se règle par profil pédagogique et vaut pour toutes les années.
- `seuilExclusion` ou `redoublementsMax` à `null` : la règle est désactivée.

## Propositions de la plateforme

| Situation | Proposition |
|---|---|
| Moyenne annuelle ≥ seuil d'admission du profil (10 par défaut) | `ADMIS` |
| En dessous | `REDOUBLE` |
| Non admis **et** moyenne annuelle < seuil d'exclusion (8,5 par défaut) | `EXCLU` |
| Non admis **et** a déjà redoublé ce niveau 2 fois (années précédentes dans l'établissement) | `EXCLU` |
| Classe d'examen, résultat pas encore saisi | `EN_ATTENTE_EXAMEN` |
| Classe d'examen, admis à l'examen | `ADMIS` (`CERTIFIE` en formation par compétences) |
| Classe d'examen, ajourné | `REDOUBLE` (`NON_CERTIFIE` en compétences), sauf exclusion |
| Formation par compétences, hors examen | `ADMIS` si tous les modules sont acquis à la dernière période |

Rang annuel : les ex-æquo ont le même rang et le rang suivant est sauté.

## Conseil de classe

| Étape | Appel | Rôle |
|---|---|---|
| Calculer (ou recalculer) | `POST /api/v1/classes/{classeId}/decisions` | CENSEUR, ADMIN_ECOLE |
| Consulter | `GET /api/v1/classes/{classeId}/decisions` | + SECRETARIAT |
| Décider, saisir l'examen | `PUT /api/v1/classes/{classeId}/decisions` | CENSEUR, ADMIN_ECOLE |
| Valider | `POST /api/v1/classes/{classeId}/decisions/validation` | CENSEUR, ADMIN_ECOLE |

```json
{"decisions":[
  {"inscriptionId":"…","decision":"ADMIS","motif":"Rachat : 9,80 et bon comportement"},
  {"inscriptionId":"…","resultatExamen":"ADMIS"}]}
```

- Toutes les périodes de la classe doivent être verrouillées (`409 PERIODES_NON_VERROUILLEES`).
- Le conseil peut remplacer toute proposition (`ADMIS`, `REDOUBLE`, `EXCLU`, `ORIENTE`, `CERTIFIE`, `NON_CERTIFIE`),
  mais **toujours avec un motif**. Un recalcul conserve ses décisions.
- En classe d'examen, on saisit le résultat de chaque élève avant de décider ou de valider
  (`409 RESULTATS_EXAMEN_MANQUANTS`).
- Après validation, les décisions sont **définitives** (`409 DECISIONS_VALIDEES`). Chaque famille reçoit un SMS :
  « Lycée X : année 2026-2027, Awa OUEDRAOGO (6e A) est admis(e) en classe supérieure (moyenne annuelle 10,40/20). »

## Année suivante et réinscriptions

| Appel | Effet |
|---|---|
| `GET /api/v1/annees/{anneeId}/passage` | Tableau de bord : classes, décisions calculées et validées, répartition |
| `POST /api/v1/annees/{anneeId}/annee-suivante` `{"libelle":"2027-2028","debut":"2027-10-01","fin":"2028-07-31"}` (ADMIN_ECOLE) | Copie les classes, matières, coefficients, périodes (décalées d'un an), **frais** et classes d'examen |
| `POST /api/v1/classes/{classeId}/decisions/reinscriptions` `{"classeAdmisId":"…","classeRedoublantsId":"…","conserverStatutBourse":false}` (SECRETARIAT, ADMIN_ECOLE) | Réinscrit les **admis** dans la classe supérieure et les **redoublants** (et non certifiés) dans la classe du même niveau, avec la mention redoublant |

- Les décisions de la classe doivent être validées.
- Exclus, orientés et certifiés ne sont pas réinscrits (un orienté se réinscrit à la main).
- Le statut de bourse repart à « non boursier » pour la nouvelle année, sauf `conserverStatutBourse:true`. Le
  secrétariat le redéfinit élève par élève, et l'échéancier de l'année suivante en découle.
- Les frais repris gardent montants, portée et tranches (dates décalées d'un an). Un frais dont la classe n'existe
  plus, ou dont les dates ne tombent pas dans la nouvelle année, est à recréer à la main.
- Les classes de niveau supérieur qui n'existaient pas (ex. 5e A quand l'école n'avait que la 6e) se créent dans la
  nouvelle année avant la réinscription.
