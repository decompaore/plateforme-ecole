/** Référentiel territorial (v0.35) : pays → ministère → directions → établissements. */

export interface PaysVue {
  id: string;
  code: string;
  nom: string;
  deviseNationale: string | null;
  indicatifTelephone: string;
  longueurNumero: number;
  fuseauHoraire: string;
  monnaie: string;
  langue: string;
  ministeres: number;
  etablissements: number;
}

export type DonneesPays = Omit<PaysVue, 'id' | 'ministeres' | 'etablissements'>;

export interface MinistereVue {
  id: string;
  paysId: string;
  sigle: string;
  nom: string;
  actif: boolean;
  /** Noms des niveaux, du plus haut (ex. Direction régionale) au plus proche des établissements */
  niveaux: string[];
  directions: number;
  etablissements: number;
}

export interface DonneesMinistere {
  sigle: string;
  nom: string;
  actif: boolean;
  niveaux: string[];
}

export interface DirectionVue {
  id: string;
  parentId: string | null;
  rang: number;
  code: string;
  nom: string;
  actif: boolean;
  etablissements: number;
}

export interface DonneesDirection {
  parentId: string | null;
  code: string;
  nom: string;
  actif: boolean;
}

/** Direction avec son chemin complet ; terminale : un établissement peut s'y rattacher. */
export interface DirectionChemin {
  id: string;
  paysId: string;
  ministereId: string;
  rang: number;
  terminale: boolean;
  chemin: string;
  actif: boolean;
}

export interface RapportImportDirections {
  simulation: boolean;
  lignes: number;
  creees: number;
  modifiees: number;
  inchangees: number;
  erreurs: { ligne: number; message: string }[];
}

/** Identité de l'établissement sur ses documents (vue de l'administrateur). */
export interface IdentiteVue {
  nom: string;
  pays: string | null;
  devise: string | null;
  autorites: string[];
  rattache: boolean;
  logo: { type: string; taille: number; modifieLe: string } | null;
}

/** Administrateur pays (v0.36), vu par le super administrateur. */
export interface AdministrateurPaysVue {
  utilisateurId: string;
  nom: string;
  prenoms: string;
  telephone: string;
  actif: boolean;
  nommeLe: string;
  derniereConnexion: string | null;
  verrouilleJusqua: string | null;
  motDePasseProvisoire: boolean;
}

export interface ResultatNomination {
  administrateur: AdministrateurPaysVue;
  motDePasseTemporaire: string | null;
}

/** Compte d'une direction (v0.37) : mêmes informations qu'un administrateur pays. */
export type CompteDirectionVue = AdministrateurPaysVue;

export interface ResultatNominationDirection {
  compte: CompteDirectionVue;
  motDePasseTemporaire: string | null;
}
