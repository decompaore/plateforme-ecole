import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { AnneeCourante } from './annee-courante.service';
import { ClassePage } from './pages/classe.page';
import { ElevesPage, erreurResponsable } from './pages/eleves.page';

const ADMIN = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: ['ADMIN_ECOLE' as const] };
const ANNEE = { id: 'a1', libelle: '2026-2027', debut: '2026-10-01', fin: '2027-07-31', etat: 'ACTIVE' };

async function session(http: HttpTestingController): Promise<void> {
  const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: ADMIN }));
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

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement {
  f.detectChanges();
  const b = [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((x) => x.textContent?.trim().startsWith(libelle));
  if (!b) {
    throw new Error(`Bouton « ${libelle} » introuvable`);
  }
  return b;
}

async function saisir<T>(f: ComponentFixture<T>, selecteur: string, valeur: string): Promise<void> {
  f.detectChanges();
  await f.whenStable();
  const champ = (f.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement | HTMLSelectElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event(champ instanceof HTMLSelectElement ? 'change' : 'input'));
  f.detectChanges();
}

const MERE = {
  responsableId: 'r1', nom: 'ZONGO', prenoms: 'Awa', telephone: '+22670123456', profession: 'Commerçante', langueSms: null,
  lien: 'MERE' as const, responsableLegal: true, contactPrioritaire: true, espaceParentOuvert: false,
};

function dossier(responsables: object[]): object {
  return {
    eleve: { id: 'e1', matricule: '2026-00012', nom: 'ZONGO', prenoms: 'Rasmata', sexe: 'F', dateNaissance: '2010-03-14', lieuNaissance: null },
    responsables,
    inscriptions: [],
  };
}

describe('Comptes des parents et tuteurs', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('contrôle un parent ou tuteur avant l’envoi', () => {
    expect(erreurResponsable('', 'Awa', '70123456')).toContain('nom');
    expect(erreurResponsable('ZONGO', 'Awa', '7012')).toContain('Téléphone');
    expect(erreurResponsable('ZONGO', 'Awa', '70 12 34 56')).toBeNull();
  });

  it('dossier d’un élève : crée le compte parent, puis ajoute un tuteur avec son compte', async () => {
    await session(http);
    const confirmation = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const f = TestBed.createComponent(ElevesPage);
    f.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/eleves' && r.method === 'GET').flush({
      elements: [{ id: 'e1', matricule: '2026-00012', nom: 'ZONGO', prenoms: 'Rasmata', sexe: 'F', dateNaissance: '2010-03-14' }],
      page: 0, taille: 20, total: 1, nombrePages: 1,
    });
    http.expectOne('/api/v1/annees/a1/classes').flush([]);
    await attendre();
    ((f.nativeElement as HTMLElement).querySelector('tr.ligne') as HTMLElement).click();
    await attendre();
    http.expectOne('/api/v1/eleves/e1').flush(dossier([MERE]));
    await attendre();
    let t = texte(f);
    expect(t).toContain('ZONGO Awa · Mère · +22670123456');
    expect(t).toContain('pas de compte parent');

    bouton(f, 'Créer le compte parent').click();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/responsables/r1/espace-parent' && r.method === 'POST')
      .flush({ responsableId: 'r1', utilisateurId: 'u7', telephone: '+22670123456', motDePasseTemporaire: 'Hm4kT9xQpa' });
    await attendre();
    t = texte(f);
    expect(t).toContain('Compte parent de Awa ZONGO créé.');
    expect(t).toContain('Hm4kT9xQpa');
    expect(t).toContain('compte parent ouvert');
    bouton(f, 'J\'ai noté le mot de passe').click();

    // Un tuteur qui a déjà un compte (parent d'un autre élève) : accès ajouté, mot de passe habituel
    bouton(f, 'Ajouter un parent ou tuteur').click();
    await saisir(f, '#rNom', 'Ouedraogo');
    await saisir(f, '#rPrenoms', 'Issa');
    await saisir(f, '#rTel', '76 54 32 10');
    await saisir(f, '#rLien', 'TUTEUR');
    bouton(f, 'Enregistrer').click();
    await attendre();
    const ajout = http.expectOne((r) => r.url === '/api/v1/eleves/e1/responsables' && r.method === 'POST');
    expect(ajout.request.body).toEqual({
      nom: 'Ouedraogo', prenoms: 'Issa', telephone: '76 54 32 10', lien: 'TUTEUR', profession: null, langueSms: null,
      responsableLegal: true, contactPrioritaire: false,
    });
    const tuteur = { ...MERE, responsableId: 'r2', nom: 'OUEDRAOGO', prenoms: 'Issa', telephone: '+22676543210', lien: 'TUTEUR', contactPrioritaire: false, profession: null };
    ajout.flush(dossier([{ ...MERE, espaceParentOuvert: true }, tuteur]));
    await attendre();
    http.expectOne('/api/v1/responsables/r2/espace-parent')
      .flush({ responsableId: 'r2', utilisateurId: 'u9', telephone: '+22676543210', motDePasseTemporaire: null });
    await attendre();
    t = texte(f);
    expect(t).toContain('Issa OUEDRAOGO avait déjà un compte');
    expect(t).not.toContain('pas de compte parent');

    bouton(f, 'Retirer').click();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/eleves/e1/responsables/r1' && r.method === 'DELETE').flush(dossier([{ ...tuteur, espaceParentOuvert: true }]));
    await attendre();
    expect(texte(f)).not.toContain('ZONGO Awa');
    confirmation.mockRestore();
  });

  it('classe : ouvre les comptes des parents et télécharge la fiche de remise', async () => {
    await session(http);
    const creer = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    const clic = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    try {
      const f = TestBed.createComponent(ClassePage);
      f.componentRef.setInput('id', 'c1');
      f.detectChanges();
      http.expectOne('/api/v1/classes/c1').flush({
        id: 'c1', anneeId: 'a1', filiereId: 'f1', filiereCode: 'F3', profilId: 'pt', code: '2nde F3', niveau: '2nde', effectifMax: 60,
      });
      await attendre();
      http.expectOne('/api/v1/profils').flush([{ id: 'pt', code: 'TECHNIQUE', libelle: 'Enseignement technique', modele: 'NOTES_PAR_GROUPES', decoupage: 'TRIMESTRE', actif: true }]);
      http.expectOne('/api/v1/classes/c1/matieres').flush([]);
      http.expectOne('/api/v1/matieres').flush([]);
      http.expectOne('/api/v1/enseignants').flush([]);
      http.expectOne('/api/v1/classes/c1/inscriptions').flush([
        { id: 'i1', eleveId: 'e1', matricule: '2026-00012', nom: 'ZONGO', prenoms: 'Rasmata', sexe: 'F', statut: 'ACTIVE', redoublant: false, statutBourse: 'NON_BOURSIER' },
      ]);
      await attendre();
      expect(texte(f)).toContain('Comptes des parents');
      bouton(f, 'Ouvrir les comptes des parents').click();
      expect(texte(f)).toContain('Les mots de passe provisoires ne figurent que sur cette fiche');
      bouton(f, 'Ouvrir et télécharger la fiche (PDF)').click();
      await attendre();
      const req = http.expectOne((r) => r.url === '/api/v1/classes/c1/espaces-parents' && r.method === 'POST');
      expect(req.request.params.get('format')).toBe('pdf');
      req.flush(new Blob(['%PDF']), { headers: { 'Content-Disposition': 'attachment; filename="acces-parents-2nde-F3.pdf"' } });
      await attendre();
      expect(clic).toHaveBeenCalled();
      expect(texte(f)).toContain('Comptes des parents ouverts');
    } finally {
      creer.mockRestore();
      clic.mockRestore();
    }
  });
});
