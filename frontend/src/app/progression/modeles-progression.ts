import { TypeMatiere } from '../admin/modeles-admin';
import { Role } from '../core/modeles';

/** Objets de l'API de la progression pédagogique. */

export type StatutFiche = 'BROUILLON' | 'SOUMISE' | 'VISEE' | 'A_REVOIR';
export type Domaine = 'GENERAL' | 'TECHNIQUE';

/** Ceux qui suivent et visent les progressions (le serveur restreint chacun à son domaine). */
export const SUPERVISION: Role[] = ['ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX'];

export const LIBELLE_STATUT: Record<StatutFiche, string> = {
  BROUILLON: 'Brouillon',
  SOUMISE: 'À viser',
  VISEE: 'Visée',
  A_REVOIR: 'À revoir',
};

export const LIBELLE_DOMAINE: Record<Domaine, string> = {
  GENERAL: 'Matières générales',
  TECHNIQUE: 'Matières techniques et pratiques',
};

export interface SequenceVue {
  ordre: number;
  titre: string;
  contenu: string | null;
  competences: string | null;
  heuresPrevues: number;
  semaineDebut: string | null;
  /** Fait d'après le cahier de textes (v0.24). */
  heuresRealisees: number;
  seances: number;
}

/** Réalisé d'une matière d'après le cahier de textes. */
export interface Avancement {
  heuresRealisees: number;
  heuresHorsSequence: number;
  seances: number;
  derniereSeance: string | null;
}

export interface DonneesSeance {
  classeId: string;
  matiereId: string;
  date: string;
  heureDebut: string;
  heureFin: string;
  sequenceOrdre: number | null;
  contenu: string;
  travailAFaire: string | null;
}

export interface SeanceVue {
  id: string;
  classeId: string;
  matiereId: string;
  date: string;
  heureDebut: string;
  heureFin: string;
  heures: number;
  sequenceOrdre: number | null;
  sequenceTitre: string | null;
  contenu: string;
  travailAFaire: string | null;
  saisiLe: string;
  modifieLe: string;
}

/** Durée d'une séance en heures (« 08:00 » → « 10:30 » : 2,5), null si l'horaire est invalide. */
export function dureeHeures(debut: string, fin: string): number | null {
  const m = (h: string) => {
    const [a, b] = h.split(':').map(Number);
    return Number.isFinite(a) && Number.isFinite(b) ? a * 60 + b : NaN;
  };
  const d = m(fin) - m(debut);
  return Number.isFinite(d) && d > 0 ? Math.round((d / 60) * 10) / 10 : null;
}

/** Contrôle d'une séance avant l'enregistrement : message à afficher, ou null. */
export function erreurSeance(d: { date: string; heureDebut: string; heureFin: string; contenu: string; travailAFaire: string }, aujourdhui: string): string | null {
  if (!d.date || !d.heureDebut || !d.heureFin) {
    return 'Indiquez la date et l’horaire du cours.';
  }
  if (d.date > aujourdhui) {
    return 'Le cahier de textes se remplit après le cours, pas avant.';
  }
  const duree = dureeHeures(d.heureDebut, d.heureFin);
  if (duree === null) {
    return 'L’heure de fin doit suivre l’heure de début.';
  }
  if (duree > 8) {
    return 'Une séance dure 8 heures au plus.';
  }
  if (!d.contenu.trim()) {
    return 'Notez ce qui a été fait pendant le cours.';
  }
  if (d.contenu.trim().length > 2000 || d.travailAFaire.trim().length > 1000) {
    return 'Texte trop long (contenu 2 000 caractères, travail à faire 1 000).';
  }
  return null;
}

export interface FicheVue {
  id: string | null;
  classeId: string;
  classeCode: string;
  matiereId: string;
  matiereCode: string;
  matiereLibelle: string;
  type: TypeMatiere;
  domaine: Domaine;
  engagementId: string | null;
  enseignant: string | null;
  statut: StatutFiche | null;
  sequences: SequenceVue[];
  heuresPrevues: number;
  volumeHebdo: number | null;
  volumeTotal: number | null;
  modifieeLe: string | null;
  soumiseLe: string | null;
  viseLe: string | null;
  visePar: string | null;
  commentaireVisa: string | null;
  /** La personne connectée enseigne cette matière dans cette classe. */
  auteur: boolean;
  modifiable: boolean;
  visable: boolean;
  avancement: Avancement;
}

export interface SuiviProgressionVue {
  classeId: string;
  classeCode: string;
  niveau: string;
  matiereId: string;
  matiereCode: string;
  matiereLibelle: string;
  type: TypeMatiere;
  domaine: Domaine;
  engagementId: string | null;
  enseignant: string | null;
  statut: StatutFiche | null;
  sequences: number;
  heuresPrevues: number;
  volumeHebdo: number | null;
  soumiseLe: string | null;
  viseLe: string | null;
  avancement: Avancement;
}

export interface SaisieSequence {
  titre: string;
  contenu: string | null;
  competences: string | null;
  heuresPrevues: number;
  semaineDebut: string | null;
}

/** Séquence telle qu'on la tape (heures en texte, virgule acceptée). */
export interface SequenceEditee {
  titre: string;
  contenu: string;
  competences: string;
  heures: string;
  semaineDebut: string;
}

/** « 12,5 » ou « 12.5 » → 12.5 ; null si ce n'est pas un nombre d'heures valable (une décimale, 0,5 à 999). */
export function lireHeures(texte: string): number | null {
  const t = texte.trim().replace(',', '.');
  if (!/^\d{1,3}(\.\d)?$/.test(t)) {
    return null;
  }
  const n = Number(t);
  return n > 0 && n <= 999 ? n : null;
}

/** « 12,5 h » */
export function heures(n: number): string {
  return `${String(Math.round(n * 10) / 10).replace('.', ',')} h`;
}

/** Contrôle d'une fiche avant l'envoi : message à afficher, ou null. */
export function erreurFiche(sequences: SequenceEditee[]): string | null {
  for (let i = 0; i < sequences.length; i++) {
    const s = sequences[i];
    const n = `Séquence ${i + 1} : `;
    if (!s.titre.trim()) {
      return n + 'donnez un titre.';
    }
    if (s.titre.trim().length > 150) {
      return n + 'titre de 150 caractères au plus.';
    }
    if (lireHeures(s.heures) === null) {
      return n + 'heures prévues entre 0,5 et 999 (une décimale au plus).';
    }
    if (s.contenu.trim().length > 2000 || s.competences.trim().length > 500) {
      return n + 'texte trop long (contenu 2 000 caractères, compétences 500).';
    }
  }
  return null;
}

export function versSaisie(s: SequenceEditee): SaisieSequence {
  return {
    titre: s.titre.trim(),
    contenu: s.contenu.trim() || null,
    competences: s.competences.trim() || null,
    heuresPrevues: lireHeures(s.heures) ?? 0,
    semaineDebut: s.semaineDebut || null,
  };
}

export function versEdition(s: SequenceVue): SequenceEditee {
  return {
    titre: s.titre,
    contenu: s.contenu ?? '',
    competences: s.competences ?? '',
    heures: String(s.heuresPrevues).replace('.', ','),
    semaineDebut: s.semaineDebut ?? '',
  };
}
