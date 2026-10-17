import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import {
  CandidatureVue, ClassementOffre, CommissionResume, CommissionVue, DonneesCandidature, DonneesCommission, MembreSaisi,
  ResultatDesistement,
} from './modeles-recrutement';

/** Commission de recrutement (v0.49). */
@Injectable({ providedIn: 'root' })
export class RecrutementApi {
  private readonly http = inject(HttpClient);

  commissions(anneeId: string): Promise<CommissionResume[]> {
    return firstValueFrom(this.http.get<CommissionResume[]>(`${API}/annees/${anneeId}/commissions-recrutement`));
  }

  creer(anneeId: string, d: DonneesCommission): Promise<CommissionVue> {
    return firstValueFrom(this.http.post<CommissionVue>(`${API}/annees/${anneeId}/commissions-recrutement`, d));
  }

  commission(id: string): Promise<CommissionVue> {
    return firstValueFrom(this.http.get<CommissionVue>(`${API}/commissions-recrutement/${id}`));
  }

  modifier(id: string, d: DonneesCommission): Promise<CommissionVue> {
    return firstValueFrom(this.http.put<CommissionVue>(`${API}/commissions-recrutement/${id}`, d));
  }

  membres(id: string, membres: MembreSaisi[]): Promise<CommissionVue> {
    return firstValueFrom(this.http.put<CommissionVue>(`${API}/commissions-recrutement/${id}/membres`, { membres }));
  }

  offres(id: string, offres: { offreId: string; places: number }[]): Promise<CommissionVue> {
    return firstValueFrom(this.http.put<CommissionVue>(`${API}/commissions-recrutement/${id}/offres`, { offres }));
  }

  actualiserOffres(id: string): Promise<CommissionVue> {
    return firstValueFrom(this.http.post<CommissionVue>(`${API}/commissions-recrutement/${id}/offres/actualisation`, {}));
  }

  classement(id: string): Promise<ClassementOffre[]> {
    return firstValueFrom(this.http.get<ClassementOffre[]>(`${API}/commissions-recrutement/${id}/classement`));
  }

  deliberer(id: string, observations: string | null): Promise<CommissionVue> {
    return firstValueFrom(this.http.post<CommissionVue>(`${API}/commissions-recrutement/${id}/deliberation`, { observations }));
  }

  clore(id: string): Promise<CommissionVue> {
    return firstValueFrom(this.http.post<CommissionVue>(`${API}/commissions-recrutement/${id}/cloture`, {}));
  }

  procesVerbal(id: string): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/commissions-recrutement/${id}/proces-verbal`, { responseType: 'blob' }));
  }

  resultats(id: string, format: 'xlsx' | 'pdf'): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${API}/commissions-recrutement/${id}/resultats`, { params: { format }, responseType: 'blob' }),
    );
  }

  candidatures(id: string): Promise<CandidatureVue[]> {
    return firstValueFrom(this.http.get<CandidatureVue[]>(`${API}/commissions-recrutement/${id}/candidatures`));
  }

  enregistrer(id: string, d: DonneesCandidature): Promise<CandidatureVue> {
    return firstValueFrom(this.http.post<CandidatureVue>(`${API}/commissions-recrutement/${id}/candidatures`, d));
  }

  modifierCandidature(id: string, d: DonneesCandidature): Promise<CandidatureVue> {
    return firstValueFrom(this.http.put<CandidatureVue>(`${API}/candidatures/${id}`, d));
  }

  supprimerCandidature(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`${API}/candidatures/${id}`));
  }

  recevabilite(id: string, recevable: boolean, motif: string | null): Promise<CandidatureVue> {
    return firstValueFrom(this.http.post<CandidatureVue>(`${API}/candidatures/${id}/recevabilite`, { recevable, motif }));
  }

  inscrire(id: string, classeId: string): Promise<CandidatureVue> {
    return firstValueFrom(this.http.post<CandidatureVue>(`${API}/candidatures/${id}/inscription`, { classeId }));
  }

  desister(id: string, motif: string | null): Promise<ResultatDesistement> {
    return firstValueFrom(this.http.post<ResultatDesistement>(`${API}/candidatures/${id}/desistement`, { motif }));
  }
}
