# Domaine Scolarité (v0.8)

Module `scolarite` : frais et tranches, bourses (prise en charge par un organisme), exonérations, échéancier
(part famille / part organisme), encaissements au guichet, reçus numérotés, état des classes, liste des retards
(Excel), relances SMS, espace parent, vérification publique des reçus. Migration `V9__scolarite.sql`.
Montants en francs CFA, sans décimales. Paiement en ligne Mobile Money : voir API_MOBILE_MONEY.md (v0.9).

Rôles : **INTENDANT** et **ADMIN_ECOLE** gèrent ; **SECRETARIAT** consulte.

## 1. Paramétrer

| Étape | Appel |
|---|---|
| Paramètres | `GET /api/v1/parametres/scolarite` ; `PUT …` (ADMIN_ECOLE) `{"tauxBoursier":100,"tauxSemiBoursier":50,"delaiRelanceJours":7}` |
| Organismes financeurs | `GET /api/v1/organismes` ; `POST …` `{"nom":"État burkinabè","type":"ETAT"}` ; `PUT /api/v1/organismes/{id}` (`actif`) |
| Frais de l'année | `GET /api/v1/annees/{anneeId}/frais` ; `POST …` ; `PUT /api/v1/frais/{id}` ; `DELETE /api/v1/frais/{id}` |

Un frais :

```json
{"libelle":"Scolarité","montant":75000,"obligatoire":true,"couvertParBourse":true,
 "portee":"FILIERES","filieres":["<id filière>"],
 "tranches":[{"dateLimite":"2026-10-15","montant":25000},
             {"dateLimite":"2027-01-15","montant":25000},
             {"dateLimite":"2027-04-15","montant":25000}]}
```

- **Portée, au choix de chaque établissement** : `TOUTES` (toute l'école), `FILIERES` (`filieres`), `NIVEAUX`
  (`niveaux`, ex. `["6e","5e"]`) ou `CLASSES` (`classes`). Un même libellé ne sert qu'une fois par année.
- **Tranches** : dates croissantes, somme égale au montant (sinon `409 TRANCHES_INCOHERENTES`). Sans tranche, un seul
  paiement à la rentrée.
- `obligatoire:false` : frais facultatif (cantine, transport…), dû seulement après souscription :
  `PUT /api/v1/inscriptions/{id}/frais/{fraisId}` (et `DELETE` pour résilier).
- `couvertParBourse:false` : la bourse ne s'applique pas à ce frais (ex. inscription, tenue) : la famille le paie.
- Une année close (`CLOTUREE`, `ARCHIVEE`) ne change plus de frais.

## 2. Bourses et exonérations

Le **statut de bourse** est porté par l'inscription de l'année (v0.3) : `PATCH /api/v1/inscriptions/{id}/bourse`.

| Étape | Appel |
|---|---|
| Prise en charge | `PUT /api/v1/inscriptions/{id}/prise-en-charge` `{"organismeId":"…","taux":50,"referenceDecision":"Arrêté 2026-045","dateDecision":"2026-09-01"}` ; `GET` ; `DELETE` |
| Exonération | `PUT /api/v1/inscriptions/{id}/exonerations/{fraisId}` `{"montant":5000,"motif":"Enfant du personnel"}` ; `DELETE` |

- Taux absent : taux par défaut du statut (boursier 100 %, semi-boursier 50 %, paramétrables). Un boursier sans
  prise en charge enregistrée a quand même sa part organisme au taux par défaut.
- Pas de prise en charge pour un non-boursier (`409 ELEVE_NON_BOURSIER`).
- L'exonération (réduction accordée par l'école) réduit d'abord les **dernières** tranches du frais.

## 3. Échéancier et situation

`GET /api/v1/inscriptions/{id}/scolarite` : pour chaque tranche, montant, exonération, **part organisme**,
**part famille**, payé et reste pour chacun, retard. Totaux : dû, payé, reste, **retard** (échu non payé),
avance, prochaine échéance, paiements.

- Part organisme = taux × (tranche − exonération), arrondie au franc sur le frais ; la famille paie le reste.
- **Les paiements soldent les tranches les plus anciennes d'abord** (famille et organisme séparément).
- L'échéancier est recalculé à chaque consultation : un changement de statut, de taux ou de frais s'applique
  aussitôt ; les paiements, eux, ne changent jamais.

## 4. Encaisser

`POST /api/v1/inscriptions/{id}/paiements`

```json
{"montant":15000,"moyen":"ESPECES","payeur":"FAMILLE","deposant":"M. Ouédraogo",
 "cleIdempotence":"<uuid généré par l'écran>"}
```

- `moyen` : `ESPECES`, `ORANGE_MONEY`, `MOOV_MONEY`, `TELECEL_MONEY` (reçu sur le numéro marchand de l'école), `VIREMENT`,
  `CHEQUE` ; la `referenceExterne` est obligatoire sauf en espèces.
- `payeur` : `FAMILLE` ou `ORGANISME` (l'organisme de la prise en charge). `datePaiement` : aujourd'hui par défaut,
  jamais dans le futur.
- Refus : plus que le reste dû du payeur (`409 MONTANT_SUPERIEUR_AU_RESTE`), rien à payer (`RIEN_A_PAYER`),
  versement d'organisme sans prise en charge (`SANS_PRISE_EN_CHARGE`).
- **Idempotent** : la même `cleIdempotence` renvoie le paiement déjà enregistré (double clic, réseau coupé).
- Chaque paiement reçoit un **reçu numéroté** (`2026-000123`) et un code de vérification. Paiement de la famille :
  SMS au contact prioritaire (« reçu n° …, 15 000 FCFA reçus pour … Reste à payer : 30 000 FCFA. »).
- **Reçu PDF** (A5) : `GET /api/v1/paiements/{id}/recu` — montant en chiffres et en lettres, reste à payer.
- **Annuler** : `POST /api/v1/paiements/{id}/annulation` `{"motif":"Chèque impayé"}`. Le paiement ne compte plus,
  le reçu reste, marqué « REÇU ANNULÉ ». Un paiement n'est **jamais modifié ni supprimé** (garanti par la base).
- **Journal de caisse** : `GET /api/v1/paiements?du=2026-10-01&au=2026-10-31` — paiements non annulés, totaux par
  moyen de paiement.

## 5. Suivi des classes et relances

| Appel | Réponse |
|---|---|
| `GET /api/v1/classes/{classeId}/scolarite` | Une ligne par élève, totaux, taux de recouvrement famille et organisme |
| `GET /api/v1/classes/{classeId}/scolarite/retards` | Fichier Excel des élèves en retard (plus gros retard d'abord, téléphone du contact, dernière relance) |
| `POST /api/v1/scolarite/relances?classeId=` | SMS aux familles en retard d'une classe (ou de toute l'année active sans `classeId`) : `{"envoyees":12,"sansContact":2,"dejaRelancees":5}` |

Les relances ne portent **que sur la part famille échue** : jamais sur la part d'un organisme, jamais pour un élève
sorti, pas plus d'une fois par élève pendant le délai paramétré (7 jours par défaut).

## 6. Espace parent et vérification publique

| Appel | Réponse |
|---|---|
| `GET /api/v1/espace-parent/enfants/{eleveId}/scolarite` | Situation de chaque année de l'enfant (PARENT) |
| `GET /api/v1/espace-parent/paiements/{id}/recu` | Reçu PDF, seulement pour ses enfants (`404` sinon) |
| `GET /api/v1/verification/recus/{code}` | **Sans connexion** : établissement, n° de reçu, élève, montant, payeur, date, annulé ou non |

## Limites connues (MVP)

- Paiement Mobile Money en ligne, rapprochement et relances automatiques : voir [API_MOBILE_MONEY.md](API_MOBILE_MONEY.md) (v0.9).
- La reprise des frais d'une année sur l'autre viendra avec le passage d'année.
