import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { SessionService } from '../core/session.service';
import { EnvoisService } from '../hors-ligne/envois.service';
import { StockageMemoire } from '../hors-ligne/stockage';
import { attendre, configurer, LYCEE } from '../testing/outils-test';
import { AppelSaisiePage } from './appel-saisie.page';

describe('AppelSaisiePage hors connexion', () => {
  let http: HttpTestingController;
  let stockage: StockageMemoire;

  beforeEach(async () => {
    ({ http, stockage } = configurer());
    // Téléphone sans réseau : profil et listes enregistrés lors d'une ouverture précédente
    await stockage.ecrire('session:profil', {
      utilisateurId: 'u1',
      nom: 'SAWADOGO',
      prenoms: 'Issa',
      superAdmin: false,
      etablissement: LYCEE,
      doitChangerMotDePasse: false,
    });
    await stockage.ecrire('cache:u1:etab-1:affectations', {
      anneeId: 'a1',
      prepareLe: '2026-10-04T18:00:00Z',
      affectations: [
        { classeId: 'c1', classeCode: '2nde F3', matiereId: 'm1', matiereLibelle: 'Électrotechnique', engagementId: 'e1' },
      ],
    });
    await stockage.ecrire('cache:u1:etab-1:classe:c1', {
      classeId: 'c1',
      prepareLe: '2026-10-04T18:00:00Z',
      eleves: [
        { inscriptionId: 'i1', nom: 'COMPAORE', prenoms: 'Aïcha', matricule: null, sexe: 'F' },
        { inscriptionId: 'i2', nom: 'KABORE', prenoms: 'Ali', matricule: null, sexe: 'M' },
      ],
    });
    const demarrage = TestBed.inject(SessionService).demarrer();
    await attendre();
    http.expectOne('/api/v1/auth/rafraichir').error(new ProgressEvent('error'), { status: 0 });
    await demarrage;
  });

  afterEach(() => http.verify());

  it('fait l’appel à partir des listes du téléphone et le met en file d’envoi', async () => {
    const navigation = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(AppelSaisiePage);
    fixture.componentRef.setInput('classeId', 'c1');
    fixture.componentRef.setInput('matiereId', 'm1');
    await fixture.whenStable();
    await attendre();
    fixture.detectChanges();

    const page: HTMLElement = fixture.nativeElement;
    expect(page.querySelector('h1')?.textContent).toContain('2nde F3 · Électrotechnique');
    const lignes = page.querySelectorAll('li.eleve');
    expect(lignes).toHaveLength(2);

    // Kaboré absent
    (lignes[1].querySelector('button.marque.a') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(lignes[1].classList).toContain('absent');
    expect(page.querySelector('.pied')?.textContent).toContain('1 présents');

    (page.querySelector('.pied button') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(page.textContent).toContain('Vérifiez l');
    expect(page.textContent).toContain('KABORE Ali');

    const valider = [...page.querySelectorAll('button')].find((b) => b.textContent?.includes("Valider l'appel"))!;
    valider.click();
    await attendre();

    const envois = TestBed.inject(EnvoisService).envois();
    expect(envois).toHaveLength(1);
    expect(envois[0].etat).toBe('EN_ATTENTE');
    expect(envois[0].appel.marques).toEqual([{ inscriptionId: 'i2', type: 'ABSENCE', minutesRetard: null }]);
    // Le cours est transmis pour proposer ensuite le cahier de textes du même créneau
    expect(navigation).toHaveBeenCalledWith(['/envois'], {
      state: { vientDeSaisir: true, cours: expect.objectContaining({ classeId: 'c1', matiereId: 'm1' }) },
    });
    // Pas de jeton : rien n'est parti vers le serveur, la session sera rouverte au retour du réseau
    http.expectNone('/api/v1/appels/lot');
    await attendre();
    http.match('/api/v1/auth/rafraichir').forEach((r) => r.error(new ProgressEvent('error'), { status: 0 }));
  });
});
