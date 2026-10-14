import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import { ResultatReinitialisation } from './modeles-admin';
import {
  AdministrateurPaysVue,
  CompteDirectionVue,
  DirectionChemin,
  DirectionVue,
  DonneesDirection,
  DonneesMinistere,
  DonneesPays,
  IdentiteVue,
  MinistereVue,
  PaysVue,
  RapportImportDirections,
  ResultatNomination,
  ResultatNominationDirection,
} from './modeles-territoire';


/** Référentiel territorial (super administrateur) et identité de l'établissement (administrateur). */
@Injectable({ providedIn: 'root' })
export class TerritoireApi {
  private readonly http = inject(HttpClient);

  pays(): Promise<PaysVue[]> {
    return firstValueFrom(this.http.get<PaysVue[]>(`${API}/plateforme/territoire/pays`));
  }

  creerPays(d: DonneesPays): Promise<PaysVue> {
    return firstValueFrom(this.http.post<PaysVue>(`${API}/plateforme/territoire/pays`, d));
  }

  modifierPays(id: string, d: DonneesPays): Promise<PaysVue> {
    return firstValueFrom(this.http.put<PaysVue>(`${API}/plateforme/territoire/pays/${id}`, d));
  }

  ministeres(paysId: string): Promise<MinistereVue[]> {
    return firstValueFrom(this.http.get<MinistereVue[]>(`${API}/plateforme/territoire/pays/${paysId}/ministeres`));
  }

  creerMinistere(paysId: string, d: DonneesMinistere): Promise<MinistereVue> {
    return firstValueFrom(this.http.post<MinistereVue>(`${API}/plateforme/territoire/pays/${paysId}/ministeres`, d));
  }

  modifierMinistere(id: string, d: DonneesMinistere): Promise<MinistereVue> {
    return firstValueFrom(this.http.put<MinistereVue>(`${API}/plateforme/territoire/ministeres/${id}`, d));
  }

  directions(ministereId: string): Promise<DirectionVue[]> {
    return firstValueFrom(this.http.get<DirectionVue[]>(`${API}/plateforme/territoire/ministeres/${ministereId}/directions`));
  }

  creerDirection(ministereId: string, d: DonneesDirection): Promise<DirectionVue> {
    return firstValueFrom(this.http.post<DirectionVue>(`${API}/plateforme/territoire/ministeres/${ministereId}/directions`, d));
  }

  modifierDirection(id: string, d: DonneesDirection): Promise<DirectionVue> {
    return firstValueFrom(this.http.put<DirectionVue>(`${API}/plateforme/territoire/directions/${id}`, d));
  }

  importerDirections(ministereId: string, contenu: string, simulation: boolean): Promise<RapportImportDirections> {
    return firstValueFrom(
      this.http.post<RapportImportDirections>(`${API}/plateforme/territoire/ministeres/${ministereId}/directions/import`, { contenu, simulation }),
    );
  }

  /** Toutes les directions avec leur chemin complet (filtres, choix du rattachement). */
  directionsAvecChemin(): Promise<DirectionChemin[]> {
    return firstValueFrom(this.http.get<DirectionChemin[]>(`${API}/plateforme/territoire/directions`));
  }

  // ---------- Administrateurs pays (super administrateur, v0.36)

  administrateursPays(paysId: string): Promise<AdministrateurPaysVue[]> {
    return firstValueFrom(this.http.get<AdministrateurPaysVue[]>(`${API}/plateforme/territoire/pays/${paysId}/administrateurs`));
  }

  nommerAdministrateurPays(paysId: string, d: { telephone: string; nom: string; prenoms: string }): Promise<ResultatNomination> {
    return firstValueFrom(this.http.post<ResultatNomination>(`${API}/plateforme/territoire/pays/${paysId}/administrateurs`, d));
  }

  retirerAdministrateurPays(paysId: string, utilisateurId: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`${API}/plateforme/territoire/pays/${paysId}/administrateurs/${utilisateurId}`));
  }

  reinitialiserAdministrateurPays(paysId: string, utilisateurId: string): Promise<ResultatReinitialisation> {
    return firstValueFrom(
      this.http.post<ResultatReinitialisation>(`${API}/plateforme/territoire/pays/${paysId}/administrateurs/${utilisateurId}/reinitialisation`, null),
    );
  }

  // ---------- Comptes des directions (administrateur pays et super administrateur, v0.37)

  comptesDirection(directionId: string): Promise<CompteDirectionVue[]> {
    return firstValueFrom(this.http.get<CompteDirectionVue[]>(`${API}/plateforme/territoire/directions/${directionId}/comptes`));
  }

  nommerCompteDirection(directionId: string, d: { telephone: string; nom: string; prenoms: string }): Promise<ResultatNominationDirection> {
    return firstValueFrom(this.http.post<ResultatNominationDirection>(`${API}/plateforme/territoire/directions/${directionId}/comptes`, d));
  }

  retirerCompteDirection(directionId: string, utilisateurId: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`${API}/plateforme/territoire/directions/${directionId}/comptes/${utilisateurId}`));
  }

  reinitialiserCompteDirection(directionId: string, utilisateurId: string): Promise<ResultatReinitialisation> {
    return firstValueFrom(
      this.http.post<ResultatReinitialisation>(`${API}/plateforme/territoire/directions/${directionId}/comptes/${utilisateurId}/reinitialisation`, null),
    );
  }

  // ---------- Identité de l'établissement (administrateur)

  identite(): Promise<IdentiteVue> {
    return firstValueFrom(this.http.get<IdentiteVue>(`${API}/identite`));
  }

  logo(): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/identite/logo`, { responseType: 'blob' }));
  }

  enregistrerLogo(fichier: File): Promise<IdentiteVue> {
    const donnees = new FormData();
    donnees.append('fichier', fichier);
    return firstValueFrom(this.http.post<IdentiteVue>(`${API}/identite/logo`, donnees));
  }

  supprimerLogo(): Promise<IdentiteVue> {
    return firstValueFrom(this.http.delete<IdentiteVue>(`${API}/identite/logo`));
  }

  apercu(): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/identite/apercu`, { responseType: 'blob' }));
  }
}
