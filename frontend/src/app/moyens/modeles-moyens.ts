/** Besoins et dotations (v0.48) : états des besoins des établissements, remontée, dotations. */

export type Categorie = 'INFRASTRUCTURE' | 'PERSONNEL' | 'EQUIPEMENT_OUTILS' | 'MATIERE';
export type Domaine = 'GENERAL' | 'TECHNIQUE';
export type TypeInstance = 'PAYS' | 'DIRECTION' | 'ETABLISSEMENT';

export const CATEGORIES: Categorie[] = ['INFRASTRUCTURE', 'PERSONNEL', 'EQUIPEMENT_OUTILS', 'MATIERE'];

export const LIBELLE_CATEGORIE: Record<Categorie, string> = {
  INFRASTRUCTURE: 'Infrastructures',
  PERSONNEL: 'Personnel',
  EQUIPEMENT_OUTILS: 'Équipements et outils',
  MATIERE: 'Matières',
};

export const LIBELLE_DOMAINE: Record<Domaine, string> = {
  GENERAL: 'Général (censeur)',
  TECHNIQUE: 'Technique (chef des travaux)',
};

export const LIBELLE_PRIORITE: Record<number, string> = { 1: 'Urgente', 2: 'Normale', 3: 'Souhaitable' };

/** Unité proposée par catégorie (modifiable). */
export const UNITE_PAR_DEFAUT: Record<Categorie, string> = {
  INFRASTRUCTURE: 'salle',
  PERSONNEL: 'poste',
  EQUIPEMENT_OUTILS: 'pièce',
  MATIERE: 'unité',
};

/** « 12 », « 2,5 » : quantités sans zéros inutiles. */
export function quantite(q: number | null | undefined): string {
  if (q === null || q === undefined) {
    return '—';
  }
  return String(Math.round(q * 100) / 100).replace('.', ',');
}

/** « 7 500 000 F » */
export function montant(v: number | null | undefined): string {
  if (v === null || v === undefined) {
    return '—';
  }
  return `${Math.round(v).toLocaleString('fr-FR').replace(/ | /g, ' ')} F`;
}

// ---------------------------------------------------------------- établissement

export type StatutEtat = 'BROUILLON' | 'TRANSMIS' | 'RENVOYE';

export const LIBELLE_STATUT_ETAT: Record<StatutEtat, string> = {
  BROUILLON: 'En préparation',
  TRANSMIS: 'Transmis à la direction',
  RENVOYE: 'Renvoyé pour correction',
};

export interface DonneesLigne {
  categorie: Categorie;
  domaine: Domaine;
  designation: string;
  unite: string;
  quantite: number;
  priorite: number;
  justification: string | null;
}

export interface LigneEtatVue extends DonneesLigne {
  id: string;
  proposePar: string | null;
  modifieeLe: string;
  peutModifier: boolean;
}

export interface AnneeBesoins {
  id: string;
  libelle: string;
  active: boolean;
}

export interface EtatVue {
  id: string | null;
  anneeId: string;
  annee: string;
  annees: AnneeBesoins[];
  statut: StatutEtat;
  transmisLe: string | null;
  transmisPar: string | null;
  direction: string | null;
  renvoyeLe: string | null;
  motifRenvoi: string | null;
  observations: string | null;
  modifiable: boolean;
  peutTransmettre: boolean;
  domaines: Domaine[];
  lignes: LigneEtatVue[];
}

// ---------------------------------------------------------------- remontée

export interface InstanceVue {
  type: TypeInstance;
  id: string;
  nom: string;
  chemin: string | null;
}

export type StatutEtablissement = 'NON_TRANSMIS' | 'EN_COURS' | 'RECU' | 'RENVOYE';
export type StatutTransmission = 'NON_TRANSMISE' | 'TRANSMISE' | 'RENVOYEE';

export const LIBELLE_STATUT_ETABLISSEMENT: Record<StatutEtablissement, string> = {
  NON_TRANSMIS: 'Non transmis',
  EN_COURS: 'En attente',
  RECU: 'Reçu',
  RENVOYE: 'Renvoyé',
};

export const CLASSE_STATUT_ETABLISSEMENT: Record<StatutEtablissement, string> = {
  NON_TRANSMIS: 'absent',
  EN_COURS: '',
  RECU: 'present',
  RENVOYE: 'absent',
};

export const LIBELLE_STATUT_TRANSMISSION: Record<StatutTransmission, string> = {
  NON_TRANSMISE: 'Non transmis',
  TRANSMISE: 'Transmis',
  RENVOYEE: 'Renvoyé',
};

export interface TransmissionVue {
  statut: StatutTransmission;
  transmiseLe: string | null;
  transmisePar: string | null;
  observations: string | null;
  renvoyeeLe: string | null;
  motifRenvoi: string | null;
}

export interface EtablissementBesoins {
  id: string;
  code: string;
  nom: string;
  direction: string;
  statut: StatutEtablissement;
  etape: string | null;
  remonteeId: string | null;
  transmiseLe: string | null;
  lignes: number;
  motifRenvoi: string | null;
}

export interface DirectionBesoins {
  id: string;
  nom: string;
  statut: StatutTransmission;
  transmiseLe: string | null;
  etablissements: number;
  recues: number;
  motifRenvoi: string | null;
  peutRenvoyer: boolean;
}

export interface LigneConsolidee {
  categorie: Categorie;
  designation: string;
  unite: string;
  demandee: number;
  retenue: number;
  etablissements: number;
  priorite: number;
}

export interface VueBesoins {
  perimetre: InstanceVue;
  annee: string;
  annees: string[];
  destination: string | null;
  transmission: TransmissionVue | null;
  peutArbitrer: boolean;
  peutTransmettre: boolean;
  etablissements: EtablissementBesoins[];
  directions: DirectionBesoins[];
  consolidation: LigneConsolidee[];
}

export interface ArbitrageVue {
  niveau: TypeInstance;
  instance: string;
  quantite: number;
  observation: string | null;
}

export interface LigneRemonteeVue {
  id: string;
  categorie: Categorie;
  domaine: Domaine;
  designation: string;
  unite: string;
  quantite: number;
  priorite: number;
  justification: string | null;
  arbitrages: ArbitrageVue[];
  maximum: number;
  retenue: number;
  observation: string | null;
  arbitreIci: boolean;
}

export interface RemonteeVue {
  id: string;
  etablissementId: string;
  etablissement: string;
  direction: string;
  annee: string;
  statut: StatutEtablissement;
  transmiseLe: string | null;
  transmisePar: string | null;
  observations: string | null;
  motifRenvoi: string | null;
  niveau: string;
  peutArbitrer: boolean;
  peutRenvoyer: boolean;
  lignes: LigneRemonteeVue[];
}

export interface LigneArbitree {
  ligneId: string;
  quantiteRetenue: number | null;
  observation: string | null;
}

export interface FiltreBesoins {
  direction?: string | null;
  pays?: string | null;
  annee?: string | null;
}

// ---------------------------------------------------------------- dotations

export type StatutDotation = 'BROUILLON' | 'ENVOYEE' | 'RECUE';

export const LIBELLE_STATUT_DOTATION: Record<StatutDotation, string> = {
  BROUILLON: 'En préparation',
  ENVOYEE: 'Envoyée',
  RECUE: 'Réception accusée',
};

export const CLASSE_STATUT_DOTATION: Record<StatutDotation, string> = {
  BROUILLON: '',
  ENVOYEE: '',
  RECUE: 'present',
};

export interface DotationResume {
  id: string;
  reference: string;
  annee: string;
  emettrice: InstanceVue;
  destinataire: InstanceVue;
  origineReference: string | null;
  objet: string;
  statut: StatutDotation;
  lignes: number;
  valeur: number | null;
  envoyeeLe: string | null;
  recueLe: string | null;
}

export interface LigneDotationVue {
  id: string;
  ligneOrigineId: string | null;
  categorie: Categorie;
  designation: string;
  unite: string;
  quantite: number;
  valeurUnitaire: number | null;
  quantiteRecue: number | null;
  quantiteConforme: number | null;
  observation: string | null;
  repartie: number;
  disponible: number;
}

export interface DotationVue {
  id: string;
  reference: string;
  annee: string;
  emettrice: InstanceVue;
  destinataire: InstanceVue;
  origineId: string | null;
  origineReference: string | null;
  objet: string;
  statut: StatutDotation;
  creeeLe: string;
  envoyeeLe: string | null;
  envoyeePar: string | null;
  recueLe: string | null;
  accuseePar: string | null;
  observationsReception: string | null;
  valeur: number | null;
  peutModifier: boolean;
  peutAccuser: boolean;
  peutRepartir: boolean;
  lignes: LigneDotationVue[];
  repartitions: DotationResume[];
}

export interface Dotations {
  instance: InstanceVue;
  annee: string | null;
  annees: string[];
  recues: DotationResume[];
  emises: DotationResume[];
  destinataires: InstanceVue[];
  peutDoter: boolean;
}

export interface LigneSaisie {
  ligneOrigineId: string | null;
  categorie: Categorie | null;
  designation: string | null;
  unite: string | null;
  quantite: number;
  valeurUnitaire: number | null;
}

export interface DonneesDotation {
  annee: string;
  destinataireType: TypeInstance;
  destinataireId: string;
  origineId: string | null;
  objet: string;
  lignes: LigneSaisie[];
}

export interface LigneRecue {
  ligneId: string;
  quantiteRecue: number | null;
  quantiteConforme: number | null;
  observation: string | null;
}

export interface Reception {
  observations: string | null;
  lignes: LigneRecue[];
}
