/** Objets de l'espace parent (voir API_ELEVES, API_SCOLARITE, API_MOBILE_MONEY, API_BULLETINS). */

export interface EnfantVue {
  eleveId: string;
  matricule: string | null;
  nom: string;
  prenoms: string;
  sexe: 'M' | 'F';
  dateNaissance: string;
  lien: string;
  anneeLibelle: string | null;
  classeCode: string | null;
  statut: 'ACTIVE' | 'TRANSFEREE' | 'ABANDON' | null;
}

export interface EcheanceVue {
  fraisId: string;
  libelle: string;
  numero: number;
  nombreTranches: number;
  dateLimite: string;
  montant: number;
  exoneration: number;
  partOrganisme: number;
  partFamille: number;
  payeFamille: number;
  payeOrganisme: number;
  resteFamille: number;
  resteOrganisme: number;
  enRetard: boolean;
}

export interface PaiementVue {
  id: string;
  inscriptionId: string;
  montant: number;
  moyen: 'ESPECES' | 'ORANGE_MONEY' | 'MOOV_MONEY' | 'TELECEL_MONEY' | 'VIREMENT' | 'CHEQUE';
  payeur: 'FAMILLE' | 'ORGANISME';
  datePaiement: string;
  recuNumero: string | null;
  annule: boolean;
}

/** Situation financière d'un enfant pour une année. */
export interface SituationVue {
  inscriptionId: string;
  eleveId: string;
  classeCode: string;
  annee: string;
  organisme: string | null;
  totalFamille: number;
  payeFamille: number;
  resteFamille: number;
  retardFamille: number;
  avanceFamille: number;
  prochaineEcheance: string | null;
  echeances: EcheanceVue[];
  exonerations: { fraisId: string; frais: string; montant: number; motif: string | null }[];
  paiements: PaiementVue[];
}

export type Operateur = 'ORANGE_MONEY' | 'MOOV_MONEY' | 'TELECEL_MONEY';
export type StatutTransaction = 'INITIEE' | 'EN_ATTENTE' | 'CONFIRMEE' | 'ECHOUEE' | 'EXPIREE' | 'A_VERIFIER' | 'REGULARISEE';

export interface TransactionVue {
  id: string;
  inscriptionId: string;
  montant: number;
  operateur: Operateur;
  telephone: string;
  statut: StatutTransaction;
  reference: string;
  message: string | null;
  paiementId: string | null;
  expireLe: string | null;
}

export interface BulletinEleveVue {
  id: string;
  periode: string;
  classe: string;
  moyenne: number | null;
  rang: number | null;
  effectif: number;
  tauxMaitrise: number | null;
  distinction: string | null;
  publieLe: string;
}

/** Une donnée lue sur le serveur, ou sur le téléphone faute de réseau. */
export interface Lecture<T> {
  donnees: T;
  /** Date de la dernière lecture réussie sur le serveur. */
  le: string;
  /** Vrai si le serveur était injoignable : `donnees` vient du téléphone. */
  horsLigne: boolean;
}

export const LIBELLE_OPERATEUR: Record<Operateur, string> = {
  ORANGE_MONEY: 'Orange Money',
  MOOV_MONEY: 'Moov Money',
  TELECEL_MONEY: 'Telecel Money',
};

export const LIBELLE_MOYEN: Record<PaiementVue['moyen'], string> = {
  ESPECES: 'Espèces',
  ORANGE_MONEY: 'Orange Money',
  MOOV_MONEY: 'Moov Money',
  TELECEL_MONEY: 'Telecel Money',
  VIREMENT: 'Virement',
  CHEQUE: 'Chèque',
};

export const LIBELLE_DISTINCTION: Record<string, string> = {
  AUCUNE: '',
  TABLEAU_HONNEUR: "Tableau d'honneur",
  ENCOURAGEMENTS: 'Encouragements',
  FELICITATIONS: 'Félicitations',
  AVERTISSEMENT_TRAVAIL: 'Avertissement (travail)',
  AVERTISSEMENT_CONDUITE: 'Avertissement (conduite)',
  BLAME: 'Blâme',
};

/** « 25 000 FCFA » (espace insécable fine entre les milliers). */
export function fcfa(montant: number): string {
  return `${Math.round(montant).toLocaleString('fr-FR').replace(/\s/g, ' ')} FCFA`;
}

/** « 12,45 » pour une moyenne sur 20. */
export function sur20(n: number | null): string {
  return n === null ? '—' : n.toFixed(2).replace('.', ',');
}
