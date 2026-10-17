/** Professeur principal (v0.49.1) : désignation, dossiers, vérification des notes. */

export type Nature = 'REVENDICATION' | 'MEDIATION';
export type StatutSaisine = 'OUVERTE' | 'TRANSMISE' | 'CLOSE';
export type StatutVerification = 'A_FAIRE' | 'EN_COURS' | 'VALIDEE';
export type StatutReclamation = 'OUVERTE' | 'CORRIGEE' | 'MAINTENUE';

export const LIBELLE_NATURE: Record<Nature, string> = {
  REVENDICATION: 'Revendication des enseignants',
  MEDIATION: 'Médiation élèves – enseignant',
};

export const LIBELLE_STATUT_SAISINE: Record<StatutSaisine, string> = {
  OUVERTE: 'Ouvert',
  TRANSMISE: "Transmis à l'administration",
  CLOSE: 'Clos',
};

export const LIBELLE_VERIFICATION: Record<StatutVerification, string> = {
  A_FAIRE: 'À faire',
  EN_COURS: 'En cours',
  VALIDEE: 'Attestée',
};

export const CLASSE_VERIFICATION: Record<StatutVerification, string> = {
  A_FAIRE: 'absent',
  EN_COURS: '',
  VALIDEE: 'present',
};

export const LIBELLE_RECLAMATION: Record<StatutReclamation, string> = {
  OUVERTE: "En attente de l'enseignant",
  CORRIGEE: 'Note corrigée',
  MAINTENUE: 'Note maintenue',
};

export const CLASSE_RECLAMATION: Record<StatutReclamation, string> = {
  OUVERTE: '',
  CORRIGEE: 'present',
  MAINTENUE: 'absent',
};

export interface EnseignantClasse {
  utilisateurId: string;
  nom: string;
  matieres: string[];
}

export interface EtatVerification {
  periodeId: string;
  periode: string;
  statut: StatutVerification;
  reclamationsOuvertes: number;
}

export interface ClassePrincipale {
  classeId: string;
  classeCode: string;
  niveau: string;
  profilId: string;
  professeurId: string | null;
  professeur: string | null;
  designeLe: string | null;
  enseignants: EnseignantClasse[];
  saisinesOuvertes: number;
  verifications: EtatVerification[];
}

export interface DonneesSaisine {
  nature: Nature;
  objet: string;
  expose: string;
  inscriptions: string[];
  matiereId: string | null;
  enseignantId: string | null;
}

export interface SuiteVue {
  id: string;
  auteur: string;
  qualite: string;
  texte: string;
  creeLe: string;
}

export interface SaisineVue {
  id: string;
  numero: string;
  classeId: string;
  classeCode: string;
  nature: Nature;
  objet: string;
  expose: string;
  auteur: string;
  professeur: string | null;
  eleves: { inscriptionId: string; nom: string }[];
  matiereId: string | null;
  matiere: string | null;
  enseignantId: string | null;
  enseignant: string | null;
  enseignantInforme: boolean;
  statut: StatutSaisine;
  transmiseLe: string | null;
  conclusion: string | null;
  closeLe: string | null;
  closePar: string | null;
  creeLe: string;
  suites: number;
  journal: SuiteVue[] | null;
  droits: { ecrire: boolean; informer: boolean; transmettre: boolean; clore: boolean };
}

export interface ReclamationVue {
  id: string;
  classeId: string;
  classeCode: string;
  periodeId: string;
  periode: string;
  inscriptionId: string;
  eleve: string;
  matiereId: string;
  matiere: string;
  evaluationId: string | null;
  evaluation: string | null;
  noteContestee: number | null;
  motif: string;
  enseignant: string | null;
  enregistreePar: string;
  enregistreeLe: string;
  statut: StatutReclamation;
  noteCorrigee: number | null;
  reponse: string | null;
  reponduePar: string | null;
  reponduLe: string | null;
  peutRepondre: boolean;
}

export interface VerificationVue {
  classeId: string;
  classeCode: string;
  periodeId: string;
  periode: string;
  periodeVerrouillee: boolean;
  professeur: string | null;
  statut: StatutVerification;
  valideeLe: string | null;
  valideePar: string | null;
  observations: string | null;
  reclamations: ReclamationVue[];
  professeurPrincipal: boolean;
  peutValider: boolean;
}

export interface DonneesReclamation {
  periodeId: string;
  inscriptionId: string;
  matiereId: string;
  evaluationId: string | null;
  motif: string;
}

/** Résultats d'une classe pour une période (extrait de l'API des évaluations). */
export interface MoyenneMatiere {
  matiereId: string;
  code: string;
  libelle: string;
  moyenne: number | null;
  notes: number;
  sansNote: boolean;
}

export interface ResultatEleve {
  inscriptionId: string;
  nom: string;
  prenoms: string;
  moyenne: number | null;
  rang: number | null;
  matieres: MoyenneMatiere[];
}

export interface ResultatsPeriode {
  classeId: string;
  periodeId: string;
  moyenneClasse: number | null;
  eleves: ResultatEleve[];
}

export interface EvaluationResume {
  id: string;
  matiereId: string;
  libelle: string;
  date: string;
}

/** « 12,5 » */
export function note(n: number | null | undefined): string {
  return n === null || n === undefined ? '—' : String(Math.round(n * 100) / 100).replace('.', ',');
}
