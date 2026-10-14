import { dateCourte, dateLocale } from '../core/outils';
import { AbsenceVue, HistoriqueVue } from '../vie-scolaire/modeles-vs';
import { EnfantVue, fcfa, SituationVue } from './modeles-parent';

export type Ton = 'bon' | 'attention' | 'alerte' | 'neutre';

export interface Indicateur {
  texte: string;
  ton: Ton;
}

/** Situation de l'année en cours de l'enfant (celle de sa dernière inscription). */
export function situationCourante(enfant: EnfantVue, situations: SituationVue[]): SituationVue | undefined {
  return situations.find((s) => s.annee === enfant.anneeLibelle) ?? situations[0];
}

/** Absences des 30 derniers jours : jours manqués, dont non justifiés. */
export function indicateurAbsences(absences: AbsenceVue[], aujourdhui = dateLocale()): Indicateur {
  const [a, m, j] = aujourdhui.split('-').map(Number);
  const debut = dateLocale(new Date(a, m - 1, j - 29));
  const recentes = absences.filter((x) => x.type === 'ABSENCE' && x.date >= debut);
  const jours = new Set(recentes.map((x) => x.date));
  const nonJustifies = new Set(recentes.filter((x) => !x.justifiee).map((x) => x.date));
  if (jours.size === 0) {
    return { texte: 'Aucune absence ces 30 derniers jours', ton: 'bon' };
  }
  if (nonJustifies.size === 0) {
    return { texte: `${jours.size} jour(s) d'absence ces 30 derniers jours, tous justifiés`, ton: 'neutre' };
  }
  return {
    texte: `${nonJustifies.size} jour(s) d'absence non justifiée ces 30 derniers jours`,
    ton: 'alerte',
  };
}

/** Scolarité de l'année : en retard, reste à payer, ou soldée. */
export function indicateurScolarite(s: SituationVue | undefined): Indicateur | null {
  if (!s || s.totalFamille === 0) {
    return null;
  }
  if (s.retardFamille > 0) {
    return { texte: `${fcfa(s.retardFamille)} en retard`, ton: 'alerte' };
  }
  if (s.resteFamille > 0) {
    return {
      texte: `Reste ${fcfa(s.resteFamille)}${s.prochaineEcheance ? `, prochaine échéance le ${dateCourte(s.prochaineEcheance)}` : ''}`,
      ton: 'attention',
    };
  }
  return { texte: 'Scolarité soldée', ton: 'bon' };
}

/** Prochaine convocation des parents encore prévue. */
export function prochaineConvocation(historiques: HistoriqueVue[], maintenant = new Date()): { rendezVous: string; motif: string } | null {
  const prevues = historiques
    .flatMap((h) => h.convocations)
    .filter((c) => c.statut === 'PREVUE' && new Date(c.rendezVous) >= maintenant)
    .sort((x, y) => x.rendezVous.localeCompare(y.rendezVous));
  return prevues[0] ?? null;
}
