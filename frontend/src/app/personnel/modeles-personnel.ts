// Dossier administratif du personnel (v0.51)

export type CategorieAgent = 'ENSEIGNANT' | 'ADMINISTRATIF' | 'SOUTIEN';
export type Situation = 'FONCTIONNAIRE' | 'CONTRACTUEL' | 'VACATAIRE' | 'AUTRE';
export type NaturePiece = 'ARRETE' | 'DECISION' | 'NOTE_SERVICE' | 'CERTIFICAT' | 'DIPLOME' | 'ETAT_CIVIL' | 'CONTRAT' | 'AUTRE';
export type NatureDiplome = 'ACADEMIQUE' | 'PROFESSIONNEL';
export type TypeActe = 'PRISE_SERVICE' | 'CESSATION_SERVICE' | 'PRESENCE_POSTE' | 'ATTESTATION_TRAVAIL' | 'AUTORISATION_ABSENCE';
export type TypeAbsence =
  | 'AUTORISATION_ABSENCE' | 'PERMISSION' | 'CONGE_ANNUEL' | 'CONGE_MALADIE' | 'CONGE_MATERNITE' | 'MISSION' | 'FORMATION'
  | 'ABSENCE_IRREGULIERE';
export type StatutAbsence = 'DEMANDEE' | 'ACCORDEE' | 'REFUSEE' | 'ANNULEE' | 'CONSTATEE';

export const CATEGORIES: Record<CategorieAgent, string> = {
  ENSEIGNANT: 'Enseignant',
  ADMINISTRATIF: 'Administratif',
  SOUTIEN: 'Agent de soutien',
};

export const SITUATIONS: Record<Situation, string> = {
  FONCTIONNAIRE: 'Fonctionnaire',
  CONTRACTUEL: 'Contractuel',
  VACATAIRE: 'Vacataire',
  AUTRE: 'Autre',
};

export const NATURES_PIECE: Record<NaturePiece, string> = {
  ARRETE: 'Arrêté',
  DECISION: 'Décision',
  NOTE_SERVICE: 'Note de service',
  CERTIFICAT: 'Certificat',
  DIPLOME: 'Diplôme',
  ETAT_CIVIL: 'État civil, CNIB',
  CONTRAT: 'Contrat',
  AUTRE: 'Autre',
};

export const TYPES_ACTE: Record<TypeActe, string> = {
  PRISE_SERVICE: 'Certificat de prise de service',
  CESSATION_SERVICE: 'Certificat de cessation de service',
  PRESENCE_POSTE: 'Attestation de présence au poste',
  ATTESTATION_TRAVAIL: 'Attestation de travail',
  AUTORISATION_ABSENCE: "Autorisation d'absence",
};

export const TYPES_ABSENCE: Record<TypeAbsence, string> = {
  AUTORISATION_ABSENCE: "Autorisation d'absence",
  PERMISSION: 'Permission',
  CONGE_ANNUEL: 'Congé annuel',
  CONGE_MALADIE: 'Congé de maladie',
  CONGE_MATERNITE: 'Congé de maternité',
  MISSION: 'Mission',
  FORMATION: 'Formation',
  ABSENCE_IRREGULIERE: 'Absence irrégulière',
};

export const STATUTS_ABSENCE: Record<StatutAbsence, string> = {
  DEMANDEE: 'À décider',
  ACCORDEE: 'Accordée',
  REFUSEE: 'Refusée',
  ANNULEE: 'Annulée',
  CONSTATEE: 'Constatée',
};

export interface Duree {
  annees: number;
  mois: number;
  jours: number;
  texte: string;
}

export interface Anciennete {
  fonction: Duree | null;
  grade: Duree | null;
  etablissement: Duree | null;
}

export interface DonneesAgent {
  categorie: CategorieAgent;
  nom: string;
  prenoms: string;
  sexe: 'M' | 'F';
  dateNaissance: string | null;
  lieuNaissance: string | null;
  telephone: string | null;
  adresse: string | null;
  matricule: string | null;
  situation: Situation | null;
  emploi: string | null;
  grade: string | null;
  categorieEchelle: string | null;
  echelon: string | null;
  fonction: string | null;
  datePremierePriseService: string | null;
  dateEntreeGrade: string | null;
  datePriseService: string | null;
  dateCessation: string | null;
  motifCessation: string | null;
  observations: string | null;
}

export interface AgentResume {
  id: string;
  categorie: CategorieAgent;
  nom: string;
  prenoms: string;
  sexe: 'M' | 'F';
  matricule: string | null;
  situation: Situation | null;
  emploi: string | null;
  grade: string | null;
  fonction: string | null;
  datePriseService: string | null;
  dateCessation: string | null;
  compte: boolean;
  ancienneteEtablissement: Duree | null;
  pieces: number;
  signalements: number;
  absentAujourdhui: boolean;
  manquants: string[];
}

export interface ListePersonnel {
  agents: AgentResume[];
  comptesSansDossier: number;
}

export interface DiplomeVue {
  id: string;
  nature: NatureDiplome;
  intitule: string;
  specialite: string | null;
  delivrePar: string | null;
  annee: number | null;
  pieceId: string | null;
}

export interface PieceVue {
  id: string;
  nature: NaturePiece;
  reference: string | null;
  datePiece: string | null;
  objet: string;
  nomFichier: string;
  typeContenu: string;
  taille: number;
  deposePar: string | null;
  deposeLe: string;
}

export interface ActeVue {
  id: string;
  type: TypeActe;
  numero: string | null;
  titre: string;
  dateEffet: string | null;
  etabliPar: string | null;
  etabliLe: string;
  signataire: string | null;
  signeLe: string | null;
  annuleLe: string | null;
  motifAnnulation: string | null;
  absenceId: string | null;
}

export interface AbsenceVue {
  id: string;
  agentId: string;
  agent: string;
  type: TypeAbsence;
  debut: string;
  fin: string;
  jours: number;
  reprise: string;
  motif: string;
  statut: StatutAbsence;
  demandeePar: string | null;
  demandeeLe: string;
  decideePar: string | null;
  decideeLe: string | null;
  reponse: string | null;
  pieceId: string | null;
  acteId: string | null;
}

export interface SignalementVue {
  id: string;
  texte: string;
  signalePar: string | null;
  signaleLe: string;
  traitePar: string | null;
  traiteLe: string | null;
  reponse: string | null;
}

export interface DossierVue extends DonneesAgent {
  id: string;
  utilisateurId: string | null;
  anciennete: Anciennete;
  manquants: string[];
  diplomes: DiplomeVue[];
  pieces: PieceVue[];
  actes: ActeVue[];
  absences: AbsenceVue[];
  signalements: SignalementVue[];
  droits: { modifier: boolean; pieces: boolean; actes: boolean; signer: boolean; decider: boolean };
  modifieLe: string;
}

export interface DonneesAbsence {
  type: TypeAbsence;
  debut: string;
  fin: string;
  motif: string;
}

/** Les champs saisis d'un dossier, à partir de sa vue. */
export function donnees(d: DossierVue): DonneesAgent {
  return {
    categorie: d.categorie, nom: d.nom, prenoms: d.prenoms, sexe: d.sexe, dateNaissance: d.dateNaissance,
    lieuNaissance: d.lieuNaissance, telephone: d.telephone, adresse: d.adresse, matricule: d.matricule,
    situation: d.situation, emploi: d.emploi, grade: d.grade, categorieEchelle: d.categorieEchelle, echelon: d.echelon,
    fonction: d.fonction, datePremierePriseService: d.datePremierePriseService, dateEntreeGrade: d.dateEntreeGrade,
    datePriseService: d.datePriseService, dateCessation: d.dateCessation, motifCessation: d.motifCessation,
    observations: d.observations,
  };
}

export function vide(): DonneesAgent {
  return {
    categorie: 'SOUTIEN', nom: '', prenoms: '', sexe: 'M', dateNaissance: null, lieuNaissance: null, telephone: null,
    adresse: null, matricule: null, situation: null, emploi: null, grade: null, categorieEchelle: null, echelon: null,
    fonction: null, datePremierePriseService: null, dateEntreeGrade: null, datePriseService: null, dateCessation: null,
    motifCessation: null, observations: null,
  };
}
