/** Commission de recrutement (v0.49) : complément d'effectif. */

export type Qualite = 'PRESIDENT' | 'VICE_PRESIDENT' | 'RAPPORTEUR' | 'MEMBRE';
export type StatutCommission = 'PREPARATION' | 'DELIBEREE' | 'CLOSE';
export type StatutCandidature = 'DEPOSEE' | 'IRRECEVABLE' | 'RETENUE' | 'LISTE_ATTENTE' | 'NON_RETENUE' | 'INSCRITE' | 'DESISTEE';
export type Lien = 'PERE' | 'MERE' | 'TUTEUR' | 'AUTRE';

export const QUALITES: Qualite[] = ['PRESIDENT', 'VICE_PRESIDENT', 'RAPPORTEUR', 'MEMBRE'];
export const LIBELLE_QUALITE: Record<Qualite, string> = {
  PRESIDENT: 'Président',
  VICE_PRESIDENT: 'Vice-président',
  RAPPORTEUR: 'Rapporteur',
  MEMBRE: 'Membre',
};

export const LIBELLE_STATUT_COMMISSION: Record<StatutCommission, string> = {
  PREPARATION: 'En préparation',
  DELIBEREE: 'Délibérée',
  CLOSE: 'Close',
};

export const LIBELLE_STATUT_CANDIDATURE: Record<StatutCandidature, string> = {
  DEPOSEE: 'Déposée',
  IRRECEVABLE: 'Irrecevable',
  RETENUE: 'Retenue',
  LISTE_ATTENTE: "Liste d'attente",
  NON_RETENUE: 'Non retenue',
  INSCRITE: 'Inscrite',
  DESISTEE: 'Désistée',
};

export const CLASSE_STATUT_CANDIDATURE: Record<StatutCandidature, string> = {
  DEPOSEE: '',
  IRRECEVABLE: 'absent',
  RETENUE: 'present',
  LISTE_ATTENTE: '',
  NON_RETENUE: 'absent',
  INSCRITE: 'present',
  DESISTEE: 'absent',
};

export const LIBELLE_LIEN: Record<Lien, string> = { PERE: 'Père', MERE: 'Mère', TUTEUR: 'Tuteur', AUTRE: 'Autre' };

/** « 12,5 » */
export function note(n: number | null | undefined): string {
  return n === null || n === undefined ? '—' : String(Math.round(n * 100) / 100).replace('.', ',');
}

export interface DonneesCommission {
  intitule: string;
  dateSession: string;
  lieu: string | null;
  dateLimiteDepot: string | null;
  criteres: string | null;
  moyenneMinimale: number;
  poidsExamen: number;
  tailleListeAttente: number;
}

export interface CommissionResume {
  id: string;
  numero: string;
  intitule: string;
  dateSession: string;
  statut: StatutCommission;
  places: number;
  candidatures: number;
  retenues: number;
  inscrites: number;
}

export interface MembreCommission {
  id: string;
  qualite: Qualite;
  utilisateurId: string | null;
  nom: string;
  fonction: string;
  present: boolean;
}

export interface MembreSaisi {
  qualite: Qualite;
  utilisateurId: string | null;
  nom: string | null;
  fonction: string;
  present: boolean;
}

export interface ClasseOffre {
  id: string;
  code: string;
  effectifMax: number | null;
  inscrits: number;
}

export interface OffreVue {
  id: string;
  niveau: string;
  filiereId: string;
  filiere: string;
  places: number;
  disponibles: number | null;
  candidatures: number;
  retenues: number;
  listeAttente: number;
  inscrites: number;
  classes: ClasseOffre[];
}

export interface Droits {
  modifier: boolean;
  saisir: boolean;
  deliberer: boolean;
  inscrire: boolean;
  clore: boolean;
}

export interface CommissionVue extends DonneesCommission {
  id: string;
  numero: string;
  anneeId: string;
  annee: string;
  statut: StatutCommission;
  observationsPv: string | null;
  delibereeLe: string | null;
  delibereePar: string | null;
  closeLe: string | null;
  membres: MembreCommission[];
  offres: OffreVue[];
  droits: Droits;
}

export interface DonneesCandidature {
  offreId: string;
  nom: string;
  prenoms: string;
  sexe: 'M' | 'F';
  dateNaissance: string;
  lieuNaissance: string | null;
  responsableNom: string;
  responsablePrenoms: string;
  responsableTelephone: string;
  responsableLien: Lien;
  etablissementOrigine: string;
  classeOrigine: string | null;
  moyenneAnnuelle: number;
  noteExamen: number | null;
  redoublant: boolean;
  observations: string | null;
}

export interface CandidatureVue extends DonneesCandidature {
  id: string;
  numero: string;
  offre: string;
  statut: StatutCandidature;
  motif: string | null;
  score: number | null;
  rang: number | null;
  repechee: boolean;
  inscriptionId: string | null;
  inscriteLe: string | null;
  classeInscription: string | null;
}

export interface LigneClassement {
  candidatureId: string;
  numero: string;
  nom: string;
  prenoms: string;
  sexe: 'M' | 'F';
  moyenneAnnuelle: number;
  noteExamen: number | null;
  score: number | null;
  rang: number | null;
  decision: StatutCandidature;
  motif: string | null;
}

export interface ClassementOffre {
  offreId: string;
  offre: string;
  places: number;
  lignes: LigneClassement[];
}

export interface ResultatDesistement {
  desistee: CandidatureVue;
  repechee: CandidatureVue | null;
}
