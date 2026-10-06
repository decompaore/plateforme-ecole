# Domaine Mobile Money (v0.9)

Module `mobilemoney` : paiement des frais de scolarité par Orange Money, Moov Money ou Telecel Money (v0.19.1) depuis l'espace parent,
via un **agrégateur** (CinetPay, Ligdicash, PayDunya…). Chaque école a son propre compte marchand. La
notification de l'agrégateur est signée, et le statut est toujours revérifié auprès de l'agrégateur. Les transactions
sans réponse expirent. Un rapprochement avec le relevé de l'agrégateur a lieu chaque nuit. Migration
`V10__mobile_money.sql`. S'y ajoutent les **relances SMS automatiques** du lundi (module Scolarité).

Aucun agrégateur réel n'est encore branché : un agrégateur **SIMULATEUR** permet de tout essayer en
développement. Brancher CinetPay ou un autre revient à écrire une classe qui implémente l'interface `Agregateur`
(initier, consulter, relevé, signature) : le reste ne change pas.

## 1. Configurer le compte marchand de l'école

`PUT /api/v1/parametres/mobile-money` (ADMIN_ECOLE) :

```json
{"agregateur":"SIMULATEUR","identifiantMarchand":"ECOLE-001","cleApi":"…","secretWebhook":"…","actif":true}
```

- Les clés sont **chiffrées en base** (AES-256-GCM, liées à l'école). Elles ne sont jamais renvoyées :
  `GET /api/v1/parametres/mobile-money` (INTENDANT, ADMIN_ECOLE) indique seulement `cleApiDefinie`,
  `secretWebhookDefini` et donne l'**URL de notification** à déclarer chez l'agrégateur :
  `/api/v1/webhooks/mobile-money/{idEcole}`.
- Une clé laissée vide lors d'une modification conserve la valeur enregistrée.
- Le serveur doit avoir la variable `MOBILE_MONEY_CLE_CHIFFREMENT` (voir CONFIGURATION.md).

## 2. Le parent paie

| Étape | Appel |
|---|---|
| Demander | `POST /api/v1/espace-parent/inscriptions/{inscriptionId}/mobile-money` `{"montant":25000,"operateur":"ORANGE_MONEY","telephone":"70 11 22 33","cleIdempotence":"<uuid>"}` → **202** ; `operateur` : `ORANGE_MONEY`, `MOOV_MONEY` ou `TELECEL_MONEY` |
| Suivre | `GET /api/v1/espace-parent/mobile-money/{id}` (l'écran interroge toutes les 5 secondes) |

Statuts : `INITIEE` → `EN_ATTENTE` (« Confirmez sur votre téléphone ») → `CONFIRMEE` (paiement et reçu enregistrés, SMS
du reçu) ou `ECHOUEE` / `EXPIREE` (15 minutes sans confirmation). `A_VERIFIER` : de l'argent a été reçu mais n'a
pas été enregistré automatiquement (montant différent, plus rien à payer, confirmation tardive) ; l'intendance
régularise (`REGULARISEE`).

Refus : enfant d'une autre famille (`403`), plus que le reste dû (`409 MONTANT_SUPERIEUR_AU_RESTE`), un paiement déjà
en attente (`409 TRANSACTION_EN_COURS`), Mobile Money non activé (`409 MOBILE_MONEY_INACTIF`), agrégateur injoignable
(`503 MOBILE_MONEY_INDISPONIBLE` : « réessayez plus tard ou payez à l'intendance »). Montant de 100 à 2 000 000 FCFA.
La même `cleIdempotence` (double clic) renvoie la même transaction.

## 3. Notification de l'agrégateur

`POST /api/v1/webhooks/mobile-money/{idEcole}`, sans connexion.

1. La signature est vérifiée avec le secret de **cette** école (sinon `401`, toujours le même message).
2. Le statut est **redemandé à l'agrégateur** : le contenu de la notification ne suffit jamais.
3. Si le montant reçu est le montant demandé, le paiement est enregistré, avec son reçu numéroté, dans le module
   Scolarité (payeur FAMILLE, moyen ORANGE_MONEY, MOOV_MONEY ou TELECEL_MONEY).

Une notification reçue deux fois, ou en même temps que la tâche d'expiration, ne crée jamais deux paiements.

## 4. Suivi par l'intendance

| Appel | Effet |
|---|---|
| `GET /api/v1/mobile-money/transactions?du=&au=` | Transactions de la période |
| `GET /api/v1/mobile-money/transactions/a-verifier` | Ce qui demande une action |
| `POST /api/v1/mobile-money/transactions/{id}/verification` | Redemande le statut à l'agrégateur (notification perdue, parent inquiet) |
| `POST /api/v1/mobile-money/transactions/{id}/regularisation` `{"motif":"4 000 FCFA encaissés au guichet, reçu 2026-000042"}` | Clôt une transaction à vérifier |

## 5. Rapprochement quotidien

Chaque nuit à 2 h 30, pour chaque école, le relevé de la veille de l'agrégateur est comparé aux transactions.
Relancer à la main : `POST /api/v1/mobile-money/rapprochements?date=2026-10-12`.

| Écart | Signification |
|---|---|
| `NON_ENREGISTRE` | Argent reçu par l'agrégateur, aucun paiement enregistré sur la plateforme |
| `ABSENT_DU_RELEVE` | Paiement confirmé sur la plateforme, absent du relevé |
| `MONTANT_DIFFERENT` | Montants différents |
| `DOUBLON` | Même transaction deux fois dans le relevé (parent débité deux fois ?) |

`GET /api/v1/mobile-money/rapprochements?du=&au=`, `GET …/rapprochements/{id}/ecarts`,
`POST /api/v1/mobile-money/ecarts/{id}/traitement` `{"motif":"…"}`. Une journée dont un écart est traité ne se refait
plus. Si le relevé est indisponible, le rapprochement déjà fait est conservé.

## 6. Relances automatiques

Chaque lundi à 7 h 30 : les familles en retard reçoivent un SMS, avec les mêmes règles que la relance manuelle
(part famille échue seulement, délai minimal entre deux relances). Chaque école peut les couper :
`PUT /api/v1/parametres/scolarite` avec `"relancesAutomatiques":false`.

## 7. Essayer en développement (profil `dev`)

1. Configurer l'école avec `"agregateur":"SIMULATEUR"`.
2. Le parent demande un paiement (étape 2) : statut `EN_ATTENTE`.
3. Jouer le rôle du parent sur son téléphone :
   `POST /api/v1/simulateur/mobile-money/{transactionId}` (`?accepter=false` pour refuser, `&montant=` pour un
   montant différent). La transaction passe à `CONFIRMEE` et le reçu apparaît dans la situation de l'élève.

Ce point d'accès n'existe qu'en profil `dev`. En production, l'agrégateur SIMULATEUR est refusé.

## Telecel Money (v0.19.1)

Troisième opérateur du Burkina Faso, au même titre qu'Orange Money et Moov Money : opérateur `TELECEL_MONEY` pour
les transactions, moyen de paiement `TELECEL_MONEY` (« Telecel Money ») pour les encaissements et les reçus.
Migration `V15__telecel_money.sql` (contraintes et longueur des colonnes `paiement.moyen` et
`transaction_mobile_money.operateur`). L'agrégateur réel devra accepter les trois opérateurs.
