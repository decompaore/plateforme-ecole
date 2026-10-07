import { DatePipe, UpperCasePipe } from '@angular/common';
import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';

import { Action } from '../action';
import { AdminNavComponent } from '../admin-nav.component';
import { IdentiteVue } from '../modeles-territoire';
import { TerritoireApi } from '../territoire-api.service';

/** Taille maximale d'un logo (vérifiée aussi par le serveur). */
export const TAILLE_MAX_LOGO = 500 * 1024;

/**
 * Identité de l'établissement sur ses documents officiels : rattachement (fixé par la
 * plateforme) et logo (choisi ici), avec un aperçu PDF de l'en-tête.
 */
@Component({
  selector: 'app-identite',
  imports: [AdminNavComponent, DatePipe, UpperCasePipe],
  template: `
    <div class="page large">
      <h1>Identité de l'établissement</h1>
      <app-admin-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message()) {
        <div class="alerte succes" role="status">{{ message() }}</div>
      }

      @if (identite(); as i) {
        <section class="carte">
          <h2>En-tête des documents officiels</h2>
          <div class="entete" aria-label="Aperçu de l'en-tête">
            <div class="tutelle">
              @if (i.pays) { <strong>{{ i.pays | uppercase }}</strong> }
              @if (i.devise) { <em>{{ i.devise }}</em> }
              @if (i.pays) { <span class="filet" aria-hidden="true"></span> }
              @for (a of i.autorites; track $index) { <span>{{ a }}</span> }
            </div>
            <div class="ecole">
              @if (urlLogo()) { <img [src]="urlLogo()" alt="Logo de l'établissement" /> }
              <strong>{{ i.nom }}</strong>
            </div>
          </div>
          @if (!i.rattache) {
            <p class="alerte attention">
              L'établissement n'est pas encore rattaché à sa direction (pays, ministère, directions). Demandez-le à
              l'administrateur de la plateforme : le rattachement figure ensuite sur tous les documents officiels.
            </p>
          } @else {
            <p class="doux">Le rattachement est fixé par l'administrateur de la plateforme. Une erreur ? Signalez-la-lui.</p>
          }
          <p class="doux">
            Cet en-tête figure sur les bulletins, les reçus, les fiches de remise des accès et les exports PDF.
            <button type="button" class="bouton discret petit" [disabled]="action.enCours()" (click)="apercu()">Voir un aperçu PDF</button>
          </p>
        </section>

        <section class="carte">
          <h2>Logo</h2>
          @if (i.logo; as l) {
            <p class="doux">Logo actuel ({{ l.type === 'image/png' ? 'PNG' : 'JPEG' }}, {{ ko(l.taille) }} Ko), modifié le {{ l.modifieLe | date: 'dd/MM/yyyy' }}.</p>
          } @else {
            <p class="doux">Aucun logo : les documents portent seulement le nom de l'établissement.</p>
          }
          <div class="actions-ligne">
            <label class="bouton secondaire petit fichier">
              {{ i.logo ? 'Remplacer le logo' : 'Ajouter un logo' }}
              <input type="file" accept="image/png,image/jpeg" (change)="choisirLogo($event)" [disabled]="action.enCours()" />
            </label>
            @if (i.logo) {
              @if (confirmation()) {
                <button type="button" class="bouton danger petit" [disabled]="action.enCours()" (click)="supprimer()">Confirmer la suppression</button>
                <button type="button" class="bouton discret petit" (click)="confirmation.set(false)">Annuler</button>
              } @else {
                <button type="button" class="bouton discret petit" (click)="confirmation.set(true)">Supprimer le logo</button>
              }
            }
          </div>
          <p class="doux">Image PNG ou JPEG, 500 Ko au plus, de préférence carrée sur fond blanc ou transparent. Elle est imprimée petite (environ 2 cm).</p>
        </section>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    h2 {
      font-size: 1.05rem;
      margin: 0 0 0.75rem;
    }
    .entete {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: flex-start;
      gap: 1rem;
      padding: 1rem;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      background: #fff;
      color: #111;
      margin-bottom: 0.75rem;
    }
    .tutelle {
      display: flex;
      flex-direction: column;
      align-items: center;
      text-align: center;
      gap: 0.15rem;
      font-size: 0.85rem;
      flex: 1 1 14rem;
      max-width: 24rem;
    }
    .filet {
      width: 3rem;
      border-top: 1px solid currentColor;
      margin: 0.2rem 0;
    }
    .ecole {
      display: flex;
      flex-direction: column;
      align-items: flex-end;
      text-align: right;
      gap: 0.35rem;
      flex: 1 1 10rem;
      img {
        max-height: 4rem;
        max-width: 8rem;
        object-fit: contain;
      }
    }
    .fichier {
      position: relative;
      overflow: hidden;
      cursor: pointer;
      input {
        position: absolute;
        inset: 0;
        opacity: 0;
        cursor: pointer;
      }
    }
  `,
})
export class IdentitePage implements OnInit {
  private readonly api = inject(TerritoireApi);

  protected readonly action = new Action();
  protected readonly identite = signal<IdentiteVue | null>(null);
  protected readonly urlLogo = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly confirmation = signal(false);

  constructor() {
    inject(DestroyRef).onDestroy(() => this.libererLogo());
  }

  async ngOnInit(): Promise<void> {
    const i = await this.action.executer(() => this.api.identite());
    if (i) {
      await this.afficher(i);
    }
  }

  protected ko(octets: number): number {
    return Math.max(1, Math.round(octets / 1024));
  }

  protected async choisirLogo(e: Event): Promise<void> {
    const champ = e.target as HTMLInputElement;
    const fichier = champ.files?.[0];
    champ.value = '';
    if (!fichier) {
      return;
    }
    this.message.set(null);
    if (fichier.size > TAILLE_MAX_LOGO) {
      this.action.erreur.set('Logo trop lourd : 500 Ko au plus. Réduisez l’image avant de l’envoyer.');
      return;
    }
    if (!['image/png', 'image/jpeg'].includes(fichier.type)) {
      this.action.erreur.set('Choisissez une image PNG ou JPEG.');
      return;
    }
    const i = await this.action.executer(() => this.api.enregistrerLogo(fichier));
    if (i) {
      await this.afficher(i);
      this.message.set('Logo enregistré : il figure désormais sur les documents officiels.');
    }
  }

  protected async supprimer(): Promise<void> {
    const i = await this.action.executer(() => this.api.supprimerLogo());
    this.confirmation.set(false);
    if (i) {
      await this.afficher(i);
      this.message.set('Logo supprimé.');
    }
  }

  protected async apercu(): Promise<void> {
    const pdf = await this.action.executer(() => this.api.apercu());
    if (pdf) {
      const url = URL.createObjectURL(pdf);
      window.open(url, '_blank', 'noopener');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    }
  }

  private async afficher(i: IdentiteVue): Promise<void> {
    this.identite.set(i);
    this.libererLogo();
    if (i.logo) {
      const image = await this.action.executer(() => this.api.logo());
      if (image) {
        this.urlLogo.set(URL.createObjectURL(image));
      }
    }
  }

  private libererLogo(): void {
    const url = this.urlLogo();
    if (url) {
      URL.revokeObjectURL(url);
      this.urlLogo.set(null);
    }
  }
}
