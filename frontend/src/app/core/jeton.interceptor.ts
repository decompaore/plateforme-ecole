import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';

import { API, SessionService } from './session.service';

/** Requêtes d'authentification : jamais de jeton ajouté, jamais de nouvel essai. */
function estAuth(url: string): boolean {
  return url.startsWith(`${API}/auth/`);
}

function avecJeton(requete: HttpRequest<unknown>, jeton: string | null): HttpRequest<unknown> {
  return jeton ? requete.clone({ setHeaders: { Authorization: `Bearer ${jeton}` } }) : requete;
}

/**
 * Ajoute le jeton d'accès aux appels de l'API. Sur un 401, renouvelle la
 * session une seule fois puis rejoue la requête ; si le renouvellement échoue,
 * renvoie à la page de connexion.
 */
export const jetonInterceptor: HttpInterceptorFn = (requete, suivant) => {
  if (!requete.url.startsWith(API) || estAuth(requete.url) || requete.headers.has('Authorization')) {
    return suivant(requete);
  }
  const session = inject(SessionService);
  const router = inject(Router);
  return suivant(avecJeton(requete, session.jeton())).pipe(
    catchError((erreur: unknown) => {
      if (!(erreur instanceof HttpErrorResponse) || erreur.status !== 401) {
        return throwError(() => erreur);
      }
      return from(session.rafraichir()).pipe(
        switchMap((ok) => {
          if (!ok) {
            if (!session.profil()) {
              void router.navigate(['/connexion']);
            }
            return throwError(() => erreur);
          }
          return suivant(avecJeton(requete, session.jeton()));
        }),
      );
    }),
  );
};
