#!/usr/bin/env node
/**
 * TEST GRANDEUR NATURE DU MVP : peuple une base VIDE, par l'API (comme le ferait l'application),
 * avec trois établissements qui « vivent » depuis la rentrée.
 *
 *   1. Lycée technique Les Bâtisseurs (profil technique) : F3, F4, G2 ; chef des travaux.
 *   2. Centre de formation professionnelle L'Atelier du Faso (profil professionnel, modules) :
 *      CAP électricité du bâtiment, CAP mécanique automobile, BEP habillement ; chef des travaux.
 *   3. Lycée privé Horizon (profil général) : A4, C, D ; pas de chef des travaux (le censeur supervise tout).
 *
 * Dans chaque établissement : une classe par filière, 60 élèves par classe avec leurs parents
 * (fratries, un parent ayant des enfants dans deux établissements), le personnel (censeur, chef des
 * travaux, secrétariat, intendant, surveillant), un enseignant par matière (titulaires et vacataires,
 * un enseignant partagé entre deux établissements par invitation), un emploi du temps, et depuis la
 * rentrée :
 *   - les appels de chaque séance (absences, retards) ;
 *   - les fiches de progression (soumises, visées, à revoir, en brouillon, non commencées) ;
 *   - le cahier de textes rattaché aux séquences (un enseignant en retard dans chaque établissement) ;
 *   - les évaluations et les notes, ou les compétences (modules) avec relevés publiés pour le Module 1 ;
 *   - la scolarité : frais, bourses et prises en charge, exonérations, cantine, encaissements au guichet,
 *     une annulation, un virement d'organisme, des paiements Mobile Money (simulateur), des relances ;
 *   - la vie scolaire : justificatifs, retards, avertissements, blâme, exclusion, convocations ;
 *   - l'espace parent ouvert pour une dizaine de familles par établissement.
 *
 * DÉVELOPPEMENT UNIQUEMENT : mots de passe connus, numéros fictifs. Normalement lancé par
 * scripts/demo/mvp-grandeur-nature.sh, qui efface d'abord la base locale et démarre l'API.
 *
 *   node scripts/demo/peupler-mvp.mjs
 *
 * Variables facultatives :
 *   API=http://localhost:8080
 *   SUPER_ADMIN_TELEPHONE=70000000
 *   SUPER_ADMIN_MOT_DE_PASSE=ChangezMoi-Dev-2026     mot de passe initial (base neuve)
 *   SUPER_ADMIN_NOUVEAU_MOT_DE_PASSE=SuperAdmin-Dev-2026   remplace le mot de passe provisoire
 *   DEMO_MOT_DE_PASSE=Demo2026       mot de passe de tous les comptes créés
 *   SEMAINES=4                       semaines écoulées depuis la rentrée (2 à 20)
 *   ELEVES=60                        élèves par classe (5 à 60)
 *   PARALLELE=6                      requêtes simultanées
 *
 * Les tirages sont reproductibles (graines fixes) : deux exécutions sur une base neuve donnent les
 * mêmes élèves, absences et notes, à la date près.
 */
import { createHash } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const API = (process.env.API ?? 'http://localhost:8080').replace(/\/$/, '') + '/api/v1';
const SUPER_ADMIN_TELEPHONE = process.env.SUPER_ADMIN_TELEPHONE ?? '70000000';
const SUPER_ADMIN_MOT_DE_PASSE = process.env.SUPER_ADMIN_MOT_DE_PASSE ?? 'ChangezMoi-Dev-2026';
const SUPER_ADMIN_NOUVEAU = process.env.SUPER_ADMIN_NOUVEAU_MOT_DE_PASSE ?? 'SuperAdmin-Dev-2026';
const MOT_DE_PASSE = process.env.DEMO_MOT_DE_PASSE ?? 'Demo2026';
const SEMAINES = borne(Number(process.env.SEMAINES ?? 4), 2, 20);
const ELEVES_PAR_CLASSE = borne(Number(process.env.ELEVES ?? 60), 5, 60);
const PARALLELE = borne(Number(process.env.PARALLELE ?? 6), 1, 16);
const FICHIER_COMPTES = join(dirname(fileURLToPath(import.meta.url)), 'comptes-demo.md');

if (MOT_DE_PASSE.length < 8 || !/[A-Za-z]/.test(MOT_DE_PASSE) || !/[0-9]/.test(MOT_DE_PASSE)) {
  arreter('DEMO_MOT_DE_PASSE doit faire au moins 8 caractères, avec une lettre et un chiffre.');
}
if (SUPER_ADMIN_NOUVEAU.length < 12) {
  arreter('SUPER_ADMIN_NOUVEAU_MOT_DE_PASSE doit faire au moins 12 caractères.');
}

// ===================================================================== données

const NOMS = [
  'OUEDRAOGO', 'SAWADOGO', 'KABORE', 'COMPAORE', 'ZONGO', 'TRAORE', 'ILBOUDO', 'NIKIEMA', 'KONATE', 'SANOU',
  'OUATTARA', 'DIALLO', 'YAMEOGO', 'ZOUNGRANA', 'KAFANDO', 'BAMBARA', 'TAPSOBA', 'SOME', 'DABIRE', 'BONKOUNGOU',
  'KIEMA', 'ROAMBA', 'BELEM', 'KINDA', 'NANA', 'TIENDREBEOGO', 'GUIGMA', 'ZERBO', 'COULIBALY', 'KY',
  'SIMPORE', 'PODA', 'HIEN', 'MEDA', 'PARE', 'TOE', 'BARRY', 'CISSE', 'SORGHO', 'OUOBA',
];
const PRENOMS_F = [
  'Awa', 'Aïcha', 'Fatimata', 'Rasmata', 'Mariam', 'Salimata', 'Aminata', 'Adjaratou', 'Pauline', 'Estelle',
  'Nafissatou', 'Clarisse', 'Bintou', 'Alimata', 'Safiatou', 'Delphine', 'Rokia', 'Assétou', 'Carine', 'Habibou',
  'Wendyam', 'Sandrine', 'Odile', 'Germaine', 'Kadidiatou', 'Ramatou', 'Edwige', 'Florence', 'Zénabo', 'Inès',
];
const PRENOMS_M = [
  'Ali', 'Moussa', 'Salif', 'Issouf', 'Rasmané', 'Boukary', 'Abdoul Aziz', 'Ousmane', 'Idrissa', 'Seydou',
  'Wendkouni', 'Arouna', 'Hamidou', 'Jean-Baptiste', 'Serge', 'Yacouba', 'Adama', 'Karim', 'Désiré', 'Boureima',
  'Lassané', 'Inoussa', 'Paul', 'Noufou', 'Christian', 'Mahamadi', 'Ibrahim', 'Firmin', 'Sylvain', 'Abdoulaye',
];
const LIEUX = ['Ouagadougou', 'Koudougou', 'Bobo-Dioulasso', 'Réo', 'Kaya', 'Ouahigouya', 'Ziniaré', 'Manga', 'Tenkodogo', 'Dédougou'];

/** Catalogue des matières : libellé et type. */
const CATALOGUE = {
  MATH: ['Mathématiques', 'GENERALE'],
  FR: ['Français', 'GENERALE'],
  ANG: ['Anglais', 'GENERALE'],
  PC: ['Physique-chimie', 'GENERALE'],
  ECO: ['Économie générale', 'GENERALE'],
  HG: ['Histoire-géographie', 'GENERALE'],
  SVT: ['Sciences de la vie et de la Terre', 'GENERALE'],
  PHILO: ['Philosophie', 'GENERALE'],
  ESP: ['Espagnol', 'GENERALE'],
  INFO: ['Informatique', 'TECHNIQUE'],
  ELEC: ['Électrotechnique', 'TECHNIQUE'],
  'DESSIN-EL': ['Dessin technique d’électricité', 'TECHNIQUE'],
  'TP-ELEC': ['Travaux pratiques d’électricité', 'PRATIQUE'],
  RDM: ['Résistance des matériaux', 'TECHNIQUE'],
  'DESSIN-BAT': ['Dessin de bâtiment', 'TECHNIQUE'],
  'TP-BAT': ['Travaux pratiques de construction', 'PRATIQUE'],
  COMPTA: ['Comptabilité générale', 'TECHNIQUE'],
  ORG: ['Organisation des entreprises', 'TECHNIQUE'],
  'INFO-G': ['Informatique de gestion', 'PRATIQUE'],
  COM: ['Communication professionnelle', 'MODULE_COMPETENCES'],
  'MATH-APP': ['Mathématiques appliquées au métier', 'MODULE_COMPETENCES'],
  HSE: ['Hygiène, santé et sécurité au travail', 'MODULE_COMPETENCES'],
  ENTR: ['Entrepreneuriat', 'MODULE_COMPETENCES'],
  'INST-ELB': ['Installations électriques domestiques', 'MODULE_COMPETENCES'],
  SCHEMA: ['Lecture et réalisation de schémas', 'MODULE_COMPETENCES'],
  MOTEUR: ['Maintenance du moteur', 'MODULE_COMPETENCES'],
  'ELEC-AUTO': ['Électricité automobile', 'MODULE_COMPETENCES'],
  PATRON: ['Patronage et coupe', 'MODULE_COMPETENCES'],
  ASSEMB: ['Assemblage et finitions', 'MODULE_COMPETENCES'],
};

/** Séquences des fiches de progression (et, pour les modules, les trois premières sont les compétences). */
const SEQUENCES = {
  MATH: ['Calcul numérique et algébrique', 'Équations et inéquations du premier degré', 'Fonctions numériques', 'Statistiques', 'Géométrie dans l’espace'],
  FR: ['Le résumé de texte', 'La dissertation : méthode', 'Étude d’une œuvre intégrale', 'Le commentaire composé', 'Expression orale et exposé'],
  ANG: ['Greetings and introductions', 'Describing people and places', 'Technical vocabulary', 'Writing a formal letter', 'Reading comprehension'],
  PC: ['Électrocinétique : courant et tension', 'Mécanique : forces et équilibre', 'Chimie : atomes et molécules', 'Optique géométrique', 'Énergie et puissance'],
  ECO: ['Les agents économiques', 'La production', 'La monnaie et le financement', 'Le marché', 'La croissance économique'],
  HG: ['Les grandes découvertes', 'Le relief du Burkina Faso', 'La population mondiale', 'Les indépendances africaines', 'L’économie du Burkina Faso'],
  SVT: ['La cellule', 'La nutrition chez les végétaux', 'La reproduction humaine', 'Les écosystèmes', 'La géologie du Burkina Faso'],
  PHILO: ['Qu’est-ce que la philosophie ?', 'La conscience', 'Le travail', 'La liberté', 'La vérité'],
  ESP: ['Saludos y presentaciones', 'La familia', 'La vida cotidiana', 'Los viajes', 'Comprensión de textos'],
  INFO: ['Architecture de l’ordinateur', 'Système d’exploitation', 'Traitement de texte', 'Tableur', 'Algorithmique de base'],
  ELEC: ['Lois générales de l’électricité', 'Circuits en courant continu', 'Courant alternatif monophasé', 'Transformateurs', 'Machines à courant continu'],
  'DESSIN-EL': ['Normes et conventions du dessin', 'Symboles électriques normalisés', 'Schémas développés et unifilaires', 'Plans d’installation', 'Dessin assisté par ordinateur'],
  'TP-ELEC': ['Sécurité et habilitation électrique', 'Câblage d’un circuit d’éclairage', 'Va-et-vient et télérupteur', 'Mesures au multimètre', 'Tableau de distribution domestique'],
  RDM: ['Statique : actions mécaniques', 'Traction et compression', 'Cisaillement', 'Flexion simple', 'Dimensionnement des poutres'],
  'DESSIN-BAT': ['Conventions du dessin de bâtiment', 'Plans de masse et de situation', 'Vues en plan et coupes', 'Façades et détails', 'Métré et quantitatif'],
  'TP-BAT': ['Sécurité sur le chantier', 'Implantation d’un ouvrage', 'Maçonnerie de parpaings', 'Coffrage et ferraillage', 'Enduits et finitions'],
  COMPTA: ['Le bilan et le compte de résultat', 'Les comptes et la partie double', 'Les opérations d’achat et de vente', 'La TVA', 'Les opérations de trésorerie'],
  ORG: ['L’entreprise et son environnement', 'Les structures de l’entreprise', 'La fonction commerciale', 'La fonction ressources humaines', 'La gestion de la production'],
  'INFO-G': ['Découverte de l’ordinateur', 'Traitement de texte', 'Tableur : formules et graphiques', 'Logiciel de comptabilité', 'Messagerie professionnelle'],
  COM: ['Communiquer oralement en milieu de travail', 'Rédiger des documents professionnels', 'Accueillir et conseiller un client', 'Préparer sa recherche d’emploi'],
  'MATH-APP': ['Effectuer des calculs professionnels', 'Mesurer et convertir des grandeurs', 'Calculer proportions et pourcentages', 'Calculer surfaces et volumes'],
  HSE: ['Identifier les risques professionnels', 'Utiliser les équipements de protection', 'Porter les premiers secours', 'Gérer les déchets de l’atelier'],
  ENTR: ['Développer l’esprit d’entreprise', 'Réaliser une étude de marché simple', 'Calculer un coût de revient', 'Rédiger un plan d’affaires'],
  'INST-ELB': ['Choisir l’outillage et l’appareillage', 'Réaliser un circuit d’éclairage', 'Installer prises et protections', 'Réaliser une mise à la terre', 'Câbler un tableau électrique'],
  SCHEMA: ['Reconnaître les symboles normalisés', 'Lire un schéma électrique', 'Lire un plan architectural', 'Réaliser un schéma d’installation'],
  MOTEUR: ['Organiser son poste à l’atelier', 'Expliquer le moteur à quatre temps', 'Contrôler la distribution', 'Entretenir lubrification et refroidissement', 'Effectuer une mise au point'],
  'ELEC-AUTO': ['Contrôler la batterie et la charge', 'Réparer un démarreur', 'Dépanner éclairage et signalisation', 'Établir un diagnostic électrique'],
  PATRON: ['Prendre des mesures', 'Tracer le patron de base de la jupe', 'Tracer le patron de base du corsage', 'Transformer un patron', 'Placer et couper le tissu'],
  ASSEMB: ['Utiliser la machine à coudre', 'Réaliser les coutures de base', 'Poser une fermeture', 'Monter une jupe', 'Réaliser finitions et repassage'],
};

const ACTIVITES_COURS = [
  'Cours et exemples au tableau', 'Exercices d’application', 'Correction des exercices de maison',
  'Travail en groupes puis mise en commun', 'Interrogation écrite de 15 minutes', 'Synthèse et prise de notes',
];
const ACTIVITES_ATELIER = [
  'Démonstration à l’atelier', 'Travaux pratiques en binômes', 'Réalisation guidée pas à pas',
  'Contrôle des réalisations et remédiation', 'Entretien et rangement du poste de travail',
];
const TRAVAUX = [
  'Exercices 1 à 4 du manuel', 'Apprendre la leçon', 'Préparer l’exposé de la semaine prochaine',
  'Terminer le schéma commencé en classe', 'Réviser pour l’interrogation', 'Rédiger le compte rendu du TP',
];
const COMMENTAIRES_A_REVOIR = [
  'Ajoutez les travaux pratiques prévus à l’atelier et précisez les compétences visées.',
  'Le volume horaire de la première séquence est trop faible : revoyez la répartition.',
  'Précisez les semaines de début : la progression doit suivre le calendrier des compositions.',
];
const MINUTES_RETARD = [5, 10, 10, 15, 15, 20, 30];
const MOTIFS_JUSTIFICATION = [
  ['MALADIE', 'Certificat du CSPS'], ['MALADIE', 'Paludisme, mot du père'],
  ['FAMILLE', 'Funérailles au village'], ['FAMILLE', 'Mot de la mère'], ['AUTRE', 'Pièce d’état civil à établir'],
];

/** Créneaux de la semaine (1 = lundi) : trois cours de deux heures par jour. */
const CRENEAUX = [];
for (let jour = 1; jour <= 5; jour++) {
  for (const [debut, fin] of [['07:00', '09:00'], ['10:00', '12:00'], ['15:00', '17:00']]) {
    CRENEAUX.push({ jour, debut, fin, cle: `${jour}|${debut}` });
  }
}

/**
 * Les trois établissements. Matières d'une classe : « CODE:coefficient:heures par semaine[:durée totale] ».
 * frais(c) reçoit le contexte (dates, filières et classes créées) et rend les frais de l'année.
 */
const ECOLES = [
  {
    n: 1,
    code: 'lt-batisseurs',
    nom: 'Lycée technique Les Bâtisseurs',
    profil: 'TECHNIQUE',
    chefTravaux: true,
    bourses: [0.15, 0.1],
    partCantine: 0.3,
    vacataires: ['ANG', 'ORG'],
    retardataire: 'FR',
    exclusion: true,
    filieres: [
      { code: 'F3', libelle: 'Électrotechnique', cycle: 'Second cycle', diplome: 'BAC F3', classe: '2nde F3', niveau: '2nde', age: 16, filles: 0.2,
        matieres: 'MATH:4:4 FR:2:4 ANG:1:2 PC:3:4 ELEC:5:6 DESSIN-EL:3:2 TP-ELEC:4:4' },
      { code: 'F4', libelle: 'Génie civil', cycle: 'Second cycle', diplome: 'BAC F4', classe: '1re F4', niveau: '1re', age: 17, filles: 0.15,
        matieres: 'MATH:4:4 FR:2:4 ANG:1:2 PC:3:4 RDM:4:4 DESSIN-BAT:3:4 TP-BAT:4:4' },
      { code: 'G2', libelle: 'Techniques quantitatives de gestion', cycle: 'Second cycle', diplome: 'BAC G2', classe: 'Tle G2', niveau: 'Tle', age: 18, filles: 0.55,
        matieres: 'MATH:3:4 FR:2:4 ANG:2:2 ECO:3:4 COMPTA:5:6 ORG:2:2 INFO-G:2:4' },
    ],
    organismes: [['État burkinabè', 'ETAT'], ['Commune de Ouagadougou', 'COLLECTIVITE']],
    fraisPrincipal: 'Scolarité',
    frais: (c) => [
      { libelle: 'Inscription', montant: 10000, couvertParBourse: false },
      { libelle: 'Scolarité', montant: 90000, tranches: tranches(c, 90000, 3) },
      { libelle: 'Frais d’atelier', montant: 15000, portee: 'FILIERES', filieres: [c.filieres.F3, c.filieres.F4] },
      { libelle: 'Cantine', montant: 25000, obligatoire: false, couvertParBourse: false, cantine: true },
    ],
  },
  {
    n: 2,
    code: 'cfp-atelier-faso',
    nom: 'Centre de formation professionnelle L’Atelier du Faso',
    profil: 'PROFESSIONNEL',
    chefTravaux: true,
    bourses: [0.35, 0.15],
    partCantine: 0,
    vacataires: ['ENTR', 'HSE'],
    retardataire: 'ENTR',
    exclusion: false,
    filieres: [
      { code: 'ELB', libelle: 'Électricité du bâtiment', cycle: 'CAP', diplome: 'CAP Électricité', classe: 'CAP1 ELB', niveau: 'CAP 1', age: 16, filles: 0.1,
        matieres: 'COM:1:2:60 MATH-APP:1:2:60 HSE:1:2:40 ENTR:1:2:40 INST-ELB:3:8:240 SCHEMA:2:4:120' },
      { code: 'MAUTO', libelle: 'Mécanique automobile', cycle: 'CAP', diplome: 'CAP Mécanique auto', classe: 'CAP1 MAUTO', niveau: 'CAP 1', age: 16, filles: 0.08,
        matieres: 'COM:1:2:60 MATH-APP:1:2:60 HSE:1:2:40 ENTR:1:2:40 MOTEUR:3:8:240 ELEC-AUTO:2:4:120' },
      { code: 'HAB', libelle: 'Habillement', cycle: 'BEP', diplome: 'BEP Habillement', classe: 'BEP1 HAB', niveau: 'BEP 1', age: 17, filles: 0.85,
        matieres: 'COM:1:2:60 MATH-APP:1:2:60 HSE:1:2:40 ENTR:1:2:40 PATRON:3:6:180 ASSEMB:3:6:180' },
    ],
    organismes: [['État burkinabè', 'ETAT'], ['Fondation Jeunesse et Métiers', 'ONG']],
    fraisPrincipal: 'Frais de formation',
    frais: (c) => [
      { libelle: 'Inscription', montant: 5000, couvertParBourse: false },
      { libelle: 'Frais de formation', montant: 60000, tranches: tranches(c, 60000, 2) },
      { libelle: 'Kit de matière d’œuvre', montant: 20000, portee: 'CLASSES', classes: [c.classes['CAP1 ELB'], c.classes['CAP1 MAUTO']] },
    ],
  },
  {
    n: 3,
    code: 'lp-horizon',
    nom: 'Lycée privé Horizon',
    profil: 'GENERAL',
    chefTravaux: false,
    bourses: [0.05, 0.05],
    partCantine: 0.35,
    vacataires: ['ESP', 'PHILO'],
    retardataire: 'HG',
    exclusion: true,
    /** L'enseignant d'informatique de gestion du lycée technique vient aussi enseigner l'informatique ici. */
    partage: { matiere: 'INFO', ecole: 1, depuis: 'INFO-G' },
    filieres: [
      { code: 'A4', libelle: 'Lettres et langues', cycle: 'Second cycle', diplome: 'BAC A4', classe: '2nde A4', niveau: '2nde', age: 16, filles: 0.6,
        matieres: 'FR:4:6 ANG:3:4 HG:3:4 MATH:2:4 PC:1:2 SVT:1:2 INFO:1:2 ESP:2:2' },
      { code: 'C', libelle: 'Mathématiques et sciences physiques', cycle: 'Second cycle', diplome: 'BAC C', classe: '1re C', niveau: '1re', age: 17, filles: 0.3,
        matieres: 'MATH:5:6 PC:5:6 FR:3:4 ANG:2:2 HG:2:2 SVT:2:2 INFO:1:2' },
      { code: 'D', libelle: 'Mathématiques et sciences de la nature', cycle: 'Second cycle', diplome: 'BAC D', classe: 'Tle D', niveau: 'Tle', age: 18, filles: 0.45,
        matieres: 'SVT:5:6 MATH:4:4 PC:4:4 PHILO:2:4 FR:2:2 ANG:2:2 HG:2:2 INFO:1:2' },
    ],
    organismes: [['État burkinabè', 'ETAT']],
    fraisPrincipal: 'Scolarité',
    frais: (c) => [
      { libelle: 'Inscription', montant: 15000, couvertParBourse: false },
      { libelle: 'Scolarité', montant: 120000, tranches: tranches(c, 120000, 3) },
      { libelle: 'Cotisation APE', montant: 5000, couvertParBourse: false },
      { libelle: 'Cantine', montant: 30000, obligatoire: false, couvertParBourse: false, cantine: true },
    ],
  },
];

const PERSONNEL = [
  { role: 'CENSEUR', sexe: 'M', libelle: 'Censeur' },
  { role: 'CHEF_TRAVAUX', sexe: 'M', libelle: 'Chef des travaux' },
  { role: 'SECRETARIAT', sexe: 'F', libelle: 'Secrétariat' },
  { role: 'INTENDANT', sexe: 'M', libelle: 'Intendant' },
  { role: 'SURVEILLANT', sexe: 'M', libelle: 'Surveillant' },
];

// ===================================================================== outils

function borne(x, min, max) {
  return Number.isFinite(x) ? Math.min(max, Math.max(min, Math.round(x))) : min;
}

function hasard(graine) {
  let etat = graine >>> 0 || 1;
  return () => {
    etat = (etat * 1664525 + 1013904223) >>> 0;
    return etat / 4294967296;
  };
}

function graineDe(texte) {
  return createHash('sha256').update(texte).digest().readUInt32BE(0);
}

/** Tirage reproductible propre à un sujet (élève, séance…). */
function tirage(texte) {
  return hasard(graineDe(texte));
}

/** UUID stable calculé à partir d'un texte (identifiants choisis par l'appareil, idempotence). */
function uuidStable(texte) {
  const h = createHash('sha256').update(texte).digest('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-4${h.slice(13, 16)}-a${h.slice(17, 20)}-${h.slice(20, 32)}`;
}

function normale(t, moyenne, ecart) {
  const u = Math.max(t(), 1e-9);
  return moyenne + ecart * Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * t());
}

function choisir(t, liste) {
  return liste[Math.floor(t() * liste.length)];
}

function iso(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function jourDe(isoDate) {
  const [a, m, j] = isoDate.split('-').map(Number);
  return new Date(a, m - 1, j);
}

function ajouterJours(isoDate, n) {
  const d = jourDe(isoDate);
  d.setDate(d.getDate() + n);
  return iso(d);
}

function joursEntre(a, b) {
  return Math.round((jourDe(b) - jourDe(a)) / 86400000);
}

/** Jour ouvré (lundi à vendredi) le plus proche à partir de `isoDate`, en avançant (sens 1) ou en reculant (-1). */
function jourOuvre(isoDate, sens = 1) {
  let d = isoDate;
  while ([0, 6].includes(jourDe(d).getDay())) d = ajouterJours(d, sens);
  return d;
}

function arrondi500(x) {
  return Math.round(x / 500) * 500;
}

function fcfa(n) {
  return `${Math.round(n).toLocaleString('fr-FR').replace(/ | /g, ' ')} FCFA`;
}

function arreter(message) {
  console.error(`\n✘ ${message}\n`);
  process.exit(1);
}

function titre(texte) {
  console.log(`\n━━ ${texte}`);
}

function etape(texte) {
  process.stdout.write(`  • ${texte}… `);
}

function ok(texte = 'ok') {
  console.log(texte);
}

// ---------------------------------------------------------------- dates de l'année

const AUJOURDHUI = iso(new Date());
const MAINTENANT = new Date().toTimeString().slice(0, 5);
const AN_DEBUT = new Date().getMonth() >= 7 ? new Date().getFullYear() : new Date().getFullYear() - 1;
/** Rentrée : le premier lundi après la date d'il y a SEMAINES semaines. */
const RENTREE = (() => {
  let d = ajouterJours(AUJOURDHUI, -7 * SEMAINES);
  while (jourDe(d).getDay() !== 1) d = ajouterJours(d, 1);
  return d;
})();
const FIN_ANNEE = `${AN_DEBUT + 1}-07-31`;
const LIBELLE_ANNEE = `${AN_DEBUT}-${AN_DEBUT + 1}`;

/** Tranches à parts égales : la première est échue depuis 5 jours, les suivantes s'étalent jusqu'à la fin de l'année. */
function tranches(_c, montant, n) {
  const premiere = ajouterJours(AUJOURDHUI, -5);
  const ecart = Math.floor(joursEntre(premiere, ajouterJours(FIN_ANNEE, -30)) / Math.max(1, n - 1));
  const part = arrondi500(montant / n);
  return Array.from({ length: n }, (_, i) => ({
    dateLimite: ajouterJours(premiere, i * ecart),
    montant: i === n - 1 ? montant - part * (n - 1) : part,
  }));
}

// ---------------------------------------------------------------- avertissements regroupés

const AVERTISSEMENTS = new Map();

function avertir(etapeLibelle, e) {
  const cle = `${etapeLibelle} : ${e?.message ?? e}`;
  AVERTISSEMENTS.set(cle, (AVERTISSEMENTS.get(cle) ?? 0) + 1);
}

/** Étape non essentielle : une erreur est notée (et regroupée) sans arrêter le script. */
async function tenter(etapeLibelle, fn) {
  try {
    return await fn();
  } catch (e) {
    if (e.fatal) throw e;
    avertir(etapeLibelle, e);
    return undefined;
  }
}

async function parallele(liste, fn, n = PARALLELE) {
  const resultats = new Array(liste.length);
  let suivant = 0;
  const travailleurs = Array.from({ length: Math.min(n, liste.length) }, async () => {
    while (suivant < liste.length) {
      const k = suivant++;
      resultats[k] = await fn(liste[k], k);
    }
  });
  await Promise.all(travailleurs);
  return resultats;
}

// ---------------------------------------------------------------- API

class ErreurApi extends Error {
  constructor(message, { statut = 0, code = null, fatal = false } = {}) {
    super(message);
    this.statut = statut;
    this.code = code;
    this.fatal = fatal;
  }
}

/** Session d'un compte dans un établissement : se reconnecte seule quand le jeton expire (15 minutes). */
class Session {
  constructor(telephone, motDePasse, etablissementId) {
    this.telephone = telephone;
    this.motDePasse = motDePasse;
    this.etablissementId = etablissementId;
    this.valeur = null;
    this.expire = 0;
    this.enCours = null;
  }

  async jeton() {
    if (!this.valeur || Date.now() > this.expire) {
      this.enCours ??= this.ouvrir().finally(() => {
        this.enCours = null;
      });
      await this.enCours;
    }
    return this.valeur;
  }

  oublier() {
    this.valeur = null;
  }

  async ouvrir() {
    const r = await api('POST', '/auth/connexion', { telephone: this.telephone, motDePasse: this.motDePasse });
    let s = r;
    if (r.selectionRequise) {
      const choix = r.etablissements.find((e) => e.id === this.etablissementId) ?? r.etablissements[0];
      s = await api('POST', '/auth/etablissement', { etablissementId: choix.id }, r.jetonSelection);
    }
    this.valeur = s.jetonAcces;
    this.expire = Date.now() + Math.max(60, (s.expireDansSecondes ?? 900) - 90) * 1000;
  }
}

async function envoyer(methode, chemin, corps, jeton) {
  try {
    return await fetch(API + chemin, {
      method: methode,
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        ...(jeton ? { Authorization: `Bearer ${jeton}` } : {}),
      },
      body: corps === undefined ? undefined : JSON.stringify(corps),
    });
  } catch (e) {
    throw new ErreurApi(`API injoignable à ${API} (${e.cause?.code ?? e.message}). Lancez-la avec le profil dev.`, { fatal: true });
  }
}

/** Appel de l'API ; `qui` est une Session ou un jeton. Erreur : ErreurApi avec le message du serveur. */
async function api(methode, chemin, corps, qui) {
  const jeton = qui instanceof Session ? await qui.jeton() : qui;
  let reponse = await envoyer(methode, chemin, corps, jeton);
  if (reponse.status === 401 && qui instanceof Session) {
    qui.oublier();
    reponse = await envoyer(methode, chemin, corps, await qui.jeton());
  }
  const texte = await reponse.text();
  let donnees = null;
  try {
    donnees = texte ? JSON.parse(texte) : null;
  } catch {
    donnees = texte;
  }
  if (!reponse.ok) {
    const detail = donnees?.detail ?? donnees?.title ?? texte;
    const code = donnees?.code ?? null;
    const champs = donnees?.erreurs ? ` ${JSON.stringify(donnees.erreurs)}` : '';
    throw new ErreurApi(`${methode} ${chemin.replace(/[0-9a-f-]{36}/g, '…')} → ${reponse.status}${code ? ` [${code}]` : ''} : ${detail}${champs}`, {
      statut: reponse.status,
      code,
    });
  }
  return donnees;
}

/** Remplace le mot de passe provisoire par le mot de passe de démonstration ; rend la session du compte. */
async function compte(telephone, motDePasseTemporaire, etablissementId) {
  if (motDePasseTemporaire) {
    const r = await api('POST', '/auth/connexion', { telephone, motDePasse: motDePasseTemporaire });
    let s = r;
    if (r.selectionRequise) {
      s = await api('POST', '/auth/etablissement', { etablissementId: etablissementId ?? r.etablissements[0].id }, r.jetonSelection);
    }
    if (s.doitChangerMotDePasse) {
      await api('POST', '/moi/mot-de-passe', { motDePasseActuel: motDePasseTemporaire, nouveauMotDePasse: MOT_DE_PASSE }, s.jetonAcces);
    }
  }
  const session = new Session(telephone, MOT_DE_PASSE, etablissementId);
  await session.jeton();
  return session;
}

// ===================================================================== comptes affichés à la fin

const COMPTES = [];
function noterCompte(ecole, role, personne, telephone, remarque = '') {
  const ligne = { ecole: ecole.nom, role, nom: `${personne.nom} ${personne.prenoms}`, telephone, remarque };
  COMPTES.push(ligne);
  return ligne;
}

/** État global partagé entre établissements. */
const GLOBAL = {
  /** Créneaux déjà pris par chaque enseignant (téléphone|créneau), tous établissements confondus. */
  occupationProfs: new Set(),
  /** Famille du lycée technique qui a aussi un enfant au lycée Horizon. */
  familleCommune: null,
  ecoles: [],
};

// ===================================================================== scénario

async function principal() {
  const debutChrono = Date.now();
  console.log(`\nTest grandeur nature du MVP — API ${API}`);
  console.log(`Année ${LIBELLE_ANNEE}, rentrée le ${RENTREE} (${SEMAINES} semaines d'activité), ${ELEVES_PAR_CLASSE} élèves par classe.`);

  titre('Plateforme');
  etape('Connexion du super administrateur');
  const sa = await superAdmin();
  ok();

  etape('Base vide ?');
  const existants = await api('GET', '/plateforme/etablissements', undefined, sa);
  const deja = (Array.isArray(existants) ? existants : existants?.contenu ?? []).filter((e) => ECOLES.some((x) => x.code === e.code));
  if (deja.length) {
    arreter(
      `Les établissements de démonstration existent déjà (${deja.map((e) => e.code).join(', ')}).\n` +
        '  Pour repartir d’une base vide : scripts/demo/mvp-grandeur-nature.sh (efface la base locale).',
    );
  }
  ok();

  for (const def of ECOLES) {
    await peuplerEcole(def, sa);
  }

  bilanFinal(debutChrono);
}

async function superAdmin() {
  let r;
  try {
    r = await api('POST', '/auth/connexion', { telephone: SUPER_ADMIN_TELEPHONE, motDePasse: SUPER_ADMIN_MOT_DE_PASSE });
  } catch (e) {
    if (e.statut !== 401) throw e;
    // Base déjà utilisée : le mot de passe provisoire a peut-être été remplacé par ce script
    await api('POST', '/auth/connexion', { telephone: SUPER_ADMIN_TELEPHONE, motDePasse: SUPER_ADMIN_NOUVEAU }).catch(() => {
      arreter(
        'Connexion du super administrateur impossible.\n' +
          '  Indiquez son mot de passe : SUPER_ADMIN_MOT_DE_PASSE=... node scripts/demo/peupler-mvp.mjs',
      );
    });
    return new Session(SUPER_ADMIN_TELEPHONE, SUPER_ADMIN_NOUVEAU);
  }
  if (r.doitChangerMotDePasse) {
    await api('POST', '/moi/mot-de-passe', { motDePasseActuel: SUPER_ADMIN_MOT_DE_PASSE, nouveauMotDePasse: SUPER_ADMIN_NOUVEAU }, r.jetonAcces);
    return new Session(SUPER_ADMIN_TELEPHONE, SUPER_ADMIN_NOUVEAU);
  }
  return new Session(SUPER_ADMIN_TELEPHONE, SUPER_ADMIN_MOT_DE_PASSE);
}

// ---------------------------------------------------------------- un établissement

async function peuplerEcole(def, sa) {
  const e = {
    ...def,
    definition: def,
    t: tirage(`ecole|${def.n}`),
    tel: {
      personnel: (k) => `6${def.n}0000${String(k).padStart(2, '0')}`,
      enseignant: (k) => `6${def.n}1000${String(k).padStart(2, '0')}`,
      parent: (k) => `6${def.n}2${String(k).padStart(5, '0')}`,
    },
    compteurParents: 0,
    classes: [],
    matieres: {},
    enseignants: {},
    familles: [],
    eleves: [],
    seances: [],
    absences: new Map(),
    retards: [],
    stats: { appels: 0, absences: 0, retards: 0, seancesCahier: 0, fiches: {}, paiements: 0, encaisse: 0, mm: 0, notes: 0, competences: 0 },
  };
  GLOBAL.ecoles.push(e);
  titre(`${e.nom} (${e.code})`);

  await creerEtablissement(e, sa);
  await parametrerAnnee(e);
  await recruterPersonnel(e);
  await engagerEnseignants(e);
  emploiDuTemps(e);
  await preparerScolarite(e);
  await inscrireEleves(e);
  await encaisser(e);
  await progressions(e);
  await appelsEtCahier(e);
  await evaluer(e);
  await vieScolaire(e);
  await espacesParents(e);
  await relancesEtStatistiques(e);
}

async function creerEtablissement(e, sa) {
  e.admin = { nom: choisir(e.t, NOMS), prenoms: choisir(e.t, PRENOMS_M), telephone: e.tel.personnel(1) };
  etape('Création par le super administrateur');
  const creation = await api('POST', '/plateforme/etablissements', {
    code: e.code,
    nom: e.nom,
    telephoneAdministrateur: e.admin.telephone,
    nomAdministrateur: e.admin.nom,
    prenomsAdministrateur: e.admin.prenoms,
  }, sa);
  e.id = creation.etablissement.id;
  e.sAdmin = await compte(e.admin.telephone, creation.motDePasseTemporaire, e.id);
  noterCompte(e, 'Administrateur', e.admin, e.admin.telephone, 'paramétrage, élèves, scolarité, statistiques');
  ok(`administrateur ${e.admin.prenoms} ${e.admin.nom}`);
}

async function parametrerAnnee(e) {
  const adm = e.sAdmin;
  etape('Profils, année, filières, matières');
  const profils = await api('POST', '/profils/initialisation', undefined, adm);
  e.profilId = profils.find((p) => p.code === e.profil)?.id;
  if (!e.profilId) throw new ErreurApi(`Profil ${e.profil} introuvable`, { fatal: true });
  e.annee = await api('POST', '/annees', { libelle: LIBELLE_ANNEE, debut: RENTREE, fin: FIN_ANNEE }, adm);

  const codes = new Set(e.filieres.flatMap((f) => f.matieres.split(' ').map((m) => m.split(':')[0])));
  await parallele([...codes], async (code) => {
    const [libelle, type] = CATALOGUE[code];
    const m = await api('POST', '/matieres', { code, libelle, type }, adm);
    e.matieres[code] = { id: m.id, code, libelle, type };
  });
  const filieres = {};
  for (const f of e.filieres) {
    const cree = await api('POST', '/filieres', { code: f.code, libelle: f.libelle, cycle: f.cycle, diplomeVise: f.diplome, profilId: e.profilId }, adm);
    filieres[f.code] = cree.id;
  }
  e.filiereIds = filieres;
  ok(`${codes.size} matières, ${e.filieres.length} filières`);

  etape('Classes et programmes');
  for (const f of e.filieres) {
    const c = await api('POST', `/annees/${e.annee.id}/classes`, { filiereId: filieres[f.code], code: f.classe, niveau: f.niveau, effectifMax: 65 }, adm);
    const classe = { id: c.id, code: f.classe, niveau: f.niveau, filiere: f, matieres: [] };
    for (const spec of f.matieres.split(' ')) {
      const [code, coef, hebdo, total] = spec.split(':');
      const m = e.matieres[code];
      await api('PUT', `/classes/${c.id}/matieres/${m.id}`, {
        coefficient: Number(coef),
        groupe: e.profil === 'TECHNIQUE'
          ? (m.type === 'GENERALE' ? 'Enseignement général' : 'Enseignement technique')
          : e.profil === 'PROFESSIONNEL' ? (['COM', 'MATH-APP', 'HSE', 'ENTR'].includes(code) ? 'Modules généraux' : 'Modules professionnels') : null,
        volumeHebdo: Number(hebdo),
        ...(total ? { volumeTotal: Number(total) } : {}),
      }, adm);
      classe.matieres.push({ ...m, coefficient: Number(coef), volumeHebdo: Number(hebdo), volumeTotal: total ? Number(total) : null, creneaux: [] });
    }
    e.classes.push(classe);
  }
  e.classeIds = Object.fromEntries(e.classes.map((c) => [c.code, c.id]));
  ok(e.classes.map((c) => `${c.code} (${c.matieres.length} matières)`).join(', '));

  etape('Périodes et ouverture de l’année');
  if (e.profil === 'PROFESSIONNEL') {
    // Découpage en modules saisis un à un ; le Module 1 vient de se terminer (relevés publiés plus loin)
    const finM1 = ajouterJours(AUJOURDHUI, -3);
    const finM2 = ajouterJours(AUJOURDHUI, Math.floor(joursEntre(AUJOURDHUI, FIN_ANNEE) / 2));
    for (const [libelle, debut, fin] of [['Module 1', RENTREE, finM1], ['Module 2', ajouterJours(finM1, 1), finM2], ['Module 3', ajouterJours(finM2, 1), FIN_ANNEE]]) {
      await api('POST', `/annees/${e.annee.id}/periodes`, { profilId: e.profilId, libelle, debut, fin }, adm);
    }
  } else {
    await api('POST', `/annees/${e.annee.id}/periodes/generation`, { profilId: e.profilId }, adm);
  }
  e.periodes = await api('GET', `/annees/${e.annee.id}/periodes`, undefined, adm);
  await api('POST', `/annees/${e.annee.id}/ouverture`, undefined, adm);
  ok(e.periodes.map((p) => `${p.libelle} ${p.debut} → ${p.fin}`).join(' ; '));
}

function personne(t, sexe) {
  return { nom: choisir(t, NOMS), prenoms: choisir(t, sexe === 'F' ? PRENOMS_F : PRENOMS_M), sexe };
}

async function recruterPersonnel(e) {
  etape('Personnel administratif');
  e.personnel = {};
  let k = 2;
  for (const p of PERSONNEL) {
    if (p.role === 'CHEF_TRAVAUX' && !e.chefTravaux) {
      k++;
      continue;
    }
    const qui = { ...personne(e.t, p.sexe), telephone: e.tel.personnel(k++) };
    const r = await api('POST', '/membres', { telephone: qui.telephone, nom: qui.nom, prenoms: qui.prenoms, role: p.role }, e.sAdmin);
    qui.session = await compte(qui.telephone, r.motDePasseTemporaire, e.id);
    e.personnel[p.role] = qui;
    const roles = {
      CENSEUR: e.chefTravaux ? 'vise les progressions des matières générales, bulletins' : 'vise toutes les progressions, bulletins',
      CHEF_TRAVAUX: 'vise les progressions techniques et pratiques',
      SECRETARIAT: 'dossiers des élèves',
      INTENDANT: 'guichet, journal de caisse, relances',
      SURVEILLANT: 'absences du jour, justificatifs, convocations',
    };
    noterCompte(e, p.libelle, qui, qui.telephone, roles[p.role]);
  }
  ok(Object.keys(e.personnel).join(', '));
}

async function engagerEnseignants(e) {
  etape('Enseignants et affectations');
  const codes = [...new Set(e.classes.flatMap((c) => c.matieres.map((m) => m.code)))];
  let k = 1;
  for (const code of codes) {
    if (e.partage?.matiere === code) continue;
    const t = tirage(`prof|${e.n}|${code}`);
    const qui = { ...personne(t, t() < 0.3 ? 'F' : 'M'), telephone: e.tel.enseignant(k++), matieres: [code] };
    const vacataire = e.vacataires.includes(code);
    const r = await api('POST', '/enseignants', {
      telephone: qui.telephone,
      nom: qui.nom,
      prenoms: qui.prenoms,
      sexe: qui.sexe,
      specialite: CATALOGUE[code][0],
      type: vacataire ? 'VACATAIRE' : 'TITULAIRE',
      debut: RENTREE,
      ...(vacataire ? { tauxHoraire: 3500 } : {}),
    }, e.sAdmin);
    qui.engagementId = r.enseignant.engagementId;
    qui.type = vacataire ? 'vacataire' : 'titulaire';
    qui.session = await compte(qui.telephone, r.motDePasseTemporaire, e.id);
    e.enseignants[code] = qui;
  }

  if (e.partage) {
    // Enseignant déjà titulaire ailleurs : il est invité comme vacataire et accepte l'invitation
    const source = GLOBAL.ecoles.find((x) => x.n === e.partage.ecole)?.enseignants[e.partage.depuis];
    if (source) {
      const r = await api('POST', '/enseignants', { telephone: source.telephone, type: 'VACATAIRE', debut: RENTREE, tauxHoraire: 4000 }, e.sAdmin);
      await api('POST', `/moi/invitations/${r.enseignant.engagementId}/acceptation`, undefined, source.session);
      e.enseignants[e.partage.matiere] = {
        ...source,
        matieres: [e.partage.matiere],
        engagementId: r.enseignant.engagementId,
        type: 'vacataire (partagé)',
        session: new Session(source.telephone, MOT_DE_PASSE, e.id),
        partage: true,
      };
      source.partageAvec = e.nom;
      if (source.ligneCompte) source.ligneCompte.remarque += ` — enseigne aussi au ${e.nom} (choix de l’établissement à la connexion)`;
    }
  }

  let affectations = 0;
  for (const c of e.classes) {
    for (const m of c.matieres) {
      const prof = e.enseignants[m.code];
      if (!prof) continue;
      await api('PUT', `/classes/${c.id}/matieres/${m.id}/enseignant`, { engagementId: prof.engagementId }, e.sAdmin);
      m.enseignant = prof;
      affectations++;
    }
  }
  ok(`${Object.keys(e.enseignants).length} enseignants, ${affectations} affectations`);

  for (const [code, prof] of Object.entries(e.enseignants)) {
    const classes = e.classes.filter((c) => c.matieres.some((m) => m.code === code)).map((c) => c.code).join(', ');
    let remarque = `${CATALOGUE[code][0]} (${classes}), ${prof.type}`;
    if (code === e.retardataire) remarque += ' — en retard : progression incomplète, cahier peu tenu';
    if (prof.partage) remarque += ' — compte du lycée technique, même mot de passe';
    const ligne = noterCompte(e, 'Enseignant', prof, prof.telephone, remarque);
    if (!prof.partage) prof.ligneCompte = ligne;
  }
}

/** Emploi du temps : chaque matière reçoit ses créneaux de deux heures, sans conflit de classe ni d'enseignant. */
function emploiDuTemps(e) {
  etape('Emploi du temps');
  let places = 0;
  for (const [ci, classe] of e.classes.entries()) {
    const occupe = new Set();
    const besoins = classe.matieres
      .filter((m) => m.enseignant)
      .map((m) => ({ m, n: Math.max(1, Math.round(m.volumeHebdo / 2)) }))
      .sort((a, b) => b.n - a.n);
    for (const { m, n } of besoins) {
      const jours = new Set();
      for (let k = 0; k < n; k++) {
        const decalage = (ci * 4 + k * 5 + m.code.length) % CRENEAUX.length;
        const ordre = CRENEAUX.map((_, i) => CRENEAUX[(i + decalage) % CRENEAUX.length]);
        const libre = (c) => !occupe.has(c.cle) && !GLOBAL.occupationProfs.has(`${m.enseignant.telephone}|${c.cle}`);
        const choix = ordre.find((c) => libre(c) && !jours.has(c.jour)) ?? ordre.find(libre);
        if (!choix) {
          avertir('Emploi du temps', new Error(`pas de créneau libre pour ${m.code} en ${classe.code}`));
          continue;
        }
        occupe.add(choix.cle);
        GLOBAL.occupationProfs.add(`${m.enseignant.telephone}|${choix.cle}`);
        jours.add(choix.jour);
        m.creneaux.push(choix);
        places++;
      }
    }
  }
  // Séances passées depuis la rentrée (celles d'aujourd'hui seulement si elles sont terminées)
  for (let jour = RENTREE; jour <= AUJOURDHUI; jour = ajouterJours(jour, 1)) {
    const js = jourDe(jour).getDay();
    for (const classe of e.classes) {
      for (const m of classe.matieres) {
        for (const c of m.creneaux.filter((x) => x.jour === js)) {
          if (jour === AUJOURDHUI && c.fin > MAINTENANT) continue;
          e.seances.push({ classe, m, date: jour, debut: c.debut, fin: c.fin });
        }
      }
    }
  }
  ok(`${places} créneaux par semaine, ${e.seances.length} séances depuis la rentrée`);
}

// ---------------------------------------------------------------- scolarité

async function preparerScolarite(e) {
  const intendant = e.personnel.INTENDANT.session;
  etape('Frais, organismes, Mobile Money');
  const contexte = { filieres: e.filiereIds, classes: e.classeIds };
  e.frais = [];
  for (const f of e.definition.frais(contexte)) {
    const cree = await api('POST', `/annees/${e.annee.id}/frais`, {
      libelle: f.libelle,
      montant: f.montant,
      obligatoire: f.obligatoire ?? true,
      couvertParBourse: f.couvertParBourse ?? true,
      portee: f.portee ?? 'TOUTES',
      filieres: f.filieres ?? [],
      classes: f.classes ?? [],
      tranches: f.tranches ?? [],
    }, intendant);
    e.frais.push({ ...f, id: cree.id });
  }
  e.organismes = [];
  for (const [nom, type] of e.definition.organismes) {
    e.organismes.push(await api('POST', '/organismes', { nom, type, telephone: null }, intendant));
  }
  const mm = await tenter('Mobile Money', () =>
    api('PUT', '/parametres/mobile-money', {
      agregateur: 'SIMULATEUR',
      identifiantMarchand: `DEMO-${e.code.toUpperCase()}`,
      cleApi: 'demo-cle-api',
      secretWebhook: 'demo-secret-webhook',
      actif: true,
    }, e.sAdmin),
  );
  e.mobileMoney = mm !== undefined;
  ok(`${e.frais.length} frais, ${e.organismes.length} organisme(s), Mobile Money ${e.mobileMoney ? 'en simulation' : 'indisponible (profil dev ?)'}`);
}

/** Nouvelle famille (un responsable) ou famille existante (fratrie). */
function nouvelleFamille(e, t, nomEleve) {
  const pere = t() < 0.55;
  return {
    nom: nomEleve,
    prenoms: choisir(t, pere ? PRENOMS_M : PRENOMS_F),
    telephone: e.tel.parent(++e.compteurParents),
    lien: pere ? 'PERE' : t() < 0.85 ? 'MERE' : 'TUTEUR',
    enfants: [],
  };
}

async function inscrireEleves(e) {
  const adm = e.sAdmin;
  for (const [ci, classe] of e.classes.entries()) {
    etape(`Élèves de ${classe.code}`);
    const t = tirage(`eleves|${e.n}|${classe.code}`);
    const pris = new Set();
    const prevus = [];
    for (let i = 0; i < ELEVES_PAR_CLASSE; i++) {
      const fille = t() < classe.filiere.filles;
      let famille = null;
      if (e.n === 3 && ci === 0 && i === 0 && GLOBAL.familleCommune) {
        famille = GLOBAL.familleCommune; // même parent dans deux établissements
      } else if (ci > 0 && t() < 0.1) {
        const candidates = e.familles.filter((f) => f.enfants.length === 1);
        famille = candidates.length ? choisir(t, candidates) : null;
      }
      let nom = famille?.nom ?? choisir(t, NOMS);
      let prenoms;
      do {
        prenoms = choisir(t, fille ? PRENOMS_F : PRENOMS_M);
        if (pris.has(nom + prenoms) && !famille) nom = choisir(t, NOMS);
      } while (pris.has(nom + prenoms));
      pris.add(nom + prenoms);
      if (!famille) {
        famille = nouvelleFamille(e, t, nom);
      }
      const profil = t();
      const r = t();
      const bourse = r < e.bourses[0] ? 'BOURSIER' : r < e.bourses[0] + e.bourses[1] ? 'SEMI_BOURSIER' : 'NON_BOURSIER';
      const naissance = new Date(AN_DEBUT - classe.filiere.age + (t() < 0.25 ? -1 : 0), Math.floor(t() * 12), 1 + Math.floor(t() * 28));
      prevus.push({
        classe,
        famille,
        nom,
        prenoms,
        sexe: fille ? 'F' : 'M',
        dateNaissance: iso(naissance),
        lieuNaissance: choisir(t, LIEUX),
        redoublant: t() < 0.08,
        bourse,
        tauxAbsence: profil < 0.7 ? 0.02 : profil < 0.9 ? 0.07 : 0.2,
        tauxRetard: 0.005 + t() * 0.02,
        niveau: Math.min(17, Math.max(5, normale(t, 11, 2.6))),
        payeur: (() => {
          const p = t();
          return p < 0.12 ? 'COMPLET' : p < 0.57 ? 'A_JOUR' : p < 0.85 ? 'PARTIEL' : 'RETARD';
        })(),
      });
    }
    // Les familles nouvelles de la classe ont des numéros distincts : création en parallèle sans risque
    await parallele(prevus, async (p) => {
      const dossier = await api('POST', '/eleves', {
        nom: p.nom,
        prenoms: p.prenoms,
        sexe: p.sexe,
        dateNaissance: p.dateNaissance,
        lieuNaissance: p.lieuNaissance,
        responsables: [{
          nom: p.famille.nom,
          prenoms: p.famille.prenoms,
          telephone: p.famille.telephone,
          lien: p.famille.lien,
          responsableLegal: true,
          contactPrioritaire: true,
        }],
      }, adm);
      const inscription = await api('POST', '/inscriptions', {
        eleveId: dossier.eleve.id,
        classeId: p.classe.id,
        redoublant: p.redoublant,
        statutBourse: p.bourse,
      }, adm);
      p.eleveId = dossier.eleve.id;
      p.inscriptionId = inscription.id;
      p.responsableId = dossier.responsables?.[0]?.responsableId;
    });
    for (const p of prevus) {
      if (!p.famille.responsableIds) p.famille.responsableIds = {};
      p.famille.responsableIds[e.n] = p.responsableId;
      p.famille.enfants.push(p);
      if (!e.familles.includes(p.famille)) e.familles.push(p.famille);
      e.eleves.push(p);
    }
    classe.eleves = prevus;
    const b = prevus.filter((p) => p.bourse !== 'NON_BOURSIER').length;
    ok(`${prevus.length} inscrits (${prevus.filter((p) => p.sexe === 'F').length} filles, ${b} boursiers ou semi-boursiers)`);
  }
  if (e.n === 1) {
    GLOBAL.familleCommune = e.familles.find((f) => f.enfants.length === 1) ?? null;
  }
  const fratries = e.familles.filter((f) => f.enfants.filter((x) => e.eleves.includes(x)).length > 1).length;
  console.log(`    ${e.familles.length} familles, dont ${fratries} avec plusieurs enfants dans l'établissement`);
}

async function encaisser(e) {
  const intendant = e.personnel.INTENDANT.session;
  const principal = e.frais.find((f) => f.libelle === e.fraisPrincipal);
  const cantine = e.frais.find((f) => f.cantine);

  etape('Bourses, exonérations, cantine');
  let prises = 0;
  await parallele(e.eleves.filter((p) => p.bourse !== 'NON_BOURSIER'), async (p) => {
    const organisme = p.bourse === 'BOURSIER' ? e.organismes[0] : e.organismes.at(-1);
    await tenter('Prise en charge', async () => {
      await api('PUT', `/inscriptions/${p.inscriptionId}/prise-en-charge`, {
        organismeId: organisme.id,
        taux: null,
        referenceDecision: `DEC-${AN_DEBUT}-${String(++prises).padStart(3, '0')}`,
        dateDecision: RENTREE,
      }, intendant);
      p.organisme = organisme;
    });
  });
  const exoneres = e.eleves.filter((p) => p.bourse === 'NON_BOURSIER').slice(5, 8);
  for (const p of exoneres) {
    await tenter('Exonération', () =>
      api('PUT', `/inscriptions/${p.inscriptionId}/exonerations/${principal.id}`, { montant: principal.montant / 2, motif: 'Enfant du personnel' }, intendant));
  }
  let souscrits = 0;
  if (cantine) {
    await parallele(e.eleves.filter((p) => tirage(`cantine|${p.inscriptionId}`)() < e.partCantine), async (p) => {
      await tenter('Cantine', async () => {
        await api('PUT', `/inscriptions/${p.inscriptionId}/frais/${cantine.id}`, undefined, intendant);
        souscrits++;
      });
    });
  }
  ok(`${prises} prises en charge, ${exoneres.length} exonérations${cantine ? `, ${souscrits} élèves à la cantine` : ''}`);

  etape('Encaissements au guichet');
  const joursEcoules = Math.max(1, joursEntre(RENTREE, AUJOURDHUI));
  await parallele(e.eleves, async (p) => {
    await tenter('Encaissement', async () => {
      const s = await api('GET', `/inscriptions/${p.inscriptionId}/scolarite`, undefined, intendant);
      p.situation = s;
      const t = tirage(`paiement|${p.inscriptionId}`);
      const reste = s.resteFamille ?? 0;
      let montant = 0;
      if (p.payeur === 'COMPLET') montant = reste;
      else if (p.payeur === 'A_JOUR') montant = Math.min(reste, Math.max(s.retardFamille ?? 0, 0));
      else if (p.payeur === 'PARTIEL') montant = Math.min(reste, arrondi500((s.retardFamille ?? 0) * (0.3 + t() * 0.5)));
      if (montant <= 0) return;
      // En une ou deux fois, à des dates passées
      const parts = montant >= 20000 && t() < 0.5 ? [arrondi500(montant * 0.6), montant - arrondi500(montant * 0.6)] : [montant];
      let jour = ajouterJours(RENTREE, Math.floor(t() * Math.min(10, joursEcoules)));
      for (const [k, m] of parts.entries()) {
        if (m <= 0) continue;
        const moyen = t() < 0.85 ? 'ESPECES' : t() < 0.7 ? 'ORANGE_MONEY' : 'MOOV_MONEY';
        const paiement = await api('POST', `/inscriptions/${p.inscriptionId}/paiements`, {
          montant: m,
          moyen,
          payeur: 'FAMILLE',
          organismeId: null,
          referenceExterne: moyen === 'ESPECES' ? null : `${moyen === 'ORANGE_MONEY' ? 'OM' : 'MM'}${String(Math.floor(t() * 1e9)).padStart(9, '0')}`,
          deposant: `${p.famille.prenoms} ${p.famille.nom}`,
          datePaiement: jour,
          cleIdempotence: uuidStable(`guichet|${p.inscriptionId}|${k}`),
        }, intendant);
        p.paiements = [...(p.paiements ?? []), paiement];
        e.stats.paiements++;
        e.stats.encaisse += m;
        jour = [ajouterJours(jour, 7 + Math.floor(t() * 10)), AUJOURDHUI].sort()[0];
      }
    });
  });
  ok(`${e.stats.paiements} paiements, ${fcfa(e.stats.encaisse)}`);

  etape('Annulation d’un reçu saisi deux fois');
  const candidat = e.eleves.find((p) => p.payeur === 'PARTIEL' && p.paiements?.length);
  if (candidat) {
    const r = await tenter('Annulation', async () => {
      const paye = candidat.paiements.reduce((a, x) => a + x.montant, 0);
      const resteActuel = (candidat.situation.resteFamille ?? 0) - paye;
      if (resteActuel < 500) throw new Error('plus rien à payer pour le doublon');
      const doublon = await api('POST', `/inscriptions/${candidat.inscriptionId}/paiements`, {
        montant: Math.min(candidat.paiements[0].montant, resteActuel),
        moyen: 'ESPECES',
        payeur: 'FAMILLE',
        deposant: `${candidat.famille.prenoms} ${candidat.famille.nom}`,
        datePaiement: AUJOURDHUI,
        cleIdempotence: uuidStable(`doublon|${candidat.inscriptionId}`),
      }, intendant);
      await api('POST', `/paiements/${doublon.id}/annulation`, { motif: 'Reçu saisi deux fois par erreur' }, intendant);
      return `${candidat.prenoms} ${candidat.nom} (${candidat.classe.code})`;
    });
    ok(r ?? 'non faite (voir les avertissements)');
  } else {
    ok('aucun élève qui convienne');
  }

  etape('Virements des organismes');
  let virements = 0;
  const pris = e.eleves.filter((p) => p.organisme && (p.situation?.resteOrganisme ?? 0) > 0);
  await parallele(pris.filter((_, i) => i % 2 === 0), async (p) => {
    await tenter('Virement organisme', async () => {
      await api('POST', `/inscriptions/${p.inscriptionId}/paiements`, {
        montant: p.situation.retardOrganisme > 0 ? p.situation.retardOrganisme : p.situation.resteOrganisme,
        moyen: 'VIREMENT',
        payeur: 'ORGANISME',
        organismeId: p.organisme.id,
        referenceExterne: `VIR-${p.organisme.type}-${AN_DEBUT}-${String(virements + 1).padStart(3, '0')}`,
        deposant: p.organisme.nom,
        datePaiement: jourOuvre(ajouterJours(AUJOURDHUI, -3), -1),
        cleIdempotence: uuidStable(`virement|${p.inscriptionId}`),
      }, intendant);
      virements++;
    });
  });
  ok(`${virements} élèves boursiers réglés par leur organisme, ${pris.length - virements} en attente`);
}

// ---------------------------------------------------------------- progression et cahier de textes

function superviseur(e, m) {
  return e.chefTravaux && m.type !== 'GENERALE' ? e.personnel.CHEF_TRAVAUX : e.personnel.CENSEUR;
}

function sequencesFiche(m) {
  const titres = SEQUENCES[m.code] ?? ['Séquence 1', 'Séquence 2', 'Séquence 3', 'Séquence 4'];
  const total = m.volumeTotal ?? m.volumeHebdo * 28;
  const poids = titres.map((_, i) => i + 1.5);
  const somme = poids.reduce((a, b) => a + b, 0);
  let cumul = 0;
  const lundi = RENTREE;
  return titres.map((titre, i) => {
    const heures = Math.max(2, Math.round((total * poids[i]) / somme));
    const semaine = Math.floor(cumul / m.volumeHebdo);
    cumul += heures;
    const debut = [ajouterJours(lundi, 7 * semaine), FIN_ANNEE].sort()[0];
    return {
      titre,
      contenu: `${titre} : notions essentielles, exercices d’application et évaluation formative.`,
      competences: m.type === 'MODULE_COMPETENCES' ? (i < 3 ? `C${i + 1}` : 'C1, C2, C3') : null,
      heuresPrevues: heures,
      semaineDebut: debut,
    };
  });
}

async function progressions(e) {
  etape('Fiches de progression');
  const stats = { aucune: 0, BROUILLON: 0, SOUMISE: 0, VISEE: 0, A_REVOIR: 0 };
  const travaux = e.classes.flatMap((c, ci) => c.matieres.filter((m) => m.enseignant).map((m) => ({ c, ci, m })));
  await parallele(travaux, async ({ c, ci, m }) => {
    await tenter('Progression', async () => {
      const prof = m.enseignant;
      const retard = m.code === e.retardataire;
      const indexProf = e.classes.filter((x, i) => i <= ci && x.matieres.some((y) => y.code === m.code)).length - 1;
      if (retard && indexProf === 0) {
        stats.aucune++;
        return;
      }
      const sequences = sequencesFiche(m);
      const base = `/classes/${c.id}/matieres/${m.id}/progression`;
      await api('PUT', base, { sequences }, prof.session);
      m.fiche = sequences;
      if (retard && indexProf === 1) {
        stats.BROUILLON++;
        return;
      }
      await api('POST', `${base}/soumission`, undefined, prof.session);
      const t = tirage(`visa|${c.id}|${m.id}`)();
      if (t < 0.68) {
        await api('POST', `${base}/visa`, { accepte: true, commentaire: null }, superviseur(e, m).session);
        stats.VISEE++;
      } else if (t < 0.85) {
        await api('POST', `${base}/visa`, { accepte: false, commentaire: choisir(tirage(`com|${c.id}|${m.id}`), COMMENTAIRES_A_REVOIR) }, superviseur(e, m).session);
        stats.A_REVOIR++;
      } else {
        stats.SOUMISE++;
      }
    });
  });
  e.stats.fiches = stats;
  ok(`${stats.VISEE} visées, ${stats.A_REVOIR} à revoir, ${stats.SOUMISE} en attente de visa, ${stats.BROUILLON} en brouillon, ${stats.aucune} non commencée(s)`);
}

async function appelsEtCahier(e) {
  etape('Appels de chaque séance');
  const parProf = new Map();
  for (const s of e.seances) {
    const t = tirage(`appel|${s.classe.id}|${s.m.id}|${s.date}|${s.debut}`);
    const marques = [];
    for (const el of s.classe.eleves) {
      const x = t();
      if (x < el.tauxAbsence) {
        marques.push({ inscriptionId: el.inscriptionId, type: 'ABSENCE' });
        const jours = e.absences.get(el) ?? [];
        jours.push(s.date);
        e.absences.set(el, jours);
      } else if (x < el.tauxAbsence + el.tauxRetard) {
        marques.push({ inscriptionId: el.inscriptionId, type: 'RETARD', minutesRetard: choisir(t, MINUTES_RETARD) });
        e.retards.push({ el, date: s.date });
      }
    }
    const appel = {
      idClient: uuidStable(`appel|${s.classe.id}|${s.m.id}|${s.date}|${s.debut}`),
      classeId: s.classe.id,
      matiereId: s.m.id,
      date: s.date,
      heureDebut: s.debut,
      heureFin: s.fin,
      saisiLe: new Date(`${s.date}T${s.debut}:00`).toISOString(),
      marques,
    };
    const prof = s.m.enseignant;
    if (!parProf.has(prof.session)) parProf.set(prof.session, []);
    parProf.get(prof.session).push(appel);
  }
  await parallele([...parProf.entries()], async ([session, appels]) => {
    for (let i = 0; i < appels.length; i += 50) {
      await tenter('Appels', async () => {
        const accuses = await api('POST', '/appels/lot', { appels: appels.slice(i, i + 50) }, session);
        for (const a of accuses) {
          if (a.statut === 'REFUSE') avertir('Appel refusé', new Error(`${a.code} ${a.message}`));
          else e.stats.appels++;
          e.stats.absences += a.absents ?? 0;
          e.stats.retards += a.retards ?? 0;
        }
      });
    }
  });
  ok(`${e.stats.appels} appels, ${e.stats.absences} absences, ${e.stats.retards} retards`);

  etape('Cahier de textes');
  const aEnvoyer = [];
  const parCours = new Map();
  for (const s of e.seances) {
    const cle = `${s.classe.id}|${s.m.id}`;
    if (!parCours.has(cle)) parCours.set(cle, []);
    parCours.get(cle).push(s);
  }
  for (const seances of parCours.values()) {
    let heures = 0;
    for (const s of seances) {
      const t = tirage(`cahier|${s.classe.id}|${s.m.id}|${s.date}|${s.debut}`);
      const retard = s.m.code === e.retardataire;
      const rempli = retard ? s.date <= ajouterJours(AUJOURDHUI, -10) && t() < 0.5 : t() < 0.88;
      // La séance a eu lieu même si elle n'est pas notée : la progression avance quand même
      let ordre = null;
      if (s.m.fiche) {
        let cumul = 0;
        ordre = s.m.fiche.length;
        for (const [i, seq] of s.m.fiche.entries()) {
          cumul += seq.heuresPrevues;
          if (heures < cumul) {
            ordre = i + 1;
            break;
          }
        }
      }
      heures += 2;
      if (!rempli) continue;
      const pratique = ['PRATIQUE', 'MODULE_COMPETENCES'].includes(s.m.type);
      const sequence = ordre ? s.m.fiche[ordre - 1].titre : null;
      const activite = choisir(t, pratique ? ACTIVITES_ATELIER : ACTIVITES_COURS);
      aEnvoyer.push({
        session: s.m.enseignant.session,
        id: uuidStable(`seance|${s.classe.id}|${s.m.id}|${s.date}|${s.debut}`),
        donnees: {
          classeId: s.classe.id,
          matiereId: s.m.id,
          date: s.date,
          heureDebut: s.debut,
          heureFin: s.fin,
          sequenceOrdre: ordre,
          contenu: sequence ? `${sequence} — ${activite}.` : `${activite} (hors séquence).`,
          travailAFaire: t() < 0.5 ? choisir(t, TRAVAUX) : null,
        },
      });
    }
  }
  await parallele(aEnvoyer, async (s) => {
    await tenter('Cahier de textes', async () => {
      await api('PUT', `/cahier-textes/${s.id}`, s.donnees, s.session);
      e.stats.seancesCahier++;
    });
  });
  ok(`${e.stats.seancesCahier} séances notées sur ${e.seances.length} (enseignant en retard : ${CATALOGUE[e.retardataire][0]})`);
}

// ---------------------------------------------------------------- évaluations

function periodeDu(e, date) {
  return e.periodes.find((p) => p.debut <= date && date <= p.fin);
}

async function evaluer(e) {
  if (e.profil === 'PROFESSIONNEL') {
    await competences(e);
    return;
  }
  etape('Évaluations et notes');
  let evaluations = 0;
  const travaux = e.classes.flatMap((c) => c.matieres.filter((m) => m.enseignant).map((m) => ({ c, m })));
  await parallele(travaux, async ({ c, m }) => {
    const pratique = m.type === 'PRATIQUE';
    const prevues = [
      { libelle: pratique ? 'TP noté 1' : 'Interrogation 1', type: pratique ? 'TP' : 'INTERROGATION', decalage: 9, bareme: pratique ? 20 : 10, poids: 1 },
      { libelle: 'Devoir 1', type: 'DEVOIR', decalage: 19, bareme: 20, poids: 2 },
    ];
    for (const ev of prevues) {
      const date = jourOuvre(ajouterJours(RENTREE, ev.decalage));
      const periode = periodeDu(e, date);
      if (date >= AUJOURDHUI || !periode) continue;
      await tenter('Évaluations', async () => {
        const evaluation = await api('POST', `/classes/${c.id}/evaluations`, {
          idClient: uuidStable(`evaluation|${c.id}|${m.id}|${ev.libelle}`),
          matiereId: m.id,
          periodeId: periode.id,
          libelle: ev.libelle,
          type: ev.type,
          date,
          bareme: ev.bareme,
          poids: ev.poids,
        }, m.enseignant.session);
        const t = tirage(`notes|${c.id}|${m.id}|${ev.libelle}`);
        const notes = c.eleves.map((el) => {
          if (t() < el.tauxAbsence) return { inscriptionId: el.inscriptionId, absent: true };
          const sur20 = Math.min(20, Math.max(0, normale(t, el.niveau + (pratique ? 1 : 0), 2.4)));
          return { inscriptionId: el.inscriptionId, valeur: Math.round(((sur20 * ev.bareme) / 20) * 4) / 4 };
        });
        await api('PUT', `/evaluations/${evaluation.id}/notes`, { notes }, m.enseignant.session);
        evaluations++;
        e.stats.notes += notes.filter((n) => !n.absent).length;
      });
    }
  });
  ok(`${evaluations} évaluations, ${e.stats.notes} notes (premier trimestre, non verrouillé)`);
}

async function competences(e) {
  const m1 = e.periodes.find((p) => p.libelle === 'Module 1');
  etape('Compétences des modules');
  const competencesDe = {};
  for (const m of Object.values(e.matieres)) {
    competencesDe[m.id] = [];
    for (const [i, libelle] of (SEQUENCES[m.code] ?? []).slice(0, 3).entries()) {
      const c = await tenter('Compétences', () => api('POST', `/matieres/${m.id}/competences`, { code: `C${i + 1}`, libelle, ordre: i + 1 }, e.personnel.CENSEUR.session));
      if (c) competencesDe[m.id].push(c);
    }
  }
  ok(`${Object.values(competencesDe).flat().length} compétences`);

  etape('Évaluation des compétences du Module 1');
  const travaux = e.classes.flatMap((c) => c.matieres.filter((m) => m.enseignant).map((m) => ({ c, m })));
  await parallele(travaux, async ({ c, m }) => {
    const liste = competencesDe[m.id];
    if (!liste.length || !m1) return;
    await tenter('Évaluation des compétences', async () => {
      const t = tirage(`competences|${c.id}|${m.id}`);
      const resultats = c.eleves.flatMap((el) =>
        liste.map((comp) => {
          const score = normale(t, el.niveau, 2.5);
          return { inscriptionId: el.inscriptionId, competenceId: comp.id, niveau: score >= 11.5 ? 'ACQUIS' : score >= 8.5 ? 'EN_COURS' : 'NON_ACQUIS' };
        }),
      );
      await api('PUT', `/classes/${c.id}/competences?periodeId=${m1.id}&matiereId=${m.id}`, { resultats }, m.enseignant.session);
      e.stats.competences += resultats.length;
    });
  });
  ok(`${e.stats.competences} niveaux de maîtrise saisis`);

  etape('Verrouillage du Module 1 et relevés de compétences');
  const censeur = e.personnel.CENSEUR.session;
  const r = await tenter('Relevés de compétences', async () => {
    await api('POST', `/periodes/${m1.id}/verrouillage`, undefined, censeur);
    let publies = 0;
    for (const c of e.classes) {
      await api('POST', `/classes/${c.id}/periodes/${m1.id}/bulletins`, undefined, censeur);
      await api('POST', `/classes/${c.id}/periodes/${m1.id}/bulletins/publication`, undefined, censeur);
      publies++;
    }
    return `${publies} classes : relevés générés et publiés (visibles par les parents)`;
  });
  ok(r ?? 'non faits (voir les avertissements)');
}

// ---------------------------------------------------------------- vie scolaire

async function vieScolaire(e) {
  const surveillant = e.personnel.SURVEILLANT.session;
  const censeur = e.personnel.CENSEUR.session;

  etape('Justificatifs d’absence');
  let justifies = 0;
  const couples = [...e.absences.entries()].flatMap(([el, jours]) => [...new Set(jours)].map((jour) => ({ el, jour })));
  await parallele(couples, async ({ el, jour }) => {
    const t = tirage(`justif|${el.inscriptionId}|${jour}`);
    if (t() > 0.45) return;
    const [type, motif] = choisir(t, MOTIFS_JUSTIFICATION);
    await tenter('Justificatifs', async () => {
      await api('POST', `/inscriptions/${el.inscriptionId}/justificatifs`, { du: jour, au: jour, type, motif }, surveillant);
      justifies++;
    });
  });
  ok(`${justifies} journées justifiées sur ${couples.length} (les autres restent à justifier)`);

  etape('Incidents');
  const faits = [];
  const recent = jourOuvre(ajouterJours(AUJOURDHUI, -2), -1);
  const date = recent < RENTREE ? RENTREE : recent;
  for (const { el, date: jour } of e.retards.slice(0, 3)) {
    await tenter('Incidents', async () => {
      await api('POST', `/inscriptions/${el.inscriptionId}/incidents`, { type: 'RETARD', date: jour, motif: 'Arrivé après la fermeture du portail', minutesRetard: 25, prevenirFamille: true }, surveillant);
      faits.push('retard');
    });
  }
  const t = tirage(`incidents|${e.n}`);
  const tous = e.eleves.slice();
  const pioche = () => tous.splice(Math.floor(t() * tous.length), 1)[0];
  for (let i = 0; i < 4; i++) {
    const el = pioche();
    const m = el.classe.matieres.find((x) => x.enseignant && !x.enseignant.partage);
    await tenter('Incidents', async () => {
      await api('POST', `/inscriptions/${el.inscriptionId}/incidents`, {
        type: 'AVERTISSEMENT', date, motif: choisir(t, ['Bavardages répétés pendant le cours', 'Téléphone utilisé en classe', 'Travail non fait à trois reprises', 'Refus de participer aux travaux pratiques']), prevenirFamille: true,
      }, m.enseignant.session);
      faits.push('avertissement');
    });
  }
  const blame = pioche();
  const incidentBlame = await tenter('Incidents', () =>
    api('POST', `/inscriptions/${blame.inscriptionId}/incidents`, { type: 'BLAME', date, motif: 'Insolence envers un surveillant', prevenirFamille: true }, censeur));
  if (incidentBlame) faits.push('blâme');
  if (e.exclusion) {
    const el = pioche();
    await tenter('Incidents', async () => {
      await api('POST', `/inscriptions/${el.inscriptionId}/incidents`, {
        type: 'EXCLUSION_TEMPORAIRE', date, motif: 'Bagarre dans la cour de récréation', debutExclusion: jourOuvre(ajouterJours(AUJOURDHUI, 1)), joursExclusion: 3, prevenirFamille: true,
      }, censeur);
      faits.push('exclusion temporaire');
    });
  }
  ok(faits.length ? Object.entries(faits.reduce((a, x) => ({ ...a, [x]: (a[x] ?? 0) + 1 }), {})).map(([k, v]) => `${v} ${k}`).join(', ') : 'aucun');

  etape('Convocations des parents');
  const rendezVous = jourOuvre(ajouterJours(AUJOURDHUI, 3));
  const plusAbsents = [...e.absences.entries()].sort((a, b) => b[1].length - a[1].length).slice(0, 2);
  const convoques = [];
  for (const [i, [el, jours]] of plusAbsents.entries()) {
    await tenter('Convocations', async () => {
      await api('POST', `/inscriptions/${el.inscriptionId}/convocations`, { rendezVous: `${rendezVous}T${i ? '11' : '10'}:00`, motif: `Absences répétées (${jours.length} séances manquées)` }, surveillant);
      convoques.push(`${el.prenoms} ${el.nom}`);
    });
  }
  if (incidentBlame) {
    await tenter('Convocations', async () => {
      await api('POST', `/inscriptions/${blame.inscriptionId}/convocations`, { rendezVous: `${rendezVous}T15:00`, motif: 'Entretien après le blâme', incidentId: incidentBlame.id }, surveillant);
      convoques.push(`${blame.prenoms} ${blame.nom}`);
    });
  }
  ok(convoques.length ? `${convoques.join(', ')} — le ${rendezVous}` : 'aucune');
}

// ---------------------------------------------------------------- espace parent et Mobile Money

async function espacesParents(e) {
  etape('Espaces parents');
  const choisies = [];
  const ajouter = (f) => f && !choisies.includes(f) && choisies.push(f);
  e.classes[0].eleves.slice(0, 6).forEach((el) => ajouter(el.famille));
  e.classes.slice(1).forEach((c) => c.eleves.slice(0, 2).forEach((el) => ajouter(el.famille)));
  ajouter(e.familles.find((f) => f.enfants.filter((x) => e.eleves.includes(x)).length > 1));
  if (GLOBAL.familleCommune && e.familles.includes(GLOBAL.familleCommune)) ajouter(GLOBAL.familleCommune);

  for (const f of choisies) {
    await tenter('Espace parent', async () => {
      const r = await api('POST', `/responsables/${f.responsableIds[e.n]}/espace-parent`, undefined, e.sAdmin);
      if (!f.sessions) f.sessions = {};
      f.sessions[e.n] = await compte(f.telephone, r.motDePasseTemporaire, e.id);
      const enfants = f.enfants.filter((x) => e.eleves.includes(x));
      const autres = f.enfants.filter((x) => !e.eleves.includes(x));
      let remarque = enfants.map((x) => `${x.prenoms} (${x.classe.code})`).join(', ');
      if (enfants.length > 1) remarque += ' — fratrie';
      if (autres.length) remarque += ' — a aussi un enfant dans un autre établissement : choix à la connexion';
      const ligne = noterCompte(e, 'Parent', f, f.telephone, remarque);
      if (f.ligneCompte && autres.length) f.ligneCompte.remarque += ' — a aussi un enfant dans un autre établissement : choix à la connexion';
      f.ligneCompte ??= ligne;
    });
  }
  ok(`${choisies.length} parents`);

  if (!e.mobileMoney) return;
  etape('Paiements Mobile Money des parents (simulateur)');
  const intendant = e.personnel.INTENDANT.session;
  const resultats = [];
  const familles = choisies.filter((f) => f.sessions?.[e.n]);
  for (const [i, f] of familles.entries()) {
    if (resultats.length >= 3) break;
    const enfant = f.enfants.find((x) => e.eleves.includes(x));
    await tenter('Mobile Money', async () => {
      const s = await api('GET', `/inscriptions/${enfant.inscriptionId}/scolarite`, undefined, intendant);
      if ((s.resteFamille ?? 0) < 1000) return;
      const montant = Math.max(500, Math.min(25000, arrondi500(s.resteFamille / 2)));
      const transaction = await api('POST', `/espace-parent/inscriptions/${enfant.inscriptionId}/mobile-money`, {
        montant,
        operateur: i % 2 ? 'MOOV_MONEY' : 'ORANGE_MONEY',
        telephone: f.telephone,
        cleIdempotence: uuidStable(`mm|${enfant.inscriptionId}`),
      }, f.sessions[e.n]);
      const refuser = resultats.length === 2;
      await api('POST', `/simulateur/mobile-money/${transaction.id}${refuser ? '?accepter=false' : ''}`, undefined, f.sessions[e.n]);
      resultats.push(refuser ? `${fcfa(montant)} refusé par le parent` : `${fcfa(montant)} confirmé`);
      if (!refuser) {
        e.stats.mm++;
        e.stats.encaisse += montant;
      }
    });
  }
  ok(resultats.join(', ') || 'aucun');
}

async function relancesEtStatistiques(e) {
  etape('Relances SMS des familles en retard');
  const r = await tenter('Relances', () => api('POST', '/scolarite/relances', undefined, e.personnel.INTENDANT.session));
  ok(r ? `${r.envoyees} SMS, ${r.sansContact} sans contact, ${r.dejaRelancees} déjà relancées` : 'non faites');

  etape('Statistiques');
  const s = await tenter('Statistiques', () => api('GET', `/annees/${e.annee.id}/statistiques`, undefined, e.sAdmin));
  if (s) {
    const tot = s.recouvrementTotal;
    e.rapport = s;
    ok(`${s.effectifTotal.garcons + s.effectifTotal.filles} élèves, recouvrement familles ${tot?.tauxFamilles ?? '?'} %, organismes ${tot?.tauxOrganismes ?? '?'} %`);
  } else {
    ok('indisponibles');
  }
}

// ===================================================================== bilan

function bilanFinal(debutChrono) {
  const duree = Math.round((Date.now() - debutChrono) / 1000);
  console.log(`\n━━ Bilan (${Math.floor(duree / 60)} min ${duree % 60} s)\n`);
  for (const e of GLOBAL.ecoles) {
    const f = e.stats.fiches;
    console.log(`  ${e.nom}`);
    console.log(`    ${e.eleves.length} élèves · ${Object.keys(e.enseignants).length} enseignants · ${e.stats.appels} appels · ${e.stats.absences} absences`);
    console.log(`    progressions : ${f.VISEE} visées, ${f.A_REVOIR} à revoir, ${f.SOUMISE} à viser, ${f.BROUILLON} brouillon, ${f.aucune} non commencée(s) · cahier : ${e.stats.seancesCahier} séances`);
    console.log(`    ${e.profil === 'PROFESSIONNEL' ? `${e.stats.competences} niveaux de compétences` : `${e.stats.notes} notes`} · ${e.stats.paiements} paiements au guichet + ${e.stats.mm} Mobile Money · ${fcfa(e.stats.encaisse)} encaissés`);
  }

  if (AVERTISSEMENTS.size) {
    console.log('\n  ⚠ Avertissements (le reste a été créé) :');
    for (const [message, n] of AVERTISSEMENTS) {
      console.log(`    ${n > 1 ? `${n} × ` : ''}${message}`);
    }
  } else {
    console.log('\n  ✔ Aucun avertissement.');
  }

  const lignes = [
    '# Comptes du test grandeur nature (DÉVELOPPEMENT UNIQUEMENT)',
    '',
    `Généré le ${new Date().toLocaleString('fr-FR')} par \`scripts/demo/peupler-mvp.mjs\`. Ce fichier n'est pas versionné.`,
    '',
    `Mot de passe de tous les comptes : **${MOT_DE_PASSE}** · super administrateur : ${SUPER_ADMIN_TELEPHONE} / ${SUPER_ADMIN_NOUVEAU}`,
    '',
    `Application : http://localhost:4200 · année ${LIBELLE_ANNEE}, rentrée le ${RENTREE}`,
    '',
  ];
  for (const e of GLOBAL.ecoles) {
    lignes.push(`## ${e.nom} (${e.code})`, '', '| Rôle | Nom | Téléphone | À essayer |', '|---|---|---|---|');
    for (const c of COMPTES.filter((x) => x.ecole === e.nom)) {
      lignes.push(`| ${c.role} | ${c.nom} | ${c.telephone} | ${c.remarque} |`);
    }
    lignes.push('');
  }
  try {
    writeFileSync(FICHIER_COMPTES, lignes.join('\n'));
  } catch (err) {
    console.log(`\n  (fichier des comptes non écrit : ${err.message})`);
  }

  console.log(`
✔ Les trois établissements sont prêts. Application : http://localhost:4200

  Mot de passe de tous les comptes : ${MOT_DE_PASSE}
  Liste complète des comptes (rôles, téléphones, quoi essayer) : scripts/demo/comptes-demo.md

  Pour commencer :`);
  for (const e of GLOBAL.ecoles) {
    const p = e.personnel;
    const prof = e.enseignants[e.retardataire];
    console.log(`    ${e.nom}`);
    console.log(`      administrateur ${e.admin.telephone} · censeur ${p.CENSEUR?.telephone}${p.CHEF_TRAVAUX ? ` · chef des travaux ${p.CHEF_TRAVAUX.telephone}` : ''} · intendant ${p.INTENDANT?.telephone} · surveillant ${p.SURVEILLANT?.telephone}`);
    console.log(`      enseignant en retard ${prof?.telephone} · parent ${COMPTES.find((c) => c.ecole === e.nom && c.role === 'Parent')?.telephone ?? '—'}`);
  }
  console.log('\n  Comptes de développement uniquement.\n');
}

principal().catch((e) => arreter(e.stack && !(e instanceof ErreurApi) ? e.stack : e.message));
