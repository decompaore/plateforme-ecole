# Progression pédagogique (v0.23)

Module `progression` : fiche de progression annuelle de chaque matière dans chaque classe, préparée par
l'enseignant, visée par la direction. Migration `V16__chef_travaux_progression.sql`.

## Rôle chef des travaux

Nouveau rôle d'établissement **`CHEF_TRAVAUX`** (Personnel → ajouter un membre). Qui suit et vise les progressions :

| Rôle | Matières suivies |
|---|---|
| ADMIN_ECOLE | Toutes |
| CENSEUR | Matières **générales** ; aussi les matières techniques et pratiques **tant qu'aucun chef des travaux n'est en fonction** (lycée d'enseignement général, petite école) |
| CHEF_TRAVAUX | Matières **techniques, pratiques et modules de compétences** |

Le domaine d'une matière vient de son type (Filières et matières) : `GENERALE` → domaine `GENERAL` ; `TECHNIQUE`,
`PRATIQUE`, `MODULE_COMPETENCES` → domaine `TECHNIQUE`.

## Cycle d'une fiche

`BROUILLON` → (soumission) → `SOUMISE` → (visa) → `VISEE` ou `A_REVOIR` (avec commentaire obligatoire)

- Une fiche **soumise** ne se modifie plus jusqu'à la réponse (`409 FICHE_SOUMISE`).
- Une fiche **à revoir** se corrige puis se soumet à nouveau.
- Une fiche **visée** reste modifiable : la modification la repasse en brouillon, à soumettre de nouveau.
- On ne vise pas sa propre progression (`409 VISA_PAR_AUTEUR`).

## Appels

| Appel | Rôles |
|---|---|
| `GET /api/v1/classes/{classeId}/matieres/{matiereId}/progression` | L'enseignant de la matière ; la direction de son domaine. `id` et `statut` sont `null` tant que la fiche n'est pas commencée ; `modifiable` et `visable` disent ce que la personne peut faire |
| `PUT …/progression` `{"sequences":[{"titre":"Lois de l'électricité","contenu":"Loi d'Ohm…","competences":"C1","heuresPrevues":12,"semaineDebut":"2026-10-05"}]}` | L'enseignant de la matière : remplace toutes les séquences (dans l'ordre donné) |
| `POST …/progression/soumission` | L'enseignant ; au moins une séquence (`409 FICHE_VIDE`) |
| `POST …/progression/visa` `{"accepte":false,"commentaire":"Ajoutez les TP d'atelier"}` | La direction du domaine de la matière |
| `GET /api/v1/annees/{anneeId}/progressions` | ADMIN_ECOLE, CENSEUR, CHEF_TRAVAUX : une ligne par matière du programme de chaque classe de son domaine (statut `null` = non commencée), nombre de séquences, heures prévues, volume hebdomadaire du programme |
| `GET /api/v1/espace-enseignant/progressions` | ENSEIGNANT : ses matières de l'année active |

Séquence : titre (150 caractères), contenu (2 000), compétences (500), heures prévues de 0,5 à 999 (une
décimale), semaine de début facultative ; 60 séquences au plus.

## À venir

- **Cahier de textes** (v0.24) : après chaque cours, l'enseignant note ce qui a été fait ; le censeur et le chef des
  travaux comparent le réalisé au prévu de la fiche.
- Format officiel : si l'inspection impose un modèle de fiche (colonnes, découpage), il s'adaptera sur ces séquences.
