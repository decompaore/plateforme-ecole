import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AnneeCourante } from '../admin/annee-courante.service';
import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { BilanHeuresVue, Cumul, LigneBilan } from './modeles-progression';
import { BilanHeuresPage } from './pages/bilan-heures.page';

const ANNEE = { id: 'a1', libelle: '2026-2027', debut: '2026-10-01', fin: '2027-07-31', etat: 'ACTIVE' };

async function session(http: HttpTestingController, role: Role, annee = false): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('61000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'KABORE', prenoms: 'Awa' });
  await connexion;
  if (annee) {
    const a = TestBed.inject(AnneeCourante).charger();
    http.expectOne('/api/v1/annees').flush([ANNEE]);
    await a;
  }
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function cumul(autres: Partial<Cumul> = {}): Cumul {
  return { volumeAnnuel: 120, duADate: 12, realise: 2, reste: 118, tauxRealisation: 1.7, tauxADate: 16.7, ecart: -10, ...autres };
}

function ligne(autres: Partial<LigneBilan> = {}): LigneBilan {
  return {
    classeId: 'c1', classeCode: '2nde F3 A', niveau: '2nde', filiereId: 'f1', filiereCode: 'F3', matiereId: 'm1',
    matiereCode: 'MATH', matiereLibelle: 'Mathématiques', type: 'GENERALE', domaine: 'GENERAL', engagementId: 'e1',
    enseignant: 'KABORE Awa', statutEnseignant: 'TITULAIRE', volumeHebdo: 4, sourceVolume: 'REFERENTIEL', cumul: cumul(),
    seances: 1, derniereSeance: '2026-11-01', etat: 'EN_RETARD', intervenants: [{ engagementId: 'e1', enseignant: 'KABORE Awa', heures: 2, seances: 1 }],
    ...autres,
  };
}

function bilan(autres: Partial<BilanHeuresVue> = {}): BilanHeuresVue {
  const lignes = [
    ligne(),
    ligne({ matiereId: 'm2', matiereCode: 'ELEC', matiereLibelle: 'Électricité', type: 'TECHNIQUE', domaine: 'TECHNIQUE',
      engagementId: 'e2', enseignant: 'SANOU Paul', statutEnseignant: 'VACATAIRE', sourceVolume: 'ESTIME',
      cumul: cumul({ volumeAnnuel: 176, duADate: 17.5, realise: 3, reste: 173 }), etat: 'EN_RETARD',
      intervenants: [{ engagementId: 'e2', enseignant: 'SANOU Paul', heures: 3, seances: 1 }] }),
    ligne({ matiereId: 'm3', matiereCode: 'DESS', matiereLibelle: 'Dessin technique', engagementId: null, enseignant: null,
      statutEnseignant: null, sourceVolume: 'AUCUN', cumul: cumul({ volumeAnnuel: null, duADate: null, realise: 0, reste: null,
        tauxRealisation: null, tauxADate: null, ecart: null }), seances: 0, derniereSeance: null, etat: 'SANS_VOLUME', intervenants: [] }),
  ];
  return {
    anneeId: 'a1', annee: '2026-2027', jour: '2026-11-03', avancementAnnee: 9, lignes,
    enseignants: [
      { engagementId: 'e1', enseignant: 'KABORE Awa', statutEnseignant: 'TITULAIRE', classes: 1, matieres: 1, sansVolume: 0, enRetard: 1, cumul: cumul() },
      { engagementId: 'e2', enseignant: 'SANOU Paul', statutEnseignant: 'VACATAIRE', classes: 1, matieres: 1, sansVolume: 0, enRetard: 1,
        cumul: cumul({ volumeAnnuel: 176, duADate: 17.5, realise: 3, reste: 173 }) },
    ],
    matieres: [], classes: [],
    total: cumul({ volumeAnnuel: 296, duADate: 29.5, realise: 5, reste: 291, tauxADate: 16.9 }),
    sansVolume: 1, sansEnseignant: 1, enRetard: 2,
    ...autres,
  };
}

describe('Bilan des heures (v0.50)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('censeur : cumuls par enseignant, puis détail d’un enseignant', async () => {
    await session(http, 'CENSEUR', true);
    const f = TestBed.createComponent(BilanHeuresPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/annees/a1/bilan-heures').flush(bilan());
    await attendre();
    let t = texte(f);
    expect(t).toContain('Bilan des heures');
    expect(t).toContain('9 % des jours de cours écoulés');
    expect(t).toContain('296 h');
    expect(t).toContain('1 matière(s) sans volume annuel');
    expect(t).toContain('1 matière(s) au volume estimé');
    expect(t).toContain('SANOU Paul');
    expect(t).toContain('vacataire');

    const lien = [...(f.nativeElement as HTMLElement).querySelectorAll('button.lien')].find((b) => b.textContent?.includes('SANOU'));
    (lien as HTMLButtonElement).click();
    t = texte(f);
    expect(t).toContain('Électricité');
    expect(t).not.toContain('Mathématiques');
    expect(t).toContain('En retard');
  });

  it('enseignant : « Mes heures » sans regroupement, export Excel', async () => {
    await session(http, 'ENSEIGNANT');
    const f = TestBed.createComponent(BilanHeuresPage);
    f.componentRef.setInput('mien', true);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/espace-enseignant/bilan-heures').flush(bilan({ lignes: [ligne()], sansVolume: 0, enRetard: 1 }));
    await attendre();
    const t = texte(f);
    expect(t).toContain('Mes heures');
    expect(t).toContain('2nde F3 A · Mathématiques');
    expect(t).not.toContain('Par enseignant');
    expect(t).toContain('En retard');

    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const excel = [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Excel');
    (excel as HTMLButtonElement).click();
    await attendre();
    const req = http.expectOne((r) => r.url === '/api/v1/espace-enseignant/bilan-heures/export');
    expect(req.request.params.get('format')).toBe('xlsx');
    req.flush(new Blob(['xlsx']));
    await attendre();
    expect(URL.createObjectURL).toHaveBeenCalled();
  });
});
