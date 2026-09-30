import { InjectionToken } from '@angular/core';

/**
 * Stockage clé → valeur persistant sur l'appareil. En production : IndexedDB
 * (survit à la fermeture de l'application et fonctionne hors connexion).
 * Les tests utilisent {@link StockageMemoire}.
 */
export interface Stockage {
  lire<T>(cle: string): Promise<T | undefined>;
  ecrire<T>(cle: string, valeur: T): Promise<void>;
  supprimer(cle: string): Promise<void>;
  /** Toutes les valeurs dont la clé commence par ce préfixe, par ordre de clé. */
  lister<T>(prefixe: string): Promise<T[]>;
  /** Supprime toutes les clés commençant par ce préfixe. */
  purger(prefixe: string): Promise<void>;
  /** Clés commençant par ce préfixe. */
  cles(prefixe: string): Promise<string[]>;
}

export const STOCKAGE = new InjectionToken<Stockage>('STOCKAGE', {
  providedIn: 'root',
  factory: () => (typeof indexedDB !== 'undefined' ? new StockageIndexedDb() : new StockageMemoire()),
});

const BASE = 'plateforme-ecoles';
const TABLE = 'donnees';

/**
 * IndexedDB. Si la base ne s'ouvre pas (navigation privée, stockage plein ou
 * bloqué), on continue en mémoire : l'application reste utilisable, seules
 * les données hors connexion ne survivent pas à la fermeture.
 */
export class StockageIndexedDb implements Stockage {
  private base?: Promise<IDBDatabase>;
  private secours?: StockageMemoire;

  private ouvrir(): Promise<IDBDatabase> {
    this.base ??= new Promise((resoudre, rejeter) => {
      const demande = indexedDB.open(BASE, 1);
      demande.onupgradeneeded = () => demande.result.createObjectStore(TABLE);
      demande.onsuccess = () => resoudre(demande.result);
      demande.onerror = () => rejeter(demande.error);
    });
    return this.base;
  }

  private async executer<R>(
    mode: IDBTransactionMode,
    action: (t: IDBObjectStore) => IDBRequest,
    enMemoire: (m: StockageMemoire) => Promise<R>,
  ): Promise<R> {
    let base: IDBDatabase;
    try {
      base = await this.ouvrir();
    } catch {
      this.secours ??= new StockageMemoire();
      return enMemoire(this.secours);
    }
    return new Promise((resoudre, rejeter) => {
      const transaction = base.transaction(TABLE, mode);
      const demande = action(transaction.objectStore(TABLE));
      transaction.oncomplete = () => resoudre(demande.result as R);
      transaction.onerror = () => rejeter(transaction.error);
      transaction.onabort = () => rejeter(transaction.error);
    });
  }

  private static plage(prefixe: string): IDBKeyRange {
    return IDBKeyRange.bound(prefixe, prefixe + '\uffff');
  }

  lire<T>(cle: string): Promise<T | undefined> {
    return this.executer('readonly', (t) => t.get(cle), (m) => m.lire<T>(cle));
  }

  async ecrire<T>(cle: string, valeur: T): Promise<void> {
    await this.executer<unknown>('readwrite', (t) => t.put(valeur, cle), (m) => m.ecrire(cle, valeur));
  }

  async supprimer(cle: string): Promise<void> {
    await this.executer<unknown>('readwrite', (t) => t.delete(cle), (m) => m.supprimer(cle));
  }

  lister<T>(prefixe: string): Promise<T[]> {
    return this.executer('readonly', (t) => t.getAll(StockageIndexedDb.plage(prefixe)), (m) => m.lister<T>(prefixe));
  }

  async purger(prefixe: string): Promise<void> {
    await this.executer<unknown>('readwrite', (t) => t.delete(StockageIndexedDb.plage(prefixe)), (m) => m.purger(prefixe));
  }

  async cles(prefixe: string): Promise<string[]> {
    const cles = await this.executer(
      'readonly',
      (t) => t.getAllKeys(StockageIndexedDb.plage(prefixe)),
      (m) => m.cles(prefixe),
    );
    return cles.map(String);
  }
}

/** Stockage en mémoire (tests, navigateurs sans IndexedDB). Copie les valeurs comme IndexedDB. */
export class StockageMemoire implements Stockage {
  private readonly valeurs = new Map<string, unknown>();

  async lire<T>(cle: string): Promise<T | undefined> {
    const v = this.valeurs.get(cle);
    return v === undefined ? undefined : structuredClone(v as T);
  }

  async ecrire<T>(cle: string, valeur: T): Promise<void> {
    this.valeurs.set(cle, structuredClone(valeur));
  }

  async supprimer(cle: string): Promise<void> {
    this.valeurs.delete(cle);
  }

  async lister<T>(prefixe: string): Promise<T[]> {
    return [...this.valeurs.keys()]
      .filter((k) => k.startsWith(prefixe))
      .sort()
      .map((k) => structuredClone(this.valeurs.get(k) as T));
  }

  async cles(prefixe: string): Promise<string[]> {
    return [...this.valeurs.keys()].filter((k) => k.startsWith(prefixe)).sort();
  }

  async purger(prefixe: string): Promise<void> {
    for (const k of [...this.valeurs.keys()]) {
      if (k.startsWith(prefixe)) {
        this.valeurs.delete(k);
      }
    }
  }
}
