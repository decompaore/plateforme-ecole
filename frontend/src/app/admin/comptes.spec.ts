import { HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SessionService } from '../core/session.service';
import { attendre, configurer, reponse } from '../testing/outils-test';
import { AnneeCourante } from './annee-courante.service';
import { CompteVue } from './modeles-admin';
import { ComptesPage, heureVerrou } from './pages/comptes.page';
import { MotDePassePage } from '../pages/mot-de-passe.page';
import { PlateformePage } from './pages/plateforme.page';

const ADMIN = { id: 'etab-1', code: 'LTK', nom: 'Lycée technique', roles: ['ADMIN_ECOLE' as const] };

async function session(http: HttpTestingController, superAdmin = false): Promise<void> {
  const connexion = TestBed.inject(SessionService).connexion('70000001', 'secret123');
  http.expectOne('/api/v1/auth/connexion').flush(
    reponse('u1', 1, superAdmin ? { superAdmin: true, etablissementActif: null } : { etablissementActif: ADMIN }),
  );
  await attendre();
  http.expectOne('/api/v1/moi').flush({ nom: 'KABORE', prenoms: 'Mathieu' });
  await connexion;
  if (!superAdmin) {
    const annees = TestBed.inject(AnneeCourante).charger();
    http.expectOne('/api/v1/annees').flush([]);
    await annees;
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

function compte(autres: Partial<CompteVue>): CompteVue {
  return {
    utilisateurId: 'u2', nom: 'SANOU', prenoms: 'Paul', telephone: '+22661000001', roles: ['ENSEIGNANT'], rolesRetires: [],
    actif: true, derniereConnexion: '2026-10-02T08:15:00Z', verrouilleJusqua: null, motDePasseProvisoire: false, moi: false,
    ...autres,
  };
}

describe('Comptes et mots de passe', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    ({ http } = configurer());
  });

  afterEach(() => http.verify());

  it('affiche l’heure de fin du verrouillage', () => {
    const d = new Date(2026, 9, 4, 9, 5);
    expect(heureVerrou(d.toISOString())).toBe('09h05');
  });

  it('administrateur : déverrouille un compte puis réinitialise un mot de passe oublié', async () => {
    await session(http);
    const f = TestBed.createComponent(ComptesPage);
    f.detectChanges();
    await attendre();
    const verrou = new Date(Date.now() + 10 * 60_000).toISOString();
    http.expectOne('/api/v1/comptes').flush([
      compte({ utilisateurId: 'u1', nom: 'KABORE', prenoms: 'Mathieu', telephone: '+22670000001', roles: ['ADMIN_ECOLE'], moi: true }),
      compte({ verrouilleJusqua: verrou }),
      compte({ utilisateurId: 'u3', nom: 'OUEDRAOGO', prenoms: 'Mariam', telephone: '+22663000001', roles: ['PARENT'], derniereConnexion: null, motDePasseProvisoire: true }),
      compte({ utilisateurId: 'u4', nom: 'ZONGO', prenoms: 'Ali', telephone: '+22662000009', roles: [], rolesRetires: ['SURVEILLANT'], actif: false }),
    ]);
    await attendre();
    let t = texte(f);
    expect(t).toContain('Actifs (3)');
    expect(t).toContain('Verrouillés (1)');
    expect(t).toContain('Jamais connecté');
    expect(t).toContain(`Verrouillé jusqu'à ${heureVerrou(verrou)}`);
    expect(t).not.toContain('ZONGO');
    // Son propre compte : lien vers « Changer mon mot de passe », pas de réinitialisation
    const lien = (f.nativeElement as HTMLElement).querySelector('a[href="/mot-de-passe"]');
    expect(lien?.textContent).toContain('Changer mon mot de passe');
    expect([...(f.nativeElement as HTMLElement).querySelectorAll('button')].filter((b) => b.textContent?.includes('Réinitialiser')).length).toBe(2);

    bouton(f, 'Déverrouiller').click();
    await attendre();
    http.expectOne('/api/v1/comptes/u2/deverrouillage').flush(compte({}));
    await attendre();
    expect(texte(f)).toContain('SANOU Paul peut de nouveau se connecter');
    expect(texte(f)).toContain('Verrouillés (0)');

    // Réinitialisation : confirmation, puis mot de passe provisoire affiché une seule fois
    bouton(f, 'Réinitialiser le mot de passe').click();
    t = texte(f);
    expect(t).toContain('Remplacer le mot de passe de SANOU Paul');
    expect(t).toContain('ses sessions ouvertes seront fermées');
    bouton(f, 'Confirmer la réinitialisation').click();
    await attendre();
    http.expectOne((r) => r.url === '/api/v1/comptes/u2/reinitialisation' && r.method === 'POST').flush({
      utilisateurId: 'u2', nom: 'SANOU', prenoms: 'Paul', telephone: '+22661000001', motDePasseTemporaire: 'Kp7mQx2aRt', autresEtablissements: 1,
    });
    await attendre();
    t = texte(f);
    expect(t).toContain('Mot de passe réinitialisé : SANOU Paul');
    expect(t).toContain('Kp7mQx2aRt');
    expect(t).toContain('1 autre(s) établissement(s)');
    expect(t).toContain('Nouveau mot de passe attendu (2)');
    bouton(f, 'J\'ai noté le mot de passe').click();
    expect(texte(f)).not.toContain('Kp7mQx2aRt');

    // Comptes retirés : visibles, sans action
    bouton(f, 'Retirés').click();
    t = texte(f);
    expect(t).toContain('ZONGO Ali');
    expect(t).not.toContain('Réinitialiser le mot de passe');
  });

  it('super administrateur : réinitialise l’administrateur d’un établissement', async () => {
    await session(http, true);
    const f = TestBed.createComponent(PlateformePage);
    f.detectChanges();
    await attendre();
    http.expectOne('/api/v1/plateforme/etablissements').flush([{ id: 'e1', code: 'ltk', nom: 'Lycée technique', statut: 'ACTIF', creeLe: '2026-09-01T08:00:00Z' }]);
    await attendre();
    bouton(f, 'Administrateurs').click();
    await attendre();
    http.expectOne('/api/v1/plateforme/etablissements/e1/administrateurs').flush([
      compte({ utilisateurId: 'a1', nom: 'KABORE', prenoms: 'Mathieu', telephone: '+22670000001', roles: ['ADMIN_ECOLE'], verrouilleJusqua: '2026-10-04T10:00:00Z' }),
    ]);
    await attendre();
    expect(texte(f)).toContain('KABORE Mathieu · +22670000001');
    expect(texte(f)).toContain('verrouillé');
    bouton(f, 'Réinitialiser le mot de passe').click();
    bouton(f, 'Confirmer la réinitialisation').click();
    await attendre();
    http.expectOne('/api/v1/plateforme/etablissements/e1/administrateurs/a1/reinitialisation').flush({
      utilisateurId: 'a1', nom: 'KABORE', prenoms: 'Mathieu', telephone: '+22670000001', motDePasseTemporaire: 'Zr4tW8nPqe', autresEtablissements: 0,
    });
    await attendre();
    const t = texte(f);
    expect(t).toContain('Mot de passe de l’administrateur KABORE Mathieu réinitialisé.');
    expect(t).toContain('Zr4tW8nPqe');
    expect(t).toContain('mot de passe provisoire');
  });
  it('nouvelle période : la page « Mot de passe » explique le renouvellement', async () => {
    const connexion = TestBed.inject(SessionService).connexion('61000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u2', 1, {
      etablissementActif: { ...ADMIN, roles: ['ENSEIGNANT'] }, doitChangerMotDePasse: true, motifChangementMotDePasse: 'RENOUVELLEMENT',
    }));
    await attendre();
    http.expectOne('/api/v1/moi').flush({ nom: 'SANOU', prenoms: 'Paul' });
    await connexion;
    expect(TestBed.inject(SessionService).profil()?.motifChangementMotDePasse).toBe('RENOUVELLEMENT');
    const f = TestBed.createComponent(MotDePassePage);
    const t = texte(f);
    expect(t).toContain('Une nouvelle période (trimestre ou semestre) a commencé');
    expect(t).not.toContain('Votre mot de passe est provisoire');
  });
});
