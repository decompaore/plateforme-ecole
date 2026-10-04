import { Component, computed, inject, OnInit, signal } from '@angular/core';

import { SessionService } from '../../core/session.service';
import { FormsModule } from '@angular/forms';

import { dateCourte, dateLocale, dateLongue, lendemain } from '../../core/outils';
import { filtrer } from '../../core/recherche';
import { RechercheComponent } from '../../partage/recherche.component';
import { Role } from '../../core/modeles';
import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { AdminNavComponent } from '../admin-nav.component';
import {
  AffectationVue,
  EnseignantVue,
  LIBELLE_ROLE,
  LIBELLE_STATUT_ENGAGEMENT,
  MembreVue,
  MOTIFS_FIN,
  Sexe,
  TypeEngagement,
} from '../modeles-admin';
import { MotDePasseTemporaireComponent } from '../mot-de-passe-temporaire.component';

/** Rôles attribués depuis cet écran ; les enseignants passent par l'engagement, les parents par le dossier élève. */
const ROLES_PERSONNEL: Role[] = ['ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'SECRETARIAT', 'INTENDANT', 'SURVEILLANT'];

/** Personnel : enseignants (engagements) et membres de l'administration. */
@Component({
  selector: 'app-personnel',
  imports: [FormsModule, AdminNavComponent, MotDePasseTemporaireComponent, RechercheComponent],
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
    .fin-engagement {
      border-left: 4px solid var(--retard);
      ul {
        margin: 0.25rem 0 0.75rem;
        padding-left: 1.25rem;
      }
    }
    .actions-cellule {
      white-space: nowrap;
    }
    td.nombre button + button {
      margin-left: 0.35rem;
    }
    .confirmation-mdp td {
      background: var(--surface);
      white-space: normal;
      text-align: left;
      p {
        margin: 0 0 0.5rem;
      }
    }
  `,
})
export class PersonnelPage implements OnInit {
  private readonly api = inject(AdminApi);
  private readonly session = inject(SessionService);

  protected readonly libelleRole = LIBELLE_ROLE;
  protected readonly libelleStatut = LIBELLE_STATUT_ENGAGEMENT;
  protected readonly motifsFin = MOTIFS_FIN;
  protected readonly dateCourte = dateCourte;
  protected readonly dateLongue = dateLongue;
  protected readonly lendemain = lendemain;
  protected readonly rolesPersonnel = ROLES_PERSONNEL;
  protected readonly action = new Action();
  protected readonly enseignants = signal<EnseignantVue[]>([]);
  protected readonly membres = signal<MembreVue[]>([]);
  protected readonly secret = signal<{ titre: string; telephone: string; motDePasse: string } | null>(null);
  protected readonly message = signal<string | null>(null);
  /** Compte de l'administrateur connecté : il change son mot de passe lui-même. */
  protected readonly moi = computed(() => this.session.profil()?.utilisateurId ?? null);
  /** Réinitialisation en attente de confirmation (ligne concernée, compte, nom). */
  protected readonly aReinitialiser = signal<{ cle: string; utilisateurId: string; nom: string; telephone: string } | null>(null);
  /** Compte de chaque enseignant, retrouvé par son téléphone parmi les membres (rôle Enseignant). */
  private readonly comptesEnseignants = computed(() => {
    const m = new Map<string, string>();
    for (const x of this.membres()) {
      if (x.role === 'ENSEIGNANT' && x.actif) {
        m.set(x.telephone, x.utilisateurId);
      }
    }
    return m;
  });

  /** Recherche commune aux deux tableaux : nom, prénoms, téléphone, spécialité ou rôle. */
  protected readonly filtre = signal('');
  protected readonly enseignantsAffiches = computed(() =>
    filtrer(this.enseignants(), this.filtre(), (e) => [e.nom, e.prenoms, e.telephone, e.specialite, e.matriculeFp]),
  );
  protected readonly administrationAffichee = computed(() =>
    filtrer(this.administration(), this.filtre(), (m) => [m.nom, m.prenoms, m.telephone, LIBELLE_ROLE[m.role]]),
  );

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

  // Fin d'engagement (mutation, départ, retraite, fin de contrat)
  protected readonly finCible = signal<EnseignantVue | null>(null);
  protected readonly finDate = signal(dateLocale());
  protected readonly finMotif = signal<string>('Mutation');
  protected readonly finAutreMotif = signal('');
  /** Matières assurées cette année : elles passeront « sans enseignant » à la fin. Null tant que non chargées. */
  protected readonly finMatieres = signal<AffectationVue[] | null>(null);
  /** Date passée : la fin est immédiate (sinon programmée). */
  protected readonly finImmediate = computed(() => this.finDate() !== '' && this.finDate() < dateLocale());

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
          (a, b) =>
            Number(b.statut === 'ACTIF') - Number(a.statut === 'ACTIF') ||
            (a.nom ?? '').localeCompare(b.nom ?? ''),
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

  /** Ouvre le formulaire de fin d'engagement et charge les matières à réaffecter. */
  protected async preparerFin(e: EnseignantVue): Promise<void> {
    this.message.set(null);
    this.finCible.set(e);
    this.finDate.set(e.finProgrammee && e.fin ? e.fin : dateLocale());
    this.finMotif.set(e.type === 'VACATAIRE' ? 'Fin de contrat' : 'Mutation');
    this.finAutreMotif.set('');
    this.finMatieres.set(null);
    const fiche = await this.action.executer(() => this.api.ficheEnseignant(e.engagementId));
    if (fiche && this.finCible()?.engagementId === e.engagementId) {
      this.finMatieres.set(
        [...fiche.affectations].sort(
          (a, b) => a.classeCode.localeCompare(b.classeCode) || a.matiereLibelle.localeCompare(b.matiereLibelle),
        ),
      );
    }
  }

  protected fermerFin(): void {
    this.finCible.set(null);
    this.finMatieres.set(null);
  }

  protected async terminer(): Promise<void> {
    const e = this.finCible();
    const date = this.finDate();
    if (!e || !date) {
      return;
    }
    const motif = this.finMotif() === 'Autre' ? this.finAutreMotif().trim() || null : this.finMotif();
    const nom = `${e.prenoms ?? ''} ${e.nom ?? ''}`.trim();
    if (
      this.finImmediate() &&
      !window.confirm(`La date est passée : ${nom} perd tout de suite l'accès à l'établissement. Continuer ?`)
    ) {
      return;
    }
    const r = await this.action.executer(() => this.api.terminerEngagement(e.engagementId, date, motif));
    if (!r) {
      return;
    }
    this.message.set(
      r.statut === 'TERMINE'
        ? `Engagement de ${nom} terminé. Ses matières sont maintenant sans enseignant.`
        : `Fin programmée : ${nom} enseigne jusqu'au ${dateLongue(date)} inclus, puis l'engagement se termine ` +
            `automatiquement. ` +
            (motif === 'Mutation' && e.type === 'TITULAIRE'
              ? `Son nouvel établissement peut l'inviter comme titulaire à partir du ${dateLongue(lendemain(date))}.`
              : ''),
    );
    this.fermerFin();
    await this.recharger();
  }

  protected async annulerFin(e: EnseignantVue): Promise<void> {
    if (!window.confirm(`Annuler la fin d'engagement de ${e.prenoms} ${e.nom} prévue le ${dateCourte(e.fin ?? '')} ?`)) {
      return;
    }
    this.message.set(null);
    if (await this.action.reussit(() => this.api.annulerFinEngagement(e.engagementId))) {
      this.message.set(`Fin d'engagement annulée : ${e.prenoms} ${e.nom} reste dans l'établissement.`);
      this.fermerFin();
      await this.recharger();
    }
  }

  protected async annulerInvitation(e: EnseignantVue): Promise<void> {
    if (!window.confirm('Annuler cette invitation ? L’enseignant ne la verra plus.')) {
      return;
    }
    this.message.set(null);
    if (await this.action.reussit(() => this.api.annulerInvitation(e.engagementId))) {
      await this.recharger();
    }
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

  protected compteDe(telephone: string | null): string | null {
    return telephone ? (this.comptesEnseignants().get(telephone) ?? null) : null;
  }

  protected demanderReinitialisation(cle: string, utilisateurId: string, nom: string, telephone: string): void {
    this.message.set(null);
    this.aReinitialiser.set({ cle, utilisateurId, nom, telephone });
  }

  /** Mot de passe oublié : nouveau mot de passe provisoire, affiché une seule fois. */
  protected async reinitialiser(): Promise<void> {
    const cible = this.aReinitialiser();
    if (!cible) {
      return;
    }
    const r = await this.action.executer(() => this.api.reinitialiserCompte(cible.utilisateurId));
    this.aReinitialiser.set(null);
    if (r) {
      this.secret.set({
        titre: `Mot de passe de ${cible.nom} réinitialisé.` + (r.autresEtablissements ? ` Il vaut aussi dans ${r.autresEtablissements} autre(s) établissement(s).` : ''),
        telephone: r.telephone,
        motDePasse: r.motDePasseTemporaire,
      });
      window.scrollTo?.({ top: 0, behavior: 'smooth' });
    }
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
