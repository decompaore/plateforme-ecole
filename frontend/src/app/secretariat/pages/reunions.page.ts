import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { dateCourte, dateLocale } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import { GESTION_SECRETARIAT, Instance, INSTANCES, LIBELLE_INSTANCE, ReunionVue, SaisieReunion } from '../modeles-secretariat';
import { SecretariatApi } from '../secretariat-api.service';
import { SecretariatNavComponent } from '../secretariat-nav.component';

/**
 * Réunions statutaires (v0.47.1) : le secrétariat prépare (ordre du jour, participants) et rédige le compte rendu
 * du conseil d'établissement, des conseils de classe, de discipline, d'enseignement, du conseil de l'école, de
 * l'assemblée de l'APE ; le chef d'établissement le valide. Le censeur et le chef des travaux le lisent.
 */
@Component({
  selector: 'app-reunions',
  imports: [FormsModule, SecretariatNavComponent],
  template: `
    <div class="page large">
      <h1>Réunions et comptes rendus</h1>
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
            <h2>{{ edite() ? 'Compléter le compte rendu' : 'Préparer une réunion' }}</h2>
            @if (edite()) { <button type="button" class="bouton discret petit" (click)="vider()">Nouvelle réunion</button> }
          </div>
          <form (ngSubmit)="enregistrer()">
            <div class="grille-champs">
              <div class="champ">
                <label for="rInstance">Instance</label>
                <select id="rInstance" name="rInstance" [(ngModel)]="fInstance">
                  @for (i of instances; track i) { <option [value]="i">{{ libelleInstance[i] }}</option> }
                </select>
              </div>
              <div class="champ"><label for="rTitre">Titre <span class="doux">(facultatif)</span></label><input id="rTitre" name="rTitre" maxlength="200" [(ngModel)]="fTitre" /></div>
              <div class="champ"><label for="rDate">Date</label><input id="rDate" name="rDate" type="date" [(ngModel)]="fDate" /></div>
              <div class="champ"><label for="rHeure">Heure</label><input id="rHeure" name="rHeure" type="time" [(ngModel)]="fHeure" /></div>
              <div class="champ"><label for="rLieu">Lieu</label><input id="rLieu" name="rLieu" maxlength="150" [(ngModel)]="fLieu" /></div>
              <div class="champ"><label for="rPresident">Président de séance</label><input id="rPresident" name="rPresident" maxlength="150" [(ngModel)]="fPresident" /></div>
              <div class="champ"><label for="rSecretaire">Secrétaire de séance</label><input id="rSecretaire" name="rSecretaire" maxlength="150" [(ngModel)]="fSecretaire" /></div>
            </div>
            <div class="champ"><label for="rParticipants">Participants</label><textarea id="rParticipants" name="rParticipants" rows="2" maxlength="4000" [(ngModel)]="fParticipants"></textarea></div>
            <div class="champ"><label for="rOrdre">Ordre du jour</label><textarea id="rOrdre" name="rOrdre" rows="3" maxlength="4000" [(ngModel)]="fOrdre"></textarea></div>
            <div class="champ"><label for="rCompteRendu">Compte rendu</label><textarea id="rCompteRendu" name="rCompteRendu" rows="8" maxlength="20000" [(ngModel)]="fCompteRendu"></textarea></div>
            <button type="submit" class="bouton" [disabled]="action.enCours() || !fDate()">Enregistrer</button>
          </form>
        </section>
      }

      <section class="carte">
        <h2>Réunions</h2>
        <ul class="liste">
          @for (r of reunions(); track r.id) {
            <li class="ligne-lien">
              <span>
                <strong>{{ r.titre }}</strong> · {{ dateCourte(r.date) }}@if (r.lieu) { · {{ r.lieu }} }
                <br />
                <span class="pastille" [class.present]="r.statut === 'VALIDE'">{{ r.statut === 'VALIDE' ? 'validé' : (r.compteRendu ? 'compte rendu à valider' : 'à rédiger') }}</span>
              </span>
              <span class="actions-ligne">
                <button type="button" class="bouton discret petit" (click)="pdf(r)">PDF</button>
                @if (r.statut === 'BROUILLON' && gestion()) {
                  <button type="button" class="bouton secondaire petit" (click)="editer(r)">Rédiger</button>
                }
                @if (r.statut === 'BROUILLON' && chef() && r.compteRendu) {
                  <button type="button" class="bouton petit" (click)="valider(r)">Valider</button>
                }
              </span>
            </li>
          } @empty {
            <li class="doux">Aucune réunion.</li>
          }
        </ul>
      </section>
    </div>
  `,
  styles: `
    textarea {
      width: 100%;
      font: inherit;
    }
  `,
})
export class ReunionsPage implements OnInit {
  private readonly api = inject(SecretariatApi);
  private readonly session = inject(SessionService);

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly gestion = computed(() => this.session.aLeRole(...GESTION_SECRETARIAT));
  protected readonly chef = computed(() => this.session.aLeRole('ADMIN_ECOLE'));
  protected readonly instances = INSTANCES;
  protected readonly libelleInstance = LIBELLE_INSTANCE;
  protected readonly dateCourte = dateCourte;

  protected readonly reunions = signal<ReunionVue[]>([]);
  protected readonly edite = signal<string | null>(null);
  protected readonly fInstance = signal<Instance>('CONSEIL_ETABLISSEMENT');
  protected readonly fTitre = signal('');
  protected readonly fDate = signal(dateLocale());
  protected readonly fHeure = signal('');
  protected readonly fLieu = signal('');
  protected readonly fPresident = signal('');
  protected readonly fSecretaire = signal('');
  protected readonly fParticipants = signal('');
  protected readonly fOrdre = signal('');
  protected readonly fCompteRendu = signal('');

  async ngOnInit(): Promise<void> {
    const l = await this.action.executer(() => this.api.reunions());
    if (l) {
      this.reunions.set(l);
    }
  }

  protected vider(): void {
    this.edite.set(null);
    for (const s of [this.fTitre, this.fHeure, this.fLieu, this.fPresident, this.fSecretaire, this.fParticipants, this.fOrdre, this.fCompteRendu]) {
      s.set('');
    }
    this.fDate.set(dateLocale());
  }

  protected editer(r: ReunionVue): void {
    this.edite.set(r.id);
    this.fInstance.set(r.instance);
    this.fTitre.set(r.titre);
    this.fDate.set(r.date);
    this.fHeure.set(r.heure?.slice(0, 5) ?? '');
    this.fLieu.set(r.lieu ?? '');
    this.fPresident.set(r.president ?? '');
    this.fSecretaire.set(r.secretaireSeance ?? '');
    this.fParticipants.set(r.participants ?? '');
    this.fOrdre.set(r.ordreDuJour ?? '');
    this.fCompteRendu.set(r.compteRendu ?? '');
  }

  protected async enregistrer(): Promise<void> {
    this.message.set(null);
    const v = (s: string) => s.trim() || null;
    const s: SaisieReunion = {
      instance: this.fInstance(),
      titre: v(this.fTitre()),
      date: this.fDate(),
      heure: this.fHeure() || null,
      lieu: v(this.fLieu()),
      president: v(this.fPresident()),
      secretaireSeance: v(this.fSecretaire()),
      participants: v(this.fParticipants()),
      ordreDuJour: v(this.fOrdre()),
      compteRendu: v(this.fCompteRendu()),
    };
    const id = this.edite();
    const r = await this.action.executer(() => (id ? this.api.modifierReunion(id, s) : this.api.creerReunion(s)));
    if (r) {
      this.remplacer(r);
      this.message.set(`« ${r.titre} » enregistré.`);
      this.vider();
    }
  }

  protected async valider(r: ReunionVue): Promise<void> {
    const v = await this.action.executer(() => this.api.validerReunion(r.id));
    if (v) {
      this.remplacer(v);
      this.message.set(`Compte rendu « ${v.titre} » validé.`);
    }
  }

  protected async pdf(r: ReunionVue): Promise<void> {
    const b = await this.action.executer(() => this.api.pdfReunion(r.id));
    if (b) {
      enregistrerFichier(b, `compte-rendu-${r.date}.pdf`);
    }
  }

  private remplacer(r: ReunionVue): void {
    const l = this.reunions();
    this.reunions.set(l.some((x) => x.id === r.id) ? l.map((x) => (x.id === r.id ? r : x)) : [r, ...l]);
  }
}
