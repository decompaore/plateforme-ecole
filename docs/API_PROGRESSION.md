# Progression pédagogique et cahier de textes (v0.23, v0.24)

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

## Cahier de textes (v0.24)

Migration `V17__cahier_textes.sql`. Après chaque cours, l'enseignant note **ce qui a été fait** et le **travail à
faire**, en rattachant la séance à une séquence de sa fiche (ou hors séquence : révision, évaluation).

| Appel | Rôles |
|---|---|
| `PUT /api/v1/cahier-textes/{id}` `{"classeId":"…","matiereId":"…","date":"2026-10-02","heureDebut":"08:00","heureFin":"10:00","sequenceOrdre":1,"contenu":"Loi d'Ohm","travailAFaire":"Exercices 1 à 3"}` | L'enseignant de la matière. Crée ou modifie : **l'identifiant est choisi par le téléphone**, un renvoi après une coupure modifie la même séance |
| `GET /api/v1/classes/{classeId}/matieres/{matiereId}/cahier-textes` | L'enseignant ; la direction du domaine (censeur, chef des travaux, administration). La plus récente d'abord |
| `DELETE /api/v1/cahier-textes/{id}` | L'enseignant de la matière |

Règles :
- pas de séance dans le futur (`409 DATE_FUTURE`) ; fin après le début, 8 heures au plus ; contenu obligatoire
  (2 000 caractères), travail à faire facultatif (1 000) ;
- un seul cours par classe, matière, date et heure de début (`409 SEANCE_EXISTANTE`) ;
- le titre de la séquence est gardé tel qu'au jour du cours ; un numéro de séquence qui n'existe plus (fiche
  raccourcie depuis une saisie hors connexion) laisse la séance **hors séquence**, sans refus.

**Réalisé face au prévu** : la fiche (`GET …/progression`) donne pour chaque séquence `heuresRealisees` et
`seances`, et `avancement` (heures faites, dont hors séquence, nombre de séances, dernière séance). Le suivi de la
direction (`GET /annees/{id}/progressions`) et la liste de l'enseignant donnent le même `avancement` par matière.

## Visa mensuel du cahier de textes (v0.47)

Chaque mois, la direction du domaine (censeur pour les matières générales, chef des travaux pour les matières
techniques et pratiques, le censeur pour tout sans chef des travaux ; le chef d'établissement partout) vise le cahier
de textes de chaque classe et matière. **Un mois visé est figé** : l'enseignant n'y ajoute, ne modifie ni ne
supprime plus de séance (`409 CAHIER_VISE`). Migration `V34` (table `visa_cahier`).

| Appel | Qui |
|---|---|
| `POST /api/v1/classes/{classeId}/matieres/{matiereId}/cahier-textes/visas` `{"mois":"2026-10","observation":"Rythme conforme"}` → `201` avec le nombre de séances du mois | La direction du domaine ; `409 MOIS_A_VENIR`, `409 CAHIER_DEJA_VISE` (un visa par mois) |
| `GET …/cahier-textes/visas` : visas de la matière, le plus récent d'abord | L'enseignant ; la direction du domaine |
| `GET /api/v1/annees/{anneeId}/cahiers-textes?mois=2026-10` : chaque matière du domaine, séances notées dans le mois, visée ou non (par qui) | ADMIN_ECOLE, CENSEUR, CHEF_TRAVAUX |

Écrans : « Progressions › Visa mensuel des cahiers de textes » (`/progression/cahiers`) et, dans le cahier d'une
matière, le formulaire « Visa mensuel ».

## Retard sur le programme et interpellation des enseignants (v0.48.1)

### Retard (suivi des progressions)

Chaque ligne du suivi (`GET /api/v1/annees/{id}/progressions`) porte deux nouveaux champs :
- `retard` : `heuresPrevuesADate`, `heuresRealisees`, `ecart`, `sequencesEnRetard`, `joursSansSeance`, `enRetard` ;
- `interpellations` : le nombre d'interpellations en cours sur la matière.

**Calcul du retard**
- **Échéance d'une séquence.** Une séquence est échue dès que la suivante a commencé (sa semaine de début est passée). La dernière l'est après autant de semaines que ses heures le demandent au volume hebdomadaire du programme.
- **Heures prévues à date.** C'est la somme des heures des séquences échues. Une séquence échue sans aucune séance au cahier de textes compte dans `sequencesEnRetard`.
- **En retard.** `enRetard` est vrai si l'écart atteint une semaine de cours (le volume hebdomadaire, 2 h à défaut), ou si une séquence échue n'a aucune séance.
- **Semaine non renseignée.** Les séquences sans semaine prévue ne comptent pas.

### Interpellation

L'interpellation est adressée par le **censeur** (matières générales), le **chef des travaux** (matières techniques et pratiques) ou le **chef d'établissement** à l'enseignant d'une matière d'une classe. Il en existe deux types :
- **`RAPPEL`** : un message de rappel, sans numéro ni délai ;
- **`DEMANDE_EXPLICATION`** : une lettre numérotée `DE-AAAA-NNNN`, avec un **délai de réponse** (postérieur à aujourd'hui). Le chef d'établissement en reçoit copie dans sa messagerie.

Le constat chiffré (heures prévues à date, heures faites) est **figé à l'émission**. L'interpellation arrive dans la messagerie de l'enseignant. Il l'ouvre (elle passe à `LUE`), puis y répond **une fois** (`REPONDUE`), et l'émetteur reçoit la réponse. La direction note la **suite donnée** et clôt (`CLOSE`). Pour une demande d'explication, la suite est obligatoire.

| Méthode | Chemin | Rôles | Effet |
|---|---|---|---|
| GET | `/api/v1/classes/{c}/matieres/{m}/interpellations/proposition` | Direction du domaine | Constat chiffré proposé, textes de rappel et de demande, délai proposé (J+7) |
| POST | `/api/v1/classes/{c}/matieres/{m}/interpellations` | Direction du domaine | Émet. Corps : `{ type, objet, constat, demande, delaiReponse }`. |
| GET | `/api/v1/interpellations?anneeId=&classeId=&matiereId=` | ADMIN_ECOLE, CENSEUR, CHEF_TRAVAUX | Interpellations de son domaine (toutes pour le chef d'établissement) |
| GET | `/api/v1/espace-enseignant/interpellations` | ENSEIGNANT | Celles que l'enseignant a reçues |
| GET | `/api/v1/interpellations/{id}` | Direction du domaine, enseignant destinataire | Détail. L'ouverture par l'enseignant la marque lue. |
| POST | `/api/v1/interpellations/{id}/reponse` | Enseignant destinataire | Réponse. Corps : `{ "reponse" }`. |
| POST | `/api/v1/interpellations/{id}/cloture` | Direction du domaine | Clôture. Corps : `{ "suite" }`. |
| GET | `/api/v1/interpellations/{id}/lettre` | Direction du domaine, enseignant destinataire | Lettre en PDF A4, avec en-tête officiel, constat, demande, réponse, suite et signature numérisée de l'émetteur |

**Erreurs**

| Code | Statut HTTP | Cas |
|---|---|---|
| — | 403 | Matière hors du domaine (par exemple, le chef des travaux sur une matière générale) |
| — | 400 | Délai manquant ou passé pour une demande d'explication ; clôture d'une demande d'explication sans suite |
| `MATIERE_SANS_ENSEIGNANT` | 409 | La matière n'a pas d'enseignant |
| `AUTO_INTERPELLATION` | 409 | La personne s'interpelle elle-même |
| `DEJA_REPONDUE` | 409 | Seconde réponse |
| `INTERPELLATION_CLOSE` | 409 | Action sur une interpellation close |

Ces routes appartiennent au module « Progressions et cahier de textes ».

**Interface**
- **Direction :** dans « Progressions », un filtre « En retard » et une pastille de retard par matière, puis le bouton « Interpeller ». L'écran « Interpellations » (`/progression/interpellations`) permet la rédaction, le suivi, la suite et la lettre.
- **Enseignant :** tuile « Mes interpellations », pour la lecture, la réponse et la lettre.

## À venir

- Format officiel : si l'inspection impose un modèle de fiche (colonnes, découpage), il s'adaptera sur ces séquences.
