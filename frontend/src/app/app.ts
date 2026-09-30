import { Component, DestroyRef, effect, inject, signal, untracked } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { SwUpdate } from '@angular/service-worker';

import { SessionService } from './core/session.service';
import { EnvoisService } from './hors-ligne/envois.service';
import { ListesService } from './hors-ligne/listes.service';

const REPRISE_MS = 30 * 1000;

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly session = inject(SessionService);
  protected readonly envois = inject(EnvoisService);
  private readonly listes = inject(ListesService);
  private readonly router = inject(Router);
  private readonly sw = inject(SwUpdate);

  protected readonly menuOuvert = signal(false);
  protected readonly miseAJour = signal(false);
  protected readonly reseau = signal(typeof navigator === 'undefined' || navigator.onLine);

  constructor() {
    const destroyRef = inject(DestroyRef);
    this.envois.demarrer(destroyRef);

    const enLigne = () => {
      this.reseau.set(true);
      // Session reprise au retour du réseau (le profil enregistré ne suffit pas pour envoyer)
      if (this.session.horsConnexion() && !this.session.sessionExpiree()) {
        void this.session.rafraichir();
      }
    };
    const horsLigne = () => this.reseau.set(false);
    window.addEventListener('online', enLigne);
    window.addEventListener('offline', horsLigne);
    // Serveur indisponible au lancement alors que le téléphone a du réseau : aucun événement « online »
    // ne viendra, on retente donc régulièrement de reprendre la session
    const reprise = setInterval(() => {
      if (this.session.horsConnexion() && !this.session.sessionExpiree() && navigator.onLine) {
        void this.session.rafraichir();
      }
    }, REPRISE_MS);
    destroyRef.onDestroy(() => {
      window.removeEventListener('online', enLigne);
      window.removeEventListener('offline', horsLigne);
      clearInterval(reprise);
    });

    // Changement d'utilisateur ou d'établissement : on relit sa file et on prépare ses listes
    let precedent = '';
    effect(() => {
      const profil = this.session.profil();
      const cle = profil?.etablissement ? `${profil.utilisateurId}:${profil.etablissement.id}` : '';
      const connecte = this.session.jeton() !== null;
      untracked(() => {
        if (cle !== precedent || connecte) {
          precedent = cle;
          void this.envois.recharger().then(() => this.envois.synchroniser());
          if (cle && connecte && !profil?.doitChangerMotDePasse) {
            void this.listes.preparerSiAncien();
          }
        }
        // Session terminée (expirée, révoquée) : retour à la connexion
        const url = this.router.url;
        if (!profil && this.router.navigated && !url.startsWith('/connexion') && !url.startsWith('/etablissement')) {
          void this.router.navigate(['/connexion']);
        }
      });
    });

    if (this.sw.isEnabled) {
      this.sw.versionUpdates.subscribe((e) => {
        if (e.type === 'VERSION_READY') {
          this.miseAJour.set(true);
        }
      });
    }
  }

  protected recharger(): void {
    document.location.reload();
  }

  protected async deconnexion(): Promise<void> {
    this.menuOuvert.set(false);
    const attente = await this.envois.enAttenteTousEtablissements();
    if (
      attente > 0 &&
      !window.confirm(
        `${attente} appel(s) ne sont pas encore envoyés. Ils restent sur ce téléphone et partiront à votre prochaine connexion. Se déconnecter ?`,
      )
    ) {
      return;
    }
    await this.session.deconnexion();
    await this.router.navigate(['/connexion']);
  }
}
