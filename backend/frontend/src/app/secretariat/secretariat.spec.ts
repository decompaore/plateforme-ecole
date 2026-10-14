import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { AffichagePage } from '../pages/affichage.page';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { CourrierVue, DemandeVue, DocumentVue, taille } from './modeles-secretariat';
import { AccueilPublicPage } from './pages/accueil-public.page';
import { CourrierPage } from './pages/courrier.page';
import { DemandesPage } from './pages/demandes.page';
import { DocumentsPage, MODELES } from './pages/documents.page';

async function session(http: HttpTestingController, role: Role): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée Horizon', roles: [role], modulesDesactives: [] };
  const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'KONE', prenoms: 'Awa' });
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

function saisir<T>(f: ComponentFixture<T>, selecteur: string, valeur: string): void {
  f.detectChanges();
  const champ = (f.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event(champ instanceof HTMLSelectElement ? 'change' : 'input'));
  f.detectChanges();
}

const COURRIER: CourrierVue = {
  id: 'c1', sens: 'ARRIVEE', numero: 'A-2026-0001', dateCourrier: '2026-10-14', dateDocument: null, reference: 'N°123',
  correspondant: 'DPEPPNF du Kadiogo', categorie: 'DIRECTION_PROVINCIALE', objet: 'Calendrier des examens', service: null,
  statut: 'ENREGISTRE', suivi: null, cote: null, reponseA: null, reponseANumero: null, fichierNom: null, enregistrePar: 'KONE Awa',
  enregistreLe: '2026-10-14T08:00:00Z',
};

describe('Secrétariat (v0.47.1)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('outils : taille lisible', () => {
    expect(taille(800)).toBe('800 o');
    expect(taille(2048)).toBe('2 Ko');
    expect(taille(3 * 1024 * 1024)).toBe('3,0 Mo');
  });

  it('courrier : la secrétaire enregistre une arrivée et la transmet à l’intendance', async () => {
    await session(http, 'SECRETARIAT');
    const f = TestBed.createComponent(CourrierPage);
    f.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/courriers').flush([]);
    await attendre();
    expect(texte(f)).toContain('Aucun courrier.');
    saisir(f, '#cCorrespondant', 'DPEPPNF du Kadiogo');
    saisir(f, '#cObjet', 'Calendrier des examens');
    saisir(f, '#cReference', 'N°123');
    bouton(f, 'Enregistrer')!.click();
    await attendre();
    const post = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/courriers');
    expect(post.request.body).toMatchObject({ sens: 'ARRIVEE', correspondant: 'DPEPPNF du Kadiogo', categorie: 'DIRECTION_PROVINCIALE', reference: 'N°123', service: null });
    post.flush(COURRIER);
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/courriers').flush([COURRIER]);
    await attendre();
    expect(texte(f)).toContain('Courrier A-2026-0001 enregistré.');

    const select = (f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('select[aria-label="Service pour A-2026-0001"]')!;
    select.value = 'INTENDANCE';
    select.dispatchEvent(new Event('change'));
    bouton(f, 'Transmettre')!.click();
    await attendre();
    const suivi = http.expectOne('/api/v1/courriers/c1/suivi');
    expect(suivi.request.body).toEqual({ statut: 'TRANSMIS', service: 'INTENDANCE', suivi: null });
    suivi.flush({ ...COURRIER, statut: 'TRANSMIS', service: 'INTENDANCE' });
    await attendre();
    expect(texte(f)).toContain('A-2026-0001 transmis : Intendance.');
  });

  it('courrier : l’intendant voit le courrier transmis et le marque traité, sans saisie', async () => {
    await session(http, 'INTENDANT');
    const f = TestBed.createComponent(CourrierPage);
    f.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/courriers').flush([{ ...COURRIER, statut: 'TRANSMIS', service: 'INTENDANCE' }]);
    await attendre();
    const t = texte(f);
    expect(t).toContain('Courrier transmis à votre service');
    expect(t).not.toContain('Enregistrer un courrier');
    expect(bouton(f, 'Classer')).toBeUndefined();
    bouton(f, 'Marquer traité')!.click();
    await attendre();
    http.expectOne('/api/v1/courriers/c1/suivi').flush({ ...COURRIER, statut: 'TRAITE', service: 'INTENDANCE' });
    await attendre();
    expect(texte(f)).toContain('traité');
  });

  it('documents : brouillon d’une note de service, signature du chef, affichage pour tous', async () => {
    await session(http, 'ADMIN_ECOLE');
    const f = TestBed.createComponent(DocumentsPage);
    f.detectChanges();
    http.expectOne('/api/v1/documents-administratifs').flush([]);
    await attendre();
    const corps = (f.nativeElement as HTMLElement).querySelector<HTMLTextAreaElement>('#dCorps')!;
    expect(corps.value).toBe(MODELES.NOTE_SERVICE);
    saisir(f, '#dTitre', 'Horaires des examens');
    bouton(f, 'Créer le brouillon')!.click();
    await attendre();
    const post = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/documents-administratifs');
    expect(post.request.body).toMatchObject({ type: 'NOTE_SERVICE', titre: 'Horaires des examens', signataire: null });
    const brouillon: DocumentVue = {
      id: 'd1', type: 'NOTE_SERVICE', numero: null, titre: 'Horaires des examens', destinataires: null, corps: 'x',
      signataire: "Le Chef d'établissement", statut: 'BROUILLON', redigePar: 'KONE Awa', redigeLe: '2026-10-14T08:00:00Z',
      signePar: null, signeLe: null, affichage: null, afficheDu: null, afficheAu: null,
    };
    post.flush(brouillon);
    await attendre();
    expect(texte(f)).toContain('brouillon');
    bouton(f, 'Signer')!.click();
    await attendre();
    const signe = { ...brouillon, statut: 'SIGNE' as const, numero: '2026-0001/NS', signePar: 'Mathieu KABORE', signeLe: '2026-10-14T09:00:00Z' };
    http.expectOne('/api/v1/documents-administratifs/d1/signature').flush(signe);
    await attendre();
    expect(texte(f)).toContain('Note de service n° 2026-0001/NS signée.');
    const el = f.nativeElement as HTMLElement;
    const pub = el.querySelector<HTMLSelectElement>('select[aria-label="Affichage de Horaires des examens"]')!;
    pub.value = 'TOUS';
    pub.dispatchEvent(new Event('change'));
    const fin = el.querySelector<HTMLInputElement>('input[aria-label="Affiché jusqu’au, Horaires des examens"]')!;
    fin.value = '2026-10-31';
    fin.dispatchEvent(new Event('change'));
    bouton(f, 'Afficher')!.click();
    await attendre();
    const aff = http.expectOne('/api/v1/documents-administratifs/d1/affichage');
    expect(aff.request.body).toEqual({ affichage: 'TOUS', du: null, au: '2026-10-31' });
    aff.flush({ ...signe, affichage: 'TOUS', afficheDu: '2026-10-14', afficheAu: '2026-10-31' });
    await attendre();
    expect(texte(f)).toContain("est au tableau d'affichage jusqu'au 31/10/2026");
  });

  it('demandes : la secrétaire reçoit une demande d’autorisation d’absence et la transmet au chef', async () => {
    await session(http, 'SECRETARIAT');
    const f = TestBed.createComponent(DemandesPage);
    f.detectChanges();
    http.expectOne('/api/v1/demandes-administratives').flush([]);
    http.expectOne('/api/v1/secretariat/personnel').flush([{ utilisateurId: 'u6', nom: 'OUEDRAOGO', prenoms: 'Salif', fonctions: 'Censeur' }]);
    await attendre();
    saisir(f, '#dDemandeur', 'u6');
    saisir(f, '#dObjet', 'Obsèques');
    saisir(f, '#dDu', '2026-10-20');
    bouton(f, 'Enregistrer la demande')!.click();
    await attendre();
    const post = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/demandes-administratives');
    expect(post.request.body).toMatchObject({ type: 'AUTORISATION_ABSENCE', demandeurId: 'u6', objet: 'Obsèques', du: '2026-10-20', au: null });
    const d: DemandeVue = {
      id: 'r1', numero: 'R-2026-0001', type: 'AUTORISATION_ABSENCE', typeLibelle: "Demande d'autorisation d'absence", demandeurId: 'u6',
      demandeur: 'OUEDRAOGO Salif', recueLe: '2026-10-14', objet: 'Obsèques', du: '2026-10-20', au: null, statut: 'RECUE', avis: null,
      avisPar: null, avisLe: null, suite: null, courrierId: null, courrierNumero: null, fichierNom: null,
    };
    post.flush(d);
    await attendre();
    expect(texte(f)).toContain('Demande R-2026-0001 enregistrée (OUEDRAOGO Salif).');
    expect(bouton(f, 'Favorable')).toBeUndefined(); // l'avis : chef d'établissement
    bouton(f, 'Transmettre au chef')!.click();
    await attendre();
    http.expectOne('/api/v1/demandes-administratives/r1/transmission').flush({ ...d, statut: 'TRANSMISE' });
    await attendre();
    expect(texte(f)).toContain('au chef d’établissement');
  });

  it('accueil : un appel pour le censeur, message transmis dans sa messagerie', async () => {
    await session(http, 'SECRETARIAT');
    const f = TestBed.createComponent(AccueilPublicPage);
    f.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/accueil').flush([]);
    http.expectOne('/api/v1/secretariat/personnel').flush([{ utilisateurId: 'u6', nom: 'OUEDRAOGO', prenoms: 'Salif', fonctions: 'Censeur' }]);
    await attendre();
    bouton(f, 'Appel téléphonique')!.click();
    saisir(f, '#aNom', 'M. SAWADOGO');
    saisir(f, '#aObjet', 'Absence de son fils');
    saisir(f, '#aPersonne', 'u6');
    saisir(f, '#aMessage', 'Rappeler avant 17 h');
    bouton(f, 'Enregistrer')!.click();
    await attendre();
    const post = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/accueil');
    expect(post.request.body).toMatchObject({ canal: 'APPEL', nom: 'M. SAWADOGO', personneId: 'u6', message: 'Rappeler avant 17 h', transmettre: true });
    post.flush({
      id: 'p1', canal: 'APPEL', arrivee: '2026-10-14T09:00:00Z', depart: null, nom: 'M. SAWADOGO', telephone: null, qualite: 'PARENT',
      objet: 'Absence de son fils', service: null, personneId: 'u6', personne: 'OUEDRAOGO Salif', message: 'Rappeler avant 17 h',
      urgent: false, transmisLe: '2026-10-14T09:00:05Z',
    });
    await attendre();
    expect(texte(f)).toContain('Enregistré ; message transmis à OUEDRAOGO Salif.');
  });

  it('tableau d’affichage : le parent lit les documents affichés pour tous, sans « mes demandes »', async () => {
    await session(http, 'PARENT');
    const f = TestBed.createComponent(AffichagePage);
    f.detectChanges();
    http.expectOne('/api/v1/affichage').flush([
      { id: 'd1', type: 'NOTE_SERVICE', typeLibelle: 'Note de service', numero: '2026-0001/NS', titre: 'Horaires des examens', signeLe: '2026-10-14T09:00:00Z', afficheAu: '2026-10-31' },
    ]);
    await attendre();
    const t = texte(f);
    expect(t).toContain('Horaires des examens');
    expect(t).not.toContain('Mes demandes administratives');
    const creer = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:doc');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    bouton(f, 'Lire')!.click();
    await attendre();
    http.expectOne('/api/v1/documents-administratifs/d1/pdf').flush(new Blob(['%PDF']));
    await attendre();
    expect(creer).toHaveBeenCalled();
    vi.restoreAllMocks();
  });

  it('tableau d’affichage : l’enseignant suit ses demandes', async () => {
    await session(http, 'ENSEIGNANT');
    const f = TestBed.createComponent(AffichagePage);
    f.detectChanges();
    http.expectOne('/api/v1/affichage').flush([]);
    await attendre();
    http.expectOne('/api/v1/demandes-administratives/miennes').flush([
      { id: 'r1', numero: 'R-2026-0003', type: 'MUTATION', typeLibelle: 'Demande de mutation', demandeurId: 'u1', demandeur: 'KONE Awa',
        recueLe: '2026-10-01', objet: 'Mutation à Bobo-Dioulasso', du: null, au: null, statut: 'AVIS_FAVORABLE', avis: 'Bonne manière de servir',
        avisPar: 'Mathieu KABORE', avisLe: null, suite: null, courrierId: null, courrierNumero: null, fichierNom: null },
    ]);
    await attendre();
    expect(texte(f)).toContain('R-2026-0003');
    expect(texte(f)).toContain('avis favorable');
  });
});
