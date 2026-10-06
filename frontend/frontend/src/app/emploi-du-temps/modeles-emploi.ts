import { TypeMatiere } from '../admin/modeles-admin';
import { Role } from '../core/modeles';

/** Objets de l'API des emplois du temps. */

export type Domaine = 'GENERAL' | 'TECHNIQUE';
export type TypeConflit = 'CLASSE' | 'ENSEIGNANT' | 'ENSEIGNANT_AILLEURS' | 'ATELIER';

/** Ceux qui consultent l'emploi du temps complet (le serveur restreint les modifications). */
export const ROLES_EMPLOI: Role[] = ['ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'SURVEILLANT'];
/** Ceux qui fixent la grille horaire et publient. */
export const ROLES_GRILLE: Role[] = ['ADMIN_ECOLE', 'CENSEUR'];

export const JOURS = ['', 'Lundi', 'Mardi', 'Mercredi', 'Jeudi', 'Vendredi', 'Samedi', 'Dimanche'];
export const JOURS_COURTS = ['', 'L', 'Ma', 'Me', 'J', 'V', 'S', 'D'];

export const LIBELLE_DOMAINE: Record<Domaine, string> = {
  GENERAL: 'matières générales',
  TECHNIQUE: 'matières techniques et pratiques',
};

export const RESPONSABLE: Record<Domaine, string> = {
  GENERAL: 'le censeur',
  TECHNIQUE: 'le chef des travaux',
};

export interface CreneauVue {
  id: string;
  /** « 08:00:00 » */
  heureDebut: string;
  heureFin: string;
  jours: number[];
  minutes: number;
}

export interface SaisieCreneau {
  id: string | null;
  heureDebut: string;
  heureFin: string;
  jours: number[];
}

export interface MatiereClasseVue {
  matiereId: string;
  code: string;
  libelle: string;
  type: TypeMatiere;
  domaine: Domaine;
  volumeHebdo: number | null;
  engagementId: string | null;
  enseignant: string | null;
  minutesPrevues: number;
  minutesPlacees: number;
}

export interface ClasseEmploiVue {
  id: string;
  code: string;
  niveau: string;
  filiereId: string;
  filiereCode: string;
  matieres: MatiereClasseVue[];
  groupes: string[];
}

export interface SeanceVue {
  id: string;
  classeId: string;
  matiereId: string;
  jour: number;
  creneauId: string;
  groupe: string | null;
  atelierId: string | null;
  salle: string | null;
  engagementId: string | null;
  domaine: Domaine;
  modifiable: boolean;
}

export interface AtelierEmploiVue {
  id: string;
  code: string;
  nom: string;
  filieres: string[];
}

export interface OccupationVue {
  engagementId: string;
  jour: number;
  heureDebut: string;
  heureFin: string;
}

export interface ConflitVue {
  type: TypeConflit;
  seances: string[];
  message: string;
}

export interface DroitsEmploi {
  grille: boolean;
  domaines: Domaine[];
  publier: boolean;
  modifiable: boolean;
}

export interface EmploiDuTempsVue {
  anneeId: string;
  annee: string;
  jours: number[];
  creneaux: CreneauVue[];
  classes: ClasseEmploiVue[];
  seances: SeanceVue[];
  enseignants: { engagementId: string; nom: string }[];
  ateliers: AtelierEmploiVue[];
  occupationsAilleurs: OccupationVue[];
  conflits: ConflitVue[];
  droits: DroitsEmploi;
  publieLe: string | null;
}

export interface DonneesSeance {
  anneeId: string;
  classeId: string;
  matiereId: string;
  jour: number;
  creneauId: string;
  groupe: string | null;
  atelierId: string | null;
  salle: string | null;
}

export interface DemandeGeneration {
  classes: string[];
  domaines: Domaine[];
  remplacer: boolean;
}

export interface ManqueVue {
  classeId: string;
  classe: string;
  matiereId: string;
  matiere: string;
  minutesManquantes: number;
  raison: string;
}

export interface ResultatGeneration {
  seancesPlacees: number;
  seancesRetirees: number;
  manques: ManqueVue[];
  emploi: EmploiDuTempsVue;
}

export interface MaSeanceVue {
  jour: number;
  creneauId: string;
  heureDebut: string;
  heureFin: string;
  classe: string;
  matiere: string;
  groupe: string | null;
  atelier: string | null;
  salle: string | null;
}

export interface MonEmploiVue {
  anneeId: string | null;
  annee: string | null;
  publieLe: string | null;
  jours: number[];
  creneaux: CreneauVue[];
  seances: MaSeanceVue[];
  ailleurs: OccupationVue[];
}

// ------------------------------------------------------------------ outils

/** « 08:00:00 » → « 08h00 » */
export function heure(h: string): string {
  return h.slice(0, 5).replace(':', 'h');
}

/** « 08h00 – 08h55 » */
export function plage(debut: string, fin: string): string {
  return `${heure(debut)} – ${heure(fin)}`;
}

/** 270 → « 4 h 30 », 240 → « 4 h » */
export function duree(minutes: number): string {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return m ? `${h} h ${String(m).padStart(2, '0')}` : `${h} h`;
}

function enMinutes(h: string): number {
  const [hh, mm] = h.split(':').map(Number);
  return hh * 60 + mm;
}

/** Contrôle de la grille avant l'envoi : heures valides, jours choisis, pas de chevauchement. */
export function erreurGrille(creneaux: SaisieCreneau[]): string | null {
  if (creneaux.length > 16) {
    return '16 créneaux au plus.';
  }
  for (const c of creneaux) {
    if (!/^\d{2}:\d{2}/.test(c.heureDebut) || !/^\d{2}:\d{2}/.test(c.heureFin)) {
      return 'Indiquez l’heure de début et l’heure de fin de chaque créneau.';
    }
    const d = enMinutes(c.heureDebut);
    const f = enMinutes(c.heureFin);
    if (f <= d) {
      return `Créneau de ${heure(c.heureDebut)} : l’heure de fin doit suivre l’heure de début.`;
    }
    if (d < 6 * 60 || f > 20 * 60) {
      return 'Les cours ont lieu entre 06h00 et 20h00.';
    }
    if (f - d > 240) {
      return `Créneau de ${heure(c.heureDebut)} : 4 heures au plus, découpez-le.`;
    }
    if (!c.jours.length) {
      return `Créneau de ${heure(c.heureDebut)} : choisissez au moins un jour.`;
    }
  }
  const tries = [...creneaux].sort((a, b) => a.heureDebut.localeCompare(b.heureDebut));
  for (let i = 1; i < tries.length; i++) {
    if (enMinutes(tries[i].heureDebut) < enMinutes(tries[i - 1].heureFin)) {
      return `Les créneaux de ${heure(tries[i - 1].heureDebut)} et de ${heure(tries[i].heureDebut)} se chevauchent.`;
    }
  }
  return null;
}

/**
 * Grille courante d'un lycée : heures de 7 h à 12 h du lundi au samedi, de 15 h à 18 h les
 * lundi, mardi, jeudi et vendredi (mercredi et samedi après-midi libres).
 */
export function grilleCourante(): SaisieCreneau[] {
  const matin = ['07:00', '08:00', '09:00', '10:00', '11:00'];
  const soir = ['15:00', '16:00', '17:00'];
  return [
    ...matin.map((h) => ({ id: null, heureDebut: h, heureFin: `${String(Number(h.slice(0, 2)) + 1).padStart(2, '0')}:00`, jours: [1, 2, 3, 4, 5, 6] })),
    ...soir.map((h) => ({ id: null, heureDebut: h, heureFin: `${String(Number(h.slice(0, 2)) + 1).padStart(2, '0')}:00`, jours: [1, 2, 4, 5] })),
  ];
}

/** Le créneau suivant le dernier : même durée, juste après. */
export function creneauSuivant(creneaux: SaisieCreneau[]): SaisieCreneau {
  const dernier = [...creneaux].sort((a, b) => a.heureDebut.localeCompare(b.heureDebut)).at(-1);
  if (!dernier || !/^\d{2}:\d{2}/.test(dernier.heureFin)) {
    return { id: null, heureDebut: '07:00', heureFin: '08:00', jours: [1, 2, 3, 4, 5, 6] };
  }
  const longueur = Math.max(enMinutes(dernier.heureFin) - enMinutes(dernier.heureDebut), 30);
  const debut = Math.min(enMinutes(dernier.heureFin), 19 * 60);
  const fin = Math.min(debut + longueur, 20 * 60);
  const hm = (m: number) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
  return { id: null, heureDebut: hm(debut), heureFin: hm(fin), jours: [...dernier.jours] };
}

/** Les deux plages se chevauchent-elles ? */
export function chevauche(d1: string, f1: string, d2: string, f2: string): boolean {
  return enMinutes(d1) < enMinutes(f2) && enMinutes(d2) < enMinutes(f1);
}
