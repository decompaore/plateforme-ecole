import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { ONGLETS } from '../admin/admin-nav.component';
import { AnneeVue, ClasseVue, PeriodeVue } from '../admin/modeles-admin';
import { EtablissementAccessible, Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { anPlusUn, ClasseCibleVue, DecisionsClasseVue, effectifsPrevus, libelleSuivant, LignePlan, PlanVue } from './modeles-fin-annee';
import { ConseilFinAnneePage, motifManquant } from './pages/conseil-fin-annee.page';
import { FinAnneePage } from './pages/fin-annee.page';

const ANNEE: AnneeVue = { id: 'a1', libelle: '2025-2026', debut: '2025-10-01', fin: '2026-07-31', etat: 'ACTIVE' };
const SUIVANTE: AnneeVue = { id: 'a2', libelle: '2026-2027', debut: '2026-10-01', fin: '2027-07-31', etat: 'PREPARATION' };
const CLASSES: ClasseVue[] = [
  { id: 'c1', anneeId: 'a1', filiereId: 'f1', filiereCode: 'GEN', profilId: 'pg', code: '6e A', niveau: '6e', effectifMax: 60 },
  { id: 'c2', anneeId: 'a1', filiereId: 'f1', filiereCode: 'GEN', profilId: 'pg', code: '6e B', niveau: '6e', effectifMax: 60 },
];
const PERIODES: PeriodeVue[] = [1, 2, 3].map((o) => ({
  id: 't' + o, anneeId: 'a1', profilId: 'pg', libelle: `Trimestre ${o}`, ordre: o, debut: '2025-10-01', fin: '2026-07-31', verrouillee: true,
}));
const CIBLES: ClasseCibleVue[] = [
  { id: 'n5a', code: '5e A', niveau: '5e', filiereCode: 'GEN', effectifMax: 3, dejaInscrits: 0 },
  { id: 'n5b', code: '5e B', niveau: '5e', filiereCode: 'GEN', effectifMax: 60, dejaInscrits: 0 },
  { id: 'n6a', code: '6e A', niveau: '6e', filiereCode: 'GEN', effectifMax: 60, dejaInscrits: 1 },
];

function ligne(id: string, nom: string, prenoms: string, sexe: 'F' | 'M', autres: Partial<LignePlan>): LignePlan {
  return {
    inscriptionId: id, eleveId: 'e' + id, matricule: null, nom, prenoms, sexe, classeSourceId: 'c1', classeSourceCode: '6e A',
    decision: 'ADMIS', moyenneAnnuelle: 12, redoublant: false, classeCibleId: null, sortie: null, dejaInscrit: null, ...autres,
  };
}

const PLAN: PlanVue = {
  annee: ANNEE,
  anneeSuivante: SUIVANTE,
  classesNonValidees: ['6e B'],
  classesCibles: CIBLES,
  eleves: [
    ligne('i1', 'OUEDRAOGO', 'Awa', 'F', { classeCibleId: 'n5a' }),
    ligne('i2', 'SAWADOGO', 'Ali', 'M', { classeCibleId: 'n5b' }),
    ligne('i3', 'ZONGO', 'Ines', 'F', { decision: 'REDOUBLE', redoublant: true, classeCibleId: 'n6a' }),
    ligne('i4', 'TRAORE', 'Paul', 'M', { decision: 'EXCLU', sortie: 'EXCLU' }),
    ligne('i5', 'KONE', 'Fati', 'F', { decision: 'ORIENTE', sortie: 'ORIENTE' }),
  ],
};

async function session(http: HttpTestingController, role: Role): Promise<void> {
  const etab: EtablissementAccessible = { id: 'etab-1', code: 'CEG', nom: 'CEG Horizon', roles: [role], modulesDesactives: [] };
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

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement | undefined {
  f.detectChanges();
  return [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim().startsWith(libelle));
}

/** Ouvre la page Fin d'année : années, puis les données de l'année et le plan. */
async function ouvrirFinAnnee(http: HttpTestingController): Promise<ComponentFixture<FinAnneePage>> {
  const f = TestBed.createComponent(FinAnneePage);
  f.detectChanges();
  await attendre();
  http.match('/api/v1/annees').forEach((r) => r.flush([ANNEE]));
  await attendre();
  f.detectChanges();
  await attendre();
  http.expectOne('/api/v1/annees/a1/periodes').flush(PERIODES);
  http.expectOne('/api/v1/annees/a1/classes').flush(CLASSES);
  http.expectOne('/api/v1/annees/a1/passage').flush({
    annee: ANNEE, classes: 2, classesValidees: 1,
    detail: [
      { classeId: 'c2', classeCode: '6e B', niveau: '6e', examen: null, decisions: 0, validees: false, repartition: {} },
      { classeId: 'c1', classeCode: '6e A', niveau: '6e', examen: null, decisions: 5, validees: true, repartition: { ADMIS: 2, REDOUBLE: 1, EXCLU: 1, ORIENTE: 1 } },
    ],
  });
  http.expectOne('/api/v1/parametres/passage').flush({ seuilExclusion: 8.5, redoublementsMax: 2 });
  await attendre();
  http.expectOne('/api/v1/profils').flush([{ id: 'pg', code: 'GENERAL', libelle: 'Enseignement général', modele: 'NOTES', decoupage: 'TRIMESTRE', actif: true }]);
  await attendre();
  http.expectOne('/api/v1/profils/pg/poids-periodes').flush([{ ordre: 3, poids: 2 }]);
  await attendre();
  return f;
}

describe('Fin d’année (v0.45)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('outils : année suivante, effectifs prévus, motif obligatoire, onglet', () => {
    expect(libelleSuivant('2025-2026')).toBe('2026-2027');
    expect(libelleSuivant('Promotion A')).toBe('');
    expect(anPlusUn('2025-10-01')).toBe('2026-10-01');
    const e = effectifsPrevus(CIBLES[0], PLAN.eleves, { i1: 'n5a', i2: 'n5a', i3: 'n5a', i4: null });
    expect(e).toEqual({ total: 3, filles: 2, garcons: 1, redoublants: 1, depasse: false });
    expect(effectifsPrevus(CIBLES[2], PLAN.eleves, { i3: 'n6a' }).total).toBe(2); // 1 déjà inscrit + Ines
    expect(motifManquant({ decision: 'REDOUBLE', motif: ' ', d: { proposition: 'ADMIS' } })).toBe(true);
    expect(motifManquant({ decision: 'REDOUBLE', motif: 'Santé', d: { proposition: 'ADMIS' } })).toBe(false);
    expect(motifManquant({ decision: 'ADMIS', motif: '', d: { proposition: 'ADMIS' } })).toBe(false);
    expect(ONGLETS.find((o) => o.lien === '/admin/fin-annee')?.roles).toEqual(['ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT']);
  });

  it('administrateur : crée l’année suivante, ajuste le plan de passage puis réinscrit', async () => {
    await session(http, 'ADMIN_ECOLE');
    const f = await ouvrirFinAnnee(http);
    http.expectOne('/api/v1/annees/a1/plan-passage').flush(
      { code: 'ANNEE_SUIVANTE_ABSENTE', detail: "Créez d'abord l'année qui suit 2025-2026" },
      { status: 409, statusText: 'Conflict' },
    );
    await attendre();
    let t = texte(f);
    expect(t).toContain('Toutes les périodes sont verrouillées');
    expect(t).toContain('1 / 2 classes validées');
    expect(t).toContain('Admis 2 · Redoublent 1 · Exclus 1 · Orientés 1');
    expect(t).not.toContain('Créez d'); // l'absence d'année suivante n'est pas une erreur
    expect((f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#sLibelle')?.value).toBe('2026-2027');

    // Calcul des propositions des classes non validées
    bouton(f, 'Calculer les propositions de 1 classe')!.click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/classes/c2/decisions').flush({ classeId: 'c2', eleves: [] });
    await attendre();
    http.expectOne((r) => r.method === 'GET' && r.url === '/api/v1/annees/a1/passage').flush({ annee: ANNEE, classes: 2, classesValidees: 1, detail: [] });
    await attendre();
    expect(texte(f)).toContain('Propositions calculées pour 1 classe(s)');

    // Année suivante
    (f.nativeElement as HTMLElement).querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await attendre();
    const creation = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/annees/a1/annee-suivante');
    expect(creation.request.body).toEqual({ libelle: '2026-2027', debut: '2026-10-01', fin: '2027-07-31' });
    creation.flush({ annee: SUIVANTE, classesCopiees: 2, matieresCopiees: 8, periodesCopiees: 3, fraisRepris: 1, examensRepris: 0 });
    await attendre();
    http.expectOne('/api/v1/annees').flush([SUIVANTE, ANNEE]);
    await attendre();
    http.expectOne('/api/v1/annees/a1/plan-passage').flush(PLAN);
    await attendre();
    t = texte(f);
    expect(t).toContain('Année 2026-2027 créée : 2 classe(s), 3 période(s), 1 frais');
    expect(t).toContain('Décisions non validées : 6e B');
    expect(t).toContain('5e A');
    expect(t).toContain('5e B');
    expect(t).toContain('1 déjà inscrit(s)');
    expect(t).toContain('Sans classe (2)'); // l'exclu et l'orientée
    expect(t).toContain('Réinscrire 3 élève(s)');

    // Ali passe de 5e B en 5e A : la 5e A (3 places) n'est pas dépassée ; Fati (orientée) est placée en 5e B
    const el = f.nativeElement as HTMLElement;
    const selectAli = el.querySelector<HTMLSelectElement>('select[aria-label="Classe de Ali SAWADOGO"]')!;
    selectAli.value = 'n5a';
    selectAli.dispatchEvent(new Event('change'));
    f.detectChanges();
    expect(el.querySelector('select[aria-label="Placer Paul TRAORE"]')).toBeNull(); // un exclu ne se place pas
    const selectFati = el.querySelector<HTMLSelectElement>('select[aria-label="Placer Fati KONE"]')!;
    selectFati.value = 'n5b';
    selectFati.dispatchEvent(new Event('change'));
    t = texte(f);
    expect(t).toContain('Réinscrire 4 élève(s)');
    expect(t).not.toContain('5e B 1'); // compteurs à jour (la 5e B ne compte plus Ali)

    bouton(f, 'Réinscrire 4 élève(s)')!.click();
    expect(texte(f)).toContain('Réinscrire 4 élève(s) en 2026-2027 ?');
    bouton(f, 'Confirmer les réinscriptions')!.click();
    await attendre();
    const app = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/annees/a1/plan-passage');
    expect(app.request.body).toEqual({
      affectations: [
        { inscriptionId: 'i1', classeCibleId: 'n5a' },
        { inscriptionId: 'i2', classeCibleId: 'n5a' },
        { inscriptionId: 'i3', classeCibleId: 'n6a' },
        { inscriptionId: 'i5', classeCibleId: 'n5b' },
      ],
      conserverStatutBourse: false,
    });
    app.flush({ reinscrits: 4, redoublants: 1, nonReinscrits: [] });
    await attendre();
    http.expectOne('/api/v1/annees/a1/plan-passage').flush({
      ...PLAN,
      classesNonValidees: [],
      eleves: PLAN.eleves.map((l) => (l.sortie === 'EXCLU' ? l : { ...l, classeCibleId: null, dejaInscrit: '5e A' })),
    });
    await attendre();
    t = texte(f);
    expect(t).toContain('4 élève(s) réinscrit(s) en 2026-2027, dont 1 redoublant(s)');
    expect(t).toContain('4 élève(s) déjà inscrit(s) en 2026-2027');
    expect(bouton(f, 'Réinscrire')).toBeUndefined();
  });

  it('listes de l’année suivante en Excel ; le censeur ne réinscrit pas', async () => {
    await session(http, 'CENSEUR');
    const f = await ouvrirFinAnnee(http);
    http.expectOne('/api/v1/annees/a1/plan-passage').flush(PLAN);
    await attendre();
    expect(bouton(f, 'Réinscrire')).toBeUndefined();
    expect((f.nativeElement as HTMLElement).querySelector('select[aria-label="Classe de Ali SAWADOGO"]')).toBeNull();
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    bouton(f, 'Excel')!.click();
    await attendre();
    const req = http.expectOne((r) => r.url === '/api/v1/annees/a1/plan-passage/listes');
    expect(req.request.params.get('format')).toBe('xlsx');
    req.flush(new Blob(['xlsx']));
    await attendre();
    expect(URL.createObjectURL).toHaveBeenCalled();
    vi.restoreAllMocks();
  });

  it('conseil de fin d’année : motif obligatoire, enregistrement puis validation', async () => {
    await session(http, 'CENSEUR');
    const vue: DecisionsClasseVue = {
      classeId: 'c1', classeCode: '6e A', examen: null, validees: false, repartition: { ADMIS: 1, EXCLU: 1 },
      eleves: [
        { inscriptionId: 'i1', matricule: null, nom: 'OUEDRAOGO', prenoms: 'Awa', redoublant: false, moyenneAnnuelle: 12.4, rang: 1, tauxMaitrise: null, redoublements: 0, resultatExamen: null, proposition: 'ADMIS', decision: 'ADMIS', motif: null, validee: false },
        { inscriptionId: 'i4', matricule: null, nom: 'TRAORE', prenoms: 'Paul', redoublant: false, moyenneAnnuelle: 6, rang: 2, tauxMaitrise: null, redoublements: 1, resultatExamen: null, proposition: 'EXCLU', decision: 'EXCLU', motif: null, validee: false },
      ],
    };
    const f = TestBed.createComponent(ConseilFinAnneePage);
    f.componentRef.setInput('classeId', 'c1');
    f.detectChanges();
    await attendre();
    http.match('/api/v1/annees').forEach((r) => r.flush([ANNEE]));
    http.expectOne('/api/v1/classes/c1/decisions').flush(vue);
    await attendre();
    f.detectChanges();
    await f.whenStable();
    let t = texte(f);
    expect(t).toContain('Conseil de fin d\'année · 6e A');
    expect(t).toContain('12,40');
    expect(t).toContain('1 redoublement(s)');

    // Paul : le conseil le garde en redoublement ; sans motif, on ne peut pas enregistrer
    const el = f.nativeElement as HTMLElement;
    const select = el.querySelector<HTMLSelectElement>('select[aria-label="Décision pour TRAORE"]')!;
    select.value = 'REDOUBLE';
    select.dispatchEvent(new Event('change'));
    f.detectChanges();
    await f.whenStable();
    expect(texte(f)).toContain('1 décision(s) modifiée(s) sans motif');
    expect(bouton(f, 'Enregistrer les décisions')!.disabled).toBe(true);
    const motif = el.querySelector<HTMLInputElement>('input[aria-label="Motif pour TRAORE"]')!;
    motif.value = 'Dérogation : santé';
    motif.dispatchEvent(new Event('input'));
    f.detectChanges();
    expect(bouton(f, 'Enregistrer les décisions')!.disabled).toBe(false);
    expect(bouton(f, 'Valider les décisions')!.disabled).toBe(true); // modification à enregistrer d'abord
    bouton(f, 'Enregistrer les décisions')!.click();
    await attendre();
    const put = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/classes/c1/decisions');
    expect(put.request.body).toEqual({ decisions: [{ inscriptionId: 'i4', decision: 'REDOUBLE', motif: 'Dérogation : santé' }] });
    const apres = { ...vue, repartition: { ADMIS: 1, REDOUBLE: 1 }, eleves: [vue.eleves[0], { ...vue.eleves[1], decision: 'REDOUBLE' as const, motif: 'Dérogation : santé' }] };
    put.flush(apres);
    await attendre();
    t = texte(f);
    expect(t).toContain('1 élève(s) mis à jour');

    bouton(f, 'Valider les décisions')!.click();
    expect(texte(f)).toContain('chaque famille reçoit un SMS');
    bouton(f, 'Confirmer la validation')!.click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/classes/c1/decisions/validation').flush({
      ...apres, validees: true, eleves: apres.eleves.map((x) => ({ ...x, validee: true })),
    });
    await attendre();
    t = texte(f);
    expect(t).toContain('décisions validées');
    expect(t).toContain('Décisions validées : les familles sont prévenues par SMS');
    expect(el.querySelector('select')).toBeNull();
  });
});
