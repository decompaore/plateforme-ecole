import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { AnneeCourante } from './annee-courante.service';
import { ClassePage } from './pages/classe.page';
import { ElevesPage } from './pages/eleves.page';
import { PersonnelPage } from './pages/personnel.page';
import { PlateformePage } from './pages/plateforme.page';

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

  afterEach(() => http.verify());

  it('super administrateur : crée un établissement et affiche une seule fois le mot de passe de son administrateur', async () => {
    await session(http, true);
    const f = TestBed.createComponent(PlateformePage);
    f.detectChanges();
    http.expectOne('/api/v1/plateforme/etablissements').flush([]);
    await attendre();
    expect(texte(f)).toContain('0 établissement(s)');

    cliquer(f, 'Nouvel établissement');
    f.detectChanges();
    await f.whenStable();
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
    const inscription = http.expectOne('/api/v1/inscriptions');
    expect(inscription.request.body).toEqual({ eleveId: 'el1', classeId: 'c2', redoublant: false, statutBourse: 'NON_BOURSIER' });
    inscription.flush({ id: 'i1', classeCode: '2nde F3' });
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/eleves' && r.method === 'GET').flush({ elements: [], page: 0, taille: 20, total: 0, nombrePages: 0 });
    await attendre();

    expect(texte(f)).toContain('ZONGO Rasmata inscrit(e) en 2nde F3 (matricule 2026-00001)');
    expect((f.nativeElement.querySelector('#nom') as HTMLInputElement).value).toBe('');
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
