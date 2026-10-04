import { HttpTestingController } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { ExportBoutonsComponent, nomDuFichier } from './export-boutons.component';
import { BesoinVue, CampagneVue, erreurReception, LigneLivraisonVue, LivraisonVue } from './modeles-besoins';
import { BesoinPage, erreurLigneBesoin } from './pages/besoin.page';
import { CampagnePage } from './pages/campagne.page';
import { bilanRepartition, LivraisonPage } from './pages/livraison.page';

async function session(http: HttpTestingController, role: Role): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('64000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u9', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'OUEDRAOGO', prenoms: 'Salif' });
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
  await attendre();
  const champ = (f.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement | HTMLSelectElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event(champ instanceof HTMLSelectElement ? 'change' : 'input'));
  f.detectChanges();
}

const FIL = {
  id: 'fil', code: 'FIL', designation: 'Fil rigide 2,5 mm²', nature: 'MATIERE_OEUVRE' as const, unite: 'rouleau', filiereId: null,
  filiereCode: null, specifications: 'Cuivre, rouleau de 100 m', normes: 'NF C 32-201', prixReference: 15000, prixModifieLe: null,
  actif: true, photo: false,
};

function besoin(autres: Partial<BesoinVue> = {}): BesoinVue {
  return {
    id: 'b1', campagneId: 'c1', campagne: 'Besoins 2026-2027', type: 'ANNEE_EN_COURS', statutCampagne: 'OUVERTE', dateLimite: '2026-11-15',
    atelierId: 'at1', atelierCode: 'ELEC', atelierNom: 'Atelier d’électricité', statut: 'BROUILLON', transmisLe: null, transmisPar: null,
    valideLe: null, commentaire: null, lignes: [], montant: 0,
    droits: { proposer: true, modifier: true, transmettre: true, arbitrer: false },
    ...autres,
  };
}

const LIGNE_FIL = {
  articleId: 'fil', code: 'FIL', designation: 'Fil rigide 2,5 mm²', nature: 'MATIERE_OEUVRE' as const, unite: 'rouleau',
  specifications: null, normes: 'NF C 32-201', photo: false, quantiteDemandee: 20, justification: 'TP câblage', proposePar: 'SANOU Paul',
  quantiteRetenue: null, prixUnitaire: 15000, montant: 300000,
};

describe('Besoins et commandes', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('contrôle les saisies de besoins, de réception et de répartition', () => {
    expect(erreurLigneBesoin(undefined, '2')).toContain('Choisissez');
    expect(erreurLigneBesoin({ ...FIL, nature: 'EQUIPEMENT' }, '2,5')).toContain('unité');
    expect(erreurLigneBesoin(FIL, '2,5')).toBeNull();
    expect(erreurReception({ designation: 'Fil', recue: 15, conforme: 12, motif: '', reste: 15 })).toContain('pourquoi');
    expect(erreurReception({ designation: 'Fil', recue: 15, conforme: 16, motif: '', reste: 15 })).toContain('dépasse');
    expect(erreurReception({ designation: 'Fil', recue: 15, conforme: null, motif: '', reste: 10 })).toContain('10 restant(s)');
    expect(erreurReception({ designation: 'Fil', recue: 15, conforme: 12, motif: 'Hors norme', reste: 15 })).toBeNull();
    const ligne: LigneLivraisonVue = {
      articleId: 'fil', code: 'FIL', designation: 'Fil', nature: 'MATIERE_OEUVRE', unite: 'rouleau', recue: 15, conforme: 12,
      motifNonConformite: 'Hors norme', repartition: [],
    };
    expect(bilanRepartition(ligne, { 'fil|a': '10', 'fil|b': '2' }, ['a', 'b'])).toEqual({ total: 12, erreur: null });
    expect(bilanRepartition(ligne, { 'fil|a': '10', 'fil|b': '3' }, ['a', 'b']).erreur).toContain('13 répartis pour 12 conformes');
    expect(nomDuFichier("attachment; filename=\"x.pdf\"; filename*=UTF-8''%C3%A9tat-des-besoins.pdf", 'export.pdf')).toBe('état-des-besoins.pdf');
    expect(nomDuFichier('attachment; filename="stock.xlsx"', 'export.xlsx')).toBe('stock.xlsx');
    expect(nomDuFichier(null, 'export.xlsx')).toBe('export.xlsx');
  });

  it('boutons d’export : téléchargent l’Excel ou le PDF de la page', async () => {
    @Component({ imports: [ExportBoutonsComponent], template: `<app-export chemin="/catalogue" nom="catalogue" libelle="le catalogue" />` })
    class Hote {}
    const creer = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    const clic = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    try {
      const f = TestBed.createComponent(Hote);
      bouton(f, 'Excel').click();
      await attendre();
      expect(bouton(f, 'Préparation').disabled).toBe(true);
      const xlsx = http.expectOne((r) => r.url === '/api/v1/catalogue/export');
      expect(xlsx.request.params.get('format')).toBe('xlsx');
      expect(xlsx.request.responseType).toBe('blob');
      xlsx.flush(new Blob(['PK']), { headers: { 'Content-Disposition': 'attachment; filename="catalogue-2026-10-04.xlsx"' } });
      await attendre();
      expect(creer).toHaveBeenCalled();
      expect(clic).toHaveBeenCalled();
      bouton(f, 'PDF').click();
      await attendre();
      const pdf = http.expectOne((r) => r.url === '/api/v1/catalogue/export');
      expect(pdf.request.params.get('format')).toBe('pdf');
      pdf.flush(new Blob([JSON.stringify({ detail: 'Accès refusé' })]), { status: 403, statusText: 'Forbidden' });
      await attendre();
      expect(texte(f)).toContain('Accès refusé');
    } finally {
      creer.mockRestore();
      clic.mockRestore();
    }
  });

  it('enseignant technique : propose un article puis le responsable transmet', async () => {
    await session(http, 'ENSEIGNANT');
    const f = TestBed.createComponent(BesoinPage);
    f.componentRef.setInput('besoinId', 'b1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/besoins-ateliers/b1').flush(besoin());
    await attendre();
    expect(texte(f)).toContain('Aucun article demandé');
    expect(texte(f)).not.toContain('Transmettre au chef des travaux');
    expect(bouton(f, 'Excel')).toBeTruthy();
    bouton(f, 'Ajouter un article').click();
    await attendre();
    http.expectOne('/api/v1/catalogue').flush([FIL, { ...FIL, id: 'vieux', designation: 'Ancien article', actif: false }]);
    await attendre();
    expect(texte(f)).not.toContain('Ancien article');
    await saisir(f, '#bl-article', 'fil');
    expect(texte(f)).toContain('normes : NF C 32-201');
    await saisir(f, '#bl-quantite', '20');
    await saisir(f, '#bl-justif', 'TP câblage');
    bouton(f, 'Enregistrer').click();
    await attendre();
    const req = http.expectOne('/api/v1/besoins-ateliers/b1/lignes/fil');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ quantite: 20, justification: 'TP câblage' });
    req.flush(besoin({ lignes: [LIGNE_FIL], montant: 300000 }));
    await attendre();
    expect(texte(f)).toContain('300 000 FCFA');
    bouton(f, 'Transmettre au chef des travaux').click();
    await attendre();
    http.expectOne('/api/v1/besoins-ateliers/b1/transmission').flush(besoin({
      lignes: [LIGNE_FIL], montant: 300000, statut: 'TRANSMIS', transmisLe: '2026-10-04T08:00:00Z', transmisPar: 'SANOU Paul',
      droits: { proposer: false, modifier: false, transmettre: false, arbitrer: false },
    }));
    await attendre();
    const t = texte(f);
    expect(t).toContain('Besoins transmis au chef des travaux.');
    expect(t).toContain('en attente de sa validation');
    expect(t).not.toContain('Retirer');
  });

  it('chef des travaux : arbitre puis valide les besoins d’un atelier', async () => {
    await session(http, 'CHEF_TRAVAUX');
    const f = TestBed.createComponent(BesoinPage);
    f.componentRef.setInput('besoinId', 'b1');
    f.detectChanges();
    await attendre();
    const droits = { proposer: false, modifier: false, transmettre: false, arbitrer: true };
    http.expectOne('/api/v1/besoins-ateliers/b1').flush(besoin({ statut: 'TRANSMIS', lignes: [LIGNE_FIL], montant: 300000, droits }));
    await attendre();
    await saisir(f, 'input.court', '15');
    bouton(f, 'Valider les besoins').click();
    await attendre();
    const arb = http.expectOne('/api/v1/besoins-ateliers/b1/arbitrage');
    expect(arb.request.body).toEqual({ lignes: [{ articleId: 'fil', quantiteRetenue: 15 }] });
    arb.flush(besoin({ statut: 'TRANSMIS', lignes: [{ ...LIGNE_FIL, quantiteRetenue: 15, montant: 225000 }], montant: 225000, droits }));
    await attendre();
    http.expectOne('/api/v1/besoins-ateliers/b1/validation').flush(besoin({
      statut: 'VALIDE', valideLe: '2026-10-04T09:00:00Z', lignes: [{ ...LIGNE_FIL, quantiteRetenue: 15, montant: 225000 }], montant: 225000,
      droits: { proposer: false, modifier: false, transmettre: false, arbitrer: false },
    }));
    await attendre();
    const t = texte(f);
    expect(t).toContain('Besoins validés');
    expect(t).toContain('225 000 FCFA');
    expect(t).toContain('Validé');
  });

  it('intendant : enregistre la commande avec ce qui reste à commander', async () => {
    await session(http, 'INTENDANT');
    const navigation = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const c: CampagneVue = {
      id: 'c1', anneeId: 'a1', type: 'ANNEE_EN_COURS', libelle: 'Besoins 2026-2027', dateLimite: null, statut: 'TRANSMISE',
      observations: null, ouverteLe: '2026-10-01T08:00:00Z', transmiseLe: '2026-10-04T08:00:00Z', closeLe: null,
      besoins: [{ id: 'b1', atelierId: 'at1', atelierCode: 'ELEC', atelierNom: 'Atelier d’électricité', filieres: [{ id: 'f3', code: 'F3', libelle: 'Électrotechnique' }],
        statut: 'VALIDE', lignes: 1, montant: 225000, transmisLe: null, responsable: 'SANOU Paul' }],
      filieres: [{ filiere: 'F3 Électrotechnique', montant: 225000, lignes: [] }],
      totaux: [{ articleId: 'fil', code: 'FIL', designation: 'Fil rigide 2,5 mm²', nature: 'MATIERE_OEUVRE', unite: 'rouleau', quantite: 15,
        prixUnitaire: 15000, montant: 225000, commandee: 5, livree: 0 }],
      montant: 225000, commandes: [], gerer: false, commander: true,
    };
    const f = TestBed.createComponent(CampagnePage);
    f.componentRef.setInput('campagneId', 'c1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/campagnes-besoins/c1').flush(c);
    await attendre();
    expect(texte(f)).toContain('État des besoins validés par filière');
    expect(texte(f)).not.toContain('Transmettre à la direction régionale');
    bouton(f, 'Enregistrer une commande').click();
    f.detectChanges();
    const quantiteCommande = (f.nativeElement as HTMLElement).querySelector('input.court') as HTMLInputElement;
    expect(quantiteCommande.value).toBe('10');
    await saisir(f, '#co-ref', 'bc-2026-014');
    await saisir(f, '#co-four', 'Quincaillerie du Faso');
    bouton(f, 'Enregistrer la commande').click();
    await attendre();
    const req = http.expectOne('/api/v1/campagnes-besoins/c1/commandes');
    expect(req.request.body.reference).toBe('bc-2026-014');
    expect(req.request.body.passeePar).toBe('DIRECTION_REGIONALE');
    expect(req.request.body.lignes).toEqual([{ articleId: 'fil', quantite: 10, prixUnitaire: 15000 }]);
    req.flush({ id: 'co1' });
    await attendre();
    expect(navigation).toHaveBeenCalledWith(['/ateliers/commandes', 'co1']);
  });

  it('chef des travaux : répartit la livraison entre les ateliers', async () => {
    await session(http, 'CHEF_TRAVAUX');
    const liv: LivraisonVue = {
      id: 'l1', commandeId: 'co1', commandeReference: 'BC-2026-014', fournisseur: 'Quincaillerie du Faso', campagneId: 'c1',
      dateReception: '2026-10-04', bonLivraison: 'BL-778', observations: null, statut: 'A_REPARTIR', recuePar: 'OUEDRAOGO Salif',
      repartieLe: null,
      lignes: [{ articleId: 'fil', code: 'FIL', designation: 'Fil rigide 2,5 mm²', nature: 'MATIERE_OEUVRE', unite: 'rouleau', recue: 15,
        conforme: 12, motifNonConformite: 'Section non conforme', repartition: [
          { atelierId: 'a1', atelierCode: 'BOB', retenu: 5, proposee: 4, quantite: null },
          { atelierId: 'a2', atelierCode: 'ELEC', retenu: 10, proposee: 8, quantite: null },
        ] }],
      ateliers: [{ id: 'a1', code: 'BOB', nom: 'Bobinage' }, { id: 'a2', code: 'ELEC', nom: 'Électricité' }],
      gerer: true,
    };
    const f = TestBed.createComponent(LivraisonPage);
    f.componentRef.setInput('livraisonId', 'l1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/livraisons/l1').flush(liv);
    await attendre();
    const champs = () => [...(f.nativeElement as HTMLElement).querySelectorAll('input.court')] as HTMLInputElement[];
    f.detectChanges();
    expect(champs().map((c) => c.value)).toEqual(['4', '8']);
    expect(texte(f)).toContain('Section non conforme');
    champs()[0].value = '5';
    champs()[0].dispatchEvent(new Event('input'));
    expect(texte(f)).toContain('13 répartis pour 12 conformes');
    expect(bouton(f, 'Valider et mettre à jour').disabled).toBe(true);
    champs()[0].value = '2';
    champs()[0].dispatchEvent(new Event('input'));
    champs()[1].value = '10';
    champs()[1].dispatchEvent(new Event('input'));
    bouton(f, 'Valider et mettre à jour').click();
    await attendre();
    const rep = http.expectOne('/api/v1/livraisons/l1/repartition');
    expect(rep.request.body.lignes).toEqual([
      { articleId: 'fil', atelierId: 'a1', quantite: 2 },
      { articleId: 'fil', atelierId: 'a2', quantite: 10 },
    ]);
    const enregistree = { ...liv, lignes: [{ ...liv.lignes[0], repartition: [
      { ...liv.lignes[0].repartition[0], quantite: 2 }, { ...liv.lignes[0].repartition[1], quantite: 10 }] }] };
    rep.flush(enregistree);
    await attendre();
    http.expectOne('/api/v1/livraisons/l1/repartition/validation')
      .flush({ ...enregistree, statut: 'REPARTIE', repartieLe: '2026-10-04T10:00:00Z', gerer: false });
    await attendre();
    const t = texte(f);
    expect(t).toContain('Répartition validée');
    expect(t).toContain('Répartie');
    expect(champs().length).toBe(0);
  });
});
