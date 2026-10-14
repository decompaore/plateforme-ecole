import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { dateLocale } from '../../core/outils';
import {
  Canal,
  LIBELLE_QUALITE,
  LIBELLE_SERVICE,
  PassageVue,
  PersonneVue,
  Qualite,
  Service,
  SERVICES,
} from '../modeles-secretariat';
import { SecretariatApi } from '../secretariat-api.service';
import { SecretariatNavComponent } from '../secretariat-nav.component';

/** Heure locale « 09h15 » d'un instant ISO. */
export function heure(iso: string): string {
  const d = new Date(iso);
  return `${String(d.getHours()).padStart(2, '0')}h${String(d.getMinutes()).padStart(2, '0')}`;
}

/**
 * Accueil et permanence téléphonique (v0.47.1) : registre des visites (parents, visiteurs, partenaires) orientées
 * vers le service compétent et des appels reçus ; un message pour une personne du personnel lui est transmis dans
 * sa messagerie, « URGENT » si besoin.
 */
@Component({
  selector: 'app-accueil-public',
  imports: [FormsModule, SecretariatNavComponent],
  template: `
    <div class="page large">
      <h1>Accueil et téléphone</h1>
      <app-secretariat-nav />
      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      <section class="carte">
        <form (ngSubmit)="enregistrer()">
          <div class="bascule" role="group" aria-label="Visite ou appel">
            <button type="button" [class.actif]="fCanal() === 'VISITE'" (click)="fCanal.set('VISITE')">Visite</button>
            <button type="button" [class.actif]="fCanal() === 'APPEL'" (click)="fCanal.set('APPEL')">Appel téléphonique</button>
          </div>
          <div class="grille-champs">
            <div class="champ"><label for="aNom">Nom</label><input id="aNom" name="aNom" maxlength="150" [(ngModel)]="fNom" /></div>
            <div class="champ"><label for="aTel">Téléphone</label><input id="aTel" name="aTel" type="tel" maxlength="20" [(ngModel)]="fTelephone" /></div>
            <div class="champ">
              <label for="aQualite">Qualité</label>
              <select id="aQualite" name="aQualite" [(ngModel)]="fQualite">
                @for (q of qualites; track q) { <option [value]="q">{{ libelleQualite[q] }}</option> }
              </select>
            </div>
            <div class="champ"><label for="aObjet">Objet</label><input id="aObjet" name="aObjet" maxlength="300" [(ngModel)]="fObjet" /></div>
            <div class="champ">
              <label for="aService">Orienté vers</label>
              <select id="aService" name="aService" [(ngModel)]="fService">
                <option value="">—</option>
                @for (s of services; track s) { <option [value]="s">{{ libelleService[s] }}</option> }
              </select>
            </div>
            <div class="champ">
              <label for="aPersonne">Personne demandée</label>
              <select id="aPersonne" name="aPersonne" [(ngModel)]="fPersonne">
                <option value="">—</option>
                @for (p of personnel(); track p.utilisateurId) { <option [value]="p.utilisateurId">{{ p.nom }} {{ p.prenoms }} ({{ p.fonctions }})</option> }
              </select>
            </div>
          </div>
          <div class="champ">
            <label for="aMessage">Message laissé</label>
            <textarea id="aMessage" name="aMessage" rows="2" maxlength="1000" [(ngModel)]="fMessage"></textarea>
          </div>
          <label class="case"><input type="checkbox" name="aUrgent" [(ngModel)]="fUrgent" /> Urgent</label>
          @if (fPersonne()) {
            <label class="case"><input type="checkbox" name="aTransmettre" [(ngModel)]="fTransmettre" /> Transmettre le message dans sa messagerie</label>
          }
          <button type="submit" class="bouton" [disabled]="action.enCours() || !fNom().trim() || !fObjet().trim()">Enregistrer</button>
        </form>
      </section>

      <section class="carte">
        <div class="entete-section">
          <h2>Registre du jour</h2>
          <input type="date" aria-label="Jour" [max]="aujourdhui" [ngModel]="jour()" (ngModelChange)="changerJour($event)" style="width:auto" />
        </div>
        <div class="tableau-defilant">
          <table class="tableau">
            <thead><tr><th>Heure</th><th></th><th>Nom</th><th>Objet</th><th>Orientation</th><th></th></tr></thead>
            <tbody>
              @for (p of passages(); track p.id) {
                <tr>
                  <td>{{ heure(p.arrivee) }}@if (p.depart) { – {{ heure(p.depart) }} }</td>
                  <td><span class="pastille">{{ p.canal === 'APPEL' ? 'appel' : 'visite' }}</span>@if (p.urgent) { <span class="pastille absent">urgent</span> }</td>
                  <td>{{ p.nom }}<br /><span class="doux">{{ libelleQualite[p.qualite] }}@if (p.telephone) { · {{ p.telephone }} }</span></td>
                  <td>{{ p.objet }}@if (p.message) { <br /><span class="doux">« {{ p.message }} »</span> }</td>
                  <td>{{ p.service ? libelleService[p.service] : '' }}@if (p.personne) { <br />{{ p.personne }} }
                    @if (p.transmisLe) { <br /><span class="doux">message transmis à {{ heure(p.transmisLe) }}</span> }</td>
                  <td class="actions-ligne">
                    @if (p.personneId && !p.transmisLe) { <button type="button" class="bouton petit" (click)="transmettre(p)">Transmettre</button> }
                    @if (p.canal === 'VISITE' && !p.depart) { <button type="button" class="bouton secondaire petit" (click)="depart(p)">Départ</button> }
                  </td>
                </tr>
              } @empty {
                <tr><td colspan="6" class="doux">Personne ce jour-là.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    </div>
  `,
})
export class AccueilPublicPage implements OnInit {
  private readonly api = inject(SecretariatApi);

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly heure = heure;
  protected readonly qualites = Object.keys(LIBELLE_QUALITE) as Qualite[];
  protected readonly libelleQualite = LIBELLE_QUALITE;
  protected readonly services = SERVICES;
  protected readonly libelleService = LIBELLE_SERVICE;
  protected readonly aujourdhui = dateLocale();

  protected readonly jour = signal(dateLocale());
  protected readonly passages = signal<PassageVue[]>([]);
  protected readonly personnel = signal<PersonneVue[]>([]);
  protected readonly fCanal = signal<Canal>('VISITE');
  protected readonly fNom = signal('');
  protected readonly fTelephone = signal('');
  protected readonly fQualite = signal<Qualite>('PARENT');
  protected readonly fObjet = signal('');
  protected readonly fService = signal('');
  protected readonly fPersonne = signal('');
  protected readonly fMessage = signal('');
  protected readonly fUrgent = signal(false);
  protected readonly fTransmettre = signal(true);

  async ngOnInit(): Promise<void> {
    const r = await this.action.executer(() => Promise.all([this.api.accueil(this.jour()), this.api.personnel()]));
    if (r) {
      this.passages.set(r[0]);
      this.personnel.set(r[1]);
    }
  }

  protected async changerJour(j: string): Promise<void> {
    if (!j) {
      return;
    }
    this.jour.set(j);
    const l = await this.action.executer(() => this.api.accueil(j));
    if (l) {
      this.passages.set(l);
    }
  }

  protected async enregistrer(): Promise<void> {
    this.message.set(null);
    const personne = this.fPersonne() || null;
    const p = await this.action.executer(() =>
      this.api.enregistrerPassage({
        canal: this.fCanal(),
        nom: this.fNom().trim(),
        telephone: this.fTelephone().trim() || null,
        qualite: this.fQualite(),
        objet: this.fObjet().trim(),
        service: (this.fService() || null) as Service | null,
        personneId: personne,
        message: this.fMessage().trim() || null,
        urgent: this.fUrgent(),
        transmettre: !!personne && this.fTransmettre(),
      }),
    );
    if (p) {
      if (this.jour() === this.aujourdhui) {
        this.passages.set([p, ...this.passages()]);
      }
      this.message.set(p.transmisLe ? `Enregistré ; message transmis à ${p.personne}.` : 'Passage enregistré.');
      for (const s of [this.fNom, this.fTelephone, this.fObjet, this.fService, this.fPersonne, this.fMessage]) {
        s.set('');
      }
      this.fUrgent.set(false);
    }
  }

  protected async transmettre(p: PassageVue): Promise<void> {
    const r = await this.action.executer(() => this.api.transmettrePassage(p.id));
    if (r) {
      this.remplacer(r);
      this.message.set(`Message transmis à ${r.personne}.`);
    }
  }

  protected async depart(p: PassageVue): Promise<void> {
    const r = await this.action.executer(() => this.api.depart(p.id));
    if (r) {
      this.remplacer(r);
    }
  }

  private remplacer(p: PassageVue): void {
    this.passages.set(this.passages().map((x) => (x.id === p.id ? p : x)));
  }
}
