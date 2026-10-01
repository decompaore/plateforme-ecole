import { HttpClient } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { StockageMemoire } from '../hors-ligne/stockage';
import { attendre, configurer, jeton, LYCEE, reponse } from '../testing/outils-test';
import { SessionService, sujet } from './session.service';

describe('SessionService', () => {
  let http: HttpTestingController;
  let stockage: StockageMemoire;
  let session: SessionService;

  beforeEach(() => {
    ({ http, stockage } = configurer());
    session = TestBed.inject(SessionService);
  });

  afterEach(() => http.verify());

  async function repondreMoi(): Promise<void> {
    await attendre();
    http.expectOne('/api/v1/moi').flush({ id: 'u1', nom: 'OUEDRAOGO', prenoms: 'Awa' });
    await attendre();
  }

  it('lit l’identifiant de l’utilisateur dans le jeton', () => {
    expect(sujet(jeton('3f1c-utilisateur'))).toBe('3f1c-utilisateur');
  });

  it('ouvre la session avec un seul établissement et enregistre le profil (jamais le jeton)', async () => {
    const promesse = session.connexion('70 00 00 01', 'secret123');
    const req = http.expectOne('/api/v1/auth/connexion');
    expect(req.request.body).toEqual({ telephone: '70 00 00 01', motDePasse: 'secret123' });
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(reponse());
    await repondreMoi();

    expect(await promesse).toBe('ouverte');
    expect(session.jeton()).toBe(jeton('u1'));
    expect(session.profil()?.prenoms).toBe('Awa');
    expect(session.aLeRole('ENSEIGNANT')).toBe(true);
    const enregistre = await stockage.lire<Record<string, unknown>>('session:profil');
    expect(enregistre?.['utilisateurId']).toBe('u1');
    expect(JSON.stringify(enregistre)).not.toContain(jeton('u1'));
  });

  it('un seul établissement : pas de choix d’établissement ; le renouvellement retrouve le nombre par /moi/etablissements', async () => {
    const connexion = session.connexion('70000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u1'));
    await repondreMoi();
    await connexion;
    expect(session.plusieursEtablissements()).toBe(false);

    // Lancement suivant : le renouvellement ne donne pas la liste, l'application la demande
    TestBed.resetTestingModule();
    ({ http, stockage } = configurer());
    session = TestBed.inject(SessionService);
    const demarrage = session.demarrer();
    await attendre();
    http.expectOne('/api/v1/auth/rafraichir').flush(reponse('u1', 2, { etablissements: [] }));
    await attendre();
    http.expectOne('/api/v1/moi').flush({ nom: 'OUEDRAOGO', prenoms: 'Awa' });
    await attendre();
    http.expectOne('/api/v1/moi/etablissements').flush([LYCEE, { ...LYCEE, id: 'etab-2' }]);
    await demarrage;
    expect(session.plusieursEtablissements()).toBe(true);
  });

  it('demande le choix de l’établissement puis l’envoie avec le jeton de sélection', async () => {
    const autre = { ...LYCEE, id: 'etab-2', nom: 'CEG de Réo' };
    const promesse = session.connexion('70000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush({
      ...reponse(),
      jetonAcces: null,
      jetonSelection: 'jeton-selection',
      selectionRequise: true,
      etablissementActif: null,
      etablissements: [LYCEE, autre],
    });
    expect(await promesse).toBe('selection');
    expect(session.profil()).toBeNull();
    expect(session.selection()?.etablissements).toHaveLength(2);

    const choix = session.choisirEtablissement('etab-2');
    const req = http.expectOne('/api/v1/auth/etablissement');
    expect(req.request.headers.get('Authorization')).toBe('Bearer jeton-selection');
    expect(req.request.body).toEqual({ etablissementId: 'etab-2' });
    req.flush(reponse('u1', 1, { etablissementActif: autre }));
    await repondreMoi();
    await choix;

    expect(session.selection()).toBeNull();
    expect(session.profil()?.etablissement?.nom).toBe('CEG de Réo');
  });

  it('sans réseau au lancement, reprend le profil enregistré en mode hors connexion', async () => {
    await stockage.ecrire('session:profil', {
      utilisateurId: 'u1',
      nom: 'OUEDRAOGO',
      prenoms: 'Awa',
      superAdmin: false,
      etablissement: LYCEE,
      doitChangerMotDePasse: false,
    });
    const promesse = session.demarrer();
    await attendre();
    http.expectOne('/api/v1/auth/rafraichir').error(new ProgressEvent('error'), { status: 0 });
    await promesse;

    expect(session.horsConnexion()).toBe(true);
    expect(session.jeton()).toBeNull();
    expect(session.profil()?.nom).toBe('OUEDRAOGO');
  });

  it('session expirée au lancement : oublie le profil et les listes, garde les appels', async () => {
    await stockage.ecrire('session:profil', { utilisateurId: 'u1' });
    await stockage.ecrire('cache:u1:etab-1:classe:c1', { eleves: [] });
    await stockage.ecrire('envoi:u1:etab-1:k', { etat: 'EN_ATTENTE' });
    const promesse = session.demarrer();
    await attendre();
    http.expectOne('/api/v1/auth/rafraichir').flush({ code: 'X' }, { status: 401, statusText: 'Unauthorized' });
    await promesse;

    expect(session.profil()).toBeNull();
    expect(await stockage.lire('session:profil')).toBeUndefined();
    expect(await stockage.cles('cache:')).toEqual([]);
    expect(await stockage.cles('envoi:')).toHaveLength(1);
  });

  it('serveur en erreur au lancement : garde le profil pour travailler hors connexion', async () => {
    await stockage.ecrire('session:profil', { utilisateurId: 'u1', etablissement: LYCEE });
    const promesse = session.demarrer();
    await attendre();
    http.expectOne('/api/v1/auth/rafraichir').flush({}, { status: 500, statusText: 'Erreur' });
    await promesse;
    expect(session.horsConnexion()).toBe(true);
  });

  it('session expirée en cours d’utilisation : garde le profil (appel en cours) et demande de se reconnecter', async () => {
    const connexion = session.connexion('70000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u1'));
    await repondreMoi();
    await connexion;

    const ok = session.rafraichir();
    http.expectOne('/api/v1/auth/rafraichir').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(await ok).toBe(false);
    expect(session.profil()?.utilisateurId).toBe('u1');
    expect(session.jeton()).toBeNull();
    expect(session.sessionExpiree()).toBe(true);
  });

  it('déconnexion sans réseau : la révocation est faite au lancement suivant, sans rouvrir la session', async () => {
    const connexion = session.connexion('70000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u1'));
    await repondreMoi();
    await connexion;

    const fin = session.deconnexion();
    http.expectOne('/api/v1/auth/deconnexion').error(new ProgressEvent('error'), { status: 0 });
    await fin;
    expect(session.profil()).toBeNull();

    const lancement = session.demarrer();
    await attendre();
    http.expectOne('/api/v1/auth/deconnexion').flush(null, { status: 204, statusText: 'No Content' });
    await lancement;
    http.expectNone('/api/v1/auth/rafraichir');
    expect(session.profil()).toBeNull();
    expect(await stockage.lire('session:deconnexion-en-attente')).toBeUndefined();
  });

  it('téléphone partagé : la connexion d’un autre utilisateur efface les listes du précédent', async () => {
    await stockage.ecrire('cache:u9:etab-1:classe:c1', { eleves: [{ nom: 'X' }] });
    const connexion = session.connexion('70000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u1'));
    await repondreMoi();
    await connexion;
    expect(await stockage.cles('cache:')).toEqual([]);
  });

  it('sur un 401, renouvelle une seule fois pour plusieurs requêtes puis les rejoue avec le nouveau jeton', async () => {
    const connexion = session.connexion('70000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1));
    await repondreMoi();
    await connexion;

    const client = TestBed.inject(HttpClient);
    const a = firstValueFrom(client.get('/api/v1/a'));
    const b = firstValueFrom(client.get('/api/v1/b'));
    http.expectOne('/api/v1/a').flush(null, { status: 401, statusText: 'Unauthorized' });
    http.expectOne('/api/v1/b').flush(null, { status: 401, statusText: 'Unauthorized' });
    await attendre();

    // Un seul renouvellement : le serveur verrait un second usage du même cookie comme un vol
    http.expectOne('/api/v1/auth/rafraichir').flush(reponse('u1', 2));
    await attendre();

    const ra = http.expectOne('/api/v1/a');
    const rb = http.expectOne('/api/v1/b');
    expect(ra.request.headers.get('Authorization')).toBe(`Bearer ${jeton('u1', 2)}`);
    expect(rb.request.headers.get('Authorization')).toBe(`Bearer ${jeton('u1', 2)}`);
    ra.flush({ ok: 'a' });
    rb.flush({ ok: 'b' });
    expect(await a).toEqual({ ok: 'a' });
    expect(await b).toEqual({ ok: 'b' });
  });

  it('la déconnexion efface les listes de classes mais garde les appels non envoyés', async () => {
    const connexion = session.connexion('70000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u1'));
    await repondreMoi();
    await connexion;
    await stockage.ecrire('cache:u1:etab-1:classe:c1', { eleves: [] });
    await stockage.ecrire('envoi:u1:etab-1:2026-10-05T08:00:00Z:x', { etat: 'EN_ATTENTE' });

    const fin = session.deconnexion();
    http.expectOne('/api/v1/auth/deconnexion').flush(null, { status: 204, statusText: 'No Content' });
    await fin;

    expect(session.profil()).toBeNull();
    expect(session.jeton()).toBeNull();
    expect(await stockage.lister('cache:u1:')).toHaveLength(0);
    expect(await stockage.lister('envoi:u1:')).toHaveLength(1);
  });
});
