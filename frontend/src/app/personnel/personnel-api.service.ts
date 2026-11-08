import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import {
  AbsenceVue, ActeVue, DonneesAbsence, DonneesAgent, DossierVue, ListePersonnel, NatureDiplome, NaturePiece, TypeActe,
} from './modeles-personnel';

/** Appels de l'API du dossier du personnel (v0.51). */
@Injectable({ providedIn: 'root' })
export class PersonnelApi {
  private readonly http = inject(HttpClient);

  lister(): Promise<ListePersonnel> {
    return firstValueFrom(this.http.get<ListePersonnel>(`${API}/personnel`));
  }

  reprendre(): Promise<{ crees: number; dejaPresents: number }> {
    return firstValueFrom(this.http.post<{ crees: number; dejaPresents: number }>(`${API}/personnel/reprise`, null));
  }

  creer(d: DonneesAgent): Promise<DossierVue> {
    return firstValueFrom(this.http.post<DossierVue>(`${API}/personnel`, d));
  }

  dossier(id: string): Promise<DossierVue> {
    return firstValueFrom(this.http.get<DossierVue>(`${API}/personnel/${id}`));
  }

  monDossier(): Promise<DossierVue> {
    return firstValueFrom(this.http.get<DossierVue>(`${API}/mon-dossier`));
  }

  modifier(id: string, d: DonneesAgent): Promise<DossierVue> {
    return firstValueFrom(this.http.put<DossierVue>(`${API}/personnel/${id}`, d));
  }

  ajouterDiplome(id: string, d: { nature: NatureDiplome; intitule: string; specialite: string | null; delivrePar: string | null; annee: number | null; pieceId: string | null }): Promise<DossierVue> {
    return firstValueFrom(this.http.post<DossierVue>(`${API}/personnel/${id}/diplomes`, d));
  }

  retirerDiplome(diplomeId: string): Promise<DossierVue> {
    return firstValueFrom(this.http.delete<DossierVue>(`${API}/personnel/diplomes/${diplomeId}`));
  }

  deposerPiece(id: string, fichier: File, nature: NaturePiece, objet: string, reference: string, datePiece: string): Promise<DossierVue> {
    const f = new FormData();
    f.append('fichier', fichier);
    f.append('nature', nature);
    f.append('objet', objet);
    if (reference.trim()) f.append('reference', reference.trim());
    if (datePiece) f.append('datePiece', datePiece);
    return firstValueFrom(this.http.post<DossierVue>(`${API}/personnel/${id}/pieces`, f));
  }

  piece(pieceId: string): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/personnel/pieces/${pieceId}`, { responseType: 'blob' }));
  }

  retirerPiece(pieceId: string): Promise<DossierVue> {
    return firstValueFrom(this.http.delete<DossierVue>(`${API}/personnel/pieces/${pieceId}`));
  }

  etablirActe(id: string, type: TypeActe, dateEffet: string | null, complement: string | null): Promise<ActeVue> {
    return firstValueFrom(this.http.post<ActeVue>(`${API}/personnel/${id}/actes`, { type, dateEffet, complement }));
  }

  signerActe(acteId: string): Promise<ActeVue> {
    return firstValueFrom(this.http.post<ActeVue>(`${API}/personnel/actes/${acteId}/signature`, null));
  }

  annulerActe(acteId: string, texte: string): Promise<ActeVue> {
    return firstValueFrom(this.http.post<ActeVue>(`${API}/personnel/actes/${acteId}/annulation`, { texte }));
  }

  actePdf(acteId: string): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/personnel/actes/${acteId}/pdf`, { responseType: 'blob' }));
  }

  suiviAbsences(): Promise<AbsenceVue[]> {
    return firstValueFrom(this.http.get<AbsenceVue[]>(`${API}/personnel/absences`));
  }

  saisirAbsence(id: string, d: DonneesAbsence): Promise<AbsenceVue> {
    return firstValueFrom(this.http.post<AbsenceVue>(`${API}/personnel/${id}/absences`, d));
  }

  demanderAbsence(d: DonneesAbsence): Promise<AbsenceVue> {
    return firstValueFrom(this.http.post<AbsenceVue>(`${API}/mon-dossier/absences`, d));
  }

  decider(absenceId: string, accorder: boolean, reponse: string | null): Promise<AbsenceVue> {
    return firstValueFrom(this.http.post<AbsenceVue>(`${API}/personnel/absences/${absenceId}/decision`, { accorder, reponse }));
  }

  annulerAbsence(absenceId: string, texte: string | null): Promise<AbsenceVue> {
    return firstValueFrom(this.http.post<AbsenceVue>(`${API}/personnel/absences/${absenceId}/annulation`, { texte }));
  }

  signaler(texte: string): Promise<DossierVue> {
    return firstValueFrom(this.http.post<DossierVue>(`${API}/mon-dossier/signalements`, { texte }));
  }

  traiterSignalement(signalementId: string, texte: string): Promise<DossierVue> {
    return firstValueFrom(this.http.post<DossierVue>(`${API}/personnel/signalements/${signalementId}/traitement`, { texte }));
  }
}
