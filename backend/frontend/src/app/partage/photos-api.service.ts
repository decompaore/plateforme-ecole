import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';

/** État de la signature du compte connecté (v0.44). */
export interface EtatSignature {
  presente: boolean;
  modifieeLe: string | null;
}

/**
 * Photos d'identité (élèves, membres de l'établissement), cartes scolaires et professionnelles,
 * signature numérisée du compte connecté (v0.44). En ligne uniquement : les images ne sont jamais
 * gardées sur l'appareil.
 */
@Injectable({ providedIn: 'root' })
export class PhotosApi {
  private readonly http = inject(HttpClient);

  /** Photo (ou miniature) ; null si aucune photo n'a été déposée. */
  async image(chemin: string, miniature = false): Promise<Blob | null> {
    try {
      return await firstValueFrom(
        this.http.get(API + chemin, { responseType: 'blob', params: miniature ? new HttpParams().set('miniature', true) : undefined }),
      );
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 404) {
        return null;
      }
      throw e;
    }
  }

  async deposer(chemin: string, fichier: Blob, nom = 'photo.jpg'): Promise<void> {
    const donnees = new FormData();
    donnees.append('fichier', fichier, nom);
    await firstValueFrom(this.http.post(API + chemin, donnees));
  }

  async retirer(chemin: string): Promise<void> {
    await firstValueFrom(this.http.delete(API + chemin));
  }

  // ---------- Élèves

  photoEleve(eleveId: string): string {
    return `/eleves/${eleveId}/photo`;
  }

  /** Parmi ces élèves (une page de la recherche), ceux qui ont une photo. */
  elevesParmi(ids: string[]): Promise<string[]> {
    if (ids.length === 0) {
      return Promise.resolve([]);
    }
    return firstValueFrom(this.http.get<string[]>(`${API}/eleves/photos`, { params: new HttpParams().set('ids', ids.join(',')) }));
  }

  /** Dépose la photo choisie dans un formulaire, réduite par le navigateur. */
  async deposerPhoto(chemin: string, fichier: File): Promise<void> {
    await this.deposer(chemin, await reduirePhoto(fichier));
  }

  /** Élèves de la classe qui ont une photo. */
  elevesAvecPhoto(classeId: string): Promise<string[]> {
    return firstValueFrom(this.http.get<string[]>(`${API}/classes/${classeId}/photos`));
  }

  cartesClasse(classeId: string): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/classes/${classeId}/cartes`, { responseType: 'blob' }));
  }

  carteEleve(eleveId: string): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/eleves/${eleveId}/carte`, { responseType: 'blob' }));
  }

  // ---------- Membres de l'établissement

  photoPersonne(utilisateurId: string): string {
    return `/personnes/${utilisateurId}/photo`;
  }

  personnesAvecPhoto(): Promise<string[]> {
    return firstValueFrom(this.http.get<string[]>(`${API}/personnes/photos`));
  }

  /** Cartes professionnelles de tous les membres actifs, ou d'une seule personne. */
  cartesPersonnel(utilisateurId?: string): Promise<Blob> {
    return firstValueFrom(
      this.http.get(`${API}/personnes/cartes`, {
        responseType: 'blob',
        params: utilisateurId ? new HttpParams().set('utilisateur', utilisateurId) : undefined,
      }),
    );
  }

  // ---------- Signature du compte connecté

  etatSignature(): Promise<EtatSignature> {
    return firstValueFrom(this.http.get<EtatSignature>(`${API}/moi/signature/etat`));
  }

  maSignature(): Promise<Blob | null> {
    return this.image('/moi/signature');
  }

  deposerSignature(image: Blob): Promise<EtatSignature> {
    const donnees = new FormData();
    donnees.append('fichier', image, 'signature.png');
    return firstValueFrom(this.http.post<EtatSignature>(`${API}/moi/signature`, donnees));
  }

  retirerSignature(): Promise<EtatSignature> {
    return firstValueFrom(this.http.delete<EtatSignature>(`${API}/moi/signature`));
  }
}

/**
 * Réduit une photo avant l'envoi (réseau lent) : JPEG de 960 × 1200 pixels au plus. Le serveur la
 * ré-encode ensuite au format définitif. Sans canvas (vieux navigateur, tests), le fichier part tel quel.
 */
export async function reduirePhoto(fichier: File, largeurMax = 960, hauteurMax = 1200): Promise<Blob> {
  if (typeof createImageBitmap !== 'function') {
    return fichier;
  }
  try {
    const image = await createImageBitmap(fichier);
    const echelle = Math.min(1, largeurMax / image.width, hauteurMax / image.height);
    if (echelle === 1 && fichier.size < 400_000) {
      image.close();
      return fichier;
    }
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(image.width * echelle);
    canvas.height = Math.round(image.height * echelle);
    const ctx = canvas.getContext('2d');
    if (!ctx) {
      return fichier;
    }
    ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
    image.close();
    const reduite = await new Promise<Blob | null>((ok) => canvas.toBlob(ok, 'image/jpeg', 0.85));
    return reduite ?? fichier;
  } catch {
    return fichier; // format que le navigateur ne décode pas : le serveur tranchera
  }
}
