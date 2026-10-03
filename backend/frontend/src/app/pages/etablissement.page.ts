import { Component, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';

import { messageErreur } from '../core/erreurs';
import { EtablissementAccessible, Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { EnvoisService } from '../hors-ligne/envois.service';

const LIBELLES: Record<Role, string> = {
  ADMIN_ECOLE: 'Administration',
  CENSEUR: 'Censeur',
  CHEF_TRAVAUX: 'Chef des travaux',
  SECRETARIAT: 'Secrétariat',
  INTENDANT: 'Intendance',
  SURVEILLANT: 'Surveillance',
  ENSEIGNANT: 'Enseignant',
  PARENT: 'Parent',
  ELEVE: 'Élève',
};

@Component({
  selector: 'app-etablissement',
  template: `
    <div class="page">
      <h1>Choisissez l'établissement</h1>
      @if (erreur()) {
        <div class="alerte erreur" role="alert">{{ erreur() }}</div>
      }
      <ul class="liste carte">
        @for (e of etablissements(); track e.id) {
          <li>
            <button type="button" class="ligne-lien choix" [disabled]="envoi()" (click)="choisir(e)">
              <span>
                <strong>{{ e.nom }}</strong><br />
                <span class="doux">{{ roles(e) }}</span>
              </span>
              @if (e.id === actif()) {
                <span class="pastille present">actuel</span>
              } @else {
                <span aria-hidden="true">›</span>
              }
            </button>
          </li>
        } @empty {
          <li class="doux">Chargement…</li>
        }
      </ul>
    </div>
  `,
  styles: `
    .choix {
      width: 100%;
      background: none;
      border: none;
      font: inherit;
      text-align: left;
      cursor: pointer;
    }
  `,
})
export class EtablissementPage implements OnInit {
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  private readonly envois = inject(EnvoisService);

  protected readonly etablissements = signal<EtablissementAccessible[]>([]);
  protected readonly envoi = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly actif = () => this.session.profil()?.etablissement?.id;

  async ngOnInit(): Promise<void> {
    const selection = this.session.selection();
    if (selection) {
      this.etablissements.set(selection.etablissements);
      return;
    }
    try {
      this.etablissements.set(await this.session.mesEtablissements());
    } catch (e) {
      this.erreur.set(messageErreur(e));
    }
  }

  protected roles(e: EtablissementAccessible): string {
    return e.roles.map((r) => LIBELLES[r] ?? r).join(', ');
  }

  protected async choisir(e: EtablissementAccessible): Promise<void> {
    if (e.id === this.actif()) {
      await this.router.navigate(['/']);
      return;
    }
    this.envoi.set(true);
    this.erreur.set(null);
    try {
      if (this.session.profil()) {
        // Les appels de l'établissement actuel partent avec son jeton, avant d'en changer
        await this.envois.synchroniser();
      }
      await this.session.choisirEtablissement(e.id);
      await this.router.navigate([this.session.profil()?.doitChangerMotDePasse ? '/mot-de-passe' : '/']);
    } catch (err) {
      this.erreur.set(messageErreur(err, 'Choix impossible. Reconnectez-vous.'));
    } finally {
      this.envoi.set(false);
    }
  }
}
