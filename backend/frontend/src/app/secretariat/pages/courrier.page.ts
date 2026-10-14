import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { dateCourte, dateLocale } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import {
  CATEGORIES_COURRIER,
  CategorieCourrier,
  CourrierVue,
  GESTION_SECRETARIAT,
  LIBELLE_CATEGORIE,
  LIBELLE_SERVICE,
  LIBELLE_STATUT_COURRIER,
  Sens,
  Service,
  SERVICES,
} from '../modeles-secretariat';
import { SecretariatApi } from '../secretariat-api.service';
import { SecretariatNavComponent } from '../secretariat-nav.component';

/**
 * Courrier (v0.47.1) : le secrétariat enregistre le courrier arrivé et le courrier départ (numéros A-2026-0001,
 * D-2026-0001), joint la pièce scannée, transmet au service, suit jusqu'au classement et imprime le registre. Un
 * service (censorat, intendance, CPE…) voit le courrier qui lui est transmis et le marque traité.
 */
@Component({
  selector: 'app-courrier',
  imports: [FormsModule, SecretariatNavComponent],
  template: `
    <div class="page large">
      <h1>Courrier</h1>
      <app-secretariat-nav />
      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      @if (gestion()) {
        <section class="carte">
          <div class="entete-section">
            <h2>Enregistrer un courrier</h2>
            <span class="actions-ligne">
              <button type="button" class="bouton secondaire petit" (click)="registre('xlsx')">Registre {{ annee() }} (Excel)</button>
              <button type="button" class="bouton secondaire petit" (click)="registre('pdf')">… (PDF)</button>
            </span>
          </div>
          <form (ngSubmit)="enregistrer()">
            <div class="bascule" role="group" aria-label="Sens du courrier">
              <button type="button" [class.actif]="fSens() === 'ARRIVEE'" (click)="fSens.set('ARRIVEE')">Arrivée</button>
              <button type="button" [class.actif]="fSens() === 'DEPART'" (click)="fSens.set('DEPART')">Départ</button>
            </div>
            <div class="grille-champs">
              <div class="champ">
                <label for="cCorrespondant">{{ fSens() === 'ARRIVEE' ? 'Expéditeur' : 'Destinataire' }}</label>
                <input id="cCorrespondant" name="cCorrespondant" maxlength="200" [(ngModel)]="fCorrespondant" />
              </div>
              <div class="champ">
                <label for="cObjet">Objet</label>
                <input id="cObjet" name="cObjet" maxlength="300" [(ngModel)]="fObjet" />
              </div>
              <div class="champ">
                <label for="cCategorie">Catégorie</label>
                <select id="cCategorie" name="cCategorie" [(ngModel)]="fCategorie">
                  @for (c of categories; track c) { <option [value]="c">{{ libelleCategorie[c] }}</option> }
                </select>
              </div>
              <div class="champ">
                <label for="cDate">{{ fSens() === 'ARRIVEE' ? 'Reçu le' : 'Expédié le' }}</label>
                <input id="cDate" name="cDate" type="date" [max]="aujourdhui" [(ngModel)]="fDate" />
              </div>
              <div class="champ">
                <label for="cReference">Référence <span class="doux">(n° de la lettre)</span></label>
                <input id="cReference" name="cReference" maxlength="80" [(ngModel)]="fReference" />
              </div>
              <div class="champ">
                <label for="cDateDocument">Date de la lettre</label>
                <input id="cDateDocument" name="cDateDocument" type="date" [(ngModel)]="fDateDocument" />
              </div>
              @if (fSens() === 'ARRIVEE') {
                <div class="champ">
                  <label for="cService">Service destinataire</label>
                  <select id="cService" name="cService" [(ngModel)]="fService">
                    <option value="">— à préciser —</option>
                    @for (s of services; track s) { <option [value]="s">{{ libelleService[s] }}</option> }
                  </select>
                </div>
              } @else {
                <div class="champ">
                  <label for="cReponse">En réponse à</label>
                  <select id="cReponse" name="cReponse" [(ngModel)]="fReponse">
                    <option value="">—</option>
                    @for (a of arrivees(); track a.id) { <option [value]="a.id">{{ a.numero }} · {{ a.objet }}</option> }
                  </select>
                </div>
              }
              <div class="champ">
                <label for="cCote">Cote de classement</label>
                <input id="cCote" name="cCote" maxlength="40" [(ngModel)]="fCote" />
              </div>
              <div class="champ">
                <label for="cPiece">Pièce scannée <span class="doux">(PDF, JPEG, PNG)</span></label>
                <input id="cPiece" type="file" accept=".pdf,image/jpeg,image/png" (change)="choisir($event)" />
              </div>
            </div>
            <button type="submit" class="bouton" [disabled]="action.enCours() || !fCorrespondant().trim() || !fObjet().trim()">Enregistrer</button>
          </form>
        </section>
      }

      <section class="carte">
        <div class="entete-section">
          <h2>{{ gestion() ? 'Registre' : 'Courrier transmis à votre service' }}</h2>
          <form class="actions-ligne" (ngSubmit)="charger()">
            @if (gestion()) {
              <select name="filtreSens" aria-label="Sens" [(ngModel)]="filtreSens" (ngModelChange)="charger()" style="width:auto">
                <option value="">Arrivée et départ</option>
                <option value="ARRIVEE">Arrivée</option>
                <option value="DEPART">Départ</option>
              </select>
            }
            <input name="q" type="search" placeholder="N°, objet, correspondant" [(ngModel)]="q" style="width:auto" />
            <button type="submit" class="bouton secondaire petit">Chercher</button>
          </form>
        </div>
        <div class="tableau-defilant">
          <table class="tableau">
            <thead><tr><th>N°</th><th>Date</th><th>Correspondant</th><th>Objet</th><th>Service</th><th>Suivi</th><th></th></tr></thead>
            <tbody>
              @for (c of courriers(); track c.id) {
                <tr>
                  <td><strong>{{ c.numero }}</strong></td>
                  <td>{{ dateCourte(c.dateCourrier) }}</td>
                  <td>{{ c.correspondant }}@if (c.reference) { <br /><span class="doux">réf. {{ c.reference }}</span> }</td>
                  <td>{{ c.objet }}@if (c.reponseANumero) { <br /><span class="doux">réponse au {{ c.reponseANumero }}</span> }</td>
                  <td>{{ c.service ? libelleService[c.service] : (c.sens === 'DEPART' ? libelleCategorie[c.categorie] : '—') }}</td>
                  <td>
                    <span class="pastille" [class.present]="c.statut === 'TRAITE' || c.statut === 'CLASSE'">{{ libelleStatut[c.statut] }}</span>
                    @if (c.suivi) { <br /><span class="doux">{{ c.suivi }}</span> }
                  </td>
                  <td class="actions-ligne">
                    @if (c.fichierNom) {
                      <button type="button" class="bouton discret petit" (click)="piece(c)">Pièce</button>
                    }
                    @if (gestion()) {
                      @if (c.sens === 'ARRIVEE' && (c.statut === 'ENREGISTRE' || c.statut === 'TRANSMIS')) {
                        <select [attr.aria-label]="'Service pour ' + c.numero" [value]="c.service ?? ''" (change)="choixService.set({ id: c.id, service: $any($event.target).value })" style="width:auto">
                          <option value="">Service…</option>
                          @for (s of services; track s) { <option [value]="s">{{ libelleService[s] }}</option> }
                        </select>
                        <button type="button" class="bouton petit" (click)="transmettre(c)">Transmettre</button>
                      }
                      @if (c.statut !== 'CLASSE') {
                        <button type="button" class="bouton secondaire petit" (click)="suivre(c, 'CLASSE')">Classer</button>
                      }
                    } @else if (c.statut === 'TRANSMIS') {
                      <button type="button" class="bouton petit" (click)="suivre(c, 'TRAITE')">Marquer traité</button>
                    }
                  </td>
                </tr>
              } @empty {
                <tr><td colspan="7" class="doux">Aucun courrier.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    </div>
  `,
})
export class CourrierPage implements OnInit {
  private readonly api = inject(SecretariatApi);
  private readonly session = inject(SessionService);

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly gestion = computed(() => this.session.aLeRole(...GESTION_SECRETARIAT));
  protected readonly dateCourte = dateCourte;
  protected readonly categories = CATEGORIES_COURRIER;
  protected readonly services = SERVICES;
  protected readonly libelleCategorie = LIBELLE_CATEGORIE;
  protected readonly libelleService = LIBELLE_SERVICE;
  protected readonly libelleStatut = LIBELLE_STATUT_COURRIER;
  protected readonly aujourdhui = dateLocale();
  protected readonly annee = signal(new Date().getFullYear());

  protected readonly courriers = signal<CourrierVue[]>([]);
  protected readonly arrivees = computed(() => this.courriers().filter((c) => c.sens === 'ARRIVEE'));
  protected readonly filtreSens = signal('');
  protected readonly q = signal('');
  protected readonly choixService = signal<{ id: string; service: string } | null>(null);

  protected readonly fSens = signal<Sens>('ARRIVEE');
  protected readonly fCorrespondant = signal('');
  protected readonly fObjet = signal('');
  protected readonly fCategorie = signal<CategorieCourrier>('DIRECTION_PROVINCIALE');
  protected readonly fDate = signal(dateLocale());
  protected readonly fReference = signal('');
  protected readonly fDateDocument = signal('');
  protected readonly fService = signal('');
  protected readonly fReponse = signal('');
  protected readonly fCote = signal('');
  private piece_: File | null = null;

  async ngOnInit(): Promise<void> {
    await this.charger();
  }

  protected async charger(): Promise<void> {
    const l = await this.action.executer(() => this.api.courriers(this.filtreSens() || null, null, this.q()));
    if (l) {
      this.courriers.set(l);
    }
  }

  protected choisir(e: Event): void {
    this.piece_ = (e.target as HTMLInputElement).files?.[0] ?? null;
  }

  protected async enregistrer(): Promise<void> {
    this.message.set(null);
    const arrivee = this.fSens() === 'ARRIVEE';
    const c = await this.action.executer(() =>
      this.api.enregistrerCourrier({
        sens: this.fSens(),
        dateCourrier: this.fDate() || null,
        dateDocument: this.fDateDocument() || null,
        reference: this.fReference().trim() || null,
        correspondant: this.fCorrespondant().trim(),
        categorie: this.fCategorie(),
        objet: this.fObjet().trim(),
        service: arrivee ? ((this.fService() || null) as Service | null) : null,
        cote: this.fCote().trim() || null,
        reponseA: arrivee ? null : this.fReponse() || null,
      }),
    );
    if (!c) {
      return;
    }
    let fin = '';
    if (this.piece_) {
      const f = this.piece_;
      fin = (await this.action.executer(() => this.api.joindreCourrier(c.id, f))) ? ', pièce jointe' : ', pièce non jointe (voir le message)';
    }
    this.message.set(`Courrier ${c.numero} enregistré${fin}.`);
    for (const s of [this.fCorrespondant, this.fObjet, this.fReference, this.fDateDocument, this.fCote, this.fReponse]) {
      s.set('');
    }
    this.piece_ = null;
    await this.charger();
  }

  protected async transmettre(c: CourrierVue): Promise<void> {
    const choix = this.choixService();
    const service = (choix?.id === c.id ? choix.service : c.service) as Service | null;
    if (!service) {
      this.action.erreur.set('Choisissez le service auquel transmettre le courrier.');
      return;
    }
    const r = await this.action.executer(() => this.api.suivreCourrier(c.id, 'TRANSMIS', service, null));
    if (r) {
      this.remplacer(r);
      this.message.set(`${r.numero} transmis : ${LIBELLE_SERVICE[service]}.`);
    }
  }

  protected async suivre(c: CourrierVue, statut: 'TRAITE' | 'CLASSE'): Promise<void> {
    const r = await this.action.executer(() => this.api.suivreCourrier(c.id, statut, null, null));
    if (r) {
      this.remplacer(r);
      this.message.set(`${r.numero} : ${LIBELLE_STATUT_COURRIER[r.statut]}.`);
    }
  }

  protected async piece(c: CourrierVue): Promise<void> {
    const b = await this.action.executer(() => this.api.pieceCourrier(c.id));
    if (b) {
      enregistrerFichier(b, c.fichierNom ?? `${c.numero}.pdf`);
    }
  }

  protected async registre(format: 'xlsx' | 'pdf'): Promise<void> {
    const b = await this.action.executer(() => this.api.registreCourrier(this.annee(), format));
    if (b) {
      enregistrerFichier(b, `registre-courrier-${this.annee()}.${format}`);
    }
  }

  private remplacer(r: CourrierVue): void {
    this.courriers.set(this.courriers().map((x) => (x.id === r.id ? r : x)));
  }
}
