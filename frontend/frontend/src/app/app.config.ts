import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';

import { routes } from './app.routes';
import { jetonInterceptor } from './core/jeton.interceptor';
import { SessionService } from './core/session.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withInterceptors([jetonInterceptor])),
    provideRouter(routes, withComponentInputBinding()),
    // Reprise de la session (cookie HttpOnly) avant la première page
    provideAppInitializer(() => inject(SessionService).demarrer()),
    // Met l'application en cache sur le téléphone : elle s'ouvre ensuite sans réseau
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:5000',
    }),
  ],
};
