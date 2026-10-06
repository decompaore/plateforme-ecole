import { Coffre, StockageChiffre, StockageMemoire } from './stockage';

/** Coffre de test : clés en mémoire (sans copie), WebCrypto du moteur de test. */
class CoffreMemoire implements Coffre {
  readonly cles = new Map<string, CryptoKey>();
  constructor(private readonly subtle: SubtleCrypto) {}
  async cle(utilisateurId: string, creer: boolean): Promise<CryptoKey | undefined> {
    if (!this.cles.has(utilisateurId) && creer) {
      this.cles.set(utilisateurId, await this.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']));
    }
    return this.cles.get(utilisateurId);
  }
  async oublier(utilisateurId: string): Promise<void> {
    this.cles.delete(utilisateurId);
  }
  vider(): void {
    this.cles.clear();
  }
}

async function subtle(): Promise<SubtleCrypto> {
  return globalThis.crypto.subtle;
}

describe('Stockage chiffré (v0.32)', () => {
  let base: StockageMemoire;
  let coffre: CoffreMemoire;
  let stockage: StockageChiffre;

  beforeEach(async () => {
    const s = await subtle();
    base = new StockageMemoire();
    coffre = new CoffreMemoire(s);
    stockage = new StockageChiffre(base, coffre, s);
  });

  it('chiffre les données d’un compte, laisse le profil en clair', async () => {
    const liste = { eleves: [{ nom: 'OUEDRAOGO', prenoms: 'Awa' }] };
    await stockage.ecrire('cache:u1:etab-1:classe:c1', liste);
    await stockage.ecrire('session:profil', { utilisateurId: 'u1', nom: 'KABORE' });

    const brut = await base.lire<Record<string, unknown>>('cache:u1:etab-1:classe:c1');
    expect(brut?.['chiffre']).toBe(1);
    expect(JSON.stringify(brut)).not.toContain('OUEDRAOGO');
    expect(await stockage.lire('cache:u1:etab-1:classe:c1')).toEqual(liste);
    expect(await base.lire('session:profil')).toEqual({ utilisateurId: 'u1', nom: 'KABORE' });
  });

  it('liste dans l’ordre des clés ; une clé détruite rend les données illisibles', async () => {
    await stockage.ecrire('envoi:u1:etab-1:b', { n: 2 });
    await stockage.ecrire('envoi:u1:etab-1:a', { n: 1 });
    await stockage.ecrire('envoi:u2:etab-1:a', { n: 3 });
    expect(await stockage.lister('envoi:u1:')).toEqual([{ n: 1 }, { n: 2 }]);

    await stockage.oublierCle('u1');
    expect(await stockage.lire('envoi:u1:etab-1:a')).toBeUndefined();
    expect(await stockage.lister('envoi:')).toEqual([{ n: 3 }]);
    // Une nouvelle clé ne relit pas les anciennes données
    await stockage.ecrire('envoi:u1:etab-1:c', { n: 4 });
    expect(await stockage.lister('envoi:u1:')).toEqual([{ n: 4 }]);
  });

  it('chiffre les données laissées en clair par une version précédente', async () => {
    await base.ecrire('notes:u1:etab-1:x', { valeur: 12 });
    await base.ecrire('cahier:u1:etab-1:y', { contenu: 'Loi d’Ohm' });
    expect(await stockage.lire('notes:u1:etab-1:x')).toEqual({ valeur: 12 });

    await stockage.chiffrerAnciennes('u1');
    expect(JSON.stringify(await base.lire('cahier:u1:etab-1:y'))).not.toContain('Ohm');
    expect(await stockage.lire('cahier:u1:etab-1:y')).toEqual({ contenu: 'Loi d’Ohm' });
    expect(await stockage.lire('notes:u1:etab-1:x')).toEqual({ valeur: 12 });
  });
});
