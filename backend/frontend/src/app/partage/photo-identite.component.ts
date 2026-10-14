import { Component, DestroyRef, effect, inject, input, model, output, signal, untracked } from '@angular/core';

import { messageErreur } from '../core/erreurs';
import { PhotosApi, reduirePhoto } from './photos-api.service';

/**
 * Photo d'identité d'un élève ou d'un membre de l'établissement (v0.44) : affichage et, si
 * {@code modifiable}, prise de vue (appareil photo du téléphone) ou choix d'un fichier, puis retrait.
 * {@code chemin} : chemin de l'API sans le préfixe, par exemple « /eleves/{id}/photo ».
 */
@Component({
  selector: 'app-photo-identite',
  template: `
    <div class="photo-identite">
      <div class="cadre" [class.vide]="!url()">
        @if (url(); as u) {
          <img [src]="u" [alt]="'Photo de ' + nom()" />
        } @else {
          <span>{{ chargement() ? '…' : 'Pas de photo' }}</span>
        }
      </div>
      @if (modifiable()) {
        <div class="actions">
          <label class="bouton secondaire petit">
            {{ url() ? 'Changer la photo' : 'Ajouter une photo' }}
            <input type="file" accept="image/jpeg,image/png" capture="user" (change)="choisir($event)" [disabled]="enCours()" hidden />
          </label>
          @if (url()) {
            <button type="button" class="bouton discret petit" (click)="retirer()" [disabled]="enCours()">Retirer</button>
          }
        </div>
        <p class="doux aide">Visage de face, fond clair. Elle s'imprime sur le bulletin et la carte.</p>
      }
      @if (erreur(); as e) {
        <p class="alerte erreur" role="alert">{{ e }}</p>
      }
    </div>
  `,
  styles: `
    .photo-identite {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 0.4rem;
    }
    .cadre {
      width: 96px;
      height: 120px;
      border: 1px solid var(--bordure);
      border-radius: 6px;
      overflow: hidden;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--fond);
      font-size: 0.75rem;
      color: var(--texte-doux);
      text-align: center;
      img {
        width: 100%;
        height: 100%;
        object-fit: cover;
      }
    }
    .actions {
      display: flex;
      gap: 0.5rem;
      align-items: center;
    }
    label.bouton {
      cursor: pointer;
    }
    .aide {
      font-size: 0.75rem;
      margin: 0;
      max-width: 16rem;
    }
  `,
})
export class PhotoIdentiteComponent {
  readonly chemin = input.required<string>();
  readonly nom = input('');
  readonly modifiable = input(false);
  /** Vrai après un dépôt, faux après un retrait (pour rafraîchir une liste). */
  readonly change = output<boolean>();

  private readonly api = inject(PhotosApi);
  protected readonly url = signal<string | null>(null);
  protected readonly chargement = signal(false);
  protected readonly enCours = signal(false);
  protected readonly erreur = signal<string | null>(null);

  constructor() {
    effect(() => {
      const chemin = this.chemin();
      untracked(() => void this.charger(chemin));
    });
    inject(DestroyRef).onDestroy(() => this.remplacer(null));
  }

  private async charger(chemin: string): Promise<void> {
    this.chargement.set(true);
    this.erreur.set(null);
    try {
      const blob = await this.api.image(chemin);
      if (chemin === this.chemin()) {
        this.remplacer(blob);
      }
    } catch (e) {
      this.remplacer(null);
      this.erreur.set(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  protected async choisir(e: Event): Promise<void> {
    const champ = e.target as HTMLInputElement;
    const fichier = champ.files?.[0];
    champ.value = '';
    if (!fichier) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    try {
      await this.api.deposer(this.chemin(), await reduirePhoto(fichier));
      await this.charger(this.chemin());
      this.change.emit(true);
    } catch (err) {
      this.erreur.set(messageErreur(err, "La photo n'a pas pu être enregistrée."));
    } finally {
      this.enCours.set(false);
    }
  }

  protected async retirer(): Promise<void> {
    if (!window.confirm('Retirer la photo ?')) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    try {
      await this.api.retirer(this.chemin());
      this.remplacer(null);
      this.change.emit(false);
    } catch (err) {
      this.erreur.set(messageErreur(err));
    } finally {
      this.enCours.set(false);
    }
  }

  private remplacer(blob: Blob | null): void {
    const ancienne = this.url();
    if (ancienne) {
      URL.revokeObjectURL(ancienne);
    }
    this.url.set(blob ? URL.createObjectURL(blob) : null);
  }
}

/** Miniature (96 × 120) dans une liste ; ne la demande que si l'on sait qu'elle existe. */
@Component({
  selector: 'app-vignette',
  template: `
    @if (url(); as u) {
      <img class="vignette" [src]="u" alt="" />
    } @else if (ajout()) {
      <span class="vignette ajout" aria-hidden="true"><span class="plus">+</span>Photo</span>
    } @else {
      <span class="vignette vide" aria-hidden="true">{{ initiales() }}</span>
    }
  `,
  styles: `
    :host {
      display: inline-block;
      vertical-align: middle;
    }
    .vignette {
      width: 32px;
      height: 40px;
      border-radius: 4px;
      object-fit: cover;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      font-size: 0.7rem;
      background: var(--fond);
      color: var(--texte-doux);
    }
    .ajout {
      flex-direction: column;
      gap: 0;
      border: 1px dashed var(--primaire);
      color: var(--primaire);
      font-size: 0.6rem;
      font-weight: 600;
      line-height: 1;
      .plus {
        font-size: 1rem;
      }
    }
  `,
})
export class VignetteComponent {
  readonly chemin = input<string | null>(null);
  readonly initiales = input('');
  /** Sans photo : cadre « + Photo » (bouton d'ajout) au lieu des initiales. */
  readonly ajout = input(false);

  private readonly api = inject(PhotosApi);
  protected readonly url = signal<string | null>(null);

  constructor() {
    effect(() => {
      const chemin = this.chemin();
      untracked(() => {
        this.liberer();
        if (chemin) {
          this.api
            .image(chemin, true)
            .then((b) => {
              if (b && chemin === this.chemin()) {
                this.url.set(URL.createObjectURL(b));
              }
            })
            .catch(() => undefined);
        }
      });
    });
    inject(DestroyRef).onDestroy(() => this.liberer());
  }

  private liberer(): void {
    const u = this.url();
    if (u) {
      URL.revokeObjectURL(u);
    }
    this.url.set(null);
  }
}

/**
 * Cadre « photo d'identité » d'un formulaire de création (élève, enseignant, personnel) : la photo est
 * choisie ou prise avant l'enregistrement, et envoyée par la page une fois le dossier créé.
 */
@Component({
  selector: 'app-choix-photo',
  template: `
    <div class="choix-photo">
      <label class="cadre" [class.vide]="!apercu()" [attr.for]="champ()">
        @if (apercu(); as u) {
          <img [src]="u" alt="Photo choisie" />
        } @else {
          <span class="plus">+</span>
          <span>Photo</span>
        }
      </label>
      <div class="texte">
        <label class="bouton secondaire petit" [attr.for]="champ()">{{ apercu() ? 'Changer' : 'Prendre ou choisir une photo' }}</label>
        <input [id]="champ()" type="file" accept="image/jpeg,image/png" capture="user" (change)="choisir($event)" hidden />
        @if (apercu()) {
          <button type="button" class="bouton discret petit" (click)="vider()">Retirer</button>
        }
        <p class="doux aide">Facultatif. Visage de face, fond clair : elle figure sur la fiche, le bulletin et la carte.</p>
      </div>
    </div>
  `,
  styles: `
    .choix-photo {
      display: flex;
      gap: 0.75rem;
      align-items: flex-start;
    }
    .cadre {
      width: 72px;
      height: 90px;
      flex: none;
      border-radius: 6px;
      overflow: hidden;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      cursor: pointer;
      border: 1px solid var(--bordure);
      background: var(--fond);
      img {
        width: 100%;
        height: 100%;
        object-fit: cover;
      }
      &.vide {
        border: 2px dashed var(--primaire);
        color: var(--primaire);
        font-size: 0.75rem;
        font-weight: 600;
      }
      .plus {
        font-size: 1.6rem;
        line-height: 1;
      }
    }
    .texte {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      align-items: center;
    }
    label.bouton {
      cursor: pointer;
    }
    .aide {
      flex-basis: 100%;
      font-size: 0.75rem;
      margin: 0;
    }
  `,
})
export class ChoixPhotoComponent {
  /** Identifiant du champ de fichier (plusieurs cadres sur une page). */
  readonly champ = input('photo');
  readonly fichier = model<File | null>(null);
  protected readonly apercu = signal<string | null>(null);

  constructor() {
    effect(() => {
      const f = this.fichier();
      untracked(() => {
        const ancien = this.apercu();
        if (ancien) {
          URL.revokeObjectURL(ancien);
        }
        this.apercu.set(f ? URL.createObjectURL(f) : null);
      });
    });
    inject(DestroyRef).onDestroy(() => {
      const u = this.apercu();
      if (u) {
        URL.revokeObjectURL(u);
      }
    });
  }

  protected choisir(e: Event): void {
    const champ = e.target as HTMLInputElement;
    const f = champ.files?.[0] ?? null;
    champ.value = '';
    if (f) {
      this.fichier.set(f);
    }
  }

  protected vider(): void {
    this.fichier.set(null);
  }
}
