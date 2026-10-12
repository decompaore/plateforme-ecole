/** Bulletins : conseil de classe, appréciations, génération et publication (v0.43). */

export type Distinction =
  | 'AUCUNE'
  | 'TABLEAU_HONNEUR'
  | 'ENCOURAGEMENTS'
  | 'FELICITATIONS'
  | 'AVERTISSEMENT_TRAVAIL'
  | 'AVERTISSEMENT_CONDUITE'
  | 'BLAME';

export const LIBELLE_DISTINCTION: Record<Distinction, string> = {
  AUCUNE: 'Aucune',
  TABLEAU_HONNEUR: "Tableau d'honneur",
  ENCOURAGEMENTS: 'Encouragements',
  FELICITATIONS: 'Félicitations',
  AVERTISSEMENT_TRAVAIL: 'Avertissement (travail)',
  AVERTISSEMENT_CONDUITE: 'Avertissement (conduite)',
  BLAME: 'Blâme',
};

export const DISTINCTIONS = Object.keys(LIBELLE_DISTINCTION) as Distinction[];

/** Avis du conseil pour un élève : résultats de la période, distinction proposée par les seuils et retenue. */
export interface AvisVue {
  inscriptionId: string;
  nom: string;
  prenoms: string;
  moyenne: number | null;
  rang: number | null;
  tauxMaitrise: number | null;
  distinctionProposee: Distinction | null;
  distinction: Distinction | null;
  appreciation: string | null;
}

export interface SaisieAvis {
  inscriptionId: string;
  distinction: Distinction | null;
  appreciation: string | null;
}

export interface AppreciationVue {
  inscriptionId: string;
  nom: string;
  prenoms: string;
  texte: string | null;
}

export type StatutGeneration = 'GENEREE' | 'PUBLIEE';

export interface BulletinResumeVue {
  id: string;
  inscriptionId: string;
  matricule: string;
  nom: string;
  prenoms: string;
  moyenne: number | null;
  rang: number | null;
  admis: boolean;
  tauxMaitrise: number | null;
  distinction: Distinction | null;
  codeVerification: string;
}

export interface GenerationVue {
  id: string;
  classeId: string;
  classeCode: string;
  periodeId: string;
  periodeLibelle: string;
  statut: StatutGeneration;
  effectif: number;
  moyenneClasse: number | null;
  tauxReussite: number | null;
  genereLe: string;
  publieLe: string | null;
  bulletins: BulletinResumeVue[];
}

/** Moyenne ou taux de maîtrise affiché à la française : « 12,45 » ou « 75 % ». */
export function resultat(moyenne: number | null, tauxMaitrise: number | null): string {
  if (moyenne !== null && moyenne !== undefined) {
    return Number(moyenne).toFixed(2).replace('.', ',');
  }
  if (tauxMaitrise !== null && tauxMaitrise !== undefined) {
    return `${String(Math.round(Number(tauxMaitrise) * 10) / 10).replace('.', ',')} %`;
  }
  return '—';
}

/** Rang avec son suffixe : 1er, 2e… (ex æquo laissés au serveur). */
export function rang(r: number | null): string {
  return r === null || r === undefined ? '' : r === 1 ? '1er' : `${r}e`;
}
