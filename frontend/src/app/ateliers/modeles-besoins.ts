import { Role } from '../core/modeles';
import { FiliereCourte, NatureArticle } from './modeles-ateliers';

/** Circuit des besoins, commandes, réception et répartition (v0.26). */

export type TypeCampagne = 'ANNEE_EN_COURS' | 'EXAMENS';
export type StatutCampagne = 'OUVERTE' | 'TRANSMISE' | 'CLOSE';
export type StatutBesoin = 'BROUILLON' | 'TRANSMIS' | 'VALIDE';
export type PasseePar = 'DIRECTION_REGIONALE' | 'ETABLISSEMENT';
export type StatutCommande = 'EN_COURS' | 'LIVREE_PARTIELLEMENT' | 'LIVREE' | 'ANNULEE';
export type StatutLivraison = 'A_REPARTIR' | 'REPARTIE';

/** Rôles qui suivent les campagnes et les commandes (direction des ateliers et intendance). */
export const ROLES_BESOINS: Role[] = ['ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'INTENDANT'];

export const LIBELLE_TYPE_CAMPAGNE: Record<TypeCampagne, string> = {
  ANNEE_EN_COURS: 'Année scolaire en cours',
  EXAMENS: 'Examens de fin d’études',
};

export const LIBELLE_STATUT_CAMPAGNE: Record<StatutCampagne, string> = {
  OUVERTE: 'Ouverte',
  TRANSMISE: 'Transmise à la DR',
  CLOSE: 'Close',
};

export const LIBELLE_STATUT_BESOIN: Record<StatutBesoin, string> = {
  BROUILLON: 'En préparation',
  TRANSMIS: 'Transmis',
  VALIDE: 'Validé',
};

export const CLASSE_STATUT_BESOIN: Record<StatutBesoin, string> = {
  BROUILLON: '',
  TRANSMIS: 'absent',
  VALIDE: 'present',
};

export const LIBELLE_PASSEE_PAR: Record<PasseePar, string> = {
  DIRECTION_REGIONALE: 'Direction régionale',
  ETABLISSEMENT: 'Établissement (intendance)',
};

export const LIBELLE_STATUT_COMMANDE: Record<StatutCommande, string> = {
  EN_COURS: 'En attente de livraison',
  LIVREE_PARTIELLEMENT: 'Livrée en partie',
  LIVREE: 'Livrée',
  ANNULEE: 'Annulée',
};

export const LIBELLE_STATUT_LIVRAISON: Record<StatutLivraison, string> = {
  A_REPARTIR: 'À répartir',
  REPARTIE: 'Répartie',
};

export interface DemandeCampagne {
  anneeId: string | null;
  type: TypeCampagne;
  libelle: string | null;
  dateLimite: string | null;
  observations: string | null;
}

export interface CampagneResumeVue {
  id: string;
  anneeId: string;
  type: TypeCampagne;
  libelle: string;
  dateLimite: string | null;
  statut: StatutCampagne;
  ouverteLe: string;
  transmiseLe: string | null;
  ateliers: number;
  transmis: number;
  valides: number;
  montant: number;
  commandes: number;
}

export interface BesoinResumeVue {
  id: string;
  atelierId: string;
  atelierCode: string;
  atelierNom: string;
  filieres: FiliereCourte[];
  statut: StatutBesoin;
  lignes: number;
  montant: number;
  transmisLe: string | null;
  responsable: string | null;
}

export interface LigneConsolideeVue {
  articleId: string;
  code: string;
  designation: string;
  nature: NatureArticle;
  unite: string;
  quantite: number;
  prixUnitaire: number | null;
  montant: number;
  commandee: number;
  livree: number;
}

export interface FiliereConsolideeVue {
  filiere: string;
  lignes: LigneConsolideeVue[];
  montant: number;
}

export interface CommandeResumeVue {
  id: string;
  reference: string;
  fournisseur: string;
  passeePar: PasseePar;
  dateCommande: string;
  statut: StatutCommande;
  montant: number;
  lignes: number;
}

export interface CampagneVue {
  id: string;
  anneeId: string;
  type: TypeCampagne;
  libelle: string;
  dateLimite: string | null;
  statut: StatutCampagne;
  observations: string | null;
  ouverteLe: string;
  transmiseLe: string | null;
  closeLe: string | null;
  besoins: BesoinResumeVue[];
  filieres: FiliereConsolideeVue[];
  totaux: LigneConsolideeVue[];
  montant: number;
  commandes: CommandeResumeVue[];
  /** Direction : ouvre, arbitre, valide, transmet, réceptionne et répartit. */
  gerer: boolean;
  /** Direction ou intendance : enregistre les commandes. */
  commander: boolean;
}

export interface BesoinAtelierResumeVue {
  id: string;
  campagneId: string;
  campagne: string;
  type: TypeCampagne;
  statutCampagne: StatutCampagne;
  dateLimite: string | null;
  statut: StatutBesoin;
  lignes: number;
  montant: number;
}

export interface DroitsBesoin {
  proposer: boolean;
  modifier: boolean;
  transmettre: boolean;
  arbitrer: boolean;
}

export interface LigneBesoinVue {
  articleId: string;
  code: string;
  designation: string;
  nature: NatureArticle;
  unite: string;
  specifications: string | null;
  normes: string | null;
  photo: boolean;
  quantiteDemandee: number;
  justification: string | null;
  proposePar: string | null;
  quantiteRetenue: number | null;
  prixUnitaire: number | null;
  montant: number;
}

export interface BesoinVue {
  id: string;
  campagneId: string;
  campagne: string;
  type: TypeCampagne;
  statutCampagne: StatutCampagne;
  dateLimite: string | null;
  atelierId: string;
  atelierCode: string;
  atelierNom: string;
  statut: StatutBesoin;
  transmisLe: string | null;
  transmisPar: string | null;
  valideLe: string | null;
  commentaire: string | null;
  lignes: LigneBesoinVue[];
  montant: number;
  droits: DroitsBesoin;
}

export interface SaisieLigneCommande {
  articleId: string;
  quantite: number;
  prixUnitaire: number | null;
}

export interface DemandeCommande {
  reference: string;
  fournisseur: string;
  passeePar: PasseePar;
  dateCommande: string | null;
  observations: string | null;
  lignes: SaisieLigneCommande[];
}

export interface LigneCommandeVue {
  articleId: string;
  code: string;
  designation: string;
  nature: NatureArticle;
  unite: string;
  quantite: number;
  prixUnitaire: number;
  montant: number;
  recue: number;
  conforme: number;
  reste: number;
}

export interface LivraisonResumeVue {
  id: string;
  dateReception: string;
  bonLivraison: string | null;
  statut: StatutLivraison;
  lignes: number;
  nonConformes: number;
}

export interface CommandeVue {
  id: string;
  campagneId: string;
  campagne: string;
  reference: string;
  fournisseur: string;
  passeePar: PasseePar;
  dateCommande: string;
  statut: StatutCommande;
  observations: string | null;
  motifAnnulation: string | null;
  lignes: LigneCommandeVue[];
  montant: number;
  livraisons: LivraisonResumeVue[];
  gerer: boolean;
  recevoir: boolean;
}

export interface SaisieLigneLivraison {
  articleId: string;
  quantiteRecue: number;
  quantiteConforme: number | null;
  motifNonConformite: string | null;
}

export interface DemandeLivraison {
  dateReception: string | null;
  bonLivraison: string | null;
  observations: string | null;
  lignes: SaisieLigneLivraison[];
}

export interface AtelierCourt {
  id: string;
  code: string;
  nom: string;
}

export interface PartAtelierVue {
  atelierId: string;
  atelierCode: string;
  /** Besoin retenu pour l'atelier dans la campagne. */
  retenu: number;
  /** Part proposée au prorata des besoins retenus. */
  proposee: number;
  /** Part enregistrée (null tant que rien n'est enregistré). */
  quantite: number | null;
}

export interface LigneLivraisonVue {
  articleId: string;
  code: string;
  designation: string;
  nature: NatureArticle;
  unite: string;
  recue: number;
  conforme: number;
  motifNonConformite: string | null;
  repartition: PartAtelierVue[];
}

export interface LivraisonVue {
  id: string;
  commandeId: string;
  commandeReference: string;
  fournisseur: string;
  campagneId: string;
  dateReception: string;
  bonLivraison: string | null;
  observations: string | null;
  statut: StatutLivraison;
  recuePar: string | null;
  repartieLe: string | null;
  lignes: LigneLivraisonVue[];
  ateliers: AtelierCourt[];
  gerer: boolean;
}

export interface SaisieRepartition {
  articleId: string;
  atelierId: string;
  quantite: number;
}

/** Contrôle d'une ligne de réception avant l'envoi. */
export function erreurReception(l: { designation: string; recue: number | null; conforme: number | null; motif: string; reste: number }): string | null {
  if (l.recue === null) {
    return null;
  }
  const conforme = l.conforme ?? l.recue;
  if (conforme > l.recue) {
    return `${l.designation} : la quantité conforme dépasse la quantité reçue.`;
  }
  if (conforme < l.recue && !l.motif.trim()) {
    return `${l.designation} : indiquez pourquoi une partie n’est pas conforme (spécifications, normes, état).`;
  }
  if (conforme > l.reste) {
    return `${l.designation} : ${conforme} conforme(s) pour ${l.reste} restant(s) à livrer.`;
  }
  return null;
}
