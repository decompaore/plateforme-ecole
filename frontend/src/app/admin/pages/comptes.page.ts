import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { dateHeureCourte } from '../../core/outils';
import { filtrer } from '../../core/recherche';
import { RechercheComponent } from '../../partage/recherche.component';
import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { AdminNavComponent } from '../admin-nav.component';
import { CompteVue, LIBELLE_ROLE, ResultatReinitialisation } from '../modeles-admin';
import { MotDePasseTemporaireComponent } from '../mot-de-passe-temporaire.component';

type Filtre = 'tous' | 'verrouilles' | 'provisoires' | 'retires';

/** Heure de fin du verrouillage : « 14h35 ». */
export function heureVerrou(iso: string): string {
  const d = new Date(iso);
  return `${String(d.getHours()).padStart(2, '0')}h${String(d.getMinutes()).padStart(2, '0')}`;
}

/**
 * Comptes de l'établissement : quand une personne a oublié son mot de passe, l'administrateur le
 * réinitialise ; un mot de passe provisoire s'affiche une seule fois, à lui remettre, et elle le
 * change à sa prochaine connexion. Les comptes verrouillés après trop d'essais se débloquent ici.
 */
@Component({
  selector: 'app-comptes',
  imports: [RouterLink, AdminNavComponent, RechercheComponent, MotDePasseTemporaireComponent],
  template: `
    <div class="page large">
      <h1>Comptes et mots de passe</h1>
      <app-admin-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (resultat(); as r) {
        <app-mot-de-passe-temporaire
          [titre]="'Mot de passe réinitialisé : ' + r.nom + ' ' + r.prenoms"
          [telephone]="r.telephone"
          [motDePasse]="r.motDePasseTemporaire"
          (fermer)="resultat.set(null)" />
        @if (r.autresEtablissements) {
          <p class="alerte attention">
            Cette personne a aussi un rôle dans {{ r.autresEtablissements }} autre(s) établissement(s) : le nouveau mot de
            passe vaut aussi pour y accéder.
          </p>
        }
      }
      @if (message()) {
        <div class="alerte succes" role="status">{{ message() }}</div>
      }

      <section class="carte">
        <p class="doux intro">
          Une personne a oublié son mot de passe ? Réinitialisez-le : un mot de passe provisoire s'affiche une seule fois,
          à lui remettre en main propre ou par téléphone. Elle le changera à sa prochaine connexion. Ses sessions ouvertes
          sont fermées et elle reçoit un SMS qui l'informe de la réinitialisation.
        </p>
        <p class="doux intro">
          Le personnel choisit aussi un nouveau mot de passe au début de chaque période (trimestre ou semestre) : l'application
          le demande à la première connexion de la période.
        </p>

        @if (charge()) {
          <div class="bascule" role="group" aria-label="Comptes affichés">
            <button type="button" [class.actif]="filtre() === 'tous'" (click)="filtre.set('tous')">Actifs ({{ nombres().actifs }})</button>
            <button type="button" [class.actif]="filtre() === 'verrouilles'" (click)="filtre.set('verrouilles')">Verrouillés ({{ nombres().verrouilles }})</button>
            <button type="button" [class.actif]="filtre() === 'provisoires'" (click)="filtre.set('provisoires')">Nouveau mot de passe attendu ({{ nombres().provisoires }})</button>
            <button type="button" [class.actif]="filtre() === 'retires'" (click)="filtre.set('retires')">Retirés ({{ nombres().retires }})</button>
          </div>

          <app-recherche libelle="Nom, prénoms ou téléphone" [(valeur)]="recherche" [total]="selection().length" [trouves]="affiches().length" />

          <ul class="liste">
            @for (c of affiches(); track c.utilisateurId) {
              <li class="compte" [class.retire]="!c.actif">
                <div class="identite">
                  <strong>{{ c.nom }} {{ c.prenoms }}</strong>
                  @if (c.moi) { <span class="pastille present">vous</span> }
                  <br />
                  <span class="doux">{{ c.telephone }}</span>
                  <span class="roles">
                    @for (r of c.roles; track r) { <span class="pastille">{{ libelleRole[r] }}</span> }
                    @for (r of c.rolesRetires; track r) { <span class="pastille barre" [title]="'Rôle retiré'">{{ libelleRole[r] }}</span> }
                  </span>
                  <span class="etat doux">
                    @if (c.verrouilleJusqua) { <span class="pastille absent">Verrouillé jusqu'à {{ heureVerrou(c.verrouilleJusqua) }}</span> }
                    @if (c.motDePasseProvisoire) {
                      <span class="pastille">{{ c.motifChangement === 'RENOUVELLEMENT' ? 'Mot de passe à renouveler (nouvelle période)' : 'Mot de passe provisoire' }}</span>
                    }
                    {{ c.derniereConnexion ? 'Dernière connexion le ' + dateHeureCourte(c.derniereConnexion) : 'Jamais connecté' }}
                    @if (c.motDePasseChangeLe) { · mot de passe choisi le {{ dateHeureCourte(c.motDePasseChangeLe) }} }
                  </span>
                </div>
                <div class="actions">
                  @if (c.moi) {
                    <a class="bouton secondaire petit" routerLink="/mot-de-passe">Changer mon mot de passe</a>
                  } @else if (c.actif && confirmation() !== c.utilisateurId) {
                    @if (c.verrouilleJusqua) {
                      <button type="button" class="bouton secondaire petit" [disabled]="action.enCours()" (click)="deverrouiller(c)">Déverrouiller</button>
                    }
                    <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="demander(c)">Réinitialiser le mot de passe</button>
                  }
                </div>
                @if (confirmation() === c.utilisateurId) {
                  <div class="confirmation" role="alertdialog" [attr.aria-label]="'Réinitialiser le mot de passe de ' + c.nom + ' ' + c.prenoms">
                    <p>
                      Remplacer le mot de passe de <strong>{{ c.nom }} {{ c.prenoms }}</strong> par un mot de passe provisoire ?
                      Son mot de passe actuel ne marchera plus et ses sessions ouvertes seront fermées. Si la personne a un rôle
                      dans un autre établissement, le nouveau mot de passe vaut aussi là-bas.
                    </p>
                    <p class="doux">Vérifiez d'abord que c'est bien elle qui le demande (en personne ou en la rappelant à son numéro).</p>
                    <div class="actions-ligne">
                      <button type="button" class="bouton danger petit" [disabled]="action.enCours()" (click)="reinitialiser(c)">Confirmer la réinitialisation</button>
                      <button type="button" class="bouton discret petit" (click)="confirmation.set(null)">Annuler</button>
                    </div>
                  </div>
                }
              </li>
            } @empty {
              <li class="doux">{{ recherche().trim() ? 'Personne ne correspond.' : 'Aucun compte dans cette catégorie.' }}</li>
            }
          </ul>
        } @else if (action.enCours()) {
          <p class="doux">Chargement…</p>
        }
      </section>
    </div>
  `,
  styles: `
    .intro {
      margin-top: 0;
    }
    .bascule {
      display: inline-flex;
      flex-wrap: wrap;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      padding: 0.2rem;
      background: var(--surface);
      margin-bottom: 0.75rem;
      button {
        min-height: 38px;
        padding: 0 0.8rem;
        border: none;
        background: none;
        border-radius: calc(var(--rayon) - 4px);
        font: inherit;
        font-weight: 600;
        color: var(--texte-doux);
        cursor: pointer;
      }
      button.actif {
        background: var(--primaire);
        color: var(--sur-primaire);
      }
    }
    .identite > .pastille {
      margin-left: 0.4rem;
    }
    .compte {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 0.5rem 1rem;
      align-items: start;
    }
    .compte.retire .identite {
      opacity: 0.65;
    }
    .roles,
    .etat {
      display: flex;
      flex-wrap: wrap;
      gap: 0.3rem 0.5rem;
      align-items: center;
      margin-top: 0.3rem;
    }
    .barre {
      text-decoration: line-through;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      justify-content: flex-end;
    }
    .confirmation {
      grid-column: 1 / -1;
      padding: 0.75rem;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      background: var(--surface);
      p {
        margin: 0 0 0.5rem;
      }
    }
    @media (max-width: 560px) {
      .compte {
        grid-template-columns: 1fr;
      }
      .actions {
        justify-content: flex-start;
      }
    }
  `,
})
export class ComptesPage {
  private readonly api = inject(AdminApi);

  protected readonly libelleRole = LIBELLE_ROLE;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly heureVerrou = heureVerrou;
  protected readonly action = new Action();

  protected readonly comptes = signal<CompteVue[]>([]);
  protected readonly charge = signal(false);
  protected readonly filtre = signal<Filtre>('tous');
  protected readonly recherche = signal('');
  protected readonly confirmation = signal<string | null>(null);
  protected readonly resultat = signal<ResultatReinitialisation | null>(null);
  protected readonly message = signal<string | null>(null);

  protected readonly nombres = computed(() => {
    const l = this.comptes();
    return {
      actifs: l.filter((c) => c.actif).length,
      verrouilles: l.filter((c) => c.actif && c.verrouilleJusqua).length,
      provisoires: l.filter((c) => c.actif && c.motDePasseProvisoire).length,
      retires: l.filter((c) => !c.actif).length,
    };
  });

  protected readonly selection = computed(() => {
    const l = this.comptes();
    switch (this.filtre()) {
      case 'verrouilles':
        return l.filter((c) => c.actif && c.verrouilleJusqua);
      case 'provisoires':
        return l.filter((c) => c.actif && c.motDePasseProvisoire);
      case 'retires':
        return l.filter((c) => !c.actif);
      default:
        return l.filter((c) => c.actif);
    }
  });

  protected readonly affiches = computed(() =>
    filtrer(this.selection(), this.recherche(), (c) => [c.nom, c.prenoms, c.telephone, c.telephone.replace(/^\+226/, '')]),
  );

  constructor() {
    void this.charger();
  }

  protected demander(c: CompteVue): void {
    this.message.set(null);
    this.confirmation.set(c.utilisateurId);
  }

  protected async reinitialiser(c: CompteVue): Promise<void> {
    const r = await this.action.executer(() => this.api.reinitialiserCompte(c.utilisateurId));
    this.confirmation.set(null);
    if (r) {
      this.resultat.set(r);
      this.remplacer({ ...c, verrouilleJusqua: null, motDePasseProvisoire: true });
      window.scrollTo?.({ top: 0, behavior: 'smooth' });
    }
  }

  protected async deverrouiller(c: CompteVue): Promise<void> {
    const v = await this.action.executer(() => this.api.deverrouillerCompte(c.utilisateurId));
    if (v) {
      this.remplacer(v);
      this.message.set(`${c.nom} ${c.prenoms} peut de nouveau se connecter avec son mot de passe.`);
    }
  }

  private remplacer(c: CompteVue): void {
    this.comptes.update((l) => l.map((x) => (x.utilisateurId === c.utilisateurId ? c : x)));
  }

  private async charger(): Promise<void> {
    const l = await this.action.executer(() => this.api.comptes());
    if (l) {
      this.comptes.set(l);
      this.charge.set(true);
    }
  }
}
