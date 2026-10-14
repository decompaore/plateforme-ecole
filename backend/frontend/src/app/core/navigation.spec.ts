import { NavigationError } from '@angular/router';

import { estEchecDeChargement, traiterErreurNavigation } from './navigation';

function fenetre(): { f: Window; assign: ReturnType<typeof vi.fn> } {
  const stock = new Map<string, string>();
  const assign = vi.fn();
  const f = {
    sessionStorage: { getItem: (k: string) => stock.get(k) ?? null, setItem: (k: string, v: string) => stock.set(k, v) },
    location: { assign },
  } as unknown as Window;
  return { f, assign };
}

describe('Erreurs de navigation (v0.44.1)', () => {
  beforeEach(() => vi.spyOn(console, 'error').mockImplementation(() => undefined));
  afterEach(() => vi.restoreAllMocks());

  it('reconnaît un écran qui ne se charge pas', () => {
    expect(estEchecDeChargement(new TypeError('Failed to fetch dynamically imported module: http://x/chunk-AB.js'))).toBe(true);
    expect(estEchecDeChargement(new TypeError('Importing a module script failed.'))).toBe(true);
    expect(estEchecDeChargement(new Error('NG0100: autre chose'))).toBe(false);
    expect(estEchecDeChargement(null)).toBe(false);
  });

  it('recharge une fois l’adresse demandée, pas en boucle', () => {
    const { f, assign } = fenetre();
    const erreur = new NavigationError(1, '/admin/classes', new TypeError('Failed to fetch dynamically imported module'));
    traiterErreurNavigation(erreur, f);
    expect(assign).toHaveBeenCalledWith('/admin/classes');
    traiterErreurNavigation(erreur, f);
    expect(assign).toHaveBeenCalledTimes(1);
  });

  it('une autre erreur ne recharge pas la page', () => {
    const { f, assign } = fenetre();
    traiterErreurNavigation(new NavigationError(1, '/admin/eleves', new Error('bogue')), f);
    expect(assign).not.toHaveBeenCalled();
  });
});
