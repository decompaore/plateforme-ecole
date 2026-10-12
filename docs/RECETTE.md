# Recette grandeur nature

Avant une mise en production, la recette vérifie l'application **comme ses utilisateurs s'en servent**, sur une base qui ressemble à la réalité : trois établissements (lycée technique, centre de formation professionnelle, lycée privé d'enseignement général) qui vivent depuis la rentrée, avec leur personnel, leurs enseignants, leurs élèves et leurs familles.

Elle a deux volets :

1. **La recette automatisée** (environ 290 scénarios) appelle l'API réelle pour chaque parcours, avec les droits de chaque rôle, l'isolation entre établissements, les règles métier et les cas d'erreur.
2. **La recette manuelle** (check-list ci-dessous) couvre ce qu'un script ne peut pas juger : l'interface, le hors connexion, l'impression, les SMS réellement reçus, le téléphone.

> Développement uniquement : la recette s'exécute sur la base locale. Le peuplement **efface** cette base.

## 1. Lancer la recette automatisée

Prérequis : Docker, Java 21 et Maven (ou Eclipse), Node.js 20 ou plus.

```bash
# Première fois (ou pour repartir d'une base neuve) : efface, repeuple, démarre l'API, puis recette
scripts/recette/recette-grandeur-nature.sh --repeupler

# Les fois suivantes, l'API tournant avec le profil dev : recette seule (elle est rejouable)
scripts/recette/recette-grandeur-nature.sh

# Une partie seulement (préfixes des scénarios)
scripts/recette/recette-grandeur-nature.sh --domaines=ELV,ENS,FIN

# Voir la liste des scénarios
scripts/recette/recette-grandeur-nature.sh --liste
```

Avec Eclipse : `--repeupler --api-eclipse`, puis lancez l'API (profil `dev`) quand le script le demande.

Le rapport est écrit dans `scripts/recette/rapport-recette.md` (et `.json`) : la synthèse par domaine, les échecs à analyser avec la réponse du serveur, puis chaque scénario. Chaque scénario finit dans l'un de ces trois états :

| État | Signification |
|---|---|
| ✔ réussi | Le parcours se déroule comme prévu. |
| ✘ échec | Réponse inattendue ou assertion fausse : **défaut à analyser**. Le détail donne l'appel, le statut et le message du serveur. |
| – ignoré | Un prérequis manque (module fermé, scénario précédent en échec, donnée absente). Ce n'est pas un défaut de l'application, mais le scénario n'a rien vérifié. |

Code de sortie : 0 si tout est réussi, 1 s'il y a des échecs, 2 si la recette n'a pas pu démarrer.

## 2. Ce que couvre la recette automatisée

| Lot | Préfixes | Domaine |
|---|---|---|
| 01 | SEC, CPT, ISO, ROL, MOD | Connexion, sessions et cookie, comptes et mots de passe provisoires, verrouillage, appareils, isolation entre établissements et entre familles, matrice des droits par rôle, modules désactivables |
| 02 | TER, PIL | Pays, ministères, directions (import CSV), rattachement des établissements, administrateurs pays, comptes de direction, tableau de bord du pilotage |
| 03 | STR, ELV, ENS | Année et classes, matières, dossiers des élèves et responsables, inscriptions, changement de classe, bourses, espace parent, import Excel, sorties ; enseignants : engagement, catégorie générale ou technique (v0.40), invitation d'un enseignant d'un autre établissement, affectations, fin d'engagement |
| 04 | PED, EDT, PRO, CAH, EVA, BUL | Emploi du temps (lecture, placement, conflits, exports), progressions (soumission, visa par le censeur ou le chef des travaux), cahier de textes, évaluations et notes, compétences, résultats, bulletins (publication, vérification publique, espace parent, conseil de classe) |
| 05 | VIE, APP, INC, CNV | Appels synchronisés depuis l'appareil, corrections, absences du jour, justificatifs, SMS aux familles, incidents et sanctions, convocations, historique vu par la famille |
| 06 | SCO, GUI, PAI, MMO | Frais, échéanciers, bourses et organismes, guichet et reçus, moyens de paiement choisis par l'établissement, virements déclarés puis validés, Mobile Money (simulateur), rapprochement |
| 07 | ATE, BES | Ateliers, catalogue, responsables, équipements et pannes, stock, inventaires, campagnes de besoins, commandes et livraisons |
| 08 | PLA, DON, ADO, ESP | Établissements gérés par le super administrateur (création, modules, dépannage, suspension), identité et logo, exports complets (réversibilité), statistiques, adoption (avec rattachement territorial, v0.39), choix d'espace d'un administrateur qui est aussi parent (v0.41) |
| 09 | FIN | Une année complète dans un collège de recette : préparation, notes des trois trimestres, verrouillage, bulletins, décisions du conseil, validation, clôture, année suivante, réinscriptions, archivage, résiliation |

**Ce que la recette laisse dans la base** (elle reste rejouable) :
- des élèves `RECETTE …` sortis de l'établissement ;
- un enseignant de test dont l'engagement est terminé ;
- des matières `REC-…` désactivées ;
- des invitations refusées ou annulées ;
- des appels et incidents de test (annulés pour la plupart) ;
- un collège de recette résilié par passage ;
- les comptes, directions et établissements de test des lots 02 et 06.

Les comptes de démonstration et les réglages des trois établissements sont remis dans leur état initial.

## 3. Recette manuelle

Cochez chaque point sur l'interface (`cd frontend && npm start`, puis http://localhost:4200), avec les comptes de `scripts/demo/comptes-demo.md`. Faites les points « téléphone » sur un vrai téléphone Android d'entrée de gamme (voir `docs/FRONTEND.md`, « Tester sur un téléphone »).

### Connexion et espaces

- [ ] Un mot de passe provisoire impose le changement avant tout autre écran ; un mot de passe faible est refusé avec un message clair.
- [ ] Un enseignant de deux établissements choisit son établissement à la connexion, puis en change sans se reconnecter.
- [ ] **v0.41** : un administrateur pays (ou un compte de direction) qui est aussi parent voit « Choisissez votre espace », avec l'administration en premier. Dans l'établissement de son enfant, il ne voit que l'espace parent. « Changer d'espace » le ramène à l'administration.
- [ ] La déconnexion ramène à l'écran de connexion ; le bouton « retour » du navigateur ne rouvre pas la session.

### Super administrateur, administrateur pays, directions

- [ ] **v0.39** : les onglets Établissements, Adoption et Pilotage proposent « Par territoire / Liste ». L'arbre se déplie et se replie (« Tout déplier / Tout replier »), et les totaux d'un groupe sont la somme de ses établissements.
- [ ] Les établissements sans rattachement sont regroupés sous « Sans rattachement », en dernier.
- [ ] Depuis l'arbre du territoire, « Nouvel établissement » ouvre le formulaire avec la direction déjà choisie.
- [ ] Un compte de direction ne voit que les onglets Pilotage et Adoption de son ressort, en lecture seule ; les exports Excel et PDF du pilotage s'ouvrent correctement.

### Établissement

- [ ] **v0.40** : sur la page Personnel, la colonne Catégorie s'affiche ; le bouton de suggestion propose « technique » pour un enseignant général qui assure une matière technique.
- [ ] **v0.40** : un enseignant technique rattaché à un atelier voit la tuile Ateliers, un enseignant général ne la voit pas.
- [ ] La fiche de remise des accès parents d'une classe (PDF) est lisible et se découpe ligne par ligne ; les mots de passe n'apparaissent qu'une fois.
- [ ] Import Excel des élèves : remplir le modèle téléchargé avec 5 élèves, dont 2 en erreur ; le contrôle signale les lignes fautives, l'import n'enregistre que les lignes valides.
- [ ] **v0.44.1** : dans « Nouvel élève », « Engager un enseignant » et « Ajouter une personne », le cadre « + Photo » ouvre la caméra du téléphone ; la photo apparaît dans la colonne Photo des listes (élèves, classe, personnel). Un clic sur « + Photo » dans une liste ouvre le dossier pour l'ajouter.
- [ ] **v0.44** : la secrétaire photographie un nouvel élève avec un téléphone (caméra frontale) depuis son dossier ; la miniature apparaît dans la liste de la classe ; les cartes scolaires de la classe s'impriment (8 par page, format carte bancaire, découpe nette).
- [ ] **v0.44** : un enseignant dessine sa signature sur son téléphone (Ma signature), vise sa matière après le verrouillage ; le censeur voit « visée », régénère, et le bulletin porte la signature et la date dans la colonne Visa. Une appréciation modifiée retire le visa. Une matière non visée laisse la case vide, et la génération l'annonce.
- [ ] **v0.44** : l'administrateur ajoute la photo d'un enseignant et d'une secrétaire (Personnel) et imprime les cartes professionnelles.
- [ ] **v0.43** : un enseignant saisit les appréciations de sa matière (« Appréciations du bulletin »). Le censeur, dans l'onglet Bulletins, enregistre le conseil de classe, verrouille la période, génère, relit le PDF, puis publie. Le parent voit le bulletin et reçoit le SMS ; plus rien n'est modifiable ensuite.
- [ ] Le logo de l'établissement apparaît sur les bulletins, les reçus et les exports PDF.
- [ ] Génération automatique de l'emploi du temps sur une année en préparation, puis placement et déplacement de séances à la main ; les conflits sont signalés.

### Hors connexion (téléphone, mode avion)

- [ ] L'enseignant fait l'appel en mode avion : l'appel est mis en file, puis envoyé au retour du réseau, sans doublon.
- [ ] Il saisit des notes et une séance du cahier de textes hors connexion ; elles s'envoient au retour du réseau.
- [ ] Une saisie refusée au retour (période verrouillée entre-temps) est signalée clairement, sans perte silencieuse.
- [ ] Après une mise à jour de l'application, le service worker propose de recharger ; les envois en attente partent avant.

### Impression et documents

- [ ] Les bulletins (classe entière et un par un) s'impriment correctement sur A4. Le QR code ou le code de vérification renvoie à la page publique de vérification.
- [ ] Bulletin avec photo et colonne Visa (v0.44) : la photo est nette dans le cadre d'identité, les signatures restent lisibles dans leur case, le tableau tient sur une page A4.
- [ ] Le reçu de paiement s'imprime, et son code se vérifie sur la page publique.
- [ ] Les exports Excel (statistiques, recouvrement, emploi du temps, ateliers) s'ouvrent dans Excel et LibreOffice sans alerte de réparation.
- [ ] L'archive de réversibilité (export complet) s'ouvre ; son fichier LISEZMOI.txt décrit les tables et les fichiers CSV sont lisibles.

### SMS et Mobile Money réels (préproduction avec les vraies passerelles)

- [ ] Un SMS d'absence, de convocation, de reçu et de bulletin arrive réellement sur un téléphone, avec le nom de l'établissement et sans faute d'accent.
- [ ] Un paiement Orange Money (puis Moov Money) en environnement de test de l'agrégateur arrive jusqu'au reçu, et le rapprochement du jour concorde.
- [ ] Un virement déclaré avec la photo de l'avis est validé à l'intendance ; le parent reçoit le reçu.

### Conditions réelles

- [ ] Sur un réseau lent (profil « 3G lente » du navigateur), la connexion et l'appel restent utilisables, et les écrans de chargement s'affichent.
- [ ] L'application s'installe sur l'écran d'accueil du téléphone (PWA) et s'ouvre sans barre d'adresse.
- [ ] Les écrans principaux restent lisibles en largeur de téléphone (360 px), sans défilement horizontal.

## 4. Après la recette

Corrigez les échecs, puis relancez les domaines concernés (`--domaines=…`). Avant la mise en production, rejouez une recette complète sur une base neuve (`--repeupler`) et archivez le rapport avec la version livrée.
