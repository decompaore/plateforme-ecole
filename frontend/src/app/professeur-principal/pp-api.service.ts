import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { FicheEnseignant } from '../core/modeles';
import { API } from '../core/session.service';
import {
  ClassePrincipale, DonneesReclamation, DonneesSaisine, EvaluationResume, ReclamationVue, ResultatsPeriode, SaisineVue,
  StatutReclamation, VerificationVue,
} from './modeles-pp';

/** Professeur principal (v0.49.1). */
@Injectable({ providedIn: 'root' })
export class ProfesseurPrincipalApi {
  private readonly http = inject(HttpClient);

  // ------------------------------------------------------------ désignation

  classes(anneeId: string): Promise<ClassePrincipale[]> {
    return firstValueFrom(this.http.get<ClassePrincipale[]>(`${API}/annees/${anneeId}/professeurs-principaux`));
  }

  mesClasses(anneeId: string): Promise<ClassePrincipale[]> {
    return firstValueFrom(
      this.http.get<ClassePrincipale[]>(`${API}/espace-enseignant/classes-principales`, { params: { anneeId } }),
    );
  }

  designer(classeId: string, utilisateurId: string): Promise<ClassePrincipale> {
    return firstValueFrom(this.http.put<ClassePrincipale>(`${API}/classes/${classeId}/professeur-principal`, { utilisateurId }));
  }

  retirer(classeId: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`${API}/classes/${classeId}/professeur-principal`));
  }

  /** Classes et matières de l'enseignant connecté. */
  affectations(): Promise<FicheEnseignant> {
    return firstValueFrom(this.http.get<FicheEnseignant>(`${API}/espace-enseignant/affectations`));
  }

  // ------------------------------------------------------------ dossiers

  saisines(anneeId: string, classeId?: string): Promise<SaisineVue[]> {
    const params: Record<string, string> = { anneeId };
    if (classeId) {
      params['classeId'] = classeId;
    }
    return firstValueFrom(this.http.get<SaisineVue[]>(`${API}/saisines`, { params }));
  }

  ouvrir(classeId: string, d: DonneesSaisine): Promise<SaisineVue> {
    return firstValueFrom(this.http.post<SaisineVue>(`${API}/classes/${classeId}/saisines`, d));
  }

  saisine(id: string): Promise<SaisineVue> {
    return firstValueFrom(this.http.get<SaisineVue>(`${API}/saisines/${id}`));
  }

  ajouterSuite(id: string, texte: string): Promise<SaisineVue> {
    return firstValueFrom(this.http.post<SaisineVue>(`${API}/saisines/${id}/suites`, { texte }));
  }

  informerEnseignant(id: string): Promise<SaisineVue> {
    return firstValueFrom(this.http.post<SaisineVue>(`${API}/saisines/${id}/information-enseignant`, {}));
  }

  transmettre(id: string, texte: string | null): Promise<SaisineVue> {
    return firstValueFrom(this.http.post<SaisineVue>(`${API}/saisines/${id}/transmission`, { texte }));
  }

  clore(id: string, texte: string): Promise<SaisineVue> {
    return firstValueFrom(this.http.post<SaisineVue>(`${API}/saisines/${id}/cloture`, { texte }));
  }

  // ------------------------------------------------------------ vérification des notes

  verification(classeId: string, periodeId: string): Promise<VerificationVue> {
    return firstValueFrom(
      this.http.get<VerificationVue>(`${API}/classes/${classeId}/verification-notes`, { params: { periodeId } }),
    );
  }

  reclamer(classeId: string, d: DonneesReclamation): Promise<VerificationVue> {
    return firstValueFrom(this.http.post<VerificationVue>(`${API}/classes/${classeId}/verification-notes/reclamations`, d));
  }

  valider(classeId: string, periodeId: string, observations: string | null): Promise<VerificationVue> {
    return firstValueFrom(
      this.http.post<VerificationVue>(`${API}/classes/${classeId}/verification-notes/validation`, { periodeId, observations }),
    );
  }

  repondre(id: string, decision: StatutReclamation, reponse: string | null): Promise<ReclamationVue> {
    return firstValueFrom(this.http.post<ReclamationVue>(`${API}/reclamations-notes/${id}/reponse`, { decision, reponse }));
  }

  mesReclamations(): Promise<ReclamationVue[]> {
    return firstValueFrom(this.http.get<ReclamationVue[]>(`${API}/espace-enseignant/reclamations-notes`));
  }

  /** Résultats de la classe (moyennes par matière) : le professeur principal les parcourt avec les élèves. */
  resultats(classeId: string, periodeId: string): Promise<ResultatsPeriode> {
    return firstValueFrom(this.http.get<ResultatsPeriode>(`${API}/classes/${classeId}/resultats`, { params: { periodeId } }));
  }

  evaluations(classeId: string, periodeId: string): Promise<EvaluationResume[]> {
    return firstValueFrom(this.http.get<EvaluationResume[]>(`${API}/classes/${classeId}/evaluations`, { params: { periodeId } }));
  }
}
