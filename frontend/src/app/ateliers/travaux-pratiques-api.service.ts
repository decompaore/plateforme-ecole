import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import {
  AchatTp,
  CategorieConsigne,
  ConsignesVue,
  DemandeCompteRendu,
  DonneesProduction,
  DonneesSeanceTp,
  EvenementVue,
  Gravite,
  LectureVue,
  LigneBesoinTp,
  OptionSeance,
  ProductionVue,
  SeanceTpVue,
  StatutProduction,
} from './modeles-tp';

/** Travaux pratiques dans les ateliers (v0.47.2) : séances, sécurité, production. */
@Injectable({ providedIn: 'root' })
export class TravauxPratiquesApi {
  private readonly http = inject(HttpClient);

  private get<T>(chemin: string, params?: Record<string, string>): Promise<T> {
    return firstValueFrom(this.http.get<T>(`${API}${chemin}`, { params }));
  }

  private post<T>(chemin: string, corps: unknown = null): Promise<T> {
    return firstValueFrom(this.http.post<T>(`${API}${chemin}`, corps));
  }

  private put<T>(chemin: string, corps: unknown): Promise<T> {
    return firstValueFrom(this.http.put<T>(`${API}${chemin}`, corps));
  }

  // ---------- Séances

  planning(atelierId: string, du: string, au: string): Promise<SeanceTpVue[]> {
    return this.get(`/ateliers/${atelierId}/seances-tp`, { du, au });
  }

  planningGeneral(du: string, au: string): Promise<SeanceTpVue[]> {
    return this.get('/seances-tp', { du, au });
  }

  miennes(): Promise<SeanceTpVue[]> {
    return this.get('/seances-tp/miennes');
  }

  achats(): Promise<AchatTp[]> {
    return this.get('/seances-tp/achats');
  }

  options(atelierId: string): Promise<OptionSeance[]> {
    return this.get(`/ateliers/${atelierId}/seances-tp/options`);
  }

  creer(atelierId: string, d: DonneesSeanceTp): Promise<SeanceTpVue> {
    return this.post(`/ateliers/${atelierId}/seances-tp`, d);
  }

  generer(atelierId: string, du: string, au: string): Promise<{ creees: number; dejaPresentes: number; sansEnseignant: number }> {
    return this.post(`/ateliers/${atelierId}/seances-tp/generation`, { du, au });
  }

  valider(id: string, accordees: LigneBesoinTp[]): Promise<SeanceTpVue> {
    return this.post(`/seances-tp/${id}/validation`, { accordees });
  }

  refuser(id: string, motif: string): Promise<SeanceTpVue> {
    return this.post(`/seances-tp/${id}/refus`, { motif });
  }

  annuler(id: string, motif: string): Promise<SeanceTpVue> {
    return this.post(`/seances-tp/${id}/annulation`, { motif });
  }

  preparer(id: string, observations: string | null): Promise<SeanceTpVue> {
    return this.post(`/seances-tp/${id}/preparation`, { observations });
  }

  compteRendu(id: string, d: DemandeCompteRendu): Promise<SeanceTpVue> {
    return this.post(`/seances-tp/${id}/compte-rendu`, d);
  }

  bilan(id: string): Promise<SeanceTpVue> {
    return this.post(`/seances-tp/${id}/bilan`);
  }

  // ---------- Sécurité

  consignes(atelierId: string): Promise<ConsignesVue> {
    return this.get(`/ateliers/${atelierId}/consignes`);
  }

  definirConsignes(atelierId: string, consignes: { categorie: CategorieConsigne; equipementId: string | null; texte: string }[]): Promise<ConsignesVue> {
    return this.put(`/ateliers/${atelierId}/consignes`, consignes);
  }

  lireConsignes(atelierId: string): Promise<ConsignesVue> {
    return this.post(`/ateliers/${atelierId}/consignes/lecture`);
  }

  lectures(atelierId: string): Promise<LectureVue[]> {
    return this.get(`/ateliers/${atelierId}/consignes/lectures`);
  }

  journal(atelierId: string): Promise<EvenementVue[]> {
    return this.get(`/ateliers/${atelierId}/hse`);
  }

  consigner(atelierId: string, d: { seanceId: string | null; gravite: Gravite; description: string; mesures: string | null }): Promise<EvenementVue> {
    return this.post(`/ateliers/${atelierId}/hse`, d);
  }

  // ---------- Production

  productions(atelierId?: string): Promise<ProductionVue[]> {
    return this.get('/productions', atelierId ? { atelier: atelierId } : undefined);
  }

  creerProduction(d: DonneesProduction): Promise<ProductionVue> {
    return this.post('/productions', d);
  }

  statutProduction(id: string, statut: StatutProduction): Promise<ProductionVue> {
    return this.post(`/productions/${id}/statut`, { statut, date: null, observations: null });
  }
}
