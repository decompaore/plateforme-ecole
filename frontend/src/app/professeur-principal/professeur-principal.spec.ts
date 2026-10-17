import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AnneeCourante } from '../admin/annee-courante.service';
import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { ClassePrincipale, SaisineVue, VerificationVue } from './modeles-pp';
import { DossierPage } from './pages/dossier.page';
import { ProfesseursPrincipauxPage } from './pages/professeurs-principaux.page';
import { VerificationPage } from './pages/verification.page';

const ANNEE = { id: 'a1', libelle: '2026-2027', debut: '2026-10-01', fin: '2027-07-31', etat: 'ACTIVE' };

async function session(http: HttpTestingController, role: Role, annee = false): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('61000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'KABORE', prenoms: 'Paul' });
  await connexion;
  if (annee) {
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
  const champ = (f.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement | HTMLSelectElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event(champ instanceof HTMLSelectElement ? 'change' : 'input'));
  f.detectChanges();
}

function classe(autres: Partial<ClassePrincipale> = {}): ClassePrincipale {
  return {
    classeId: 'c1', classeCode: '3e A', niveau: '3e', profilId: 'pg', professeurId: null, professeur: null, designeLe: null,
    enseignants: [
      { utilisateurId: 'u1', nom: 'KABORE Paul', matieres: ['Mathématiques'] },
      { utilisateurId: 'u2', nom: 'SAWADOGO Rita', matieres: ['Français'] },
    ],
    saisinesOuvertes: 0, verifications: [],
    ...autres,
  };
}

function saisine(autres: Partial<SaisineVue> = {}): SaisineVue {
  return {
    id: 's1', numero: 'PP-2026-0001', classeId: 'c1', classeCode: '3e A', nature: 'MEDIATION', objet: 'Propos blessants',
    expose: 'Ali dit avoir été humilié.', auteur: 'KABORE Paul', professeur: 'KABORE Paul',
    eleves: [{ inscriptionId: 'i2', nom: 'NIKIEMA Ali' }], matiereId: null, matiere: null, enseignantId: 'u2',
    enseignant: 'SAWADOGO Rita', enseignantInforme: false, statut: 'OUVERTE', transmiseLe: null, conclusion: null,
    closeLe: null, closePar: null, creeLe: '2026-10-16T08:00:00Z', suites: 0, journal: [],
    droits: { ecrire: true, informer: true, transmettre: true, clore: true },
    ...autres,
  };
}

function verification(autres: Partial<VerificationVue> = {}): VerificationVue {
  return {
    classeId: 'c1', classeCode: '3e A', periodeId: 'p1', periode: '1er trimestre', periodeVerrouillee: false,
    professeur: 'KABORE Paul', statut: 'A_FAIRE', valideeLe: null, valideePar: null, observations: null, reclamations: [],
    professeurPrincipal: true, peutValider: true,
    ...autres,
  };
}

describe('Professeur principal (v0.49.1)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('censeur : désigne le professeur principal parmi les enseignants de la classe', async () => {
    await session(http, 'CENSEUR', true);
    const f = TestBed.createComponent(ProfesseursPrincipauxPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/annees/a1/professeurs-principaux').flush([classe()]);
    http.expectOne((r) => r.url === '/api/v1/saisines' && r.params.get('anneeId') === 'a1').flush([]);
    await attendre();
    expect(texte(f)).toContain('1 classe(s) sans professeur principal.');

    await saisir(f, 'select[aria-label="Professeur principal de la 3e A"]', '1: u1');
    await attendre();
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/classes/c1/professeur-principal');
    expect(req.request.body).toEqual({ utilisateurId: 'u1' });
    req.flush(classe({ professeurId: 'u1', professeur: 'KABORE Paul', designeLe: '2026-10-16T08:00:00Z' }));
    await attendre();
    const t = texte(f);
    expect(t).toContain('KABORE Paul est professeur principal de la 3e A');
    expect(t).not.toContain('sans professeur principal');
  });

  it('professeur principal : parcourt les moyennes, enregistre une réclamation sur une évaluation', async () => {
    await session(http, 'ENSEIGNANT');
    const f = TestBed.createComponent(VerificationPage);
    f.componentRef.setInput('classeId', 'c1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/classes/c1').flush({ id: 'c1', anneeId: 'a1', filiereId: 'f1', filiereCode: 'G', profilId: 'pg', code: '3e A', niveau: '3e', effectifMax: null });
    await attendre();
    http.expectOne('/api/v1/annees/a1/periodes').flush([
      { id: 'p1', anneeId: 'a1', profilId: 'pg', libelle: '1er trimestre', ordre: 1, debut: '2026-01-01', fin: '2026-12-20', verrouillee: false },
      { id: 'px', anneeId: 'a1', profilId: 'autre', libelle: 'Semestre 1', ordre: 1, debut: '2026-01-01', fin: '2026-12-20', verrouillee: false },
    ]);
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/classes/c1/verification-notes' && r.params.get('periodeId') === 'p1').flush(verification());
    http.expectOne((r) => r.url === '/api/v1/classes/c1/resultats').flush({
      classeId: 'c1', periodeId: 'p1', moyenneClasse: 10,
      eleves: [
        { inscriptionId: 'i1', nom: 'OUEDRAOGO', prenoms: 'Awa', moyenne: 8, rang: 2,
          matieres: [{ matiereId: 'm1', code: 'MATH', libelle: 'Mathématiques', moyenne: 8, notes: 1, sansNote: false }] },
        { inscriptionId: 'i2', nom: 'NIKIEMA', prenoms: 'Ali', moyenne: 12, rang: 1,
          matieres: [{ matiereId: 'm1', code: 'MATH', libelle: 'Mathématiques', moyenne: 12, notes: 1, sansNote: false }] },
      ],
    });
    http.expectOne((r) => r.url === '/api/v1/classes/c1/evaluations').flush([{ id: 'e1', matiereId: 'm1', libelle: 'Devoir 1', date: '2026-10-10' }]);
    await attendre();
    let t = texte(f);
    expect(t).toContain('Vérification des notes — 3e A');
    expect(t).toContain('OUEDRAOGO Awa');
    expect(t).not.toContain('Semestre 1');

    await saisir(f, '#rEvaluation', '1: e1');
    await saisir(f, '#rMotif', '12 sur la copie, 8 saisi');
    bouton(f, "Transmettre à l'enseignant").click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/classes/c1/verification-notes/reclamations');
    expect(req.request.body).toEqual({ periodeId: 'p1', inscriptionId: 'i1', matiereId: 'm1', evaluationId: 'e1', motif: '12 sur la copie, 8 saisi' });
    req.flush(verification({
      statut: 'EN_COURS', peutValider: false,
      reclamations: [{
        id: 'r1', classeId: 'c1', classeCode: '3e A', periodeId: 'p1', periode: '1er trimestre', inscriptionId: 'i1',
        eleve: 'OUEDRAOGO Awa', matiereId: 'm1', matiere: 'Mathématiques', evaluationId: 'e1', evaluation: 'Devoir 1',
        noteContestee: 8, motif: '12 sur la copie, 8 saisi', enseignant: 'KABORE Paul', enregistreePar: 'KABORE Paul',
        enregistreeLe: '2026-10-16T09:00:00Z', statut: 'OUVERTE', noteCorrigee: null, reponse: null, reponduePar: null,
        reponduLe: null, peutRepondre: false,
      }],
    }));
    await attendre();
    t = texte(f);
    expect(t).toContain("Réclamation transmise à l'enseignant de la matière.");
    expect(t).toContain("En attente de l'enseignant");
    expect(bouton(f, 'Attester la vérification').disabled).toBe(true);
  });

  it('médiation : le professeur principal informe l’enseignant puis transmet à l’administration', async () => {
    await session(http, 'ENSEIGNANT');
    const f = TestBed.createComponent(DossierPage);
    f.componentRef.setInput('id', 's1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/saisines/s1').flush(saisine());
    await attendre();
    expect(texte(f)).toContain('pas encore informé(e)');

    bouton(f, 'Informer SAWADOGO Rita').click();
    await attendre();
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/saisines/s1/information-enseignant')
      .flush(saisine({ enseignantInforme: true, droits: { ecrire: true, informer: false, transmettre: true, clore: true } }));
    await attendre();
    expect(texte(f)).toContain('SAWADOGO Rita est informé(e)');

    bouton(f, "Transmettre à l'administration").click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/saisines/s1/transmission');
    expect(req.request.body).toEqual({ texte: null });
    req.flush(saisine({ enseignantInforme: true, statut: 'TRANSMISE', transmiseLe: '2026-10-16T10:00:00Z',
      droits: { ecrire: true, informer: false, transmettre: false, clore: true } }));
    await attendre();
    const t = texte(f);
    expect(t).toContain("Dossier transmis à l'administration.");
    expect(t).toContain("Transmis à l'administration le");
  });
});
