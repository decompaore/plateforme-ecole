import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import {
  DonneesInterpellation, FicheVue, InterpellationVue, PropositionInterpellation, SaisieSequence, SeanceVue, SuiviCahierVue,
  SuiviProgressionVue, VisaCahierVue,
} from './modeles-progression';

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

  seances(classeId: string, matiereId: string): Promise<SeanceVue[]> {
    return firstValueFrom(this.http.get<SeanceVue[]>(`${API}/classes/${classeId}/matieres/${matiereId}/cahier-textes`));
  }

  // ---------- Visa mensuel des cahiers de textes (v0.47)

  visasCahier(classeId: string, matiereId: string): Promise<VisaCahierVue[]> {
    return firstValueFrom(this.http.get<VisaCahierVue[]>(`${API}/classes/${classeId}/matieres/${matiereId}/cahier-textes/visas`));
  }

  viserCahier(classeId: string, matiereId: string, mois: string, observation: string | null): Promise<VisaCahierVue> {
    return firstValueFrom(
      this.http.post<VisaCahierVue>(`${API}/classes/${classeId}/matieres/${matiereId}/cahier-textes/visas`, { mois, observation }),
    );
  }

  suiviCahiers(anneeId: string, mois: string): Promise<SuiviCahierVue[]> {
    return firstValueFrom(this.http.get<SuiviCahierVue[]>(`${API}/annees/${anneeId}/cahiers-textes`, { params: { mois } }));
  }

  // ---------- Interpellation des enseignants (v0.48.1)

  propositionInterpellation(classeId: string, matiereId: string): Promise<PropositionInterpellation> {
    return firstValueFrom(
      this.http.get<PropositionInterpellation>(`${API}/classes/${classeId}/matieres/${matiereId}/interpellations/proposition`),
    );
  }

  interpeller(classeId: string, matiereId: string, d: DonneesInterpellation): Promise<InterpellationVue> {
    return firstValueFrom(this.http.post<InterpellationVue>(`${API}/classes/${classeId}/matieres/${matiereId}/interpellations`, d));
  }

  interpellations(anneeId: string | null): Promise<InterpellationVue[]> {
    const params: Record<string, string> = anneeId ? { anneeId } : {};
    return firstValueFrom(this.http.get<InterpellationVue[]>(`${API}/interpellations`, { params }));
  }

  mesInterpellations(): Promise<InterpellationVue[]> {
    return firstValueFrom(this.http.get<InterpellationVue[]>(`${API}/espace-enseignant/interpellations`));
  }

  interpellation(id: string): Promise<InterpellationVue> {
    return firstValueFrom(this.http.get<InterpellationVue>(`${API}/interpellations/${id}`));
  }

  repondreInterpellation(id: string, reponse: string): Promise<InterpellationVue> {
    return firstValueFrom(this.http.post<InterpellationVue>(`${API}/interpellations/${id}/reponse`, { reponse }));
  }

  cloreInterpellation(id: string, suite: string | null): Promise<InterpellationVue> {
    return firstValueFrom(this.http.post<InterpellationVue>(`${API}/interpellations/${id}/cloture`, { suite }));
  }

  lettreInterpellation(id: string): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/interpellations/${id}/lettre`, { responseType: 'blob' }));
  }
}
