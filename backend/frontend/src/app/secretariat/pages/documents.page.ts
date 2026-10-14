import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { dateCourte, dateLocale } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import { DocumentVue, LIBELLE_TYPE_DOCUMENT, PublicAffichage, TYPES_DOCUMENT, TypeDocument } from '../modeles-secretariat';
import { SecretariatApi } from '../secretariat-api.service';
import { SecretariatNavComponent } from '../secretariat-nav.component';

/** Modèles de départ, à compléter avant la signature. */
export const MODELES: Partial<Record<TypeDocument, string>> = {
  NOTE_SERVICE:
    "Il est porté à la connaissance de l'ensemble du personnel que …\n\nLa présente note de service prend effet à compter de sa date de signature.",
  COMMUNIQUE: "Le Chef d'établissement informe les parents d'élèves et le public que …",
  ATTESTATION:
    "Je soussigné(e), Chef d'établissement, atteste que M./Mme …, matricule …, exerce en qualité de … dans notre établissement depuis le …\n\nEn foi de quoi, la présente attestation lui est délivrée pour servir et valoir ce que de droit.",
  CONVOCATION: 'M./Mme … est convoqué(e) le … à … heures, en salle …, pour …\n\nLa présence est obligatoire.',
  DECISION: "Le Chef d'établissement,\n\nVu …\nVu …\n\nDÉCIDE :\n\nArticle 1 : …\nArticle 2 : La présente décision prend effet à compter de sa date de signature.",
};

/**
 * Documents administratifs (v0.47.1) : le secrétariat rédige notes de service, décisions, communiqués,
 * attestations, convocations et lettres ; le chef d'établissement signe (le numéro est alors attribué) ; un document
 * signé s'affiche au tableau d'affichage, pour le personnel ou pour tous.
 */
@Component({
  selector: 'app-documents-administratifs',
  imports: [FormsModule, SecretariatNavComponent],
  template: `
    <div class="page large">
      <h1>Documents administratifs</h1>
      <app-secretariat-nav />
      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      <section class="carte">
        <div class="entete-section">
          <h2>{{ edite() ? 'Modifier le brouillon' : 'Rédiger un document' }}</h2>
          @if (edite()) { <button type="button" class="bouton discret petit" (click)="vider()">Nouveau document</button> }
        </div>
        <form (ngSubmit)="enregistrer()">
          <div class="grille-champs">
            <div class="champ">
              <label for="dType">Type</label>
              <select id="dType" name="dType" [ngModel]="fType()" (ngModelChange)="changerType($event)">
                @for (t of types; track t) { <option [value]="t">{{ libelleType[t] }}</option> }
              </select>
            </div>
            <div class="champ">
              <label for="dTitre">Objet</label>
              <input id="dTitre" name="dTitre" maxlength="200" [(ngModel)]="fTitre" />
            </div>
            <div class="champ">
              <label for="dDestinataires">Destinataires</label>
              <input id="dDestinataires" name="dDestinataires" maxlength="300" placeholder="Tout le personnel, parents d'élèves…" [(ngModel)]="fDestinataires" />
            </div>
            <div class="champ">
              <label for="dSignataire">Signataire</label>
              <input id="dSignataire" name="dSignataire" maxlength="120" placeholder="Le Chef d'établissement" [(ngModel)]="fSignataire" />
            </div>
          </div>
          <div class="champ">
            <label for="dCorps">Texte</label>
            <textarea id="dCorps" name="dCorps" rows="10" maxlength="20000" [(ngModel)]="fCorps"></textarea>
          </div>
          <button type="submit" class="bouton" [disabled]="action.enCours() || !fTitre().trim() || !fCorps().trim()">
            {{ edite() ? 'Enregistrer le brouillon' : 'Créer le brouillon' }}
          </button>
        </form>
      </section>

      <section class="carte">
        <h2>Documents</h2>
        <ul class="liste">
          @for (d of documents(); track d.id) {
            <li class="document">
              <div>
                <strong>{{ libelleType[d.type] }}</strong> @if (d.numero) { n° {{ d.numero }} } · {{ d.titre }}
                <br />
                <span class="pastille" [class.present]="d.statut === 'SIGNE'">{{ d.statut === 'BROUILLON' ? 'brouillon' : d.statut === 'SIGNE' ? 'signé' : 'annulé' }}</span>
                @if (d.signePar) { <span class="doux"> par {{ d.signePar }}</span> }
                @if (d.affichage) {
                  <span class="pastille present">affiché ({{ d.affichage === 'TOUS' ? 'tous' : 'personnel' }}) jusqu'au {{ dateCourte(d.afficheAu!) }}</span>
                }
              </div>
              <div class="actions-ligne">
                <button type="button" class="bouton discret petit" (click)="pdf(d)">{{ d.statut === 'BROUILLON' ? 'Aperçu' : 'PDF' }}</button>
                @if (d.statut === 'BROUILLON') {
                  <button type="button" class="bouton secondaire petit" (click)="editer(d)">Modifier</button>
                  <button type="button" class="bouton discret petit" (click)="supprimer(d)">Supprimer</button>
                  @if (chef()) { <button type="button" class="bouton petit" (click)="signer(d)">Signer</button> }
                }
                @if (d.statut === 'SIGNE') {
                  @if (!d.affichage) {
                    <select [attr.aria-label]="'Affichage de ' + d.titre" [value]="publicPour(d.id)" (change)="choixPublic(d.id, $any($event.target).value)" style="width:auto">
                      <option value="PERSONNEL">Personnel</option>
                      <option value="TOUS">Tous (parents compris)</option>
                    </select>
                    <input type="date" [attr.aria-label]="'Affiché jusqu’au, ' + d.titre" [min]="aujourdhui" [value]="finPour(d.id)" (change)="choixFin(d.id, $any($event.target).value)" style="width:auto" />
                    <button type="button" class="bouton petit" (click)="afficher(d)">Afficher</button>
                  } @else {
                    <button type="button" class="bouton secondaire petit" (click)="retirer(d)">Retirer de l'affichage</button>
                  }
                  @if (chef()) { <button type="button" class="bouton discret petit" (click)="annuler(d)">Annuler</button> }
                }
              </div>
            </li>
          } @empty {
            <li class="doux">Aucun document.</li>
          }
        </ul>
      </section>
    </div>
  `,
  styles: `
    .document {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 0.5rem;
      padding: 0.6rem 0;
    }
    textarea {
      width: 100%;
      font: inherit;
    }
  `,
})
export class DocumentsPage implements OnInit {
  private readonly api = inject(SecretariatApi);
  private readonly session = inject(SessionService);

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly chef = computed(() => this.session.aLeRole('ADMIN_ECOLE'));
  protected readonly types = TYPES_DOCUMENT;
  protected readonly libelleType = LIBELLE_TYPE_DOCUMENT;
  protected readonly dateCourte = dateCourte;
  protected readonly aujourdhui = dateLocale();

  protected readonly documents = signal<DocumentVue[]>([]);
  protected readonly edite = signal<string | null>(null);
  protected readonly fType = signal<TypeDocument>('NOTE_SERVICE');
  protected readonly fTitre = signal('');
  protected readonly fDestinataires = signal('');
  protected readonly fSignataire = signal('');
  protected readonly fCorps = signal(MODELES.NOTE_SERVICE ?? '');
  private readonly publics = signal<Record<string, PublicAffichage>>({});
  private readonly fins = signal<Record<string, string>>({});

  async ngOnInit(): Promise<void> {
    const l = await this.action.executer(() => this.api.documents());
    if (l) {
      this.documents.set(l);
    }
  }

  protected changerType(t: TypeDocument): void {
    const ancien = MODELES[this.fType()] ?? '';
    this.fType.set(t);
    if (!this.fCorps().trim() || this.fCorps() === ancien) {
      this.fCorps.set(MODELES[t] ?? '');
    }
  }

  protected vider(): void {
    this.edite.set(null);
    this.fType.set('NOTE_SERVICE');
    this.fTitre.set('');
    this.fDestinataires.set('');
    this.fSignataire.set('');
    this.fCorps.set(MODELES.NOTE_SERVICE ?? '');
  }

  protected editer(d: DocumentVue): void {
    this.edite.set(d.id);
    this.fType.set(d.type);
    this.fTitre.set(d.titre);
    this.fDestinataires.set(d.destinataires ?? '');
    this.fSignataire.set(d.signataire);
    this.fCorps.set(d.corps);
  }

  protected async enregistrer(): Promise<void> {
    this.message.set(null);
    const s = {
      type: this.fType(),
      titre: this.fTitre().trim(),
      destinataires: this.fDestinataires().trim() || null,
      corps: this.fCorps(),
      signataire: this.fSignataire().trim() || null,
    };
    const id = this.edite();
    const d = await this.action.executer(() => (id ? this.api.modifierDocument(id, s) : this.api.rediger(s)));
    if (d) {
      this.remplacer(d);
      this.message.set(`Brouillon « ${d.titre} » enregistré : à faire signer par le chef d'établissement.`);
      this.vider();
    }
  }

  protected async signer(d: DocumentVue): Promise<void> {
    const r = await this.action.executer(() => this.api.signer(d.id));
    if (r) {
      this.remplacer(r);
      this.message.set(`${LIBELLE_TYPE_DOCUMENT[r.type]} n° ${r.numero} signée.`);
    }
  }

  protected async annuler(d: DocumentVue): Promise<void> {
    if (!window.confirm(`Annuler le document n° ${d.numero} ? Il garde son numéro et quitte l'affichage.`)) {
      return;
    }
    const r = await this.action.executer(() => this.api.annulerDocument(d.id));
    if (r) {
      this.remplacer(r);
    }
  }

  protected async supprimer(d: DocumentVue): Promise<void> {
    if (await this.action.reussit(() => this.api.supprimerDocument(d.id))) {
      this.documents.set(this.documents().filter((x) => x.id !== d.id));
      if (this.edite() === d.id) {
        this.vider();
      }
    }
  }

  protected publicPour(id: string): PublicAffichage {
    return this.publics()[id] ?? 'PERSONNEL';
  }

  protected finPour(id: string): string {
    return this.fins()[id] ?? '';
  }

  protected choixPublic(id: string, p: PublicAffichage): void {
    this.publics.set({ ...this.publics(), [id]: p });
  }

  protected choixFin(id: string, fin: string): void {
    this.fins.set({ ...this.fins(), [id]: fin });
  }

  protected async afficher(d: DocumentVue): Promise<void> {
    const au = this.finPour(d.id);
    if (!au) {
      this.action.erreur.set("Indiquez jusqu'à quand le document reste affiché.");
      return;
    }
    const r = await this.action.executer(() => this.api.afficher(d.id, this.publicPour(d.id), null, au));
    if (r) {
      this.remplacer(r);
      this.message.set(`« ${r.titre} » est au tableau d'affichage jusqu'au ${dateCourte(au)}.`);
    }
  }

  protected async retirer(d: DocumentVue): Promise<void> {
    const r = await this.action.executer(() => this.api.afficher(d.id, null, null, null));
    if (r) {
      this.remplacer(r);
    }
  }

  protected async pdf(d: DocumentVue): Promise<void> {
    const b = await this.action.executer(() => this.api.pdfDocument(d.id));
    if (b) {
      enregistrerFichier(b, `${(d.numero ?? 'projet').replace(/\//g, '-')}-${LIBELLE_TYPE_DOCUMENT[d.type].toLowerCase().replace(/\s+/g, '-')}.pdf`);
    }
  }

  private remplacer(d: DocumentVue): void {
    const l = this.documents();
    this.documents.set(l.some((x) => x.id === d.id) ? l.map((x) => (x.id === d.id ? d : x)) : [d, ...l]);
  }
}
