import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { EtablissementAccessible, Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { MessagerieApi, Personne, segmentsSms } from './messagerie-api.service';
import { chercherPersonnes, MessagesPage } from './pages/messages.page';

const ANNEE = { id: 'a1', libelle: '2025-2026', debut: '2025-10-01', fin: '2026-07-31', etat: 'ACTIVE' };
const ANNUAIRE: Personne[] = [
  { utilisateurId: 'u2', responsableId: null, nom: 'SANOU Paul', qualite: 'Enseignant', categorie: 'ENSEIGNANT' },
  { utilisateurId: 'u3', responsableId: 'r3', nom: 'OUEDRAOGO Mariam', qualite: 'Parent de Awa OUEDRAOGO (6e A)', categorie: 'PARENT' },
  { utilisateurId: null, responsableId: 'r4', nom: 'SAWADOGO Issa', qualite: 'Parent de Ali SAWADOGO (6e B)', categorie: 'PARENT_SANS_COMPTE' },
];

async function session(http: HttpTestingController, role: Role): Promise<void> {
  const etab: EtablissementAccessible = { id: 'etab-1', code: 'CEG', nom: 'CEG Horizon', roles: [role], modulesDesactives: [] };
  const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'KABORE', prenoms: 'Mathieu' });
  await connexion;
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
}

function bouton<T>(f: ComponentFixture<T>, libelle: string): HTMLButtonElement | undefined {
  f.detectChanges();
  return [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.trim().startsWith(libelle));
}

function lien<T>(f: ComponentFixture<T>, libelle: string): HTMLAnchorElement {
  f.detectChanges();
  return [...(f.nativeElement as HTMLElement).querySelectorAll('a')].find((a) => a.textContent?.trim().startsWith(libelle))!;
}

function cocher<T>(f: ComponentFixture<T>, libelle: string): void {
  const l = [...(f.nativeElement as HTMLElement).querySelectorAll('label')].find((x) => x.textContent?.trim().startsWith(libelle))!;
  l.querySelector('input')!.click();
  f.detectChanges();
}

function saisir<T>(f: ComponentFixture<T>, selecteur: string, valeur: string): void {
  const champ = (f.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event('input'));
  f.detectChanges();
}

describe('Messagerie (v0.46)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('outils : SMS facturés, recherche dans l’annuaire sans accents', () => {
    expect(segmentsSms('x'.repeat(160))).toBe(1);
    expect(segmentsSms('x'.repeat(161))).toBe(2);
    expect(segmentsSms('x'.repeat(300))).toBe(2);
    expect(chercherPersonnes(ANNUAIRE, 'awa ouedraogo', new Set()).map((p) => p.nom)).toEqual(['OUEDRAOGO Mariam']);
    expect(chercherPersonnes(ANNUAIRE, 'Ouédraogo', new Set()).map((p) => p.nom)).toEqual(['OUEDRAOGO Mariam']);
    expect(chercherPersonnes(ANNUAIRE, 'parent', new Set(['U:u3'])).map((p) => p.nom)).toEqual(['SAWADOGO Issa']);
    expect(chercherPersonnes(ANNUAIRE, '  ', new Set())).toEqual([]);
  });

  it('enseignant : lit un message reçu, qui passe à « lu » ; pas d’écriture', async () => {
    await session(http, 'ENSEIGNANT');
    const api = TestBed.inject(MessagerieApi);
    api.nonLus.set(1);
    const f = TestBed.createComponent(MessagesPage);
    f.detectChanges();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/messages').flush({
      messages: [{ id: 'm1', objet: 'Réunion', extrait: 'Samedi à 9 h', auteur: 'Mathieu KABORE', envoyeLe: '2026-03-02T08:00:00Z', lu: false }],
      total: 1,
      nonLus: 1,
    });
    await attendre();
    expect(texte(f)).toContain('Réunion');
    expect(lien(f, 'Écrire')).toBeUndefined();
    expect((f.nativeElement as HTMLElement).querySelector('.non-lu')).not.toBeNull();
    bouton(f, 'Réunion')!.click();
    await attendre();
    http.expectOne('/api/v1/messages/m1').flush({
      id: 'm1', objet: 'Réunion', corps: 'Samedi à 9 h, salle des fêtes.', auteur: 'Mathieu KABORE',
      envoyeLe: '2026-03-02T08:00:00Z', luLe: '2026-03-02T09:00:00Z', sms: false,
    });
    await attendre();
    expect(texte(f)).toContain('Samedi à 9 h, salle des fêtes.');
    expect((f.nativeElement as HTMLElement).querySelector('.non-lu')).toBeNull();
    expect(api.nonLus()).toBe(0);
  });

  it('chef d’établissement : écrit aux parents et à une personne, voit le décompte, confirme puis suit la lecture', async () => {
    await session(http, 'ADMIN_ECOLE');
    const f = TestBed.createComponent(MessagesPage);
    f.detectChanges();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/messages').flush({ messages: [], total: 0, nonLus: 0 });
    await attendre();
    expect(texte(f)).toContain('Aucun message.');

    lien(f, 'Écrire').click();
    await attendre();
    http.match('/api/v1/annees').forEach((r) => r.flush([ANNEE]));
    await attendre();
    http.expectOne('/api/v1/messages/annuaire').flush(ANNUAIRE);
    http.expectOne('/api/v1/annees/a1/classes').flush([
      { id: 'c1', anneeId: 'a1', filiereId: 'f', filiereCode: 'G', profilId: 'p', code: '6e A', niveau: '6e', effectifMax: 60 },
    ]);
    await attendre();
    expect(bouton(f, 'Envoyer')!.disabled).toBe(true);

    // Tous les parents : un parent sans compte n'est pas joint sans SMS
    cocher(f, 'Tous les parents');
    await attendre();
    let apercu = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/messages/apercu');
    expect(apercu.request.body.groupes).toEqual(['PARENTS']);
    expect(apercu.request.body.sms).toBe(false);
    apercu.flush({ destinataires: 1, comptes: 1, smsSeulement: 0, injoignables: 1, sms: 0 });
    await attendre();
    expect(texte(f)).toContain('1 parent(s) sans compte ne seront pas joints');
    expect(texte(f)).not.toContain('Ou les parents de certaines classes');

    cocher(f, 'Envoyer aussi par SMS');
    await attendre();
    apercu = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/messages/apercu');
    expect(apercu.request.body.sms).toBe(true);
    apercu.flush({ destinataires: 3, comptes: 2, smsSeulement: 1, injoignables: 0, sms: 3 });
    await attendre();
    expect(texte(f)).toContain('3 destinataire(s) : 2 dans l\'application, 1 par SMS seulement');

    // Une personne choisie dans l'annuaire
    saisir(f, 'input[type=search]', 'paul');
    bouton(f, '+ SANOU Paul')!.click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/messages/apercu')
      .flush({ destinataires: 4, comptes: 3, smsSeulement: 1, injoignables: 0, sms: 4 });
    await attendre();
    expect(texte(f)).toContain('SANOU Paul (Enseignant)');

    saisir(f, '#objet', 'Réunion des parents');
    const corps = (f.nativeElement as HTMLElement).querySelector('#corps') as HTMLTextAreaElement;
    corps.value = 'Samedi à 9 h, salle des fêtes.';
    corps.dispatchEvent(new Event('input'));
    f.detectChanges();
    expect(texte(f)).toContain('(30 / 2000)');
    bouton(f, 'Envoyer')!.click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/messages/apercu')
      .flush({ destinataires: 4, comptes: 3, smsSeulement: 1, injoignables: 0, sms: 4 });
    await attendre();
    expect(texte(f)).toContain('Envoyer « Réunion des parents » à 4 personne(s) (4 SMS) ?');
    bouton(f, "Confirmer l'envoi")!.click();
    await attendre();
    const envoi = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/messages');
    expect(envoi.request.body).toEqual({
      objet: 'Réunion des parents',
      corps: 'Samedi à 9 h, salle des fêtes.',
      sms: true,
      groupes: ['PARENTS'],
      roles: [],
      classes: [],
      utilisateurs: ['u2'],
      responsables: [],
    });
    envoi.flush({ id: 'm9', destinataires: 4, comptes: 3, sms: 4 });
    await attendre();
    expect(texte(f)).toContain('Message envoyé à 4 personne(s), dont 4 par SMS');
    expect(((f.nativeElement as HTMLElement).querySelector('#objet') as HTMLInputElement).value).toBe('');

    // Suivi de la lecture
    lien(f, 'Envoyés').click();
    await attendre();
    http.expectOne('/api/v1/messages/envoyes').flush([
      { id: 'm9', objet: 'Réunion des parents', cibles: 'Tous les parents · 1 personne(s) choisie(s)', auteur: 'Mathieu KABORE',
        envoyeLe: '2026-03-02T08:00:00Z', sms: true, destinataires: 4, comptes: 3, lus: 1 },
    ]);
    await attendre();
    expect(texte(f)).toContain('1 / 3');
    bouton(f, 'Détail')!.click();
    await attendre();
    http.expectOne('/api/v1/messages/m9/destinataires').flush([
      { nom: 'SANOU Paul', qualite: 'Enseignant', compte: true, sms: true, luLe: '2026-03-02T09:00:00Z' },
      { nom: 'SAWADOGO Issa', qualite: 'Parent de Ali', compte: false, sms: true, luLe: null },
    ]);
    await attendre();
    const t = texte(f);
    expect(t).toContain('SAWADOGO Issa');
    expect(t).toContain('lu le');
  });
});
