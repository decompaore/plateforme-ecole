import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import { FiltrePilotage, TableauPilotage } from './modeles-pilotage';

function parametres(f: FiltrePilotage, format?: 'xlsx' | 'pdf'): HttpParams {
  let p = new HttpParams();
  for (const [cle, valeur] of Object.entries({ ...f, format })) {
    if (valeur) {
      p = p.set(cle, valeur);
    }
  }
  return p;
}

/** Pilotage : comptes de direction, administrateurs pays, super administrateur. */
@Injectable({ providedIn: 'root' })
export class PilotageApi {
  private readonly http = inject(HttpClient);

  tableau(f: FiltrePilotage): Promise<TableauPilotage> {
    return firstValueFrom(this.http.get<TableauPilotage>(`${API}/pilotage`, { params: parametres(f) }));
  }

  exporter(f: FiltrePilotage, format: 'xlsx' | 'pdf'): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/pilotage/export`, { params: parametres(f, format), responseType: 'blob' }));
  }
}
