/** Objets échangés avec l'API (mêmes noms que côté serveur). */

export type Role =
  | 'ADMIN_ECOLE'
  | 'CENSEUR'
  | 'CHEF_TRAVAUX'
  | 'SECRETARIAT'
  | 'INTENDANT'
  | 'SURVEILLANT'
  | 'ENSEIGNANT'
  | 'PARENT'
  | 'ELEVE';

export interface EtablissementAccessible {
  id: string;
  code: string;
  nom: string;
  roles: Role[];
}

export interface ReponseConnexion {
  jetonAcces: string | null;
  jetonSelection: string | null;
  expireDansSecondes: number;
  selectionRequise: boolean;
  etablissementActif: EtablissementAccessible | null;
  etablissements: EtablissementAccessible[];
  superAdmin: boolean;
  doitChangerMotDePasse: boolean;
}

export interface ProfilConnecte {
  id: string;
  nom: string;
  prenoms: string;
  telephone: string;
  superAdmin: boolean;
  etablissementId: string | null;
  etablissementCode: string | null;
  roles: Role[];
  doitChangerMotDePasse: boolean;
}

/** Erreur au format Problem Details renvoyée par le serveur. */
export interface Probleme {
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
}

export interface Affectation {
  classeId: string;
  classeCode: string;
  matiereId: string;
  matiereCode: string;
  matiereLibelle: string;
  volumeHebdo: number | null;
  volumeTotal: number | null;
  engagementId: string;
}

/** Engagement de l'enseignant connecté dans l'établissement actif (extrait de sa fiche). */
export type TypeEngagementEnseignant = 'TITULAIRE' | 'VACATAIRE';

export interface EngagementEnseignant {
  type: TypeEngagementEnseignant;
  statut: string;
  /** Dernier jour de travail (fin programmée ou fin de contrat), null si aucune. */
  fin: string | null;
  motifFin: string | null;
  finProgrammee?: boolean;
}

export interface FicheEnseignant {
  enseignant?: EngagementEnseignant;
  anneeId: string;
  affectations: Affectation[];
}

export interface Inscription {
  id: string;
  eleveId: string;
  matricule: string | null;
  nom: string;
  prenoms: string;
  sexe: 'M' | 'F' | null;
  classeId: string;
  statut: string;
  redoublant: boolean;
}

export type TypeMarque = 'ABSENCE' | 'RETARD';

export interface Marque {
  inscriptionId: string;
  type: TypeMarque;
  minutesRetard: number | null;
}

export interface DonneesAppel {
  idClient: string;
  classeId: string;
  matiereId: string;
  /** Date locale AAAA-MM-JJ. */
  date: string;
  /** HH:MM */
  heureDebut: string;
  heureFin: string;
  /** Instant ISO de la saisie sur l'appareil. */
  saisiLe: string;
  marques: Marque[];
}

export type StatutAccuse = 'ENREGISTRE' | 'DEJA_RECU' | 'MODIFIE' | 'REFUSE';

export interface AccuseAppel {
  idClient: string;
  statut: StatutAccuse;
  code: string | null;
  message: string | null;
  absents: number;
  retards: number;
  inscriptionsIgnorees: string[] | null;
}
