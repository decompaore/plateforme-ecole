import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ONGLETS } from '../admin/admin-nav.component';
import { AnneeVue, ClasseVue, PeriodeVue } from '../admin/modeles-admin';
import { EtablissementAccessible, Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { AvisVue, GenerationVue, rang, resultat, VisaVue, visasApresGeneration } from './modeles-bulletins';
import { AppreciationsPage } from './pages/appreciations.page';
import { BulletinsPage, periodeParDefaut } from './pages/bulletins.page';
import { ListesService } from '../hors-ligne/listes.service';

// Année passée : le 2e trimestre est toujours la dernière période commencée, quel que soit le jour du test
const ANNEE: AnneeVue = { id: 'a1', libelle: '2025-2026', debut: '2025-10-01', fin: '2026-07-31', etat: 'ACTIVE' };
const CLASSES: ClasseVue[] = [
  { id: 'c2', anneeId: 'a1', filiereId: 'f1', filiereCode: 'A4', profilId: 'pg', code: '1re A4', niveau: '1re', effectifMax: 60 },
  { id: 'c1', anneeId: 'a1', filiereId: 'f1', filiereCode: 'A4', profilId: 'pg', code: '2nde A4', niveau: '2nde', effectifMax: 60 },
];
const PERIODES: PeriodeVue[] = [
  { id: 't1', anneeId: 'a1', profilId: 'pg', libelle: '1er trimestre', ordre: 1, debut: '2025-10-01', fin: '2025-12-20', verrouillee: false },
  { id: 't2', anneeId: 'a1', profilId: 'pg', libelle: '2e trimestre', ordre: 2, debut: '2026-01-05', fin: '2026-03-31', verrouillee: false },
  { id: 'x1', anneeId: 'a1', profilId: 'autre', libelle: 'Module 1', ordre: 1, debut: '2025-10-01', fin: '2025-12-01', verrouillee: false },
];
const AVIS: AvisVue[] = [
  { inscriptionId: 'i1', nom: 'OUEDRAOGO', prenoms: 'Awa', moyenne: 15.5, rang: 1, tauxMaitrise: null, distinctionProposee: 'FELICITATIONS', distinction: null, appreciation: null },
  { inscriptionId: 'i2', nom: 'SANOU', prenoms: 'Paul', moyenne: 7.25, rang: 2, tauxMaitrise: null, distinctionProposee: 'AVERTISSEMENT_TRAVAIL', distinction: null, appreciation: null },
];

function generation(statut: 'GENEREE' | 'PUBLIEE'): GenerationVue {
  return {
    id: 'g1', classeId: 'c2', classeCode: '1re A4', periodeId: 't1', periodeLibelle: '1er trimestre', statut, effectif: 2,
    moyenneClasse: 11.38, tauxReussite: 50, genereLe: '2026-12-21T10:00:00Z', publieLe: statut === 'PUBLIEE' ? '2026-12-22T09:00:00Z' : null,
    bulletins: [
      { id: 'b1', inscriptionId: 'i1', matricule: '2026-00001', nom: 'OUEDRAOGO', prenoms: 'Awa', moyenne: 15.5, rang: 1, admis: true, tauxMaitrise: null, distinction: 'FELICITATIONS', codeVerification: 'ABCD-1234' },
      { id: 'b2', inscriptionId: 'i2', matricule: '2026-00002', nom: 'SANOU', prenoms: 'Paul', moyenne: 7.25, rang: 2, admis: false, tauxMaitrise: null, distinction: 'AUCUNE', codeVerification: 'EFGH-5678' },
    ],
  };
}

const VISAS: VisaVue[] = [
  { matiereId: 'm2', matiereCode: 'ANG', matiereLibelle: 'Anglais', engagementId: 'e2', enseignant: 'KONE Ali', vise: false, viseLe: null, signature: false, moi: false },
  { matiereId: 'm1', matiereCode: 'MATH', matiereLibelle: 'Mathématiques', engagementId: 'e1', enseignant: 'SANOU Paul', vise: true, viseLe: '2026-12-21T12:00:00Z', signature: true, moi: false },
];

async function session(http: HttpTestingController, role: Role): Promise<void> {
  const etab: EtablissementAccessible = { id: 'etab-1', code: 'LPH', nom: 'Lycée Horizon', roles: [role], modulesDesactives: [] };
  const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'ZONGO', prenoms: 'Ines' });
  await connexion;
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement {
  f.detectChanges();
  return [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim().startsWith(libelle))!;
}

/** Ouvre la page : année, classes et périodes, puis la classe et la période par défaut. */
async function ouvrir(http: HttpTestingController, aujourdhui: boolean): Promise<ComponentFixture<BulletinsPage>> {
  const f = TestBed.createComponent(BulletinsPage);
  f.detectChanges();
  await attendre();
  http.match('/api/v1/annees').forEach((r) => r.flush([ANNEE])); // la page et les onglets
  await attendre();
  f.detectChanges();
  await attendre();
  http.expectOne('/api/v1/annees/a1/classes').flush(CLASSES);
  http.expectOne('/api/v1/annees/a1/periodes').flush(PERIODES);
  await attendre();
  void aujourdhui;
  return f;
}

describe('Bulletins (v0.43, visas v0.44)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('outils : période par défaut, résultat à la française, rang', () => {
    const p = PERIODES.filter((x) => x.profilId === 'pg');
    expect(periodeParDefaut(p, '2026-02-10')?.id).toBe('t2');
    expect(periodeParDefaut(p, '2025-12-25')?.id).toBe('t1'); // vacances : la dernière commencée
    expect(periodeParDefaut(p, '2025-09-01')?.id).toBe('t1'); // avant la rentrée : la première
    expect(periodeParDefaut([])).toBeNull();
    expect(resultat(12.5, null)).toBe('12,50');
    expect(resultat(null, 66.67)).toBe('66,7 %');
    expect(resultat(null, null)).toBe('—');
    expect(rang(1)).toBe('1er');
    expect(rang(3)).toBe('3e');
    expect(visasApresGeneration(VISAS, '2026-12-21T10:00:00Z').map((v) => v.matiereCode)).toEqual(['MATH']);
    expect(visasApresGeneration(VISAS, '2026-12-22T10:00:00Z')).toEqual([]);
    expect(visasApresGeneration(VISAS, null)).toEqual([]);
    // v0.47.1 : le secrétariat ne voit plus bulletins ni évaluations
    expect(ONGLETS.find((o) => o.lien === '/admin/bulletins')?.roles).toEqual(['ADMIN_ECOLE', 'CENSEUR']);
    expect(ONGLETS.find((o) => o.lien === '/admin/evaluations')?.roles).toEqual(['ADMIN_ECOLE', 'CENSEUR']);
  });

  it('censeur : conseil de classe, verrouillage, génération puis publication', async () => {
    await session(http, 'CENSEUR');
    const f = await ouvrir(http, true);
    // Classes triées par niveau : 1re A4 d'abord ; la période proposée est la dernière commencée
    http.expectOne((r) => r.url.endsWith('/classes/c2/periodes/t2/conseil')).flush(AVIS);
    http.expectOne((r) => r.url.endsWith('/classes/c2/periodes/t2/bulletins')).flush({ detail: 'Aucun bulletin' }, { status: 404, statusText: 'Not Found' });
    http.expectOne((r) => r.url.endsWith('/classes/c2/periodes/t2/visas')).flush([]);
    await attendre();
    let t = texte(f);
    expect(t).toContain('2e trimestre');
    expect(t).toContain('saisies ouvertes');
    expect(t).not.toContain('Module 1'); // période d'un autre profil
    expect(t).toContain('OUEDRAOGO');
    expect(t).toContain('15,50');
    expect(t).toContain('Félicitations');
    expect(t).toContain("n'ont pas encore été générés");
    expect(t).toContain("Verrouillez d'abord la période");

    // Retour au 1er trimestre
    const select = (f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#periode')!;
    select.value = 't1';
    select.dispatchEvent(new Event('change'));
    await attendre();
    http.expectOne((r) => r.url.endsWith('/classes/c2/periodes/t1/conseil')).flush(AVIS);
    http.expectOne((r) => r.url.endsWith('/classes/c2/periodes/t1/bulletins')).flush(null, { status: 404, statusText: 'Not Found' });
    http.expectOne((r) => r.url.endsWith('/classes/c2/periodes/t1/visas')).flush(VISAS);
    await attendre();
    // Visas des enseignants (v0.44)
    expect(texte(f)).toContain('Visas des enseignants');
    expect(texte(f)).toContain('1 / 2 matières visées');
    expect(texte(f)).toContain('non visée');

    // Avis du conseil : une distinction retenue et une appréciation générale
    f.detectChanges();
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    const distinction = el.querySelector<HTMLSelectElement>('select[aria-label="Distinction de SANOU"]')!;
    distinction.value = 'ENCOURAGEMENTS';
    distinction.dispatchEvent(new Event('change'));
    const appreciation = el.querySelector<HTMLInputElement>('input[aria-label="Appréciation de SANOU"]')!;
    appreciation.value = 'Des efforts à poursuivre';
    appreciation.dispatchEvent(new Event('input'));
    bouton(f, 'Enregistrer le conseil').click();
    await attendre();
    const conseil = http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/classes/c2/periodes/t1/conseil'));
    expect(conseil.request.body).toEqual({
      avis: [
        { inscriptionId: 'i1', distinction: null, appreciation: null },
        { inscriptionId: 'i2', distinction: 'ENCOURAGEMENTS', appreciation: 'Des efforts à poursuivre' },
      ],
    });
    conseil.flush([AVIS[0], { ...AVIS[1], distinction: 'ENCOURAGEMENTS', appreciation: 'Des efforts à poursuivre' }]);
    await attendre();
    expect(texte(f)).toContain('Avis du conseil enregistrés');

    // Verrouillage en deux temps
    bouton(f, 'Verrouiller la période').click();
    expect(texte(f)).toContain('ne pourront plus modifier leurs notes');
    bouton(f, 'Confirmer').click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/periodes/t1/verrouillage')).flush({ ...PERIODES[0], verrouillee: true });
    await attendre();
    t = texte(f);
    expect(t).toContain('verrouillée : vous pouvez générer les bulletins');
    expect(t).toContain('Déverrouiller la période');

    bouton(f, 'Générer les bulletins').click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/classes/c2/periodes/t1/bulletins')).flush({ ...generation('GENEREE'), matieresNonVisees: ['Anglais'] });
    await attendre();
    t = texte(f);
    expect(t).toContain('Matière(s) non visée(s) : Anglais');
    expect(t).toContain('1 visa(s) posé(s) après la génération (Mathématiques) : régénérez');
    expect(t).toContain('2 bulletin(s) générés');
    expect(t).toContain('ABCD-1234');
    expect(t).toContain('générés, non publiés');
    expect(t).toContain('Moyenne de la classe : 11,38');

    bouton(f, 'Publier aux familles').click();
    expect(texte(f)).toContain('Les familles reçoivent un SMS');
    bouton(f, 'Confirmer la publication').click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/classes/c2/periodes/t1/bulletins/publication')).flush(generation('PUBLIEE'));
    await attendre();
    t = texte(f);
    expect(t).toContain('Bulletins publiés');
    expect(bouton(f, 'Publier aux familles')).toBeUndefined();
    expect(bouton(f, 'Enregistrer le conseil')).toBeUndefined(); // plus de modification après publication
    expect(bouton(f, 'Déverrouiller la période')).toBeUndefined();
  });

  it('enseignant : appréciations du bulletin, seules les lignes modifiées partent', async () => {
    // Affectations, classe et périodes : servies par le service des listes (mises en cache sur le téléphone)
    const listes = TestBed.inject(ListesService);
    vi.spyOn(listes, 'affectations').mockResolvedValue({
      anneeId: 'a1',
      affectations: [{ classeId: 'c1', classeCode: '2nde A4', matiereId: 'm1', matiereCode: 'MATH', matiereLibelle: 'Mathématiques' }],
    } as never);
    vi.spyOn(listes, 'eleves').mockResolvedValue({ profilId: 'pg' } as never);
    vi.spyOn(listes, 'periodes').mockResolvedValue({ anneeId: 'a1', periodes: PERIODES, prepareLe: '' } as never);
    const f = TestBed.createComponent(AppreciationsPage);
    f.componentRef.setInput('classeId', 'c1');
    f.componentRef.setInput('matiereId', 'm1');
    f.detectChanges();
    await attendre();
    const req = http.expectOne((r) => r.url.endsWith('/classes/c1/periodes/t2/appreciations'));
    expect(req.request.params.get('matiereId')).toBe('m1');
    req.flush([
      { inscriptionId: 'i1', nom: 'OUEDRAOGO', prenoms: 'Awa', texte: 'Excellent travail' },
      { inscriptionId: 'i2', nom: 'SANOU', prenoms: 'Paul', texte: null },
    ]);
    await attendre();
    // Visa de la matière (v0.44) : pas encore visée, pas de signature déposée
    const maVisa: VisaVue = { ...VISAS[1], vise: false, viseLe: null, signature: false, moi: true };
    http.expectOne((r) => r.url.endsWith('/classes/c1/periodes/t2/visas')).flush([maVisa]);
    await attendre();
    http.expectOne('/api/v1/moi/signature/etat').flush({ presente: false, modifieeLe: null });
    await attendre();
    f.detectChanges();
    await f.whenStable();
    const el = f.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Appréciations · 2nde A4 · Mathématiques');
    expect(texte(f)).toContain('Aucune signature déposée');
    bouton(f, 'Viser Mathématiques en 2nde A4').click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/classes/c1/periodes/t2/matieres/m1/visa'))
      .flush([{ ...maVisa, vise: true, viseLe: '2026-03-31T08:00:00Z' }]);
    await attendre();
    expect(texte(f)).toContain('Matière visée.');
    expect(bouton(f, 'Retirer mon visa')).toBeDefined();

    const champ = el.querySelector<HTMLInputElement>('#app1')!;
    champ.value = 'Doit travailler davantage';
    champ.dispatchEvent(new Event('input'));
    bouton(f, 'Enregistrer les appréciations').click();
    await attendre();
    const put = http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/classes/c1/periodes/t2/appreciations'));
    expect(put.request.body).toEqual({ matiereId: 'm1', appreciations: [{ inscriptionId: 'i2', texte: 'Doit travailler davantage' }] });
    put.flush([
      { inscriptionId: 'i1', nom: 'OUEDRAOGO', prenoms: 'Awa', texte: 'Excellent travail' },
      { inscriptionId: 'i2', nom: 'SANOU', prenoms: 'Paul', texte: 'Doit travailler davantage' },
    ]);
    await attendre();
    // L'appréciation modifiée annule le visa côté serveur
    http.expectOne((r) => r.method === 'GET' && r.url.endsWith('/classes/c1/periodes/t2/visas')).flush([maVisa]);
    await attendre();
    expect(texte(f)).toContain('1 appréciation(s) enregistrée(s)');
    expect(texte(f)).toContain('Votre visa est retiré');
    vi.restoreAllMocks();
  });
});
