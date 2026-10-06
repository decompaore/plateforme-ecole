import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { Module, Role } from './modeles';
import { SessionService } from './session.service';

/** Page réservée aux utilisateurs connectés (ou reconnus hors connexion). */
export const connecte: CanActivateFn = () => {
  const session = inject(SessionService);
  const router = inject(Router);
  const profil = session.profil();
  if (!profil) {
    return router.parseUrl('/connexion');
  }
  if (profil.doitChangerMotDePasse && !session.horsConnexion()) {
    return router.parseUrl('/mot-de-passe');
  }
  return true;
};

/** Page réservée à certains rôles dans l'établissement actif. */
export function role(...roles: Role[]): CanActivateFn {
  return () => {
    const session = inject(SessionService);
    return session.aLeRole(...roles) || inject(Router).parseUrl('/');
  };
}

/** Page de connexion : inutile si l'utilisateur est déjà connecté (sauf session expirée à rouvrir). */
export const anonyme: CanActivateFn = () => {
  const session = inject(SessionService);
  return !session.profil() || session.sessionExpiree() || inject(Router).parseUrl('/');
};

/** Écrans d'un module : fermés si l'établissement ne l'utilise pas (v0.31). */
export function module(m: Module): CanActivateFn {
  return () => {
    const session = inject(SessionService);
    return session.moduleActif(m) || inject(Router).parseUrl('/');
  };
}
