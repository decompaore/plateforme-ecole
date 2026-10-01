import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { DonneesAppel } from '../core/modeles';
import { dateLocale, lendemain } from '../core/outils';
import { SessionService } from '../core/session.service';
import { Envoi, EnvoisService } from '../hors-ligne/envois.service';
import { ListesService } from '../hors-ligne/listes.service';
import { StockageMemoire } from '../hors-ligne/stockage';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { FinEngagementComponent, joursEntre } from './fin-engagement.component';

/** Date AAAA-MM-JJ dans n jours. */
function dans(n: number): string {
  let d = dateLocale();
  for (let i = 0; i < n; i++) {
    d = lendemain(d);
  }
  return d;
}

function fiche(fin: string | null, finProgrammee: boolean, motifFin: string | null = null): object {
  return {
    enseignant: { type: 'TITULAIRE', statut: 'ACTIF', fin, motifFin, finProgrammee },
    anneeId: 'a1',
    affectations: [],
    chargeHebdomadaire: 0,
  };
}

describe('Bandeau de fin d’engagement (mutation)', () => {
  let http: HttpTestingController;
  let stockage: StockageMemoire;

  beforeEach(async () => {
    ({ http, stockage } = configurer());
    const connexion = TestBed.inject(SessionService).connexion('61000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u1'));
    await attendre();
    http.expectOne('/api/v1/moi').flush({ nom: 'SANOU', prenoms: 'Paul' });
    await connexion;
  });

  afterEach(() => http.verify());

  async function afficher(reponseServeur: object | 'hors-ligne') {
    const f = TestBed.createComponent(FinEngagementComponent);
    f.detectChanges();
    await attendre();
    const req = http.expectOne('/api/v1/espace-enseignant/affectations');
    if (reponseServeur === 'hors-ligne') {
      req.error(new ProgressEvent('error'), { status: 0 });
    } else {
      req.flush(reponseServeur);
    }
    await attendre();
    f.detectChanges();
    return f;
  }

  it('compte les jours sans erreur d’heure d’été', () => {
    expect(joursEntre('2027-03-30', '2027-04-02')).toBe(3);
    expect(joursEntre('2027-06-30', '2027-06-30')).toBe(0);
    expect(joursEntre('2027-07-01', '2027-06-30')).toBe(-1);
  });

  it('annonce la mutation, compte les envois en attente et les envoie tout de suite', async () => {
    // Un appel fait sans réseau attend sur le téléphone
    const appel: DonneesAppel = {
      idClient: 'id-1',
      saisiLe: new Date().toISOString(),
      classeId: 'c1',
      matiereId: 'm1',
      date: dateLocale(),
      heureDebut: '08:00',
      heureFin: '10:00',
      marques: [],
    };
    const envoi: Envoi = {
      cle: `envoi:u1:etab-1:${appel.saisiLe}:id-1`,
      utilisateurId: 'u1',
      etablissementId: 'etab-1',
      appel,
      classeCode: '2nde F3',
      matiereLibelle: 'Électrotechnique',
      absents: 0,
      retards: 0,
      etat: 'EN_ATTENTE',
      tentatives: 1,
    };
    await stockage.ecrire(envoi.cle, envoi);
    await TestBed.inject(EnvoisService).recharger();

    const f = await afficher(fiche(dans(10), true, 'Mutation'));
    const page: HTMLElement = f.nativeElement;
    expect(page.textContent).toContain('Lycée technique de Koudougou : votre poste se termine le');
    expect(page.textContent).toContain('(mutation)');
    expect(page.textContent).toContain('1 envoi(s) en attente');

    [...page.querySelectorAll('button')].find((b) => b.textContent?.includes('Envoyer maintenant'))!.click();
    await attendre();
    const lot = http.expectOne('/api/v1/appels/lot');
    expect((lot.request.body as { appels: DonneesAppel[] }).appels[0].idClient).toBe('id-1');
    lot.flush([
      { idClient: 'id-1', statut: 'ENREGISTRE', code: null, message: null, absents: 0, retards: 0, inscriptionsIgnorees: null },
    ]);
    await attendre();
    f.detectChanges();
    expect(page.textContent).toContain('Tous vos appels et notes sont envoyés');
  });

  it('reste discret quand la fin est lointaine, et s’affiche aussi sans réseau', async () => {
    // Contrat de vacataire qui se termine dans plus de 30 jours : rien à signaler
    let f = await afficher(fiche(dans(60), false));
    expect(f.nativeElement.textContent.trim()).toBe('');

    // Le jour même, l'information gardée sur l'appareil suffit, même sans réseau
    await stockage.ecrire('cache:u1:etab-1:fin-engagement', {
      type: 'TITULAIRE',
      fin: dateLocale(),
      motif: 'Retraite',
      programmee: true,
    });
    f = await afficher('hors-ligne');
    expect(f.nativeElement.textContent).toContain('se termine aujourd');
    expect(f.nativeElement.textContent).toContain('(retraite)');
    expect(f.nativeElement.textContent).toContain('Tous vos appels et notes sont envoyés');
  });

  it('efface l’annonce quand le serveur n’a plus de date de fin (fin annulée)', async () => {
    await stockage.ecrire('cache:u1:etab-1:fin-engagement', { type: 'TITULAIRE', fin: dans(3), motif: 'Mutation', programmee: true });
    const f = await afficher(fiche(null, false));
    expect(f.nativeElement.textContent.trim()).toBe('');
    expect(await TestBed.inject(ListesService).finEngagement()).toBeUndefined();
  });
});
