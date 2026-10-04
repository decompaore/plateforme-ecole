import { Role } from '../core/modeles';

/** Objets de l'API des ateliers (v0.25). */

export type NatureArticle = 'MATIERE_OEUVRE' | 'EQUIPEMENT';
export type EtatEquipement = 'BON' | 'EN_PANNE' | 'MANQUANT' | 'REFORME';
export type StatutPanne = 'OUVERTE' | 'REPAREE' | 'IRREPARABLE';
export type TypeMouvement = 'ENTREE' | 'SORTIE' | 'INVENTAIRE';
export type StatutInventaire = 'EN_COURS' | 'CLOS';
export type FrequenceInventaire = 'SEMESTRIELLE' | 'ANNUELLE';

/** Rôles qui ouvrent l'espace des ateliers (le serveur restreint chacun à ses ateliers). */
export const ROLES_ATELIERS: Role[] = ['ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'INTENDANT', 'ENSEIGNANT'];
/** Direction des ateliers (le censeur seulement s'il n'y a pas de chef des travaux : le serveur décide). */
export const DIRECTION_ATELIERS: Role[] = ['ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX'];

export const LIBELLE_NATURE: Record<NatureArticle, string> = {
  MATIERE_OEUVRE: 'Matière d’œuvre',
  EQUIPEMENT: 'Équipement',
};

export const LIBELLE_ETAT: Record<EtatEquipement, string> = {
  BON: 'En service',
  EN_PANNE: 'En panne',
  MANQUANT: 'Manquant',
  REFORME: 'Réformé',
};

export const CLASSE_ETAT: Record<EtatEquipement, string> = {
  BON: 'present',
  EN_PANNE: 'absent',
  MANQUANT: 'absent',
  REFORME: '',
};

export const LIBELLE_MOUVEMENT: Record<TypeMouvement, string> = {
  ENTREE: 'Entrée',
  SORTIE: 'Sortie',
  INVENTAIRE: 'Correction d’inventaire',
};

export const LIBELLE_FREQUENCE: Record<FrequenceInventaire, string> = {
  SEMESTRIELLE: 'Chaque semestre',
  ANNUELLE: 'Une fois par an',
};

export interface ParametresAteliers {
  /** null : sans limite de durée. */
  dureeMandatMois: number | null;
  frequenceInventaire: FrequenceInventaire;
}

export interface ArticleVue {
  id: string;
  code: string;
  designation: string;
  nature: NatureArticle;
  unite: string;
  filiereId: string | null;
  filiereCode: string | null;
  specifications: string | null;
  normes: string | null;
  prixReference: number | null;
  prixModifieLe: string | null;
  actif: boolean;
  photo: boolean;
}

export interface DonneesArticle {
  code: string;
  designation: string;
  nature: NatureArticle;
  unite: string;
  filiereId: string | null;
  specifications: string | null;
  normes: string | null;
  prixReference: number | null;
  actif: boolean;
}

export interface FiliereCourte {
  id: string;
  code: string;
  libelle: string;
}

export interface MandatVue {
  id: string;
  engagementId: string;
  enseignant: string;
  debut: string;
  finPrevue: string | null;
  fin: string | null;
  motifFin: string | null;
  echeanceProche: boolean;
  echu: boolean;
}

export interface AlertesAtelier {
  sansResponsable: boolean;
  mandatAEcheance: boolean;
  mandatEchu: boolean;
  equipementsEnPanne: number;
  equipementsManquants: number;
  articlesSousSeuil: number;
  inventaireEnCours: boolean;
  dernierInventaire: string | null;
  inventaireEnRetard: boolean;
}

export interface DroitsAtelier {
  gerer: boolean;
  responsable: boolean;
  signaler: boolean;
}

export interface AtelierResumeVue {
  id: string;
  code: string;
  nom: string;
  emplacement: string | null;
  postes: number | null;
  ouvert: boolean;
  filieres: FiliereCourte[];
  responsable: MandatVue | null;
  equipements: number;
  articles: number;
  alertes: AlertesAtelier;
  droits: DroitsAtelier;
}

export interface AtelierVue extends AtelierResumeVue {
  observations: string | null;
  historique: MandatVue[];
}

export interface DonneesAtelier {
  code: string;
  nom: string;
  emplacement: string | null;
  postes: number | null;
  ouvert: boolean;
  observations: string | null;
  filieres: string[];
}

export interface CandidatVue {
  engagementId: string;
  enseignant: string;
  matieres: string[];
}

export interface PanneVue {
  id: string;
  equipementId: string;
  description: string;
  signaleePar: string | null;
  signaleeLe: string;
  statut: StatutPanne;
  intervention: string | null;
  cout: number | null;
  clotureePar: string | null;
  clotureeLe: string | null;
}

export interface EquipementVue {
  id: string;
  atelierId: string;
  articleId: string | null;
  designation: string;
  numeroInventaire: string;
  marque: string | null;
  numeroSerie: string | null;
  dateAcquisition: string | null;
  valeur: number | null;
  etat: EtatEquipement;
  observations: string | null;
  panneOuverte: PanneVue | null;
}

export interface DonneesEquipement {
  articleId: string | null;
  designation: string | null;
  numeroInventaire: string | null;
  marque: string | null;
  numeroSerie: string | null;
  dateAcquisition: string | null;
  valeur: number | null;
  observations: string | null;
  etat?: EtatEquipement | null;
}

export interface LigneStockVue {
  articleId: string;
  code: string;
  designation: string;
  unite: string;
  quantite: number;
  seuilAlerte: number | null;
  sousLeSeuil: boolean;
  prixReference: number | null;
}

export interface MouvementVue {
  id: string;
  articleId: string;
  article: string | null;
  unite: string | null;
  type: TypeMouvement;
  quantite: number;
  stockApres: number;
  date: string;
  motif: string | null;
  auteur: string | null;
}

export interface InventaireResumeVue {
  id: string;
  atelierId: string;
  libelle: string;
  statut: StatutInventaire;
  ouvertLe: string;
  closLe: string | null;
  ecarts: number;
}

export interface LigneMatiereVue {
  articleId: string;
  code: string;
  designation: string;
  unite: string;
  quantiteTheorique: number;
  quantiteConstatee: number | null;
  ecart: number | null;
}

export interface LigneEquipementVue {
  equipementId: string;
  designation: string;
  numeroInventaire: string;
  etatTheorique: EtatEquipement;
  etatConstate: EtatEquipement | null;
  observation: string | null;
}

export interface InventaireVue {
  id: string;
  atelierId: string;
  atelierCode: string;
  atelierNom: string;
  libelle: string;
  statut: StatutInventaire;
  ouvertLe: string;
  ouvertPar: string | null;
  closLe: string | null;
  closPar: string | null;
  observations: string | null;
  matieres: LigneMatiereVue[];
  equipements: LigneEquipementVue[];
  restantes: number;
  modifiable: boolean;
}

export interface SaisieInventaire {
  matieres: { articleId: string; quantiteConstatee: number | null }[];
  equipements: { equipementId: string; etatConstate: EtatEquipement | null; observation: string | null }[];
  observations: string | null;
}

/** Quantité saisie (virgule ou point, deux décimales au plus) ; null si invalide. */
export function lireQuantite(texte: string, zero = false): number | null {
  const t = texte.trim().replace(/\s/g, '').replace(',', '.');
  if (!/^\d+(\.\d{1,2})?$/.test(t)) {
    return null;
  }
  const n = Number(t);
  return n > 0 || (zero && n === 0) ? n : null;
}

/** 12,5 · 1 250 */
export function quantite(n: number | null | undefined): string {
  return n === null || n === undefined ? '' : n.toLocaleString('fr-FR', { maximumFractionDigits: 2 }).replace(/ | /g, ' ');
}

/** Ce qui demande l'attention du chef des travaux, en phrases courtes. */
export function alertesEnTexte(a: AlertesAtelier): string[] {
  const t: string[] = [];
  if (a.sansResponsable) {
    t.push('Sans responsable');
  }
  if (a.mandatEchu) {
    t.push('Mandat du responsable échu');
  } else if (a.mandatAEcheance) {
    t.push('Mandat du responsable bientôt à échéance');
  }
  if (a.equipementsEnPanne) {
    t.push(`${a.equipementsEnPanne} équipement(s) en panne`);
  }
  if (a.equipementsManquants) {
    t.push(`${a.equipementsManquants} équipement(s) manquant(s)`);
  }
  if (a.articlesSousSeuil) {
    t.push(`${a.articlesSousSeuil} matière(s) sous le seuil`);
  }
  if (a.inventaireEnRetard) {
    t.push(a.dernierInventaire ? 'Inventaire à faire' : 'Jamais inventorié');
  }
  return t;
}

/** Durée du mandat en clair : « 2 ans », « 18 mois », « sans limite ». */
export function dureeMandat(mois: number | null): string {
  if (mois === null) {
    return 'sans limite';
  }
  return mois % 12 === 0 ? `${mois / 12} an${mois > 12 ? 's' : ''}` : `${mois} mois`;
}
