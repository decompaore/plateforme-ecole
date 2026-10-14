import { arbreTerritoire, basculer, niveaux, ouvertsParDefaut, SANS_RATTACHEMENT } from './arbre-territoire';

interface E {
  nom: string;
  r: string | null;
}

const ECOLES: E[] = [
  { nom: 'Lycée A', r: 'Burkina Faso · MESFPT · DR du Centre · DP du Kadiogo' },
  { nom: 'Lycée B', r: 'Burkina Faso · MESFPT · DR du Centre · DP du Bazèga' },
  { nom: 'Lycée C', r: 'Burkina Faso · MESFPT · DR des Hauts-Bassins · DP du Houet' },
  { nom: 'CFP D', r: 'Burkina Faso · MESFPT · DR du Centre · DP du Kadiogo' },
];

const vue = (lignes: ReturnType<typeof arbreTerritoire<E>>['lignes']) =>
  lignes.map((l) => `${l.profondeur}:${l.groupe ? `[${l.groupe.nom} ${l.groupe.elements.length}]` : l.element!.nom}`);

describe('Arbre territorial (v0.39)', () => {
  it('niveaux communs retirés, groupes triés, éléments dans leur direction', () => {
    const a = arbreTerritoire(ECOLES, (e) => e.r, new Set(), true);
    expect(a.prefixe).toEqual(['Burkina Faso', 'MESFPT']);
    expect(vue(a.lignes)).toEqual([
      '0:[DR des Hauts-Bassins 1]',
      '1:[DP du Houet 1]',
      '2:Lycée C',
      '0:[DR du Centre 3]',
      '1:[DP du Bazèga 1]',
      '2:Lycée B',
      '1:[DP du Kadiogo 2]',
      '2:Lycée A',
      '2:CFP D',
    ]);
    expect(a.cles.length).toBe(5);
  });

  it('repli : un groupe fermé cache ses sous-groupes ; dépliage par défaut du premier niveau', () => {
    const ouverts = ouvertsParDefaut(ECOLES, (e) => e.r);
    let a = arbreTerritoire(ECOLES, (e) => e.r, ouverts);
    expect(vue(a.lignes)).toEqual(['0:[DR des Hauts-Bassins 1]', '1:[DP du Houet 1]', '0:[DR du Centre 3]', '1:[DP du Bazèga 1]', '1:[DP du Kadiogo 2]']);
    const kadiogo = a.lignes.find((l) => l.groupe?.nom === 'DP du Kadiogo')!.groupe!.cle;
    a = arbreTerritoire(ECOLES, (e) => e.r, basculer(ouverts, kadiogo));
    expect(vue(a.lignes)).toContain('2:CFP D');
    expect(basculer(basculer(ouverts, kadiogo), kadiogo).has(kadiogo)).toBe(false);
  });

  it('établissements non rattachés : groupe à part, en dernier, sans niveau commun retiré', () => {
    const a = arbreTerritoire([...ECOLES, { nom: 'Privé X', r: null }], (e) => e.r, new Set(), true);
    expect(a.prefixe).toEqual([]);
    expect(a.lignes[0].groupe?.nom).toBe('Burkina Faso');
    expect(a.lignes.at(-2)?.groupe?.nom).toBe(SANS_RATTACHEMENT);
    expect(a.lignes.at(-1)?.element?.nom).toBe('Privé X');
    expect(niveaux('  ')).toEqual([]);
    // Personne n'est rattaché : liste simple, sans groupe
    const seuls = arbreTerritoire([{ nom: 'X', r: null }, { nom: 'Y', r: '' }], (e) => e.r, new Set());
    expect(vue(seuls.lignes)).toEqual(['0:X', '0:Y']);
  });

  it('un seul établissement : son rattachement en tête, sa direction comme seul groupe', () => {
    const a = arbreTerritoire([ECOLES[0]], (e) => e.r, new Set(), true);
    expect(a.prefixe).toEqual(['Burkina Faso', 'MESFPT', 'DR du Centre']);
    expect(vue(a.lignes)).toEqual(['0:[DP du Kadiogo 1]', '1:Lycée A']);
  });
});
