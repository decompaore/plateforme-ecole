import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AnneeCourante } from '../admin/annee-courante.service';
import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { erreurFiche, lireHeures } from './modeles-progression';
import { FicheProgressionPage } from './pages/fiche-progression.page';
import { MesProgressionsPage } from './pages/mes-progressions.page';
import { SuiviProgressionsPage } from './pages/suivi-progressions.page';

const ANNEE = { id: 'a1', libelle: '2026-2027', debut: '2026-10-01', fin: '2027-07-31', etat: 'ACTIVE' };
const URL_FICHE = '/api/v1/classes/c1/matieres/m-elec/progression';

async function session(http: HttpTestingController, role: Role, annees = false): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('61000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u5', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'SANOU', prenoms: 'Paul' });
  await connexion;
  if (annees) {
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
  await attendre();
  const champ = (f.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement | HTMLTextAreaElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event('input'));
  f.detectChanges();
}

function fiche(autres: object = {}) {
  return {
    id: null, classeId: 'c1', classeCode: '2nde F3', matiereId: 'm-elec', matiereCode: 'ELEC', matiereLibelle: 'Électrotechnique',
    type: 'TECHNIQUE', domaine: 'TECHNIQUE', engagementId: 'g1', enseignant: 'SANOU Paul', statut: null, sequences: [],
    heuresPrevues: 0, volumeHebdo: 4, volumeTotal: null, modifieeLe: null, soumiseLe: null, viseLe: null, visePar: null,
    commentaireVisa: null, modifiable: true, visable: false,
    ...autres,
  };
}

const ligne = (classe: string, matiere: string, statut: string | null, sequences = 0) => ({
  classeId: classe, classeCode: classe, niveau: '2nde', matiereId: 'm-' + matiere, matiereCode: matiere, matiereLibelle: matiere,
  type: 'TECHNIQUE', domaine: 'TECHNIQUE', engagementId: 'g1', enseignant: 'SANOU Paul', statut, sequences,
  heuresPrevues: sequences * 10, volumeHebdo: 4, soumiseLe: null, viseLe: null,
});

describe('Fiches de progression', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('contrôle les séquences avant l’envoi', () => {
    expect(lireHeures('12,5')).toBe(12.5);
    expect(lireHeures('12.25')).toBeNull();
    expect(lireHeures('0')).toBeNull();
    const s = { titre: 'Lois', contenu: '', competences: '', heures: '10', semaineDebut: '' };
    expect(erreurFiche([s])).toBeNull();
    expect(erreurFiche([s, { ...s, titre: ' ' }])).toBe('Séquence 2 : donnez un titre.');
    expect(erreurFiche([{ ...s, heures: 'dix' }])).toContain('heures prévues');
  });

  it('enseignant : liste de ses fiches, renvoi signalé', async () => {
    await session(http, 'ENSEIGNANT');
    const f = TestBed.createComponent(MesProgressionsPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/espace-enseignant/progressions').flush([ligne('2nde F3', 'ELEC', 'A_REVOIR', 3), ligne('1re F3', 'DESSIN', null)]);
    await attendre();
    const t = texte(f);
    expect(t).toContain('1 progression(s) renvoyée(s)');
    expect(t).toContain('3 séquence(s) · 30 h prévues');
    expect(t).toContain('Non commencée');
    expect(t).toContain('À revoir');
  });

  it('enseignant : prépare la première séquence et soumet au chef des travaux', async () => {
    await session(http, 'ENSEIGNANT');
    const f = TestBed.createComponent(FicheProgressionPage);
    f.componentRef.setInput('classeId', 'c1');
    f.componentRef.setInput('matiereId', 'm-elec');
    f.detectChanges();
    await attendre();
    http.expectOne(URL_FICHE).flush(fiche());
    await attendre();
    expect(texte(f)).toContain('visa du chef des travaux');
    expect(bouton(f, 'Soumettre au visa').disabled).toBe(true); // séquence vide

    await saisir(f, '#titre-0', 'Lois de l’électricité');
    await saisir(f, '#heures-0', '12,5');
    await saisir(f, '#contenu-0', 'Loi d’Ohm');
    expect(texte(f)).toContain('1 séquence(s) · 12,5 h');
    expect(texte(f)).toContain('modifications non enregistrées');
    bouton(f, 'Soumettre au visa').click();
    await attendre();
    const put = http.expectOne((r) => r.url === URL_FICHE && r.method === 'PUT');
    expect(put.request.body).toEqual({
      sequences: [{ titre: 'Lois de l’électricité', contenu: 'Loi d’Ohm', competences: null, heuresPrevues: 12.5, semaineDebut: null }],
    });
    const seq = { ordre: 1, titre: 'Lois de l’électricité', contenu: 'Loi d’Ohm', competences: null, heuresPrevues: 12.5, semaineDebut: null };
    put.flush(fiche({ id: 'fp1', statut: 'BROUILLON', sequences: [seq], heuresPrevues: 12.5 }));
    await attendre();
    http.expectOne(`${URL_FICHE}/soumission`).flush(
      fiche({ id: 'fp1', statut: 'SOUMISE', sequences: [seq], heuresPrevues: 12.5, soumiseLe: '2026-10-03T10:00:00Z', modifiable: false }),
    );
    await attendre();
    const t = texte(f);
    expect(t).toContain('Progression soumise au visa du chef des travaux.');
    expect(t).toContain('en attente du visa');
    expect(t).toContain('1. Lois de l’électricité');
  });

  it('chef des travaux : renvoie la fiche avec un commentaire obligatoire', async () => {
    await session(http, 'CHEF_TRAVAUX');
    const f = TestBed.createComponent(FicheProgressionPage);
    f.componentRef.setInput('classeId', 'c1');
    f.componentRef.setInput('matiereId', 'm-elec');
    f.detectChanges();
    await attendre();
    const seq = { ordre: 1, titre: 'Lois', contenu: null, competences: 'C1', heuresPrevues: 10, semaineDebut: '2026-10-05' };
    http.expectOne(URL_FICHE).flush(fiche({ id: 'fp1', statut: 'SOUMISE', sequences: [seq], heuresPrevues: 10, soumiseLe: '2026-10-03T10:00:00Z', modifiable: false, visable: true }));
    await attendre();
    expect(texte(f)).toContain('10 h · à partir du 05/10/2026');
    bouton(f, 'Renvoyer').click();
    await attendre();
    expect(texte(f)).toContain("Dites à l'enseignant ce qu'il faut revoir.");
    await saisir(f, '#commentaire', 'Ajoutez les TP d’atelier');
    bouton(f, 'Renvoyer').click();
    await attendre();
    const visa = http.expectOne(`${URL_FICHE}/visa`);
    expect(visa.request.body).toEqual({ accepte: false, commentaire: 'Ajoutez les TP d’atelier' });
    visa.flush(fiche({ id: 'fp1', statut: 'A_REVOIR', sequences: [seq], viseLe: '2026-10-03T11:00:00Z', visePar: 'OUEDRAOGO Salif', commentaireVisa: 'Ajoutez les TP d’atelier', modifiable: false }));
    await attendre();
    const t = texte(f);
    expect(t).toContain('renvoyée à l\'enseignant');
    expect(t).toContain('À revoir (OUEDRAOGO Salif');
  });

  it('suivi : fiches à viser et non commencées', async () => {
    await session(http, 'CHEF_TRAVAUX', true);
    const f = TestBed.createComponent(SuiviProgressionsPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/annees/a1/progressions').flush([
      ligne('2nde F3', 'ELEC', 'SOUMISE', 4), ligne('2nde F3', 'DESSIN', null), ligne('1re F3', 'ELEC', 'VISEE', 5), ligne('1re F3', 'ATELIER', 'BROUILLON', 1),
    ]);
    await attendre();
    const tuiles = [...(f.nativeElement as HTMLElement).querySelectorAll('.chiffres strong')].map((x) => x.textContent?.trim());
    expect(tuiles).toEqual(['1', '1', '1', '1']);
    expect(texte(f)).toContain('Matières techniques et pratiques');
    bouton(f, 'À viser').click();
    f.detectChanges();
    const titres = [...(f.nativeElement as HTMLElement).querySelectorAll('section h2')].map((h) => h.textContent?.trim());
    expect(titres).toEqual(['2nde F3']);
    expect(texte(f)).toContain('4 séquence(s) · 40 h');
  });
});
