# Mesure de l'adoption (v0.34)

Module `adoption`. Savoir qui utilise réellement l'application et pour quoi faire : pour le super administrateur
(accompagner les établissements qui décrochent) et pour l'administrateur de chaque établissement (accompagner les
membres du personnel qui ne l'utilisent pas encore). Migration `V25__mesure_adoption.sql`.

## D'où viennent les chiffres

| Mesure | Source | Mise à jour |
|---|---|---|
| **Jour d'activité** d'une personne dans un établissement | Ouverture ou renouvellement d'une session (`SessionsAppareils`) : table `activite_jour`, une ligne par personne, établissement et jour. L'enseignant qui fait l'appel sans réseau est compté quand son téléphone se reconnecte | En direct |
| **Actions** par établissement et par jour | Fonction SQL `calculer_adoption(jour)` : table `mesure_adoption` (nombres seulement) | Chaque nuit à 0 h 20 (veille et avant-veille) ; le jour en cours est recalculé au plus toutes les 10 minutes lors d'une consultation |

Indicateurs : `APPELS` (appels faits), `NOTES`, `EVALUATIONS`, `CAHIER` (séances du cahier de textes),
`JUSTIFICATIFS`, `INCIDENTS`, `PAIEMENTS` (guichet), `MOBILE_MONEY` (confirmés), `SMS` (envoyés), `BULLETINS`
(publications), `PROGRESSIONS` (fiches soumises), `EMPLOI_DU_TEMPS` (séances placées ou modifiées), `ATELIERS`
(mouvements de stock), `ELEVES` (élèves ajoutés).

**Historique** : la migration reconstitue les jours d'activité passés à partir des connexions du journal d'audit et
des saisies (appels, notes, cahier de textes) ; au premier démarrage, l'application calcule en arrière-plan les
actions des jours passés (jusqu'à 400 jours, `app.adoption.historique-jours`).

## Points d'accès

| Méthode | Chemin | Rôle | Effet |
|---|---|---|---|
| GET | `/api/v1/adoption?jours=30` | ADMIN_ECOLE | Utilisation dans l'établissement, avec l'activité de chaque membre du personnel |
| GET | `/api/v1/plateforme/adoption?jours=30` | SUPER_ADMIN | Tous les établissements, uniquement des nombres |
| POST | `/api/v1/plateforme/adoption/calcul?jours=30` | SUPER_ADMIN | Recalcule les actions des derniers jours puis renvoie la vue |

`jours` : de 1 à 366 (défaut 30). La période se termine aujourd'hui.

**Vue établissement** : `comptes`, `actifs`, `personnel`, `enseignants`, `parents` (chacun `{ total, actifs }`),
`actions` (`{ "APPELS": 120, … }`), `quotidien` (`[{ jour, actifs }]`, un élément par jour), `personnes` (personnel
seulement, sans les parents ni les élèves : `nom`, `prenoms`, `roles`, `joursActifs`, `derniereActivite`, `appels`,
`notes`, `cahier` sur la période), `indicateurs` (codes et libellés, dans l'ordre d'affichage).

**Vue plateforme** : totaux des établissements actifs, `quotidien` (personnes actives par jour sur toute la
plateforme), et `details` : pour chaque établissement `comptes`, `actifs`, `enseignants`, `parents`, `joursActifs`,
`derniereActivite`, `actions` et `alertes`.

| Alerte (établissements actifs seulement) | Condition |
|---|---|
| `SANS_ACTIVITE` | Personne n'a utilisé l'application depuis 7 jours |
| `PEU_D_ENSEIGNANTS` | Moins de la moitié des enseignants actifs sur la période |
| `SANS_APPEL` | Des enseignants sont actifs mais aucun appel n'a été fait dans l'application |

Les établissements avec une alerte sont listés en premier.

## Données personnelles

- Le super administrateur ne lit que des **nombres**, par des fonctions `SECURITY DEFINER`
  (`adoption_etablissements`, `adoption_indicateurs`, `adoption_quotidienne`) : aucun nom, aucun téléphone.
- L'administrateur d'un établissement voit, pour **son personnel**, les jours d'activité et le nombre d'appels, de
  notes et de séances du cahier de textes ; pas l'activité individuelle des parents (seulement leur nombre).
- `activite_jour` ne contient qu'une date par personne et par établissement : ni heure, ni adresse IP, ni appareil.
  Elle fait partie de l'export complet de l'établissement (v0.33).

## Configuration (`app.adoption`)

| Clé | Défaut | Effet |
|---|---|---|
| `calcul-automatique` | true | Calcul nocturne et rattrapage au démarrage (désactivé dans le profil `test`) |
| `cron` | `0 20 0 * * *` | Heure du calcul nocturne (Ouagadougou) |
| `historique-jours` | 400 | Jours passés calculés au démarrage s'ils ne l'ont jamais été |

Le calcul est protégé par un verrou PostgreSQL (`pg_advisory_xact_lock`) : les deux instances de l'API peuvent le
lancer en même temps sans doublon.
