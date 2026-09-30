# Application web (v0.13)

Application Angular 22 installable sur smartphone (PWA). Cette première version couvre :

- la **connexion** : téléphone et mot de passe, choix de l'établissement, changement du mot de passe provisoire,
  changement d'établissement, déconnexion ;
- l'**appel sur smartphone, même sans réseau** (enseignants) ;
- un **accueil par rôle** : les écrans des autres rôles sont annoncés « bientôt disponibles ». En attendant, ils
  utilisent l'API (Swagger).

## Démarrer en local

Prérequis : **Node.js 24** (ou 22.22.3 et plus) et npm. Vérifier avec `node -v`.

```bash
docker compose up -d                                                   # base PostgreSQL
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev       # API sur :8080 (ou depuis Eclipse)
cd frontend && npm ci && npm start                                     # application sur http://localhost:4200
```

`npm start` relaie `/api` vers `http://localhost:8080` (fichier `proxy.conf.json`). L'application et l'API
paraissent ainsi venir de la même adresse, et le cookie de session fonctionne comme en production.

Compte de développement : `70000000` / `ChangezMoi-Dev-2026`. C'est le super administrateur. Pour essayer l'appel,
il faut un compte enseignant qui a des affectations : on le crée par l'API (établissement, année active, classes,
matières, élèves, enseignant, affectation), puis on se connecte avec le mot de passe provisoire reçu.

| Commande (dans `frontend/`) | Rôle |
|---|---|
| `npm start` | Serveur de développement, rechargement automatique (service worker désactivé) |
| `npm test` | Tests unitaires (Vitest), en continu. `npx ng test --watch=false` pour une seule exécution |
| `npm run build` | Construction de production dans `dist/frontend/browser/` (avec service worker) |

## Tester sur un téléphone

Le service worker (fonctionnement sans réseau à l'ouverture) n'est actif que sur la **construction de production**,
servie en **HTTPS** ou sur `localhost`. Sur le réseau local en HTTP, l'application fonctionne, y compris l'appel
sans réseau si l'onglet reste ouvert, mais elle ne se réinstalle pas hors connexion. Pour un vrai test :
construire (`npm run build`), servir `dist/frontend/browser/` derrière un HTTPS (serveur de recette), puis
« Ajouter à l'écran d'accueil ».

Dans Chrome sur ordinateur, on simule un téléphone et une coupure avec les outils de développement : mode appareil,
puis onglet *Network* → *Offline*.

## Sécurité de la session

| Élément | Où il se trouve |
|---|---|
| Jeton d'accès (15 min) | **Mémoire** de l'application uniquement : jamais écrit sur le téléphone |
| Jeton de rafraîchissement | Cookie **HttpOnly**, SameSite=Strict, chemin `/api/v1/auth` : invisible pour le code |
| Profil affiché (nom, établissement, rôles) | IndexedDB, pour ouvrir l'application sans réseau |
| Listes de classes de l'enseignant | IndexedDB, **effacées à la déconnexion** |
| Appels non envoyés | IndexedDB, **conservés** à la déconnexion (envoyés à la connexion suivante) |

- L'établissement ne vient jamais du téléphone : le serveur le lit dans le jeton.
- Sur une réponse 401, l'application renouvelle la session **une seule fois** pour toutes les requêtes en cours.
  Le serveur fait tourner le cookie et traite la réutilisation d'un ancien cookie comme un vol.
- Le renouvellement est aussi verrouillé entre onglets : l'application installée et un onglet ouvert ne l'envoient
  jamais en même temps.
- **Déconnexion sans réseau** : le cookie reste valide tant que le serveur ne l'a pas révoqué. L'application note
  la déconnexion et, au lancement suivant, la termine auprès du serveur **avant** toute reprise de session. Un
  téléphone partagé ne rouvre donc jamais la session de l'utilisateur précédent.
- Téléphone partagé : quand un autre utilisateur se connecte, les listes de classes du précédent sont effacées.
- Le service worker ne met **jamais** en cache les réponses de l'API : seuls les fichiers de l'application le sont.
- Au lancement, l'application n'attend jamais le serveur plus de 8 secondes. Si le stockage du téléphone est
  indisponible (navigation privée), elle fonctionne quand même, sans conserver de données hors connexion.

## L'appel sans réseau

1. **Préparation.** À chaque ouverture avec du réseau, les affectations et les listes de classes de l'enseignant
   sont téléchargées si elles ont plus de 12 heures. Le bouton « Mettre à jour les listes maintenant » force le
   téléchargement.
2. **Saisie.** L'enseignant choisit la classe et la matière. La date et l'heure sont modifiables et une durée se
   règle d'un appui (1, 2 ou 3 h). Tous les élèves sont présents au départ : on touche **A** (absent) ou **R**
   (retard, avec la durée). Un récapitulatif s'affiche avant validation. L'application prévient si le même créneau
   a déjà été saisi sur ce téléphone.
3. **File d'envoi.** L'appel validé est enregistré sur le téléphone avec un identifiant généré sur place
   (`idClient`). Il est envoyé à `POST /api/v1/appels/lot` :
   - tout de suite si le réseau le permet ;
   - sinon au retour du réseau, puis toutes les minutes tant qu'il reste des appels ;
   - par lots de 50 au plus, dans l'ordre de saisie.
4. **Accusés.** Le traitement dépend du statut renvoyé :

   | Statut | Effet sur le téléphone |
   |---|---|
   | ENREGISTRE, DEJA_RECU, MODIFIE | « envoyé ✓ », affiché une semaine puis effacé |
   | REFUSE | « refusé » avec le motif du serveur (créneau déjà fait, année close…) ; jamais renvoyé ; l'enseignant peut le retirer |
   | Pas de réponse (réseau, serveur indisponible) | Reste « en attente », nouvel essai automatique |

   Renvoyer le même appel après une coupure ne crée jamais de doublon : le serveur reconnaît l'`idClient`.

La session dure 30 jours sans nouvelle connexion, ce qui couvre largement les périodes sans réseau.
- **Expiration pendant la saisie** : l'appel en cours n'est pas perdu. L'enseignant le termine et le valide ; un
  bandeau l'invite à se reconnecter, puis la file part.
- **Serveur indisponible au lancement** : l'application travaille avec les données du téléphone et retente la
  connexion toutes les 30 secondes.
- **Changement d'établissement** : les appels de l'établissement actuel sont envoyés avant le changement.
- **Lot rejeté en bloc** (réponse 400) : les appels sont renvoyés un par un, pour ne refuser que celui qui pose
  problème.

## Organisation du code

```
frontend/src/app/
├── core/          session (connexion, renouvellement), intercepteur du jeton, gardes, modèles, outils
├── hors-ligne/    stockage IndexedDB, listes de classes, file d'envoi des appels
├── pages/         connexion, établissement, mot de passe, accueil, appel (choix, saisie), mes appels
└── testing/       aides pour les tests
```

- Composants autonomes (*standalone*), état en **signals**, sans zone.js.
- L'accueil et les écrans de l'appel sont chargés avec l'application, pour s'ouvrir sans réseau. Les autres écrans
  sont chargés à la demande.
- Styles communs dans `src/styles.scss` (boutons de 48 px, contrastes lisibles au soleil, mode sombre).

## Tests

28 tests (Vitest) :

| Fichier | Ce qui est vérifié |
|---|---|
| `session.service.spec.ts` | Connexion, choix de l'établissement avec le jeton de sélection, démarrage sans réseau ou avec un serveur en erreur, session expirée au lancement et en cours d'utilisation, renouvellement unique puis rejeu, déconnexion (listes effacées, appels gardés), déconnexion sans réseau terminée au lancement suivant, téléphone partagé |
| `envois.service.spec.ts` | Mise en file, accusés, coupure puis renvoi avec le même identifiant, refus, lot invalide renvoyé un par un, lots de 50 dans l'ordre, cloisonnement par utilisateur et établissement, un seul envoi à la fois |
| `feuille-appel.spec.ts` | Absents et retards, bornes des minutes, récapitulatif, dates locales, identifiants, stockage |
| `appel-saisie.page.spec.ts` | Appel complet hors connexion à partir des listes du téléphone, jusqu'à la file d'envoi |

La CI (`.github/workflows/ci.yml`, job *Frontend*) exécute les tests et la construction de production à chaque pull
request.

Le parcours complet a aussi été vérifié dans Chromium, à la taille d'un téléphone, avec une API simulée :
- connexion ;
- appel hors connexion, sans aucun envoi tant que le réseau manque ;
- envoi automatique au retour du réseau ;
- session expirée pendant la saisie, puis reconnexion et envoi.

## Limite connue

Si la réponse d'un renouvellement se perd (réseau très faible), le téléphone garde l'ancien cookie, que le serveur
a déjà remplacé. Au renouvellement suivant, le serveur voit un cookie révoqué réutilisé : il ferme la session par
précaution et l'enseignant doit se reconnecter. Aucun appel n'est perdu. Correction prévue côté serveur : accepter
le cookie précédent pendant quelques secondes et renvoyer le même successeur.

## Mise en production (à venir)

L'application construite est un ensemble de fichiers statiques. Elle sera servie par Caddy **sur le même domaine
que l'API** (`/api` vers les instances Spring Boot, le reste vers les fichiers). Le cookie SameSite=Strict l'impose.
Il faudra aussi le repli vers `index.html` pour les adresses de l'application. Ce sera fait avec la chaîne de
déploiement du frontend.
