import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import {
  CreneauVue,
  DemandeGeneration,
  DonneesSeance,
  EmploiDuTempsVue,
  MonEmploiVue,
  ResultatGeneration,
  SaisieCreneau,
} from './modeles-emploi';

/** Appels de l'API des emplois du temps (en ligne). */
@Injectable({ providedIn: 'root' })
export class EmploiApi {
  private readonly http = inject(HttpClient);

  creneaux(anneeId: string): Promise<CreneauVue[]> {
    return firstValueFrom(this.http.get<CreneauVue[]>(`${API}/annees/${anneeId}/creneaux`));
  }

  definirGrille(anneeId: string, creneaux: SaisieCreneau[]): Promise<CreneauVue[]> {
    return firstValueFrom(this.http.put<CreneauVue[]>(`${API}/annees/${anneeId}/creneaux`, { creneaux }));
  }

  emploi(anneeId: string): Promise<EmploiDuTempsVue> {
    return firstValueFrom(this.http.get<EmploiDuTempsVue>(`${API}/annees/${anneeId}/emploi-du-temps`));
  }

  placer(id: string, d: DonneesSeance): Promise<EmploiDuTempsVue> {
    return firstValueFrom(this.http.put<EmploiDuTempsVue>(`${API}/seances-emploi/${id}`, d));
  }

  retirer(id: string): Promise<EmploiDuTempsVue> {
    return firstValueFrom(this.http.delete<EmploiDuTempsVue>(`${API}/seances-emploi/${id}`));
  }

  generer(anneeId: string, d: DemandeGeneration): Promise<ResultatGeneration> {
    return firstValueFrom(this.http.post<ResultatGeneration>(`${API}/annees/${anneeId}/emploi-du-temps/generation`, d));
  }

  publier(anneeId: string): Promise<EmploiDuTempsVue> {
    return firstValueFrom(this.http.post<EmploiDuTempsVue>(`${API}/annees/${anneeId}/emploi-du-temps/publication`, null));
  }

  retirerPublication(anneeId: string): Promise<EmploiDuTempsVue> {
    return firstValueFrom(this.http.delete<EmploiDuTempsVue>(`${API}/annees/${anneeId}/emploi-du-temps/publication`));
  }

  monEmploi(): Promise<MonEmploiVue> {
    return firstValueFrom(this.http.get<MonEmploiVue>(`${API}/espace-enseignant/emploi-du-temps`));
  }
}
