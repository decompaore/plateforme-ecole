# Application web (v0.31)

Application Angular 22 installable sur smartphone (PWA). Elle couvre :

- la **connexion** : téléphone et mot de passe, choix de l'établissement, changement du mot de passe provisoire,
  changement d'établissement, déconnexion ;
- l'**appel sur smartphone, même sans réseau** (enseignants, v0.13) ;
- la **saisie des notes, même sans réseau** (enseignants, v0.15) : création des évaluations et feuilles de notes ;
- l'**espace d'administration** (v0.14) : établissements pour le super administrateur ; année scolaire, filières et
  matières, classes et programmes, élèves, personnel pour l'établissement ;
- la **mutation d'un enseignant** (v0.16) : fin d'engagement programmée par l'établissement, bandeau d'avertissement
  chez l'enseignant pour envoyer ses appels et notes avant la date ;
- la **vie scolaire** (v0.18) : absences du jour et justificatifs, fiche de l'élève (incidents, convocations des
  parents), agenda des convocations ;
- l'**espace parent** (v0.19) : résumé par enfant, absences avec leur discipline, bulletins, vie scolaire,
  scolarité et paiement Mobile Money, lisible aussi sans réseau ;
- des **zones de recherche** (v0.17) sur les listes longues, qui marchent aussi sans réseau côté enseignant ;
- les **statistiques** de l'année (v0.21) et leur classeur Excel ;
- la **scolarité et les paiements** côté intendance (v0.22) : guichet, reçus, annulations, retards et relances,
  journal de caisse, frais de l'année, bourses et exonérations ;
- les **fiches de progression** (v0.23) : préparées par l'enseignant, visées par le censeur (matières générales)
  ou le nouveau **chef des travaux** (matières techniques et pratiques) ;
- le **cahier de textes, même sans réseau** (v0.24) : après chaque cours, ce qui a été fait et le travail donné,
  rattaché à une séquence ; réalisé face au prévu pour l'enseignant et la direction ;
- un **accueil par rôle** : chaque personne ne voit que les tuiles de ses rôles.

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
- un enseignant d'électrotechnique affecté aux deux classes ;
- (v0.19) frais de scolarité de 75 000 FCFA en trois tranches (la première déjà échue), Mobile Money en simulation
  (API en profil `dev`), une absence du jour pour le premier élève et l'espace parent de son parent.

À la fin, il affiche les téléphones et le mot de passe (`Demo2026`) de l'enseignant, de l'administrateur et du
parent.

**Test grandeur nature du MVP** (v0.24.1) : une seule commande efface la base locale et la remplit avec trois
établissements complets, par l'API (comme l'application) :

```bash
scripts/demo/mvp-grandeur-nature.sh                # demande de taper EFFACER ; démarre l'API avec mvn
scripts/demo/mvp-grandeur-nature.sh --api-eclipse  # vous lancez l'API depuis Eclipse quand il le demande
scripts/demo/mvp-grandeur-nature.sh --arreter-api  # arrête l'API lancée par le script
SEMAINES=6 ELEVES=30 scripts/demo/mvp-grandeur-nature.sh --oui
```

- seule la base `plateforme` du conteneur `plateforme-postgres` est effacée (pas `plateforme_test`), et seulement
  sur un Docker local, API arrêtée ;
- Lycée technique Les Bâtisseurs (profil technique : 2nde F3, 1re F4, Tle G2), Centre de formation professionnelle
  L'Atelier du Faso (modules : CAP1 ELB, CAP1 MAUTO, BEP1 HAB), Lycée privé Horizon (général : 2nde A4, 1re C,
  Tle D, sans chef des travaux) ; 60 élèves par classe, fratries, un parent dans deux établissements ;
- personnel complet, un enseignant par matière (titulaires, vacataires, un enseignant partagé par invitation),
  emploi du temps sans conflit ;
- depuis la rentrée (4 semaines par défaut) : appels, fiches de progression (visées, à revoir, à viser, brouillon,
  non commencée), cahier de textes rattaché aux séquences (un enseignant en retard par établissement), notes ou
  compétences, relevés du Module 1 publiés ;
- scolarité : frais (tranches, filières, cantine facultative), bourses et prises en charge, exonérations,
  encaissements, une annulation, virements d'organismes, Mobile Money (simulateur), relances ;
- vie scolaire : justificatifs, retards, avertissements, blâme, exclusion, convocations ; espaces parents.

Le bilan s'affiche à la fin (avec les éventuels avertissements regroupés). Les comptes (tous avec le mot de passe
`Demo2026`) et ce qu'il faut essayer avec chacun sont écrits dans `scripts/demo/comptes-demo.md` (non versionné).
Le super administrateur passe à `SuperAdmin-Dev-2026`.

**Quelques semaines d'activité** (v0.19.1) : `scripts/demo/activite-enseignant.mjs` fait « vivre » l'enseignant de
démonstration, par l'API, comme s'il travaillait depuis la rentrée :

```bash
node scripts/demo/activite-enseignant.mjs <téléphone enseignant> [<téléphone administrateur>]
SEMAINES=6 node scripts/demo/activite-enseignant.mjs …     # 4 semaines par défaut
```

- un emploi du temps de deux séances par semaine pour chaque classe et matière, et l'appel de chaque séance passée :
  la plupart des élèves viennent presque toujours, quelques-uns manquent souvent ; quelques retards ;
- une interrogation (sur 10) et un devoir (sur 20, poids 2) notés par classe et matière dans la période en cours ;
- deux avertissements donnés en classe (SMS aux familles) ;
- avec le téléphone de l'administrateur : environ la moitié des journées d'absence justifiées (certificat, mot des
  parents) et les parents de l'élève le plus absent convoqués.

Les tirages sont reproductibles et les identifiants calculés (appels, évaluations) : relancer le script ne crée
pas de doublon. Chaque
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
  Le serveur fait tourner le cookie et traite la réutilisation d'un ancien cookie comme un vol (sauf, quelques secondes, quand la réponse précédente s'est perdue : voir plus bas).
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
2. **Évaluations**. L'enseignant choisit la classe et la matière (sous chacune, quand le réseau est là, le résumé de
   l'année : « 4 évaluations cette année : 1 devoir, 2 interrogations, 1 composition », v0.20), puis la période (celle du jour par défaut). Il voit
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
| Évaluations (`/admin/evaluations`) | ADMIN_ECOLE, CENSEUR, SECRETARIAT | **Suivi des évaluations** (v0.20) : par enseignant ou par classe, sur l'année ou une période, nombre d'évaluations par type (devoirs, interrogations, compositions, TP/ateliers), date de la dernière, % de notes saisies (en rouge sous 90 %), matières sans évaluation surlignées, recherche |
| Statistiques (`/admin/statistiques`) | ADMIN_ECOLE, CENSEUR, SECRETARIAT, INTENDANT | (v0.21) Chiffres clés de l'année (élèves G/F, classes, enseignants, taux de recouvrement), effectifs par niveau avec la répartition garçons / filles, âges (total, garçons ou filles), bourses par filière, personnel, recouvrement par classe (familles et organismes, en rouge sous 50 %), résultats de fin d'année ; **téléchargement du classeur Excel**. L'intendance n'a que cet onglet dans l'administration |
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

## Modules activables (v0.31)

Page « Établissements » du super administrateur : bouton **Modules** sur chaque établissement. Une case par module
(ateliers, emplois du temps, progressions et cahier de textes, vie scolaire, scolarité, Mobile Money, espace
parent), avec ce qu'il contient ; décocher la scolarité décoche Mobile Money, cocher Mobile Money recoche la
scolarité. Dans l'établissement, un module désactivé disparaît : tuiles de l'accueil, écrans (retour à l'accueil si
on y accède par un lien), rubriques « Vie scolaire » et « Scolarité » de l'espace parent, paiement Mobile Money,
boutons de création des comptes parents, lien vers le cahier de textes après l'appel. Règles et API :
[API_SOCLE.md](API_SOCLE.md#modules-activables-v031).

## Emplois du temps (v0.30)

Le censeur n'a pas accès aux ateliers (v0.30.1) : tuile, écrans et API reviennent au chef des travaux et à
l'administrateur. Dans l'emploi du temps, la vue « Par atelier » n'apparaît que si l'établissement a des ateliers.

En ligne. Tuile « Emplois du temps » (administration, censeur, chef des travaux, surveillant) et « Mon emploi du
temps » (enseignant). Règles et API : [API_EMPLOI_DU_TEMPS.md](API_EMPLOI_DU_TEMPS.md).

- **Grille horaire** (censeur, administrateur) : une ligne par heure de cours, avec les jours où elle existe ;
  « Partir de la grille courante » propose 7 h – 12 h du lundi au samedi et 15 h – 18 h les lundi, mardi, jeudi et
  vendredi.
- **Emplois du temps** : bilan (heures placées, classes complètes, conflits), conflits à régler, grille **par
  classe, par enseignant ou par atelier** (bleu : matières générales, ocre : techniques et pratiques ; hachures :
  pas de cours ; « Autre établissement » pour les heures d'un vacataire ailleurs). Par classe, « + » sur une case
  libre ouvre le placement (matière proposée : celle à qui il manque des heures ; jour, heure, groupe, atelier de la
  filière proposé pour un TP, salle) ; une séance de son domaine s'ouvre pour être déplacée ou retirée. « + groupe »
  ajoute l'autre demi-classe. Le censeur ne voit modifiables que les matières générales, le chef des travaux que les
  techniques et pratiques.
- **Volume horaire** de la classe : heures placées / prévues pour chaque matière et qui la place.
- **Génération automatique** : toutes les classes ou la classe affichée, option « Refaire entièrement » ; le résultat
  liste ce qui reste à placer à la main.
- **Publication** (censeur) : bloquée tant qu'il reste des conflits ; l'enseignant voit ensuite ses cours jour par
  jour et télécharge son emploi du temps en PDF.
- Exports Excel et PDF de la vue affichée et de toutes les classes.

## Parents et tuteurs (v0.29)

- **Dossier d'un élève** (onglet Élèves) : chaque parent ou tuteur avec son lien (père, mère, tuteur, autre), sa
  profession, « reçoit les SMS » et « compte parent ouvert » ou « pas de compte parent ». Boutons « Créer le compte
  parent » (mot de passe provisoire affiché une fois ; si le numéro a déjà un compte, l'accès est simplement ajouté),
  « Ajouter un parent ou tuteur » (responsable légal, contact prioritaire, création du compte cochée par défaut) et
  « Retirer ».
- **Fiche d'une classe** : « Ouvrir les comptes des parents » ouvre en une fois les comptes des responsables légaux et
  des contacts prioritaires de la classe et télécharge la fiche de remise des accès (PDF ou Excel).
- Un parent qui a perdu son mot de passe se dépanne dans l'onglet Comptes (réinitialisation).

## Comptes et mots de passe (v0.27, v0.28)

- (v0.28) Tuile « Comptes et mots de passe » sur l'accueil de l'administrateur (la réinitialisation se fait dans
  l'onglet Comptes, accessible depuis chaque page de l'administration).
- (v0.28) **Renouvellement à chaque période** : à la première connexion d'un trimestre ou d'un semestre, la page
  « Mot de passe » explique qu'une nouvelle période a commencé et demande un nouveau mot de passe. La page Comptes
  affiche la date du dernier mot de passe choisi et « Mot de passe à renouveler (nouvelle période) ».

- **Comptes** (`/admin/comptes`, administrateur de l'établissement) : un compte par personne (personnel, enseignants,
  parents), avec ses rôles, sa dernière connexion (« Jamais connecté »), le blocage après trop d'essais (« Verrouillé
  jusqu'à 14h35 ») et le mot de passe provisoire pas encore changé ; filtres Actifs, Verrouillés, Mot de passe
  provisoire, Retirés ; recherche par nom ou téléphone.
- **Réinitialiser le mot de passe** : confirmation (sessions fermées, valable aussi dans les autres établissements de la
  personne, vérifier d'abord son identité), puis mot de passe provisoire affiché une seule fois avec « Copier ».
  **Déverrouiller** lève le blocage sans changer le mot de passe. Son propre compte renvoie vers « Changer mon mot
  de passe ».
- **Plateforme** (super administrateur) : bouton « Administrateurs » sur chaque établissement, pour réinitialiser le
  mot de passe d'un administrateur qui l'a oublié.
- **Connexion** : « Mot de passe oublié ? » explique la démarche (s'adresser à l'administration, se connecter avec le
  mot de passe provisoire puis en choisir un nouveau).

## Ateliers (v0.25)

Espace `/ateliers` (en ligne), pour le chef des travaux, l'administration, le censeur (s'il n'y a pas de chef des
travaux), l'intendant (lecture et catalogue) et les enseignants techniques (leurs ateliers) :

- **Ateliers** : chaque atelier avec ses filières, son responsable et la fin de son mandat, et ce qui demande
  attention (sans responsable, mandat à renouveler, équipements en panne, matières sous le seuil, inventaire à
  faire). La direction crée les ateliers et règle la durée des mandats et la fréquence des inventaires.
- **Fiche d'un atelier**, en quatre rubriques :
  - *Responsable* : désignation parmi les enseignants techniques de la filière, fin du mandat, anciens responsables ;
  - *Équipements* : ajout (numéro d'inventaire attribué automatiquement si vide), panne signalée par tout enseignant
    de l'atelier, clôture (réparé ou irréparable), réforme ;
  - *Matière d'œuvre* : stock, entrées et sorties, seuils d'alerte, valeur au prix du catalogue, derniers mouvements ;
  - *Inventaires* : ouverture, liste des inventaires et de leurs écarts.
- **Inventaire** : quantité constatée de chaque matière (bouton « Reprendre les quantités théoriques »), état
  constaté de chaque équipement, enregistrement en plusieurs fois, clôture qui corrige le stock.
- **Catalogue des prix** : matière d'œuvre et équipements, spécifications, normes, prix de référence daté, photo.

En ligne. Règles et API : [API_ATELIERS.md](API_ATELIERS.md). Le test grandeur nature crée des ateliers au lycée
technique et au centre de formation (responsables, stock, équipements, pannes, un inventaire clos, un en cours).

### Besoins, commandes et exports (v0.26)

Onglet **Besoins et commandes** (`/ateliers/besoins`, direction des ateliers et intendant) et rubrique *Besoins* de
chaque atelier :

- **Campagnes** : deux par an (année en cours, examens de fin d'études), ouvertes par le chef des travaux avec une
  date limite et des consignes ; avancement (ateliers ayant transmis, validés, montant retenu, commandes).
- **Fiche de besoins d'un atelier** (`/ateliers/besoins-ateliers/:id`) : les enseignants techniques ajoutent les
  articles du catalogue (spécifications et normes affichées, quantité entière pour un équipement, justification) ;
  le responsable transmet ; le chef des travaux saisit les quantités retenues, renvoie avec un commentaire ou valide.
- **Campagne** (`/ateliers/besoins/:id`) : besoins de chaque atelier, état consolidé par filière, transmission à la
  direction régionale, puis commandes (formulaire prérempli avec ce qui reste à commander et les prix du catalogue).
- **Commande** (`/ateliers/commandes/:id`) : reçu, conforme et reste par article ; réception d'une livraison
  (quantité reçue, dont conforme, motif de non-conformité) ; annulation tant que rien n'est livré.
- **Livraison** (`/ateliers/livraisons/:id`) : procès-verbal de réception et grille de répartition article × atelier,
  préremplie avec la part proposée (besoin retenu affiché sous chaque case), total contrôlé, validation qui met à
  jour le stock et les équipements des ateliers.

**Boutons Excel et PDF** (composant `app-export`) sur toutes les pages du chef des travaux et du chef d'atelier :
tableau de bord des ateliers et stock par filière, catalogue des prix, équipements et stock d'un atelier, fiche
d'inventaire, fiche de besoins, état des besoins pour la DR, bon de commande, procès-verbaux de réception et de
répartition. Le fichier est édité par le serveur et enregistré sous le nom qu'il propose ; une erreur du serveur
s'affiche à côté des boutons.

## Cahier de textes sans réseau (v0.24)

Comme l'appel et les notes, la saisie se fait d'abord sur le téléphone, puis part dès que le réseau revient.

1. **Accès** : tuile « Cahier de textes » (choix de la classe et de la matière, à partir des listes du téléphone), ou
   lien « Remplir le cahier de textes de ce cours » juste après un appel : la date et l'horaire sont préremplis.
2. **Saisie** : date (pas dans le futur), début, fin, séquence de la fiche de progression (la première pas encore
   terminée est proposée ; « hors séquence » pour une révision ou une évaluation), ce qui a été fait, travail à faire.
   Le bouton affiche la durée (« Enregistrer (2 h) »).
3. **Sans réseau** : la séance est gardée sur le téléphone (« en attente d'envoi ») et comptée dans l'avancement ;
   le compteur de l'en-tête et « Mes envois » la signalent. Au retour du réseau elle part toute seule, avec son
   identifiant : un renvoi ne crée pas de doublon. Une séance refusée (doublon, date future) reste visible avec le
   message du serveur, à modifier ou abandonner.
4. **Lecture sans réseau** : la fiche de progression et les séances déjà envoyées sont gardées sur le téléphone
   (effacées à la déconnexion, comme les listes ; les séances en attente, elles, sont gardées).
5. **Avancement** : heures faites / prévues par séquence.

Côté direction, la fiche de progression montre le **réalisé** (jauge, heures faites, séances, dernière séance, heures
hors séquence) et « Lire le cahier de textes » ; le suivi des progressions affiche pour chaque matière les heures
faites face aux prévues et la date jusqu'à laquelle le cahier est tenu.

## Fiches de progression (v0.23)

En ligne. Règles et API : [API_PROGRESSION.md](API_PROGRESSION.md).

| Écran | Rôles | Ce qu'on y fait |
|---|---|---|
| Mes progressions (`/progression`) | ENSEIGNANT | Une ligne par matière confiée : statut (non commencée, brouillon, à viser, visée, à revoir), séquences, heures prévues ; renvois signalés en haut |
| Fiche (`/progression/:classe/:matiere`) | ENSEIGNANT de la matière ; direction du domaine | **Enseignant** : séquences (titre, heures prévues, semaine de début, contenus, compétences), ajouter, retirer, monter, descendre ; total des heures face au volume du programme ; « Enregistrer » puis « Soumettre au visa » (enregistre d'abord ce qui est en cours). **Direction** : lecture, puis « Viser » ou « Renvoyer à l'enseignant » avec un commentaire obligatoire |
| Progressions (`/progression/suivi`) | ADMIN_ECOLE, CENSEUR, CHEF_TRAVAUX | Chiffres : à viser, visées, en préparation, non commencées ; filtre « À viser » ou « Non commencées », recherche, regroupement par classe |

Le chef des travaux s'ajoute dans **Personnel** (rôle « Chef des travaux »). Tant qu'il n'y en a pas, le censeur suit
toutes les matières.

## Scolarité et paiements (v0.22)

En ligne uniquement : un encaissement exige le serveur (reçu numéroté, SMS au parent).

| Écran | Rôles | Ce qu'on y fait |
|---|---|---|
| Guichet (`/scolarite`) | INTENDANT, ADMIN_ECOLE, SECRETARIAT | Retrouver l'élève par son nom ou son matricule |
| Fiche de l'élève (`/scolarite/eleves/:id`) | idem (SECRETARIAT en lecture) | Reste à payer, retard, payé, prochaine échéance, part de l'organisme ; **encaisser** (famille ou organisme, montant proposé en un clic : le retard ou tout le reste, espèces, Orange / Moov / Telecel Money, virement, chèque, référence obligatoire hors espèces, déposant, date) puis **imprimer le reçu PDF** ; échéancier tranche par tranche ; paiements avec leur reçu et **annulation avec motif** ; prise en charge d'un boursier (organisme, taux, décision), exonérations, frais facultatifs (souscrire, résilier) |
| Classes et retards (`/scolarite/classes`) | INTENDANT, ADMIN_ECOLE, SECRETARIAT | Taux de recouvrement d'une classe, élèves en retard (le plus gros retard d'abord), **liste Excel des retards**, **relance SMS** de la classe ou de tout l'établissement (INTENDANT, ADMIN_ECOLE) |
| Journal de caisse (`/scolarite/journal`) | INTENDANT, ADMIN_ECOLE | Paiements d'un jour ou d'une période avec l'élève et sa classe, total par moyen de paiement pour la clôture de caisse, reçu de chaque paiement |
| Frais et bourses (`/scolarite/frais`) | INTENDANT, ADMIN_ECOLE (SECRETARIAT en lecture) | Frais de l'année : montant, à qui ils s'appliquent (tout l'établissement, filières, niveaux, classes), obligatoire ou facultatif, couvert ou non par la bourse, **tranches** (réparties à parts égales en un clic, somme contrôlée) ; organismes financeurs ; paramètres (taux par défaut, délai entre deux relances : ADMIN_ECOLE) |

- **Pas de double encaissement** : chaque saisie porte une clé unique (`cleIdempotence`) ; un double clic ou un
  renvoi après une coupure renvoie le même paiement et le même reçu.
- Un paiement n'est **jamais modifié ni supprimé** : annulé, il ne compte plus et son reçu porte « REÇU ANNULÉ ».
- L'écran contrôle avant l'envoi ce que le serveur refuserait : montant entier, pas plus que le reste du payeur,
  référence hors espèces, date pas dans le futur ; tranches dans l'ordre et de somme égale au montant.

## Espace parent (v0.19)

Pour le parent (rôle PARENT), dont l'espace a été ouvert par le secrétariat. Pensé pour un petit téléphone et un
réseau faible : chaque lecture est gardée sur le téléphone, et sans réseau le parent revoit la dernière situation
connue, avec sa date (« Pas de réseau : situation du … »). Ces copies sont effacées à la déconnexion.

| Écran | Ce qu'on y voit |
|---|---|
| Mes enfants (`/parent`) | Une carte par enfant : classe, convocation à venir, jours d'absence non justifiée des 30 derniers jours, scolarité (en retard, reste à payer et prochaine échéance, ou soldée) |
| Suivi d'un enfant (`/parent/enfants/:id`) | Quatre rubriques : **Absences** (heures de l'année, dont non justifiées, retards ; jour par jour avec la discipline de chaque cours) ; **Bulletins** publiés (moyenne, rang, distinction, PDF à télécharger) ; **Vie scolaire** (convocations, incidents non annulés, sans les comptes rendus internes) ; **Scolarité** (dû, payé, reste, retard, échéances, paiements et reçus PDF) |

**Paiement Mobile Money** (rubrique Scolarité) : le parent choisit Orange Money, Moov Money ou Telecel Money, le montant (le
retard est proposé, ou tout le reste) et le numéro qui paie. Il confirme sur son téléphone avec son code secret ;
l'écran suit la transaction toutes les 5 secondes jusqu'au résultat (reçu, échec, délai dépassé, à vérifier par
l'intendance) puis relit la situation. Une clé unique par demande évite tout double paiement (double appui, réseau
lent). Le paiement et les PDF demandent du réseau. En développement (`npm start`), deux boutons simulent la
réponse de l'opérateur (API en profil `dev`, agrégateur SIMULATEUR).

Côté serveur, rien de nouveau : l'espace parent utilise les points d'accès `espace-parent` existants (voir
`API_ELEVES.md`, `API_ABSENCES.md`, `API_VIE_SCOLAIRE.md`, `API_BULLETINS.md`, `API_SCOLARITE.md`,
`API_MOBILE_MONEY.md`).

## Vie scolaire (v0.18)

En ligne, comme l'administration. Rôles : SURVEILLANT, CENSEUR, ADMIN_ECOLE ; SECRETARIAT en consultation et pour
les justificatifs. Le serveur vérifie chaque action.

| Écran | Ce qu'on y fait |
|---|---|
| Absences du jour (`/vie-scolaire`) | Tous les élèves absents ou en retard dans l'établissement, classe par classe, d'après les appels reçus, avec la **discipline** de chaque créneau (« absent 08h00–10h00 · Mathématiques », « appel général » sans matière) ; jour précédent ou suivant ; bilan (absents, retards, à justifier) ; recherche et filtre « à justifier » ; **Justifier** sur place (motif, période, précision) |
| Élèves (`/vie-scolaire/eleves`) | Recherche sur le serveur (nom, prénoms, matricule) pour ouvrir une fiche |
| Fiche de l'élève (`/vie-scolaire/eleves/:id`) | Parents à contacter (celui qui reçoit les SMS en premier, numéro cliquable), bilan de l'année, absences jour par jour avec leur discipline, **heures manquées par discipline** (dont non justifiées, retards) et justificatifs, **incidents** (retard à l'entrée, avertissement ; blâme et exclusion pour la direction ; SMS à la famille proposé selon le type ; annulation avec motif), **convocations** des parents (motif prérempli depuis un incident ; clôture : venu, pas venu, annulé) ; choix de l'année |
| Classes (`/vie-scolaire/classes`, v0.18.1) | Pour une classe, sur l'année, un trimestre (ou semestre) du profil de la classe, ou les 30 derniers jours : absences **par discipline** (cours manqués, élèves concernés, heures, non justifiées, retards ; les plus manquées d'abord) et **par élève** (heures non justifiées d'abord, lien vers la fiche) |
| Convocations (`/vie-scolaire/convocations`) | Rendez-vous des 30 prochains jours ; ceux des 14 derniers jours à clôturer d'un geste (« Venu », « Pas venu ») |

La liste du jour vient de `GET /api/v1/absences/jour` (v0.18, voir `API_ABSENCES.md`).

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

86 tests (Vitest) :

| Fichier | Ce qui est vérifié |
|---|---|
| `session.service.spec.ts` | Connexion, choix de l'établissement avec le jeton de sélection, démarrage sans réseau ou avec un serveur en erreur, session expirée au lancement et en cours d'utilisation, renouvellement unique puis rejeu, déconnexion (listes effacées, appels gardés), déconnexion sans réseau terminée au lancement suivant, téléphone partagé |
| `envois.service.spec.ts` | Mise en file, accusés, coupure puis renvoi avec le même identifiant, refus, lot invalide renvoyé un par un, lots de 50 dans l'ordre, cloisonnement par utilisateur et établissement, un seul envoi à la fois |
| `feuille-appel.spec.ts` | Absents et retards, bornes des minutes, récapitulatif, dates locales, identifiants, stockage |
| `appel-saisie.page.spec.ts` | Appel complet hors connexion à partir des listes du téléphone, jusqu'à la file d'envoi |
| `notes.service.spec.ts` | Création avec identifiant de l'appareil sans doublon, seuls les élèves modifiés envoyés, saisies regroupées, création refusée puis corrigée ou abandonnée avec ses notes, note refusée puis corrigée, saisie pendant un envoi, notes avant leur évaluation (horloge), conflit technique non bloquant, envoi par 200, lecture des notes saisies au clavier |
| `notes-saisie.page.spec.ts` | Feuille hors connexion : note hors barème bloquée, absence, « Abs » retiré qui redonne la note d'origine, mise en file des seuls élèves modifiés |
| `invitations.component.spec.ts` | Titulaire dans X invité comme vacataire dans Y : acceptation, choix d'établissement qui apparaît, passage dans Y |
| `admin.spec.ts` | Création d'un établissement et affichage unique du mot de passe, programme d'une classe (coefficient, enseignant), nouvel élève avec parent et inscription, engagement (compte ou invitation), mutation programmée avec les matières à réaffecter puis annulée, message du serveur sur une règle refusée, suivi des évaluations (tuiles, regroupement par enseignant puis par classe, filtre de période), statistiques (chiffres clés, âges par sexe, recouvrement sous 50 % en rouge, résultats à venir, classeur Excel) |
| `parent.spec.ts` | Résumés (absences des 30 derniers jours, retard, reste, soldée), cartes des enfants puis même situation sans réseau, fiche d'un enfant (discipline, appel général, bulletin téléchargé, incidents), paiement Mobile Money suivi toutes les 5 secondes jusqu'à la confirmation puis situation relue, sans suivi au-delà |
| `cahier.page.spec.ts` | Contrôles de la séance (date future, horaire, 8 heures, contenu) ; après l'appel : créneau prérempli, séquence en cours proposée, séance gardée sans réseau et comptée dans l'avancement, renvoi avec le même identifiant puis « Séance envoyée » ; refus du serveur conservé avec son message |
| `progression.spec.ts` | Contrôle des séquences (titre, heures à une décimale), liste de l'enseignant avec renvoi signalé, première séquence enregistrée puis soumise au chef des travaux, renvoi par le chef des travaux avec commentaire obligatoire, suivi (chiffres, filtre « À viser ») |
| `scolarite.spec.ts` | Contrôles avant l'envoi (montant, reste, référence, date, tranches, répartition), guichet d'une semi-boursière : retard proposé, référence Orange Money exigée, encaissement avec clé d'idempotence puis reçu, annulation avec motif ; secrétariat en lecture seule ouvert depuis une inscription ; journal de caisse (totaux par moyen, élève et classe, plus récent d'abord) ; retards d'une classe et relance SMS ; nouveau frais en trois tranches |
| `vie-scolaire.spec.ts` | Absences du jour (discipline de chaque créneau, appel général, bilan, filtre « à justifier », justification puis liste à jour), fiche élève (parent prioritaire en premier, heures par discipline, avertissement avec SMS, blâme absent pour un surveillant, convocation préremplie depuis l'incident), clôture d'une convocation passée, synthèse d'une classe par discipline et par élève, sur l'année puis sur un trimestre |
| `recherche.spec.ts` | Accents, majuscules et ordre des mots, téléphone avec espaces ; feuille de notes filtrée qui garde le n° d'ordre et place le curseur sur la note ; message clair quand les trimestres manquent |
| `ateliers.spec.ts` | Contrôles (quantités à deux décimales, code d'atelier, unité d'un article), alertes en phrases ; liste du chef des travaux (chiffres, sans responsable, matières sous le seuil, paramètres) ; désignation du responsable parmi les candidats ; panne signalée par un enseignant de l'atelier ; inventaire saisi (écart affiché, « Tous conformes ») puis clos |
| `besoins.spec.ts` | Contrôles (ligne de besoin, réception, répartition, nom du fichier exporté) ; boutons d'export (Excel téléchargé avec le nom du serveur, erreur du PDF affichée) ; article proposé puis transmis par le responsable ; arbitrage et validation par le chef des travaux ; commande préremplie avec le reste à commander ; répartition d'une livraison (excédent signalé, validation) |
| `parents.spec.ts` | Contrôle d'un parent ou tuteur ; dossier d'un élève : compte parent créé (mot de passe affiché), tuteur ajouté avec un compte existant, retrait ; fiche d'une classe : comptes des parents ouverts et fiche PDF téléchargée |
| `comptes.spec.ts` | (v0.28) message de renouvellement de période sur la page « Mot de passe » ; heure de fin du blocage ; comptes de l'administrateur (chiffres, jamais connecté, verrouillé, son propre compte sans réinitialisation), déverrouillage, réinitialisation confirmée puis mot de passe provisoire affiché une fois avec l'avertissement « autre établissement », comptes retirés sans action ; réinitialisation d'un administrateur par le super administrateur |
| `fin-engagement.component.spec.ts` | Bandeau de mutation : envois en attente comptés et envoyés tout de suite, rien à 60 jours, affichage sans réseau le jour même, annonce effacée quand la fin est annulée, calcul des jours |

La CI (`.github/workflows/ci.yml`, job *Frontend*) exécute les tests et la construction de production à chaque pull
request, puis `scripts/verifier-routes.mjs` (v0.20) : **chaque appel de l'application doit avoir sa route dans le
backend**, sinon la CI échoue.

Sur un poste de développement, `node scripts/verifier-routes.mjs --api` compare aussi le code avec l'API qui tourne
(profil dev, `/v3/api-docs`) : une route présente dans le code mais absente de l'API signale une API lancée avec une
ancienne version compilée (erreur « No static resource … » dans l'application). Arrêtez-la puis
`cd backend && mvn clean spring-boot:run -Dspring-boot.run.profiles=dev`.

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
- (v0.17) recherche d'un établissement par le super administrateur, à la taille d'un téléphone ;
- (v0.18) vie scolaire d'un surveillant : absences du jour sur téléphone, justification, fiche de l'élève sur
  ordinateur, formulaire d'incident, agenda des convocations ;
- (v0.25) ateliers : liste du chef des travaux avec les alertes, désignation d'un responsable, équipements et
  pannes, stock et sortie de matière d'œuvre, inventaire saisi sur téléphone, catalogue des prix ;
- (v0.27) comptes : liste sur ordinateur et téléphone, confirmation puis mot de passe provisoire, aide « Mot de passe
  oublié ? » sur la page de connexion, réinitialisation d'un administrateur par le super administrateur ;
- (v0.26) besoins et commandes : campagnes, état par filière et téléchargement du PDF, formulaire de commande,
  réception, répartition (excédent signalé puis validation), arbitrage et validation d'une fiche de besoins ; boutons
  d'export et rubrique *Besoins* sur téléphone ;
- (v0.18.1) disciplines : liste du jour sur téléphone, heures par discipline dans la fiche, synthèse d'une classe ;
- (v0.19) espace parent sur un téléphone de 360 px : deux enfants, absences, bulletin, scolarité, paiement Orange
  Money simulé jusqu'au reçu ;
- (v0.20) suivi des évaluations sur ordinateur : regroupement par enseignant, matières sans évaluation, taux de
  notes saisies ;
- (v0.21) statistiques sur ordinateur et sur un téléphone de 390 px ;
- (v0.22) guichet : recherche de l'élève, encaissement du retard, reçu proposé ; retards et relance d'une classe ;
  journal de caisse ; nouveau frais en tranches ; fiche de l'élève sur un téléphone de 390 px ;
- (v0.23) progression : deux séquences saisies sur un téléphone par l'enseignant puis soumises, suivi du chef des
  travaux, visa avec commentaire ;
- (v0.24) cahier de textes sur un téléphone : une séance envoyée, une seconde en mode avion (en attente, comptée
  dans l'avancement), envoi automatique au retour du réseau ; réalisé et lecture du cahier par le chef des travaux.

## Session sur réseau faible (v0.18)

Si la réponse d'un renouvellement de session se perd, le téléphone renvoie l'ancien cookie quelques secondes plus
tard : le serveur l'accepte désormais une fois (voir `CONFIGURATION.md`) au lieu de fermer la session. Un cookie
volé rejoué plus tard reste détecté.

## Mise en production (à venir)

L'application construite est un ensemble de fichiers statiques. Elle sera servie par Caddy **sur le même domaine
que l'API** (`/api` vers les instances Spring Boot, le reste vers les fichiers). Le cookie SameSite=Strict l'impose.
Il faudra aussi le repli vers `index.html` pour les adresses de l'application. Ce sera fait avec la chaîne de
déploiement du frontend.
