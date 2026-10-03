import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import { FicheVue, SaisieSequence, SuiviProgressionVue } from './modeles-progression';

/** Appels de l'API des fiches de progression (en ligne). */
@Injectable({ providedIn: 'root' })
export class ProgressionApi {
  private readonly http = inject(HttpClient);

  mesFiches(): Promise<SuiviProgressionVue[]> {
    return firstValueFrom(this.http.get<SuiviProgressionVue[]>(`${API}/espace-enseignant/progressions`));
  }

  suivi(anneeId: string): Promise<SuiviProgressionVue[]> {
    return firstValueFrom(this.http.get<SuiviProgressionVue[]>(`${API}/annees/${anneeId}/progressions`));
  }

  fiche(classeId: string, matiereId: string): Promise<FicheVue> {
    return firstValueFrom(this.http.get<FicheVue>(`${API}/classes/${classeId}/matieres/${matiereId}/progression`));
  }

  enregistrer(classeId: string, matiereId: string, sequences: SaisieSequence[]): Promise<FicheVue> {
    return firstValueFrom(this.http.put<FicheVue>(`${API}/classes/${classeId}/matieres/${matiereId}/progression`, { sequences }));
  }

  soumettre(classeId: string, matiereId: string): Promise<FicheVue> {
    return firstValueFrom(this.http.post<FicheVue>(`${API}/classes/${classeId}/matieres/${matiereId}/progression/soumission`, null));
  }

  viser(classeId: string, matiereId: string, accepte: boolean, commentaire: string | null): Promise<FicheVue> {
    return firstValueFrom(this.http.post<FicheVue>(`${API}/classes/${classeId}/matieres/${matiereId}/progression/visa`, {
        accepte,
        commentaire,
      }));
  }
}
