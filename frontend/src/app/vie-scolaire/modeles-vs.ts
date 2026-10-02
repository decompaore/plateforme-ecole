/** Objets de l'API Vie scolaire et Absences (voir docs/API_VIE_SCOLAIRE.md et docs/API_ABSENCES.md). */

export type TypeAbsence = 'ABSENCE' | 'RETARD';
export type TypeJustificatif = 'MALADIE' | 'FAMILLE' | 'CONVOCATION' | 'AUTRE';
export type TypeIncident = 'RETARD' | 'AVERTISSEMENT' | 'BLAME' | 'EXCLUSION_TEMPORAIRE';
export type StatutConvocation = 'PREVUE' | 'HONOREE' | 'NON_HONOREE' | 'ANNULEE';

export interface CreneauVue {
  appelId: string;
  heureDebut: string;
  heureFin: string;
  type: TypeAbsence;
  minutesRetard: number | null;
  /** Discipline du cours ; null pour un appel général (sans matière). */
  matiereId: string | null;
  matiereCode: string | null;
  matiereLibelle: string | null;
}

/** Élève absent ou en retard un jour donné (liste de travail de la vie scolaire). */
export interface EleveDuJourVue {
  inscriptionId: string;
  eleveId: string | null;
  matricule: string | null;
  nom: string;
  prenoms: string;
  classeId: string;
  classeCode: string | null;
  absences: number;
  retards: number;
  justifiee: boolean;
  creneaux: CreneauVue[];
}

export interface AbsenceVue {
  id: string;
  appelId: string;
  inscriptionId: string;
  date: string;
  heureDebut: string;
  heureFin: string;
  type: TypeAbsence;
  minutesRetard: number | null;
  justifiee: boolean;
  matiereId: string | null;
  matiereCode: string | null;
  matiereLibelle: string | null;
}

/** Absences d'une classe dans une discipline (null : appels généraux). */
export interface AbsencesParMatiereVue {
  matiereId: string | null;
  matiereCode: string | null;
  matiereLibelle: string | null;
  absences: number;
  heures: number;
  heuresNonJustifiees: number;
  eleves: number;
  retards: number;
}

/** Synthèse d'un élève de la classe sur une période. */
export interface SyntheseEleveVue {
  inscriptionId: string;
  nom: string;
  prenoms: string;
  absences: number;
  absencesJustifiees: number;
  retards: number;
  heuresAbsence: number;
  heuresNonJustifiees: number;
}

export interface JustificatifVue {
  id: string;
  inscriptionId: string;
  du: string;
  au: string;
  type: TypeJustificatif;
  motif: string | null;
}

export interface IncidentVue {
  id: string;
  inscriptionId: string;
  type: TypeIncident;
  date: string;
  motif: string;
  minutesRetard: number | null;
  debutExclusion: string | null;
  finExclusion: string | null;
  joursExclusion: number | null;
  famillePrevenue: boolean;
  saisiLe: string;
  annule: boolean;
  motifAnnulation: string | null;
}

export interface ConvocationVue {
  id: string;
  inscriptionId: string;
  nom: string;
  prenoms: string;
  classe: string;
  rendezVous: string;
  motif: string;
  statut: StatutConvocation;
  compteRendu: string | null;
  incidentId: string | null;
}

export interface SyntheseVue {
  retards: number;
  minutesRetard: number;
  avertissements: number;
  blames: number;
  exclusions: number;
  joursExclusion: number;
  convocations: number;
}

export interface HistoriqueVue {
  inscriptionId: string;
  nom: string;
  prenoms: string;
  classe: string;
  annee: string;
  synthese: SyntheseVue;
  incidents: IncidentVue[];
  convocations: ConvocationVue[];
}

export interface SaisieIncident {
  type: TypeIncident;
  date: string | null;
  motif: string;
  minutesRetard: number | null;
  debutExclusion: string | null;
  joursExclusion: number | null;
  prevenirFamille: boolean;
}

export const LIBELLE_JUSTIFICATIF: Record<TypeJustificatif, string> = {
  MALADIE: 'Maladie',
  FAMILLE: 'Raison familiale',
  CONVOCATION: 'Convocation',
  AUTRE: 'Autre',
};

export const LIBELLE_INCIDENT: Record<TypeIncident, string> = {
  RETARD: "Retard à l'entrée",
  AVERTISSEMENT: 'Avertissement',
  BLAME: 'Blâme',
  EXCLUSION_TEMPORAIRE: 'Exclusion temporaire',
};

/** SMS à la famille proposé par défaut (le serveur applique la même règle). */
export const SMS_PAR_DEFAUT: Record<TypeIncident, boolean> = {
  RETARD: false,
  AVERTISSEMENT: true,
  BLAME: true,
  EXCLUSION_TEMPORAIRE: true,
};

/** Sanctions réservées à la direction (censeur, administrateur). */
export const RESERVE_DIRECTION: TypeIncident[] = ['BLAME', 'EXCLUSION_TEMPORAIRE'];

export const LIBELLE_CONVOCATION: Record<StatutConvocation, string> = {
  PREVUE: 'Prévue',
  HONOREE: 'Honorée',
  NON_HONOREE: 'Non honorée',
  ANNULEE: 'Annulée',
};

/** « 2026-10-15T10:00 » → « 15/10/2026 à 10h00 ». */
export function dateHeure(iso: string): string {
  const [jour, h = '00:00'] = iso.split('T');
  const [a, m, j] = jour.split('-');
  return `${j}/${m}/${a} à ${heure(h)}`;
}

/** « 08:00:00 » → « 08h00 ». */
export function heure(h: string): string {
  return h.slice(0, 5).replace(':', 'h');
}

/** Nom de la discipline à afficher : « Mathématiques », ou « appel général » sans matière. */
export function discipline(x: { matiereLibelle: string | null }): string {
  return x.matiereLibelle ?? 'appel général';
}

/** Heures avec une décimale, à la française : 3,5 h. */
export function heures(h: number): string {
  return `${String(Math.round(h * 10) / 10).replace('.', ',')} h`;
}
