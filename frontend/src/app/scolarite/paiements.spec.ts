import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { erreurVirement, PREUVE_MAX } from '../parent/virement.component';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { ParametresPaiement, VirementVue } from './modeles-scolarite';
import { basculerMoyen, MoyensPaiementPage } from './pages/moyens-paiement.page';
import { VirementsPage } from './pages/virements.page';

async function session(http: HttpTestingController, role: Role): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('70000003', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u7', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'SOME', prenoms: 'Issa' });
  await connexion;
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement | undefined {
  f.detectChanges();
  return [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((x) => x.textContent?.trim().startsWith(libelle));
}

const PARAMETRES: ParametresPaiement = {
  moyensGuichet: ['ESPECES', 'ORANGE_MONEY', 'MOOV_MONEY', 'TELECEL_MONEY', 'VIREMENT', 'CHEQUE'],
  mobileMoneyParents: true,
  operateurs: ['ORANGE_MONEY', 'MOOV_MONEY', 'TELECEL_MONEY'],
  virementParents: false,
  consigneVirement: null,
  comptes: [],
  mobileMoneyDisponible: false,
};

const VIREMENT: VirementVue = {
  id: 'v1', inscriptionId: 'i1', eleve: 'Awa OUEDRAOGO', matricule: '2026-00012', classe: '2nde F3 A', compteId: 'cb1',
  compte: 'Coris Bank · BF0001', montant: 20_000, dateVirement: '2026-10-06', reference: 'VIR-2026-001', donneurOrdre: 'OUEDRAOGO Mariam',
  preuve: true, statut: 'EN_ATTENTE', montantValide: null, motifRefus: null, paiementId: null, recuNumero: null,
  declareLe: '2026-10-07T08:00:00Z', traiteLe: null,
};

describe('Moyens de paiement et virements (v0.38)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => {
    vi.restoreAllMocks();
    http.verify();
  });

  it('contrôles : liste des moyens dans l’ordre usuel, déclaration de virement', () => {
    expect(basculerMoyen(['ESPECES', 'CHEQUE'], 'VIREMENT')).toEqual(['ESPECES', 'VIREMENT', 'CHEQUE']);
    expect(basculerMoyen(['ESPECES', 'CHEQUE'], 'CHEQUE')).toEqual(['ESPECES']);
    expect(erreurVirement(null, 1000, '2026-10-01', '2026-10-07', 'R', null)).toContain('montant');
    expect(erreurVirement(2000, 1000, '2026-10-01', '2026-10-07', 'R', null)).toContain('reste à payer');
    expect(erreurVirement(1000, 1000, '2026-10-08', '2026-10-07', 'R', null)).toContain('futur');
    expect(erreurVirement(1000, 1000, '2026-10-01', '2026-10-07', ' ', null)).toContain('référence');
    const lourd = new File([new Uint8Array(PREUVE_MAX + 1)], 'a.png', { type: 'image/png' });
    expect(erreurVirement(1000, 1000, '2026-10-01', '2026-10-07', 'R', lourd)).toContain('2 Mo');
    const word = new File(['x'], 'a.docx', { type: 'application/msword' });
    expect(erreurVirement(1000, 1000, '2026-10-01', '2026-10-07', 'R', word)).toContain('PDF');
    expect(erreurVirement(1000, 1000, '2026-10-01', '2026-10-07', 'R', null)).toBeNull();
  });

  it('administrateur : moyens du guichet et des parents, compte bancaire ajouté', async () => {
    await session(http, 'ADMIN_ECOLE');
    const f = TestBed.createComponent(MoyensPaiementPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/parametres/paiement').flush(PARAMETRES);
    await attendre();
    http.expectOne('/api/v1/parametres/mobile-money').flush({ agregateur: null, identifiantMarchand: null, cleApiDefinie: false,
      secretWebhookDefini: false, actif: false, urlNotification: '/api/v1/webhooks/mobile-money/etab-1', agregateursDisponibles: ['SIMULATEUR'] });
    await attendre();
    f.detectChanges();
    await f.whenStable();
    expect(texte(f)).toContain("Le compte marchand Mobile Money n'est pas encore configuré");
    expect(texte(f)).toContain('/api/v1/webhooks/mobile-money/etab-1');

    bouton(f, 'Ajouter un compte')!.click();
    f.detectChanges();
    await f.whenStable();
    const saisir = (sel: string, v: string) => {
      const c = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>(sel)!;
      c.value = v;
      c.dispatchEvent(new Event('input'));
    };
    saisir('#cb-banque', 'Coris Bank');
    saisir('#cb-titulaire', 'Lycée technique');
    saisir('#cb-numero', 'BF0001');
    f.detectChanges();
    bouton(f, 'Enregistrer le compte')!.click();
    await attendre();
    const creation = http.expectOne((r) => r.url === '/api/v1/comptes-bancaires' && r.method === 'POST');
    expect(creation.request.body).toMatchObject({ banque: 'Coris Bank', titulaire: 'Lycée technique', numero: 'BF0001', codeSwift: null, agence: null, actif: true });
    const compte = { id: 'cb1', banque: 'Coris Bank', titulaire: 'Lycée technique', numero: 'BF0001', codeSwift: null, agence: null, actif: true };
    creation.flush(compte);
    await attendre();
    http.expectOne('/api/v1/parametres/paiement').flush({ ...PARAMETRES, comptes: [compte] });
    await attendre();
    expect(texte(f)).toContain('Compte « Coris Bank » enregistré.');

    // Plus de chèque au guichet ; virement proposé aux parents
    const page = f.nativeElement as HTMLElement;
    (page.querySelector('input[name="g-CHEQUE"]') as HTMLInputElement).click();
    (page.querySelector('input[name="vir"]') as HTMLInputElement).click();
    f.detectChanges();
    await f.whenStable();
    bouton(f, 'Enregistrer')!.click();
    await attendre();
    const maj = http.expectOne((r) => r.url === '/api/v1/parametres/paiement' && r.method === 'PUT');
    expect(maj.request.body.moyensGuichet).toEqual(['ESPECES', 'ORANGE_MONEY', 'MOOV_MONEY', 'TELECEL_MONEY', 'VIREMENT']);
    expect(maj.request.body.virementParents).toBe(true);
    maj.flush({ ...PARAMETRES, moyensGuichet: maj.request.body.moyensGuichet, virementParents: true, comptes: [compte] });
    await attendre();
    expect(texte(f)).toContain('Moyens de paiement enregistrés.');
  });

  it('intendant : consultation seule des moyens de paiement', async () => {
    await session(http, 'INTENDANT');
    const f = TestBed.createComponent(MoyensPaiementPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/parametres/paiement').flush(PARAMETRES);
    await attendre();
    http.expectOne('/api/v1/parametres/mobile-money').flush({ agregateur: null, identifiantMarchand: null, cleApiDefinie: false,
      secretWebhookDefini: false, actif: false, urlNotification: '/api/v1/webhooks/mobile-money/etab-1', agregateursDisponibles: ['SIMULATEUR'] });
    await attendre();
    expect(texte(f)).toContain('Non configuré.');
    expect(texte(f)).toContain("Seul l'administrateur de l'établissement modifie ces réglages.");
    expect(bouton(f, 'Enregistrer')).toBeUndefined();
    expect(bouton(f, 'Ajouter un compte')).toBeUndefined();
  });

  it('intendant : virement validé avec le montant reçu, un autre refusé avec motif', async () => {
    await session(http, 'INTENDANT');
    const f = TestBed.createComponent(VirementsPage);
    f.detectChanges();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/virements' && r.params.get('statut') === 'EN_ATTENTE')
      .flush([VIREMENT, { ...VIREMENT, id: 'v2', reference: 'VIR-2026-002', preuve: false }]);
    await attendre();
    let t = texte(f);
    expect(t).toContain('Awa OUEDRAOGO');
    expect(t).toContain('VIR-2026-001');
    expect(t).toContain("donneur d'ordre : OUEDRAOGO Mariam");

    bouton(f, 'Valider')!.click();
    f.detectChanges();
    await f.whenStable();
    const montant = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#recu-v1')!;
    expect(montant.value).toBe('20000');
    montant.value = '19 500';
    montant.dispatchEvent(new Event('input'));
    f.detectChanges();
    bouton(f, 'Confirmer')!.click();
    await attendre();
    const validation = http.expectOne('/api/v1/virements/v1/validation');
    expect(validation.request.body).toEqual({ montantRecu: 19_500 });
    validation.flush({ ...VIREMENT, statut: 'VALIDE', montantValide: 19_500, recuNumero: '2026-000050' });
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/virements').flush([{ ...VIREMENT, id: 'v2', reference: 'VIR-2026-002', preuve: false }]);
    await attendre();
    expect(texte(f)).toContain('Virement validé : reçu 2026-000050');

    bouton(f, 'Refuser')!.click();
    f.detectChanges();
    await f.whenStable();
    expect(bouton(f, 'Confirmer le refus')!.disabled).toBe(true);
    const motif = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#motif-v2')!;
    motif.value = 'Introuvable sur le relevé';
    motif.dispatchEvent(new Event('input'));
    f.detectChanges();
    bouton(f, 'Confirmer le refus')!.click();
    await attendre();
    const refus = http.expectOne('/api/v1/virements/v2/refus');
    expect(refus.request.body).toEqual({ motif: 'Introuvable sur le relevé' });
    refus.flush({ ...VIREMENT, id: 'v2', statut: 'REFUSE', motifRefus: 'Introuvable sur le relevé' });
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/virements').flush([]);
    await attendre();
    t = texte(f);
    expect(t).toContain('le parent est prévenu par SMS');
    expect(t).toContain('Aucun virement à vérifier.');
  });
});
