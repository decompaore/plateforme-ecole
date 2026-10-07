import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DirectionVue, MinistereVue, PaysVue } from '../admin/modeles-territoire';
import { TerritoirePage } from '../admin/pages/territoire.page';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { Compte, Indicateurs, TableauPilotage } from './modeles-pilotage';
import { nomsExamens, partFilles, PilotagePage, pourcent, tauxExamen } from './pilotage.page';

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement {
  return [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim().startsWith(libelle))!;
}

const c = (garcons: number, filles: number): Compte => ({ garcons, filles, total: garcons + filles });
const p = (hommes: number, femmes: number) => ({ hommes, femmes, total: hommes + femmes });

function indicateurs(eleves: Compte, bepc: { admis: number; resultats: number; taux: number | null } | null, autres: Partial<Indicateurs> = {}): Indicateurs {
  return {
    etablissements: 1,
    classes: 1,
    eleves,
    redoublants: c(0, 0),
    enseignants: p(1, 0),
    titulaires: p(1, 0),
    vacataires: p(0, 0),
    elevesParEnseignant: eleves.total,
    decides: c(0, 0),
    admis: c(0, 0),
    tauxAdmission: null,
    niveaux: [],
    examens: bepc
      ? [{ examen: 'BEPC', candidats: c(1, 2), resultats: c(1, 2), admis: c(0, bepc.admis), taux: bepc.taux, tauxGarcons: 0, tauxFilles: 100 }]
      : [],
    periodes: [],
    utilisation: { comptes: 10, actifs: 4, enseignants: 1, enseignantsActifs: 1, derniereActivite: '2026-10-06' },
    ...autres,
  };
}

const LYCEE_A = indicateurs(c(1, 2), { admis: 2, resultats: 3, taux: 66.7 }, {
  niveaux: [{ niveau: '3e', classes: 1, eleves: c(1, 2), redoublants: c(0, 0), decides: c(1, 2), admis: c(0, 2), tauxAdmission: 66.7 }],
  periodes: [{ decoupage: 'TRIMESTRE', ordre: 1, libelle: 'Trimestre 1', bulletins: c(1, 2), moyenne: 11.5, admis: c(0, 2), taux: 66.7 }],
});
const LYCEE_B = indicateurs(c(2, 0), null);

const TABLEAU: TableauPilotage = {
  perimetre: { type: 'DIRECTION', id: 'dr', nom: 'DR du Centre', niveau: 'Direction régionale', chemin: 'Burkina Faso · MESFPT · DR du Centre' },
  parent: null,
  annees: ['2026-2027', '2025-2026'],
  annee: '2026-2027',
  debutUtilisation: '2026-09-08',
  finUtilisation: '2026-10-07',
  synthese: indicateurs(c(3, 2), { admis: 2, resultats: 3, taux: 66.7 }, {
    etablissements: 2, classes: 2, decides: c(1, 2), admis: c(0, 2), tauxAdmission: 66.7, enseignants: p(1, 1), titulaires: p(1, 0), vacataires: p(0, 1),
    elevesParEnseignant: 2.5, niveaux: LYCEE_A.niveaux, periodes: LYCEE_A.periodes,
  }),
  niveauDirections: 'Direction provinciale',
  directions: [
    { id: 'dp1', code: 'DP1', nom: 'DP du Kadiogo', indicateurs: LYCEE_A },
    { id: 'dp2', code: 'DP2', nom: 'DP du Bazèga', indicateurs: LYCEE_B },
  ],
  etablissements: [
    { id: 'a', code: 'lycee-a', nom: 'Lycée A', statut: 'ACTIF', directionId: 'dp1', direction: 'DP du Kadiogo', sousDirectionId: 'dp1', indicateurs: LYCEE_A },
    { id: 'b', code: 'lycee-b', nom: 'Lycée B', statut: 'SUSPENDU', directionId: 'dp2', direction: 'DP du Bazèga', sousDirectionId: 'dp2', indicateurs: LYCEE_B },
  ],
  produitLe: '2026-10-07T10:00:00Z',
};

async function connexionDirection(http: HttpTestingController): Promise<void> {
  const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: null, etablissements: [], superAdmin: false,
    direction: { id: 'dr', code: 'DR', nom: 'DR du Centre', chemin: 'Burkina Faso · MESFPT · DR du Centre', paysId: 'bf' } }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'SANOU', prenoms: 'Aline' });
  await connexion;
}

describe('Pilotage des directions (v0.37)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => {
    vi.restoreAllMocks();
    http.verify();
  });

  it('formats : pourcentages, part des filles, examens présents', () => {
    expect(pourcent(66.7)).toBe('66,7 %');
    expect(pourcent(null)).toBe('—');
    expect(partFilles(c(1, 2))).toBe('66,7 %');
    expect(partFilles(c(0, 0))).toBe('—');
    expect(nomsExamens(TABLEAU)).toEqual(['BEPC']);
    expect(tauxExamen(LYCEE_A, 'BEPC')).toBe('66,7 %');
    expect(tauxExamen(LYCEE_B, 'BEPC')).toBe('—');
  });

  it('compte de direction : session sans établissement ni administration de la plateforme', async () => {
    await connexionDirection(http);
    const session = TestBed.inject(SessionService);
    expect(session.pilote()).toBe(true);
    expect(session.administrePlateforme()).toBe(false);
    expect(session.profil()?.direction?.nom).toBe('DR du Centre');
    expect(session.profil()?.etablissement).toBeNull();
  });

  it('tableau de bord : synthèse, examens, directions provinciales, établissements, puis descente', async () => {
    await connexionDirection(http);
    const f = TestBed.createComponent(PilotagePage);
    f.detectChanges();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/pilotage' && !r.params.keys().length).flush(TABLEAU);
    await attendre();
    let t = texte(f);
    expect(t).toContain('Direction régionale · DR du Centre');
    expect(t).toContain('élèves dont 2 filles (40 %) · 2 classes');
    expect(t).toContain('2,5 élèves par enseignant');
    expect(t).toContain('de réussite au BEPC (2 / 3 résultats connus)');
    expect(t).toContain('Examens de fin d\'études');
    expect(t).toContain('Trimestre 1');
    expect(t).toContain('11,5');
    expect(t).toContain('Direction provinciale');
    expect(t).toContain('DP du Kadiogo');
    expect(t).toContain('Suspendu');
    // Pas d'onglets d'administration de la plateforme pour une direction
    expect((f.nativeElement as HTMLElement).querySelector('app-plateforme-nav')).toBeNull();

    // Filtre des établissements par direction provinciale
    const sous = (f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#pilotageSous')!;
    sous.value = 'dp2';
    sous.dispatchEvent(new Event('change'));
    t = texte(f);
    expect(t).toContain('Lycée B');
    expect(t).not.toContain('Lycée A');

    // Détail d'un établissement : uniquement des nombres
    bouton(f, 'Détail').click();
    expect(texte(f)).toContain('2 garçons, 0 filles');

    // Descendre vers une direction provinciale ; l'année choisie suit
    bouton(f, 'Ouvrir').click();
    await attendre();
    const r = http.expectOne((x) => x.url === '/api/v1/pilotage' && x.params.get('direction') === 'dp1');
    expect(r.request.params.get('annee')).toBe('2026-2027');
    expect(r.request.params.has('pays')).toBe(false);
    r.flush({ ...TABLEAU, perimetre: { type: 'DIRECTION', id: 'dp1', nom: 'DP du Kadiogo', niveau: 'Direction provinciale', chemin: '…' },
      parent: { type: 'DIRECTION', id: 'dr', nom: 'DR du Centre' }, directions: [], etablissements: [TABLEAU.etablissements[0]] });
    await attendre();
    expect(texte(f)).toContain('↑ DR du Centre');

    bouton(f, '↑ DR du Centre').click();
    await attendre();
    http.expectOne((x) => x.url === '/api/v1/pilotage' && x.params.get('direction') === 'dr').flush(TABLEAU);
    await attendre();
    expect(texte(f)).not.toContain('↑');
  });

  it('changement d\'année et export Excel', async () => {
    await connexionDirection(http);
    const f = TestBed.createComponent(PilotagePage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/pilotage').flush(TABLEAU);
    await attendre();
    f.detectChanges();
    const annee = (f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#pilotageAnnee')!;
    annee.value = '2025-2026';
    annee.dispatchEvent(new Event('change'));
    await attendre();
    http.expectOne((x) => x.url === '/api/v1/pilotage' && x.params.get('annee') === '2025-2026').flush({ ...TABLEAU, annee: '2025-2026' });
    await attendre();

    const creer = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const clic = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    bouton(f, 'Excel').click();
    await attendre();
    const e = http.expectOne((x) => x.url === '/api/v1/pilotage/export');
    expect(e.request.params.get('format')).toBe('xlsx');
    expect(e.request.params.get('annee')).toBe('2025-2026');
    e.flush(new Blob(['x']));
    await attendre();
    expect(creer).toHaveBeenCalled();
    expect(clic).toHaveBeenCalled();
  });

  it('territoire : l\'administrateur pays crée le compte d\'une direction (mot de passe affiché une fois)', async () => {
    const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: null, etablissements: [], superAdmin: false,
      adminPays: { id: 'bf', code: 'BF', nom: 'Burkina Faso' } }));
    await attendre();
    http.expectOne('/api/v1/moi').flush({ nom: 'OUEDRAOGO', prenoms: 'Awa' });
    await connexion;
    expect(TestBed.inject(SessionService).pilote()).toBe(true);

    const BF: PaysVue = { id: 'bf', code: 'BF', nom: 'Burkina Faso', deviseNationale: null, indicatifTelephone: '+226', longueurNumero: 8,
      fuseauHoraire: 'Africa/Ouagadougou', monnaie: 'XOF', langue: 'fr', ministeres: 1, etablissements: 0 };
    const M: MinistereVue = { id: 'm1', paysId: 'bf', sigle: 'MESFPT', nom: 'MESFPT', actif: true, niveaux: ['Direction régionale', 'Direction provinciale'],
      directions: 1, etablissements: 0 };
    const DR: DirectionVue = { id: 'dr', parentId: null, rang: 1, code: 'DR', nom: 'DR du Centre', actif: true, etablissements: 0 };

    const f = TestBed.createComponent(TerritoirePage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/pays').flush([BF]);
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/pays/bf/ministeres').flush([M]);
    await attendre();
    bouton(f, 'Directions').click();
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/ministeres/m1/directions').flush([DR]);
    await attendre();
    const lien = (f.nativeElement as HTMLElement).querySelector<HTMLAnchorElement>('a[href*="pilotage?"]');
    expect(lien?.getAttribute('href')).toBe('/pilotage?direction=dr');

    bouton(f, 'Comptes').click();
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/directions/dr/comptes').flush([]);
    await attendre();
    expect(texte(f)).toContain('Aucun compte pour cette direction.');
    const saisir = (sel: string, v: string) => {
      const champ = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>(sel)!;
      champ.value = v;
      champ.dispatchEvent(new Event('input'));
    };
    saisir('#dc-tel', '70 44 55 66');
    saisir('#dc-nom', 'Sanou');
    saisir('#dc-prenoms', 'Aline');
    f.detectChanges();
    bouton(f, 'Créer un compte pour cette direction').click();
    await attendre();
    const n = http.expectOne((r) => r.url === '/api/v1/plateforme/territoire/directions/dr/comptes' && r.method === 'POST');
    expect(n.request.body).toEqual({ telephone: '70 44 55 66', nom: 'Sanou', prenoms: 'Aline' });
    const aline = { utilisateurId: 'u7', nom: 'SANOU', prenoms: 'Aline', telephone: '+22670445566', actif: true, nommeLe: '2026-10-07T10:00:00Z',
      derniereConnexion: null, verrouilleJusqua: null, motDePasseProvisoire: true };
    n.flush({ compte: aline, motDePasseTemporaire: 'Zt8-q2Wd' });
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/directions/dr/comptes').flush([aline]);
    await attendre();
    let t = texte(f);
    expect(t).toContain('SANOU Aline a un compte pour « DR du Centre ».');
    expect(t).toContain('Zt8-q2Wd');

    bouton(f, 'Réinitialiser le mot de passe').click();
    f.detectChanges();
    bouton(f, 'Confirmer la réinitialisation').click();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/plateforme/territoire/directions/dr/comptes/u7/reinitialisation' && r.method === 'POST')
      .flush({ utilisateurId: 'u7', nom: 'SANOU', prenoms: 'Aline', telephone: '+22670445566', motDePasseTemporaire: 'Nw3-r7Kc', sessionsFermees: 0 });
    await attendre();
    http.expectOne('/api/v1/plateforme/territoire/directions/dr/comptes').flush([aline]);
    await attendre();
    t = texte(f);
    expect(t).toContain('Nw3-r7Kc');
  });
});
