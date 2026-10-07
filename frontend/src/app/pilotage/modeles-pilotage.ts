/** Tableau de bord de pilotage (v0.37) : uniquement des nombres. */

export interface Compte {
  garcons: number;
  filles: number;
  total: number;
}

export interface Personnes {
  hommes: number;
  femmes: number;
  total: number;
}

export interface NiveauPilotage {
  niveau: string;
  classes: number;
  eleves: Compte;
  redoublants: Compte;
  decides: Compte;
  admis: Compte;
  tauxAdmission: number | null;
}

/** Examen de fin d'études ; taux = admis / résultats connus. */
export interface ExamenPilotage {
  examen: string;
  candidats: Compte;
  resultats: Compte;
  admis: Compte;
  taux: number | null;
  tauxGarcons: number | null;
  tauxFilles: number | null;
}

export interface PeriodePilotage {
  decoupage: string;
  ordre: number;
  libelle: string;
  bulletins: Compte;
  moyenne: number | null;
  admis: Compte;
  taux: number | null;
}

export interface Utilisation {
  comptes: number;
  actifs: number;
  enseignants: number;
  enseignantsActifs: number;
  derniereActivite: string | null;
}

export interface Indicateurs {
  etablissements: number;
  classes: number;
  eleves: Compte;
  redoublants: Compte;
  enseignants: Personnes;
  titulaires: Personnes;
  vacataires: Personnes;
  elevesParEnseignant: number | null;
  decides: Compte;
  admis: Compte;
  tauxAdmission: number | null;
  niveaux: NiveauPilotage[];
  examens: ExamenPilotage[];
  periodes: PeriodePilotage[];
  utilisation: Utilisation;
}

export interface LigneDirection {
  id: string;
  code: string;
  nom: string;
  indicateurs: Indicateurs;
}

export interface LigneEtablissement {
  id: string;
  code: string;
  nom: string;
  statut: 'ACTIF' | 'SUSPENDU';
  directionId: string;
  direction: string;
  /** Ligne « directions » dont dépend l'établissement */
  sousDirectionId: string | null;
  indicateurs: Indicateurs;
}

export interface TableauPilotage {
  perimetre: { type: 'PAYS' | 'DIRECTION'; id: string; nom: string; niveau: string | null; chemin: string };
  parent: { type: 'PAYS' | 'DIRECTION'; id: string; nom: string } | null;
  annees: string[];
  annee: string | null;
  debutUtilisation: string;
  finUtilisation: string;
  synthese: Indicateurs;
  niveauDirections: string | null;
  directions: LigneDirection[];
  etablissements: LigneEtablissement[];
  produitLe: string;
}

export interface FiltrePilotage {
  pays?: string | null;
  direction?: string | null;
  annee?: string | null;
}
