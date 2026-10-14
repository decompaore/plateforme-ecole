import { signal } from '@angular/core';

import { messageErreur } from '../core/erreurs';

/**
 * Action asynchrone d'un écran : indique si elle est en cours et garde le
 * message d'erreur du serveur à afficher. Évite de répéter try/catch/finally.
 */
export class Action {
  readonly enCours = signal(false);
  readonly erreur = signal<string | null>(null);

  async executer<T>(action: () => Promise<T>, parDefaut?: string): Promise<T | undefined> {
    this.enCours.set(true);
    this.erreur.set(null);
    try {
      return await action();
    } catch (e) {
      this.erreur.set(messageErreur(e, parDefaut));
      return undefined;
    } finally {
      this.enCours.set(false);
    }
  }

  /** Pour les appels sans réponse utile (suppression…) : vrai si le serveur a accepté. */
  async reussit(action: () => Promise<unknown>, parDefaut?: string): Promise<boolean> {
    let ok = false;
    await this.executer(async () => {
      await action();
      ok = true;
    }, parDefaut);
    return ok;
  }
}
