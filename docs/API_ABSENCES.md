# Domaine Absences et notifications (v0.5)

Modules `absences` (appel, absences, retards, justificatifs, synthèses) et `notifications` (file d'envoi des SMS et
worker). Migration `V6__absences_notifications.sql`.

## Appel hors connexion : le principe

1. L'application (PWA) garde en cache la liste des élèves des classes de l'enseignant
   (`GET /api/v1/espace-enseignant/affectations`, puis `GET /api/v1/classes/{id}/inscriptions`).
2. L'enseignant fait l'appel, même sans réseau. L'appareil génère un **identifiant unique** (`idClient`, UUID) et
   place l'appel dans sa file locale.
3. Au retour du réseau, l'appareil envoie sa file : `POST /api/v1/appels/lot` (50 appels au plus).
4. Le serveur répond **un accusé par appel** ; l'appareil retire de sa file les appels ENREGISTRE, DEJA_RECU,
   MODIFIE, et signale à l'utilisateur les REFUSE.

Renvoyer un appel déjà reçu (coupure pendant l'envoi) est **sans effet** : accusé `DEJA_RECU`. Chaque appel du lot est
enregistré dans sa propre transaction : un appel refusé n'empêche jamais les autres de passer.

```json
POST /api/v1/appels/lot
{ "appels": [ {
    "idClient": "9f1c…", "classeId": "…", "matiereId": "…",
    "date": "2026-09-28", "heureDebut": "08:00", "heureFin": "10:00",
    "saisiLe": "2026-09-28T08:05:12Z",
    "marques": [ { "inscriptionId": "…", "type": "ABSENCE" },
                 { "inscriptionId": "…", "type": "RETARD", "minutesRetard": 15 } ] } ] }

→ [ { "idClient": "9f1c…", "statut": "ENREGISTRE", "absents": 1, "retards": 1, "inscriptionsIgnorees": [] } ]
```

Seuls les élèves **absents ou en retard** sont envoyés ; les autres sont présents. Un élève inconnu de la classe est
ignoré et listé dans `inscriptionsIgnorees`.

## Règles

| Code (accusé REFUSE ou erreur 409) | Règle |
|---|---|
| `NON_AFFECTE` | L'enseignant fait l'appel des seules classes où il a une matière ; surveillant, censeur et administrateur : toutes les classes |
| `ANNEE_NON_ACTIVE`, `HORS_ANNEE`, `DATE_FUTURE` | Appel pendant l'année en cours, à une date passée ou du jour |
| `CRENEAU_DEJA_FAIT` | Un seul appel par classe et par créneau (heure de début), même depuis deux appareils |
| `IDENTIFIANT_DEJA_UTILISE` | Un `idClient` désigne toujours le même appel (classe, date, heure) |
| `APPEL_CLOS` | L'enseignant corrige son appel **le jour même** ; ensuite, seule la vie scolaire le modifie |
| `MATIERE_HORS_CLASSE` | La matière indiquée doit être enseignée dans la classe |

Toute modification est journalisée (auteur, date). Un élève qui a quitté la classe garde ses absences passées.

## SMS aux familles (outbox)

- Une **absence** (pas un retard) met un SMS en file **dans la même transaction** que l'appel, à destination du
  **contact prioritaire** de l'élève : « Lycée X : Awa OUEDRAOGO (6e A) était absente le 28/09 de 08h00 à 10h00. Merci
  de justifier cette absence auprès de l'établissement. »
- Si l'enseignant corrige (élève finalement présent, ou absence changée en retard), le SMS encore en file est **annulé**.
- Le **worker** passe toutes les 10 secondes : il réserve un lot (verrou avec bail, `SKIP LOCKED` : plusieurs instances
  de l'API ne se gênent pas), envoie, enregistre le résultat. En cas d'échec : nouvelle tentative après 2, 4, 8, 16,
  32 minutes, puis échec définitif. Après 5 échecs consécutifs, le **coupe-circuit** suspend les envois 5 minutes.
- Passerelle SMS : `PasserelleSmsJournal` (provisoire) écrit les messages dans le journal. Le fournisseur réel (opérateur
  local ou agrégateur) se branche en implémentant l'interface `PasserelleSms`.
- Messages en français pour l'instant ; la langue du responsable (`langueSms`) est déjà enregistrée pour les
  traductions en mooré, dioula et fulfulde.
- Paramètres : `app.notifications.*` dans `application.yml` ; `NOTIFICATIONS_ENVOI_AUTOMATIQUE=false` suspend l'envoi.

## Points d'accès

| Ressource | Méthodes | Rôles |
|---|---|---|
| Appels | `POST /api/v1/appels/lot`, `PUT /api/v1/appels/{id}` `{"marques":[…]}` | ENSEIGNANT (ses classes), SURVEILLANT, CENSEUR, ADMIN_ECOLE |
| | `GET /api/v1/appels/{id}`, `GET /api/v1/classes/{id}/appels?date=2026-09-28` | personnel*, enseignant de la classe |
| Synthèse | `GET /api/v1/classes/{id}/absences/synthese?du=&au=` : absences, justifiées, retards, heures | personnel*, enseignant de la classe |
| Absences du jour (v0.18) | `GET /api/v1/absences/jour?date=2026-10-01` (aujourd'hui par défaut) : un élément par élève absent ou en retard dans tout l'établissement, avec sa classe, ses créneaux et l'état de justification ; trié par classe puis par nom | personnel* |
| Absences d'un élève | `GET /api/v1/inscriptions/{id}/absences?du=&au=` | personnel* |
| Par discipline (v0.18.1) | `GET /api/v1/classes/{id}/absences/matieres?du=&au=` : par matière, cours manqués (élève × séance), élèves concernés, heures, dont non justifiées, retards ; les plus manquées d'abord ; une ligne sans matière pour les appels généraux | personnel*, enseignant de la classe |
| Justificatifs | `GET/POST /api/v1/inscriptions/{id}/justificatifs` `{"du":"…","au":"…","type":"MALADIE","motif":"…"}`, `DELETE /api/v1/justificatifs/{id}` | SURVEILLANT, CENSEUR, ADMIN_ECOLE, SECRETARIAT |
| Espace parent | `GET /api/v1/espace-parent/enfants/{eleveId}/absences` | PARENT (ses enfants uniquement) |
| Suivi des SMS | `GET /api/v1/notifications?statut=ECHEC&limite=100` | ADMIN_ECOLE, SURVEILLANT |

\* ADMIN_ECOLE, CENSEUR, SURVEILLANT, SECRETARIAT, INTENDANT.

**Discipline (v0.18.1).** Chaque absence et chaque créneau de la liste du jour indiquent la matière du cours manqué
(`matiereId`, `matiereCode`, `matiereLibelle`), reprise de l'appel. Elles sont nulles pour un appel général, fait
sans matière (surveillant à l'entrée, par exemple).

Une absence est **justifiée** si un justificatif de l'élève couvre sa date (calcul à la lecture : un justificatif saisi
après coup s'applique aux absences déjà enregistrées).
