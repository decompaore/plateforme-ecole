import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import {
  DonneesDotation, DonneesLigne, Dotations, DotationResume, DotationVue, EtatVue, FiltreBesoins, LigneArbitree,
  Reception, RemonteeVue, VueBesoins,
} from './modeles-moyens';

function parametres(valeurs: Record<string, string | null | undefined>): HttpParams {
  let p = new HttpParams();
  for (const [cle, valeur] of Object.entries(valeurs)) {
    if (valeur) {
      p = p.set(cle, valeur);
    }
  }
  return p;
}

/** Besoins et dotations (v0.48) : côté établissement et côté directions / niveau central. */
@Injectable({ providedIn: 'root' })
export class MoyensApi {
  private readonly http = inject(HttpClient);

  // ------------------------------------------------------------ établissement

  etat(anneeId?: string | null): Promise<EtatVue> {
    return firstValueFrom(this.http.get<EtatVue>(`${API}/etat-besoins`, { params: parametres({ anneeId }) }));
  }

  ajouterLigne(anneeId: string, d: DonneesLigne): Promise<EtatVue> {
    return firstValueFrom(this.http.post<EtatVue>(`${API}/etat-besoins/${anneeId}/lignes`, d));
  }

  modifierLigne(id: string, d: DonneesLigne): Promise<EtatVue> {
    return firstValueFrom(this.http.put<EtatVue>(`${API}/etat-besoins/lignes/${id}`, d));
  }

  supprimerLigne(id: string): Promise<EtatVue> {
    return firstValueFrom(this.http.delete<EtatVue>(`${API}/etat-besoins/lignes/${id}`));
  }

  transmettreEtat(anneeId: string, observations: string | null): Promise<EtatVue> {
    return firstValueFrom(this.http.post<EtatVue>(`${API}/etat-besoins/${anneeId}/transmettre`, { observations }));
  }

  dotationsRecues(): Promise<DotationResume[]> {
    return firstValueFrom(this.http.get<DotationResume[]>(`${API}/dotations-recues`));
  }

  dotationRecue(id: string): Promise<DotationVue> {
    return firstValueFrom(this.http.get<DotationVue>(`${API}/dotations-recues/${id}`));
  }

  accuserReceptionEtablissement(id: string, r: Reception): Promise<DotationVue> {
    return firstValueFrom(this.http.post<DotationVue>(`${API}/dotations-recues/${id}/accuser`, r));
  }

  // ------------------------------------------------------------ directions et niveau central

  besoins(f: FiltreBesoins): Promise<VueBesoins> {
    return firstValueFrom(this.http.get<VueBesoins>(`${API}/pilotage/besoins`, { params: parametres({ ...f }) }));
  }

  exporterBesoins(f: FiltreBesoins, format: 'xlsx' | 'pdf'): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${API}/pilotage/besoins/export`, { params: parametres({ ...f, format }), responseType: 'blob' }),
    );
  }

  remontee(id: string, direction?: string | null): Promise<RemonteeVue> {
    return firstValueFrom(
      this.http.get<RemonteeVue>(`${API}/pilotage/besoins/remontees/${id}`, { params: parametres({ direction }) }),
    );
  }

  arbitrer(id: string, lignes: LigneArbitree[]): Promise<RemonteeVue> {
    return firstValueFrom(this.http.put<RemonteeVue>(`${API}/pilotage/besoins/remontees/${id}/arbitrages`, { lignes }));
  }

  renvoyerRemontee(id: string, motif: string): Promise<RemonteeVue> {
    return firstValueFrom(this.http.post<RemonteeVue>(`${API}/pilotage/besoins/remontees/${id}/renvoyer`, { motif }));
  }

  transmettreBesoins(annee: string, observations: string | null): Promise<VueBesoins> {
    return firstValueFrom(this.http.post<VueBesoins>(`${API}/pilotage/besoins/transmettre`, { annee, observations }));
  }

  renvoyerDirection(id: string, annee: string, motif: string): Promise<VueBesoins> {
    return firstValueFrom(this.http.post<VueBesoins>(`${API}/pilotage/besoins/directions/${id}/renvoyer`, { annee, motif }));
  }

  dotations(f: FiltreBesoins): Promise<Dotations> {
    return firstValueFrom(this.http.get<Dotations>(`${API}/pilotage/dotations`, { params: parametres({ ...f }) }));
  }

  dotation(id: string): Promise<DotationVue> {
    return firstValueFrom(this.http.get<DotationVue>(`${API}/pilotage/dotations/${id}`));
  }

  creerDotation(d: DonneesDotation): Promise<DotationVue> {
    return firstValueFrom(this.http.post<DotationVue>(`${API}/pilotage/dotations`, d));
  }

  modifierDotation(id: string, d: DonneesDotation): Promise<DotationVue> {
    return firstValueFrom(this.http.put<DotationVue>(`${API}/pilotage/dotations/${id}`, d));
  }

  supprimerDotation(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`${API}/pilotage/dotations/${id}`));
  }

  envoyerDotation(id: string): Promise<DotationVue> {
    return firstValueFrom(this.http.post<DotationVue>(`${API}/pilotage/dotations/${id}/envoyer`, {}));
  }

  accuserReceptionDirection(id: string, r: Reception): Promise<DotationVue> {
    return firstValueFrom(this.http.post<DotationVue>(`${API}/pilotage/dotations/${id}/accuser`, r));
  }
}
