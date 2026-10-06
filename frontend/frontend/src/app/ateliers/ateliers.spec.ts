import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import {
  alertesEnTexte,
  AlertesAtelier,
  AtelierVue,
  dureeMandat,
  EquipementVue,
  InventaireVue,
  lireQuantite,
  quantite,
} from './modeles-ateliers';
import { AtelierPage } from './pages/atelier.page';
import { AteliersPage, erreurAtelier } from './pages/ateliers.page';
import { erreurArticle } from './pages/catalogue.page';
import { InventairePage } from './pages/inventaire.page';

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
  const champ = (f.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement | HTMLTextAreaElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event('input'));
  f.detectChanges();
}

const SANS_ALERTE: AlertesAtelier = {
  sansResponsable: false, mandatAEcheance: false, mandatEchu: false, equipementsEnPanne: 0, equipementsManquants: 0,
  articlesSousSeuil: 0, inventaireEnCours: false, dernierInventaire: '2026-03-01', inventaireEnRetard: false,
};

function atelier(autres: Partial<AtelierVue> = {}): AtelierVue {
  return {
    id: 'at1', code: 'ELEC', nom: 'Atelier d’électricité', emplacement: 'Bâtiment B', postes: 24, ouvert: true,
    observations: null, filieres: [{ id: 'f3', code: 'F3', libelle: 'Électrotechnique' }], responsable: null, historique: [],
    equipements: 1, articles: 1, alertes: SANS_ALERTE, droits: { gerer: true, responsable: false, signaler: true },
    ...autres,
  };
}

const PERCEUSE: EquipementVue = {
  id: 'e1', atelierId: 'at1', articleId: null, designation: 'Perceuse à colonne', numeroInventaire: 'ELEC-2026-001',
  marque: 'Bosch', numeroSerie: null, dateAcquisition: null, valeur: null, etat: 'BON', observations: null, panneOuverte: null,
};

describe('Ateliers', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('contrôle les saisies et met les alertes en phrases', () => {
    expect(lireQuantite('12,5')).toBe(12.5);
    expect(lireQuantite('0')).toBeNull();
    expect(lireQuantite('0', true)).toBe(0);
    expect(lireQuantite('1.234')).toBeNull();
    expect(quantite(1250.5)).toBe('1 250,5');
    expect(dureeMandat(24)).toBe('2 ans');
    expect(dureeMandat(18)).toBe('18 mois');
    expect(dureeMandat(null)).toBe('sans limite');
    expect(erreurAtelier({ code: 'ELEC', nom: 'Électricité', postes: '', filieres: [] })).toContain('filière');
    expect(erreurAtelier({ code: 'EL EC', nom: 'x', postes: '', filieres: ['f'] })).toContain('Code');
    expect(erreurArticle({ code: 'FIL', designation: 'Fil', unite: '', prix: '' })).toContain('unité');
    expect(erreurArticle({ code: 'FIL', designation: 'Fil', unite: 'rouleau', prix: '15 000' })).toBeNull();
    expect(alertesEnTexte({ ...SANS_ALERTE, sansResponsable: true, equipementsEnPanne: 2, inventaireEnRetard: true, dernierInventaire: null }))
      .toEqual(['Sans responsable', '2 équipement(s) en panne', 'Jamais inventorié']);
  });

  it('chef des travaux : liste des ateliers avec ce qui demande attention', async () => {
    await session(http, 'CHEF_TRAVAUX');
    const f = TestBed.createComponent(AteliersPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/ateliers').flush([
      atelier({ alertes: { ...SANS_ALERTE, sansResponsable: true, articlesSousSeuil: 1 } }),
      atelier({ id: 'at2', code: 'BAT', nom: 'Atelier de maçonnerie', alertes: SANS_ALERTE,
        responsable: { id: 'm1', engagementId: 'g2', enseignant: 'SOME Ali', debut: '2025-10-01', finPrevue: '2027-10-01', fin: null, motifFin: null, echeanceProche: false, echu: false } }),
    ]);
    http.expectOne('/api/v1/parametres/ateliers').flush({ dureeMandatMois: 24, frequenceInventaire: 'SEMESTRIELLE' });
    await attendre();
    http.expectOne('/api/v1/filieres').flush([{ id: 'f3', code: 'F3', libelle: 'Électrotechnique' }]);
    await attendre();
    const t = texte(f);
    expect(t).toContain('ELEC · Atelier d’électricité');
    expect(t).toContain('Sans responsable');
    expect(t).toContain('1 matière(s) sous le seuil');
    expect(t).toContain('SOME Ali (jusqu\'au 01/10/2027)');
    expect(t).toContain('Durée du mandat d\'un responsable d\'atelier : 2 ans');
    const tuiles = [...(f.nativeElement as HTMLElement).querySelectorAll('.chiffres strong')].map((x) => x.textContent?.trim());
    expect(tuiles).toEqual(['2', '1', '0', '0', '1']);
  });

  it('chef des travaux : désigne le responsable parmi les enseignants techniques', async () => {
    await session(http, 'CHEF_TRAVAUX');
    const f = TestBed.createComponent(AtelierPage);
    f.componentRef.setInput('atelierId', 'at1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/ateliers/at1').flush(atelier({ alertes: { ...SANS_ALERTE, sansResponsable: true } }));
    await attendre();
    expect(texte(f)).toContain('Aucun responsable');
    bouton(f, 'Désigner le responsable').click();
    await attendre();
    http.expectOne('/api/v1/ateliers/at1/candidats').flush([
      { engagementId: 'g1', enseignant: 'SANOU Paul', matieres: ['Électrotechnique (2nde F3)'] },
    ]);
    await attendre();
    expect(texte(f)).toContain('Électrotechnique (2nde F3)');
    expect(bouton(f, 'Désigner').disabled).toBe(true);
    const radio = (f.nativeElement as HTMLElement).querySelector('input[type=radio]') as HTMLInputElement;
    radio.click();
    radio.dispatchEvent(new Event('change'));
    f.detectChanges();
    bouton(f, 'Désigner').click();
    await attendre();
    const req = http.expectOne((r) => r.url === '/api/v1/ateliers/at1/responsable');
    expect(req.request.body.engagementId).toBe('g1');
    expect(req.request.body.finPrevue).toBeNull();
    req.flush(atelier({ responsable: { id: 'm1', engagementId: 'g1', enseignant: 'SANOU Paul', debut: '2026-10-04', finPrevue: '2028-10-04', fin: null, motifFin: null, echeanceProche: false, echu: false } }));
    await attendre();
    const t = texte(f);
    expect(t).toContain('SANOU Paul est désigné responsable de l\'atelier.');
    expect(t).toContain('mandat jusqu\'au 04/10/2028');
  });

  it('enseignant de l’atelier : signale une panne', async () => {
    await session(http, 'ENSEIGNANT');
    const f = TestBed.createComponent(AtelierPage);
    f.componentRef.setInput('atelierId', 'at1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/ateliers/at1').flush(atelier({ droits: { gerer: false, responsable: false, signaler: true } }));
    await attendre();
    bouton(f, 'Équipements').click();
    await attendre();
    http.expectOne('/api/v1/ateliers/at1/equipements').flush([PERCEUSE]);
    await attendre();
    expect(texte(f)).toContain('ELEC-2026-001');
    expect(texte(f)).not.toContain('Ajouter');
    bouton(f, 'Signaler une panne').click();
    await saisir(f, 'textarea', 'Ne démarre plus');
    bouton(f, 'Signaler').click();
    await attendre();
    const req = http.expectOne('/api/v1/equipements/e1/pannes');
    expect(req.request.body).toEqual({ description: 'Ne démarre plus' });
    req.flush({ id: 'p1', equipementId: 'e1', description: 'Ne démarre plus', signaleePar: 'TRAORE Ali', signaleeLe: '2026-10-04T08:00:00Z', statut: 'OUVERTE', intervention: null, cout: null, clotureePar: null, clotureeLe: null });
    await attendre();
    http.expectOne('/api/v1/ateliers/at1').flush(atelier({ droits: { gerer: false, responsable: false, signaler: true }, alertes: { ...SANS_ALERTE, equipementsEnPanne: 1 } }));
    await attendre();
    const t = texte(f);
    expect(t).toContain('Panne signalée : Perceuse à colonne (ELEC-2026-001).');
    expect(t).toContain('En panne');
    expect(t).toContain('par TRAORE Ali : Ne démarre plus');
  });

  it('responsable : saisit puis clôt l’inventaire', async () => {
    await session(http, 'ENSEIGNANT');
    const inv: InventaireVue = {
      id: 'i1', atelierId: 'at1', atelierCode: 'ELEC', atelierNom: 'Atelier d’électricité', libelle: '1er semestre 2026-2027',
      statut: 'EN_COURS', ouvertLe: '2026-10-04T08:00:00Z', ouvertPar: 'SANOU Paul', closLe: null, closPar: null, observations: null,
      matieres: [{ articleId: 'fil', code: 'FIL', designation: 'Fil rigide 2,5 mm²', unite: 'rouleau', quantiteTheorique: 7, quantiteConstatee: null, ecart: null }],
      equipements: [{ equipementId: 'e1', designation: 'Perceuse à colonne', numeroInventaire: 'ELEC-2026-001', etatTheorique: 'BON', etatConstate: null, observation: null }],
      restantes: 2, modifiable: true,
    };
    const f = TestBed.createComponent(InventairePage);
    f.componentRef.setInput('inventaireId', 'i1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/inventaires/i1').flush(inv);
    await attendre();
    expect(texte(f)).toContain('2 ligne(s) restent à renseigner');
    expect(bouton(f, 'Clore l\'inventaire').disabled).toBe(true);
    await saisir(f, 'input.qte', '6,5');
    expect(texte(f)).toContain('-0,5');
    bouton(f, 'Tous conformes').click();
    f.detectChanges();
    expect(bouton(f, 'Clore l\'inventaire').disabled).toBe(false);
    bouton(f, 'Clore l\'inventaire').click();
    await attendre();
    const saisie = http.expectOne('/api/v1/inventaires/i1/lignes');
    expect(saisie.request.body.matieres).toEqual([{ articleId: 'fil', quantiteConstatee: 6.5 }]);
    expect(saisie.request.body.equipements).toEqual([{ equipementId: 'e1', etatConstate: 'BON', observation: null }]);
    const rempli = { ...inv, restantes: 0,
      matieres: [{ ...inv.matieres[0], quantiteConstatee: 6.5, ecart: -0.5 }],
      equipements: [{ ...inv.equipements[0], etatConstate: 'BON' as const }] };
    saisie.flush(rempli);
    await attendre();
    http.expectOne('/api/v1/inventaires/i1/cloture').flush({ ...rempli, statut: 'CLOS', closLe: '2026-10-04T09:00:00Z', modifiable: false });
    await attendre();
    const t = texte(f);
    expect(t).toContain('Inventaire clos : le stock et l’état des équipements sont corrigés.');
    expect(t).toContain('Clos');
    expect((f.nativeElement as HTMLElement).querySelector('input.qte')).toBeNull();
  });
});
