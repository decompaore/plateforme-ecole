import { StatutBourse } from '../admin/modeles-admin';

/** Objets de l'API Scolarité (montants en FCFA, entiers). */

export type MoyenPaiement = 'ESPECES' | 'ORANGE_MONEY' | 'MOOV_MONEY' | 'TELECEL_MONEY' | 'VIREMENT' | 'CHEQUE';
export type Payeur = 'FAMILLE' | 'ORGANISME';
export type Portee = 'TOUTES' | 'FILIERES' | 'NIVEAUX' | 'CLASSES';
export type TypeOrganisme = 'ETAT' | 'COLLECTIVITE' | 'ONG' | 'ENTREPRISE';

export const MOYENS: MoyenPaiement[] = ['ESPECES', 'ORANGE_MONEY', 'MOOV_MONEY', 'TELECEL_MONEY', 'VIREMENT', 'CHEQUE'];

export const LIBELLE_MOYEN: Record<MoyenPaiement, string> = {
  ESPECES: 'Espèces',
  ORANGE_MONEY: 'Orange Money',
  MOOV_MONEY: 'Moov Money',
  TELECEL_MONEY: 'Telecel Money',
  VIREMENT: 'Virement',
  CHEQUE: 'Chèque',
};

export const LIBELLE_PORTEE: Record<Portee, string> = {
  TOUTES: 'Tout l’établissement',
  FILIERES: 'Certaines filières',
  NIVEAUX: 'Certains niveaux',
  CLASSES: 'Certaines classes',
};

export const LIBELLE_TYPE_ORGANISME: Record<TypeOrganisme, string> = {
  ETAT: 'État',
  COLLECTIVITE: 'Collectivité',
  ONG: 'ONG',
  ENTREPRISE: 'Entreprise',
};

export const LIBELLE_BOURSE: Record<StatutBourse, string> = {
  BOURSIER: 'Boursier',
  SEMI_BOURSIER: 'Semi-boursier',
  NON_BOURSIER: 'Non boursier',
};

export interface ParametresScolarite {
  tauxBoursier: number;
  tauxSemiBoursier: number;
  delaiRelanceJours: number;
  relancesAutomatiques: boolean | null;
}

export interface OrganismeVue {
  id: string;
  nom: string;
  type: TypeOrganisme;
  telephone: string | null;
  actif: boolean;
}

export interface TrancheVue {
  numero: number;
  dateLimite: string;
  montant: number;
}

export interface FraisVue {
  id: string;
  anneeId: string;
  libelle: string;
  montant: number;
  obligatoire: boolean;
  couvertParBourse: boolean;
  portee: Portee;
  filieres: string[];
  niveaux: string[];
  classes: string[];
  tranches: TrancheVue[];
}

export interface DonneesFrais {
  libelle: string;
  montant: number;
  obligatoire: boolean;
  couvertParBourse: boolean;
  portee: Portee;
  filieres: string[];
  niveaux: string[];
  classes: string[];
  tranches: { dateLimite: string; montant: number }[];
}

export interface PriseEnChargeVue {
  inscriptionId: string;
  organismeId: string;
  organisme: string;
  taux: number | null;
  referenceDecision: string | null;
  dateDecision: string | null;
}

export interface ExonerationVue {
  fraisId: string;
  frais: string;
  montant: number;
  motif: string | null;
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
  moyen: MoyenPaiement;
  payeur: Payeur;
  organismeId: string | null;
  referenceExterne: string | null;
  deposant: string | null;
  datePaiement: string;
  enregistreLe: string;
  recuNumero: string | null;
  recuCode: string | null;
  annule: boolean;
  motifAnnulation: string | null;
}

export interface SituationVue {
  inscriptionId: string;
  eleveId: string;
  matricule: string | null;
  nom: string;
  prenoms: string;
  classeCode: string;
  annee: string;
  statutBourse: StatutBourse;
  tauxPriseEnCharge: number | null;
  organisme: string | null;
  total: number;
  exonere: number;
  totalFamille: number;
  totalOrganisme: number;
  payeFamille: number;
  payeOrganisme: number;
  resteFamille: number;
  resteOrganisme: number;
  retardFamille: number;
  retardOrganisme: number;
  avanceFamille: number;
  prochaineEcheance: string | null;
  echeances: EcheanceVue[];
  exonerations: ExonerationVue[];
  paiements: PaiementVue[];
}

export interface DemandePaiement {
  montant: number;
  moyen: MoyenPaiement;
  payeur: Payeur;
  organismeId: string | null;
  referenceExterne: string | null;
  deposant: string | null;
  datePaiement: string | null;
  cleIdempotence: string;
}

export interface SituationResumeVue {
  inscriptionId: string;
  matricule: string | null;
  nom: string;
  prenoms: string;
  statutBourse: StatutBourse;
  totalFamille: number;
  payeFamille: number;
  resteFamille: number;
  retardFamille: number;
  totalOrganisme: number;
  payeOrganisme: number;
  resteOrganisme: number;
  derniereRelance: string | null;
}

export interface EtatClasseVue {
  classeId: string;
  classeCode: string;
  eleves: SituationResumeVue[];
  totalFamille: number;
  payeFamille: number;
  retardFamille: number;
  totalOrganisme: number;
  payeOrganisme: number;
  tauxRecouvrementFamille: number | null;
  tauxRecouvrementOrganisme: number | null;
}

export interface ResultatRelancesVue {
  envoyees: number;
  sansContact: number;
  dejaRelancees: number;
}

export interface JournalVue {
  du: string;
  au: string;
  total: number;
  parMoyen: { moyen: MoyenPaiement; montant: number; nombre: number }[];
  paiements: PaiementVue[];
  eleves: Record<string, { matricule: string | null; nom: string; prenoms: string; classeCode: string }>;
}

export { fcfa } from '../parent/modeles-parent';

/** Lit un montant saisi (« 15 000 », « 15000 ») : entier positif, sinon null. */
export function lireMontant(texte: string): number | null {
  const propre = texte.replace(/[\s.]/g, '');
  if (!/^\d+$/.test(propre)) {
    return null;
  }
  const n = Number(propre);
  return n > 0 && Number.isSafeInteger(n) ? n : null;
}
