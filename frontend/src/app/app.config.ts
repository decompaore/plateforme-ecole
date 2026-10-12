import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding, withNavigationErrorHandler } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';

import { routes } from './app.routes';
import { jetonInterceptor } from './core/jeton.interceptor';
import { traiterErreurNavigation } from './core/navigation';
import { SessionService } from './core/session.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withInterceptors([jetonInterceptor])),
    // Écran chargé à la demande introuvable (version périmée) : rechargement au lieu d'un clic sans effet
    provideRouter(routes, withComponentInputBinding(), withNavigationErrorHandler((e) => traiterErreurNavigation(e))),
    // Reprise de la session (cookie HttpOnly) avant la première page
    provideAppInitializer(() => inject(SessionService).demarrer()),
    // Met l'application en cache sur le téléphone : elle s'ouvre ensuite sans réseau
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:5000',
    }),
  ],
};
