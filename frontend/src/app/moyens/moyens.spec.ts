import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { DotationVue, EtatVue, montant, quantite, RemonteeVue, VueBesoins } from './modeles-moyens';
import { BesoinsPilotagePage } from './pages/besoins-pilotage.page';
import { BesoinsPage } from './pages/besoins.page';
import { anneeScolaireCourante, DotationsPilotagePage } from './pages/dotations-pilotage.page';

async function sessionEtablissement(http: HttpTestingController, role: Role): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('64000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u9', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'KABORE', prenoms: 'Rose' });
  await connexion;
}

async function sessionDirection(http: HttpTestingController): Promise<void> {
  const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: null, etablissements: [], superAdmin: false,
    direction: { id: 'dp1', code: 'DP1', nom: 'DP du Kadiogo', chemin: 'Burkina Faso · MESFPT · DR du Centre · DP du Kadiogo', paysId: 'bf' } }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'SANOU', prenoms: 'Aline' });
  await connexion;
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
  const champ = (f.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;
  if (!champ) {
    throw new Error(`Champ ${selecteur} introuvable`);
  }
  champ.value = valeur;
  champ.dispatchEvent(new Event(champ instanceof HTMLSelectElement ? 'change' : 'input'));
  f.detectChanges();
}

function etat(autres: Partial<EtatVue> = {}): EtatVue {
  return {
    id: 'e1', anneeId: 'a1', annee: '2026-2027', annees: [{ id: 'a1', libelle: '2026-2027', active: true }], statut: 'BROUILLON',
    transmisLe: null, transmisPar: null, direction: 'DP du Kadiogo', renvoyeLe: null, motifRenvoi: null, observations: null,
    modifiable: true, peutTransmettre: false, domaines: ['GENERAL'],
    lignes: [{
      id: 'l1', categorie: 'PERSONNEL', domaine: 'GENERAL', designation: 'Professeur de mathématiques', unite: 'poste', quantite: 2,
      priorite: 1, justification: 'Deux classes sans professeur', proposePar: 'KABORE Rose', modifieeLe: '2026-10-10T08:00:00Z', peutModifier: true,
    }],
    ...autres,
  };
}

function dotation(autres: Partial<DotationVue> = {}): DotationVue {
  return {
    id: 'd1', reference: 'DOT-2026-00001', annee: '2026-2027',
    emettrice: { type: 'DIRECTION', id: 'dp1', nom: 'DP du Kadiogo', chemin: null },
    destinataire: { type: 'ETABLISSEMENT', id: 'etab-1', nom: 'Lycée technique', chemin: null },
    origineId: null, origineReference: null, objet: 'Dotation de rentrée', statut: 'ENVOYEE', creeeLe: '2026-10-10T08:00:00Z',
    envoyeeLe: '2026-10-11T08:00:00Z', envoyeePar: 'SANOU Aline', recueLe: null, accuseePar: null, observationsReception: null,
    valeur: null, peutModifier: false, peutAccuser: true, peutRepartir: false,
    lignes: [{
      id: 'x1', ligneOrigineId: null, categorie: 'MATIERE', designation: "Rond d'acier Ø 20", unite: 'barre', quantite: 60,
      valeurUnitaire: null, quantiteRecue: null, quantiteConforme: null, observation: null, repartie: 0, disponible: 60,
    }],
    repartitions: [],
    ...autres,
  };
}

describe('Besoins et dotations (v0.48)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('outils : quantités, montants, année scolaire courante', () => {
    expect(quantite(2.5)).toBe('2,5');
    expect(quantite(3)).toBe('3');
    expect(quantite(null)).toBe('—');
    expect(montant(7500000)).toBe('7 500 000 F');
    expect(anneeScolaireCourante(new Date(2026, 9, 14))).toBe('2026-2027');
    expect(anneeScolaireCourante(new Date(2027, 2, 1))).toBe('2026-2027');
  });

  it('censeur : exprime un besoin du domaine général', async () => {
    await sessionEtablissement(http, 'CENSEUR');
    const f = TestBed.createComponent(BesoinsPage);
    f.detectChanges();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/etat-besoins').flush(etat());
    await attendre();
    http.expectOne('/api/v1/dotations-recues').flush([]);
    await attendre();
    let t = texte(f);
    expect(t).toContain('Professeur de mathématiques');
    expect(t).toContain('En préparation');
    expect(t).not.toContain("Transmettre l'état des besoins");

    bouton(f, 'Ajouter un besoin').click();
    await attendre();
    const domaines = [...(f.nativeElement as HTMLElement).querySelectorAll('#fDomaine option')].map((o) => o.textContent?.trim());
    expect(domaines).toEqual(['Général (censeur)']);
    await saisir(f, '#fCategorie', 'INFRASTRUCTURE');
    await saisir(f, '#fDesignation', 'Salle de classe');
    await saisir(f, '#fQuantite', '2');
    await saisir(f, '#fJustification', 'Effectifs pléthoriques');
    bouton(f, 'Enregistrer').click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/etat-besoins/a1/lignes');
    expect(req.request.body).toEqual({
      categorie: 'INFRASTRUCTURE', domaine: 'GENERAL', designation: 'Salle de classe', unite: 'salle', quantite: 2, priorite: 2,
      justification: 'Effectifs pléthoriques',
    });
    req.flush(etat({ lignes: [...etat().lignes, { ...etat().lignes[0], id: 'l2', categorie: 'INFRASTRUCTURE', designation: 'Salle de classe', unite: 'salle' }] }));
    await attendre();
    t = texte(f);
    expect(t).toContain('Infrastructures (1)');
    expect(t).toContain('Salle de classe');
  });

  it('chef d’établissement : transmet, puis voit le renvoi de la direction', async () => {
    await sessionEtablissement(http, 'ADMIN_ECOLE');
    const f = TestBed.createComponent(BesoinsPage);
    f.detectChanges();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/etat-besoins').flush(etat({ peutTransmettre: true, domaines: ['GENERAL', 'TECHNIQUE'] }));
    await attendre();
    http.expectOne('/api/v1/dotations-recues').flush([]);
    await attendre();
    expect(texte(f)).toContain('Transmettre à DP du Kadiogo');
    await saisir(f, '#obsTransmission', 'Urgent');
    bouton(f, "Transmettre l'état des besoins").click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/etat-besoins/a1/transmettre');
    expect(req.request.body).toEqual({ observations: 'Urgent' });
    req.flush(etat({ statut: 'TRANSMIS', modifiable: false, transmisLe: '2026-10-14T08:00:00Z', transmisPar: 'ZONGO Paul',
      lignes: etat().lignes.map((l) => ({ ...l, peutModifier: false })) }));
    await attendre();
    let t = texte(f);
    expect(t).toContain('État des besoins 2026-2027 transmis à DP du Kadiogo.');
    expect(t).toContain('Transmis à la direction');
    expect(t).not.toContain('Ajouter un besoin');

    // Renvoyé : le motif s'affiche et l'état redevient modifiable
    void f.componentInstance['charger']();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/etat-besoins').flush(etat({ statut: 'RENVOYE', motifRenvoi: 'Précisez la section du tour', domaines: ['GENERAL', 'TECHNIQUE'] }));
    await attendre();
    t = texte(f);
    expect(t).toContain('Renvoyé par la direction pour correction : Précisez la section du tour');
    expect(t).toContain('Ajouter un besoin');
  });

  it('intendant : accuse réception d’une dotation avec une quantité non conforme', async () => {
    await sessionEtablissement(http, 'INTENDANT');
    const f = TestBed.createComponent(BesoinsPage);
    f.detectChanges();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/etat-besoins').flush(etat({ modifiable: true, domaines: [], lignes: [] }));
    await attendre();
    const resume = { id: 'd1', reference: 'DOT-2026-00001', annee: '2026-2027', emettrice: dotation().emettrice, destinataire: dotation().destinataire,
      origineReference: null, objet: 'Dotation de rentrée', statut: 'ENVOYEE' as const, lignes: 1, valeur: null, envoyeeLe: '2026-10-11T08:00:00Z', recueLe: null };
    http.expectOne('/api/v1/dotations-recues').flush([resume]);
    await attendre();
    expect(texte(f)).toContain('Dotations reçues (1 à accuser)');
    bouton(f, 'Dotations reçues').click();
    await attendre();
    http.expectOne('/api/v1/dotations-recues').flush([resume]);
    await attendre();
    bouton(f, 'DOT-2026-00001').click();
    await attendre();
    http.expectOne('/api/v1/dotations-recues/d1').flush(dotation());
    await attendre();
    expect(texte(f)).toContain('De DP du Kadiogo à Lycée technique');
    await saisir(f, 'input[aria-label="Quantité conforme : Rond d\'acier Ø 20"]', '58');
    await saisir(f, 'input[aria-label="Observation : Rond d\'acier Ø 20"]', '2 barres tordues');
    await saisir(f, '#obsReception', 'Reçu au magasin');
    bouton(f, 'Accuser réception').click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/dotations-recues/d1/accuser');
    expect(req.request.body).toEqual({
      observations: 'Reçu au magasin',
      lignes: [{ ligneId: 'x1', quantiteRecue: 60, quantiteConforme: 58, observation: '2 barres tordues' }],
    });
    req.flush(dotation({ statut: 'RECUE', peutAccuser: false, recueLe: '2026-10-14T08:00:00Z', accuseePar: 'KAFANDO Marc',
      lignes: [{ ...dotation().lignes[0], quantiteRecue: 60, quantiteConforme: 58, observation: '2 barres tordues' }] }));
    await attendre();
    http.expectOne('/api/v1/dotations-recues').flush([{ ...resume, statut: 'RECUE' }]);
    await attendre();
    const t = texte(f);
    expect(t).toContain('Réception de DOT-2026-00001 accusée.');
    expect(t).toContain('par KAFANDO Marc');
    expect(t).toContain('2 barres tordues');
  });

  it('direction provinciale : ouvre un état reçu, arbitre, puis transmet', async () => {
    await sessionDirection(http);
    const f = TestBed.createComponent(BesoinsPilotagePage);
    f.detectChanges();
    await attendre();
    const vue: VueBesoins = {
      perimetre: { type: 'DIRECTION', id: 'dp1', nom: 'DP du Kadiogo', chemin: 'Burkina Faso · MESFPT · DR du Centre · DP du Kadiogo' },
      annee: '2026-2027', annees: ['2026-2027'], destination: 'DR du Centre', transmission: null, peutArbitrer: true, peutTransmettre: true,
      etablissements: [
        { id: 'a', code: 'lta', nom: 'Lycée A', direction: 'DP du Kadiogo', statut: 'RECU', etape: null, remonteeId: 'r1', transmiseLe: '2026-10-12T08:00:00Z', lignes: 1, motifRenvoi: null },
        { id: 'b', code: 'ltb', nom: 'Lycée B', direction: 'DP du Kadiogo', statut: 'NON_TRANSMIS', etape: null, remonteeId: null, transmiseLe: null, lignes: 0, motifRenvoi: null },
      ],
      directions: [],
      consolidation: [{ categorie: 'EQUIPEMENT_OUTILS', designation: 'Tour parallèle', unite: 'pièce', demandee: 3, retenue: 3, etablissements: 1, priorite: 1 }],
    };
    http.expectOne((r) => r.url === '/api/v1/pilotage/besoins' && !r.params.keys().length).flush(vue);
    await attendre();
    let t = texte(f);
    expect(t).toContain('1 / 2états reçus');
    expect(t).toContain('Non transmis');
    // Onglets d'une direction : Pilotage, Adoption, Besoins, Dotations
    const onglets = [...(f.nativeElement as HTMLElement).querySelectorAll('app-plateforme-nav a')].map((a) => a.textContent?.trim());
    expect(onglets).toEqual(['Pilotage', 'Adoption', 'Besoins', 'Dotations']);

    bouton(f, 'Voir').click();
    await attendre();
    const remontee: RemonteeVue = {
      id: 'r1', etablissementId: 'a', etablissement: 'Lycée A', direction: 'DP du Kadiogo', annee: '2026-2027', statut: 'RECU',
      transmiseLe: '2026-10-12T08:00:00Z', transmisePar: 'ZONGO Paul', observations: 'Urgent', motifRenvoi: null, niveau: 'DP du Kadiogo',
      peutArbitrer: true, peutRenvoyer: true,
      lignes: [{ id: 'lr1', categorie: 'EQUIPEMENT_OUTILS', domaine: 'TECHNIQUE', designation: 'Tour parallèle', unite: 'pièce', quantite: 3, priorite: 1,
        justification: null, arbitrages: [], maximum: 3, retenue: 3, observation: null, arbitreIci: false }],
    };
    http.expectOne((r) => r.url === '/api/v1/pilotage/besoins/remontees/r1').flush(remontee);
    await attendre();
    expect(texte(f)).toContain("Observations de l'établissement : Urgent");
    await saisir(f, 'input[aria-label="Quantité retenue : Tour parallèle"]', '2');
    await saisir(f, 'input[aria-label="Observation : Tour parallèle"]', 'Un tour réparable');
    bouton(f, "Enregistrer l'arbitrage").click();
    await attendre();
    const arb = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/pilotage/besoins/remontees/r1/arbitrages');
    expect(arb.request.body).toEqual({ lignes: [{ ligneId: 'lr1', quantiteRetenue: 2, observation: 'Un tour réparable' }] });
    arb.flush({ ...remontee, lignes: [{ ...remontee.lignes[0], retenue: 2, observation: 'Un tour réparable', arbitreIci: true }] });
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/pilotage/besoins').flush({ ...vue, consolidation: [{ ...vue.consolidation[0], retenue: 2 }] });
    await attendre();
    expect(texte(f)).toContain('Arbitrage de Lycée A enregistré.');

    bouton(f, 'Transmettre les besoins 2026-2027').click();
    await attendre();
    const tr = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/pilotage/besoins/transmettre');
    expect(tr.request.body).toEqual({ annee: '2026-2027', observations: null });
    tr.flush({ ...vue, peutArbitrer: false, peutTransmettre: false,
      transmission: { statut: 'TRANSMISE', transmiseLe: '2026-10-14T08:00:00Z', transmisePar: 'SANOU Aline', observations: null, renvoyeeLe: null, motifRenvoi: null } });
    await attendre();
    t = texte(f);
    expect(t).toContain('Besoins 2026-2027 transmis à DR du Centre.');
    expect(t).not.toContain('Transmettre les besoins');
  });

  it('direction provinciale : répartit une dotation reçue vers un établissement, puis l’envoie', async () => {
    await sessionDirection(http);
    const f = TestBed.createComponent(DotationsPilotagePage);
    f.detectChanges();
    await attendre();
    const recue = dotation({
      id: 'dr1', reference: 'DOT-2026-00002', emettrice: { type: 'DIRECTION', id: 'dr', nom: 'DR du Centre', chemin: null },
      destinataire: { type: 'DIRECTION', id: 'dp1', nom: 'DP du Kadiogo', chemin: null }, statut: 'RECUE', peutAccuser: false, peutRepartir: true,
      lignes: [{ ...dotation().lignes[0], id: 'y1', quantite: 60, quantiteRecue: 60, quantiteConforme: 60, disponible: 60 }],
    });
    http.expectOne((r) => r.url === '/api/v1/pilotage/dotations').flush({
      instance: { type: 'DIRECTION', id: 'dp1', nom: 'DP du Kadiogo', chemin: null }, annee: null, annees: ['2026-2027'],
      recues: [{ id: 'dr1', reference: 'DOT-2026-00002', annee: '2026-2027', emettrice: recue.emettrice, destinataire: recue.destinataire,
        origineReference: null, objet: 'Dotation de rentrée', statut: 'RECUE', lignes: 1, valeur: null, envoyeeLe: null, recueLe: null }],
      emises: [], destinataires: [{ type: 'ETABLISSEMENT', id: 'etab-1', nom: 'Lycée technique', chemin: null }], peutDoter: true,
    });
    await attendre();
    bouton(f, 'DOT-2026-00002').click();
    await attendre();
    http.expectOne('/api/v1/pilotage/dotations/dr1').flush(recue);
    await attendre();
    expect(texte(f)).toContain('Disponible');
    bouton(f, 'Répartir').click();
    await attendre();
    await saisir(f, 'input[aria-label="Quantité : Rond d\'acier Ø 20 (barre)"]', '40');
    bouton(f, 'Préparer la dotation').click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/pilotage/dotations');
    expect(req.request.body).toEqual({
      annee: '2026-2027', destinataireType: 'ETABLISSEMENT', destinataireId: 'etab-1', origineId: 'dr1', objet: 'Dotation de rentrée',
      lignes: [{ ligneOrigineId: 'y1', categorie: null, designation: null, unite: null, quantite: 40, valeurUnitaire: null }],
    });
    const preparee = dotation({ id: 'd9', reference: 'DOT-2026-00003', statut: 'BROUILLON', peutAccuser: false, peutModifier: true, envoyeeLe: null,
      origineId: 'dr1', origineReference: 'DOT-2026-00002' });
    req.flush(preparee);
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/pilotage/dotations').flush({
      instance: { type: 'DIRECTION', id: 'dp1', nom: 'DP du Kadiogo', chemin: null }, annee: null, annees: ['2026-2027'], recues: [],
      emises: [], destinataires: [], peutDoter: true,
    });
    await attendre();
    expect(texte(f)).toContain('DOT-2026-00003 préparée');
    bouton(f, 'Envoyer à Lycée technique').click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/pilotage/dotations/d9/envoyer')
      .flush({ ...preparee, statut: 'ENVOYEE', peutModifier: false });
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/pilotage/dotations').flush({
      instance: { type: 'DIRECTION', id: 'dp1', nom: 'DP du Kadiogo', chemin: null }, annee: null, annees: ['2026-2027'], recues: [],
      emises: [], destinataires: [], peutDoter: true,
    });
    await attendre();
    expect(texte(f)).toContain('DOT-2026-00003 envoyée à Lycée technique.');
  });
});
