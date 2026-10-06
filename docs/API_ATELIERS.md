# Ateliers, équipements, matière d'œuvre et besoins (v0.25, v0.26)

Module `ateliers`, supervisé par le chef des travaux. Migrations `V18__ateliers.sql` et `V19__besoins_commandes.sql`.

Procédure suivie (établissements techniques et professionnels) : chaque atelier a un **responsable**, enseignant
d'une matière technique de la filière, désigné pour une durée propre à l'établissement. Il coordonne les travaux
pratiques, tient l'inventaire et l'état de la matière d'œuvre et des équipements, et remonte les besoins au chef des
travaux. Le circuit des besoins (campagnes, état pour la direction régionale, commande, réception, répartition) et
les exports Excel et PDF sont décrits en fin de document (v0.26).

## Qui fait quoi

| Qui | Droits |
|---|---|
| Direction des ateliers : CHEF_TRAVAUX et ADMIN_ECOLE (qui le remplace s'il n'y en a pas). Le censeur n'a pas accès aux ateliers (v0.30.1) | Tout : ateliers, désignation des responsables, paramètres, et tout ce que fait un responsable |
| Responsable d'un atelier (mandat en cours) | Dans son atelier : équipements, pannes (signalement et clôture), stock (entrées, sorties, seuils), inventaires ; catalogue des prix |
| Enseignant d'une matière technique, pratique ou module d'une classe (année active) d'une filière de l'atelier | Consulte l'atelier, **signale les pannes** |
| INTENDANT | Consulte tous les ateliers ; tient le catalogue des prix |
| Autres enseignants | Ne voient pas l'atelier (`GET /ateliers` rend une liste vide, la fiche `403`) |

## Paramètres

| Appel | Rôles |
|---|---|
| `GET /api/v1/parametres/ateliers` | Tous les rôles du module |
| `PUT /api/v1/parametres/ateliers` `{"dureeMandatMois":24,"frequenceInventaire":"SEMESTRIELLE"}` | Direction. `dureeMandatMois` de 6 à 120, ou `null` (sans limite) ; `SEMESTRIELLE` ou `ANNUELLE` |

Par défaut : mandat de 24 mois, inventaire semestriel. La durée s'applique aux désignations suivantes.

## Catalogue des prix

| Appel | Rôles |
|---|---|
| `GET /api/v1/catalogue` | Tous les rôles du module |
| `POST /api/v1/catalogue` `{"code":"FIL-2.5","designation":"Fil rigide 2,5 mm²","nature":"MATIERE_OEUVRE","unite":"rouleau","filiereId":null,"specifications":"Cuivre, rouleau de 100 m","normes":"NF C 32-201","prixReference":15000}` | Direction, intendant, responsables d'atelier |
| `PUT /api/v1/catalogue/{id}` (mêmes champs, plus `actif`) | Idem ; le code et la nature ne changent pas |
| `POST /api/v1/catalogue/{id}/photo` (multipart, champ `fichier`) | Idem ; JPEG, PNG ou WebP, 2 Mo au plus |
| `GET /api/v1/catalogue/{id}/photo` · `DELETE …/photo` | Lecture : tous ; suppression : idem création |

`nature` : `MATIERE_OEUVRE` (suivie en quantité, par atelier) ou `EQUIPEMENT` (suivi un par un). Le code est mis en
majuscules (`409 CODE_EXISTANT` s'il est pris). Chaque changement de prix est daté (`prixModifieLe`) et journalisé.

## Ateliers et responsables

| Appel | Rôles |
|---|---|
| `GET /api/v1/ateliers` | Tous les rôles du module, filtré (voir plus haut). Chaque atelier porte son responsable, ses compteurs, ses `alertes` et les `droits` de la personne |
| `POST /api/v1/ateliers` `{"code":"ELEC","nom":"Atelier d'électricité","emplacement":"Bâtiment B","postes":24,"filieres":["…"]}` | Direction ; au moins une filière |
| `GET /api/v1/ateliers/{id}` | Lecture de l'atelier ; avec l'`historique` des mandats terminés |
| `PUT /api/v1/ateliers/{id}` (mêmes champs, plus `ouvert`, `observations`) | Direction |
| `GET /api/v1/ateliers/{id}/candidats` | Direction : enseignants pouvant être désignés, avec les matières qu'ils enseignent dans la filière |
| `POST /api/v1/ateliers/{id}/responsable` `{"engagementId":"…","debut":"2026-10-05","finPrevue":null}` | Direction. `finPrevue` vide : début + durée de l'établissement. Le mandat en cours se termine la veille du nouveau |
| `POST /api/v1/ateliers/{id}/responsable/fin` `{"date":"2026-12-31","motif":"Mutation"}` | Direction |

Règles : un seul mandat en cours par atelier ; le responsable doit enseigner cette année une matière technique,
pratique ou un module d'une classe d'une filière de l'atelier (`409 RESPONSABLE_NON_ELIGIBLE`). Quand l'engagement de
l'enseignant n'est plus actif (fin de contrat, mutation), son mandat se clôt automatiquement (« Fin d'engagement de
l'enseignant ») et l'atelier est signalé sans responsable.

`alertes` : `sansResponsable`, `mandatAEcheance` (fin prévue dans les 60 jours), `mandatEchu`, `equipementsEnPanne`,
`equipementsManquants`, `articlesSousSeuil`, `inventaireEnCours`, `dernierInventaire`, `inventaireEnRetard` (aucun
inventaire clos depuis 6 ou 12 mois selon la fréquence).

## Équipements et pannes

| Appel | Rôles |
|---|---|
| `GET /api/v1/ateliers/{id}/equipements` | Lecture de l'atelier ; avec la panne ouverte de chaque équipement |
| `POST /api/v1/ateliers/{id}/equipements` `{"articleId":"…","designation":null,"numeroInventaire":null,"marque":"Bosch","numeroSerie":null,"dateAcquisition":"2024-11-12","valeur":450000}` | Direction, responsable. Numéro vide : `CODE-ANNÉE-RANG` (`ELEC-2026-001`) ; désignation vide : celle du catalogue |
| `PUT /api/v1/equipements/{id}` (mêmes champs, plus `etat` : `BON`, `MANQUANT` ou `REFORME`) | Direction, responsable |
| `GET /api/v1/equipements/{id}/pannes` | Lecture de l'atelier |
| `POST /api/v1/equipements/{id}/pannes` `{"description":"Ne démarre plus"}` | Direction, responsable, enseignants de l'atelier. L'équipement passe `EN_PANNE` ; une seule panne ouverte (`409 PANNE_DEJA_SIGNALEE`) |
| `POST /api/v1/pannes/{id}/cloture` `{"statut":"REPAREE","intervention":"Charbons remplacés","cout":7500}` | Direction, responsable. `REPAREE` : retour en service ; `IRREPARABLE` : l'équipement est réformé |

## Matière d'œuvre

| Appel | Rôles |
|---|---|
| `GET /api/v1/ateliers/{id}/stock` | Lecture : quantité, seuil, `sousLeSeuil`, prix du catalogue |
| `POST /api/v1/ateliers/{id}/mouvements` `{"articleId":"…","type":"SORTIE","quantite":3,"date":"2026-10-03","motif":"TP câblage 2nde F3"}` | Direction, responsable. `ENTREE` ou `SORTIE` ; quantité positive, deux décimales ; date passée ou du jour ; sortie limitée au stock (`409 STOCK_INSUFFISANT`) |
| `GET /api/v1/ateliers/{id}/mouvements[?articleId=]` | Lecture : 200 derniers mouvements, avec le stock qui en résulte |
| `PUT /api/v1/ateliers/{id}/stock/{articleId}/seuil` `{"seuil":5}` | Direction, responsable ; `null` : pas d'alerte |

Pendant un inventaire, le stock est figé (`409 INVENTAIRE_EN_COURS`).

## Inventaires

| Appel | Rôles |
|---|---|
| `GET /api/v1/ateliers/{id}/inventaires` | Lecture, avec le nombre d'écarts |
| `POST /api/v1/ateliers/{id}/inventaires` `{"libelle":null}` | Direction, responsable. Recopie le stock et les équipements non réformés. Libellé par défaut : « 1er semestre 2026-2027 », « 2e semestre … » ou « Inventaire annuel … » |
| `GET /api/v1/inventaires/{id}` | Lecture ; `restantes` = lignes non renseignées, `modifiable` |
| `PUT /api/v1/inventaires/{id}/lignes` `{"matieres":[{"articleId":"…","quantiteConstatee":6.5}],"equipements":[{"equipementId":"…","etatConstate":"EN_PANNE","observation":"Mandrin cassé"}],"observations":"…"}` | Direction, responsable ; saisie partielle possible |
| `POST /api/v1/inventaires/{id}/cloture` | Direction, responsable ; toutes les lignes renseignées (`409 INVENTAIRE_INCOMPLET`) |

La clôture enregistre un mouvement `INVENTAIRE` pour chaque écart de quantité et met à jour l'état des équipements
(une panne constatée ouvre une panne ; un équipement constaté en service clôt sa panne).

## Circuit des besoins (v0.26)

1. Le chef des travaux ouvre une **campagne** : `ANNEE_EN_COURS` (besoins de l'année) ou `EXAMENS` (épreuves de fin
   d'études), au plus une de chaque par année. Chaque atelier ouvert reçoit sa fiche de besoins.
2. Les **enseignants techniques de l'atelier** proposent des articles du catalogue ; le **responsable** transmet.
3. La **direction** (chef des travaux, avec le proviseur et l'intendant) arbitre les quantités, renvoie avec un
   commentaire ou valide. La validation fige le prix du catalogue.
4. Tous les besoins exprimés validés, la direction **transmet** la campagne à la direction régionale avec l'état par
   filière (désignation, spécifications, normes, photo, prix, quantités).
5. Selon le budget, la DR ou l'intendant passe **commande** ; l'intendant ou la direction l'enregistre.
6. Le chef des travaux **réceptionne** : quantité reçue, quantité conforme aux spécifications et normes, motif du refus.
7. Il **répartit** la quantité conforme entre les ateliers (part proposée au prorata des besoins retenus). À la
   validation, la matière d'œuvre entre dans le stock de chaque atelier et chaque équipement reçoit sa fiche.

### Campagnes

| Appel | Rôles |
|---|---|
| `GET /api/v1/campagnes-besoins` | Direction, intendance |
| `POST /api/v1/campagnes-besoins` `{"anneeId":null,"type":"ANNEE_EN_COURS","libelle":null,"dateLimite":"2026-11-15","observations":"…"}` | Direction. Année vide : l'année active ; `409 CAMPAGNE_EXISTANTE` |
| `GET /api/v1/campagnes-besoins/{id}` | Direction, intendance : besoins des ateliers, état consolidé par filière (`filieres`), totaux avec quantités commandées et livrées, commandes ; `gerer`, `commander` |
| `PUT /api/v1/campagnes-besoins/{id}` | Direction : libellé, date limite, consignes |
| `POST /api/v1/campagnes-besoins/{id}/transmission` | Direction ; `409 BESOINS_NON_VALIDES` (ateliers cités), `409 AUCUN_BESOIN` |
| `POST /api/v1/campagnes-besoins/{id}/cloture` | Direction |

### Besoins d'un atelier

| Appel | Rôles |
|---|---|
| `GET /api/v1/ateliers/{id}/besoins` | Lecture de l'atelier : une fiche par campagne |
| `GET /api/v1/besoins-ateliers/{id}` | Lecture de l'atelier ; `droits` : `proposer`, `modifier`, `transmettre`, `arbitrer` |
| `PUT /api/v1/besoins-ateliers/{id}/lignes/{articleId}` `{"quantite":20,"justification":"TP câblage"}` | Enseignants techniques de l'atelier, responsable, direction ; fiche en préparation, campagne ouverte. Équipement : quantité entière |
| `DELETE /api/v1/besoins-ateliers/{id}/lignes/{articleId}` | Auteur de la ligne, responsable, direction |
| `POST /api/v1/besoins-ateliers/{id}/transmission` | Responsable, direction ; `409 BESOINS_VIDES` |
| `PUT /api/v1/besoins-ateliers/{id}/arbitrage` `{"lignes":[{"articleId":"…","quantiteRetenue":15}]}` | Direction ; fiche transmise. `null` : la quantité demandée |
| `POST /api/v1/besoins-ateliers/{id}/renvoi` `{"commentaire":"…"}` | Direction ; commentaire obligatoire, la fiche repasse en préparation |
| `POST /api/v1/besoins-ateliers/{id}/validation` | Direction ; fiche transmise (`409 BESOINS_NON_TRANSMIS`) |

### Commandes, réception, répartition

| Appel | Rôles |
|---|---|
| `POST /api/v1/campagnes-besoins/{id}/commandes` `{"reference":"BC-2026-014","fournisseur":"…","passeePar":"DIRECTION_REGIONALE","dateCommande":"2026-10-02","observations":null,"lignes":[{"articleId":"…","quantite":15,"prixUnitaire":15000}]}` | Direction, intendance ; campagne transmise (`409 CAMPAGNE_NON_TRANSMISE`) ; référence unique (`409 REFERENCE_EXISTANTE`). Prix vide : celui du catalogue |
| `GET /api/v1/commandes/{id}` | Direction, intendance : reçu, conforme et reste par article ; livraisons |
| `POST /api/v1/commandes/{id}/annulation` `{"motif":"…"}` | Direction, intendance ; sans livraison (`409 COMMANDE_LIVREE`) |
| `POST /api/v1/commandes/{id}/livraisons` `{"dateReception":"2026-10-04","bonLivraison":"BL-778","lignes":[{"articleId":"…","quantiteRecue":15,"quantiteConforme":12,"motifNonConformite":"Section non conforme"}]}` | Direction. Conforme vide : tout est conforme ; motif obligatoire sinon ; `409 LIVRAISON_EXCEDENTAIRE` |
| `GET /api/v1/livraisons/{id}` | Direction, intendance : pour chaque article, part de chaque atelier (`retenu`, `proposee`, `quantite` enregistrée) |
| `PUT /api/v1/livraisons/{id}/repartition` `{"lignes":[{"articleId":"…","atelierId":"…","quantite":8}]}` | Direction ; remplace la répartition enregistrée ; `409 REPARTITION_EXCEDENTAIRE` |
| `POST /api/v1/livraisons/{id}/repartition/validation` | Direction ; toute la quantité conforme répartie (`409 REPARTITION_INCOMPLETE`) ; stock figé par un inventaire : `409 INVENTAIRE_EN_COURS` |

## Exports Excel et PDF (v0.26)

Chaque export répond à `GET …/export?format=xlsx` (par défaut) ou `?format=pdf`, en pièce jointe nommée
`{document}-{date}.xlsx|pdf`. Le PDF (A4 paysage) porte l'en-tête de l'établissement, répète les titres de colonnes
à chaque page et numérote les pages ; l'Excel a une ligne d'en-tête figée et des formats numériques. Les droits sont
ceux de la page correspondante.

| Export | Contenu |
|---|---|
| `/api/v1/ateliers/export` | Tableau de bord : responsable et fin du mandat, équipements, matières, points à surveiller |
| `/api/v1/catalogue/export` | Catalogue des prix, avec les photos dans le PDF |
| `/api/v1/ateliers/stock-par-filiere/export` | Matière d'œuvre (quantité, répartition par atelier, valeur) et équipements par état, par filière |
| `/api/v1/ateliers/{id}/equipements/export` | Équipements de l'atelier et pannes ouvertes |
| `/api/v1/ateliers/{id}/stock/export` | Stock (seuils, valeur) et derniers mouvements |
| `/api/v1/inventaires/{id}/export` | Fiche d'inventaire (à remplir sur place, ou avec les écarts une fois close) |
| `/api/v1/besoins-ateliers/{id}/export` | Fiche de besoins d'un atelier |
| `/api/v1/campagnes-besoins/{id}/export` | État des besoins pour la direction régionale : une feuille par filière (photo, désignation, spécifications, normes, prix, quantités), récapitulatif, détail par atelier, signatures |
| `/api/v1/commandes/{id}/export` | Bon de commande |
| `/api/v1/livraisons/{id}/export` | Procès-verbaux de réception (conformité) et de répartition entre les ateliers |
