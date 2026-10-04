import { DatePipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { filtrer } from '../../core/recherche';
import { RechercheComponent } from '../../partage/recherche.component';
import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { CompteVue, EtablissementVue, ResultatReinitialisation, StatutTenant } from '../modeles-admin';
import { MotDePasseTemporaireComponent } from '../mot-de-passe-temporaire.component';

const LIBELLE_STATUT: Record<StatutTenant, string> = { ACTIF: 'Actif', SUSPENDU: 'Suspendu', RESILIE: 'Résilié' };

/** Super administrateur : établissements de la plateforme. */
@Component({
  selector: 'app-plateforme',
  imports: [FormsModule, DatePipe, MotDePasseTemporaireComponent, RechercheComponent],
  template: `
    <div class="page large">
      <h1>Établissements</h1>

      @if (cree(); as c) {
        <app-mot-de-passe-temporaire
          [titre]="'Établissement « ' + c.nom + ' » créé. Compte de son administrateur :'"
          [telephone]="c.telephone"
          [motDePasse]="c.motDePasse"
          (fermer)="cree.set(null)"
        />
      }
      @if (reinitialise(); as r) {
        <app-mot-de-passe-temporaire
          [titre]="'Mot de passe de l’administrateur ' + r.nom + ' ' + r.prenoms + ' réinitialisé.'"
          [telephone]="r.telephone"
          [motDePasse]="r.motDePasseTemporaire"
          (fermer)="reinitialise.set(null)"
        />
      }
      @if (liste.erreur()) {
        <div class="alerte erreur" role="alert">{{ liste.erreur() }}</div>
      }

      <section class="carte">
        <div class="entete-section">
          <h2>{{ etablissements().length }} établissement(s)</h2>
          <button type="button" class="bouton" (click)="formulaire.set(!formulaire())">
            {{ formulaire() ? 'Annuler' : 'Nouvel établissement' }}
          </button>
        </div>

        @if (formulaire()) {
          <form (ngSubmit)="creer()" class="nouveau">
            @if (creation.erreur()) {
              <div class="alerte erreur" role="alert">{{ creation.erreur() }}</div>
            }
            <div class="grille-champs">
              <div class="champ">
                <label for="code">Code</label>
                <input id="code" name="code" required minlength="3" maxlength="30" placeholder="ltk" [(ngModel)]="code" />
                <span class="doux">Court et unique : il servira d'adresse (ltk.votre-domaine).</span>
              </div>
              <div class="champ">
                <label for="nom">Nom de l'établissement</label>
                <input id="nom" name="nom" required maxlength="200" [(ngModel)]="nom" />
              </div>
            </div>
            <h3>Premier administrateur</h3>
            <div class="grille-champs">
              <div class="champ">
                <label for="tel">Téléphone</label>
                <input id="tel" name="tel" type="tel" inputmode="tel" required [(ngModel)]="telephone" />
              </div>
              <div class="champ">
                <label for="nomAdmin">Nom</label>
                <input id="nomAdmin" name="nomAdmin" required [(ngModel)]="nomAdmin" />
              </div>
              <div class="champ">
                <label for="prenomsAdmin">Prénoms</label>
                <input id="prenomsAdmin" name="prenomsAdmin" required [(ngModel)]="prenomsAdmin" />
              </div>
            </div>
            <button type="submit" class="bouton" [disabled]="creation.enCours()">
              {{ creation.enCours() ? 'Création…' : 'Créer l’établissement' }}
            </button>
          </form>
        }

        <div class="filtres">
          <app-recherche
            libelle="Rechercher un établissement (nom ou code)"
            [(valeur)]="filtre"
            [total]="etablissements().length"
            [trouves]="affiches().length"
          />
          <label class="visuellement-cache" for="filtreStatut">Statut</label>
          <select id="filtreStatut" [value]="filtreStatut()" (change)="filtreStatut.set($any($event.target).value)">
            <option value="">Tous les statuts</option>
            <option value="ACTIF">Actifs</option>
            <option value="SUSPENDU">Suspendus</option>
            <option value="RESILIE">Résiliés</option>
          </select>
        </div>
        <div class="tableau-defilant">
          <table class="tableau">
            <thead>
              <tr><th>Code</th><th>Nom</th><th>Créé le</th><th>Statut</th><th></th></tr>
            </thead>
            <tbody>
              @for (e of affiches(); track e.id) {
                <tr>
                  <td><strong>{{ e.code }}</strong></td>
                  <td>{{ e.nom }}</td>
                  <td>{{ e.creeLe | date: 'dd/MM/yyyy' }}</td>
                  <td>
                    <span class="pastille" [class.present]="e.statut === 'ACTIF'" [class.absent]="e.statut !== 'ACTIF'">
                      {{ libelleStatut[e.statut] }}
                    </span>
                  </td>
                  <td class="nombre actions-etab">
                    <button type="button" class="bouton secondaire petit" [attr.aria-expanded]="ouvert() === e.id" (click)="basculerAdmins(e)">Administrateurs</button>
                    @if (e.statut === 'ACTIF') {
                      <button type="button" class="bouton danger petit" (click)="statut(e, 'SUSPENDU')">Suspendre</button>
                    } @else if (e.statut === 'SUSPENDU') {
                      <button type="button" class="bouton secondaire petit" (click)="statut(e, 'ACTIF')">Réactiver</button>
                    }
                  </td>
                </tr>
                @if (ouvert() === e.id) {
                  <tr class="admins">
                    <td colspan="5">
                      @if (admins.erreur()) { <div class="alerte erreur" role="alert">{{ admins.erreur() }}</div> }
                      @if (administrateurs() === null) {
                        <span class="doux">Chargement…</span>
                      } @else {
                        <p class="doux">
                          Si l'administrateur de l'établissement a oublié son mot de passe, réinitialisez-le après avoir vérifié
                          son identité : un mot de passe provisoire s'affiche une seule fois, à lui transmettre.
                        </p>
                        <ul class="liste">
                          @for (a of administrateurs(); track a.utilisateurId) {
                            <li>
                              <span>
                                <strong>{{ a.nom }} {{ a.prenoms }}</strong> · {{ a.telephone }}
                                @if (a.verrouilleJusqua) { <span class="pastille absent">verrouillé</span> }
                                @if (a.motDePasseProvisoire) { <span class="pastille">mot de passe provisoire</span> }
                              </span>
                              @if (confirmation() === a.utilisateurId) {
                                <span class="actions-ligne">
                                  <button type="button" class="bouton danger petit" [disabled]="admins.enCours()" (click)="reinitialiser(e, a)">Confirmer la réinitialisation</button>
                                  <button type="button" class="bouton discret petit" (click)="confirmation.set(null)">Annuler</button>
                                </span>
                              } @else {
                                <button type="button" class="bouton petit" (click)="confirmation.set(a.utilisateurId)">Réinitialiser le mot de passe</button>
                              }
                            </li>
                          } @empty {
                            <li class="doux">Aucun administrateur actif.</li>
                          }
                        </ul>
                      }
                    </td>
                  </tr>
                }
              } @empty {
                <tr><td colspan="5" class="doux">{{ etablissements().length ? 'Aucun établissement ne correspond.' : 'Aucun établissement.' }}</td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    </div>
  `,
  styles: `
    .filtres {
      display: flex;
      flex-wrap: wrap;
      gap: 0 0.75rem;
      align-items: flex-start;
      app-recherche {
        flex: 1 1 16rem;
        min-width: 0;
      }
      select {
        width: auto;
      }
    }
    .nouveau {
      border-bottom: 1px solid var(--bordure);
      padding-bottom: 1rem;
      margin-bottom: 1rem;
    }
    .actions-etab {
      white-space: nowrap;
      button + button {
        margin-left: 0.35rem;
      }
    }
    .admins td {
      background: var(--surface);
    }
    .admins li {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 0.5rem;
    }
    h3 {
      font-size: 1rem;
      margin: 0.5rem 0 0.75rem;
    }
  `,
})
export class PlateformePage implements OnInit {
  private readonly api = inject(AdminApi);

  protected readonly libelleStatut = LIBELLE_STATUT;
  protected readonly etablissements = signal<EtablissementVue[]>([]);
  protected readonly filtre = signal('');
  protected readonly filtreStatut = signal<StatutTenant | ''>('');
  protected readonly affiches = computed(() =>
    filtrer(
      this.etablissements().filter((e) => !this.filtreStatut() || e.statut === this.filtreStatut()),
      this.filtre(),
      (e) => [e.nom, e.code],
    ),
  );
  protected readonly formulaire = signal(false);
  protected readonly cree = signal<{ nom: string; telephone: string; motDePasse: string } | null>(null);
  protected readonly liste = new Action();
  protected readonly admins = new Action();
  protected readonly ouvert = signal<string | null>(null);
  protected readonly administrateurs = signal<CompteVue[] | null>(null);
  protected readonly confirmation = signal<string | null>(null);
  protected readonly reinitialise = signal<ResultatReinitialisation | null>(null);
  protected readonly creation = new Action();

  protected readonly code = signal('');
  protected readonly nom = signal('');
  protected readonly telephone = signal('');
  protected readonly nomAdmin = signal('');
  protected readonly prenomsAdmin = signal('');

  async ngOnInit(): Promise<void> {
    await this.recharger();
  }

  protected async basculerAdmins(e: EtablissementVue): Promise<void> {
    this.confirmation.set(null);
    if (this.ouvert() === e.id) {
      this.ouvert.set(null);
      return;
    }
    this.ouvert.set(e.id);
    this.administrateurs.set(null);
    const l = await this.admins.executer(() => this.api.administrateursEtablissement(e.id));
    if (this.ouvert() === e.id) {
      this.administrateurs.set(l ?? []);
    }
  }

  protected async reinitialiser(e: EtablissementVue, a: CompteVue): Promise<void> {
    const r = await this.admins.executer(() => this.api.reinitialiserAdministrateur(e.id, a.utilisateurId));
    this.confirmation.set(null);
    if (r) {
      this.reinitialise.set(r);
      this.administrateurs.update((l) => (l ?? []).map((x) => (x.utilisateurId === a.utilisateurId ? { ...x, verrouilleJusqua: null, motDePasseProvisoire: true } : x)));
    }
  }

  private async recharger(): Promise<void> {
    const liste = await this.liste.executer(() => this.api.etablissements());
    if (liste) {
      this.etablissements.set([...liste].sort((a, b) => a.nom.localeCompare(b.nom)));
    }
  }

  protected async creer(): Promise<void> {
    const r = await this.creation.executer(() =>
      this.api.creerEtablissement({
        code: this.code().trim(),
        nom: this.nom().trim(),
        telephoneAdministrateur: this.telephone().trim(),
        nomAdministrateur: this.nomAdmin().trim(),
        prenomsAdministrateur: this.prenomsAdmin().trim(),
      }),
    );
    if (!r) {
      return;
    }
    this.cree.set({
      nom: r.etablissement.nom,
      telephone: this.telephone().trim(),
      motDePasse: r.motDePasseTemporaire ?? '(compte existant : son mot de passe actuel)',
    });
    for (const s of [this.code, this.nom, this.telephone, this.nomAdmin, this.prenomsAdmin]) {
      s.set('');
    }
    this.formulaire.set(false);
    await this.recharger();
  }

  protected async statut(e: EtablissementVue, statut: StatutTenant): Promise<void> {
    if (statut === 'SUSPENDU' && !window.confirm(`Suspendre « ${e.nom} » ? Ses utilisateurs ne pourront plus se connecter.`)) {
      return;
    }
    if (await this.liste.executer(() => this.api.changerStatutEtablissement(e.id, statut))) {
      await this.recharger();
    }
  }
}
