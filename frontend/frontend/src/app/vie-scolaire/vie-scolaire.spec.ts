import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { dateLocale } from '../core/outils';
import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { AbsencesJourPage } from './pages/absences-jour.page';
import { ConvocationsPage } from './pages/convocations.page';
import { FicheElevePage } from './pages/fiche-eleve.page';

async function session(http: HttpTestingController, role: Role): Promise<void> {
  const etab = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] };
  const connexion = TestBed.inject(SessionService).connexion('70000002', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(reponse('u9', 1, { etablissementActif: etab, etablissements: [etab] }));
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'KONE', prenoms: 'Awa' });
  await connexion;
}

function texte<T>(f: ComponentFixture<T>): string {
  f.detectChanges();
  return (f.nativeElement as HTMLElement).textContent ?? '';
}

function cliquer<T>(f: ComponentFixture<T>, libelle: string, index = 0): void {
  f.detectChanges();
  const boutons = [...(f.nativeElement as HTMLElement).querySelectorAll('button')].filter((b) => b.textContent?.trim() === libelle || b.textContent?.includes(libelle));
  if (!boutons[index]) {
    throw new Error(`Bouton « ${libelle} » introuvable`);
  }
  boutons[index].click();
}

function saisir<T>(f: ComponentFixture<T>, selecteur: string, valeur: string): void {
  f.detectChanges();
  const champ = (f.nativeElement as HTMLElement).querySelector(selecteur) as HTMLInputElement | HTMLSelectElement;
  champ.value = valeur;
  champ.dispatchEvent(new Event(champ instanceof HTMLSelectElement ? 'change' : 'input'));
}

const AUJOURDHUI = dateLocale();

const DU_JOUR = [
  {
    inscriptionId: 'i1', eleveId: 'e1', matricule: '2026-00001', nom: 'OUEDRAOGO', prenoms: 'Awa', classeId: 'c1', classeCode: '6e A',
    absences: 2, retards: 0, justifiee: false,
    creneaux: [
      { appelId: 'a1', heureDebut: '08:00:00', heureFin: '10:00:00', type: 'ABSENCE', minutesRetard: null, matiereId: 'm1', matiereCode: 'MATH', matiereLibelle: 'Mathématiques' },
      { appelId: 'a2', heureDebut: '10:00:00', heureFin: '12:00:00', type: 'ABSENCE', minutesRetard: null, matiereId: null, matiereCode: null, matiereLibelle: null },
    ],
  },
  {
    inscriptionId: 'i2', eleveId: 'e2', matricule: null, nom: 'SAWADOGO', prenoms: 'Ali', classeId: 'c1', classeCode: '6e A',
    absences: 0, retards: 1, justifiee: false,
    creneaux: [{ appelId: 'a2', heureDebut: '10:00:00', heureFin: '12:00:00', type: 'RETARD', minutesRetard: 10, matiereId: 'm2', matiereCode: 'FR', matiereLibelle: 'Français' }],
  },
  {
    inscriptionId: 'i3', eleveId: 'e3', matricule: null, nom: 'ZONGO', prenoms: 'Ines', classeId: 'c2', classeCode: '5e B',
    absences: 1, retards: 0, justifiee: true,
    creneaux: [{ appelId: 'a3', heureDebut: '08:00:00', heureFin: '09:00:00', type: 'ABSENCE', minutesRetard: null, matiereId: 'm1', matiereCode: 'MATH', matiereLibelle: 'Mathématiques' }],
  },
];

describe('Vie scolaire', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('absences du jour : bilan, filtre « à justifier », puis justification d’un élève', async () => {
    await session(http, 'SURVEILLANT');
    const f = TestBed.createComponent(AbsencesJourPage);
    f.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/absences/jour' && r.params.get('date') === AUJOURDHUI).flush(DU_JOUR);
    await attendre();

    expect(texte(f)).toContain('2 élève(s) absent(s)');
    expect(texte(f)).toContain('1 en retard');
    expect(texte(f)).toContain('1 absence(s) à justifier');
    expect(texte(f)).toContain('absent 08h00–10h00 · Mathématiques');
    expect(texte(f)).toContain('absent 10h00–12h00 · appel général');
    expect(texte(f)).toContain('retard 10 min 10h00–12h00 · Français');

    const caseJustifier = (f.nativeElement as HTMLElement).querySelector('input[type=checkbox]') as HTMLInputElement;
    caseJustifier.click();
    await attendre();
    expect(texte(f)).toContain('OUEDRAOGO');
    expect(texte(f)).not.toContain('SAWADOGO');
    expect(texte(f)).not.toContain('ZONGO');

    cliquer(f, 'Justifier');
    f.detectChanges();
    await f.whenStable();
    saisir(f, 'input[name=motif]', 'Certificat du CSPS');
    (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('app-justification button[type=submit]')!.click();
    await attendre();
    const req = http.expectOne('/api/v1/inscriptions/i1/justificatifs');
    expect(req.request.body).toEqual({ du: AUJOURDHUI, au: AUJOURDHUI, type: 'MALADIE', motif: 'Certificat du CSPS' });
    req.flush({ id: 'j1', inscriptionId: 'i1', du: AUJOURDHUI, au: AUJOURDHUI, type: 'MALADIE', motif: 'Certificat du CSPS' });
    await attendre();
    http.expectOne('/api/v1/absences/jour?date=' + AUJOURDHUI).flush(DU_JOUR.map((e) => (e.inscriptionId === 'i1' ? { ...e, justifiee: true } : e)));
    await attendre();
    expect(texte(f)).toContain('Absences de Awa OUEDRAOGO justifiées.');
    expect(texte(f)).toContain('0 absence(s) à justifier');
  });

  it('fiche élève : contacts, avertissement avec SMS, convocation ; blâme réservé à la direction', async () => {
    await session(http, 'SURVEILLANT');
    const f = TestBed.createComponent(FicheElevePage);
    f.componentRef.setInput('eleveId', 'e1');
    f.detectChanges();
    http.expectOne('/api/v1/eleves/e1').flush({
      eleve: { id: 'e1', matricule: '2026-00001', nom: 'OUEDRAOGO', prenoms: 'Awa', sexe: 'F', dateNaissance: '2013-04-02', lieuNaissance: null },
      responsables: [
        { responsableId: 'r2', nom: 'OUEDRAOGO', prenoms: 'Salif', telephone: '+22670111111', lien: 'PERE', contactPrioritaire: false },
        { responsableId: 'r1', nom: 'OUEDRAOGO', prenoms: 'Mariam', telephone: '+22670222222', lien: 'MERE', contactPrioritaire: true },
      ],
      inscriptions: [{ id: 'i1', eleveId: 'e1', anneeLibelle: '2026-2027', classeCode: '6e A', statut: 'ACTIVE' }],
    });
    await attendre();
    const historique = {
      inscriptionId: 'i1', nom: 'OUEDRAOGO', prenoms: 'Awa', classe: '6e A', annee: '2026-2027',
      synthese: { retards: 0, minutesRetard: 0, avertissements: 0, blames: 0, exclusions: 0, joursExclusion: 0, convocations: 0 },
      incidents: [], convocations: [],
    };
    const recharger = (h: object) => {
      http.expectOne('/api/v1/inscriptions/i1/vie-scolaire').flush(h);
      http.expectOne('/api/v1/inscriptions/i1/absences').flush([
        { id: 'x1', appelId: 'a1', inscriptionId: 'i1', date: '2026-10-05', heureDebut: '08:00:00', heureFin: '10:00:00', type: 'ABSENCE', minutesRetard: null, justifiee: false, matiereId: 'm1', matiereCode: 'MATH', matiereLibelle: 'Mathématiques' },
        { id: 'x2', appelId: 'a2', inscriptionId: 'i1', date: '2026-10-05', heureDebut: '10:00:00', heureFin: '11:30:00', type: 'ABSENCE', minutesRetard: null, justifiee: false, matiereId: 'm2', matiereCode: 'FR', matiereLibelle: 'Français' },
        { id: 'x3', appelId: 'a3', inscriptionId: 'i1', date: '2026-10-02', heureDebut: '08:00:00', heureFin: '10:00:00', type: 'ABSENCE', minutesRetard: null, justifiee: true, matiereId: 'm1', matiereCode: 'MATH', matiereLibelle: 'Mathématiques' },
      ]);
      http.expectOne('/api/v1/inscriptions/i1/justificatifs').flush([]);
    };
    recharger(historique);
    await attendre();

    const t = texte(f);
    // La mère reçoit les SMS : elle apparaît en premier
    expect(t.indexOf('Mariam')).toBeLessThan(t.indexOf('Salif'));
    expect(t).toContain('+22670222222');
    expect(t).toContain('5,5 h');
    expect(t).toContain('non justifiée');
    expect(t).toContain('08h00–10h00 · Mathématiques');
    // Par discipline : maths en tête (2 cours, 4 h dont 2 h non justifiées), puis français
    const lignes = [...(f.nativeElement as HTMLElement).querySelectorAll('table.disciplines tbody tr')].map((l) =>
      [...l.querySelectorAll('td')].map((c) => c.textContent?.trim()),
    );
    expect(lignes).toEqual([
      ['Mathématiques', '2', '4 h', '2 h', '0'],
      ['Français', '1', '1,5 h', '1,5 h', '0'],
    ]);

    cliquer(f, 'Signaler un incident');
    f.detectChanges();
    await f.whenStable();
    const types = [...(f.nativeElement as HTMLElement).querySelectorAll('#iType option')].map((o) => o.textContent?.trim());
    expect(types).toEqual(["Retard à l'entrée", 'Avertissement']);
    saisir(f, '#iMotif', 'Bavardages répétés');
    cliquer(f, 'Enregistrer');
    await attendre();
    const incident = http.expectOne('/api/v1/inscriptions/i1/incidents');
    expect(incident.request.body).toMatchObject({ type: 'AVERTISSEMENT', motif: 'Bavardages répétés', prevenirFamille: true, minutesRetard: null });
    const avertissement = {
      id: 'inc1', inscriptionId: 'i1', type: 'AVERTISSEMENT', date: AUJOURDHUI, motif: 'Bavardages répétés', minutesRetard: null,
      debutExclusion: null, finExclusion: null, joursExclusion: null, famillePrevenue: true, saisiLe: '', annule: false, motifAnnulation: null,
    };
    incident.flush(avertissement);
    await attendre();
    recharger({ ...historique, incidents: [avertissement], synthese: { ...historique.synthese, avertissements: 1 } });
    await attendre();
    expect(texte(f)).toContain('la famille est prévenue par SMS');

    // Convocation à partir de l'incident : motif prérempli
    cliquer(f, 'Convoquer', 0);
    f.detectChanges();
    await f.whenStable();
    expect(((f.nativeElement as HTMLElement).querySelector('#cMotif') as HTMLInputElement).value).toContain('Bavardages répétés');
    saisir(f, '#cRdv', '2026-10-20T09:30');
    (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('form[aria-label="Convoquer les parents"] button[type=submit]')!.click();
    await attendre();
    const convocation = http.expectOne('/api/v1/inscriptions/i1/convocations');
    expect(convocation.request.body).toMatchObject({ rendezVous: '2026-10-20T09:30', incidentId: 'inc1' });
    convocation.flush({ id: 'cv1', inscriptionId: 'i1', nom: 'OUEDRAOGO', prenoms: 'Awa', classe: '6e A', rendezVous: '2026-10-20T09:30:00', motif: 'x', statut: 'PREVUE', compteRendu: null, incidentId: 'inc1' });
    await attendre();
    recharger(historique);
    await attendre();
    expect(texte(f)).toContain('Parents convoqués le 20/10/2026 à 09h30');
  });

  it('convocations : un rendez-vous passé se clôture « parent venu »', async () => {
    await session(http, 'CENSEUR');
    const f = TestBed.createComponent(ConvocationsPage);
    f.detectChanges();
    http.expectOne((r) => r.url === '/api/v1/convocations').flush([]);
    await attendre();
    expect(texte(f)).toContain('Aucune convocation prévue');

    cliquer(f, '14 derniers jours');
    await attendre();
    const passee = { id: 'cv1', inscriptionId: 'i1', nom: 'OUEDRAOGO', prenoms: 'Awa', classe: '6e A', rendezVous: '2026-01-05T10:00:00', motif: 'Comportement', statut: 'PREVUE', compteRendu: null, incidentId: null };
    http.expectOne((r) => r.url === '/api/v1/convocations').flush([passee]);
    await attendre();
    expect(texte(f)).toContain('1 rendez-vous passé(s) à clôturer');

    cliquer(f, 'Venu');
    await attendre();
    const cloture = http.expectOne('/api/v1/convocations/cv1/cloture');
    expect(cloture.request.body).toEqual({ statut: 'HONOREE', compteRendu: null });
    cloture.flush({ ...passee, statut: 'HONOREE' });
    await attendre();
    expect(texte(f)).toContain('Awa OUEDRAOGO : honorée.');
    expect(texte(f)).not.toContain('à clôturer');
  });

  it('classe : absences par discipline et par élève, sur l’année puis sur un trimestre', async () => {
    await session(http, 'CENSEUR');
    const { ClasseVsPage } = await import('./pages/classe-vs.page');
    const f = TestBed.createComponent(ClasseVsPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/annees').flush([{ id: 'a1', libelle: '2026-2027', debut: '2026-10-01', fin: '2027-07-31', etat: 'ACTIVE' }]);
    await attendre();
    http.expectOne('/api/v1/annees/a1/classes').flush([
      { id: 'c1', anneeId: 'a1', filiereId: 'f', filiereCode: 'F3', profilId: 'pt', code: '2nde F3 A', niveau: '2nde', effectifMax: 60 },
    ]);
    http.expectOne('/api/v1/annees/a1/periodes').flush([
      { id: 'p1', anneeId: 'a1', profilId: 'pt', libelle: 'Trimestre 1', ordre: 1, debut: '2026-10-01', fin: '2026-12-20', verrouillee: false },
      { id: 'pg', anneeId: 'a1', profilId: 'pg', libelle: 'Autre profil', ordre: 1, debut: '2026-10-01', fin: '2026-12-20', verrouillee: false },
    ]);
    await attendre();

    saisir(f, '#classe', 'c1');
    await attendre();
    const repondre = (filtre: (p: URLSearchParams) => boolean) => {
      const m = http.expectOne((r) => r.url === '/api/v1/classes/c1/absences/matieres' && filtre(new URLSearchParams(r.params.toString())));
      m.flush([
        { matiereId: 'm1', matiereCode: 'EPS', matiereLibelle: 'EPS', absences: 12, heures: 24, heuresNonJustifiees: 20, eleves: 9, retards: 0 },
        { matiereId: null, matiereCode: null, matiereLibelle: null, absences: 2, heures: 2, heuresNonJustifiees: 0, eleves: 2, retards: 1 },
      ]);
      http.expectOne((r) => r.url === '/api/v1/classes/c1/absences/synthese').flush([
        { inscriptionId: 'i1', nom: 'ZONGO', prenoms: 'Ines', absences: 2, absencesJustifiees: 2, retards: 0, heuresAbsence: 4, heuresNonJustifiees: 0 },
        { inscriptionId: 'i2', nom: 'KABORE', prenoms: 'Ali', absences: 5, absencesJustifiees: 0, retards: 1, heuresAbsence: 10, heuresNonJustifiees: 10 },
        { inscriptionId: 'i3', nom: 'BAZIE', prenoms: 'Odile', absences: 0, absencesJustifiees: 0, retards: 0, heuresAbsence: 0, heuresNonJustifiees: 0 },
      ]);
      http.expectOne('/api/v1/classes/c1/inscriptions').flush([{ id: 'i2', eleveId: 'e2' }, { id: 'i1', eleveId: 'e1' }]);
    };
    repondre((p) => !p.has('du'));
    await attendre();
    const t = texte(f);
    expect(t).toContain('EPS');
    expect(t).toContain('appel général');
    // Élèves : non justifiées d'abord, élèves sans absence comptés à part
    expect(t.indexOf('KABORE')).toBeLessThan(t.indexOf('ZONGO'));
    expect(t).not.toContain('BAZIE');
    expect(t).toContain('1 élève(s) sans absence');
    expect((f.nativeElement as HTMLElement).querySelector('a[href="/vie-scolaire/eleves/e2"]')).not.toBeNull();
    // Seules les périodes du profil de la classe sont proposées
    const options = [...(f.nativeElement as HTMLElement).querySelectorAll('#periode option')].map((o) => o.textContent?.trim());
    expect(options.some((o) => o?.startsWith('Trimestre 1'))).toBe(true);
    expect(options.some((o) => o?.startsWith('Autre profil'))).toBe(false);

    saisir(f, '#periode', 'p1');
    await attendre();
    repondre((p) => p.get('du') === '2026-10-01' && p.get('au') === '2026-12-20');
    await attendre();
    expect(texte(f)).toContain('EPS');
  });
});
