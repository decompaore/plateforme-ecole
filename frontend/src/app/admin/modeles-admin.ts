/** Objets de l'API utilisés par l'espace d'administration (mêmes noms que côté serveur). */

import { Role } from '../core/modeles';

export type EtatAnnee = 'PREPARATION' | 'ACTIVE' | 'CLOTUREE' | 'ARCHIVEE';
export type TypeMatiere = 'GENERALE' | 'TECHNIQUE' | 'PRATIQUE' | 'MODULE_COMPETENCES';
export type Sexe = 'M' | 'F';
export type LienParente = 'PERE' | 'MERE' | 'TUTEUR' | 'AUTRE';
export type StatutBourse = 'BOURSIER' | 'SEMI_BOURSIER' | 'NON_BOURSIER';
export type TypeEngagement = 'TITULAIRE' | 'VACATAIRE';
export type StatutEngagement = 'INVITE' | 'ACTIF' | 'TERMINE' | 'REFUSE';
export type StatutTenant = 'ACTIF' | 'SUSPENDU' | 'RESILIE';

export interface EtablissementVue {
  id: string;
  code: string;
  nom: string;
  statut: StatutTenant;
  creeLe: string;
}

export interface MembreVue {
  id: string;
  utilisateurId: string;
  nom: string;
  prenoms: string;
  telephone: string;
  role: Role;
  actif: boolean;
}

export interface ResultatCreationEtablissement {
  etablissement: EtablissementVue;
  administrateur: MembreVue;
  motDePasseTemporaire: string | null;
}

export interface ResultatAjoutMembre {
  membre: MembreVue;
  motDePasseTemporaire: string | null;
}

export interface ProfilVue {
  id: string;
  code: string;
  libelle: string;
  modele: string;
  decoupage: string;
  actif: boolean;
}

export interface AnneeVue {
  id: string;
  libelle: string;
  debut: string;
  fin: string;
  etat: EtatAnnee;
}

export interface PeriodeVue {
  id: string;
  anneeId: string;
  profilId: string;
  libelle: string;
  ordre: number;
  debut: string;
  fin: string;
  verrouillee: boolean;
}

export interface FiliereVue {
  id: string;
  code: string;
  libelle: string;
  cycle: string;
  diplomeVise: string | null;
  profilId: string;
}

export interface MatiereVue {
  id: string;
  code: string;
  libelle: string;
  type: TypeMatiere;
  actif: boolean;
}

export interface ClasseVue {
  id: string;
  anneeId: string;
  filiereId: string;
  filiereCode: string;
  profilId: string;
  code: string;
  niveau: string;
  effectifMax: number | null;
}

export interface MatiereDeClasseVue {
  id: string;
  matiereId: string;
  matiereCode: string;
  matiereLibelle: string;
  type: TypeMatiere;
  coefficient: number;
  groupe: string | null;
  volumeHebdo: number | null;
  volumeTotal: number | null;
  engagementId: string | null;
}

export interface EleveVue {
  id: string;
  matricule: string | null;
  nom: string;
  prenoms: string;
  sexe: Sexe;
  dateNaissance: string;
  lieuNaissance: string | null;
}

export interface InscriptionVue {
  id: string;
  eleveId: string;
  matricule: string | null;
  nom: string;
  prenoms: string;
  sexe: Sexe;
  dateNaissance: string;
  anneeId: string;
  anneeLibelle: string;
  classeId: string;
  classeCode: string;
  statut: 'ACTIVE' | 'TRANSFEREE' | 'ABANDON';
  redoublant: boolean;
  statutBourse: StatutBourse;
}

export interface ResponsableDeEleveVue {
  responsableId: string;
  nom: string;
  prenoms: string;
  telephone: string;
  lien: LienParente;
  contactPrioritaire: boolean;
}

export interface DossierEleveVue {
  eleve: EleveVue;
  responsables: ResponsableDeEleveVue[];
  inscriptions: InscriptionVue[];
}

export interface Page<T> {
  elements: T[];
  page: number;
  taille: number;
  total: number;
  nombrePages: number;
}

export interface RapportImport {
  simulation: boolean;
  lignes: number;
  valides: number;
  importees: number;
  erreurs: { ligne: number; nomComplet: string | null; erreurs: string[] }[];
}

/** Identité masquée (null) tant qu'une invitation n'est pas acceptée. */
export interface EnseignantVue {
  engagementId: string;
  enseignantId: string | null;
  nom: string | null;
  prenoms: string | null;
  telephone: string | null;
  sexe: Sexe | null;
  matriculeFp?: string | null;
  specialite: string | null;
  type: TypeEngagement;
  statut: StatutEngagement;
  debut: string;
  /** Dernier jour de travail : fin de contrat, fin programmée ou fin effective. */
  fin: string | null;
  tauxHoraire?: number | null;
  motifFin?: string | null;
  /** Engagement encore actif dont la fin est fixée à `fin`. */
  finProgrammee?: boolean;
}

/** Matière d'une classe assurée par un enseignant pendant l'année. */
export interface AffectationVue {
  classeId: string;
  classeCode: string;
  matiereId: string;
  matiereCode: string;
  matiereLibelle: string;
  volumeHebdo: number | null;
  volumeTotal: number | null;
  engagementId: string;
}

export interface FicheEnseignantVue {
  enseignant: EnseignantVue;
  anneeId: string | null;
  affectations: AffectationVue[];
  chargeHebdomadaire: number;
}

/** Motifs proposés pour une fin d'engagement (texte libre côté serveur). */
export const MOTIFS_FIN = ['Mutation', 'Démission', 'Retraite', 'Fin de contrat', 'Autre'] as const;

export interface ResultatEngagement {
  enseignant: EnseignantVue;
  invitation: boolean;
  motDePasseTemporaire: string | null;
}

/** Libellés pour l'affichage. */
export const LIBELLE_ETAT_ANNEE: Record<EtatAnnee, string> = {
  PREPARATION: 'En préparation',
  ACTIVE: 'En cours',
  CLOTUREE: 'Clôturée',
  ARCHIVEE: 'Archivée',
};

export const LIBELLE_TYPE_MATIERE: Record<TypeMatiere, string> = {
  GENERALE: 'Générale',
  TECHNIQUE: 'Technique',
  PRATIQUE: 'Pratique',
  MODULE_COMPETENCES: 'Module (compétences)',
};

export const LIBELLE_ROLE: Record<Role, string> = {
  ADMIN_ECOLE: 'Administration',
  CENSEUR: 'Censeur',
  SECRETARIAT: 'Secrétariat',
  INTENDANT: 'Intendance',
  SURVEILLANT: 'Surveillance',
  ENSEIGNANT: 'Enseignant',
  PARENT: 'Parent',
  ELEVE: 'Élève',
};

export const LIBELLE_STATUT_ENGAGEMENT: Record<StatutEngagement, string> = {
  INVITE: 'Invité',
  ACTIF: 'Actif',
  TERMINE: 'Terminé',
  REFUSE: 'Refusé',
};

export type TypeEvaluation = 'DEVOIR' | 'INTERROGATION' | 'COMPOSITION' | 'TP' | 'ATELIER' | 'AUTRE';

/** Suivi des évaluations d'une matière dans une classe (une ligne par matière du programme). */
export interface SuiviEvaluationVue {
  classeId: string;
  classeCode: string;
  niveau: string;
  matiereId: string;
  matiereCode: string;
  matiereLibelle: string;
  engagementId: string | null;
  enseignant: string | null;
  evaluations: number;
  parType: Partial<Record<TypeEvaluation, number>>;
  derniere: string | null;
  notesSaisies: number;
  notesAttendues: number;
}

// Statistiques d'une année (v0.21)

export interface Compte {
  garcons: number;
  filles: number;
}

export type Decision = 'ADMIS' | 'REDOUBLE' | 'EXCLU' | 'ORIENTE' | 'CERTIFIE' | 'NON_CERTIFIE' | 'EN_ATTENTE_EXAMEN';

export const LIBELLE_DECISION: Record<Decision, string> = {
  ADMIS: 'Admis',
  REDOUBLE: 'Redoublent',
  EXCLU: 'Exclus',
  ORIENTE: 'Orientés',
  CERTIFIE: 'Certifiés',
  NON_CERTIFIE: 'Non certifiés',
  EN_ATTENTE_EXAMEN: "En attente de l'examen",
};

export interface EffectifNiveauVue {
  niveau: string;
  classes: number;
  effectif: Compte;
  redoublants: Compte;
}

export interface AgeVue {
  niveau: string;
  age: number;
  effectif: Compte;
}

export interface BourseFiliereVue {
  filiere: string;
  boursiers: Compte;
  semiBoursiers: Compte;
  nonBoursiers: Compte;
}

export interface PersonnelStatVue {
  titulaires: Compte;
  vacataires: Compte;
  sexeNonRenseigne: number;
  administratif: Partial<Record<Role, number>>;
}

export interface RecouvrementClasseVue {
  classe: string;
  duFamilles: number;
  payeFamilles: number;
  tauxFamilles: number | null;
  duOrganismes: number;
  payeOrganismes: number;
  tauxOrganismes: number | null;
}

export interface ResultatNiveauVue {
  niveau: string;
  decides: Compte;
  parDecision: Partial<Record<Decision, Compte>>;
  tauxAdmission: number | null;
}

export interface RapportStatistiques {
  etablissement: string;
  annee: AnneeVue;
  produitLe: string;
  effectifTotal: Compte;
  classes: number;
  effectifs: EffectifNiveauVue[];
  ages: AgeVue[];
  bourses: BourseFiliereVue[];
  personnel: PersonnelStatVue;
  recouvrement: RecouvrementClasseVue[];
  recouvrementTotal: RecouvrementClasseVue;
  resultats: ResultatNiveauVue[];
}
