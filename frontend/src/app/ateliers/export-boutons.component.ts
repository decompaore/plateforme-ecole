import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Component, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { messageErreur } from '../core/erreurs';
import { API } from '../core/session.service';
import { enregistrerFichier } from '../scolarite/scolarite-api.service';

export type FormatExport = 'xlsx' | 'pdf';

/** Nom du fichier annoncé par le serveur (Content-Disposition), sinon le nom proposé. */
export function nomDuFichier(entete: string | null, parDefaut: string): string {
  if (entete) {
    const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(entete);
    if (utf8) {
      try {
        return decodeURIComponent(utf8[1]);
      } catch {
        // nom mal encodé : on garde le nom par défaut
      }
    }
    const simple = /filename="?([^";]+)"?/i.exec(entete);
    if (simple) {
      return simple[1];
    }
  }
  return parDefaut;
}

/** Une erreur sur un téléchargement arrive en Blob : on relit le problème JSON du serveur. */
async function lireErreur(e: unknown): Promise<unknown> {
  if (e instanceof HttpErrorResponse && e.error instanceof Blob) {
    try {
      const corps: unknown = JSON.parse(await e.error.text());
      return new HttpErrorResponse({ error: corps, status: e.status, statusText: e.statusText, url: e.url ?? undefined });
    } catch {
      return e;
    }
  }
  return e;
}

/**
 * Boutons « Excel » et « PDF » : téléchargent l'export d'une page des ateliers
 * (`{chemin}/export?format=xlsx|pdf`), édité par le serveur avec l'en-tête de l'établissement.
 */
@Component({
  selector: 'app-export',
  template: `
    <span class="export" role="group" [attr.aria-label]="'Télécharger ' + libelle()">
      <button type="button" class="bouton secondaire petit" [disabled]="enCours() !== null" (click)="telecharger('xlsx')"
        [attr.aria-label]="'Télécharger ' + libelle() + ' en Excel'">
        {{ enCours() === 'xlsx' ? 'Préparation…' : 'Excel' }}
      </button>
      <button type="button" class="bouton secondaire petit" [disabled]="enCours() !== null" (click)="telecharger('pdf')"
        [attr.aria-label]="'Télécharger ' + libelle() + ' en PDF'">
        {{ enCours() === 'pdf' ? 'Préparation…' : 'PDF' }}
      </button>
    </span>
    @if (erreur()) {
      <span class="rouge" role="alert">{{ erreur() }}</span>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.4rem;
    }
    .export {
      display: inline-flex;
      gap: 0.35rem;
    }
    .rouge {
      color: var(--absent);
      font-size: 0.85rem;
    }
  `,
})
export class ExportBoutonsComponent {
  private readonly http = inject(HttpClient);

  /** Chemin de la ressource, sans « /export » : « /ateliers », « /campagnes-besoins/{id} »… */
  readonly chemin = input.required<string>();
  /** Nom de fichier si le serveur n'en propose pas (sans extension). */
  readonly nom = input('export');
  /** Ce qui est téléchargé, pour les lecteurs d'écran : « le catalogue », « l'état des besoins »… */
  readonly libelle = input('la liste');
  /** Paramètres ajoutés à la demande (filtre : classe, enseignant…). */
  readonly parametres = input<Record<string, string>>({});

  protected readonly enCours = signal<FormatExport | null>(null);
  protected readonly erreur = signal<string | null>(null);

  protected async telecharger(format: FormatExport): Promise<void> {
    this.enCours.set(format);
    this.erreur.set(null);
    try {
      const r: HttpResponse<Blob> = await firstValueFrom(
        this.http.get(`${API}${this.chemin()}/export`, { params: { ...this.parametres(), format }, responseType: 'blob', observe: 'response' }),
      );
      if (r.body) {
        enregistrerFichier(r.body, nomDuFichier(r.headers.get('Content-Disposition'), `${this.nom()}.${format}`));
      }
    } catch (e) {
      this.erreur.set(messageErreur(await lireErreur(e), 'Le fichier n’a pas pu être préparé.'));
    } finally {
      this.enCours.set(null);
    }
  }
}
