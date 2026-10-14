import { Role } from '../core/modeles';

/** Secrétariat (v0.47.1) : modèles des échanges avec l'API et libellés. */

export type Service = 'DIRECTION' | 'CENSORAT' | 'CHEF_TRAVAUX' | 'INTENDANCE' | 'VIE_SCOLAIRE' | 'SECRETARIAT' | 'ENSEIGNANTS';

export const SERVICES: Service[] = ['DIRECTION', 'CENSORAT', 'CHEF_TRAVAUX', 'INTENDANCE', 'VIE_SCOLAIRE', 'SECRETARIAT', 'ENSEIGNANTS'];

export const LIBELLE_SERVICE: Record<Service, string> = {
  DIRECTION: 'Direction',
  CENSORAT: 'Censorat',
  CHEF_TRAVAUX: 'Chef des travaux',
  INTENDANCE: 'Intendance',
  VIE_SCOLAIRE: 'Vie scolaire (CPE)',
  SECRETARIAT: 'Secrétariat',
  ENSEIGNANTS: 'Enseignants',
};

/** Rôles qui gèrent le secrétariat ; les services ne voient que le courrier qui leur est transmis. */
export const GESTION_SECRETARIAT: Role[] = ['ADMIN_ECOLE', 'SECRETARIAT'];
export const SERVICES_COURRIER: Role[] = ['CENSEUR', 'CHEF_TRAVAUX', 'INTENDANT', 'SURVEILLANT', 'ENSEIGNANT'];

// ---------- Courrier

export type Sens = 'ARRIVEE' | 'DEPART';
export type CategorieCourrier =
  | 'DIRECTION_PROVINCIALE'
  | 'DIRECTION_REGIONALE'
  | 'MINISTERE'
  | 'PARENTS'
  | 'PERSONNEL'
  | 'PARTENAIRE'
  | 'NOTE_SERVICE'
  | 'AUTRE';
export type StatutCourrier = 'ENREGISTRE' | 'TRANSMIS' | 'TRAITE' | 'CLASSE';

export const CATEGORIES_COURRIER: CategorieCourrier[] = [
  'DIRECTION_PROVINCIALE',
  'DIRECTION_REGIONALE',
  'MINISTERE',
  'PARENTS',
  'PERSONNEL',
  'PARTENAIRE',
  'NOTE_SERVICE',
  'AUTRE',
];

export const LIBELLE_CATEGORIE: Record<CategorieCourrier, string> = {
  DIRECTION_PROVINCIALE: 'Direction provinciale',
  DIRECTION_REGIONALE: 'Direction régionale',
  MINISTERE: 'Ministère',
  PARENTS: 'Parents d’élèves',
  PERSONNEL: 'Personnel',
  PARTENAIRE: 'Partenaire',
  NOTE_SERVICE: 'Note de service',
  AUTRE: 'Autre',
};

export const LIBELLE_STATUT_COURRIER: Record<StatutCourrier, string> = {
  ENREGISTRE: 'enregistré',
  TRANSMIS: 'transmis',
  TRAITE: 'traité',
  CLASSE: 'classé',
};

export interface CourrierVue {
  id: string;
  sens: Sens;
  numero: string;
  dateCourrier: string;
  dateDocument: string | null;
  reference: string | null;
  correspondant: string;
  categorie: CategorieCourrier;
  objet: string;
  service: Service | null;
  statut: StatutCourrier;
  suivi: string | null;
  cote: string | null;
  reponseA: string | null;
  reponseANumero: string | null;
  fichierNom: string | null;
  enregistrePar: string | null;
  enregistreLe: string;
}

export interface SaisieCourrier {
  sens: Sens;
  dateCourrier: string | null;
  dateDocument: string | null;
  reference: string | null;
  correspondant: string;
  categorie: CategorieCourrier;
  objet: string;
  service: Service | null;
  cote: string | null;
  reponseA: string | null;
}

// ---------- Documents administratifs

export type TypeDocument = 'NOTE_SERVICE' | 'DECISION' | 'COMMUNIQUE' | 'ATTESTATION' | 'CONVOCATION' | 'LETTRE' | 'AUTRE';
export type StatutDocument = 'BROUILLON' | 'SIGNE' | 'ANNULE';
export type PublicAffichage = 'PERSONNEL' | 'TOUS';

export const TYPES_DOCUMENT: TypeDocument[] = ['NOTE_SERVICE', 'DECISION', 'COMMUNIQUE', 'ATTESTATION', 'CONVOCATION', 'LETTRE', 'AUTRE'];

export const LIBELLE_TYPE_DOCUMENT: Record<TypeDocument, string> = {
  NOTE_SERVICE: 'Note de service',
  DECISION: 'Décision',
  COMMUNIQUE: 'Communiqué',
  ATTESTATION: 'Attestation',
  CONVOCATION: 'Convocation',
  LETTRE: 'Lettre',
  AUTRE: 'Document',
};

export interface DocumentVue {
  id: string;
  type: TypeDocument;
  numero: string | null;
  titre: string;
  destinataires: string | null;
  corps: string;
  signataire: string;
  statut: StatutDocument;
  redigePar: string | null;
  redigeLe: string;
  signePar: string | null;
  signeLe: string | null;
  affichage: PublicAffichage | null;
  afficheDu: string | null;
  afficheAu: string | null;
}

export interface SaisieDocument {
  type: TypeDocument;
  titre: string;
  destinataires: string | null;
  corps: string;
  signataire: string | null;
}

export interface AfficheVue {
  id: string;
  type: TypeDocument;
  typeLibelle: string;
  numero: string;
  titre: string;
  signeLe: string;
  afficheAu: string;
}

// ---------- Actes administratifs du personnel

export type TypeDemande = 'MUTATION' | 'AFFECTATION' | 'AUTORISATION_ABSENCE' | 'CONGE' | 'DISPONIBILITE' | 'ATTESTATION_SERVICE' | 'AUTRE';
export type StatutDemande = 'RECUE' | 'TRANSMISE' | 'AVIS_FAVORABLE' | 'AVIS_DEFAVORABLE' | 'TRANSMISE_HIERARCHIE' | 'CLOTUREE';

export const TYPES_DEMANDE: TypeDemande[] = ['MUTATION', 'AFFECTATION', 'AUTORISATION_ABSENCE', 'CONGE', 'DISPONIBILITE', 'ATTESTATION_SERVICE', 'AUTRE'];

export const LIBELLE_TYPE_DEMANDE: Record<TypeDemande, string> = {
  MUTATION: 'Mutation',
  AFFECTATION: 'Affectation',
  AUTORISATION_ABSENCE: "Autorisation d'absence",
  CONGE: 'Congé',
  DISPONIBILITE: 'Mise en disponibilité',
  ATTESTATION_SERVICE: 'Attestation de service',
  AUTRE: 'Autre',
};

export const LIBELLE_STATUT_DEMANDE: Record<StatutDemande, string> = {
  RECUE: 'reçue',
  TRANSMISE: 'au chef d’établissement',
  AVIS_FAVORABLE: 'avis favorable',
  AVIS_DEFAVORABLE: 'avis défavorable',
  TRANSMISE_HIERARCHIE: 'transmise à la hiérarchie',
  CLOTUREE: 'close',
};

export interface DemandeVue {
  id: string;
  numero: string;
  type: TypeDemande;
  typeLibelle: string;
  demandeurId: string | null;
  demandeur: string;
  recueLe: string;
  objet: string;
  du: string | null;
  au: string | null;
  statut: StatutDemande;
  avis: string | null;
  avisPar: string | null;
  avisLe: string | null;
  suite: string | null;
  courrierId: string | null;
  courrierNumero: string | null;
  fichierNom: string | null;
}

// ---------- Accueil

export interface PersonneVue {
  utilisateurId: string;
  nom: string;
  prenoms: string;
  fonctions: string;
}

export type Canal = 'VISITE' | 'APPEL';
export type Qualite = 'PARENT' | 'VISITEUR' | 'PARTENAIRE' | 'AUTORITE' | 'PERSONNEL' | 'AUTRE';

export const LIBELLE_QUALITE: Record<Qualite, string> = {
  PARENT: 'Parent d’élève',
  VISITEUR: 'Visiteur',
  PARTENAIRE: 'Partenaire',
  AUTORITE: 'Autorité',
  PERSONNEL: 'Personnel',
  AUTRE: 'Autre',
};

export interface PassageVue {
  id: string;
  canal: Canal;
  arrivee: string;
  depart: string | null;
  nom: string;
  telephone: string | null;
  qualite: Qualite;
  objet: string;
  service: Service | null;
  personneId: string | null;
  personne: string | null;
  message: string | null;
  urgent: boolean;
  transmisLe: string | null;
}

export interface SaisiePassage {
  canal: Canal;
  nom: string;
  telephone: string | null;
  qualite: Qualite;
  objet: string;
  service: Service | null;
  personneId: string | null;
  message: string | null;
  urgent: boolean;
  transmettre: boolean;
}

// ---------- Réunions

export type Instance =
  | 'CONSEIL_ETABLISSEMENT'
  | 'CONSEIL_CLASSE'
  | 'CONSEIL_DISCIPLINE'
  | 'CONSEIL_ENSEIGNEMENT'
  | 'CONSEIL_ECOLE'
  | 'ASSEMBLEE_APE'
  | 'REUNION_PERSONNEL'
  | 'AUTRE';

export const INSTANCES: Instance[] = [
  'CONSEIL_ETABLISSEMENT',
  'CONSEIL_CLASSE',
  'CONSEIL_DISCIPLINE',
  'CONSEIL_ENSEIGNEMENT',
  'CONSEIL_ECOLE',
  'ASSEMBLEE_APE',
  'REUNION_PERSONNEL',
  'AUTRE',
];

export const LIBELLE_INSTANCE: Record<Instance, string> = {
  CONSEIL_ETABLISSEMENT: "Conseil d'établissement",
  CONSEIL_CLASSE: 'Conseil de classe',
  CONSEIL_DISCIPLINE: 'Conseil de discipline',
  CONSEIL_ENSEIGNEMENT: "Conseil d'enseignement",
  CONSEIL_ECOLE: "Conseil de l'école",
  ASSEMBLEE_APE: "Assemblée générale de l'APE",
  REUNION_PERSONNEL: 'Réunion du personnel',
  AUTRE: 'Réunion',
};

export interface ReunionVue {
  id: string;
  instance: Instance;
  instanceLibelle: string;
  titre: string;
  date: string;
  heure: string | null;
  lieu: string | null;
  president: string | null;
  secretaireSeance: string | null;
  participants: string | null;
  ordreDuJour: string | null;
  compteRendu: string | null;
  statut: 'BROUILLON' | 'VALIDE';
  validePar: string | null;
  valideLe: string | null;
}

export interface SaisieReunion {
  instance: Instance;
  titre: string | null;
  date: string;
  heure: string | null;
  lieu: string | null;
  president: string | null;
  secretaireSeance: string | null;
  participants: string | null;
  ordreDuJour: string | null;
  compteRendu: string | null;
}

// ---------- Archives

export type DossierArchive = 'ELEVE' | 'PERSONNEL' | 'ETABLISSEMENT';

export interface PieceArchiveVue {
  id: string;
  dossier: DossierArchive;
  eleveId: string | null;
  utilisateurId: string | null;
  categorie: string;
  titre: string;
  cote: string | null;
  dateDocument: string | null;
  fichierNom: string;
  fichierType: string;
  taille: number;
  ajoutePar: string | null;
  ajouteLe: string;
}

export const CATEGORIES_ARCHIVE: Record<DossierArchive, string[]> = {
  ELEVE: ['État civil', 'Scolarité antérieure', 'Orientation et affectation', 'Diplômes', 'Santé', 'Correspondance', 'Autre'],
  PERSONNEL: ['Carrière (arrêtés, décisions)', 'Prise de service', 'Diplômes', 'Congés et absences', 'Évaluation', 'Correspondance', 'Autre'],
  ETABLISSEMENT: ['Textes officiels', 'Arrêtés et décisions', 'Conventions et partenariats', 'Patrimoine', 'Examens', 'Autre'],
};

// ---------- Certificats et examens

export interface CertificatVue {
  id: string;
  numero: string;
  inscriptionId: string;
  eleve: string;
  classe: string;
  annee: string;
  delivrePar: string | null;
  delivreLe: string;
}

export interface CandidatVue {
  inscriptionId: string;
  eleveId: string;
  classe: string;
  matricule: string | null;
  nom: string;
  prenoms: string;
  sexe: string;
  dateNaissance: string;
  lieuNaissance: string | null;
  identifiantNational: string | null;
  photo: boolean;
  manquants: string[];
}

/** Taille lisible d'un fichier. */
export function taille(octets: number): string {
  if (octets < 1024) {
    return `${octets} o`;
  }
  if (octets < 1024 * 1024) {
    return `${Math.round(octets / 1024)} Ko`;
  }
  return `${(octets / 1024 / 1024).toFixed(1).replace('.', ',')} Mo`;
}
