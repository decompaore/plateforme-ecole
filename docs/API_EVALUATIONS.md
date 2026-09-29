# Domaine Évaluations et résultats (v0.6)

Module `evaluations` : évaluations (devoirs, compositions, TP, ateliers), saisie des notes, référentiels et évaluation
des compétences, calcul des résultats d'une période selon le **profil pédagogique** de la classe.
Migration `V7__evaluations.sql`. Les bulletins PDF, leur publication et le SMS aux parents : voir [API_BULLETINS.md](API_BULLETINS.md).

## Saisie

| Étape | Appel | Rôles |
|---|---|---|
| Créer une évaluation | `POST /api/v1/classes/{classeId}/evaluations` `{"matiereId":"…","periodeId":"…","libelle":"Composition","type":"COMPOSITION","date":"2026-11-20","bareme":40,"poids":2}` | ENSEIGNANT de la matière, CENSEUR, ADMIN_ECOLE |
| Lister | `GET /api/v1/classes/{classeId}/evaluations?periodeId=` (avec le nombre de notes saisies) | + SECRETARIAT |
| Feuille de notes | `GET /api/v1/evaluations/{id}/notes` : tous les élèves actifs, notés ou non | idem |
| Saisir / corriger | `PUT /api/v1/evaluations/{id}/notes` `{"notes":[{"inscriptionId":"…","valeur":14.5},{"inscriptionId":"…","absent":true}]}` | ENSEIGNANT de la matière, CENSEUR, ADMIN_ECOLE |
| Modifier, supprimer | `PUT /api/v1/evaluations/{id}`, `DELETE /api/v1/evaluations/{id}` | idem |

- Barème de 1 à 100 (20 par défaut), poids de l'évaluation dans la moyenne de la matière (1 par défaut).
- `absent` : l'élève n'a pas composé, la note ne compte pas. Une ligne sans valeur ni absence **efface** la note.
- La saisie est **idempotente** : renvoyer la même feuille depuis un appareil hors connexion ne change rien.
  Les élèves qui ne sont plus dans la classe sont ignorés et listés dans `ignorees`.

## Compétences (formation professionnelle)

| Étape | Appel |
|---|---|
| Référentiel d'un module | `POST /api/v1/matieres/{matiereId}/competences` `{"code":"C1","libelle":"Réaliser une installation domestique"}` (CENSEUR, ADMIN_ECOLE) ; `GET …` |
| Grille de la classe | `GET /api/v1/classes/{classeId}/competences?periodeId=&matiereId=` |
| Évaluer | `PUT /api/v1/classes/{classeId}/competences?periodeId=&matiereId=` `{"resultats":[{"inscriptionId":"…","competenceId":"…","niveau":"ACQUIS"}]}` |

Niveaux : `ACQUIS`, `EN_COURS`, `NON_ACQUIS` ; un niveau `null` efface l'évaluation.

## Calcul des résultats

`GET /api/v1/classes/{classeId}/resultats?periodeId=` — la stratégie dépend du modèle du profil de la classe.

| Modèle (profil) | Résultat par élève |
|---|---|
| `NOTES_COEFFICIENTS` (général) | Moyenne par matière et rang dans la matière, moyenne générale, rang |
| `NOTES_PAR_GROUPES` (technique) | Idem, **plus** la moyenne de chaque groupe de matières et les notes éliminatoires |
| `COMPETENCES` (professionnel) | Par module : compétences acquises / référentiel, statut ACQUIS / NON_ACQUIS / NON_EVALUE ; taux de maîtrise global ; pas de rang |
| `MIXTE` | Notes pour les matières, compétences pour les modules |

Règles de calcul (validées avec l'établissement pilote) :

1. Chaque note est ramenée sur 20, puis pondérée par le poids de l'évaluation ; une absence à l'évaluation ne compte pas.
2. **Une matière sans aucune note pour l'élève sur la période compte zéro**, avec son coefficient.
3. Moyenne générale = Σ (moyenne de matière × coefficient) / Σ coefficients, **y compris en technique** (les moyennes de
   groupe sont affichées en plus, elles n'entrent pas dans le calcul).
4. Arrondi au plus proche, au nombre de décimales du profil (2 par défaut), sur le résultat final seulement.
5. Rangs : les **ex-æquo ont le même rang** et le rang suivant est sauté (1, 2, 2, 4).
6. Admis sur la période : moyenne ≥ seuil d'admission du profil (10 par défaut) et aucune note éliminatoire.
7. Note éliminatoire (si le profil en définit une) : moyenne sous ce seuil dans une matière **technique ou pratique**.
8. Compétences : module ACQUIS si la part des compétences acquises atteint le seuil de maîtrise du profil (70 % par défaut).

La réponse contient aussi les statistiques de la classe (moyenne, plus forte, plus faible, taux de réussite) et des
**alertes** avant le conseil de classe : `NOTES_MANQUANTES` (élèves sans note ni absence à une évaluation),
`MATIERE_SANS_EVALUATION` (comptée zéro pour tous), `MODULE_SANS_REFERENTIEL`, `COMPETENCES_NON_EVALUEES`.

Exemple vérifié par les tests : maths coef. 4 (devoir /20 poids 1, composition /40 poids 2), français coef. 2.
Awa : maths (16 + 15×2)/3 = 15,33, français 10 → (15,33×4 + 10×2)/6 = **13,56** (1ʳᵉ). Ali et Issa : **12,67**
(2ᵉ ex-æquo). Ines, sans note de français : (10×4 + 0×2)/6 = **6,67** (4ᵉ).

## Règles de gestion

| Code (409) | Règle |
|---|---|
| `PERIODE_VERROUILLEE` | Une période verrouillée ne se modifie plus (notes, évaluations, compétences) |
| `ANNEE_NON_ACTIVE`, `PERIODE_HORS_CLASSE`, `DATE_HORS_PERIODE` | Saisie pendant l'année en cours, dans une période du profil de la classe |
| `MATIERE_HORS_CLASSE`, `MODULE_EN_COMPETENCES`, `PAS_UN_MODULE` | Matière enseignée dans la classe ; un module s'évalue par compétences, une matière par notes |
| `BAREME_INFERIEUR_AUX_NOTES` | Un barème ne descend pas sous une note déjà saisie |
| `COMPETENCE_HORS_MODULE`, `CODE_EXISTANT` | Référentiel du module |

Un enseignant ne saisit que dans les matières qui lui sont **affectées** dans la classe (erreur 403 sinon) ; il consulte
les résultats des classes où il enseigne.
