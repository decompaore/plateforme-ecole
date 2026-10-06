import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import {
  DemandePaiement,
  DonneesFrais,
  EtatClasseVue,
  ExonerationVue,
  FraisVue,
  JournalVue,
  OrganismeVue,
  PaiementVue,
  ParametresScolarite,
  PriseEnChargeVue,
  ResultatRelancesVue,
  SituationVue,
  TypeOrganisme,
} from './modeles-scolarite';

/** Appels de l'API Scolarité (en ligne uniquement : l'encaissement exige le serveur). */
@Injectable({ providedIn: 'root' })
export class ScolariteApi {
  private readonly http = inject(HttpClient);

  private get<T>(chemin: string, params?: Record<string, string>): Promise<T> {
    return firstValueFrom(this.http.get<T>(API + chemin, { params: params ? new HttpParams({ fromObject: params }) : undefined }));
  }

  private fichier(chemin: string): Promise<Blob> {
    return firstValueFrom(this.http.get(API + chemin, { responseType: 'blob' }));
  }

  private post<T>(chemin: string, corps: unknown, params?: Record<string, string>): Promise<T> {
    return firstValueFrom(this.http.post<T>(API + chemin, corps, { params: params ? new HttpParams({ fromObject: params }) : undefined }));
  }

  private put<T>(chemin: string, corps: unknown): Promise<T> {
    return firstValueFrom(this.http.put<T>(API + chemin, corps));
  }

  private delete(chemin: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(API + chemin));
  }

  // ---------- Paramètres, organismes, frais

  parametres(): Promise<ParametresScolarite> {
    return this.get('/parametres/scolarite');
  }

  modifierParametres(p: ParametresScolarite): Promise<ParametresScolarite> {
    return this.put('/parametres/scolarite', p);
  }

  organismes(): Promise<OrganismeVue[]> {
    return this.get('/organismes');
  }

  creerOrganisme(d: { nom: string; type: TypeOrganisme; telephone: string | null }): Promise<OrganismeVue> {
    return this.post('/organismes', d);
  }

  modifierOrganisme(id: string, d: { nom: string; type: TypeOrganisme; telephone: string | null; actif: boolean }): Promise<OrganismeVue> {
    return this.put(`/organismes/${id}`, d);
  }

  frais(anneeId: string): Promise<FraisVue[]> {
    return this.get(`/annees/${anneeId}/frais`);
  }

  creerFrais(anneeId: string, d: DonneesFrais): Promise<FraisVue> {
    return this.post(`/annees/${anneeId}/frais`, d);
  }

  modifierFrais(id: string, d: DonneesFrais): Promise<FraisVue> {
    return this.put(`/frais/${id}`, d);
  }

  supprimerFrais(id: string): Promise<void> {
    return this.delete(`/frais/${id}`);
  }

  // ---------- Élève : situation, frais facultatifs, bourses, exonérations

  situation(inscriptionId: string): Promise<SituationVue> {
    return this.get(`/inscriptions/${inscriptionId}/scolarite`);
  }

  souscrire(inscriptionId: string, fraisId: string): Promise<void> {
    return this.put(`/inscriptions/${inscriptionId}/frais/${fraisId}`, null);
  }

  resilier(inscriptionId: string, fraisId: string): Promise<void> {
    return this.delete(`/inscriptions/${inscriptionId}/frais/${fraisId}`);
  }

  /** Null quand l'élève n'a pas de prise en charge enregistrée (le serveur répond 404). */
  async priseEnCharge(inscriptionId: string): Promise<PriseEnChargeVue | null> {
    try {
      return await this.get<PriseEnChargeVue>(`/inscriptions/${inscriptionId}/prise-en-charge`);
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 404) {
        return null;
      }
      throw e;
    }
  }

  definirPriseEnCharge(
    inscriptionId: string,
    d: { organismeId: string; taux: number | null; referenceDecision: string | null; dateDecision: string | null },
  ): Promise<PriseEnChargeVue> {
    return this.put(`/inscriptions/${inscriptionId}/prise-en-charge`, d);
  }

  supprimerPriseEnCharge(inscriptionId: string): Promise<void> {
    return this.delete(`/inscriptions/${inscriptionId}/prise-en-charge`);
  }

  exonerer(inscriptionId: string, fraisId: string, d: { montant: number; motif: string }): Promise<ExonerationVue> {
    return this.put(`/inscriptions/${inscriptionId}/exonerations/${fraisId}`, d);
  }

  retirerExoneration(inscriptionId: string, fraisId: string): Promise<void> {
    return this.delete(`/inscriptions/${inscriptionId}/exonerations/${fraisId}`);
  }

  // ---------- Encaissements

  encaisser(inscriptionId: string, d: DemandePaiement): Promise<PaiementVue> {
    return this.post(`/inscriptions/${inscriptionId}/paiements`, d);
  }

  annuler(paiementId: string, motif: string): Promise<PaiementVue> {
    return this.post(`/paiements/${paiementId}/annulation`, { motif });
  }

  recu(paiementId: string): Promise<Blob> {
    return this.fichier(`/paiements/${paiementId}/recu`);
  }

  journal(du: string, au: string): Promise<JournalVue> {
    return this.get('/paiements', { du, au });
  }

  // ---------- Classes et relances

  etatClasse(classeId: string): Promise<EtatClasseVue> {
    return this.get(`/classes/${classeId}/scolarite`);
  }

  retards(classeId: string): Promise<Blob> {
    return this.fichier(`/classes/${classeId}/scolarite/retards`);
  }

  /** Sans classe : toutes les familles en retard de l'année active. */
  relancer(classeId?: string): Promise<ResultatRelancesVue> {
    return this.post('/scolarite/relances', null, classeId ? { classeId } : undefined);
  }
}

/** Enregistre un fichier reçu du serveur (reçu PDF, liste Excel). */
export function enregistrerFichier(fichier: Blob, nom: string): void {
  const url = URL.createObjectURL(fichier);
  const lien = document.createElement('a');
  lien.href = url;
  lien.download = nom;
  lien.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
