# Notifications aux parents dans l'application (v0.50)

Dès que l'enseignant envoie son appel, les familles sont prévenues des **absences** et des **retards** :
- **dans l'application**, par notification « push » sur le téléphone, pour les parents qui l'ont activée. Gratuit,
  sans opérateur SMS. Norme Web Push du W3C : Android (Chrome, Firefox, Edge), ordinateurs, iPhone si
  l'application est ajoutée à l'écran d'accueil (iOS 16.4 et suivants) ;
- **par SMS** (contact prioritaire de l'élève) pour les autres. C'est le cas des parents sans smartphone, et de ceux
  que le secrétariat a mis « toujours par SMS ».

Tout s'écrit dans la transaction de l'appel (outbox) : rien n'est perdu si le réseau ou le service push tombe.
Chaque notification arrive aussi dans le **centre de notifications** de l'espace parent.

Module `notifications` (`NotificationsFamilles`, `AbonnementsPush`, `EnvoiPush`, `WebPush`). Migration
`V41__notifications_parents.sql` :
- colonnes de `responsable` ;
- tables `reglage_notifications`, `abonnement_push` et `notification_parent`, isolées par établissement ;
- fonction `notifications_parents_dues`.

## Qui reçoit quoi

| Responsable | Absence | Retard |
|---|---|---|
| Compte parent, canal `AUTO`, notifications activées sur au moins un appareil | Push + centre | Push + centre, si le parent veut les retards |
| Compte parent, canal `AUTO`, sans appareil | SMS (s'il est contact prioritaire) + centre | Centre ; SMS si l'établissement l'a choisi |
| Compte parent, canal `SMS` (choisi par l'établissement) | SMS (s'il est contact prioritaire) + centre | Centre ; SMS si l'établissement l'a choisi |
| Sans compte parent, contact prioritaire | SMS | SMS si l'établissement l'a choisi |

- **SMS de secours.** Si la notification push n'atteint aucun appareil, le SMS de secours part au contact
  prioritaire. C'est le cas d'un abonnement expiré ou révoqué (404, 410), ou d'un service push indisponible après
  4 tentatives (1, 2, 4 minutes).
- **Abonnements défaillants.** Un appareil expiré est retiré. Un appareil en échec 10 fois de suite est désactivé.
- **Délai de correction.** C'est le temps laissé à l'enseignant pour corriger une erreur d'appel (0 à 60 minutes,
  0 par défaut). L'envoi attend ce délai, et une correction faite entre-temps annule tout. Après le délai, une
  correction marque la notification « corrigée » dans le centre du parent.
- **Message discret.** Le parent peut demander un message discret sur l'écran verrouillé : « Nouvelle information
  de l'établissement sur votre enfant ». Le détail s'affiche à l'ouverture.

## Points d'accès

| Méthode | Chemin | Rôles | Effet |
|---|---|---|---|
| GET | `/api/v1/push/configuration` | tout utilisateur connecté | `{ disponible, clePublique }` : clé VAPID publique, ou `disponible: false` si les clés ne sont pas configurées (tout part alors par SMS) |
| GET | `/api/v1/push/abonnements` | PARENT | Appareils du parent où les notifications sont actives |
| POST | `/api/v1/push/abonnements` | PARENT | `{ endpoint, p256dh, auth, appareil }`, tiré de `PushSubscription.toJSON()`. Adresse `https` et clés vérifiées ; `PUSH_INDISPONIBLE` (409) sans clés VAPID |
| POST | `/api/v1/push/abonnements/retrait` | PARENT | `{ endpoint }` |
| POST | `/api/v1/push/essai` | PARENT | Notification d'essai sur ses appareils (`AUCUN_APPAREIL` sinon) |
| GET | `/api/v1/espace-parent/notifications?limite=50` | PARENT | `{ nonLues, notifications: [{ type, titre, message, lien, recueLe, lueLe, corrigeeLe }] }` |
| POST | `/api/v1/espace-parent/notifications/lues` | PARENT | `{ ids }`, ou corps vide : toutes |
| GET / PUT | `/api/v1/espace-parent/preferences-notifications` | PARENT | `{ canal (lecture seule), notifierRetards, discret }` |
| PUT | `/api/v1/responsables/{id}/canal-notification` | ADMIN_ECOLE, INTENDANT, SECRETARIAT, SURVEILLANT | `{ canal: "AUTO" \| "SMS" }` |
| GET | `/api/v1/notifications/reglages` | ADMIN_ECOLE, CENSEUR, SURVEILLANT, SECRETARIAT | `{ delaiCorrectionMinutes, smsRetards, pushDisponible }` |
| PUT | `/api/v1/notifications/reglages` | ADMIN_ECOLE, SURVEILLANT | `{ delaiCorrectionMinutes (0 à 60), smsRetards }` |

## Sécurité

- **Chiffrement.** Les messages sont chiffrés pour chaque appareil : RFC 8291, `aes128gcm`, ECDH P-256 et
  AES-128-GCM, avec la cryptographie du JDK. Le service push (Google, Mozilla, Apple) ne peut pas les lire. Le
  serveur s'identifie par VAPID (RFC 8292, jeton ES256 valable 12 heures).
- **Clés VAPID.** Elles sont fournies par l'environnement (`PUSH_CLE_PUBLIQUE`, `PUSH_CLE_PRIVEE`), jamais par le
  dépôt, et se génèrent avec `node scripts/push/generer-cles-vapid.mjs`. Le profil `test` a ses propres clés,
  sans valeur d'exploitation.
- **Cloisonnement.** Un parent ne voit que ses notifications, et l'établissement vient du jeton. Le worker
  parcourt les écoles avec la fonction `notifications_parents_dues` (SECURITY DEFINER), puis traite chaque
  notification dans le cloisonnement de son établissement.
- **HTTPS.** Il est obligatoire : les navigateurs n'activent pas le push sur une page en HTTP, sauf `localhost`.

## Application web

- **Parent** : bouton « Notifications » sur « Mes enfants », avec le nombre de notifications non lues. Il ouvre
  `/parent/notifications`, qui propose :
  - **Sur ce téléphone** :
    - « Activer les notifications », par le service worker Angular (`SwPush`) ;
    - envoi d'un essai ;
    - désactivation ;
    - des explications si le navigateur ne les reçoit pas (iPhone : ajouter d'abord à l'écran d'accueil) ou si
      elles ont été refusées.
  - **Préférences** : retards, message discret.
  - **Reçues** : la liste, les notifications corrigées barrées, « Tout marquer comme lu ».
  - Toucher une notification ouvre la fiche de l'enfant.
- **Vie scolaire** : onglet « Familles » (`/vie-scolaire/notifications`) avec le délai de correction, les retards
  par SMS et les derniers SMS (statut, annulés par correction).
- **Dossier d'un élève** : pour chaque parent, choix « Absences : application, sinon SMS » ou « toujours par SMS ».
