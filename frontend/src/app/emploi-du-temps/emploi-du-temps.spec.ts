import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AnneeCourante } from '../admin/annee-courante.service';
import { Role } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { creneauSuivant, duree, EmploiDuTempsVue, erreurGrille, grilleCourante, MonEmploiVue, SeanceVue } from './modeles-emploi';
import { EmploiDuTempsPage } from './pages/emploi-du-temps.page';
import { GrilleHorairePage } from './pages/grille-horaire.page';
import { journees, MonEmploiPage } from './pages/mon-emploi.page';

const ANNEE = { id: 'a1', libelle: '2026-2027', debut: '2026-10-01', fin: '2027-07-31', etat: 'ACTIVE' };

async function session(http: HttpTestingController, role: Role, annees = true): Promise<void> {
  const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(
    reponse('u1', 1, { etablissementActif: { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: [role] } }),
  );
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'ZONGO', prenoms: 'Ines' });
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

const C7 = { id: 'c7', heureDebut: '07:00:00', heureFin: '08:00:00', jours: [1, 2, 3, 4, 5, 6], minutes: 60 };
const C8 = { id: 'c8', heureDebut: '08:00:00', heureFin: '09:00:00', jours: [1, 2, 3, 4, 5, 6], minutes: 60 };
const C15 = { id: 'c15', heureDebut: '15:00:00', heureFin: '16:00:00', jours: [1, 2, 4, 5], minutes: 60 };

function seance(autres: Partial<SeanceVue>): SeanceVue {
  return {
    id: 's1', classeId: 'k1', matiereId: 'm-maths', jour: 1, creneauId: 'c7', groupe: null, atelierId: null, salle: null,
    engagementId: 'e-kabore', domaine: 'GENERAL', modifiable: true, ...autres,
  };
}

function emploi(autres: Partial<EmploiDuTempsVue> = {}): EmploiDuTempsVue {
  return {
    anneeId: 'a1', annee: '2026-2027', jours: [1, 2, 3, 4, 5, 6], creneaux: [C7, C8, C15],
    classes: [{
      id: 'k1', code: '2nde F3', niveau: '2nde', filiereId: 'f3', filiereCode: 'F3', groupes: [],
      matieres: [
        { matiereId: 'm-maths', code: 'MATH', libelle: 'Mathématiques', type: 'GENERALE', domaine: 'GENERAL', volumeHebdo: 4, engagementId: 'e-kabore', enseignant: 'KABORE Awa', minutesPrevues: 240, minutesPlacees: 60 },
        { matiereId: 'm-fran', code: 'FRAN', libelle: 'Français', type: 'GENERALE', domaine: 'GENERAL', volumeHebdo: 3, engagementId: 'e-zida', enseignant: 'ZIDA Marie', minutesPrevues: 180, minutesPlacees: 0 },
        { matiereId: 'm-tp', code: 'TPEL', libelle: 'TP électricité', type: 'PRATIQUE', domaine: 'TECHNIQUE', volumeHebdo: 4, engagementId: 'e-traore', enseignant: 'TRAORE Ali', minutesPrevues: 240, minutesPlacees: 240 },
      ],
    }],
    seances: [seance({}), seance({ id: 's2', matiereId: 'm-tp', creneauId: 'c8', engagementId: 'e-traore', domaine: 'TECHNIQUE', atelierId: 'at1', modifiable: false })],
    enseignants: [{ engagementId: 'e-kabore', nom: 'KABORE Awa' }, { engagementId: 'e-traore', nom: 'TRAORE Ali' }, { engagementId: 'e-zida', nom: 'ZIDA Marie' }],
    ateliers: [{ id: 'at1', code: 'ELEC', nom: 'Atelier d’électricité', filieres: ['f3'] }],
    occupationsAilleurs: [{ engagementId: 'e-zida', jour: 2, heureDebut: '07:30:00', heureFin: '08:30:00' }],
    conflits: [],
    droits: { grille: true, domaines: ['GENERAL'], publier: true, modifiable: true },
    publieLe: null,
    ...autres,
  };
}

describe('Emplois du temps', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('contrôle la grille horaire et propose le créneau suivant', () => {
    expect(erreurGrille(grilleCourante())).toBeNull();
    expect(grilleCourante()).toHaveLength(8);
    expect(erreurGrille([{ id: null, heureDebut: '07:00', heureFin: '08:30', jours: [1] }, { id: null, heureDebut: '08:00', heureFin: '09:00', jours: [1] }]))
      .toContain('se chevauchent');
    expect(erreurGrille([{ id: null, heureDebut: '09:00', heureFin: '08:00', jours: [1] }])).toContain('doit suivre');
    expect(erreurGrille([{ id: null, heureDebut: '08:00', heureFin: '09:00', jours: [] }])).toContain('au moins un jour');
    expect(creneauSuivant([{ id: 'x', heureDebut: '10:15', heureFin: '11:10', jours: [1, 3] }])).toEqual({ id: null, heureDebut: '11:10', heureFin: '12:05', jours: [1, 3] });
    expect(duree(270)).toBe('4 h 30');
    expect(duree(240)).toBe('4 h');
  });

  it('grille horaire : part de la grille courante et l’enregistre', async () => {
    await session(http, 'CENSEUR');
    const f = TestBed.createComponent(GrilleHorairePage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/annees/a1/creneaux').flush([]);
    await attendre();
    expect(texte(f)).toContain('Aucun créneau pour 2026-2027');
    bouton(f, 'Partir de la grille courante').click();
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).querySelectorAll('tbody tr')).toHaveLength(8);
    bouton(f, 'Enregistrer la grille').click();
    await attendre();
    const req = http.expectOne((r) => r.url === '/api/v1/annees/a1/creneaux' && r.method === 'PUT');
    expect(req.request.body.creneaux[0]).toEqual({ id: null, heureDebut: '07:00', heureFin: '08:00', jours: [1, 2, 3, 4, 5, 6] });
    expect(req.request.body.creneaux[7].jours).toEqual([1, 2, 4, 5]);
    req.flush([C7, C8, C15]);
    await attendre();
    expect(texte(f)).toContain('Grille enregistrée : 3 créneau(x).');
  });

  it('censeur : place une matière générale, ne touche pas au TP, génère et publie', async () => {
    await session(http, 'CENSEUR');
    const f = TestBed.createComponent(EmploiDuTempsPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/annees/a1/emploi-du-temps').flush(emploi());
    await attendre();
    let t = texte(f);
    expect(t).toContain('Version de travail, non publiée');
    expect(t).toContain('Vous placez les matières générales');
    expect(t).toContain('Mathématiques');
    expect(t).toContain('KABORE Awa');
    expect(t).toContain('Atelier ELEC');
    expect(t).toContain('1 h / 4 h');
    // Le TP (chef des travaux) n'est pas modifiable par le censeur
    const tp = [...(f.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button.seance')].find((b) => b.textContent?.includes('TP électricité'));
    expect(tp?.disabled).toBe(true);
    // Mercredi 15 h : pas de cours
    expect((f.nativeElement as HTMLElement).querySelectorAll('td.ferme').length).toBe(2);

    // Mardi 7 h : la matière qui manque d'heures est proposée ; Français, mais Mme ZIDA est prise ailleurs
    const placer = (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[aria-label="Placer une séance le mardi à 07h00"]')!;
    placer.click();
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#eMatiere')!.value).toBe('m-maths');
    expect((f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#eJour')!.value).toBe('2');
    expect((f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#eCreneau')!.value).toBe('c7');
    const matiere = (f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#eMatiere')!;
    matiere.value = 'm-fran';
    matiere.dispatchEvent(new Event('change'));
    t = texte(f);
    expect(t).toContain('ZIDA Marie a déjà cours dans un autre établissement à ce moment');
    matiere.value = 'm-maths';
    matiere.dispatchEvent(new Event('change'));
    const salle = (f.nativeElement as HTMLElement).querySelector<HTMLInputElement>('#eSalle')!;
    salle.value = 'Salle 4';
    salle.dispatchEvent(new Event('input'));
    bouton(f, 'Enregistrer').click();
    await attendre();
    const pose = http.expectOne((r) => r.url.startsWith('/api/v1/seances-emploi/') && r.method === 'PUT');
    expect(pose.request.body).toEqual({ anneeId: 'a1', classeId: 'k1', matiereId: 'm-maths', jour: 2, creneauId: 'c7', groupe: null, atelierId: null, salle: 'Salle 4' });
    pose.flush(emploi({ seances: [...emploi().seances, seance({ id: 's3', jour: 2, salle: 'Salle 4' })] }));
    await attendre();
    expect(texte(f)).toContain('Séance placée : mardi à 07h00.');

    // Une séance existante s'ouvre pour être modifiée ou retirée
    bouton(f, 'Mathématiques').click();
    f.detectChanges();
    expect(texte(f)).toContain('Modifier la séance');
    bouton(f, 'Retirer la séance').click();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/seances-emploi/s1' && r.method === 'DELETE').flush(emploi({ seances: [] }));
    await attendre();
    expect(texte(f)).toContain('Séance retirée.');

    // Génération pour toutes les classes
    bouton(f, 'Générer').click();
    await attendre();
    const gen = http.expectOne((r) => r.url === '/api/v1/annees/a1/emploi-du-temps/generation');
    expect(gen.request.body).toEqual({ classes: [], domaines: [], remplacer: false });
    gen.flush({
      seancesPlacees: 6, seancesRetirees: 0, emploi: emploi(),
      manques: [{ classeId: 'k1', classe: '2nde F3', matiereId: 'm-fran', matiere: 'Français', minutesManquantes: 60, raison: 'Aucune case où la classe et l’enseignant sont libres ensemble' }],
    });
    await attendre();
    t = texte(f);
    expect(t).toContain('6 séance(s) placée(s)');
    expect(t).toContain('2nde F3 · Français : 1 h');

    // Publication
    bouton(f, 'Publier aux enseignants').click();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/annees/a1/emploi-du-temps/publication' && r.method === 'POST')
      .flush(emploi({ publieLe: '2026-10-05T08:00:00Z' }));
    await attendre();
    t = texte(f);
    expect(t).toContain('Emploi du temps publié');
    expect(t).toContain('Republier');
  });

  it('chef des travaux : les conflits bloquent la publication, la vue par enseignant montre les heures prises ailleurs', async () => {
    await session(http, 'CHEF_TRAVAUX');
    const f = TestBed.createComponent(EmploiDuTempsPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/annees/a1/emploi-du-temps').flush(emploi({
      droits: { grille: false, domaines: ['TECHNIQUE'], publier: false, modifiable: true },
      seances: [seance({ modifiable: false }), seance({ id: 's2', matiereId: 'm-tp', creneauId: 'c8', engagementId: 'e-traore', domaine: 'TECHNIQUE', atelierId: 'at1', modifiable: true })],
      conflits: [{ type: 'ENSEIGNANT', seances: ['s1'], message: 'KABORE Awa a déjà cours en 1re F3 le lundi à 07h00' }],
    }));
    await attendre();
    let t = texte(f);
    expect(t).toContain('Conflits à régler');
    expect(t).toContain('KABORE Awa a déjà cours en 1re F3');
    expect(t).not.toContain('Publier aux enseignants');
    expect(t).toContain('Vous placez les matières techniques et pratiques');
    expect((f.nativeElement as HTMLElement).querySelector('button.seance.conflit')).not.toBeNull();

    bouton(f, 'Par enseignant').click();
    f.detectChanges();
    const choix = (f.nativeElement as HTMLElement).querySelector<HTMLSelectElement>('#selection')!;
    choix.value = 'e-zida';
    choix.dispatchEvent(new Event('change'));
    t = texte(f);
    expect(t).toContain('Autre établissement');
    expect(t).toContain('ZIDA Marie :');
  });

  it('enseignant : son emploi du temps une fois publié, avec ses heures ailleurs', async () => {
    const mon: MonEmploiVue = {
      anneeId: 'a1', annee: '2026-2027', publieLe: '2026-10-05T08:00:00Z', jours: [1, 2], creneaux: [C7, C8],
      seances: [
        { jour: 1, creneauId: 'c8', heureDebut: '08:00:00', heureFin: '09:00:00', classe: '2nde F3', matiere: 'Mathématiques', groupe: null, atelier: null, salle: 'Salle 4' },
        { jour: 1, creneauId: 'c7', heureDebut: '07:00:00', heureFin: '08:00:00', classe: '1re F3', matiere: 'Mathématiques', groupe: 'G1', atelier: null, salle: null },
      ],
      ailleurs: [{ engagementId: 'e', jour: 2, heureDebut: '10:00:00', heureFin: '12:00:00' }],
    };
    expect(journees(mon).map((j) => j.libelle)).toEqual(['Lundi', 'Mardi']);
    expect(journees(mon)[0].lignes[0].titre).toBe('1re F3 (G1) · Mathématiques');

    await session(http, 'ENSEIGNANT', false);
    const f = TestBed.createComponent(MonEmploiPage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/espace-enseignant/emploi-du-temps').flush({ ...mon, publieLe: null, seances: [], ailleurs: [] });
    await attendre();
    expect(texte(f)).toContain('pas encore publié');

    const g = TestBed.createComponent(MonEmploiPage);
    g.detectChanges();
    await attendre();
    http.expectOne('/api/v1/espace-enseignant/emploi-du-temps').flush(mon);
    await attendre();
    const t = texte(g);
    expect(t).toContain('2 h de cours par semaine');
    expect(t).toMatch(/Lundi\s*07h00 – 08h00\s*1re F3 \(G1\) · Mathématiques\s*08h00 – 09h00/);
    expect(t).toContain('Cours dans un autre établissement');
  });
});
