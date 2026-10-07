import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { EtablissementAccessible } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { ONGLETS } from './admin-nav.component';
import { DirectionChemin, DirectionVue, IdentiteVue, MinistereVue, PaysVue } from './modeles-territoire';
import { AdoptionPlateformePage } from './pages/adoption-plateforme.page';
import { IdentitePage, TAILLE_MAX_LOGO } from './pages/identite.page';
import { PlateformePage } from './pages/plateforme.page';
import { arbre, TerritoirePage } from './pages/territoire.page';

const ETAB: EtablissementAccessible = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: ['ADMIN_ECOLE'], modulesDesactives: [] };

async function session(http: HttpTestingController, etab: EtablissementAccessible | null, superAdmin = false): Promise<void> {
  const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: etab, etablissements: etab ? [etab] : [], superAdmin }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'KABORE', prenoms: 'Mathieu' });
  await connexion;
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement {
  return [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim().startsWith(libelle))!;
}

const BF: PaysVue = {
  id: 'bf', code: 'BF', nom: 'Burkina Faso', deviseNationale: null, indicatifTelephone: '+226', longueurNumero: 8,
  fuseauHoraire: 'Africa/Ouagadougou', monnaie: 'XOF', langue: 'fr', ministeres: 1, etablissements: 0,
};
const MESFPT: MinistereVue = {
  id: 'm1', paysId: 'bf', sigle: 'MESFPT', nom: 'Ministère des enseignements secondaire, de la formation professionnelle et technique',
  actif: true, niveaux: ['Direction régionale', 'Direction provinciale'], directions: 3, etablissements: 0,
};
const DIRECTIONS: DirectionVue[] = [
  { id: 'dp2', parentId: 'dr', rang: 2, code: 'DP-BAZ', nom: 'DP du Bazèga', actif: true, etablissements: 0 },
  { id: 'dr', parentId: null, rang: 1, code: 'DR-CEN', nom: 'DR du Centre', actif: true, etablissements: 0 },
  { id: 'dp1', parentId: 'dr', rang: 2, code: 'DP-KAD', nom: 'DP du Kadiogo', actif: true, etablissements: 2 },
];

describe('Territoire et documents officiels (v0.35)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => {
    vi.restoreAllMocks();
    http.verify();
  });

  it('arbre : chaque direction suivie de celles qui en dépendent, par nom', () => {
    expect(arbre(DIRECTIONS).map((d) => `${d.profondeur}:${d.code}`)).toEqual(['0:DR-CEN', '1:DP-BAZ', '1:DP-KAD']);
    expect(ONGLETS.find((o) => o.lien === '/admin/identite')?.roles).toEqual(['ADMIN_ECOLE']);
  });

  it('super administrateur : ministère, arbre des directions, import CSV vérifié puis enregistré', async () => {
    await session(http, null, true);
    const f = TestBed.createComponent(TerritoirePage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/pays').flush([BF]);
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/pays/bf/ministeres').flush([MESFPT]);
    await attendre();
    expect(texte(f)).toContain('Indicatif +226 (8 chiffres)');
    expect(texte(f)).toContain('Direction régionale › Direction provinciale');

    bouton(f, 'Directions').click();
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/ministeres/m1/directions').flush(DIRECTIONS);
    await attendre();
    const lignes = [...(f.nativeElement as HTMLElement).querySelectorAll('ul.arbre li')].map((l) => (l.textContent ?? '').replace(/\s+/g, ' ').trim());
    expect(lignes[0]).toContain('Direction régionale · DR du Centre (DR-CEN)');
    expect(lignes[1]).toContain('Direction provinciale · DP du Bazèga');
    expect(lignes[2]).toContain('2 établissement(s)');
    // Pas d'« Ajouter dessous » au dernier niveau
    expect([...(f.nativeElement as HTMLElement).querySelectorAll('ul.arbre li')].map((l) => l.textContent?.includes('Ajouter dessous'))).toEqual([true, false, false]);

    // Import : vérification d'abord ; « Importer » n'est possible qu'après une vérification sans erreur
    const zone = (f.nativeElement as HTMLElement).querySelector<HTMLTextAreaElement>('#d-csv')!;
    zone.value = 'DP-ZIN;DP du Ziniaré;DR-PC\nDR-PC;DR du Plateau-Central;';
    zone.dispatchEvent(new Event('input'));
    f.detectChanges();
    expect(bouton(f, 'Importer').disabled).toBe(true);
    bouton(f, 'Vérifier').click();
    await attendre();
    const simulation = http.expectOne('/api/v1/plateforme/territoire/ministeres/m1/directions/import');
    expect(simulation.request.body).toEqual({ contenu: 'DP-ZIN;DP du Ziniaré;DR-PC\nDR-PC;DR du Plateau-Central;', simulation: true });
    simulation.flush({ simulation: true, lignes: 2, creees: 2, modifiees: 0, inchangees: 0, erreurs: [] });
    await attendre();
    expect(texte(f)).toContain('Vérification : 2 ligne(s) · 2 à créer');
    expect(bouton(f, 'Importer').disabled).toBe(false);
    bouton(f, 'Importer').click();
    await attendre();
    const reel = http.expectOne('/api/v1/plateforme/territoire/ministeres/m1/directions/import');
    expect(reel.request.body.simulation).toBe(false);
    reel.flush({ simulation: false, lignes: 2, creees: 2, modifiees: 0, inchangees: 0, erreurs: [] });
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/ministeres/m1/directions').flush(DIRECTIONS);
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/pays/bf/ministeres').flush([MESFPT]);
    await attendre();
    expect(texte(f)).toContain('Import terminé : 2 créée(s), 0 modifiée(s).');
  });

  it('import en erreur : les lignes fautives sont listées et rien n’est importable', async () => {
    await session(http, null, true);
    const f = TestBed.createComponent(TerritoirePage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/pays').flush([BF]);
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/pays/bf/ministeres').flush([MESFPT]);
    await attendre();
    bouton(f, 'Directions').click();
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/ministeres/m1/directions').flush([]);
    await attendre();
    const zone = (f.nativeElement as HTMLElement).querySelector<HTMLTextAreaElement>('#d-csv')!;
    zone.value = 'X;Y;INCONNU';
    zone.dispatchEvent(new Event('input'));
    f.detectChanges();
    bouton(f, 'Vérifier').click();
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/ministeres/m1/directions/import')
      .flush({ simulation: true, lignes: 1, creees: 1, modifiees: 0, inchangees: 0, erreurs: [{ ligne: 1, message: 'Parent « INCONNU » introuvable' }] });
    await attendre();
    expect(texte(f)).toContain('Ligne 1 : Parent « INCONNU » introuvable');
    expect(bouton(f, 'Importer').disabled).toBe(true);
  });

  it('établissements : rattachement affiché, modifié, et filtre par direction', async () => {
    await session(http, null, true);
    const f = TestBed.createComponent(PlateformePage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/plateforme/etablissements').flush([
      { id: 'e1', code: 'ltk', nom: 'Lycée technique', statut: 'ACTIF', creeLe: '2026-09-01T08:00:00Z', directionId: null, rattachement: null },
    ]);
    await attendre();
    expect(texte(f)).toContain('non rattaché');

    bouton(f, 'Rattachement').click();
    await attendre();
    const chemins: DirectionChemin[] = [
      { id: 'dr', paysId: 'bf', ministereId: 'm1', rang: 1, terminale: false, chemin: 'Burkina Faso · MESFPT · DR du Centre', actif: true },
      { id: 'dp1', paysId: 'bf', ministereId: 'm1', rang: 2, terminale: true, chemin: 'Burkina Faso · MESFPT · DR du Centre · DP du Kadiogo', actif: true },
    ];
    http.expectOne('/api/v1/plateforme/territoire/directions').flush(chemins);
    await attendre();
    f.detectChanges();
    await f.whenStable();
    const select = (f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#ratt-e1')!;
    select.selectedIndex = 1;
    select.dispatchEvent(new Event('change'));
    f.detectChanges();
    bouton(f, 'Enregistrer').click();
    await attendre();
    const req = http.expectOne((r) => r.url === '/api/v1/plateforme/etablissements/e1/rattachement' && r.method === 'PUT');
    expect(req.request.body).toEqual({ directionId: 'dp1' });
    req.flush({ id: 'e1', code: 'ltk', nom: 'Lycée technique', statut: 'ACTIF', creeLe: '2026-09-01T08:00:00Z', directionId: 'dp1',
      rattachement: 'Burkina Faso · MESFPT · DR du Centre · DP du Kadiogo' });
    await attendre();
    expect(texte(f)).toContain('Burkina Faso · MESFPT · DR du Centre · DP du Kadiogo');
    expect(texte(f)).not.toContain('non rattaché');

    // Filtre : toutes les directions (régionales comprises) ; la liste est redemandée au serveur
    const filtre = (f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#filtreDirection')!;
    expect([...filtre.options].map((o) => o.textContent?.trim())).toEqual(['Toutes les directions', ...chemins.map((c) => c.chemin)]);
    filtre.selectedIndex = 1;
    filtre.dispatchEvent(new Event('change'));
    await attendre();
    const filtree = http.expectOne((r) => r.url === '/api/v1/plateforme/etablissements');
    expect(filtree.request.params.get('direction')).toBe('dr');
    filtree.flush([]);
    await attendre();
  });

  it('adoption : le filtre par direction est transmis au serveur', async () => {
    await session(http, null, true);
    const f = TestBed.createComponent(AdoptionPlateformePage);
    f.detectChanges();
    await attendre();
    const vide = { debut: '2026-09-08', fin: '2026-10-07', calculeLe: '2026-10-07T10:00:00Z', indicateurs: [], etablissements: 0,
      etablissementsActifs: 0, comptes: 0, actifs: 0, enseignants: { total: 0, actifs: 0 }, parents: { total: 0, actifs: 0 },
      actions: {}, quotidien: [], details: [] };
    http.expectOne((r) => r.url === '/api/v1/plateforme/adoption').flush(vide);
    await attendre();
    const select = (f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#adoptionDirection')!;
    select.dispatchEvent(new Event('focus'));
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/directions').flush([
      { id: 'dr', paysId: 'bf', ministereId: 'm1', rang: 1, terminale: false, chemin: 'Burkina Faso · MESFPT · DR du Centre', actif: true },
    ]);
    await attendre();
    f.detectChanges();
    select.value = 'dr';
    select.dispatchEvent(new Event('change'));
    await attendre();
    const r = http.expectOne((x) => x.url === '/api/v1/plateforme/adoption');
    expect(r.request.params.get('direction')).toBe('dr');
    expect(r.request.params.get('jours')).toBe('30');
    r.flush(vide);
    await attendre();
  });

  it('administrateur : en-tête officiel, logo ajouté (contrôlé avant envoi) puis supprimé', async () => {
    await session(http, ETAB);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:logo');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const f = TestBed.createComponent(IdentitePage);
    f.detectChanges();
    await attendre();
    http.match('/api/v1/annees').forEach((r) => r.flush([]));
    const sansLogo: IdentiteVue = {
      nom: 'Lycée technique', pays: 'Burkina Faso', devise: 'Unité – Progrès – Justice',
      autorites: ['Ministère X', 'Direction régionale du Centre', 'Direction provinciale du Kadiogo'], rattache: true, logo: null,
    };
    http.expectOne('/api/v1/identite').flush(sansLogo);
    await attendre();
    let t = texte(f);
    expect(t).toContain('BURKINA FASO');
    expect(t).toContain('Direction provinciale du Kadiogo');
    expect(t).toContain('Aucun logo');
    expect(t).not.toContain('pas encore rattaché');

    const champ = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type=file]')!;
    const choisir = (fichier: File) => {
      Object.defineProperty(champ, 'files', { value: [fichier], configurable: true });
      champ.dispatchEvent(new Event('change'));
    };
    choisir(new File([new Uint8Array(TAILLE_MAX_LOGO + 1)], 'gros.png', { type: 'image/png' }));
    await attendre();
    expect(texte(f)).toContain('Logo trop lourd');
    choisir(new File(['x'], 'logo.gif', { type: 'image/gif' }));
    await attendre();
    expect(texte(f)).toContain('PNG ou JPEG');

    choisir(new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'logo.png', { type: 'image/png' }));
    await attendre();
    const envoi = http.expectOne((r) => r.url === '/api/v1/identite/logo' && r.method === 'POST');
    expect(envoi.request.body instanceof FormData).toBe(true);
    envoi.flush({ ...sansLogo, logo: { type: 'image/png', taille: 2048, modifieLe: '2026-10-07T10:00:00Z' } });
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/identite/logo' && r.method === 'GET').flush(new Blob(['png']));
    await attendre();
    t = texte(f);
    expect(t).toContain('Logo enregistré');
    expect(t).toContain('Logo actuel (PNG, 2 Ko)');
    expect((f.nativeElement as HTMLElement).querySelector('img')?.getAttribute('src')).toBe('blob:logo');

    bouton(f, 'Supprimer le logo').click();
    f.detectChanges();
    bouton(f, 'Confirmer la suppression').click();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/identite/logo' && r.method === 'DELETE').flush(sansLogo);
    await attendre();
    expect(texte(f)).toContain('Logo supprimé');
    expect((f.nativeElement as HTMLElement).querySelector('img')).toBeNull();
  });

  it('administrateur : établissement pas encore rattaché', async () => {
    await session(http, ETAB);
    const f = TestBed.createComponent(IdentitePage);
    f.detectChanges();
    await attendre();
    http.match('/api/v1/annees').forEach((r) => r.flush([]));
    http.expectOne('/api/v1/identite').flush({ nom: 'Lycée technique', pays: null, devise: null, autorites: [], rattache: false, logo: null });
    await attendre();
    expect(texte(f)).toContain('pas encore rattaché');
  });
});
