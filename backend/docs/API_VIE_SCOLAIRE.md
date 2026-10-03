# Vie scolaire (v0.11)

Module `viescolaire` : retards, avertissements, blâmes, exclusions temporaires, convocations des parents, avec SMS
automatique au contact prioritaire et historique dans le dossier de l'élève. Migration `V12__vie_scolaire.sql`.
Le conseil de discipline et le suivi éducatif complet viendront en phase 2.

## Incidents

`POST /api/v1/inscriptions/{inscriptionId}/incidents`

```json
{"type":"RETARD","minutesRetard":20,"motif":"Arrivée à 7 h 20"}
{"type":"AVERTISSEMENT","motif":"Bavardages répétés"}
{"type":"BLAME","motif":"Insolence envers un professeur"}
{"type":"EXCLUSION_TEMPORAIRE","joursExclusion":3,"debutExclusion":"2026-11-03","motif":"Bagarre"}
```

| Type | Qui peut le saisir | SMS à la famille par défaut |
|---|---|---|
| `RETARD` | SURVEILLANT ; ENSEIGNANT (dans ses classes) ; CENSEUR, ADMIN_ECOLE | non (`"prevenirFamille":true` pour l'envoyer) |
| `AVERTISSEMENT` | idem | oui |
| `BLAME`, `EXCLUSION_TEMPORAIRE` | CENSEUR, ADMIN_ECOLE | oui |

- `date` : aujourd'hui par défaut, jamais dans le futur ni avant le début de l'année. L'élève doit être inscrit et
  l'année en cours.
- Exclusion de 1 à 30 jours (calendaires), à partir du lendemain par défaut, et finie avant la fin de l'année.
  Pendant l'exclusion, l'appel marque l'élève absent : l'absence est justifiable par l'exclusion.
- `"prevenirFamille":false` : pas de SMS, quel que soit le type.
- Exemples de SMS : « Lycée X : avertissement pour Awa OUEDRAOGO (6e A) le 12/10/2026 : Bavardages répétés. » ;
  « … exclusion temporaire de Awa OUEDRAOGO (6e A) pour 3 jour(s), du 13/10/2026 au 15/10/2026 : Bagarre. »
- **Annuler** (jamais supprimer) : `POST /api/v1/incidents/{id}/annulation` `{"motif":"Saisi par erreur"}`, par la
  direction ou par l'auteur dans les 24 heures. Un SMS pas encore parti est retiré. Il faut d'abord annuler une
  convocation prévue qui porte sur l'incident.

## Convocations

| Appel | Rôle |
|---|---|
| `POST /api/v1/inscriptions/{inscriptionId}/convocations` `{"rendezVous":"2026-10-15T10:00","motif":"Comportement en classe","incidentId":"…"}` | SURVEILLANT, CENSEUR, ADMIN_ECOLE |
| `POST /api/v1/convocations/{id}/cloture` `{"statut":"HONOREE","compteRendu":"Père venu, engagement pris"}` | idem |
| `GET /api/v1/convocations?du=&au=` : agenda de l'établissement | + SECRETARIAT |

- Le rendez-vous est à venir, dans les trois prochains mois. SMS : « Lycée X : vous êtes convoqué(e) le 15/10/2026 à
  10h00 au sujet de Awa OUEDRAOGO (6e A) : Comportement en classe. Merci de vous présenter à l'établissement. »
- Clôture : `HONOREE` ou `NON_HONOREE` une fois le rendez-vous passé, `ANNULEE` à tout moment. Une annulation retire
  le SMS s'il n'est pas encore parti ; sinon la famille reçoit un SMS d'annulation.

## Historique et synthèses

| Appel | Contenu |
|---|---|
| `GET /api/v1/inscriptions/{id}/vie-scolaire` | Année d'inscription : synthèse (retards et minutes, avertissements, blâmes, exclusions et jours, convocations), incidents (annulés compris), convocations avec compte rendu |
| `GET /api/v1/eleves/{eleveId}/vie-scolaire` | Toutes les années de l'élève dans l'établissement |
| `GET /api/v1/classes/{classeId}/vie-scolaire?du=&au=` | Une ligne de synthèse par élève |
| `GET /api/v1/espace-parent/enfants/{eleveId}/vie-scolaire` | Parent : incidents non annulés et convocations, sans les comptes rendus internes |

Consultation : SURVEILLANT, CENSEUR, ADMIN_ECOLE, SECRETARIAT. L'historique d'une année close ne change plus.
Les retards relevés pendant l'appel restent dans le module Absences ; les retards saisis ici sont ceux constatés
à l'entrée de l'établissement.
