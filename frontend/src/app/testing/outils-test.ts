import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { jetonInterceptor } from '../core/jeton.interceptor';
import { EtablissementAccessible, ReponseConnexion } from '../core/modeles';
import { STOCKAGE, StockageMemoire } from '../hors-ligne/stockage';

/** Faux jeton JWT (seul le « sub » est lu par l'application). */
export function jeton(sub: string, n = 1): string {
  const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `${b64({ alg: 'HS256' })}.${b64({ sub, n })}.signature`;
}

export const LYCEE: EtablissementAccessible = {
  id: 'etab-1',
  code: 'LTK',
  nom: 'Lycée technique de Koudougou',
  roles: ['ENSEIGNANT'],
};

export function reponse(sub = 'u1', n = 1, autres: Partial<ReponseConnexion> = {}): ReponseConnexion {
  return {
    jetonAcces: jeton(sub, n),
    jetonSelection: null,
    expireDansSecondes: 900,
    selectionRequise: false,
    etablissementActif: LYCEE,
    etablissements: [LYCEE],
    superAdmin: false,
    doitChangerMotDePasse: false,
    ...autres,
  };
}

export function configurer(): { http: HttpTestingController; stockage: StockageMemoire } {
  const stockage = new StockageMemoire();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideHttpClient(withInterceptors([jetonInterceptor])),
      provideHttpClientTesting(),
      { provide: STOCKAGE, useValue: stockage },
    ],
  });
  return { http: TestBed.inject(HttpTestingController), stockage };
}

/** Laisse s'exécuter les promesses en attente (enchaînements async entre deux requêtes). */
export async function attendre(): Promise<void> {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
  }
  await new Promise((r) => setTimeout(r, 0));
}
