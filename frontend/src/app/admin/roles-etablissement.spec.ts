import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { AnneeCourante } from './annee-courante.service';
import { PieceVue } from './modeles-admin';
import { ClassePage } from './pages/classe.page';
import { ElevesPage } from './pages/eleves.page';
import { PersonnelPage } from './pages/personnel.page';

const ANNEE = { id: 'a1', libelle: '2026-2027', debut: '2026-10-01', fin: '2027-07-31', etat: 'ACTIVE' };
const PIECES: PieceVue[] = [
  { id: 'p1', libelle: "Extrait d'acte de naissance légalisé", modes: ['AFFECTATION_ETAT', 'TRANSFERT'], obligatoire: true, ordre: 1 },
  { id: 'p2', libelle: "Décision d'affectation de l'État", modes: ['AFFECTATION_ETAT'], obligatoire: true, ordre: 2 },
  { id: 'p3', libelle: 'Certificat de non-redevance', modes: ['TRANSFERT'], obligatoire: true, ordre: 3 },
  { id: 'p4', libelle: "Photos d'identité", modes: ['AFFECTATION_ETAT', 'TRANSFERT'], obligatoire: true, ordre: 4 },
];

async function session(http: HttpTestingController, role: Role): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: etab }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'KABORE', prenoms: 'Mathieu' });
  await connexion;
  const annees = TestBed.inject(AnneeCourante).charger();
  http.expectOne('/api/v1/annees').flush([ANNEE]);
  await annees;
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement | undefined {
  f.detectChanges();
  return [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim().startsWith(libelle));
}

function caseDe<T>(f: ComponentFixture<T>, libelle: string): HTMLInputElement {
  f.detectChanges();
  const l = [...(f.nativeElement as HTMLElement).querySelectorAll('label.case')].find((x) => x.textContent?.includes(libelle))!;
  return l.querySelector('input')!;
}

async function ouvrirEleves(http: HttpTestingController): Promise<ComponentFixture<ElevesPage>> {
  const f = TestBed.createComponent(ElevesPage);
  f.detectChanges();
  http.expectOne((r) => r.url === '/api/v1/eleves' && r.method === 'GET').flush({
    elements: [{ id: 'el1', matricule: '2026-00001', nom: 'ZONGO', prenoms: 'Rasmata', sexe: 'F', dateNaissance: '2013-03-14' }],
    page: 0, taille: 20, total: 1, nombrePages: 1,
  });
  http.expectOne('/api/v1/annees/a1/classes').flush([{ id: 'c1', code: '6e A', niveau: '6e' }]);
  await attendre();
  return f;
}

describe('Rôles dans l’établissement (v0.47)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => {
    http.match((r) => r.method === 'GET' && r.url.endsWith('/photos')).forEach((r) => r.flush([]));
    http.match((r) => r.method === 'GET' && r.url.endsWith('/photo')).forEach((r) => r.flush(null, { status: 404, statusText: 'Not Found' }));
    http.verify();
  });

  it('intendant : saisit, complète le dossier d’inscription, mais ne retire pas une pièce enregistrée', async () => {
    await session(http, 'INTENDANT');
    const f = await ouvrirEleves(http);
    expect(texte(f)).toContain('Nouvel élève');
    expect(texte(f)).toContain('Importer depuis Excel');
    expect(texte(f)).not.toContain("Pièces demandées à l'inscription"); // réglage : chef d'établissement

    (f.nativeElement as HTMLElement).querySelector<HTMLTableRowElement>('tr.ligne')!.click();
    await attendre();
    http.expectOne('/api/v1/eleves/el1').flush({
      eleve: { id: 'el1', matricule: '2026-00001', nom: 'ZONGO', prenoms: 'Rasmata', dateNaissance: '2013-03-14' },
      responsables: [{ responsableId: 'r1', nom: 'ZONGO', prenoms: 'Issa', telephone: '+22670123456', lien: 'PERE', contactPrioritaire: true, espaceParentOuvert: false }],
      inscriptions: [{ id: 'i1', anneeId: 'a1', anneeLibelle: '2026-2027', classeId: 'c1', classeCode: '6e A', statut: 'ACTIVE' }],
    });
    await attendre();
    expect(bouton(f, 'Ajouter un parent ou tuteur')).toBeUndefined(); // modification : chef d'établissement
    expect(bouton(f, 'Retirer')).toBeUndefined();

    bouton(f, 'Pièces du dossier')!.click();
    await attendre();
    http.expectOne('/api/v1/pieces-dossier').flush(PIECES);
    http.expectOne('/api/v1/inscriptions/i1/dossier').flush({
      inscriptionId: 'i1', modeAdmission: 'AFFECTATION_ETAT', modeLibelle: "Affectation de l'État", complet: false, manquantes: 2,
      pieces: [
        { pieceId: 'p1', libelle: PIECES[0].libelle, obligatoire: true, recue: true, recueLe: '2026-10-02' },
        { pieceId: 'p2', libelle: PIECES[1].libelle, obligatoire: true, recue: false, recueLe: null },
        { pieceId: 'p4', libelle: PIECES[3].libelle, obligatoire: true, recue: false, recueLe: null },
      ],
    });
    await attendre();
    let t = texte(f);
    expect(t).toContain('2 pièce(s) manquante(s)');
    expect(t).not.toContain('non-redevance'); // pas demandé pour une affectation de l'État
    expect(caseDe(f, 'acte de naissance').disabled).toBe(true);
    expect(((f.nativeElement as HTMLElement).querySelector('#dMode') as HTMLSelectElement).disabled).toBe(true);
    caseDe(f, 'Décision').click();
    caseDe(f, 'Photos').click();
    t = texte(f);
    expect(t).toContain('dossier complet');

    bouton(f, 'Enregistrer le dossier')!.click();
    await attendre();
    const maj = http.expectOne('/api/v1/inscriptions/i1/dossier');
    expect(maj.request.method).toBe('PUT');
    expect(maj.request.body.modeAdmission).toBe('AFFECTATION_ETAT');
    expect([...maj.request.body.piecesRecues].sort()).toEqual(['p1', 'p2', 'p4']);
    maj.flush({
      inscriptionId: 'i1', modeAdmission: 'AFFECTATION_ETAT', modeLibelle: "Affectation de l'État", complet: true, manquantes: 0,
      pieces: [
        { pieceId: 'p1', libelle: PIECES[0].libelle, obligatoire: true, recue: true, recueLe: '2026-10-02' },
        { pieceId: 'p2', libelle: PIECES[1].libelle, obligatoire: true, recue: true, recueLe: '2026-10-14' },
        { pieceId: 'p4', libelle: PIECES[3].libelle, obligatoire: true, recue: true, recueLe: '2026-10-14' },
      ],
    });
    await attendre();
    expect(texte(f)).toContain('Dossier d’inscription complet.');

    // Dossiers incomplets de l'année
    bouton(f, 'Afficher')!.click();
    await attendre();
    const liste = http.expectOne((r) => r.url === '/api/v1/annees/a1/dossiers-inscription');
    expect(liste.request.params.get('incomplets')).toBe('true');
    liste.flush([{ inscriptionId: 'i9', eleveId: 'el9', matricule: null, nom: 'SAWADOGO', prenoms: 'Ali', classeCode: '6e A', modeAdmission: 'TRANSFERT', manquantes: ['Certificat de non-redevance'] }]);
    await attendre();
    expect(texte(f)).toContain('Certificat de non-redevance');
  });

  it('secrétariat : consulte sans saisir', async () => {
    await session(http, 'SECRETARIAT');
    const f = await ouvrirEleves(http);
    expect(texte(f)).not.toContain('Nouvel élève');
    expect(texte(f)).not.toContain('Importer depuis Excel');
    expect(texte(f)).toContain("Dossiers d'inscription incomplets");
  });

  it('chef d’établissement : règle la liste des pièces demandées', async () => {
    await session(http, 'ADMIN_ECOLE');
    const f = await ouvrirEleves(http);
    bouton(f, 'Régler la liste')!.click();
    await attendre();
    http.expectOne('/api/v1/pieces-dossier').flush(PIECES);
    await attendre();
    // Le certificat de non-redevance n'est plus demandé ; le CEP/BEPC est ajouté pour les compléments d'effectif
    const lignes = (f.nativeElement as HTMLElement).querySelectorAll('tbody tr');
    expect(lignes.length).toBeGreaterThanOrEqual(4);
    const retirer = [...(f.nativeElement as HTMLElement).querySelectorAll('tr')].find((tr) =>
      (tr.querySelector('input') as HTMLInputElement | null)?.value === 'Certificat de non-redevance',
    )!;
    retirer.querySelector<HTMLButtonElement>('button')!.click();
    bouton(f, 'Ajouter une pièce')!.click();
    f.detectChanges();
    const nouvelle = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[aria-label="Pièce 4"]')!;
    nouvelle.value = 'Copie légalisée du BEPC';
    nouvelle.dispatchEvent(new Event('input'));
    f.detectChanges();
    bouton(f, 'Enregistrer la liste')!.click();
    await attendre();
    const req = http.expectOne((r) => r.url === '/api/v1/pieces-dossier' && r.method === 'PUT');
    expect(req.request.body.map((p: { libelle: string }) => p.libelle)).toEqual([
      PIECES[0].libelle,
      PIECES[1].libelle,
      PIECES[3].libelle,
      'Copie légalisée du BEPC',
    ]);
    expect(req.request.body[3].id).toBeNull();
    expect(req.request.body[3].modes).not.toContain('REINSCRIPTION');
    req.flush([...PIECES.filter((p) => p.id !== 'p3'), { id: 'p5', libelle: 'Copie légalisée du BEPC', modes: ['COMPLEMENT_EFFECTIF'], obligatoire: true, ordre: 4 }]);
    await attendre();
    expect(texte(f)).toContain('Liste enregistrée : 4 pièce(s) demandée(s).');
  });

  it('censeur : répartit les élèves entre les classes du même niveau', async () => {
    await session(http, 'CENSEUR');
    const f = TestBed.createComponent(ClassePage);
    f.componentRef.setInput('id', 'c1');
    f.detectChanges();
    http.expectOne('/api/v1/classes/c1').flush({ id: 'c1', anneeId: 'a1', filiereId: 'f1', filiereCode: 'G', profilId: 'pg', code: '6e A', niveau: '6e', effectifMax: 60 });
    await attendre();
    http.expectOne('/api/v1/profils').flush([{ id: 'pg', code: 'GENERAL', libelle: 'Général', modele: 'NOTES_COEFFICIENTS', decoupage: 'TRIMESTRE', actif: true }]);
    http.expectOne('/api/v1/classes/c1/matieres').flush([]);
    http.expectOne('/api/v1/matieres').flush([]);
    http.expectOne('/api/v1/enseignants').flush([]);
    http.expectOne('/api/v1/classes/c1/inscriptions').flush([
      { id: 'i1', eleveId: 'el1', matricule: null, nom: 'ZONGO', prenoms: 'Rasmata', sexe: 'F', redoublant: false, statutBourse: 'NON_BOURSIER', classeId: 'c1', classeCode: '6e A', statut: 'ACTIVE' },
    ]);
    await attendre();
    expect(texte(f)).not.toContain('Inscrire des élèves'); // l'intendance inscrit
    expect(texte(f)).not.toContain('Cartes scolaires');

    bouton(f, 'Répartir')!.click();
    await attendre();
    http.expectOne('/api/v1/annees/a1/classes').flush([
      { id: 'c1', code: '6e A', niveau: '6e' },
      { id: 'c2', code: '6e B', niveau: '6e' },
      { id: 'c3', code: '5e A', niveau: '5e' },
    ]);
    await attendre();
    const select = (f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[aria-label="Nouvelle classe de Rasmata ZONGO"]')!;
    expect([...select.options].map((o) => o.textContent?.trim())).toEqual(['— classe —', '6e B']);
    select.value = 'c2';
    select.dispatchEvent(new Event('change'));
    bouton(f, 'Déplacer')!.click();
    await attendre();
    const req = http.expectOne('/api/v1/inscriptions/i1/classe');
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ classeId: 'c2' });
    req.flush({ id: 'i1', classeCode: '6e B' });
    await attendre();
    expect(texte(f)).toContain('Rasmata ZONGO passe en 6e B.');
  });

  it('chef d’établissement : désigne le censeur pour l’intérim puis y met fin', async () => {
    await session(http, 'ADMIN_ECOLE');
    const f = TestBed.createComponent(PersonnelPage);
    f.detectChanges();
    http.expectOne('/api/v1/enseignants').flush([]);
    http.expectOne('/api/v1/membres').flush([
      { id: 'm1', utilisateurId: 'u6', nom: 'OUEDRAOGO', prenoms: 'Salif', telephone: '70000006', role: 'CENSEUR', actif: true },
      { id: 'm2', utilisateurId: 'u7', nom: 'KONE', prenoms: 'Awa', telephone: '70000007', role: 'INTENDANT', actif: true },
    ]);
    await attendre();
    http.expectOne('/api/v1/personnes/photos').flush([]);
    await attendre();

    bouton(f, 'Afficher')!.click();
    await attendre();
    http.expectOne('/api/v1/interims').flush([]);
    await attendre();
    expect(texte(f)).toContain('Aucun intérim.');
    const personne = (f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#iPersonne')!;
    expect([...personne.options].map((o) => o.textContent?.trim())).toEqual(['— choisir —', 'OUEDRAOGO Salif (Censeur)']);
    personne.value = 'u6';
    personne.dispatchEvent(new Event('change'));
    for (const [id, v] of [['#iDu', '2026-10-20'], ['#iAu', '2026-10-31'], ['#iMotif', 'Mission à Ouagadougou']]) {
      const champ = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>(id)!;
      champ.value = v;
      champ.dispatchEvent(new Event('input'));
    }
    f.detectChanges();
    bouton(f, 'Désigner')!.click();
    await attendre();
    const req = http.expectOne((r) => r.url === '/api/v1/interims' && r.method === 'POST');
    expect(req.request.body).toEqual({ utilisateurId: 'u6', du: '2026-10-20', au: '2026-10-31', motif: 'Mission à Ouagadougou' });
    const interim = { id: 'in1', utilisateurId: 'u6', interimaire: 'OUEDRAOGO Salif', du: '2026-10-20', au: '2026-10-31', motif: 'Mission à Ouagadougou', designePar: 'KABORE Mathieu', enCours: false, termine: false };
    req.flush(interim);
    await attendre();
    expect(texte(f)).toContain('OUEDRAOGO Salif');
    expect(texte(f)).toContain('prévu');

    bouton(f, 'Annuler')!.click();
    await attendre();
    http.expectOne('/api/v1/interims/in1/fin').flush({ ...interim, au: '2026-10-19', termine: true });
    await attendre();
    expect(texte(f)).toContain('terminé');
  });
});
