import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { messageErreur } from '../core/erreurs';
import { SessionService } from '../core/session.service';

@Component({
  selector: 'app-mot-de-passe',
  imports: [FormsModule],
  template: `
    <div class="page">
      <h1>Changer mon mot de passe</h1>
      @if (session.profil()?.doitChangerMotDePasse) {
        <div class="alerte attention">
          Votre mot de passe est provisoire (nouveau compte, ou mot de passe réinitialisé par l'administration).
          Saisissez-le comme mot de passe actuel, puis choisissez-en un nouveau pour continuer.
        </div>
      }
      <form class="carte" (ngSubmit)="valider()">
        @if (erreur()) {
          <div class="alerte erreur" role="alert">{{ erreur() }}</div>
        }
        @if (reussi()) {
          <div class="alerte succes" role="status">Mot de passe changé.</div>
        }
        <div class="champ">
          <label for="actuel">Mot de passe actuel</label>
          <input id="actuel" name="actuel" type="password" autocomplete="current-password" required
                 [(ngModel)]="actuel" />
        </div>
        <div class="champ">
          <label for="nouveau">Nouveau mot de passe</label>
          <input id="nouveau" name="nouveau" type="password" autocomplete="new-password" required
                 minlength="8" maxlength="100" [(ngModel)]="nouveau" />
          <span class="doux">Au moins 8 caractères, avec au moins une lettre et un chiffre.</span>
        </div>
        <div class="champ">
          <label for="confirmation">Confirmez le nouveau mot de passe</label>
          <input id="confirmation" name="confirmation" type="password" autocomplete="new-password" required
                 [(ngModel)]="confirmation" />
        </div>
        @if (probleme(); as p) {
          <p class="doux" role="status">{{ p }}</p>
        }
        <button type="submit" class="bouton plein" [disabled]="envoi() || probleme() !== null">
          {{ envoi() ? 'Enregistrement…' : 'Enregistrer' }}
        </button>
      </form>
    </div>
  `,
})
export class MotDePassePage {
  protected readonly session = inject(SessionService);
  private readonly router = inject(Router);

  protected readonly actuel = signal('');
  protected readonly nouveau = signal('');
  protected readonly confirmation = signal('');
  protected readonly envoi = signal(false);
  protected readonly reussi = signal(false);
  protected readonly erreur = signal<string | null>(null);

  /** Mêmes règles que le serveur, pour guider avant l'envoi. */
  protected readonly probleme = computed<string | null>(() => {
    const n = this.nouveau();
    if (!this.actuel() || !n) {
      return '';
    }
    if (n.length < 8) {
      return 'Le nouveau mot de passe est trop court.';
    }
    if (!/[A-Za-z]/.test(n) || !/[0-9]/.test(n)) {
      return 'Il faut au moins une lettre et un chiffre.';
    }
    if (n === this.actuel()) {
      return 'Le nouveau mot de passe doit être différent.';
    }
    if (n !== this.confirmation()) {
      return 'La confirmation ne correspond pas.';
    }
    return null;
  });

  protected async valider(): Promise<void> {
    this.envoi.set(true);
    this.erreur.set(null);
    try {
      await this.session.changerMotDePasse(this.actuel(), this.nouveau());
      this.actuel.set('');
      this.nouveau.set('');
      this.confirmation.set('');
      this.reussi.set(true);
      setTimeout(() => void this.router.navigate(['/']), 800);
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.envoi.set(false);
    }
  }
}
