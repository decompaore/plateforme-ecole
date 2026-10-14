import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import {
  AbsencesParMatiereVue,
  AbsenceVue,
  ConvocationVue,
  EleveDuJourVue,
  HistoriqueVue,
  IncidentVue,
  JustificatifVue,
  SaisieIncident,
  StatutConvocation,
  SyntheseEleveVue,
  TypeJustificatif,
} from './modeles-vs';

/** Appels de l'API pour la vie scolaire (en ligne uniquement, comme l'administration). */
@Injectable({ providedIn: 'root' })
export class VieScolaireApi {
  private readonly http = inject(HttpClient);

  private get<T>(chemin: string, params?: Record<string, string>): Promise<T> {
    return firstValueFrom(this.http.get<T>(API + chemin, { params: params ? new HttpParams({ fromObject: params }) : undefined }));
  }

  private post<T>(chemin: string, corps: unknown): Promise<T> {
    return firstValueFrom(this.http.post<T>(API + chemin, corps));
  }

  // ---------- Absences

  absencesDuJour(date: string): Promise<EleveDuJourVue[]> {
    return this.get('/absences/jour', { date });
  }

  /** Période facultative : toute l'année si `du` et `au` sont omis. */
  absencesParMatiere(classeId: string, du?: string, au?: string): Promise<AbsencesParMatiereVue[]> {
    return this.get(`/classes/${classeId}/absences/matieres`, du && au ? { du, au } : undefined);
  }

  syntheseClasse(classeId: string, du?: string, au?: string): Promise<SyntheseEleveVue[]> {
    return this.get(`/classes/${classeId}/absences/synthese`, du && au ? { du, au } : undefined);
  }

  absences(inscriptionId: string): Promise<AbsenceVue[]> {
    return this.get(`/inscriptions/${inscriptionId}/absences`);
  }

  justificatifs(inscriptionId: string): Promise<JustificatifVue[]> {
    return this.get(`/inscriptions/${inscriptionId}/justificatifs`);
  }

  justifier(inscriptionId: string, d: { du: string; au: string; type: TypeJustificatif; motif: string | null }): Promise<JustificatifVue> {
    return this.post(`/inscriptions/${inscriptionId}/justificatifs`, d);
  }

  supprimerJustificatif(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`${API}/justificatifs/${id}`));
  }

  // ---------- Incidents et convocations

  historique(inscriptionId: string): Promise<HistoriqueVue> {
    return this.get(`/inscriptions/${inscriptionId}/vie-scolaire`);
  }

  signaler(inscriptionId: string, s: SaisieIncident): Promise<IncidentVue> {
    return this.post(`/inscriptions/${inscriptionId}/incidents`, s);
  }

  annulerIncident(id: string, motif: string): Promise<IncidentVue> {
    return this.post(`/incidents/${id}/annulation`, { motif });
  }

  convoquer(inscriptionId: string, d: { rendezVous: string; motif: string; incidentId: string | null }): Promise<ConvocationVue> {
    return this.post(`/inscriptions/${inscriptionId}/convocations`, d);
  }

  cloturer(id: string, statut: StatutConvocation, compteRendu: string | null): Promise<ConvocationVue> {
    return this.post(`/convocations/${id}/cloture`, { statut, compteRendu });
  }

  agenda(du: string, au: string): Promise<ConvocationVue[]> {
    return this.get('/convocations', { du, au });
  }
}
