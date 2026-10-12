import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import { PeriodeVue } from '../admin/modeles-admin';
import { AppreciationVue, AvisVue, GenerationVue, SaisieAvis, VisaVue } from './modeles-bulletins';

/** Appels de l'API des bulletins (en ligne uniquement). */
@Injectable({ providedIn: 'root' })
export class BulletinsApi {
  private readonly http = inject(HttpClient);

  private base(classeId: string, periodeId: string): string {
    return `${API}/classes/${classeId}/periodes/${periodeId}`;
  }

  // ---------- Périodes

  verrouiller(periodeId: string): Promise<PeriodeVue> {
    return firstValueFrom(this.http.post<PeriodeVue>(`${API}/periodes/${periodeId}/verrouillage`, null));
  }

  deverrouiller(periodeId: string): Promise<PeriodeVue> {
    return firstValueFrom(this.http.post<PeriodeVue>(`${API}/periodes/${periodeId}/deverrouillage`, null));
  }

  // ---------- Conseil de classe (direction)

  conseil(classeId: string, periodeId: string): Promise<AvisVue[]> {
    return firstValueFrom(this.http.get<AvisVue[]>(`${this.base(classeId, periodeId)}/conseil`));
  }

  enregistrerConseil(classeId: string, periodeId: string, avis: SaisieAvis[]): Promise<AvisVue[]> {
    return firstValueFrom(this.http.put<AvisVue[]>(`${this.base(classeId, periodeId)}/conseil`, { avis }));
  }

  // ---------- Appréciations (enseignant de la matière, ou direction)

  appreciations(classeId: string, periodeId: string, matiereId: string): Promise<AppreciationVue[]> {
    return firstValueFrom(
      this.http.get<AppreciationVue[]>(`${this.base(classeId, periodeId)}/appreciations`, { params: { matiereId } }),
    );
  }

  enregistrerAppreciations(
    classeId: string,
    periodeId: string,
    matiereId: string,
    appreciations: { inscriptionId: string; texte: string }[],
  ): Promise<AppreciationVue[]> {
    return firstValueFrom(
      this.http.put<AppreciationVue[]>(`${this.base(classeId, periodeId)}/appreciations`, { matiereId, appreciations }),
    );
  }

  // ---------- Visas des matières (v0.44)

  visas(classeId: string, periodeId: string): Promise<VisaVue[]> {
    return firstValueFrom(this.http.get<VisaVue[]>(`${this.base(classeId, periodeId)}/visas`));
  }

  viser(classeId: string, periodeId: string, matiereId: string): Promise<VisaVue[]> {
    return firstValueFrom(this.http.post<VisaVue[]>(`${this.base(classeId, periodeId)}/matieres/${matiereId}/visa`, null));
  }

  retirerVisa(classeId: string, periodeId: string, matiereId: string): Promise<VisaVue[]> {
    return firstValueFrom(this.http.delete<VisaVue[]>(`${this.base(classeId, periodeId)}/matieres/${matiereId}/visa`));
  }

  // ---------- Génération, impression, publication

  /** Bulletins de la période, ou null s'ils n'ont pas encore été générés. */
  async bulletins(classeId: string, periodeId: string): Promise<GenerationVue | null> {
    try {
      return await firstValueFrom(this.http.get<GenerationVue>(`${this.base(classeId, periodeId)}/bulletins`));
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 404) {
        return null;
      }
      throw e;
    }
  }

  generer(classeId: string, periodeId: string): Promise<GenerationVue> {
    return firstValueFrom(this.http.post<GenerationVue>(`${this.base(classeId, periodeId)}/bulletins`, null));
  }

  publier(classeId: string, periodeId: string): Promise<GenerationVue> {
    return firstValueFrom(this.http.post<GenerationVue>(`${this.base(classeId, periodeId)}/bulletins/publication`, null));
  }

  pdfClasse(classeId: string, periodeId: string): Promise<Blob> {
    return firstValueFrom(this.http.get(`${this.base(classeId, periodeId)}/bulletins/pdf`, { responseType: 'blob' }));
  }

  pdf(bulletinId: string): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/bulletins/${bulletinId}/pdf`, { responseType: 'blob' }));
  }
}
