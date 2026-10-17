import { HttpClient } from '@angular/common/http';
import { effect, inject, Injectable, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { STOCKAGE } from '../hors-ligne/stockage';
import { API, ProfilLocal, SessionService } from './session.service';

/** Clé des armoiries d'un profil : son établissement, sa direction ou le pays qu'il administre. */
export function cleArmoiries(p: ProfilLocal | null): string | null {
  if (!p) {
    return null;
  }
  if (p.etablissement) {
    return `armoiries:etablissement:${p.etablissement.id}`;
  }
  if (p.direction) {
    return `armoiries:direction:${p.direction.id}`;
  }
  if (p.adminPays) {
    return `armoiries:pays:${p.adminPays.id}`;
  }
  return null;
}

function enDataUrl(b: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result as string);
    r.onerror = () => reject(r.error);
    r.readAsDataURL(b);
  });
}

/**
 * Armoiries du pays en fond d'écran (v0.48.1 ; en couleurs, sans filigrane, depuis v0.49.1). Lues une fois par session (établissement, direction ou pays
 * administré) puis gardées sur l'appareil pour s'afficher aussi hors connexion. Aucune armoirie : fond uni.
 */
@Injectable({ providedIn: 'root' })
export class ArmoiriesEcran {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly stockage = inject(STOCKAGE);

  /** Image (data URL) à afficher, ou null. */
  readonly image = signal<string | null>(null);
  private derniere: string | null = null;
  /** Clé déjà lue (ou en cours de lecture) sur le serveur pendant cette session. */
  private lue: string | null = null;

  /** À appeler une fois (composant racine). */
  demarrer(): void {
    effect(() => {
      const profil = this.session.profil();
      const jeton = this.session.jeton();
      const cle = profil && !profil.doitChangerMotDePasse ? cleArmoiries(profil) : null;
      untracked(() => void this.actualiser(cle, jeton !== null));
    });
  }

  private async actualiser(cle: string | null, connecte: boolean): Promise<void> {
    if (!cle) {
      this.derniere = null;
      this.lue = null;
      this.image.set(null);
      return;
    }
    if (cle !== this.derniere) {
      this.derniere = cle;
      this.image.set((await this.lireLocal(cle)) ?? null);
    }
    if (!connecte || this.lue === cle) {
      return;
    }
    this.lue = cle;
    try {
      const b = await firstValueFrom(this.http.get(`${API}/armoiries`, { responseType: 'blob' }));
      const url = await enDataUrl(b);
      if (this.derniere === cle) {
        this.image.set(url);
      }
      await this.stockage.ecrire(cle, url).catch(() => undefined);
    } catch (e) {
      // 404 : pas d'armoiries pour ce pays ; autre erreur (réseau) : on garde la copie locale et on réessaiera
      if ((e as { status?: number }).status !== 404) {
        this.lue = null;
      } else {
        if (this.derniere === cle) {
          this.image.set(null);
        }
        await this.stockage.supprimer(cle).catch(() => undefined);
      }
    }
  }

  private async lireLocal(cle: string): Promise<string | undefined> {
    try {
      return await this.stockage.lire<string>(cle);
    } catch {
      return undefined;
    }
  }
}
