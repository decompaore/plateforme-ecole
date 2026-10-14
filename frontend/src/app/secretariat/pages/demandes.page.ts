import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { dateCourte, dateLocale } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import {
  CATEGORIES_COURRIER,
  DemandeVue,
  LIBELLE_CATEGORIE,
  LIBELLE_STATUT_DEMANDE,
  LIBELLE_TYPE_DEMANDE,
  PersonneVue,
  TYPES_DEMANDE,
  TypeDemande,
} from '../modeles-secretariat';
import { SecretariatApi } from '../secretariat-api.service';
import { SecretariatNavComponent } from '../secretariat-nav.component';

/**
 * Actes administratifs du personnel (v0.47.1) : le secrétariat recueille les demandes (mutation, affectation,
 * autorisation d'absence, congé…), les transmet au chef d'établissement qui donne son avis, puis à la hiérarchie
 * (le courrier de départ est enregistré) ; la suite reçue clôt la demande.
 */
@Component({
  selector: 'app-demandes-administratives',
  imports: [FormsModule, SecretariatNavComponent],
  template: `
    <div class="page large">
      <h1>Demandes du personnel</h1>
      <app-secretariat-nav />
      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      <section class="carte">
        <h2>Recevoir une demande</h2>
        <form (ngSubmit)="recevoir()">
          <div class="grille-champs">
            <div class="champ">
              <label for="dType">Nature</label>
              <select id="dType" name="dType" [(ngModel)]="fType">
                @for (t of types; track t) { <option [value]="t">{{ libelleType[t] }}</option> }
              </select>
            </div>
            <div class="champ">
              <label for="dDemandeur">Demandeur</label>
              <select id="dDemandeur" name="dDemandeur" [(ngModel)]="fDemandeur">
                <option value="">— choisir —</option>
                @for (p of personnel(); track p.utilisateurId) {
                  <option [value]="p.utilisateurId">{{ p.nom }} {{ p.prenoms }} ({{ p.fonctions }})</option>
                }
              </select>
            </div>
            <div class="champ">
              <label for="dObjet">Objet</label>
              <input id="dObjet" name="dObjet" maxlength="300" [(ngModel)]="fObjet" />
            </div>
            <div class="champ">
              <label for="dRecue">Reçue le</label>
              <input id="dRecue" name="dRecue" type="date" [max]="aujourdhui" [(ngModel)]="fRecue" />
            </div>
            <div class="champ">
              <label for="dDu">Du <span class="doux">(absence, congé)</span></label>
              <input id="dDu" name="dDu" type="date" [(ngModel)]="fDu" />
            </div>
            <div class="champ">
              <label for="dAu">Au</label>
              <input id="dAu" name="dAu" type="date" [min]="fDu()" [(ngModel)]="fAu" />
            </div>
          </div>
          <button type="submit" class="bouton" [disabled]="action.enCours() || !fDemandeur() || !fObjet().trim()">Enregistrer la demande</button>
        </form>
      </section>

      <section class="carte">
        <h2>Demandes</h2>
        <ul class="liste">
          @for (d of demandes(); track d.id) {
            <li class="demande">
              <div>
                <strong>{{ d.numero }}</strong> · {{ d.typeLibelle }} · {{ d.demandeur }}
                <br />
                {{ d.objet }}@if (d.du) { <span class="doux"> · du {{ dateCourte(d.du) }}@if (d.au) { au {{ dateCourte(d.au) }} }</span> }
                <br />
                <span class="pastille" [class.present]="d.statut === 'AVIS_FAVORABLE' || d.statut === 'CLOTUREE'">{{ libelleStatut[d.statut] }}</span>
                @if (d.avis) { <span class="doux"> Avis : {{ d.avis }}</span> }
                @if (d.courrierNumero) { <span class="doux"> · courrier {{ d.courrierNumero }}</span> }
                @if (d.suite) { <br /><span class="doux">Suite : {{ d.suite }}</span> }
              </div>
              <div class="actions-ligne">
                @if (d.statut === 'RECUE') {
                  <button type="button" class="bouton petit" (click)="transmettre(d)">Transmettre au chef</button>
                }
                @if (chef() && (d.statut === 'RECUE' || d.statut === 'TRANSMISE')) {
                  <input [attr.aria-label]="'Avis sur ' + d.numero" placeholder="Avis (obligatoire si défavorable)" [value]="avisPour(d.id)" (input)="saisirAvis(d.id, $any($event.target).value)" />
                  <button type="button" class="bouton petit" (click)="aviser(d, true)">Favorable</button>
                  <button type="button" class="bouton secondaire petit" (click)="aviser(d, false)">Défavorable</button>
                }
                @if (d.statut === 'AVIS_FAVORABLE' || d.statut === 'AVIS_DEFAVORABLE') {
                  <input [attr.aria-label]="'Destinataire pour ' + d.numero" placeholder="DPEPPNF du …" [value]="destinatairePour(d.id)" (input)="saisirDestinataire(d.id, $any($event.target).value)" />
                  <select [attr.aria-label]="'Niveau pour ' + d.numero" (change)="saisirCategorie(d.id, $any($event.target).value)" style="width:auto">
                    @for (c of hierarchie; track c) { <option [value]="c">{{ libelleCategorie[c] }}</option> }
                  </select>
                  <button type="button" class="bouton petit" (click)="hierarchiser(d)">Transmettre à la hiérarchie</button>
                }
                @if (d.statut !== 'RECUE' && d.statut !== 'TRANSMISE' && d.statut !== 'CLOTUREE') {
                  <input [attr.aria-label]="'Suite donnée à ' + d.numero" placeholder="Suite donnée" [value]="suitePour(d.id)" (input)="saisirSuite(d.id, $any($event.target).value)" />
                  <button type="button" class="bouton secondaire petit" (click)="cloturer(d)">Clore</button>
                }
                <label class="bouton discret petit">
                  {{ d.fichierNom ? 'Remplacer la pièce' : 'Joindre la demande scannée' }}
                  <input type="file" class="visuellement-cache" accept=".pdf,image/jpeg,image/png" (change)="joindre(d, $event)" />
                </label>
                @if (d.fichierNom) { <button type="button" class="bouton discret petit" (click)="piece(d)">Pièce</button> }
              </div>
            </li>
          } @empty {
            <li class="doux">Aucune demande.</li>
          }
        </ul>
      </section>
    </div>
  `,
  styles: `
    .demande {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 0.5rem;
      padding: 0.6rem 0;
    }
    .demande input:not([type='file']) {
      width: auto;
      min-width: 12rem;
    }
  `,
})
export class DemandesPage implements OnInit {
  private readonly api = inject(SecretariatApi);
  private readonly session = inject(SessionService);

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly chef = computed(() => this.session.aLeRole('ADMIN_ECOLE'));
  protected readonly types = TYPES_DEMANDE;
  protected readonly libelleType = LIBELLE_TYPE_DEMANDE;
  protected readonly libelleStatut = LIBELLE_STATUT_DEMANDE;
  protected readonly libelleCategorie = LIBELLE_CATEGORIE;
  protected readonly hierarchie = CATEGORIES_COURRIER.filter((c) => ['DIRECTION_PROVINCIALE', 'DIRECTION_REGIONALE', 'MINISTERE'].includes(c));
  protected readonly dateCourte = dateCourte;
  protected readonly aujourdhui = dateLocale();

  protected readonly demandes = signal<DemandeVue[]>([]);
  protected readonly personnel = signal<PersonneVue[]>([]);
  protected readonly fType = signal<TypeDemande>('AUTORISATION_ABSENCE');
  protected readonly fDemandeur = signal('');
  protected readonly fObjet = signal('');
  protected readonly fRecue = signal(dateLocale());
  protected readonly fDu = signal('');
  protected readonly fAu = signal('');
  private readonly saisies = signal<Record<string, Record<string, string>>>({});

  async ngOnInit(): Promise<void> {
    const r = await this.action.executer(() => Promise.all([this.api.demandes(), this.api.personnel()]));
    if (r) {
      this.demandes.set(r[0]);
      this.personnel.set(r[1]);
    }
  }

  private valeur(id: string, champ: string): string {
    return this.saisies()[id]?.[champ] ?? '';
  }

  private saisir(id: string, champ: string, v: string): void {
    this.saisies.set({ ...this.saisies(), [id]: { ...(this.saisies()[id] ?? {}), [champ]: v } });
  }

  protected avisPour = (id: string) => this.valeur(id, 'avis');
  protected destinatairePour = (id: string) => this.valeur(id, 'destinataire');
  protected suitePour = (id: string) => this.valeur(id, 'suite');
  protected saisirAvis = (id: string, v: string) => this.saisir(id, 'avis', v);
  protected saisirDestinataire = (id: string, v: string) => this.saisir(id, 'destinataire', v);
  protected saisirCategorie = (id: string, v: string) => this.saisir(id, 'categorie', v);
  protected saisirSuite = (id: string, v: string) => this.saisir(id, 'suite', v);

  protected async recevoir(): Promise<void> {
    this.message.set(null);
    const d = await this.action.executer(() =>
      this.api.recevoirDemande({
        type: this.fType(),
        demandeurId: this.fDemandeur(),
        demandeur: null,
        recueLe: this.fRecue() || null,
        objet: this.fObjet().trim(),
        du: this.fDu() || null,
        au: this.fAu() || null,
      }),
    );
    if (d) {
      this.demandes.set([d, ...this.demandes()]);
      this.message.set(`Demande ${d.numero} enregistrée (${d.demandeur}).`);
      this.fObjet.set('');
      this.fDu.set('');
      this.fAu.set('');
    }
  }

  protected async transmettre(d: DemandeVue): Promise<void> {
    await this.maj(() => this.api.transmettreDemande(d.id), (r) => `Demande ${r.numero} transmise au chef d'établissement.`);
  }

  protected async aviser(d: DemandeVue, favorable: boolean): Promise<void> {
    await this.maj(() => this.api.aviser(d.id, favorable, this.avisPour(d.id).trim() || null), (r) => `Avis ${favorable ? 'favorable' : 'défavorable'} sur ${r.numero}.`);
  }

  protected async hierarchiser(d: DemandeVue): Promise<void> {
    const dest = this.destinatairePour(d.id).trim();
    if (!dest) {
      this.action.erreur.set('Indiquez le destinataire (direction provinciale, régionale…).');
      return;
    }
    await this.maj(
      () => this.api.transmettreHierarchie(d.id, dest, this.valeur(d.id, 'categorie') || 'DIRECTION_PROVINCIALE'),
      (r) => `Demande ${r.numero} transmise : courrier départ ${r.courrierNumero}.`,
    );
  }

  protected async cloturer(d: DemandeVue): Promise<void> {
    await this.maj(() => this.api.cloturer(d.id, this.suitePour(d.id).trim()), (r) => `Demande ${r.numero} close.`);
  }

  protected async joindre(d: DemandeVue, e: Event): Promise<void> {
    const f = (e.target as HTMLInputElement).files?.[0];
    if (f) {
      await this.maj(() => this.api.joindreDemande(d.id, f), (r) => `Pièce jointe à la demande ${r.numero}.`);
    }
  }

  protected async piece(d: DemandeVue): Promise<void> {
    const b = await this.action.executer(() => this.api.pieceDemande(d.id));
    if (b) {
      enregistrerFichier(b, d.fichierNom ?? `${d.numero}.pdf`);
    }
  }

  private async maj(appel: () => Promise<DemandeVue>, texte: (r: DemandeVue) => string): Promise<void> {
    this.message.set(null);
    const r = await this.action.executer(appel);
    if (r) {
      this.demandes.set(this.demandes().map((x) => (x.id === r.id ? r : x)));
      this.message.set(texte(r));
    }
  }
}
