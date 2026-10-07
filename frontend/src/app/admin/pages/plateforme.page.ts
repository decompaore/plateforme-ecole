import { DatePipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Module } from '../../core/modeles';
import { filtrer } from '../../core/recherche';
import { RechercheComponent } from '../../partage/recherche.component';
import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { CompteVue, EtablissementVue, ModuleEtablissementVue, ResultatReinitialisation, StatutTenant } from '../modeles-admin';
import { ExportsDonneesComponent } from '../exports-donnees.component';
import { MotDePasseTemporaireComponent } from '../mot-de-passe-temporaire.component';
import { DirectionChemin } from '../modeles-territoire';
import { PlateformeNavComponent } from '../plateforme-nav.component';
import { TerritoireApi } from '../territoire-api.service';


const LIBELLE_STATUT: Record<StatutTenant, string> = { ACTIF: 'Actif', SUSPENDU: 'Suspendu', RESILIE: 'Résilié' };

/** Super administrateur : établissements de la plateforme. */
@Component({
  selector: 'app-plateforme',
  imports: [FormsModule, DatePipe, MotDePasseTemporaireComponent, RechercheComponent, ExportsDonneesComponent, PlateformeNavComponent],
  template: `
    <div class="page large">
      <h1>Établissements</h1>
      <app-plateforme-nav />

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
          <button type="button" class="bouton" (click)="formulaire.set(!formulaire()); chargerDirections()">
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
            <div class="champ">
              <label for="rattachement">Rattachement (pays, ministère, directions)</label>
              <select id="rattachement" name="rattachement" [(ngModel)]="directionCreation">
                <option [ngValue]="null">Sans rattachement pour l'instant</option>
                @for (d of terminales(); track d.id) { <option [ngValue]="d.id">{{ d.chemin }}</option> }
              </select>
              <span class="doux">Il figure sur les documents officiels et donne l'indicatif téléphonique du pays.
                Le référentiel se gère dans l'onglet Territoire.</span>
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
          <label class="visuellement-cache" for="filtreDirection">Direction</label>
          <select id="filtreDirection" (focus)="chargerDirections()" [ngModel]="filtreDirection()" (ngModelChange)="filtrerDirection($event)">
            <option [ngValue]="null">Toutes les directions</option>
            @for (d of directions(); track d.id) { <option [ngValue]="d.id">{{ d.chemin }}</option> }
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
                  <td>
                    {{ e.nom }}
                    <br />
                    @if (e.rattachement) {
                      <span class="doux petit-texte">{{ e.rattachement }}</span>
                    } @else {
                      <span class="pastille">non rattaché</span>
                    }
                  </td>
                  <td>{{ e.creeLe | date: 'dd/MM/yyyy' }}</td>
                  <td>
                    <span class="pastille" [class.present]="e.statut === 'ACTIF'" [class.absent]="e.statut !== 'ACTIF'">
                      {{ libelleStatut[e.statut] }}
                    </span>
                  </td>
                  <td class="nombre actions-etab">
                    <button type="button" class="bouton secondaire petit" [attr.aria-expanded]="ouvert() === e.id" (click)="basculerAdmins(e)">Administrateurs</button>
                    <button type="button" class="bouton secondaire petit" [attr.aria-expanded]="ouvertModules() === e.id" (click)="basculerModules(e)">Modules</button>
                    <button type="button" class="bouton secondaire petit" [attr.aria-expanded]="ouvertExports() === e.id" (click)="basculerExports(e)">Données</button>
                    <button type="button" class="bouton secondaire petit" [attr.aria-expanded]="ouvertRattachement() === e.id" (click)="basculerRattachement(e)">Rattachement</button>
                    @if (e.statut === 'ACTIF') {
                      <button type="button" class="bouton danger petit" (click)="statut(e, 'SUSPENDU')">Suspendre</button>
                    } @else if (e.statut === 'SUSPENDU') {
                      <button type="button" class="bouton secondaire petit" (click)="statut(e, 'ACTIF')">Réactiver</button>
                    }
                  </td>
                </tr>
                @if (ouvertRattachement() === e.id) {
                  <tr class="admins">
                    <td colspan="5">
                      @if (actionRattachement.erreur()) { <div class="alerte erreur" role="alert">{{ actionRattachement.erreur() }}</div> }
                      <div class="rattacher">
                        <label [for]="'ratt-' + e.id">Direction de rattachement</label>
                        <select [id]="'ratt-' + e.id" [ngModel]="choixRattachement()" (ngModelChange)="choixRattachement.set($event)">
                          <option [ngValue]="null" disabled>Choisir…</option>
                          @for (d of terminales(); track d.id) { <option [ngValue]="d.id">{{ d.chemin }}</option> }
                        </select>
                        <button type="button" class="bouton petit" [disabled]="!choixRattachement() || choixRattachement() === e.directionId || actionRattachement.enCours()"
                          (click)="rattacher(e)">Enregistrer</button>
                      </div>
                      @if (!terminales().length) {
                        <p class="doux">Aucune direction disponible : créez le ministère et ses directions dans l'onglet Territoire.</p>
                      }
                    </td>
                  </tr>
                }
                @if (ouvertExports() === e.id) {
                  <tr class="admins">
                    <td colspan="5">
                      <p class="doux">
                        Export complet des données de l'établissement (réversibilité), par exemple quand il quitte la
                        plateforme : possible aussi s'il est suspendu ou résilié. L'établissement voit cet export dans sa
                        propre liste.
                      </p>
                      <app-exports-donnees [etablissementId]="e.id" />
                    </td>
                  </tr>
                }
                @if (ouvertModules() === e.id) {
                  <tr class="admins modules">
                    <td colspan="5">
                      @if (actionModules.erreur()) { <div class="alerte erreur" role="alert">{{ actionModules.erreur() }}</div> }
                      @if (messageModules()) { <div class="alerte succes" role="status">{{ messageModules() }}</div> }
                      @if (modules() === null) {
                        <span class="doux">Chargement…</span>
                      } @else {
                        <p class="doux">
                          Le socle (classes, élèves, enseignants, appel, notes, bulletins, comptes) est toujours actif. Un
                          module désactivé disparaît de l'application de l'établissement ; ses données sont gardées et
                          reviennent si on le réactive.
                        </p>
                        <ul class="liste">
                          @for (m of modules(); track m.code) {
                            <li class="module">
                              <label>
                                <input type="checkbox" [checked]="actifs().includes(m.code)" (change)="basculerModule(m.code)" />
                                <span>
                                  <strong>{{ m.libelle }}</strong>
                                  @if (m.requis) { <span class="doux"> · nécessite « {{ libelleModule(m.requis) }} »</span> }
                                  <br /><span class="doux">{{ m.description }}</span>
                                </span>
                              </label>
                            </li>
                          }
                        </ul>
                        <div class="actions-ligne">
                          <button type="button" class="bouton petit" [disabled]="actionModules.enCours() || !modulesModifies()" (click)="enregistrerModules(e)">Enregistrer les modules</button>
                          @if (modulesModifies()) {
                            <button type="button" class="bouton discret petit" (click)="annulerModules()">Annuler</button>
                          }
                        </div>
                      }
                    </td>
                  </tr>
                }
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
    .petit-texte {
      font-size: 0.8rem;
    }
    .rattacher {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      select {
        flex: 1 1 20rem;
        min-width: 0;
      }
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
    .modules li.module {
      justify-content: flex-start;
      label {
        display: flex;
        gap: 0.6rem;
        align-items: flex-start;
        cursor: pointer;
      }
      input {
        width: 1.2rem;
        min-height: 1.2rem;
        height: 1.2rem;
        margin-top: 0.15rem;
      }
    }
  `,
})
export class PlateformePage implements OnInit {
  private readonly api = inject(AdminApi);
  private readonly territoire = inject(TerritoireApi);

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
  // Modules de l'établissement ouvert (v0.31)
  protected readonly actionModules = new Action();
  protected readonly ouvertModules = signal<string | null>(null);
  protected readonly modules = signal<ModuleEtablissementVue[] | null>(null);
  protected readonly actifs = signal<Module[]>([]);
  protected readonly messageModules = signal<string | null>(null);
  protected readonly modulesModifies = computed(() => {
    const avant = (this.modules() ?? []).filter((m) => m.actif).map((m) => m.code).sort();
    return JSON.stringify(avant) !== JSON.stringify([...this.actifs()].sort());
  });
  protected readonly reinitialise = signal<ResultatReinitialisation | null>(null);
  // Export complet de l'établissement ouvert (v0.33)
  protected readonly ouvertExports = signal<string | null>(null);
  protected readonly creation = new Action();

  protected readonly code = signal('');
  protected readonly nom = signal('');
  protected readonly telephone = signal('');
  protected readonly nomAdmin = signal('');
  protected readonly prenomsAdmin = signal('');
  // Rattachement territorial (v0.35)
  protected readonly directions = signal<DirectionChemin[]>([]);
  protected readonly terminales = computed(() => this.directions().filter((d) => d.terminale && d.actif));
  protected readonly directionCreation = signal<string | null>(null);
  protected readonly filtreDirection = signal<string | null>(null);
  protected readonly ouvertRattachement = signal<string | null>(null);
  protected readonly choixRattachement = signal<string | null>(null);
  protected readonly actionRattachement = new Action();
  private directionsChargees = false;

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

  protected async basculerModules(e: EtablissementVue): Promise<void> {
    this.messageModules.set(null);
    if (this.ouvertModules() === e.id) {
      this.ouvertModules.set(null);
      return;
    }
    this.ouvertModules.set(e.id);
    this.modules.set(null);
    const l = await this.actionModules.executer(() => this.api.modulesEtablissement(e.id));
    if (l && this.ouvertModules() === e.id) {
      this.afficherModules(l);
    }
  }

  protected basculerExports(e: EtablissementVue): void {
    this.ouvertExports.set(this.ouvertExports() === e.id ? null : e.id);
  }

  protected libelleModule(code: Module): string {
    return this.modules()?.find((m) => m.code === code)?.libelle ?? code;
  }

  /** Cocher un module coche celui dont il dépend ; décocher un module décoche ceux qui en dépendent. */
  protected basculerModule(code: Module): void {
    this.messageModules.set(null);
    const tous = this.modules() ?? [];
    const actifs = new Set(this.actifs());
    if (actifs.has(code)) {
      actifs.delete(code);
      tous.filter((m) => m.requis === code).forEach((m) => actifs.delete(m.code));
    } else {
      actifs.add(code);
      const requis = tous.find((m) => m.code === code)?.requis;
      if (requis) {
        actifs.add(requis);
      }
    }
    this.actifs.set(tous.map((m) => m.code).filter((c) => actifs.has(c)));
  }

  protected annulerModules(): void {
    this.afficherModules(this.modules() ?? []);
  }

  protected async enregistrerModules(e: EtablissementVue): Promise<void> {
    const l = await this.actionModules.executer(() => this.api.definirModules(e.id, this.actifs()));
    if (l) {
      this.afficherModules(l);
      const fermes = l.filter((m) => !m.actif).map((m) => m.libelle);
      this.messageModules.set(fermes.length ? `Modules enregistrés. Désactivés : ${fermes.join(', ')}.` : 'Modules enregistrés : tous sont actifs.');
    }
  }

  private afficherModules(l: ModuleEtablissementVue[]): void {
    this.modules.set(l);
    this.actifs.set(l.filter((m) => m.actif).map((m) => m.code));
  }

  protected async reinitialiser(e: EtablissementVue, a: CompteVue): Promise<void> {
    const r = await this.admins.executer(() => this.api.reinitialiserAdministrateur(e.id, a.utilisateurId));
    this.confirmation.set(null);
    if (r) {
      this.reinitialise.set(r);
      this.administrateurs.update((l) => (l ?? []).map((x) => (x.utilisateurId === a.utilisateurId ? { ...x, verrouilleJusqua: null, motDePasseProvisoire: true } : x)));
    }
  }

  /** Directions avec leur chemin : chargées au premier besoin (filtre, création, rattachement). */
  protected async chargerDirections(): Promise<void> {
    if (this.directionsChargees) {
      return;
    }
    this.directionsChargees = true;
    const l = await this.liste.executer(() => this.territoire.directionsAvecChemin());
    if (l) {
      this.directions.set(l);
    } else {
      this.directionsChargees = false;
    }
  }

  protected async filtrerDirection(id: string | null): Promise<void> {
    this.filtreDirection.set(id);
    await this.recharger();
  }

  protected async basculerRattachement(e: EtablissementVue): Promise<void> {
    if (this.ouvertRattachement() === e.id) {
      this.ouvertRattachement.set(null);
      return;
    }
    this.ouvertRattachement.set(e.id);
    this.choixRattachement.set(e.directionId);
    await this.chargerDirections();
  }

  protected async rattacher(e: EtablissementVue): Promise<void> {
    const direction = this.choixRattachement();
    if (!direction) {
      return;
    }
    const r = await this.actionRattachement.executer(() => this.api.rattacherEtablissement(e.id, direction));
    if (r) {
      this.etablissements.update((l) => l.map((x) => (x.id === r.id ? r : x)));
      this.ouvertRattachement.set(null);
    }
  }

  private async recharger(): Promise<void> {
    const liste = await this.liste.executer(() => this.api.etablissements(this.filtreDirection()));
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
        directionId: this.directionCreation(),
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
    this.directionCreation.set(null);
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
