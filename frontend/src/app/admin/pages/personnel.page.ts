import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { dateLocale } from '../../core/outils';
import { Role } from '../../core/modeles';
import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { AdminNavComponent } from '../admin-nav.component';
import {
  EnseignantVue,
  LIBELLE_ROLE,
  LIBELLE_STATUT_ENGAGEMENT,
  MembreVue,
  Sexe,
  TypeEngagement,
} from '../modeles-admin';
import { MotDePasseTemporaireComponent } from '../mot-de-passe-temporaire.component';

/** Rôles attribués depuis cet écran ; les enseignants passent par l'engagement, les parents par le dossier élève. */
const ROLES_PERSONNEL: Role[] = ['ADMIN_ECOLE', 'CENSEUR', 'SECRETARIAT', 'INTENDANT', 'SURVEILLANT'];

/** Personnel : enseignants (engagements) et membres de l'administration. */
@Component({
  selector: 'app-personnel',
  imports: [FormsModule, AdminNavComponent, MotDePasseTemporaireComponent],
  templateUrl: './personnel.page.html',
  styles: `
    h3 {
      font-size: 1rem;
      margin: 0.5rem 0 0.75rem;
    }
    .nouveau {
      border-bottom: 1px solid var(--bordure);
      padding-bottom: 1rem;
      margin-bottom: 0.5rem;
    }
  `,
})
export class PersonnelPage implements OnInit {
  private readonly api = inject(AdminApi);

  protected readonly libelleRole = LIBELLE_ROLE;
  protected readonly libelleStatut = LIBELLE_STATUT_ENGAGEMENT;
  protected readonly rolesPersonnel = ROLES_PERSONNEL;
  protected readonly action = new Action();
  protected readonly enseignants = signal<EnseignantVue[]>([]);
  protected readonly membres = signal<MembreVue[]>([]);
  protected readonly secret = signal<{ titre: string; telephone: string; motDePasse: string } | null>(null);
  protected readonly message = signal<string | null>(null);

  /** Membres hors enseignants et parents, actifs d'abord. */
  protected readonly administration = computed(() =>
    this.membres()
      .filter((m) => ROLES_PERSONNEL.includes(m.role))
      .sort((a, b) => Number(b.actif) - Number(a.actif) || a.nom.localeCompare(b.nom)),
  );

  // Engagement d'un enseignant
  protected readonly formEnseignant = signal(false);
  protected readonly eTelephone = signal('');
  protected readonly eNom = signal('');
  protected readonly ePrenoms = signal('');
  protected readonly eSexe = signal<Sexe>('M');
  protected readonly eSpecialite = signal('');
  protected readonly eType = signal<TypeEngagement>('TITULAIRE');
  protected readonly eDebut = signal(dateLocale());
  protected readonly eFin = signal('');
  protected readonly eTaux = signal<number | null>(null);

  // Ajout d'un membre
  protected readonly formMembre = signal(false);
  protected readonly mTelephone = signal('');
  protected readonly mNom = signal('');
  protected readonly mPrenoms = signal('');
  protected readonly mRole = signal<Role>('SECRETARIAT');

  async ngOnInit(): Promise<void> {
    await this.recharger();
  }

  private async recharger(): Promise<void> {
    await this.action.executer(async () => {
      const [enseignants, membres] = await Promise.all([this.api.enseignants(), this.api.membres()]);
      this.enseignants.set(
        [...enseignants].sort(
          (a, b) => Number(b.statut === 'ACTIF') - Number(a.statut === 'ACTIF') || a.nom.localeCompare(b.nom),
        ),
      );
      this.membres.set(membres);
    });
  }

  protected async engager(): Promise<void> {
    this.message.set(null);
    const telephone = this.eTelephone().trim();
    const r = await this.action.executer(() =>
      this.api.engager({
        telephone,
        nom: this.eNom().trim(),
        prenoms: this.ePrenoms().trim(),
        sexe: this.eSexe(),
        specialite: this.eSpecialite().trim() || null,
        type: this.eType(),
        debut: this.eDebut(),
        fin: this.eFin() || null,
        tauxHoraire: this.eType() === 'VACATAIRE' ? this.eTaux() : null,
      }),
    );
    if (!r) {
      return;
    }
    if (r.invitation) {
      this.message.set(
        'Cet enseignant a déjà un compte sur la plateforme : une invitation lui a été envoyée. ' +
          'Il l’accepte depuis son compte, puis vous pourrez lui affecter des matières.',
      );
    } else if (r.motDePasseTemporaire) {
      this.secret.set({
        titre: `Compte de ${r.enseignant.prenoms} ${r.enseignant.nom} créé.`,
        telephone,
        motDePasse: r.motDePasseTemporaire,
      });
    }
    for (const s of [this.eTelephone, this.eNom, this.ePrenoms, this.eSpecialite, this.eFin]) {
      s.set('');
    }
    this.formEnseignant.set(false);
    await this.recharger();
  }

  protected async ajouterMembre(): Promise<void> {
    this.message.set(null);
    const telephone = this.mTelephone().trim();
    const r = await this.action.executer(() =>
      this.api.ajouterMembre({ telephone, nom: this.mNom().trim(), prenoms: this.mPrenoms().trim(), role: this.mRole() }),
    );
    if (!r) {
      return;
    }
    if (r.motDePasseTemporaire) {
      this.secret.set({
        titre: `Compte de ${r.membre.prenoms} ${r.membre.nom} (${LIBELLE_ROLE[r.membre.role]}) créé.`,
        telephone,
        motDePasse: r.motDePasseTemporaire,
      });
    } else {
      this.message.set(
        `${r.membre.prenoms} ${r.membre.nom} avait déjà un compte : le rôle ${LIBELLE_ROLE[r.membre.role]} lui est ajouté, avec son mot de passe habituel.`,
      );
    }
    for (const s of [this.mTelephone, this.mNom, this.mPrenoms]) {
      s.set('');
    }
    this.formMembre.set(false);
    await this.recharger();
  }

  protected async desactiver(m: MembreVue): Promise<void> {
    if (!window.confirm(`Retirer le rôle ${LIBELLE_ROLE[m.role]} à ${m.prenoms} ${m.nom} ?`)) {
      return;
    }
    if (await this.action.reussit(() => this.api.desactiverMembre(m.id))) {
      await this.recharger();
    }
  }
}
