import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { AnneeCourante } from './annee-courante.service';
import { ClassePage } from './pages/classe.page';
import { ElevesPage } from './pages/eleves.page';
import { PersonnelPage } from './pages/personnel.page';
import { PlateformePage } from './pages/plateforme.page';
import { pivotAges, StatistiquesPage } from './pages/statistiques.page';
import { SuiviEvaluationsPage, taux } from './pages/suivi-evaluations.page';

const ADMIN = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: ['ADMIN_ECOLE' as const] };
const ANNEE = { id: 'a1', libelle: '2026-2027', debut: '2026-10-01', fin: '2027-07-31', etat: 'ACTIVE' };

/** Ouvre une session (administrateur de l'établissement ou super administrateur). */
async function session(http: HttpTestingController, superAdmin = false): Promise<void> {
  const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(
    reponse('u1', 1, superAdmin ? { superAdmin: true, etablissementActif: null } : { etablissementActif: ADMIN }),
  );
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'KABORE', prenoms: 'Mathieu' });
  await connexion;
  if (!superAdmin) {
    // Années de l'établissement, lues une fois par l'en-tête de l'administration
    const annees = TestBed.inject(AnneeCourante).charger();
    http.expectOne('/api/v1/annees').flush([ANNEE]);
    await annees;
  }
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return (f.nativeElement as HTMLElement).textContent ?? '';
}

function saisir<T>(f: ComponentFixture<T>, selecteur: string, valeur: string): void {
  const champ = (f.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement | HTMLSelectElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event(champ instanceof HTMLSelectElement ? 'change' : 'input'));
}

function cliquer<T>(f: ComponentFixture<T>, libelle: string): void {
  f.detectChanges();
  const bouton = [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) =>
    b.textContent?.includes(libelle),
  );
  if (!bouton) {
    throw new Error(`Bouton « ${libelle} » introuvable`);
  }
  bouton.click();
}

describe('Espace d’administration', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => {
    // Listes « qui a une photo » (v0.44), sans objet pour ces scénarios
    http.match((r) => r.method === 'GET' && r.url.endsWith('/photos')).forEach((r) => r.flush([]));
    http.match((r) => r.method === 'GET' && r.url.endsWith('/photo')).forEach((r) => r.flush(null, { status: 404, statusText: 'Not Found' }));
    http.verify();
  });

  it('super administrateur : crée un établissement et affiche une seule fois le mot de passe de son administrateur', async () => {
    await session(http, true);
    const f = TestBed.createComponent(PlateformePage);
    f.detectChanges();
    http.expectOne('/api/v1/plateforme/etablissements').flush([]);
    await attendre();
    expect(texte(f)).toContain('0 établissement(s)');

    cliquer(f, 'Nouvel établissement');
    f.detectChanges();
    await attendre();
    // Rattachement (v0.35) : seules les directions actives du dernier niveau sont proposées
    http.expectOne('/api/v1/plateforme/territoire/directions').flush([
      { id: 'dr', paysId: 'bf', ministereId: 'm', rang: 1, terminale: false, chemin: 'Burkina Faso · MESFPT · DR du Centre-Ouest', actif: true },
      { id: 'dp', paysId: 'bf', ministereId: 'm', rang: 2, terminale: true, chemin: 'Burkina Faso · MESFPT · DR du Centre-Ouest · DP du Boulkiemdé', actif: true },
      { id: 'dp2', paysId: 'bf', ministereId: 'm', rang: 2, terminale: true, chemin: 'Burkina Faso · MESFPT · DR du Centre-Ouest · DP fermée', actif: false },
    ]);
    await attendre();
    f.detectChanges();
    await f.whenStable();
    const options = [...(f.nativeElement as HTMLElement).querySelectorAll<HTMLOptionElement>('#rattachement option')].map((o) => o.textContent?.trim());
    expect(options).toEqual(['Sans rattachement pour l\'instant', 'Burkina Faso · MESFPT · DR du Centre-Ouest · DP du Boulkiemdé']);
    const select = (f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#rattachement')!;
    select.selectedIndex = 1;
    select.dispatchEvent(new Event('change'));
    saisir(f, '#code', 'ltk');
    saisir(f, '#nom', 'Lycée technique de Koudougou');
    saisir(f, '#tel', '70 11 22 33');
    saisir(f, '#nomAdmin', 'KABORE');
    saisir(f, '#prenomsAdmin', 'Mathieu');
    cliquer(f, 'Créer l’établissement');
    await attendre();

    const creation = http.expectOne('/api/v1/plateforme/etablissements');
    expect(creation.request.method).toBe('POST');
    expect(creation.request.body).toEqual({
      code: 'ltk',
      nom: 'Lycée technique de Koudougou',
      telephoneAdministrateur: '70 11 22 33',
      nomAdministrateur: 'KABORE',
      prenomsAdministrateur: 'Mathieu',
      directionId: 'dp',
    });
    creation.flush({
      etablissement: { id: 'e1', code: 'ltk', nom: 'Lycée technique de Koudougou', statut: 'ACTIF', creeLe: '2026-10-01T08:00:00Z' },
      administrateur: {},
      motDePasseTemporaire: 'Xk7-p2Qa',
    });
    await attendre();
    http.expectOne('/api/v1/plateforme/etablissements').flush([
      { id: 'e1', code: 'ltk', nom: 'Lycée technique de Koudougou', statut: 'ACTIF', creeLe: '2026-10-01T08:00:00Z' },
    ]);
    await attendre();

    expect(texte(f)).toContain('Xk7-p2Qa');
    expect(texte(f)).toContain('1 établissement(s)');
    cliquer(f, "J'ai noté le mot de passe");
    expect(texte(f)).not.toContain('Xk7-p2Qa');
  });

  it('programme d’une classe : modifie un coefficient et confie la matière à un enseignant', async () => {
    await session(http);
    const f = TestBed.createComponent(ClassePage);
    f.componentRef.setInput('id', 'c1');
    f.detectChanges();
    http.expectOne('/api/v1/classes/c1').flush({
      id: 'c1', anneeId: 'a1', filiereId: 'f1', filiereCode: 'F3', profilId: 'pt', code: '2nde F3', niveau: '2nde', effectifMax: 60,
    });
    await attendre();
    http.expectOne('/api/v1/profils').flush([{ id: 'pt', code: 'TECHNIQUE', libelle: 'Enseignement technique', modele: 'NOTES_PAR_GROUPES', decoupage: 'TRIMESTRE', actif: true }]);
    http.expectOne('/api/v1/classes/c1/matieres').flush([
      { id: 'cm1', matiereId: 'm1', matiereCode: 'ELEC', matiereLibelle: 'Électrotechnique', type: 'TECHNIQUE', coefficient: 5, groupe: 'Matières techniques', volumeHebdo: 6, volumeTotal: null, engagementId: null },
    ]);
    http.expectOne('/api/v1/matieres').flush([{ id: 'm1', code: 'ELEC', libelle: 'Électrotechnique', type: 'TECHNIQUE', actif: true }]);
    http.expectOne('/api/v1/enseignants').flush([
      { engagementId: 'g1', enseignantId: 'x', nom: 'SANOU', prenoms: 'Paul', telephone: '61000000', sexe: 'M', specialite: null, type: 'TITULAIRE', statut: 'ACTIF', debut: '2026-09-01', fin: null },
    ]);
    http.expectOne('/api/v1/classes/c1/inscriptions').flush([]);
    await attendre();

    expect(texte(f)).toContain('1 sans enseignant');
    expect(texte(f)).toContain('Profil technique');

    saisir(f, 'input[aria-label="Coefficient Électrotechnique"]', '6');
    cliquer(f, 'Enregistrer');
    await attendre();
    const maj = http.expectOne('/api/v1/classes/c1/matieres/m1');
    expect(maj.request.method).toBe('PUT');
    expect(maj.request.body).toEqual({ coefficient: 6, groupe: 'Matières techniques', volumeHebdo: 6, volumeTotal: null });
    maj.flush({ id: 'cm1', matiereId: 'm1', matiereCode: 'ELEC', matiereLibelle: 'Électrotechnique', type: 'TECHNIQUE', coefficient: 6, groupe: 'Matières techniques', volumeHebdo: 6, volumeTotal: null, engagementId: null });
    await attendre();
    expect(texte(f)).toContain('Électrotechnique : enregistré');

    saisir(f, 'select[aria-label="Enseignant Électrotechnique"]', 'g1');
    await attendre();
    const affectation = http.expectOne('/api/v1/classes/c1/matieres/m1/enseignant');
    expect(affectation.request.method).toBe('PUT');
    expect(affectation.request.body).toEqual({ engagementId: 'g1' });
    affectation.flush({ id: 'cm1', matiereId: 'm1', matiereCode: 'ELEC', matiereLibelle: 'Électrotechnique', type: 'TECHNIQUE', coefficient: 6, groupe: 'Matières techniques', volumeHebdo: 6, volumeTotal: null, engagementId: 'g1' });
    await attendre();
    expect(texte(f)).not.toContain('sans enseignant');
  });

  it('élèves : crée le dossier avec un parent puis l’inscrit dans la classe choisie', async () => {
    await session(http);
    const f = TestBed.createComponent(ElevesPage);
    f.componentRef.setInput('classe', 'c2');
    f.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/eleves' && r.method === 'GET').flush({ elements: [], page: 0, taille: 20, total: 0, nombrePages: 0 });
    http.expectOne('/api/v1/annees/a1/classes').flush([
      { id: 'c1', code: '1re F3', niveau: '1re' },
      { id: 'c2', code: '2nde F3', niveau: '2nde' },
    ]);
    await attendre();
    f.detectChanges();
    await f.whenStable();
    // Venu de la fiche d'une classe : formulaire ouvert, classe présélectionnée
    expect((f.nativeElement.querySelector('#classe') as HTMLSelectElement).value).toBe('c2');

    saisir(f, '#nom', 'ZONGO');
    saisir(f, '#prenoms', 'Rasmata');
    saisir(f, '#naissance', '2010-03-14');
    saisir(f, '#pTel', '70 12 34 56');
    saisir(f, '#pPrenoms', 'Issa');
    // Photo d'identité prise dans le formulaire (v0.44.1)
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:apercu');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const champPhoto = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#photoEleve')!;
    expect(champPhoto.getAttribute('capture')).toBe('user');
    Object.defineProperty(champPhoto, 'files', { value: [new File(['jpg'], 'rasmata.jpg', { type: 'image/jpeg' })] });
    champPhoto.dispatchEvent(new Event('change'));
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).querySelector('img[alt="Photo choisie"]')).not.toBeNull();
    cliquer(f, 'Enregistrer l’élève');
    await attendre();

    const creation = http.expectOne((r) => r.url === '/api/v1/eleves' && r.method === 'POST');
    expect(creation.request.body).toEqual({
      nom: 'ZONGO',
      prenoms: 'Rasmata',
      sexe: 'F',
      dateNaissance: '2010-03-14',
      lieuNaissance: null,
      responsables: [{ nom: 'ZONGO', prenoms: 'Issa', telephone: '70 12 34 56', lien: 'PERE' }],
    });
    creation.flush({ eleve: { id: 'el1', matricule: '2026-00001', nom: 'ZONGO', prenoms: 'Rasmata' }, responsables: [], inscriptions: [] });
    await attendre();
    const photo = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/eleves/el1/photo');
    expect((photo.request.body as FormData).get('fichier')).toBeInstanceOf(Blob);
    photo.flush(null);
    await attendre();
    const inscription = http.expectOne('/api/v1/inscriptions');
    expect(inscription.request.body).toEqual({
      eleveId: 'el1',
      classeId: 'c2',
      redoublant: false,
      statutBourse: 'NON_BOURSIER',
      modeAdmission: 'AFFECTATION_ETAT',
    });
    inscription.flush({ id: 'i1', classeCode: '2nde F3' });
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/eleves' && r.method === 'GET').flush({ elements: [], page: 0, taille: 20, total: 0, nombrePages: 0 });
    await attendre();
    // v0.44 : le dossier s'ouvre pour ajouter la photo d'identité
    http.expectOne('/api/v1/eleves/el1').flush({
      eleve: { id: 'el1', matricule: '2026-00001', nom: 'ZONGO', prenoms: 'Rasmata', dateNaissance: '2010-03-14' },
      responsables: [],
      inscriptions: [{ id: 'i1', anneeId: 'a1', anneeLibelle: '2026-2027', classeId: 'c2', classeCode: '2nde F3', statut: 'ACTIVE' }],
    });
    await attendre();

    expect(texte(f)).toContain('ZONGO Rasmata inscrit(e) en 2nde F3 (matricule 2026-00001). Photo enregistrée.');
    expect((f.nativeElement.querySelector('#nom') as HTMLInputElement).value).toBe('');
    expect((f.nativeElement as HTMLElement).querySelector('img[alt="Photo choisie"]')).toBeNull(); // formulaire vidé
    vi.restoreAllMocks();
  });

  it('élèves (v0.44.1) : colonne Photo de la recherche, « + Photo » ouvre le dossier', async () => {
    await session(http);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:photo');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const f = TestBed.createComponent(ElevesPage);
    f.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/eleves' && r.method === 'GET').flush({
      elements: [
        { id: 'el1', matricule: '2026-00001', nom: 'ZONGO', prenoms: 'Rasmata', sexe: 'F', dateNaissance: '2010-03-14' },
        { id: 'el2', matricule: '2026-00002', nom: 'SAWADOGO', prenoms: 'Ali', sexe: 'M', dateNaissance: '2010-05-01' },
      ],
      page: 0, taille: 20, total: 2, nombrePages: 1,
    });
    http.expectOne('/api/v1/annees/a1/classes').flush([]);
    await attendre();
    const parmi = http.expectOne((r) => r.url === '/api/v1/eleves/photos');
    expect(parmi.request.params.get('ids')).toBe('el1,el2');
    parmi.flush(['el1']);
    await attendre();
    f.detectChanges();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/eleves/el1/photo' && r.params.get('miniature') === 'true').flush(new Blob(['jpg']));
    await attendre();
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    expect(el.querySelector('th.col-photo')?.textContent).toBe('Photo');
    expect(el.querySelectorAll('img.vignette')).toHaveLength(1);
    const ajouter = el.querySelector<HTMLButtonElement>('button[aria-label="Ajouter la photo de Ali SAWADOGO"]')!;
    expect(ajouter.textContent).toContain('Photo');
    ajouter.click();
    await attendre();
    http.expectOne('/api/v1/eleves/el2').flush({
      eleve: { id: 'el2', matricule: '2026-00002', nom: 'SAWADOGO', prenoms: 'Ali', dateNaissance: '2010-05-01' },
      responsables: [],
      inscriptions: [],
    });
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/eleves/el2/photo' && !r.params.has('miniature')).flush(null, { status: 404, statusText: 'Not Found' });
    await attendre();
    expect(texte(f)).toContain('Ajouter une photo');
    vi.restoreAllMocks();
  });

  it('personnel (v0.44) : miniatures, photo d’un membre et cartes professionnelles', async () => {
    await session(http);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:photo');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    const f = TestBed.createComponent(PersonnelPage);
    f.detectChanges();
    http.expectOne('/api/v1/enseignants').flush([
      { engagementId: 'e1', enseignantId: 'en1', utilisateurId: 'u5', nom: 'SANOU', prenoms: 'Paul', telephone: '70000005', sexe: 'M', specialite: 'Maths', type: 'TITULAIRE', statut: 'ACTIF', debut: '2026-10-01', fin: null },
    ]);
    http.expectOne('/api/v1/membres').flush([
      { id: 'm1', utilisateurId: 'u6', nom: 'KONE', prenoms: 'Awa', telephone: '70000006', role: 'SECRETARIAT', actif: true },
    ]);
    await attendre();
    http.expectOne('/api/v1/personnes/photos').flush(['u5']);
    await attendre();
    f.detectChanges();
    await attendre();
    // Seul Paul SANOU a une photo : sa miniature est demandée
    const mini = http.expectOne((r) => r.url === '/api/v1/personnes/u5/photo');
    expect(mini.request.params.get('miniature')).toBe('true');
    mini.flush(new Blob(['jpg'], { type: 'image/jpeg' }));
    await attendre();

    const boutons = (f.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button.photo');
    expect(boutons).toHaveLength(2);
    boutons[1].click(); // Awa KONE
    f.detectChanges();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/personnes/u6/photo' && !r.params.has('miniature')).flush(null, { status: 404, statusText: 'Not Found' });
    await attendre();
    expect(texte(f)).toContain('Photo de Awa KONE');
    expect(texte(f)).toContain('Ajouter une photo');

    cliquer(f, 'Carte professionnelle (PDF)');
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/personnes/cartes' && r.params.get('utilisateur') === 'u6').flush(new Blob(['%PDF']));
    await attendre();
    cliquer(f, 'Cartes professionnelles (PDF)');
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/personnes/cartes' && !r.params.has('utilisateur')).flush(new Blob(['%PDF']));
    await attendre();
    expect(URL.createObjectURL).toHaveBeenCalledTimes(3); // la miniature de Paul + 2 PDF
    vi.restoreAllMocks();
  });

  it('personnel (v0.44.1) : la photo prise dans le formulaire d’ajout part avec le nouveau compte', async () => {
    await session(http);
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:apercu');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    const f = TestBed.createComponent(PersonnelPage);
    f.detectChanges();
    http.expectOne('/api/v1/enseignants').flush([]);
    http.expectOne('/api/v1/membres').flush([]);
    await attendre();
    http.expectOne('/api/v1/personnes/photos').flush([]);
    await attendre();
    cliquer(f, 'Ajouter une personne');
    f.detectChanges();
    await f.whenStable();
    saisir(f, '#mTel', '70000008');
    saisir(f, '#mNom', 'KONE');
    saisir(f, '#mPrenoms', 'Awa');
    const champ = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#mPhoto')!;
    Object.defineProperty(champ, 'files', { value: [new File(['jpg'], 'awa.jpg', { type: 'image/jpeg' })] });
    champ.dispatchEvent(new Event('change'));
    f.detectChanges();
    expect(texte(f)).toContain('Changer');
    ((f.nativeElement as HTMLElement).querySelector('form.nouveau button[type=submit]') as HTMLButtonElement).click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/membres').flush({
      membre: { id: 'm8', utilisateurId: 'u8', nom: 'KONE', prenoms: 'Awa', telephone: '70000008', role: 'SECRETARIAT', actif: true },
      motDePasseTemporaire: null,
    });
    await attendre();
    const photo = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/personnes/u8/photo');
    expect((photo.request.body as FormData).get('fichier')).toBeInstanceOf(Blob);
    photo.flush(null);
    await attendre();
    http.expectOne('/api/v1/enseignants').flush([]);
    http.expectOne('/api/v1/membres').flush([
      { id: 'm8', utilisateurId: 'u8', nom: 'KONE', prenoms: 'Awa', telephone: '70000008', role: 'SECRETARIAT', actif: true },
    ]);
    await attendre();
    http.expectOne('/api/v1/personnes/photos').flush(['u8']);
    await attendre();
    f.detectChanges();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/personnes/u8/photo' && r.params.get('miniature') === 'true').flush(new Blob(['jpg']));
    await attendre();
    const el = f.nativeElement as HTMLElement;
    expect([...el.querySelectorAll('th.col-photo')].map((t) => t.textContent)).toEqual(['Photo', 'Photo']);
    expect(el.querySelector('button[aria-label="Photo de Awa KONE"] img.vignette')).not.toBeNull();
    expect(texte(f)).not.toContain('Photo non enregistrée');
    vi.restoreAllMocks();
  });

  it('personnel : un enseignant inconnu reçoit un compte, un enseignant déjà inscrit une invitation', async () => {
    await session(http);
    const f = TestBed.createComponent(PersonnelPage);
    f.detectChanges();
    http.expectOne('/api/v1/enseignants').flush([]);
    http.expectOne('/api/v1/membres').flush([]);
    await attendre();

    const engager = async (tel: string, reponseServeur: object) => {
      cliquer(f, 'Engager un enseignant');
      f.detectChanges();
      await f.whenStable();
      saisir(f, '#eTel', tel);
      saisir(f, '#eNom', 'SANOU');
      saisir(f, '#ePrenoms', 'Paul');
      cliquer(f, 'Engager');
      await attendre();
      const req = http.expectOne((r) => r.url === '/api/v1/enseignants' && r.method === 'POST');
      expect(req.request.body).toMatchObject({ telephone: tel, nom: 'SANOU', prenoms: 'Paul', type: 'TITULAIRE', fin: null, tauxHoraire: null });
      req.flush(reponseServeur);
      await attendre();
      http.expectOne((r) => r.url === '/api/v1/enseignants' && r.method === 'GET').flush([]);
      http.expectOne('/api/v1/membres').flush([]);
      await attendre();
    };

    await engager('61000001', { enseignant: { nom: 'SANOU', prenoms: 'Paul' }, invitation: false, motDePasseTemporaire: 'Tmp-9x' });
    expect(texte(f)).toContain('Tmp-9x');
    cliquer(f, "J'ai noté le mot de passe");

    await engager('61000002', { enseignant: { nom: 'SANOU', prenoms: 'Paul' }, invitation: true, motDePasseTemporaire: null });
    expect(texte(f)).toContain('une invitation lui a été envoyée');
  });

  it('personnel (v0.40) : catégorie général / technique, suggestion de reclassement d’un enseignant de TP', async () => {
    await session(http);
    const f = TestBed.createComponent(PersonnelPage);
    f.detectChanges();
    const tp = {
      engagementId: 'g3', enseignantId: 's3', nom: 'TRAORE', prenoms: 'Ali', telephone: '22661000003', sexe: 'M',
      specialite: 'TP électricité', type: 'TITULAIRE', statut: 'ACTIF', debut: '2026-10-01', fin: null, motifFin: null,
      finProgrammee: false, categorie: 'GENERAL', suggestionTechnique: true,
    };
    const maths = { ...tp, engagementId: 'g4', nom: 'KABORE', prenoms: 'Awa', specialite: 'Maths', suggestionTechnique: false };
    http.expectOne('/api/v1/enseignants').flush([tp, maths]);
    http.expectOne('/api/v1/membres').flush([]);
    await attendre();
    expect(texte(f)).toContain('Assure une matière technique ou pratique.');
    cliquer(f, 'Classer en technique');
    await attendre();
    const req = http.expectOne((r) => r.url === '/api/v1/engagements/g3/categorie' && r.method === 'PUT');
    expect(req.request.body).toEqual({ categorie: 'TECHNIQUE' });
    req.flush({ ...tp, categorie: 'TECHNIQUE', suggestionTechnique: false });
    await attendre();
    http.expectOne('/api/v1/enseignants').flush([{ ...tp, categorie: 'TECHNIQUE', suggestionTechnique: false }, maths]);
    http.expectOne('/api/v1/membres').flush([]);
    await attendre();
    const t = texte(f);
    expect(t).toContain('Technique et professionnel');
    expect(t).toContain('enseignement technique et professionnel');
    expect(t).not.toContain('Assure une matière technique');

    // Le formulaire d'engagement propose la catégorie
    cliquer(f, 'Engager un enseignant');
    f.detectChanges();
    await f.whenStable();
    expect((f.nativeElement as HTMLElement).querySelector('#eCategorie')).not.toBeNull();
  });

  it('personnel : programme la mutation d’un titulaire, rappelle ses matières puis annule la fin', async () => {
    await session(http);
    const f = TestBed.createComponent(PersonnelPage);
    f.detectChanges();
    const titulaire = {
      engagementId: 'g1', enseignantId: 's1', nom: 'SANOU', prenoms: 'Paul', telephone: '22661000001', sexe: 'M',
      specialite: 'Maths', type: 'TITULAIRE', statut: 'ACTIF', debut: '2026-10-01', fin: null, motifFin: null,
      finProgrammee: false,
    };
    const invitation = { ...titulaire, engagementId: 'g2', enseignantId: null, nom: null, prenoms: null, telephone: null, statut: 'INVITE' };
    http.expectOne('/api/v1/enseignants').flush([invitation, titulaire]);
    http.expectOne('/api/v1/membres').flush([]);
    await attendre();
    expect(texte(f)).toContain('Invitation en attente');

    cliquer(f, 'Terminer…');
    await attendre();
    http.expectOne('/api/v1/engagements/g1').flush({
      enseignant: titulaire,
      anneeId: 'a1',
      affectations: [
        { classeId: 'c2', classeCode: '2nde F3', matiereId: 'm1', matiereCode: 'MATH', matiereLibelle: 'Mathématiques', volumeHebdo: 4, volumeTotal: null, engagementId: 'g1' },
        { classeId: 'c1', classeCode: '1re F3', matiereId: 'm1', matiereCode: 'MATH', matiereLibelle: 'Mathématiques', volumeHebdo: 5, volumeTotal: null, engagementId: 'g1' },
      ],
      chargeHebdomadaire: 9,
    });
    await attendre();
    f.detectChanges();
    await f.whenStable();
    saisir(f, '#finDate', '2099-06-30');
    expect(texte(f)).toContain('2 matière(s) à réaffecter');
    expect(texte(f)).toContain('1re F3 · Mathématiques (5 h / semaine)');
    expect(texte(f)).toContain('pourra l\'inviter comme titulaire à partir du');

    cliquer(f, 'Programmer la fin');
    await attendre();
    const fin = http.expectOne('/api/v1/engagements/g1/fin');
    expect(fin.request.method).toBe('POST');
    expect(fin.request.body).toEqual({ date: '2099-06-30', motif: 'Mutation' });
    const programmee = { ...titulaire, fin: '2099-06-30', motifFin: 'Mutation', finProgrammee: true };
    fin.flush(programmee);
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/enseignants' && r.method === 'GET').flush([programmee]);
    http.expectOne('/api/v1/membres').flush([]);
    await attendre();
    expect(texte(f)).toContain('Fin programmée : Paul SANOU enseigne jusqu');
    expect(texte(f)).toContain('part le 30/06/2099');

    vi.spyOn(window, 'confirm').mockReturnValue(true);
    cliquer(f, 'Annuler la fin');
    await attendre();
    const annulation = http.expectOne('/api/v1/engagements/g1/fin');
    expect(annulation.request.method).toBe('DELETE');
    annulation.flush(titulaire);
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/enseignants' && r.method === 'GET').flush([titulaire]);
    http.expectOne('/api/v1/membres').flush([]);
    await attendre();
    expect(texte(f)).toContain('Fin d’engagement annulée'.replace('’', "'"));
    expect(texte(f)).not.toContain('part le');
  });

  it('suivi des évaluations : par enseignant, matières sans évaluation, puis un trimestre', async () => {
    await session(http);
    const f = TestBed.createComponent(SuiviEvaluationsPage);
    f.detectChanges();
    await attendre();
    const ligne = (classe: string, matiere: string, enseignant: string | null, parType: object, saisies: number, attendues: number, derniere: string | null) => ({
      classeId: classe, classeCode: classe, niveau: '2nde', matiereId: matiere, matiereCode: matiere, matiereLibelle: matiere,
      engagementId: enseignant ? 'g-' + enseignant : null, enseignant,
      evaluations: Object.values(parType).reduce((t: number, n) => t + (n as number), 0), parType, derniere, notesSaisies: saisies, notesAttendues: attendues,
    });
    const reponse = [
      ligne('2nde F3', 'Électrotechnique', 'SANOU Paul', { DEVOIR: 2, INTERROGATION: 1 }, 120, 120, '2026-09-27'),
      ligne('1re F3', 'Électrotechnique', 'SANOU Paul', { TP: 1 }, 20, 40, '2026-09-20'),
      ligne('2nde F3', 'Français', 'KABORE Awa', {}, 0, 0, null),
      ligne('2nde F3', 'EPS', null, {}, 0, 0, null),
    ];
    http.expectOne((r) => r.url === '/api/v1/annees/a1/suivi-evaluations' && !r.params.has('ordre')).flush(reponse);
    http.expectOne('/api/v1/annees/a1/periodes').flush([
      { id: 'p1', anneeId: 'a1', profilId: 'pt', libelle: 'Trimestre 1', ordre: 1, debut: '2026-10-01', fin: '2026-12-20', verrouillee: false },
      { id: 'p2', anneeId: 'a1', profilId: 'pt', libelle: 'Trimestre 2', ordre: 2, debut: '2027-01-04', fin: '2027-03-31', verrouillee: false },
    ]);
    await attendre();
    const t = texte(f);
    const tuiles = [...(f.nativeElement as HTMLElement).querySelectorAll('.chiffres strong')].map((x) => x.textContent?.trim());
    expect(tuiles).toEqual(['4', '2', '88 %']); // 4 évaluations, 2 matières sans évaluation, 140 / 160 notes
    // Groupes : enseignants par ordre alphabétique, puis les matières sans enseignant
    const titres = [...(f.nativeElement as HTMLElement).querySelectorAll('section h2')].map((h) => h.textContent?.trim().split(' ·')[0]);
    expect(titres).toEqual(['KABORE Awa', 'SANOU Paul', 'Matières sans enseignant']);
    expect(t).toContain('2 classe(s), 2 matière(s)');
    expect(t).toContain('aucune');
    expect(t).toContain('50 %'); // 1re F3 : 20 / 40

    // Regroupement par classe, puis le 1er trimestre seulement
    [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Classe')!.click();
    f.detectChanges();
    const parClasse = [...(f.nativeElement as HTMLElement).querySelectorAll('section h2')].map((h) => h.textContent?.trim().split(' ·')[0]);
    expect(parClasse).toEqual(['1re F3', '2nde F3']);
    saisir(f, '#periode', '1');
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/annees/a1/suivi-evaluations' && r.params.get('ordre') === '1').flush([]);
    await attendre();
    expect(taux(0, 0)).toBeNull();
  });

  it('statistiques : chiffres clés, âges par sexe, recouvrement, résultats à venir et classeur Excel', async () => {
    await session(http);
    const f = TestBed.createComponent(StatistiquesPage);
    f.detectChanges();
    await attendre();
    const c = (garcons: number, filles: number) => ({ garcons, filles });
    const recouvrement = (classe: string, du: number, paye: number, taux: number | null) => ({
      classe, duFamilles: du, payeFamilles: paye, tauxFamilles: taux, duOrganismes: 0, payeOrganismes: 0, tauxOrganismes: null,
    });
    http.expectOne('/api/v1/annees/a1/statistiques').flush({
      etablissement: 'Lycée technique', annee: ANNEE, produitLe: '2026-10-03T09:00:00Z',
      effectifTotal: c(70, 50), classes: 3,
      effectifs: [
        { niveau: '2nde', classes: 2, effectif: c(45, 35), redoublants: c(3, 1) },
        { niveau: '1re', classes: 1, effectif: c(25, 15), redoublants: c(0, 0) },
      ],
      ages: [
        { niveau: '2nde', age: 15, effectif: c(20, 20) },
        { niveau: '2nde', age: 16, effectif: c(25, 15) },
        { niveau: '1re', age: 17, effectif: c(25, 15) },
      ],
      bourses: [{ filiere: 'F3', boursiers: c(5, 7), semiBoursiers: c(0, 0), nonBoursiers: c(65, 43) }],
      personnel: { titulaires: c(10, 4), vacataires: c(5, 1), sexeNonRenseigne: 1, administratif: { CENSEUR: 1, INTENDANT: 2 } },
      recouvrement: [recouvrement('2nde F3 A', 2_000_000, 1_500_000, 75), recouvrement('1re F3', 1_000_000, 300_000, 30)],
      recouvrementTotal: recouvrement('TOTAL', 3_000_000, 1_800_000, 60),
      resultats: [],
    });
    await attendre();
    const t = texte(f);
    const tuiles = [...(f.nativeElement as HTMLElement).querySelectorAll('.chiffres strong')].map((x) => x.textContent?.trim());
    expect(tuiles).toEqual(['120', '3', '20', '60 %']);
    expect(t).toContain('70 G, 50 F');
    expect(t).toContain('Intendance');
    expect(t).toContain('1 enseignant(s) sans sexe renseigné');
    expect(t).toContain('12 (5 G, 7 F)');
    expect(t).toMatch(/1.500.000 FCFA/);
    expect(t).toContain("Les résultats apparaissent une fois les décisions de fin d'année");
    // Taux familles sous 50 % en rouge
    const rouges = [...(f.nativeElement as HTMLElement).querySelectorAll('#recouvrement .rouge')].map((x) => x.textContent?.trim());
    expect(rouges).toEqual(['30 %']);

    // Âges : total, puis filles seulement
    const cellules = () => [...(f.nativeElement as HTMLElement).querySelectorAll('table.ages tbody tr:first-child td')].map((x) => x.textContent?.trim());
    expect(cellules()).toEqual(['2nde', '40', '40', '', '80']);
    [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Filles')!.click();
    expect((f.detectChanges(), cellules())).toEqual(['2nde', '20', '15', '', '35']);
    expect(pivotAges([]).colonnes).toEqual([]);

    // Classeur Excel
    const creer = vi.fn(() => 'blob:stat');
    URL.createObjectURL = creer;
    URL.revokeObjectURL = vi.fn();
    [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.includes('Excel'))!.click();
    await attendre();
    http.expectOne('/api/v1/annees/a1/statistiques/excel').flush(new Blob(['xlsx']));
    await attendre();
    expect(creer).toHaveBeenCalled();
  });

  it('affiche le message du serveur quand une règle est refusée', async () => {
    await session(http);
    const f = TestBed.createComponent(PersonnelPage);
    f.detectChanges();
    http.expectOne('/api/v1/enseignants').flush([]);
    http.expectOne('/api/v1/membres').flush([
      { id: 'mb1', utilisateurId: 'u1', nom: 'KABORE', prenoms: 'Mathieu', telephone: '60000000', role: 'ADMIN_ECOLE', actif: true },
    ]);
    await attendre();
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    cliquer(f, 'Retirer');
    await attendre();
    http.expectOne('/api/v1/membres/mb1/desactivation').flush(
      { title: 'Règle', detail: 'Le dernier administrateur ne peut pas être retiré', code: 'DERNIER_ADMIN' },
      { status: 409, statusText: 'Conflict' },
    );
    await attendre();
    expect(texte(f)).toContain('Le dernier administrateur ne peut pas être retiré');
  });
});
