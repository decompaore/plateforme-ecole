# Application web (v0.17)

Application Angular 22 installable sur smartphone (PWA). Elle couvre :

- la **connexion** : téléphone et mot de passe, choix de l'établissement, changement du mot de passe provisoire,
  changement d'établissement, déconnexion ;
- l'**appel sur smartphone, même sans réseau** (enseignants, v0.13) ;
- la **saisie des notes, même sans réseau** (enseignants, v0.15) : création des évaluations et feuilles de notes ;
- l'**espace d'administration** (v0.14) : établissements pour le super administrateur ; année scolaire, filières et
  matières, classes et programmes, élèves, personnel pour l'établissement ;
- la **mutation d'un enseignant** (v0.16) : fin d'engagement programmée par l'établissement, bandeau d'avertissement
  chez l'enseignant pour envoyer ses appels et notes avant la date ;
- des **zones de recherche** (v0.17) sur les listes longues, qui marchent aussi sans réseau côté enseignant ;
- un **accueil par rôle** : les écrans encore à venir (vie scolaire, scolarité, statistiques, parents) sont
  annoncés « bientôt disponibles ». En attendant, ces fonctions s'utilisent par l'API (Swagger).

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
il faut un compte enseignant qui a des affectations. Le script de démonstration le prépare en une commande (API
lancée) :

```bash
node scripts/demo/creer-etablissement-demo.mjs
# si le mot de passe du super administrateur a été changé :
SUPER_ADMIN_MOT_DE_PASSE=... node scripts/demo/creer-etablissement-demo.mjs
```

Il crée par l'API un lycée technique complet :
- année ouverte contenant la date du jour, trimestres ;
- filière F3, 6 matières regroupées (générales, techniques) ;
- classes 2nde F3 et 1re F3, 46 élèves avec un parent chacun ;
- un enseignant d'électrotechnique affecté aux deux classes.

À la fin, il affiche les téléphones et le mot de passe (`Demo2026`) de l'enseignant et de l'administrateur. Chaque
exécution crée un nouvel établissement : on peut le relancer sans rien nettoyer.

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
- Un compte (un téléphone) peut appartenir à plusieurs établissements, avec un rôle propre à chacun : enseignant
  vacataire dans deux lycées, parent d'enfants dans deux écoles, promoteur d'un réseau. « Changer d'établissement »
  n'apparaît que pour ces comptes. Un administrateur rattaché à un seul établissement ne le voit pas.
- **Titulaire dans X, vacataire dans Y** : l'établissement Y engage l'enseignant comme vacataire (écran Personnel).
  L'enseignant ayant déjà un compte, il reçoit une **invitation**, affichée sur son accueil avec le type de poste, les
  dates et le taux horaire. S'il accepte, « Changer d'établissement » apparaît et un bouton le fait passer dans Y.
  L'établissement X n'est jamais informé. Le serveur refuse un second poste de **titulaire** sur la même période
  (`POSTE_TITULAIRE_OCCUPE`).
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

## La saisie des notes sans réseau

1. **Préparation**, en même temps que les listes de classes. Elle télécharge :
   - les périodes de l'année ;
   - pour chaque matière de l'enseignant, les évaluations de la période en cours et leurs feuilles de notes.
2. **Évaluations**. L'enseignant choisit la classe et la matière, puis la période (celle du jour par défaut). Il voit
   les évaluations avec le nombre de notes saisies. « Nouvelle évaluation » propose un intitulé, le type, la date
   (contrôlée dans la période), le barème (20, ou 1 à 100) et le poids (2 pour une composition).
3. **Feuille de notes**. L'enseignant tape la note (virgule acceptée, deux décimales au plus), puis Entrée pour passer
   à l'élève suivant. Le bouton **Abs** marque un élève qui n'a pas composé. Une note au-dessus du barème est signalée
   et bloque l'enregistrement. Le pied de page affiche le nombre de notes, d'absents et la moyenne. Quitter l'écran
   avec des notes non enregistrées demande confirmation.
4. **Envoi**. L'enregistrement garde la saisie sur le téléphone, puis l'envoie dès qu'il y a du réseau, comme pour
   l'appel. La page « Mes envois » montre les saisies en attente ou refusées.

Garanties :
- **Pas de doublon.** Une évaluation créée sans réseau reçoit sur le téléphone un identifiant que le serveur réutilise
  (`idClient`). Un renvoi après une coupure retrouve l'évaluation déjà créée.
- **Pas d'effacement.** Seuls les élèves modifiés sur le téléphone sont envoyés. Une note saisie ailleurs (par le
  censeur, par exemple) pour un autre élève n'est jamais effacée. Un « Abs » retiré redonne la note d'origine.
- **Ordre respecté.** Les notes d'une évaluation créée sans réseau partent toujours après sa création, même si
  l'horloge du téléphone a reculé entre-temps.
- **Saisie pendant un envoi.** Les notes enregistrées pendant qu'un envoi est en cours partent au passage suivant.
- **Refus.** Une note hors barème ou une période verrouillée est affichée avec le motif du serveur. Les notes
  corrigées repartent. Une évaluation refusée (date hors période…) se corrige depuis sa feuille et se renvoie avec
  ses notes, ou s'abandonne depuis « Mes envois ».
- Plus de 200 notes : envoi en plusieurs fois (limite du serveur). Un seul envoi à la fois, même avec plusieurs
  onglets ouverts.

Limite : si le censeur et l'enseignant modifient **la même note** à des moments différents, la dernière saisie
envoyée l'emporte.

## Espace d'administration

L'administration travaille en ligne : contrairement à l'appel, rien n'est gardé sur l'appareil. Les écrans sont
utilisables sur ordinateur comme sur téléphone (les tableaux défilent horizontalement sur petit écran).

| Écran | Rôles | Ce qu'on y fait |
|---|---|---|
| Établissements (`/plateforme`) | Super administrateur | Créer un établissement et son premier administrateur, suspendre ou réactiver |
| Année scolaire (`/admin/annee`) | ADMIN_ECOLE, CENSEUR | Profils pédagogiques, nouvelle année, génération des trimestres ou semestres, ouverture de l'année |
| Filières et matières (`/admin/referentiel`) | ADMIN_ECOLE, CENSEUR | Filières avec leur profil (général, technique, professionnel), matières |
| Classes (`/admin/classes`) | ADMIN_ECOLE, CENSEUR, SECRETARIAT (lecture) | Classes de l'année par niveau, création |
| Fiche d'une classe | idem | Programme : matières, coefficients, groupes, heures, **enseignant de chaque matière** ; liste des élèves |
| Élèves (`/admin/eleves`) | ADMIN_ECOLE, SECRETARIAT (CENSEUR en lecture) | Nouvel élève avec son parent et son inscription, recherche, dossier, **import Excel** de la rentrée |
| Personnel (`/admin/personnel`) | ADMIN_ECOLE | Engager un enseignant (titulaire ou vacataire), **terminer un engagement** (mutation, démission, retraite, fin de contrat) ou annuler une fin programmée, annuler une invitation ; ajouter censeur, secrétariat, intendance, surveillance ; retirer un rôle |

Mise en place d'un établissement, dans l'ordre :
1. Année scolaire : créer les profils pédagogiques, puis l'année.
2. Filières et matières.
3. Classes, puis le programme de chaque classe (le profil technique exige un groupe par matière).
4. Personnel : engager les enseignants, puis les affecter dans le programme des classes.
5. Élèves : un par un, ou par l'import Excel (modèle, vérification, import).
6. Année scolaire : générer les périodes, puis ouvrir l'année.

Comptes créés (administrateur d'un établissement, enseignant, personnel) : le **mot de passe provisoire** s'affiche
une seule fois, avec un bouton pour le copier. La personne le change à sa première connexion. Un enseignant déjà
inscrit sur la plateforme reçoit une **invitation** au lieu d'un nouveau compte.

### Recherche (v0.17)

Un même composant (`partage/recherche.component.ts`, règles dans `core/recherche.ts`) filtre la liste déjà
affichée : instantané, sans accents ni majuscules (« kabore aicha » trouve « KABORÉ Aïcha »), mots dans
n'importe quel ordre, téléphones tapés avec ou sans espaces. Il affiche « n sur N » pendant une recherche ; Échap
efface.

| Page | Ce qu'on cherche | Affichée |
|---|---|---|
| Établissements (super administrateur) | Nom ou code, plus un filtre par statut (actifs, suspendus, résiliés) | Toujours |
| Personnel | Enseignant ou membre : nom, téléphone, spécialité, matricule, rôle | Toujours |
| Classes | Code, niveau ou filière | Plus de 8 classes |
| Fiche d'une classe | Élève : nom ou matricule (le n° d'ordre est conservé) | Plus de 10 élèves |
| Appel | Élève : nom, matricule ou n° d'ordre ; les marques des autres élèves sont conservées | Plus de 15 élèves |
| Feuille de notes | Idem ; **Entrée** place le curseur sur la note du premier élève trouvé | Plus de 15 élèves |
| Élèves | Recherche sur le serveur (nom, prénoms, matricule), page par page, inchangée | Toujours |

Côté enseignant, la recherche porte sur les listes du téléphone : elle marche sans réseau.

### Créer une évaluation : quand le bouton n'apparaît pas (v0.17)

« + Nouvelle évaluation » est désormais en haut de la liste des évaluations. S'il manque, l'écran dit pourquoi :
trimestres pas encore générés par l'administration (Année scolaire → Générer les périodes), périodes absentes du
téléphone (ouvrir la page une fois avec du réseau, bouton « Réessayer »), ou période verrouillée (en choisir une
autre).

### Mutation d'un enseignant (v0.16)

Exemple : M. SANOU, titulaire au lycée A, est muté au lycée B à la fin de l'année.

1. **Lycée A**, Personnel : « Terminer… » sur la ligne de l'enseignant. On saisit le **dernier jour de travail**
   (30 juin) et le motif (Mutation). L'écran rappelle les **matières à réaffecter** : elles passeront « sans
   enseignant » le lendemain. « Programmer la fin » : l'enseignant reste actif jusqu'au 30 juin inclus (appel,
   notes) et la ligne affiche « part le 30/06 ». Dans le programme des classes, la liste des enseignants l'indique
   aussi, pour éviter de lui confier une nouvelle matière.
2. **Lycée B** peut l'engager comme **titulaire à partir du 1er juillet** dès maintenant : le poste au lycée A ne
   couvre plus cette période. Il reçoit une invitation, qu'il accepte depuis son accueil.
3. **L'enseignant** voit sur son accueil, dans les 30 jours qui précèdent la date, le bandeau « Lycée A : votre
   poste se termine le 30 juin (mutation) », avec le nombre d'appels et de notes encore sur le téléphone et un
   bouton « Envoyer maintenant ». L'information est gardée sur l'appareil : elle s'affiche aussi sans réseau.
4. **Le 1er juillet à 0 h 15**, le serveur clôt l'engagement : l'enseignant perd l'accès au lycée A et ses matières
   passent « sans enseignant ». Les contrats de vacataires arrivés à échéance sont clos de la même façon.

Une date déjà passée termine l'engagement **tout de suite** (après confirmation). Une fin programmée peut être
modifiée ou annulée tant qu'aucun autre établissement n'a engagé l'enseignant sur la période libérée.

Toutes les règles restent vérifiées par le serveur (année figée, classe complète, groupe obligatoire, dernier
administrateur…). L'écran affiche son message tel quel.

## Organisation du code

```
frontend/src/app/
├── core/          session (connexion, renouvellement), intercepteur du jeton, gardes, modèles, outils
├── hors-ligne/    stockage IndexedDB, listes de classes et périodes, files d'envoi des appels et des notes
├── pages/         connexion, établissement, mot de passe, accueil, appel, notes (classes, évaluations, feuille), mes envois
├── admin/         espace d'administration : API typée, année de travail, onglets, écrans (chargés à la demande)
└── testing/       aides pour les tests
```

- Composants autonomes (*standalone*), état en **signals**, sans zone.js.
- L'accueil et les écrans de l'appel sont chargés avec l'application, pour s'ouvrir sans réseau. Les autres écrans
  sont chargés à la demande.
- Styles communs dans `src/styles.scss` (boutons de 48 px, contrastes lisibles au soleil, mode sombre).

## Tests

56 tests (Vitest) :

| Fichier | Ce qui est vérifié |
|---|---|
| `session.service.spec.ts` | Connexion, choix de l'établissement avec le jeton de sélection, démarrage sans réseau ou avec un serveur en erreur, session expirée au lancement et en cours d'utilisation, renouvellement unique puis rejeu, déconnexion (listes effacées, appels gardés), déconnexion sans réseau terminée au lancement suivant, téléphone partagé |
| `envois.service.spec.ts` | Mise en file, accusés, coupure puis renvoi avec le même identifiant, refus, lot invalide renvoyé un par un, lots de 50 dans l'ordre, cloisonnement par utilisateur et établissement, un seul envoi à la fois |
| `feuille-appel.spec.ts` | Absents et retards, bornes des minutes, récapitulatif, dates locales, identifiants, stockage |
| `appel-saisie.page.spec.ts` | Appel complet hors connexion à partir des listes du téléphone, jusqu'à la file d'envoi |
| `notes.service.spec.ts` | Création avec identifiant de l'appareil sans doublon, seuls les élèves modifiés envoyés, saisies regroupées, création refusée puis corrigée ou abandonnée avec ses notes, note refusée puis corrigée, saisie pendant un envoi, notes avant leur évaluation (horloge), conflit technique non bloquant, envoi par 200, lecture des notes saisies au clavier |
| `notes-saisie.page.spec.ts` | Feuille hors connexion : note hors barème bloquée, absence, « Abs » retiré qui redonne la note d'origine, mise en file des seuls élèves modifiés |
| `invitations.component.spec.ts` | Titulaire dans X invité comme vacataire dans Y : acceptation, choix d'établissement qui apparaît, passage dans Y |
| `admin.spec.ts` | Création d'un établissement et affichage unique du mot de passe, programme d'une classe (coefficient, enseignant), nouvel élève avec parent et inscription, engagement (compte ou invitation), mutation programmée avec les matières à réaffecter puis annulée, message du serveur sur une règle refusée |
| `recherche.spec.ts` | Accents, majuscules et ordre des mots, téléphone avec espaces ; feuille de notes filtrée qui garde le n° d'ordre et place le curseur sur la note ; message clair quand les trimestres manquent |
| `fin-engagement.component.spec.ts` | Bandeau de mutation : envois en attente comptés et envoyés tout de suite, rien à 60 jours, affichage sans réseau le jour même, annonce effacée quand la fin est annulée, calcul des jours |

La CI (`.github/workflows/ci.yml`, job *Frontend*) exécute les tests et la construction de production à chaque pull
request.

Le parcours complet a aussi été vérifié dans Chromium, à la taille d'un téléphone, avec une API simulée :
- connexion ;
- appel hors connexion, sans aucun envoi tant que le réseau manque ;
- envoi automatique au retour du réseau ;
- session expirée pendant la saisie, puis reconnexion et envoi ;
- (v0.14) administration complète d'un établissement de bout en bout : création d'une classe, programme refusé sans
  groupe puis accepté, enseignant affecté, élève inscrit depuis la fiche de la classe, trimestres générés, année
  ouverte, enseignant engagé ; création d'un établissement par le super administrateur ;
- (v0.15) notes sans réseau : évaluation créée et notée en mode avion (Entrée d'un élève à l'autre, note hors barème
  signalée), rien n'est envoyé sans réseau, puis au retour du réseau la création part avec son `idClient` et seules
  les trois notes modifiées sont envoyées ;
- (v0.16) mutation : fin programmée depuis Personnel (matières à réaffecter, « part le … »), puis bandeau sur
  l'accueil de l'enseignant, à la taille d'un téléphone ;
- (v0.17) recherche d'un établissement par le super administrateur, à la taille d'un téléphone.

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
