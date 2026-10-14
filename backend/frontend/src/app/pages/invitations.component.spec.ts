import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';

import { SessionService } from '../core/session.service';
import { attendre, configurer, LYCEE, reponse } from '../testing/outils-test';
import { InvitationsComponent } from './invitations.component';

const CEG = { id: 'etab-y', code: 'CEG-Y', nom: 'CEG de Réo', roles: ['ENSEIGNANT' as const] };

describe('Invitations : titulaire dans X, vacataire dans Y', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    ({ http } = configurer());
    // Enseignant titulaire au lycée X, seul établissement pour l'instant
    const connexion = TestBed.inject(SessionService).connexion('61000001', 'secret123');
    http.expectOne('/api/v1/auth/connexion').flush(reponse('u1'));
    await attendre();
    http.expectOne('/api/v1/moi').flush({ nom: 'SANOU', prenoms: 'Paul' });
    await connexion;
  });

  afterEach(() => http.verify());

  it('accepte le poste de vacataire, gagne le choix d’établissement et passe dans Y', async () => {
    const session = TestBed.inject(SessionService);
    expect(session.plusieursEtablissements()).toBe(false);
    const navigation = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

    const f = TestBed.createComponent(InvitationsComponent);
    f.detectChanges();
    http.expectOne('/api/v1/moi/invitations').flush([
      { engagementId: 'g9', etablissementId: 'etab-y', etablissementNom: 'CEG de Réo', type: 'VACATAIRE', debut: '2026-10-05', fin: '2027-06-30', tauxHoraire: 2500 },
    ]);
    await attendre();
    f.detectChanges();
    const page: HTMLElement = f.nativeElement;
    expect(page.textContent).toContain('poste de vacataire');
    expect(page.textContent).toContain('2500 FCFA');

    [...page.querySelectorAll('button')].find((b) => b.textContent?.includes('Accepter'))!.click();
    await attendre();
    http.expectOne('/api/v1/moi/invitations/g9/acceptation').flush({ etablissementId: 'etab-y', etablissementNom: 'CEG de Réo', statut: 'ACTIF' });
    await attendre();
    http.expectOne('/api/v1/moi/etablissements').flush([LYCEE, CEG]);
    await attendre();
    f.detectChanges();

    // Deux établissements : le choix d'établissement apparaît
    expect(session.plusieursEtablissements()).toBe(true);
    expect(page.textContent).toContain('Vous enseignez maintenant aussi à CEG de Réo');

    [...page.querySelectorAll('button')].find((b) => b.textContent?.includes('Y aller'))!.click();
    await attendre();
    const choix = http.expectOne('/api/v1/auth/etablissement');
    expect(choix.request.body).toEqual({ etablissementId: 'etab-y' });
    choix.flush(reponse('u1', 2, { etablissementActif: CEG, etablissements: [LYCEE, CEG] }));
    await attendre();
    expect(session.profil()?.etablissement?.nom).toBe('CEG de Réo');
    expect(session.plusieursEtablissements()).toBe(true);
    expect(navigation).toHaveBeenCalledWith(['/']);
  });
});
