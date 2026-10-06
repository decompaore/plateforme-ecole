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
  /** Détruit la clé de chiffrement de ce compte : ses données chiffrées deviennent illisibles (v0.32). */
  oublierCle?(utilisateurId: string): Promise<void>;
  /** Chiffre les données de ce compte enregistrées par une version précédente, en clair (v0.32). */
  chiffrerAnciennes?(utilisateurId: string): Promise<void>;
}

export const STOCKAGE = new InjectionToken<Stockage>('STOCKAGE', {
  providedIn: 'root',
  factory: () => {
    const base: Stockage = typeof indexedDB !== 'undefined' ? new StockageIndexedDb() : new StockageMemoire();
    // WebCrypto n'existe que sur HTTPS (ou localhost) : en HTTP sur le réseau local, données en clair
    const subtle = globalThis.crypto?.subtle;
    return subtle && globalThis.isSecureContext !== false ? new StockageChiffre(base, new CoffreStockage(base, subtle), subtle) : base;
  },
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

// ------------------------------------------------------------------ chiffrement local (v0.32)

/** Données d'un compte : listes de classes, appels, notes et cahier de textes en attente. */
const DONNEES_COMPTE = /^(cache|envoi|notes|cahier):([^:]+):/;
const CLE_COFFRE = 'cle:';

/** Valeur chiffrée telle qu'enregistrée sur l'appareil. */
interface Enveloppe {
  chiffre: 1;
  iv: Uint8Array<ArrayBuffer>;
  d: ArrayBuffer;
}

function estEnveloppe(v: unknown): v is Enveloppe {
  return !!v && typeof v === 'object' && (v as Enveloppe).chiffre === 1 && 'iv' in (v as object) && 'd' in (v as object);
}

/** Clés de chiffrement, une par compte sur cet appareil. */
export interface Coffre {
  /** Clé du compte ; {@code creer} : la crée si elle n'existe pas. */
  cle(utilisateurId: string, creer: boolean): Promise<CryptoKey | undefined>;
  oublier(utilisateurId: string): Promise<void>;
  /** Après un effacement complet de l'appareil. */
  vider(): void;
}

/**
 * Clés AES-GCM 256 bits non exportables, conservées dans le stockage de l'appareil (IndexedDB
 * sait les garder sans jamais exposer leur valeur). Une clé détruite rend illisible tout ce
 * qu'elle a chiffré.
 */
export class CoffreStockage implements Coffre {
  private readonly enMemoire = new Map<string, CryptoKey>();

  constructor(
    private readonly base: Stockage,
    private readonly subtle: SubtleCrypto,
  ) {}

  async cle(utilisateurId: string, creer: boolean): Promise<CryptoKey | undefined> {
    const connue = this.enMemoire.get(utilisateurId) ?? (await this.base.lire<CryptoKey>(CLE_COFFRE + utilisateurId));
    if (connue) {
      this.enMemoire.set(utilisateurId, connue);
      return connue;
    }
    if (!creer) {
      return undefined;
    }
    const nouvelle = await this.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
    await this.base.ecrire(CLE_COFFRE + utilisateurId, nouvelle);
    this.enMemoire.set(utilisateurId, nouvelle);
    return nouvelle;
  }

  async oublier(utilisateurId: string): Promise<void> {
    this.enMemoire.delete(utilisateurId);
    await this.base.supprimer(CLE_COFFRE + utilisateurId);
  }

  vider(): void {
    this.enMemoire.clear();
  }
}

/**
 * Stockage qui chiffre (AES-GCM) les données de chaque compte avec la clé de ce compte. Le profil
 * affiché et les réglages restent en clair. Une valeur illisible (clé détruite, donnée abîmée) est
 * ignorée comme si elle n'existait pas.
 */
export class StockageChiffre implements Stockage {
  private readonly encodeur = new TextEncoder();
  private readonly decodeur = new TextDecoder();

  constructor(
    private readonly base: Stockage,
    private readonly coffre: Coffre,
    private readonly subtle: SubtleCrypto,
  ) {}

  async lire<T>(cle: string): Promise<T | undefined> {
    return this.ouvrir<T>(cle, await this.base.lire<unknown>(cle));
  }

  async ecrire<T>(cle: string, valeur: T): Promise<void> {
    const compte = DONNEES_COMPTE.exec(cle)?.[2];
    if (!compte) {
      await this.base.ecrire(cle, valeur);
      return;
    }
    const k = (await this.coffre.cle(compte, true))!;
    const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
    const d = await this.subtle.encrypt({ name: 'AES-GCM', iv }, k, this.encodeur.encode(JSON.stringify(valeur)));
    await this.base.ecrire<Enveloppe>(cle, { chiffre: 1, iv, d });
  }

  supprimer(cle: string): Promise<void> {
    return this.base.supprimer(cle);
  }

  async lister<T>(prefixe: string): Promise<T[]> {
    const [cles, valeurs] = await Promise.all([this.base.cles(prefixe), this.base.lister<unknown>(prefixe)]);
    const ouvertes = await Promise.all(valeurs.map((v, i) => this.ouvrir<T>(cles[i] ?? '', v)));
    return ouvertes.filter((v): v is Awaited<T> => v !== undefined) as T[];
  }

  async purger(prefixe: string): Promise<void> {
    await this.base.purger(prefixe);
    if (prefixe === '' || prefixe.startsWith(CLE_COFFRE)) {
      this.coffre.vider();
    }
  }

  cles(prefixe: string): Promise<string[]> {
    return this.base.cles(prefixe);
  }

  oublierCle(utilisateurId: string): Promise<void> {
    return this.coffre.oublier(utilisateurId);
  }

  async chiffrerAnciennes(utilisateurId: string): Promise<void> {
    for (const type of ['cache', 'envoi', 'notes', 'cahier']) {
      for (const cle of await this.base.cles(`${type}:${utilisateurId}:`)) {
        const v = await this.base.lire<unknown>(cle);
        if (v !== undefined && !estEnveloppe(v)) {
          await this.ecrire(cle, v);
        }
      }
    }
  }

  private async ouvrir<T>(cle: string, valeur: unknown): Promise<T | undefined> {
    if (!estEnveloppe(valeur)) {
      return valeur as T | undefined; // en clair : profil, réglages, ou donnée d'une version précédente
    }
    const compte = DONNEES_COMPTE.exec(cle)?.[2];
    const k = compte ? await this.coffre.cle(compte, false) : undefined;
    if (!k) {
      return undefined;
    }
    try {
      const clair = await this.subtle.decrypt({ name: 'AES-GCM', iv: valeur.iv }, k, valeur.d);
      return JSON.parse(this.decodeur.decode(clair)) as T;
    } catch {
      return undefined;
    }
  }
}
