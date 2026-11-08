import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { AgentResume, DossierVue, vide } from './modeles-personnel';
import { AbsencesPersonnelPage } from './pages/absences-personnel.page';
import { DossierAgentPage } from './pages/dossier-agent.page';
import { PersonnelPage } from './pages/personnel.page';

async function session(http: HttpTestingController, role: Role): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('61000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'ZONGO', prenoms: 'Ines' });
  await connexion;
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement {
  f.detectChanges();
  const b = [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((x) => x.textContent?.trim().startsWith(libelle));
  if (!b) throw new Error(`Bouton « ${libelle} » introuvable`);
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

function agent(autres: Partial<AgentResume> = {}): AgentResume {
  return {
    id: 'a1', categorie: 'ENSEIGNANT', nom: 'KABORE', prenoms: 'Awa', sexe: 'F', matricule: '123456A', situation: 'FONCTIONNAIRE',
    emploi: 'Professeur certifié', grade: null, fonction: 'Professeur de mathématiques', datePriseService: '2022-10-03',
    dateCessation: null, compte: true, ancienneteEtablissement: { annees: 4, mois: 1, jours: 3, texte: '4 ans, 1 mois et 3 jours' },
    pieces: 2, signalements: 0, absentAujourdhui: false, manquants: [],
    ...autres,
  };
}

function dossier(autres: Partial<DossierVue> = {}): DossierVue {
  return {
    ...vide(), id: 'a1', utilisateurId: 'u2', categorie: 'ENSEIGNANT', nom: 'KABORE', prenoms: 'Awa', sexe: 'F',
    matricule: '123456A', situation: 'FONCTIONNAIRE', fonction: 'Professeur de mathématiques', datePriseService: '2022-10-03',
    anciennete: { fonction: { annees: 11, mois: 1, jours: 5, texte: '11 ans, 1 mois et 5 jours' }, grade: null,
      etablissement: { annees: 4, mois: 1, jours: 3, texte: '4 ans, 1 mois et 3 jours' } },
    manquants: ['diplômes'], diplomes: [], pieces: [], absences: [], signalements: [],
    actes: [{ id: 'x1', type: 'ATTESTATION_TRAVAIL', numero: null, titre: 'Attestation de travail', dateEffet: null,
      etabliPar: 'ZONGO Ines', etabliLe: '2026-11-06T08:00:00Z', signataire: null, signeLe: null, annuleLe: null,
      motifAnnulation: null, absenceId: null }],
    droits: { modifier: true, pieces: true, actes: true, signer: true, decider: true },
    modifieLe: '2026-11-06T08:00:00Z',
    ...autres,
  };
}

describe('Dossiers du personnel (v0.51)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('secrétariat : ouvre les dossiers des comptes sans dossier et filtre les agents de soutien', async () => {
    await session(http, 'SECRETARIAT');
    const f = TestBed.createComponent(PersonnelPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/personnel').flush({ agents: [agent()], comptesSansDossier: 3 });
    await attendre();
    let t = texte(f);
    expect(t).toContain('3 compte(s) du personnel sans dossier');
    expect(t).toContain('4 ans, 1 mois et 3 jours');

    bouton(f, 'Ouvrir leurs dossiers').click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/personnel/reprise').flush({ crees: 3, dejaPresents: 1 });
    await attendre();
    http.expectOne('/api/v1/personnel').flush({
      agents: [agent(), agent({ id: 'a2', categorie: 'SOUTIEN', nom: 'OUEDRAOGO', prenoms: 'Issa', sexe: 'M', matricule: null,
        compte: false, fonction: 'Gardien', manquants: ['date de naissance'] })],
      comptesSansDossier: 0,
    });
    await attendre();
    t = texte(f);
    expect(t).toContain('3 dossier(s) ouvert(s)');
    expect(t).not.toContain('sans dossier.');

    bouton(f, 'Agent de soutien').click();
    t = texte(f);
    expect(t).toContain('OUEDRAOGO');
    expect(t).not.toContain('KABORE');
    expect(t).toContain('À compléter : date de naissance');
  });

  it('chef d’établissement : signe l’acte préparé par le secrétariat', async () => {
    await session(http, 'ADMIN_ECOLE');
    const f = TestBed.createComponent(DossierAgentPage);
    f.componentRef.setInput('id', 'a1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/personnel/a1').flush(dossier());
    await attendre();
    let t = texte(f);
    expect(t).toContain('11 ans, 1 mois et 5 jours');
    expect(t).toContain('À compléter : diplômes.');

    bouton(f, 'Actes').click();
    t = texte(f);
    expect(t).toContain('Attestation de travail · projet');
    bouton(f, 'Signer').click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/personnel/actes/x1/signature')
      .flush({ ...dossier().actes[0], numero: 'AP-2026-0001', signeLe: '2026-11-06T09:00:00Z', signataire: 'Issouf SAWADOGO' });
    await attendre();
    expect(texte(f)).toContain('Attestation de travail signé : n° AP-2026-0001.');
    http.expectOne('/api/v1/personnel/a1').flush(dossier({
      actes: [{ ...dossier().actes[0], numero: 'AP-2026-0001', signeLe: '2026-11-06T09:00:00Z', signataire: 'Issouf SAWADOGO' }],
    }));
    await attendre();
    expect(texte(f)).toContain('par Issouf SAWADOGO');
  });

  it('agent : demande une absence depuis « Mon dossier »', async () => {
    await session(http, 'ENSEIGNANT');
    const f = TestBed.createComponent(DossierAgentPage);
    f.componentRef.setInput('mien', true);
    f.detectChanges();
    await attendre();
    const moi = dossier({ actes: [], observations: null, droits: { modifier: false, pieces: false, actes: false, signer: false, decider: false } });
    http.expectOne('/api/v1/mon-dossier').flush(moi);
    await attendre();
    expect(texte(f)).toContain('Mon dossier');
    expect(texte(f)).not.toContain('Modifier');

    bouton(f, 'Congés et absences').click();
    await saisir(f, '#bDebut', '2026-11-16');
    await saisir(f, '#bFin', '2026-11-18');
    await saisir(f, '#bMotif', 'Baptême');
    bouton(f, 'Envoyer la demande').click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/mon-dossier/absences');
    expect(req.request.body).toEqual({ type: 'AUTORISATION_ABSENCE', debut: '2026-11-16', fin: '2026-11-18', motif: 'Baptême' });
    req.flush({ id: 'b1', statut: 'DEMANDEE' });
    await attendre();
    expect(texte(f)).toContain("Demande envoyée au chef d'établissement.");
    http.expectOne('/api/v1/mon-dossier').flush(moi);
    await attendre();
  });

  it('chef d’établissement : accorde une demande d’absence', async () => {
    await session(http, 'ADMIN_ECOLE');
    const f = TestBed.createComponent(AbsencesPersonnelPage);
    f.detectChanges();
    await attendre();
    const demande = {
      id: 'b1', agentId: 'a1', agent: 'KABORE Awa', type: 'AUTORISATION_ABSENCE', debut: '2099-01-10', fin: '2099-01-12', jours: 3,
      reprise: '2099-01-13', motif: 'Baptême', statut: 'DEMANDEE', demandeePar: 'KABORE Awa', demandeeLe: '2026-11-06T08:00:00Z',
      decideePar: null, decideeLe: null, reponse: null, pieceId: null, acteId: null,
    };
    http.expectOne('/api/v1/personnel/absences').flush([demande]);
    await attendre();
    expect(texte(f)).toContain('Demandes à décider (1)');
    bouton(f, 'Accorder').click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/personnel/absences/b1/decision');
    expect(req.request.body).toEqual({ accorder: true, reponse: null });
    req.flush({ ...demande, statut: 'ACCORDEE', acteId: 'x2' });
    await attendre();
    http.expectOne('/api/v1/personnel/absences').flush([{ ...demande, statut: 'ACCORDEE', acteId: 'x2' }]);
    await attendre();
    const t = texte(f);
    expect(t).toContain("Absence de KABORE Awa accordée");
    expect(t).toContain('Demandes à décider (0)');
  });
});
