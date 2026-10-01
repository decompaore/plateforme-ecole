# Domaine Bulletins (v0.7)

Module `bulletins` : appréciations des enseignants, conseil de classe (distinctions, appréciation générale),
génération des bulletins PDF sur une période **verrouillée**, publication aux familles avec SMS, consultation dans
l'espace parent, vérification publique d'un bulletin papier. Migration `V8__bulletins.sql`.
PDF produits avec Apache PDFBox 3 (police Helvetica standard, aucun fichier de police à installer).

## Déroulement d'une fin de période

| Étape | Appel | Rôles |
|---|---|---|
| 1. Appréciations par matière | `GET /api/v1/classes/{classeId}/periodes/{periodeId}/appreciations?matiereId=` ; `PUT …/appreciations` `{"matiereId":"…","appreciations":[{"inscriptionId":"…","texte":"Bon trimestre"}]}` | ENSEIGNANT **de la matière dans la classe**, CENSEUR, ADMIN_ECOLE |
| 2. Verrouiller la période | `POST /api/v1/periodes/{id}/verrouillage` (v0.2) : plus aucune note ne change | CENSEUR, ADMIN_ECOLE |
| 3. Conseil de classe | `GET …/conseil` (moyenne, rang, distinction proposée) ; `PUT …/conseil` `{"avis":[{"inscriptionId":"…","distinction":"ENCOURAGEMENTS","appreciation":"Élève sérieux"}]}` | CENSEUR, ADMIN_ECOLE |
| 4. Générer | `POST …/bulletins` → liste des bulletins (rang, moyenne, distinction, code de vérification) | CENSEUR, ADMIN_ECOLE |
| 5. Contrôler | `GET …/bulletins` ; `GET /api/v1/bulletins/{id}/pdf` ; `GET …/bulletins/pdf` (toute la classe en un PDF, ordre alphabétique, pour l'impression) | + SECRETARIAT |
| 6. Publier | `POST …/bulletins/publication` : visibles dans l'espace parent + SMS au contact prioritaire | CENSEUR, ADMIN_ECOLE |

(`…` = `/api/v1/classes/{classeId}/periodes/{periodeId}`)

- Appréciation : 200 caractères au plus ; un texte vide l'efface. Appréciation du conseil : 300 caractères.
- `distinction` absente (`null`) dans l'avis du conseil : la distinction **proposée** d'après les seuils s'applique.
  Valeurs : `AUCUNE`, `TABLEAU_HONNEUR`, `ENCOURAGEMENTS`, `FELICITATIONS`, `AVERTISSEMENT_TRAVAIL`,
  `AVERTISSEMENT_CONDUITE`, `BLAME`.
- Les résultats sont **figés** à la génération (moyennes, rangs, identité de l'élève, absences de la période) :
  un bulletin ne change pas si un élève change de classe ensuite.
- Avant publication, on peut corriger (déverrouiller, corriger, reverrouiller) puis **régénérer** : l'ancienne
  génération est remplacée. La publication exige une période verrouillée.
- Après publication, plus rien ne change : régénération, appréciations et avis du conseil sont refusés
  (`409 BULLETINS_PUBLIES`).

## Contenu du bulletin

- En-tête : pays, devise, ministère, direction régionale (à gauche) ; établissement, adresse, année (à droite).
- Identité : nom, matricule, date de naissance, redoublant, classe, effectif.
- **Tableau des notes** : Matières, Coef., Moy./20, Points, Rang, Appréciations, Professeur.
  - Lycées et collèges **techniques et professionnels** : une **rubrique par groupe de matières**, chacune suivie
    de sa moyenne. Les groupes contenant « général » viennent toujours en premier (matières générales, puis
    matières techniques). Pour obtenir ces rubriques, renseignez le **groupe** en affectant la matière à la classe,
    par exemple `Enseignement général` et `Enseignement technique` (`PUT /api/v1/classes/{id}/matieres/{matiereId}`).
  - Enseignement général : un seul tableau (pas de groupe).
  - Une matière sans aucune note est marquée `*` (comptée zéro, décision v0.6).
- **Relevé de compétences** (formation professionnelle) : Modules, Compétences, Acquises, Taux, Résultat,
  Appréciations, Formateur ; taux de maîtrise global à la place de la moyenne et du rang.
- Résultats : moyenne générale, rang (1er, 2e…), moyenne de la classe, plus forte, plus faible, admis(e).
- Absences et retards de la période, décision et appréciation du conseil, cadres de signature (censeur,
  chef d'établissement, parent).
- Pied de page : **code de vérification** `XXXXX-XXXXX` et date de production.

## Paramètres de l'établissement

`GET /api/v1/parametres/bulletins` (personnel) ; `PUT /api/v1/parametres/bulletins` (ADMIN_ECOLE) :

```json
{"entetePays":"BURKINA FASO","enteteDevise":"La Patrie ou la Mort, nous Vaincrons",
 "enteteMinistere":"Ministère de l'Enseignement secondaire","enteteDirection":"DREPS du Centre",
 "adresse":"01 BP 000 Ouagadougou 01","seuilTableauHonneur":12,"seuilEncouragements":14,
 "seuilFelicitations":16,"seuilAvertissement":8}
```

Règle : avertissement < tableau d'honneur ≤ encouragements ≤ félicitations ≤ 20 (sinon `400`).
Proposition : ≥ félicitations → Félicitations ; ≥ encouragements → Encouragements ; ≥ tableau d'honneur →
Tableau d'honneur ; < avertissement → Avertissement (travail).

## Espace parent

| Appel | Réponse |
|---|---|
| `GET /api/v1/espace-parent/enfants/{eleveId}/bulletins` | Bulletins **publiés** de l'enfant (toutes années), du plus récent au plus ancien |
| `GET /api/v1/espace-parent/bulletins/{id}/pdf` | Le PDF ; `404` s'il n'est pas publié ou n'est pas celui d'un de ses enfants |

SMS envoyé à la publication (un par élève ayant un contact) :
`Lycée X : bulletin « Trimestre 1 » de Awa OUEDRAOGO (6e A) : moyenne 13,56/20, rang 1er/42. Consultez-le dans l'espace parent.`

## Vérification publique

`GET /api/v1/verification/bulletins/{code}` — **sans connexion**. Le code imprimé en bas du bulletin
(`ABCDE-FGH23`, tirets et casse indifférents). Réponse : établissement, élève, matricule, classe, période, année,
moyenne, rang, effectif, date de publication. `404` si le code est inconnu, si le bulletin n'est pas publié ou si
l'établissement est suspendu. Codes de 10 caractères tirés au hasard (32¹⁰ possibilités) : impossibles à deviner.

## Limites connues (MVP)

- Police standard : les caractères hors alphabet latin occidental (certaines lettres des langues nationales)
  s'impriment `?`.
- PDF générés de façon synchrone et stockés en base (≈ 5 à 10 Ko par bulletin) ; une file de génération viendra
  si les classes dépassent quelques centaines d'élèves.
