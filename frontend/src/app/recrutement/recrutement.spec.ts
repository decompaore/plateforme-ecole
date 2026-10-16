import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { AnneeCourante } from '../admin/annee-courante.service';
import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { CandidatureVue, ClassementOffre, CommissionVue, Droits } from './modeles-recrutement';
import { CommissionPage } from './pages/commission.page';
import { CommissionsPage } from './pages/commissions.page';

const ANNEE = { id: 'a1', libelle: '2026-2027', debut: '2026-10-01', fin: '2027-07-31', etat: 'ACTIVE' };

async function session(http: HttpTestingController, role: Role, annee = false): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('61000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u1', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'OUEDRAOGO', prenoms: 'Paul' });
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

const AUCUN: Droits = { modifier: false, saisir: false, deliberer: false, inscrire: false, clore: false };

function commission(autres: Partial<CommissionVue> = {}): CommissionVue {
  return {
    id: 'k1', numero: 'CR-2026-01', anneeId: 'a1', annee: '2026-2027', intitule: "Complément d'effectif 2026-2027",
    dateSession: '2026-10-20', lieu: 'Salle des professeurs', dateLimiteDepot: null, criteres: null, moyenneMinimale: 10,
    poidsExamen: 0, tailleListeAttente: 5, statut: 'PREPARATION', observationsPv: null, delibereeLe: null, delibereePar: null,
    closeLe: null,
    membres: [
      { id: 'm1', qualite: 'PRESIDENT', utilisateurId: 'u1', nom: 'OUEDRAOGO Paul', fonction: 'Proviseur', present: true },
      { id: 'm2', qualite: 'RAPPORTEUR', utilisateurId: 'u3', nom: 'SAWADOGO Aline', fonction: 'CPE', present: true },
    ],
    offres: [{
      id: 'o1', niveau: '2nde', filiereId: 'f1', filiere: 'F3', places: 0, disponibles: 3, candidatures: 0, retenues: 0,
      listeAttente: 0, inscrites: 0,
      classes: [{ id: 'c1', code: '2nde F3 A', effectifMax: 40, inscrits: 38 }, { id: 'c2', code: '2nde F3 B', effectifMax: 40, inscrits: 39 }],
    }],
    droits: { ...AUCUN, modifier: true, saisir: true },
    ...autres,
  };
}

function candidature(autres: Partial<CandidatureVue> = {}): CandidatureVue {
  return {
    id: 'd1', numero: 'CAND-2026-0001', offre: '2nde F3', offreId: 'o1', nom: 'KABORE', prenoms: 'Awa', sexe: 'F',
    dateNaissance: '2010-03-12', lieuNaissance: null, responsableNom: 'KABORE', responsablePrenoms: 'Issa',
    responsableTelephone: '70000001', responsableLien: 'PERE', etablissementOrigine: 'CEG de Réo', classeOrigine: '3e A',
    moyenneAnnuelle: 14.5, noteExamen: null, redoublant: false, observations: null, statut: 'DEPOSEE', motif: null, score: null,
    rang: null, repechee: false, inscriptionId: null, inscriteLe: null, classeInscription: null,
    ...autres,
  };
}

describe('Commission de recrutement (v0.49)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('proviseur : liste des commissions, puis création d’une commission', async () => {
    await session(http, 'ADMIN_ECOLE', true);
    const router = TestBed.inject(Router);
    const nav = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const f = TestBed.createComponent(CommissionsPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/annees/a1/commissions-recrutement').flush([]);
    await attendre();
    expect(texte(f)).toContain('Aucune commission pour cette année.');

    bouton(f, 'Nouvelle commission').click();
    await saisir(f, '#cDate', '2026-10-20');
    await saisir(f, '#cLieu', 'Salle des professeurs');
    bouton(f, 'Créer la commission').click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/annees/a1/commissions-recrutement');
    expect(req.request.body).toEqual({
      intitule: "Complément d'effectif 2026-2027", dateSession: '2026-10-20', lieu: 'Salle des professeurs',
      dateLimiteDepot: null, criteres: null, moyenneMinimale: 10, poidsExamen: 0, tailleListeAttente: 5,
    });
    req.flush(commission());
    await attendre();
    expect(nav).toHaveBeenCalledWith(['/recrutement', 'k1']);
  });

  it('censeur : places offertes, puis enregistrement d’une candidature et aperçu du classement', async () => {
    await session(http, 'CENSEUR');
    const f = TestBed.createComponent(CommissionPage);
    f.componentRef.setInput('id', 'k1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/commissions-recrutement/k1').flush(commission());
    http.expectOne('/api/v1/commissions-recrutement/k1/candidatures').flush([]);
    await attendre();
    let t = texte(f);
    expect(t).toContain('CR-2026-01');
    expect(t).toContain('En préparation');
    expect(t).toContain('Président · OUEDRAOGO Paul');

    bouton(f, 'Places').click();
    await saisir(f, 'input[aria-label="Places 2nde F3"]', '3');
    bouton(f, 'Enregistrer les places').click();
    await attendre();
    const places = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/commissions-recrutement/k1/offres');
    expect(places.request.body).toEqual({ offres: [{ offreId: 'o1', places: 3 }] });
    const avecPlaces = commission({ offres: [{ ...commission().offres[0], places: 3 }] });
    places.flush(avecPlaces);
    await attendre();
    expect(texte(f)).toContain('Places enregistrées.');

    bouton(f, 'Candidatures').click();
    await attendre();
    http.expectOne('/api/v1/commissions-recrutement/k1/candidatures').flush([]);
    await attendre();
    bouton(f, 'Enregistrer une candidature').click();
    await saisir(f, '#kNom', 'KABORE');
    await saisir(f, '#kPrenoms', 'Awa');
    await saisir(f, '#kNaissance', '2010-03-12');
    await saisir(f, '#kOrigine', 'CEG de Réo');
    await saisir(f, '#kClasse', '3e A');
    await saisir(f, '#kMoyenne', '14.5');
    await saisir(f, '#kRespNom', 'KABORE');
    await saisir(f, '#kRespPrenoms', 'Issa');
    await saisir(f, '#kRespTel', '70000001');
    bouton(f, 'Enregistrer').click();
    await attendre();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/commissions-recrutement/k1/candidatures');
    expect(req.request.body).toEqual({
      offreId: 'o1', nom: 'KABORE', prenoms: 'Awa', sexe: 'F', dateNaissance: '2010-03-12', lieuNaissance: null,
      responsableNom: 'KABORE', responsablePrenoms: 'Issa', responsableTelephone: '70000001', responsableLien: 'PERE',
      etablissementOrigine: 'CEG de Réo', classeOrigine: '3e A', moyenneAnnuelle: 14.5, noteExamen: null, redoublant: false,
      observations: null,
    });
    req.flush(candidature());
    await attendre();
    http.expectOne('/api/v1/commissions-recrutement/k1').flush(avecPlaces);
    http.expectOne('/api/v1/commissions-recrutement/k1/candidatures').flush([candidature()]);
    await attendre();
    t = texte(f);
    expect(t).toContain('Candidature CAND-2026-0001 enregistrée.');
    expect(t).toContain('KABORE Awa');

    bouton(f, 'Classement').click();
    await attendre();
    const apercu: ClassementOffre[] = [{
      offreId: 'o1', offre: '2nde F3', places: 3,
      lignes: [{ candidatureId: 'd1', numero: 'CAND-2026-0001', nom: 'KABORE', prenoms: 'Awa', sexe: 'F', moyenneAnnuelle: 14.5,
        noteExamen: null, score: 14.5, rang: 1, decision: 'RETENUE', motif: null }],
    }];
    http.expectOne('/api/v1/commissions-recrutement/k1/classement').flush(apercu);
    http.expectOne('/api/v1/commissions-recrutement/k1/candidatures').flush([candidature()]);
    await attendre();
    t = texte(f);
    expect(t).toContain("Aperçu du classement au mérite");
    expect(t).toContain('2nde F3 — 3 place(s)');
    expect(t).not.toContain('Délibération');
    expect(t).not.toContain('Inscrire');
  });

  it('intendant : inscrit un retenu, puis un désistement repêche la liste d’attente', async () => {
    await session(http, 'INTENDANT');
    const deliberee = commission({
      statut: 'DELIBEREE', delibereeLe: '2026-10-20T10:00:00Z', delibereePar: 'OUEDRAOGO Paul',
      droits: { ...AUCUN, inscrire: true },
    });
    const retenue = candidature({ statut: 'RETENUE', score: 14.5, rang: 1 });
    const attente = candidature({ id: 'd2', numero: 'CAND-2026-0002', nom: 'ZONGO', prenoms: 'Ali', statut: 'LISTE_ATTENTE', rang: 2, score: 12 });
    const classement = (d1: CandidatureVue, d2: CandidatureVue): ClassementOffre[] => [{
      offreId: 'o1', offre: '2nde F3', places: 1,
      lignes: [d1, d2].map((k) => ({ candidatureId: k.id, numero: k.numero, nom: k.nom, prenoms: k.prenoms, sexe: k.sexe,
        moyenneAnnuelle: k.moyenneAnnuelle, noteExamen: null, score: k.score, rang: k.rang, decision: k.statut, motif: null })),
    }];
    const f = TestBed.createComponent(CommissionPage);
    f.componentRef.setInput('id', 'k1');
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/commissions-recrutement/k1').flush(deliberee);
    http.expectOne('/api/v1/commissions-recrutement/k1/candidatures').flush([retenue, attente]);
    await attendre();

    bouton(f, 'Résultats').click();
    await attendre();
    http.expectOne('/api/v1/commissions-recrutement/k1/classement').flush(classement(retenue, attente));
    http.expectOne('/api/v1/commissions-recrutement/k1/candidatures').flush([retenue, attente]);
    await attendre();
    let t = texte(f);
    expect(t).toContain('Procès-verbal (PDF)');
    expect(t).not.toContain('Clore la commission');

    // Désistement du retenu : le premier de la liste d'attente est repêché
    bouton(f, 'Désistement').click();
    await attendre();
    const des = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/candidatures/d1/desistement');
    const repeche = { ...attente, statut: 'RETENUE' as const, repechee: true };
    des.flush({ desistee: { ...retenue, statut: 'DESISTEE' }, repechee: repeche });
    await attendre();
    http.expectOne('/api/v1/commissions-recrutement/k1').flush(deliberee);
    http.expectOne('/api/v1/commissions-recrutement/k1/candidatures').flush([{ ...retenue, statut: 'DESISTEE' }, repeche]);
    await attendre();
    http.expectOne('/api/v1/commissions-recrutement/k1/classement').flush(classement({ ...retenue, statut: 'DESISTEE' }, repeche));
    http.expectOne('/api/v1/commissions-recrutement/k1/candidatures').flush([{ ...retenue, statut: 'DESISTEE' }, repeche]);
    await attendre();
    t = texte(f);
    expect(t).toContain('ZONGO Ali est repêché(e)');
    expect(t).toContain('(repêché)');

    // Inscription du repêché en 2nde F3 B
    await saisir(f, 'select[aria-label="Classe pour ZONGO"]', 'c2');
    bouton(f, 'Inscrire').click();
    await attendre();
    const ins = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/candidatures/d2/inscription');
    expect(ins.request.body).toEqual({ classeId: 'c2' });
    const inscrite = { ...repeche, statut: 'INSCRITE' as const, classeInscription: '2nde F3 B' };
    ins.flush(inscrite);
    await attendre();
    http.expectOne('/api/v1/commissions-recrutement/k1').flush(deliberee);
    http.expectOne('/api/v1/commissions-recrutement/k1/candidatures').flush([{ ...retenue, statut: 'DESISTEE' }, inscrite]);
    await attendre();
    http.expectOne('/api/v1/commissions-recrutement/k1/classement').flush(classement({ ...retenue, statut: 'DESISTEE' }, inscrite));
    http.expectOne('/api/v1/commissions-recrutement/k1/candidatures').flush([{ ...retenue, statut: 'DESISTEE' }, inscrite]);
    await attendre();
    t = texte(f);
    expect(t).toContain("ZONGO Ali inscrit(e) en 2nde F3 B (complément d'effectif).");
    expect(t).toContain('· 2nde F3 B');
  });
});
