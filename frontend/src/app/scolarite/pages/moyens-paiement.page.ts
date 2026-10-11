import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { SessionService } from '../../core/session.service';
import {
  CompteBancaireVue,
  ConfigurationMobileMoney,
  DonneesCompte,
  LIBELLE_MOYEN,
  MOYENS,
  MoyenPaiement,
  OPERATEURS_MM,
  ParametresPaiement,
} from '../modeles-scolarite';
import { ScolariteApi } from '../scolarite-api.service';
import { ScoNavComponent } from '../sco-nav.component';

const COMPTE_VIDE: DonneesCompte = { banque: '', titulaire: '', numero: '', codeSwift: '', agence: '', actif: true };

/** Ajoute ou retire un moyen d'une liste, dans l'ordre usuel. */
export function basculerMoyen(liste: MoyenPaiement[], m: MoyenPaiement): MoyenPaiement[] {
  return liste.includes(m) ? liste.filter((x) => x !== m) : MOYENS.filter((x) => x === m || liste.includes(x));
}

/**
 * Moyens de paiement de l'établissement (v0.38) : ce que l'intendance accepte au guichet, ce que
 * les parents peuvent utiliser eux-mêmes (Mobile Money et ses opérateurs, virement déclaré) et
 * les comptes bancaires qui leur sont indiqués. Modifiable par l'administrateur ; consultable
 * par l'intendance et le secrétariat.
 */
@Component({
  selector: 'app-moyens-paiement',
  imports: [FormsModule, ScoNavComponent],
  template: `
    <div class="page large">
      <h1>Scolarité et paiements</h1>
      <app-sco-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message()) {
        <div class="alerte succes" role="status">{{ message() }}</div>
      }
      @if (!admin) {
        <p class="doux">Seul l'administrateur de l'établissement modifie ces réglages.</p>
      }

      @if (form(); as f) {
        <form class="carte" (ngSubmit)="enregistrer()">
          <h2>Au guichet</h2>
          <p class="doux">Moyens que l'intendance peut enregistrer (les autres ne lui sont pas proposés).</p>
          <div class="cases">
            @for (m of moyens; track m) {
              <label class="case">
                <input type="checkbox" [name]="'g-' + m" [checked]="f.moyensGuichet.includes(m)" [disabled]="!admin"
                  (change)="f.moyensGuichet = basculer(f.moyensGuichet, m)" />
                {{ libelle[m] }}
              </label>
            }
          </div>

          <h2>Pour les parents</h2>
          <label class="case">
            <input type="checkbox" name="mm" [(ngModel)]="f.mobileMoneyParents" [disabled]="!admin" />
            Paiement Mobile Money depuis l'espace parent
          </label>
          @if (f.mobileMoneyParents) {
            <div class="cases retrait">
              @for (o of operateurs; track o) {
                <label class="case">
                  <input type="checkbox" [name]="'o-' + o" [checked]="f.operateurs.includes(o)" [disabled]="!admin"
                    (change)="f.operateurs = basculer(f.operateurs, o)" />
                  {{ libelle[o] }}
                </label>
              }
            </div>
            @if (!parametres()?.mobileMoneyDisponible) {
              <p class="alerte attention retrait">
                Le compte marchand Mobile Money n'est pas encore configuré (ou le module est désactivé) : les parents ne
                verront pas ce moyen tant qu'il ne l'est pas.
              </p>
            }
          }
          <label class="case">
            <input type="checkbox" name="vir" [(ngModel)]="f.virementParents" [disabled]="!admin" />
            Virement bancaire déclaré par le parent, vérifié puis validé par l'intendance
          </label>
          @if (f.virementParents) {
            <div class="champ retrait">
              <label for="consigne">Consigne affichée aux parents (facultatif)</label>
              <textarea id="consigne" name="consigne" rows="2" maxlength="300" [disabled]="!admin" [(ngModel)]="f.consigneVirement"
                placeholder="Ex. : indiquez la référence de l'élève dans le motif du virement."></textarea>
            </div>
          }
          @if (admin) {
            <button type="submit" class="bouton" [disabled]="action.enCours() || !f.moyensGuichet.length">Enregistrer</button>
          }
        </form>
      }

      @if (mm(); as c) {
        <section class="carte">
          <h2>Compte marchand Mobile Money</h2>
          <p class="doux">
            Contrat de l'établissement avec un agrégateur (paiements des parents sur téléphone). Adresse de notification à
            communiquer à l'agrégateur : <code>{{ c.urlNotification }}</code>
          </p>
          @if (admin) {
            <form (ngSubmit)="enregistrerMobileMoney()">
              <div class="grille-champs">
                <div class="champ">
                  <label for="mm-agregateur">Agrégateur</label>
                  <select id="mm-agregateur" name="agregateur" [(ngModel)]="mmAgregateur">
                    @for (a of c.agregateursDisponibles; track a) { <option [value]="a">{{ a }}</option> }
                  </select>
                </div>
                <div class="champ"><label for="mm-marchand">Identifiant marchand</label><input id="mm-marchand" name="marchand" maxlength="80" [(ngModel)]="mmMarchand" /></div>
                <div class="champ">
                  <label for="mm-cle">Clé d'API</label>
                  <input id="mm-cle" name="cle" type="password" autocomplete="off" maxlength="500" [(ngModel)]="mmCle"
                    [placeholder]="c.cleApiDefinie ? 'Enregistrée : laisser vide pour la garder' : ''" />
                </div>
                <div class="champ">
                  <label for="mm-secret">Secret de notification</label>
                  <input id="mm-secret" name="secret" type="password" autocomplete="off" maxlength="500" [(ngModel)]="mmSecret"
                    [placeholder]="c.secretWebhookDefini ? 'Enregistré : laisser vide pour le garder' : ''" />
                </div>
              </div>
              <label class="case"><input type="checkbox" name="mmactif" [(ngModel)]="mmActif" /> Compte actif</label>
              <button type="submit" class="bouton petit" [disabled]="action.enCours() || !mmAgregateur() || !mmMarchand().trim()">Enregistrer Mobile Money</button>
            </form>
          } @else {
            <p>{{ c.agregateur ? c.agregateur + ' · ' + c.identifiantMarchand + (c.actif ? ' · actif' : ' · inactif') : 'Non configuré.' }}</p>
          }
        </section>
      }

      <section class="carte">
        <div class="entete-section">
          <h2>Comptes bancaires</h2>
          @if (admin && !formCompte()) {
            <button type="button" class="bouton secondaire petit" (click)="nouveauCompte()">Ajouter un compte</button>
          }
        </div>
        <p class="doux">Affichés aux parents qui paient par virement (comptes actifs seulement).</p>
        <ul class="liste">
          @for (c of parametres()?.comptes ?? []; track c.id) {
            <li>
              <span>
                <strong>{{ c.banque }}</strong> · {{ c.titulaire }}
                @if (!c.actif) { <span class="pastille">inactif</span> }
                <br /><span class="numero">{{ c.numero }}</span>
                @if (c.codeSwift) { <span class="doux">&nbsp;· SWIFT {{ c.codeSwift }}</span> }
                @if (c.agence) { <span class="doux">&nbsp;· {{ c.agence }}</span> }
              </span>
              @if (admin) {
                <button type="button" class="bouton discret petit" (click)="modifierCompte(c)">Modifier</button>
              }
            </li>
          } @empty {
            <li class="doux">Aucun compte bancaire enregistré.</li>
          }
        </ul>
        @if (formCompte(); as c) {
          <form class="formulaire" (ngSubmit)="enregistrerCompte()">
            <div class="grille-champs">
              <div class="champ"><label for="cb-banque">Banque</label><input id="cb-banque" name="banque" maxlength="120" required [(ngModel)]="c.banque" /></div>
              <div class="champ"><label for="cb-titulaire">Titulaire du compte</label><input id="cb-titulaire" name="titulaire" maxlength="150" required [(ngModel)]="c.titulaire" /></div>
              <div class="champ large"><label for="cb-numero">Numéro (RIB ou IBAN)</label><input id="cb-numero" name="numero" maxlength="60" required [(ngModel)]="c.numero" /></div>
              <div class="champ"><label for="cb-swift">Code SWIFT (facultatif)</label><input id="cb-swift" name="swift" maxlength="11" [(ngModel)]="c.codeSwift" /></div>
              <div class="champ"><label for="cb-agence">Agence (facultatif)</label><input id="cb-agence" name="agence" maxlength="120" [(ngModel)]="c.agence" /></div>
            </div>
            <label class="case"><input type="checkbox" name="actif" [(ngModel)]="c.actif" /> Actif (proposé aux parents)</label>
            <div class="actions-ligne">
              <button type="submit" class="bouton petit" [disabled]="action.enCours() || !compteValide()">Enregistrer le compte</button>
              <button type="button" class="bouton discret petit" (click)="formCompte.set(null)">Annuler</button>
            </div>
          </form>
        }
      </section>
    </div>
  `,
  styles: `
    h2 {
      font-size: 1.05rem;
      margin: 0.75rem 0 0.25rem;
    }
    .cases {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem 1.25rem;
      margin-bottom: 0.5rem;
    }
    .case {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      margin-bottom: 0.5rem;
      input {
        width: 1.2rem;
        min-height: 1.2rem;
        height: 1.2rem;
      }
    }
    .retrait {
      margin-left: 1.6rem;
    }
    textarea {
      width: 100%;
    }
    .liste li {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 0.4rem;
    }
    .numero {
      font-family: monospace;
      overflow-wrap: anywhere;
    }
    .formulaire {
      border-top: 1px solid var(--bordure);
      margin-top: 0.75rem;
      padding-top: 0.75rem;
    }
    .champ.large {
      grid-column: span 2;
    }
    @media (max-width: 40rem) {
      .champ.large {
        grid-column: auto;
      }
    }
  `,
})
export class MoyensPaiementPage implements OnInit {
  private readonly api = inject(ScolariteApi);
  private readonly session = inject(SessionService);
  protected readonly admin = this.session.aLeRole('ADMIN_ECOLE');

  /** Compte marchand Mobile Money (module actif ; intendance et administrateur). */
  protected readonly mm = signal<ConfigurationMobileMoney | null>(null);
  protected readonly mmAgregateur = signal('');
  protected readonly mmMarchand = signal('');
  protected readonly mmCle = signal('');
  protected readonly mmSecret = signal('');
  protected readonly mmActif = signal(true);

  protected readonly moyens = MOYENS;
  protected readonly operateurs = OPERATEURS_MM;
  protected readonly libelle = LIBELLE_MOYEN;
  protected readonly basculer = basculerMoyen;

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly parametres = signal<ParametresPaiement | null>(null);
  protected readonly form = signal<{
    moyensGuichet: MoyenPaiement[];
    mobileMoneyParents: boolean;
    operateurs: MoyenPaiement[];
    virementParents: boolean;
    consigneVirement: string;
  } | null>(null);
  protected readonly formCompte = signal<(DonneesCompte & { id?: string }) | null>(null);
  /** Méthode (pas un signal calculé) : les champs du formulaire modifient l'objet en place. */
  protected compteValide(): boolean {
    const c = this.formCompte();
    return !!c && !!c.banque.trim() && !!c.titulaire.trim() && !!c.numero.trim();
  }

  async ngOnInit(): Promise<void> {
    await this.charger();
    if (this.session.moduleActif('MOBILE_MONEY') && this.session.aLeRole('ADMIN_ECOLE', 'INTENDANT')) {
      const c = await this.action.executer(() => this.api.configurationMobileMoney());
      if (c) {
        this.appliquerMobileMoney(c);
      }
    }
  }

  private appliquerMobileMoney(c: ConfigurationMobileMoney): void {
    this.mm.set(c);
    this.mmAgregateur.set(c.agregateur ?? c.agregateursDisponibles[0] ?? '');
    this.mmMarchand.set(c.identifiantMarchand ?? '');
    this.mmCle.set('');
    this.mmSecret.set('');
    this.mmActif.set(c.agregateur ? c.actif : true);
  }

  protected async enregistrerMobileMoney(): Promise<void> {
    this.message.set(null);
    const c = await this.action.executer(() =>
      this.api.configurerMobileMoney({
        agregateur: this.mmAgregateur(),
        identifiantMarchand: this.mmMarchand().trim(),
        cleApi: this.mmCle().trim() || null,
        secretWebhook: this.mmSecret().trim() || null,
        actif: this.mmActif(),
      }),
    );
    if (c) {
      this.appliquerMobileMoney(c);
      this.message.set('Compte marchand Mobile Money enregistré.');
      await this.charger();
    }
  }

  private async charger(): Promise<void> {
    const p = await this.action.executer(() => this.api.parametresPaiement());
    if (p) {
      this.appliquer(p);
    }
  }

  private appliquer(p: ParametresPaiement): void {
    this.parametres.set(p);
    this.form.set({
      moyensGuichet: [...p.moyensGuichet],
      mobileMoneyParents: p.mobileMoneyParents,
      operateurs: [...p.operateurs],
      virementParents: p.virementParents,
      consigneVirement: p.consigneVirement ?? '',
    });
  }

  protected async enregistrer(): Promise<void> {
    const f = this.form();
    if (!f) {
      return;
    }
    this.message.set(null);
    const p = await this.action.executer(() =>
      this.api.modifierParametresPaiement({ ...f, consigneVirement: f.consigneVirement.trim() || null }),
    );
    if (p) {
      this.appliquer(p);
      this.message.set('Moyens de paiement enregistrés.');
    }
  }

  protected nouveauCompte(): void {
    this.formCompte.set({ ...COMPTE_VIDE });
  }

  protected modifierCompte(c: CompteBancaireVue): void {
    this.formCompte.set({ ...c, codeSwift: c.codeSwift ?? '', agence: c.agence ?? '' });
  }

  protected async enregistrerCompte(): Promise<void> {
    const f = this.formCompte();
    if (!f) {
      return;
    }
    const { id, ...d } = f;
    const donnees: DonneesCompte = { ...d, codeSwift: d.codeSwift?.trim() || null, agence: d.agence?.trim() || null };
    this.message.set(null);
    const r = await this.action.executer(() => (id ? this.api.modifierCompte(id, donnees) : this.api.creerCompte(donnees)));
    if (r) {
      this.formCompte.set(null);
      this.message.set(`Compte « ${r.banque} » enregistré.`);
      await this.charger();
    }
  }
}
