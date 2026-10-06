# Export complet des données — réversibilité (v0.33)

Module `reversibilite`. Les données d'un établissement lui appartiennent : son administrateur, ou le
super administrateur de la plateforme, peut à tout moment en obtenir une copie complète dans des formats
ouverts, pour la conserver ou la reprendre dans un autre logiciel. Migration `V24__exports_donnees.sql`.

## Parcours

1. L'administrateur ouvre **Administration → Données** et saisit son mot de passe (il est redemandé : l'archive
   contient les données personnelles de tous les élèves).
2. L'archive est préparée **en tâche de fond** (quelques secondes à quelques minutes) ; la page se met à jour seule.
3. Il la télécharge par **« Télécharger »** : l'API délivre un lien signé valable 10 minutes, que le navigateur
   ouvre lui-même (barre de progression, reprise après une coupure de réseau).
4. L'archive reste sur le serveur **7 jours**, puis elle est effacée. Seules les 3 archives prêtes les plus
   récentes sont gardées.

Le super administrateur fait de même depuis **Établissements → Données**, y compris pour un établissement
**suspendu ou résilié** (un établissement qui quitte la plateforme récupère ainsi ses données). L'établissement voit
ces exports dans sa propre liste, avec la mention « Plateforme (super administrateur) », et dans son journal d'audit.

## Points d'accès

| Méthode | Chemin | Rôle | Effet |
|---|---|---|---|
| GET | `/api/v1/exports` | ADMIN_ECOLE | Exports de l'établissement, le plus récent d'abord |
| POST | `/api/v1/exports` | ADMIN_ECOLE | `{ "motDePasse": "…" }` → `202` et l'export `EN_COURS` |
| POST | `/api/v1/exports/{id}/lien` | ADMIN_ECOLE | `{ "chemin": "/telechargements/exports/{id}?jeton=…", "expireLe" }` (chemin relatif à `/api/v1`) |
| GET | `/api/v1/plateforme/etablissements/{etab}/exports` | SUPER_ADMIN | Idem pour un établissement |
| POST | `/api/v1/plateforme/etablissements/{etab}/exports` | SUPER_ADMIN | Idem (mot de passe du super administrateur) |
| POST | `/api/v1/plateforme/etablissements/{etab}/exports/{id}/lien` | SUPER_ADMIN | Idem |
| GET | `/api/v1/telechargements/exports/{id}?jeton=…` | aucun (le lien signé) | L'archive ZIP ; reprise par `Range` acceptée |

Vue d'un export : `id`, `statut`, `demandeLe`, `demandePar`, `parPlateforme`, `termineLe`, `expireLe`, `taille`
(octets), `empreinte` (SHA-256 de l'archive), `nombreTables`, `nombreLignes`, `erreur`, `telechargements`,
`dernierTelechargement`.

| Statut | Sens |
|---|---|
| `EN_COURS` | Archive en préparation |
| `PRET` | Téléchargeable jusqu'à `expireLe` |
| `ECHEC` | Préparation échouée (détail dans les journaux du serveur) |
| `EXPIRE` | Archive effacée du serveur (durée dépassée ou archive plus récente) |
| `INTERROMPU` | Préparation arrêtée par un redémarrage du serveur (plus d'une heure « en cours ») |

Erreurs (`409`, champ `code`) : `MOT_DE_PASSE_INCORRECT` (les échecs comptent comme à la connexion : après 5, le
compte est verrouillé 15 minutes), `COMPTE_VERROUILLE`, `EXPORT_EN_COURS` (un seul export en préparation par
établissement), `EXPORTS_TROP_NOMBREUX` (5 par 24 heures), `EXPORT_PAS_PRET`, `EXPORT_EXPIRE`. Un lien falsifié,
expiré ou utilisé pour une autre archive : `403`.

## Contenu de l'archive

```
LISEZMOI.txt                  explication en français
manifeste.json                description des tables, colonnes, clés, nombre de lignes, empreintes
SHA256SUMS.txt                empreinte de chaque fichier (sha256sum -c SHA256SUMS.txt)
donnees/<table>.csv           une table = un fichier (80 tables en v0.33)
fichiers/bulletin/<id>.pdf    bulletins PDF tels qu'ils ont été publiés
fichiers/photo_article/<id>.jpg  photos du catalogue des ateliers
comptes/utilisateurs.csv      personnes citées dans les données : nom, prénoms, téléphone, courriel
```

**Format CSV** : UTF-8 avec BOM (s'ouvre directement dans Excel ou LibreOffice), séparateur `;`, fin de ligne
CRLF, guillemets seulement si nécessaire (doublés à l'intérieur), champ vide = pas de valeur, dates `AAAA-MM-JJ`,
instants ISO 8601 en UTC, décimales avec un point, booléens `true`/`false`. Les liens entre tables passent par les
identifiants (`id`, `*_id`), comme dans la base. Une colonne binaire contient le chemin du fichier dans l'archive.

**Jamais exportés** : mots de passe (même hachés), informations de connexion, clés d'API chiffrées (colonnes
`*_chiffre`/`*_chiffree`, inutilisables hors de la plateforme), adresses IP du journal d'audit (que l'établissement
ne voit pas non plus), comptes du super administrateur. Le manifeste liste ces colonnes (`colonnesNonExportees`).

## Garanties

- **Exhaustif sans maintenance** : les tables sont lues dans le catalogue de PostgreSQL (toutes celles qui ont la
  Row-Level Security). Une table ajoutée par une migration future est exportée sans modifier le code.
- **Cloisonné** : la lecture se fait avec le rôle applicatif (sans BYPASSRLS), l'établissement fixé dans la
  transaction ; les tables qui ont `tenant_id` sont en plus filtrées explicitement. Si le rôle contourne la RLS,
  l'export est refusé. Le test `ExportsIntegrationTest` vérifie qu'aucun identifiant d'un autre établissement
  n'apparaît dans l'archive.
- **Cohérent** : une seule transaction en lecture seule `REPEATABLE READ` — photographie de la base à un instant,
  même si des saisies ont lieu pendant l'export.
- **Vérifiable** : empreinte SHA-256 de l'archive affichée dans l'application, et de chaque fichier dans
  `SHA256SUMS.txt`.
- **Tracé** : `EXPORT_COMPLET_DEMANDE`, `EXPORT_COMPLET_TELECHARGE` et `CONFIRMATION_ECHEC` dans le journal d'audit
  de l'établissement.
- **Mémoire maîtrisée** : les lignes sont lues par paquets et écrites au fil de l'eau ; les fichiers binaires un par
  un. Un seul export à la fois par instance de l'API.

## Configuration (`app.exports`)

| Clé | Défaut | Effet |
|---|---|---|
| `repertoire` | `${EXPORTS_REPERTOIRE}` ou `<tmp>/plateforme-exports` | Dossier des archives. **En production : volume partagé par les instances** (`/var/lib/plateforme/exports`, volume `exports` du docker-compose) |
| `duree-conservation` | 7d | Archive téléchargeable pendant cette durée, puis effacée (purge horaire) |
| `duree-max` | 1h | Au-delà, un export « en cours » est considéré comme interrompu |
| `duree-lien` | 10m | Validité d'un lien de téléchargement |
| `max-par-jour` | 5 | Exports par établissement et par 24 heures |
| `purge-automatique` | true | Effacement horaire des archives expirées |

Le lien de téléchargement porte un jeton signé dans l'adresse : ne pas journaliser les paramètres des URL
`/api/v1/telechargements/**` sur le reverse proxy (Caddy ne journalise pas les requêtes par défaut).
