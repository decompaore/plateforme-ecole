import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { AnneeCourante } from '../admin/annee-courante.service';
import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { InterpellationVue, PropositionInterpellation } from './modeles-progression';
import { InterpellationsPage } from './pages/interpellations.page';

const ANNEE = { id: 'a1', libelle: '2026-2027', debut: '2026-10-01', fin: '2027-07-31', etat: 'ACTIVE' };

async function session(http: HttpTestingController, role: Role): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('61000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u5', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'ZONGO', prenoms: 'Ines' });
  await connexion;
  if (role !== 'ENSEIGNANT') {
    const a = TestBed.inject(AnneeCourante).charger();
    http.expectOne('/api/v1/annees').flush([ANNEE]);
    await a;
  }
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
  const champ = (f.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement | HTMLTextAreaElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event('input'));
  f.detectChanges();
}

const PROPOSITION: PropositionInterpellation = {
  classeId: 'c1', classeCode: '2nde F3', matiereId: 'm1', matiere: 'Mathématiques', domaine: 'GENERAL', enseignant: 'KABORE Awa',
  retard: { heuresPrevuesADate: 16, heuresRealisees: 2, ecart: 14, sequencesEnRetard: 1, joursSansSeance: 3, enRetard: true },
  objet: 'Retard sur la progression de Mathématiques en 2nde F3',
  constat: 'Au 15/10/2026 : heures prévues 16 h ; heures faites 2 h, soit un retard de 14 h.',
  demandeRappel: 'Je vous invite à rattraper ce retard.',
  demandeExplication: 'Je vous demande de vous expliquer par écrit.',
  delaiReponse: '2026-10-22', interpellationsEnCours: 0,
};

function interpellation(autres: Partial<InterpellationVue> = {}): InterpellationVue {
  return {
    id: 'i1', numero: 'DE-2026-0001', type: 'DEMANDE_EXPLICATION', classeId: 'c1', classeCode: '2nde F3', matiereId: 'm1',
    matiere: 'Mathématiques', domaine: 'GENERAL', enseignant: 'KABORE Awa', objet: PROPOSITION.objet, constat: PROPOSITION.constat,
    demande: PROPOSITION.demandeExplication, heuresPrevues: 16, heuresRealisees: 2, delaiReponse: '2026-10-22', emetteur: 'ZONGO Ines',
    qualiteEmetteur: 'Le Censeur', emiseLe: '2026-10-15T08:00:00Z', statut: 'ENVOYEE', lueLe: null, reponse: null, reponduLe: null,
    suite: null, closeLe: null, closePar: null, delaiDepasse: false, peutRepondre: false, peutClore: true,
    ...autres,
  };
}

describe('Interpellation des enseignants (v0.48.1)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('censeur : rédige une demande d’explication à partir du constat chiffré', async () => {
    await session(http, 'CENSEUR');
    await TestBed.inject(Router).navigateByUrl('/?classeId=c1&matiereId=m1');
    const f = TestBed.createComponent(InterpellationsPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/classes/c1/matieres/m1/interpellations/proposition').flush(PROPOSITION);
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/interpellations' && r.params.get('anneeId') === 'a1').flush([]);
    await attendre();
    let t = texte(f);
    expect(t).toContain('Interpeller KABORE Awa · Mathématiques en 2nde F3');
    expect(t).toContain('retard de 14 h');
    expect(((f.nativeElement as HTMLElement).querySelector('#iConstat') as HTMLTextAreaElement).value).toContain('soit un retard de 14 h');

    bouton(f, "Demande d'explication").click();
    await attendre();
    f.detectChanges();
    await f.whenStable();
    expect(((f.nativeElement as HTMLElement).querySelector('#iDelai') as HTMLInputElement).value).toBe('2026-10-22');
    await saisir(f, '#iDemande', 'Merci de vous expliquer par écrit avant le 22 octobre.');
    bouton(f, "Envoyer la demande d'explication").click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/classes/c1/matieres/m1/interpellations');
    expect(req.request.body).toEqual({
      type: 'DEMANDE_EXPLICATION', objet: PROPOSITION.objet, constat: PROPOSITION.constat,
      demande: 'Merci de vous expliquer par écrit avant le 22 octobre.', delaiReponse: '2026-10-22',
    });
    req.flush(interpellation());
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/interpellations').flush([interpellation()]);
    await attendre();
    t = texte(f);
    expect(t).toContain("Demande d'explication n° DE-2026-0001 envoyé(e) à KABORE Awa");
    expect(t).toContain('En cours (1)');
    expect(t).toContain('Lettre (PDF)');
  });

  it('enseignant : ouvre la demande et y répond', async () => {
    await session(http, 'ENSEIGNANT');
    const f = TestBed.createComponent(InterpellationsPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/espace-enseignant/interpellations').flush([interpellation({ peutClore: false, peutRepondre: true })]);
    await attendre();
    expect(texte(f)).toContain('Mes interpellations');
    bouton(f, "Demande d'explication").click();
    await attendre();
    http.expectOne('/api/v1/interpellations/i1').flush(interpellation({ statut: 'LUE', lueLe: '2026-10-15T09:00:00Z', peutClore: false, peutRepondre: true }));
    await attendre();
    expect(texte(f)).toContain('Le Censeur (ZONGO Ines) à KABORE Awa');
    expect(texte(f)).not.toContain('Clore');
    await saisir(f, '#iReponse', "J'ai été malade ; je rattrape le samedi.");
    bouton(f, 'Envoyer ma réponse').click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/interpellations/i1/reponse');
    expect(req.request.body).toEqual({ reponse: "J'ai été malade ; je rattrape le samedi." });
    req.flush(interpellation({ statut: 'REPONDUE', reponse: "J'ai été malade ; je rattrape le samedi.", reponduLe: '2026-10-15T10:00:00Z', peutClore: false }));
    await attendre();
    const t = texte(f);
    expect(t).toContain('Réponse envoyée.');
    expect(t).toContain("Réponse de l'enseignant");
  });
});
