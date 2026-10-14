import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { messageErreur } from '../core/erreurs';
import { SessionService } from '../core/session.service';

@Component({
  selector: 'app-connexion',
  imports: [FormsModule],
  template: `
    <div class="page connexion">
      <div class="entete">
        <img src="icons/icon-96x96.png" alt="" width="64" height="64" />
        <h1>Connexion</h1>
        <p class="doux">Gestion de l'établissement</p>
      </div>

      <form class="carte" (ngSubmit)="valider()" #f="ngForm">
        @if (session.effaceADistance()) {
          <div class="alerte attention" role="status">
            Ce téléphone a été déconnecté à distance (appareil perdu ou volé) : ses données ont été effacées.
            Vous pouvez vous reconnecter si c'est bien votre appareil.
          </div>
        }
        @if (erreur()) {
          <div class="alerte erreur" role="alert">{{ erreur() }}</div>
        }
        <div class="champ">
          <label for="telephone">Numéro de téléphone</label>
          <input
            id="telephone"
            name="telephone"
            type="tel"
            inputmode="tel"
            autocomplete="username"
            required
            [(ngModel)]="telephone"
          />
        </div>
        <div class="champ">
          <label for="motDePasse">Mot de passe</label>
          <div class="mot-de-passe">
            <input
              id="motDePasse"
              name="motDePasse"
              [type]="voir() ? 'text' : 'password'"
              autocomplete="current-password"
              required
              [(ngModel)]="motDePasse"
            />
            <button type="button" class="bouton discret" (click)="voir.set(!voir())">
              {{ voir() ? 'Cacher' : 'Voir' }}
            </button>
          </div>
        </div>
        <button type="submit" class="bouton plein" [disabled]="envoi() || f.invalid">
          {{ envoi() ? 'Connexion…' : 'Se connecter' }}
        </button>
      </form>
      <details class="oubli">
        <summary>Mot de passe oublié ?</summary>
        <p>
          Adressez-vous à l'administration de votre établissement (en personne ou par téléphone). Après avoir vérifié
          votre identité, elle vous remet un <strong>mot de passe provisoire</strong>.
        </p>
        <p>
          Connectez-vous avec votre numéro et ce mot de passe provisoire : l'application vous demandera aussitôt d'en
          choisir un nouveau. Vous recevez aussi un SMS qui confirme la réinitialisation.
        </p>
        <p class="doux">
          Après plusieurs essais manqués, le compte est bloqué quelques minutes ; l'administration peut le débloquer.
        </p>
      </details>
    </div>
  `,
  styles: `
    .oubli {
      margin: 1rem auto 0;
      max-width: 32rem;
      font-size: 0.92rem;
      summary {
        cursor: pointer;
        text-align: center;
        color: var(--primaire);
        font-weight: 600;
        min-height: 44px;
        display: flex;
        align-items: center;
        justify-content: center;
      }
      p {
        margin: 0.5rem 0;
      }
    }
    .entete {
      text-align: center;
      margin: 2rem 0 1.5rem;
      img {
        border-radius: 16px;
      }
    }
    .mot-de-passe {
      display: flex;
      gap: 0.25rem;
    }
    .centre {
      text-align: center;
    }
  `,
})
export class ConnexionPage {
  protected readonly session = inject(SessionService);
  private readonly router = inject(Router);

  protected readonly telephone = signal('');
  protected readonly motDePasse = signal('');
  protected readonly voir = signal(false);
  protected readonly envoi = signal(false);
  protected readonly erreur = signal<string | null>(null);

  protected async valider(): Promise<void> {
    this.envoi.set(true);
    this.erreur.set(null);
    try {
      const etape = await this.session.connexion(this.telephone().replace(/\s+/g, ''), this.motDePasse());
      this.motDePasse.set('');
      if (etape === 'selection') {
        await this.router.navigate(['/etablissement']);
      } else if (this.session.profil()?.doitChangerMotDePasse) {
        await this.router.navigate(['/mot-de-passe']);
      } else {
        await this.router.navigate(['/']);
      }
    } catch (e) {
      this.erreur.set(messageErreur(e, 'Connexion impossible.'));
    } finally {
      this.envoi.set(false);
    }
  }
}
