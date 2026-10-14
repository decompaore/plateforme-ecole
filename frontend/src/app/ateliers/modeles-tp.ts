/** Travaux pratiques dans les ateliers (v0.47.2). */

export type StatutSeanceTp = 'DEMANDEE' | 'PLANIFIEE' | 'REFUSEE' | 'REALISEE' | 'ANNULEE';

export const LIBELLE_STATUT_TP: Record<StatutSeanceTp, string> = {
  DEMANDEE: 'demandée',
  PLANIFIEE: 'planifiée',
  REFUSEE: 'refusée',
  REALISEE: 'réalisée',
  ANNULEE: 'annulée',
};

export const CLASSE_STATUT_TP: Record<StatutSeanceTp, string> = {
  DEMANDEE: 'attention',
  PLANIFIEE: 'present',
  REFUSEE: 'absent',
  REALISEE: 'present',
  ANNULEE: '',
};

export interface LigneBesoinTpVue {
  articleId: string;
  code: string;
  designation: string;
  unite: string;
  demandee: number;
  accordee: number | null;
  aAcheter: number | null;
  servie: number | null;
  utilisee: number | null;
  rendue: number | null;
  stock: number;
}

export interface DroitsSeance {
  modifier: boolean;
  valider: boolean;
  preparer: boolean;
  compteRendu: boolean;
  bilan: boolean;
  annuler: boolean;
}

export interface EquipementIndisponible {
  id: string;
  numeroInventaire: string;
  designation: string;
  etat: string;
}

export interface SeanceTpVue {
  id: string;
  atelierId: string;
  atelierCode: string;
  atelier: string;
  classeId: string;
  classeCode: string;
  matiereId: string;
  matiere: string;
  engagementId: string;
  enseignant: string | null;
  date: string;
  heureDebut: string;
  heureFin: string;
  groupe: string | null;
  titre: string;
  effectif: number | null;
  productionId: string | null;
  production: string | null;
  origine: 'DEMANDE' | 'EMPLOI_DU_TEMPS';
  statut: StatutSeanceTp;
  motif: string | null;
  prepareeLe: string | null;
  observationsPreparation: string | null;
  realiseeLe: string | null;
  nettoyage: boolean | null;
  rangement: boolean | null;
  epiRespectes: boolean | null;
  quantiteProduite: number | null;
  observations: string | null;
  bilanLe: string | null;
  besoins: LigneBesoinTpVue[];
  conflits: string[];
  equipementsIndisponibles: EquipementIndisponible[];
  droits: DroitsSeance;
}

export interface OptionSeance {
  classeId: string;
  classeCode: string;
  matiereId: string;
  matiere: string;
  engagementId: string;
  enseignant: string | null;
}

export interface LigneBesoinTp {
  articleId: string;
  quantite: number;
}

export interface DonneesSeanceTp {
  classeId: string;
  matiereId: string;
  date: string;
  heureDebut: string;
  heureFin: string;
  groupe: string | null;
  titre: string;
  effectif: number | null;
  productionId: string | null;
  besoins: LigneBesoinTp[];
}

export interface DemandeCompteRendu {
  nettoyage: boolean;
  rangement: boolean;
  epiRespectes: boolean;
  quantiteProduite: number | null;
  observations: string | null;
  consommation: LigneBesoinTp[];
  pannes: { equipementId: string; description: string }[];
}

export type CategorieConsigne = 'ZONE_DANGER' | 'EXTINCTEUR' | 'ARRET_URGENCE' | 'EPI' | 'MACHINE' | 'HYGIENE' | 'AUTRE';

export const CATEGORIES_CONSIGNE: CategorieConsigne[] = ['EPI', 'ZONE_DANGER', 'ARRET_URGENCE', 'EXTINCTEUR', 'MACHINE', 'HYGIENE', 'AUTRE'];

export const LIBELLE_CONSIGNE: Record<CategorieConsigne, string> = {
  ZONE_DANGER: 'Zone de danger',
  EXTINCTEUR: 'Extincteurs',
  ARRET_URGENCE: "Arrêt d'urgence",
  EPI: 'Équipements de protection (EPI)',
  MACHINE: 'Consigne machine',
  HYGIENE: 'Hygiène',
  AUTRE: 'Autre',
};

export interface ConsigneVue {
  id: string;
  categorie: CategorieConsigne;
  equipementId: string | null;
  equipement: string | null;
  texte: string;
}

export interface ConsignesVue {
  atelierId: string;
  consignes: ConsigneVue[];
  modifieesLe: string | null;
  aLire: boolean;
  luLe: string | null;
  modifier: boolean;
}

export interface LectureVue {
  engagementId: string;
  enseignant: string | null;
  matieres: string;
  luLe: string | null;
  aJour: boolean;
}

export type Gravite = 'OBSERVATION' | 'DANGER_IMMINENT' | 'ACCIDENT';

export const LIBELLE_GRAVITE: Record<Gravite, string> = {
  OBSERVATION: 'Observation',
  DANGER_IMMINENT: 'Danger imminent',
  ACCIDENT: 'Accident',
};

export interface EvenementVue {
  id: string;
  seanceId: string | null;
  seance: string | null;
  survenuLe: string;
  gravite: Gravite;
  description: string;
  mesures: string | null;
  auteur: string | null;
}

export type TypeClient = 'PUBLIC' | 'PRIVE' | 'INTERNE';
export type StatutProduction = 'EN_COURS' | 'TERMINEE' | 'LIVREE' | 'ANNULEE';

export const LIBELLE_TYPE_CLIENT: Record<TypeClient, string> = { PUBLIC: 'Commande publique', PRIVE: 'Commande privée', INTERNE: "Pour l'établissement" };
export const LIBELLE_STATUT_PRODUCTION: Record<StatutProduction, string> = {
  EN_COURS: 'en cours',
  TERMINEE: 'terminée',
  LIVREE: 'livrée',
  ANNULEE: 'annulée',
};

export interface ProductionVue {
  id: string;
  numero: string;
  atelierId: string;
  atelierCode: string;
  client: string;
  typeClient: TypeClient;
  objet: string;
  quantite: number;
  unite: string;
  montant: number | null;
  dateCommande: string;
  echeance: string | null;
  statut: StatutProduction;
  livreeLe: string | null;
  observations: string | null;
  quantiteProduite: number;
  seances: number;
  enRetard: boolean;
  gerer: boolean;
}

export interface DonneesProduction {
  atelierId: string;
  client: string;
  typeClient: TypeClient;
  objet: string;
  quantite: number;
  unite: string;
  montant: number | null;
  dateCommande: string | null;
  echeance: string | null;
  observations: string | null;
}

export interface AchatTp {
  atelier: string;
  date: string;
  classe: string;
  titre: string;
  article: string;
  designation: string;
  unite: string;
  quantite: number;
  prix: number | null;
}

/** Lundi de la semaine d'une date (AAAA-MM-JJ). */
export function lundi(date: string): string {
  const d = new Date(date + 'T12:00:00Z');
  const decalage = (d.getUTCDay() + 6) % 7;
  d.setUTCDate(d.getUTCDate() - decalage);
  return d.toISOString().slice(0, 10);
}

export function ajouterJours(date: string, n: number): string {
  const d = new Date(date + 'T12:00:00Z');
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

/** « 08:00:00 » → « 08h00 ». */
export function heure(h: string): string {
  return h.slice(0, 5).replace(':', 'h');
}
