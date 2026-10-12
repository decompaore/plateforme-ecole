import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { AnneeVue } from '../admin/modeles-admin';
import { API } from '../core/session.service';
import {
  AnneeSuivanteVue,
  DecisionsClasseVue,
  ParametresPassage,
  PlanVue,
  PoidsPeriode,
  ResultatPlan,
  SaisieDecision,
  TableauPassageVue,
} from './modeles-fin-annee';

/** Appels de l'API de fin d'année (en ligne uniquement). */
@Injectable({ providedIn: 'root' })
export class FinAnneeApi {
  private readonly http = inject(HttpClient);

  // ---------- Réglages

  parametres(): Promise<ParametresPassage> {
    return firstValueFrom(this.http.get<ParametresPassage>(`${API}/parametres/passage`));
  }

  modifierParametres(p: ParametresPassage): Promise<ParametresPassage> {
    return firstValueFrom(this.http.put<ParametresPassage>(`${API}/parametres/passage`, p));
  }

  poids(profilId: string): Promise<PoidsPeriode[]> {
    return firstValueFrom(this.http.get<PoidsPeriode[]>(`${API}/profils/${profilId}/poids-periodes`));
  }

  definirPoids(profilId: string, poids: PoidsPeriode[]): Promise<PoidsPeriode[]> {
    return firstValueFrom(this.http.put<PoidsPeriode[]>(`${API}/profils/${profilId}/poids-periodes`, { poids }));
  }

  definirExamen(classeId: string, examen: string): Promise<{ examen: string | null }> {
    return firstValueFrom(this.http.put<{ examen: string | null }>(`${API}/classes/${classeId}/examen`, { examen }));
  }

  // ---------- Conseils de fin d'année

  tableau(anneeId: string): Promise<TableauPassageVue> {
    return firstValueFrom(this.http.get<TableauPassageVue>(`${API}/annees/${anneeId}/passage`));
  }

  decisions(classeId: string): Promise<DecisionsClasseVue> {
    return firstValueFrom(this.http.get<DecisionsClasseVue>(`${API}/classes/${classeId}/decisions`));
  }

  calculer(classeId: string): Promise<DecisionsClasseVue> {
    return firstValueFrom(this.http.post<DecisionsClasseVue>(`${API}/classes/${classeId}/decisions`, null));
  }

  modifier(classeId: string, decisions: SaisieDecision[]): Promise<DecisionsClasseVue> {
    return firstValueFrom(this.http.put<DecisionsClasseVue>(`${API}/classes/${classeId}/decisions`, { decisions }));
  }

  valider(classeId: string): Promise<DecisionsClasseVue> {
    return firstValueFrom(this.http.post<DecisionsClasseVue>(`${API}/classes/${classeId}/decisions/validation`, null));
  }

  // ---------- Année suivante, plan de passage, clôture

  creerAnneeSuivante(anneeId: string, d: { libelle: string; debut: string; fin: string }): Promise<AnneeSuivanteVue> {
    return firstValueFrom(this.http.post<AnneeSuivanteVue>(`${API}/annees/${anneeId}/annee-suivante`, d));
  }

  plan(anneeId: string): Promise<PlanVue> {
    return firstValueFrom(this.http.get<PlanVue>(`${API}/annees/${anneeId}/plan-passage`));
  }

  appliquerPlan(
    anneeId: string,
    affectations: { inscriptionId: string; classeCibleId: string }[],
    conserverStatutBourse: boolean,
  ): Promise<ResultatPlan> {
    return firstValueFrom(
      this.http.post<ResultatPlan>(`${API}/annees/${anneeId}/plan-passage`, { affectations, conserverStatutBourse }),
    );
  }

  listes(anneeId: string, format: 'xlsx' | 'pdf'): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${API}/annees/${anneeId}/plan-passage/listes`, { responseType: 'blob', params: new HttpParams().set('format', format) }),
    );
  }

  cloturer(anneeId: string): Promise<AnneeVue> {
    return firstValueFrom(this.http.post<AnneeVue>(`${API}/annees/${anneeId}/cloture`, null));
  }

  ouvrir(anneeId: string): Promise<AnneeVue> {
    return firstValueFrom(this.http.post<AnneeVue>(`${API}/annees/${anneeId}/ouverture`, null));
  }
}
