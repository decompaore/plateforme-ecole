import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { AccuseAppel, DonneesAppel } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { Envoi, EnvoisService, LOT_MAX } from './envois.service';
import { StockageMemoire } from './stockage';

const APPEL = {
  classeId: 'c1',
  matiereId: 'm1',
  date: '2026-10-05',
  heureDebut: '08:00',
  heureFin: '10:00',
  marques: [
    { inscriptionId: 'i1', type: 'ABSENCE' as const, minutesRetard: null },
    { inscriptionId: 'i2', type: 'RETARD' as const, minutesRetard: 15 },
  ],
};
const LIBELLES = { classeCode: '2nde F3', matiereLibelle: 'Électrotechnique' };

function accuse(appel: DonneesAppel, statut: AccuseAppel['statut'], autres: Partial<AccuseAppel> = {}): AccuseAppel {
  return {
    idClient: appel.idClient,
    statut,
    code: null,
    message: null,
    absents: 1,
    retards: 1,
    inscriptionsIgnorees: [],
    ...autres,
  };
}

describe('EnvoisService', () => {
  let http: HttpTestingController;
  let stockage: StockageMemoire;
  let envois: EnvoisService;

  beforeEach(async () => {
    ({ http, stockage } = configurer());
    const session = TestBed.inject(SessionService);
    envois = TestBed.inject(EnvoisService);
    const connexion = session.connexion('70000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u1'));
    await attendre();
    http.expectOne('/api/v1/moi').flush({ nom: 'SAWADOGO', prenoms: 'Issa' });
    await connexion;
  });

  afterEach(() => http.verify());

  async function lot(): Promise<{ appels: DonneesAppel[]; repondre: (a: AccuseAppel[]) => void; erreur: (s: number, corps?: object) => void }> {
    await attendre();
    const req = http.expectOne('/api/v1/appels/lot');
    return {
      appels: (req.request.body as { appels: DonneesAppel[] }).appels,
      repondre: (a) => req.flush(a),
      erreur: (s, corps) =>
        s === 0
          ? req.error(new ProgressEvent('error'), { status: 0 })
          : req.flush(corps ?? { title: 'Erreur' }, { status: s, statusText: 'Erreur' }),
    };
  }

  it('garde l’appel sur l’appareil puis le marque envoyé à réception de l’accusé', async () => {
    const envoi = await envois.ajouter(APPEL, LIBELLES);
    expect(envoi.etat).toBe('EN_ATTENTE');
    expect(envoi.appel.idClient).toMatch(/^[0-9a-f-]{36}$/);
    expect(envoi.absents).toBe(1);
    expect(envoi.retards).toBe(1);
    expect(envois.enAttente()).toBe(1);

    const l = await lot();
    expect(l.appels).toHaveLength(1);
    expect(l.appels[0]).toMatchObject({ ...APPEL, idClient: envoi.appel.idClient });
    expect(l.appels[0].saisiLe).toBeTruthy();
    l.repondre([accuse(l.appels[0], 'ENREGISTRE')]);
    await attendre();

    expect(envois.enAttente()).toBe(0);
    const stocke = await stockage.lire<Envoi>(envoi.cle);
    expect(stocke?.etat).toBe('ENVOYE');
  });

  it('coupure réseau : l’appel reste en attente et repart avec le même identifiant (pas de doublon)', async () => {
    const envoi = await envois.ajouter(APPEL, LIBELLES);
    (await lot()).erreur(0);
    await attendre();
    expect(envois.enAttente()).toBe(1);
    expect(envois.dernierBilan()?.erreur).toContain('connexion');

    const bilan = envois.synchroniser();
    const l = await lot();
    expect(l.appels[0].idClient).toBe(envoi.appel.idClient);
    l.repondre([accuse(l.appels[0], 'DEJA_RECU')]);
    expect(await bilan).toMatchObject({ envoyes: 1, refuses: 0, restants: 0 });
  });

  it('appel refusé par le serveur : affiché avec son motif et plus jamais renvoyé', async () => {
    const envoi = await envois.ajouter(APPEL, LIBELLES);
    const l = await lot();
    l.repondre([
      accuse(l.appels[0], 'REFUSE', {
        code: 'CRENEAU_DEJA_FAIT',
        message: "L'appel de ce créneau a déjà été fait",
      }),
    ]);
    await attendre();

    expect(envois.refuses()).toBe(1);
    const stocke = await stockage.lire<Envoi>(envoi.cle);
    expect(stocke?.message).toBe("L'appel de ce créneau a déjà été fait");

    const bilan = await envois.synchroniser();
    expect(bilan.restants).toBe(0);
    http.expectNone('/api/v1/appels/lot');

    await envois.retirer(stocke!);
    expect(envois.envois()).toHaveLength(0);
  });

  it('lot invalide (400) : les appels passent en refusés au lieu de bloquer la file', async () => {
    await envois.ajouter(APPEL, LIBELLES);
    (await lot()).erreur(400, { title: 'Requête invalide', detail: 'Données invalides' });
    await attendre();
    expect(envois.refuses()).toBe(1);
    expect(envois.envois()[0].message).toBe('Données invalides');
  });

  it('lot rejeté (400) : renvoi un par un pour ne refuser que l’appel illisible', async () => {
    const bon = await envois.ajouter(APPEL, LIBELLES);
    (await lot()).repondre([accuse(bon.appel, 'ENREGISTRE')]);
    await attendre();
    // Deux appels en attente, dont un corrompu
    const a = await envois.ajouter({ ...APPEL, date: '2026-10-06' }, LIBELLES);
    (await lot()).erreur(0);
    await attendre();
    await stockage.ecrire(a.cle.replace(/:[^:]+$/, ':zz'), {
      ...a,
      cle: a.cle.replace(/:[^:]+$/, ':zz'),
      appel: { ...a.appel, idClient: 'illisible' },
    });

    const bilan = envois.synchroniser();
    const l = await lot();
    expect(l.appels).toHaveLength(2);
    l.erreur(400, { title: 'Requête invalide', detail: 'Identifiant invalide' });
    const un = await lot();
    un.repondre([accuse(un.appels[0], 'ENREGISTRE')]);
    const deux = await lot();
    deux.erreur(400, { title: 'Requête invalide', detail: 'Identifiant invalide' });
    expect(await bilan).toMatchObject({ envoyes: 1, refuses: 1, restants: 0 });
  });

  it('envoie par lots de 50 au plus, dans l’ordre de saisie', async () => {
    const total = LOT_MAX * 2 + 7;
    for (let i = 0; i < total; i++) {
      await stockage.ecrire(`envoi:u1:etab-1:2026-10-05T08:${String(i).padStart(3, '0')}:x${i}`, {
        cle: `envoi:u1:etab-1:2026-10-05T08:${String(i).padStart(3, '0')}:x${i}`,
        utilisateurId: 'u1',
        etablissementId: 'etab-1',
        appel: { ...APPEL, idClient: `id-${i}`, saisiLe: '2026-10-05T08:00:00Z' },
        ...LIBELLES,
        absents: 0,
        retards: 0,
        etat: 'EN_ATTENTE',
        tentatives: 0,
      } satisfies Envoi);
    }
    const bilan = envois.synchroniser();
    const tailles: number[] = [];
    for (let n = 0; n < 3; n++) {
      const l = await lot();
      tailles.push(l.appels.length);
      if (n === 0) {
        expect(l.appels[0].idClient).toBe('id-0');
      }
      l.repondre(l.appels.map((a) => accuse(a, 'ENREGISTRE')));
    }
    expect(tailles).toEqual([LOT_MAX, LOT_MAX, 7]);
    expect((await bilan).envoyes).toBe(total);
  });

  it('n’envoie que les appels de l’utilisateur et de l’établissement actifs', async () => {
    await stockage.ecrire('envoi:u2:etab-1:2026-10-05T08:00:00Z:a', { etat: 'EN_ATTENTE' });
    await stockage.ecrire('envoi:u1:etab-9:2026-10-05T08:00:00Z:b', { etat: 'EN_ATTENTE' });
    const bilan = await envois.synchroniser();
    expect(bilan.restants).toBe(0);
    http.expectNone('/api/v1/appels/lot');
  });

  it('un seul envoi à la fois', async () => {
    await envois.ajouter(APPEL, LIBELLES);
    const second = envois.synchroniser();
    const l = await lot();
    l.repondre([accuse(l.appels[0], 'ENREGISTRE')]);
    await second;
    http.expectNone('/api/v1/appels/lot');
  });
});
