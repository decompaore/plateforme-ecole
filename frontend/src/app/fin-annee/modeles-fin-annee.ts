/** Fin d'année : décisions des conseils, année suivante, plan de passage, clôture (v0.45). */

import { AnneeVue, Decision } from '../admin/modeles-admin';

export type ResultatExamen = 'ADMIS' | 'AJOURNE';

/** Décisions que le conseil peut retenir (« en attente d'examen » n'en est pas une). */
export const DECISIONS_CONSEIL: Decision[] = ['ADMIS', 'REDOUBLE', 'EXCLU', 'ORIENTE', 'CERTIFIE', 'NON_CERTIFIE'];

/** Libellé d'une décision pour un élève (au singulier). */
export const DECISION_ELEVE: Record<Decision, string> = {
  ADMIS: 'Admis(e)',
  REDOUBLE: 'Redouble',
  EXCLU: 'Exclu(e)',
  ORIENTE: 'Orienté(e)',
  CERTIFIE: 'Certifié(e)',
  NON_CERTIFIE: 'Non certifié(e)',
  EN_ATTENTE_EXAMEN: "Attend l'examen",
};

export interface DecisionVue {
  inscriptionId: string;
  matricule: string | null;
  nom: string | null;
  prenoms: string | null;
  redoublant: boolean;
  moyenneAnnuelle: number | null;
  rang: number | null;
  tauxMaitrise: number | null;
  redoublements: number;
  resultatExamen: ResultatExamen | null;
  proposition: Decision;
  decision: Decision;
  motif: string | null;
  validee: boolean;
}

export interface DecisionsClasseVue {
  classeId: string;
  classeCode: string;
  examen: string | null;
  validees: boolean;
  repartition: Partial<Record<Decision, number>>;
  eleves: DecisionVue[];
}

export interface SaisieDecision {
  inscriptionId: string;
  decision?: Decision | null;
  motif?: string | null;
  resultatExamen?: ResultatExamen | null;
}

export interface ClassePassageVue {
  classeId: string;
  classeCode: string;
  niveau: string;
  examen: string | null;
  decisions: number;
  validees: boolean;
  repartition: Partial<Record<Decision, number>>;
}

export interface TableauPassageVue {
  annee: AnneeVue;
  classes: number;
  classesValidees: number;
  detail: ClassePassageVue[];
}

export interface ParametresPassage {
  seuilExclusion: number | null;
  redoublementsMax: number | null;
}

export interface PoidsPeriode {
  ordre: number;
  poids: number;
}

export interface AnneeSuivanteVue {
  annee: AnneeVue;
  classesCopiees: number;
  matieresCopiees: number;
  periodesCopiees: number;
  fraisRepris: number;
  examensRepris: number;
}

export type Sortie = 'EXCLU' | 'ORIENTE' | 'CERTIFIE' | 'FIN_DE_CYCLE' | 'SANS_CLASSE_CIBLE';

export const LIBELLE_SORTIE: Record<Sortie, string> = {
  EXCLU: 'Exclu(e)',
  ORIENTE: 'Orienté(e) : à placer à la main',
  CERTIFIE: 'Certifié(e) : fin de formation',
  FIN_DE_CYCLE: 'Fin de cycle',
  SANS_CLASSE_CIBLE: "Pas de classe de ce niveau l'an prochain",
};

export interface ClasseCibleVue {
  id: string;
  code: string;
  niveau: string;
  filiereCode: string;
  effectifMax: number | null;
  dejaInscrits: number;
}

export interface LignePlan {
  inscriptionId: string;
  eleveId: string;
  matricule: string | null;
  nom: string;
  prenoms: string;
  sexe: 'F' | 'M' | null;
  classeSourceId: string;
  classeSourceCode: string;
  decision: Decision;
  moyenneAnnuelle: number | null;
  redoublant: boolean;
  classeCibleId: string | null;
  sortie: Sortie | null;
  dejaInscrit: string | null;
}

export interface PlanVue {
  annee: AnneeVue;
  anneeSuivante: AnneeVue;
  classesNonValidees: string[];
  classesCibles: ClasseCibleVue[];
  eleves: LignePlan[];
}

export interface NonReinscrit {
  inscriptionId: string;
  nomComplet: string | null;
  motif: string;
}

export interface ResultatPlan {
  reinscrits: number;
  redoublants: number;
  nonReinscrits: NonReinscrit[];
}

/** Moyenne à la française : « 10,40 ». */
export function moyenne(m: number | null | undefined): string {
  return m === null || m === undefined ? '—' : Number(m).toFixed(2).replace('.', ',');
}

/** Libellé l'année suivante proposé : « 2026-2027 » → « 2027-2028 ». */
export function libelleSuivant(libelle: string): string {
  const m = /^(\d{4})\s*-\s*(\d{4})$/.exec(libelle.trim());
  return m ? `${Number(m[1]) + 1}-${Number(m[2]) + 1}` : '';
}

/** Date AAAA-MM-JJ décalée d'un an. */
export function anPlusUn(jour: string): string {
  const [a, m, j] = jour.split('-');
  return `${Number(a) + 1}-${m}-${j}`;
}

/**
 * Effectifs prévus d'une classe de l'année suivante : déjà inscrits + élèves que le plan (ajusté) y met,
 * filles et garçons parmi ces derniers.
 */
export function effectifsPrevus(
  classe: ClasseCibleVue,
  lignes: LignePlan[],
  choix: Record<string, string | null>,
): { total: number; filles: number; garcons: number; redoublants: number; depasse: boolean } {
  const ici = lignes.filter((l) => !l.dejaInscrit && choix[l.inscriptionId] === classe.id);
  const total = classe.dejaInscrits + ici.length;
  return {
    total,
    filles: ici.filter((l) => l.sexe === 'F').length,
    garcons: ici.filter((l) => l.sexe === 'M').length,
    redoublants: ici.filter((l) => l.redoublant).length,
    depasse: classe.effectifMax !== null && total > classe.effectifMax,
  };
}

// ---------------------------------------------------------------- Examens nationaux (v0.46)

export interface ClasseExamenVue {
  classeId: string;
  classeCode: string;
  inscrits: number;
  resultatsConnus: number;
  admis: number;
  taux: number | null;
}

export interface ResultatExamenVue {
  annee: string;
  examen: string;
  source: 'CALCULE' | 'SAISI';
  historiqueId: string | null;
  inscrits: number;
  resultatsConnus: number;
  admis: number;
  resultatsFilles: number | null;
  admisFilles: number | null;
  taux: number | null;
  tauxFilles: number | null;
  tauxGarcons: number | null;
  classes: ClasseExamenVue[];
}

export interface TableauExamensVue {
  examens: string[];
  annees: string[];
  resultats: ResultatExamenVue[];
}

export interface SaisieHistorique {
  annee: string;
  examen: string;
  candidats: number | null;
  admis: number | null;
  candidatesFilles: number | null;
  admisesFilles: number | null;
}

/** « 62,5 % ». */
export function pourcent(t: number | null | undefined): string {
  return t === null || t === undefined ? '—' : `${String(Math.round(Number(t) * 10) / 10).replace('.', ',')} %`;
}

/** Série d'un examen, de la plus ancienne année à la plus récente, avec l'écart à l'année précédente. */
export function serieExamen(t: TableauExamensVue, examen: string): { annee: string; taux: number | null; ecart: number | null }[] {
  const lignes = t.resultats.filter((r) => r.examen === examen).sort((a, b) => a.annee.localeCompare(b.annee));
  return lignes.map((r, i) => {
    const prec = i > 0 ? lignes[i - 1].taux : null;
    return {
      annee: r.annee,
      taux: r.taux,
      ecart: r.taux !== null && prec !== null ? Math.round((Number(r.taux) - Number(prec)) * 10) / 10 : null,
    };
  });
}
