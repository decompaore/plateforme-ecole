import { HttpErrorResponse } from '@angular/common/http';
import { TimeoutError } from 'rxjs';

import { Probleme } from './modeles';

/** Vrai si la requête n'a pas abouti (pas de réseau, serveur injoignable ou trop lent). */
export function estErreurReseau(e: unknown): boolean {
  if (e instanceof TimeoutError) {
    return true;
  }
  return e instanceof HttpErrorResponse && (e.status === 0 || e.status === 502 || e.status === 503 || e.status === 504);
}

export function probleme(e: unknown): Probleme | undefined {
  if (e instanceof HttpErrorResponse && e.error && typeof e.error === 'object') {
    return e.error as Probleme;
  }
  return undefined;
}

/** Message lisible pour l'utilisateur à partir d'une erreur HTTP. */
export function messageErreur(e: unknown, parDefaut = 'Une erreur est survenue. Réessayez.'): string {
  if (estErreurReseau(e)) {
    return 'Pas de connexion au serveur. Vérifiez votre réseau.';
  }
  const p = probleme(e);
  return p?.detail || p?.title || parDefaut;
}
