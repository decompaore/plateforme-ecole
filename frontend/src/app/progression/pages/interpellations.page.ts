import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { dateCourte, dateHeureCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import {
  CLASSE_STATUT_INTERPELLATION, heures, InterpellationVue, LIBELLE_STATUT_INTERPELLATION, LIBELLE_TYPE_INTERPELLATION,
  PropositionInterpellation, TypeInterpellation,
} from '../modeles-progression';
import { ProgressionApi } from '../progression-api.service';

type Filtre = 'en-cours' | 'repondues' | 'closes' | 'toutes';

/**
 * Interpellation des enseignants (v0.48.1).
 * <ul>
 * <li>Direction (censeur, chef des travaux, chef d'établissement) : rédaction d'un rappel ou d'une demande
 * d'explication à partir du constat chiffré (ouverte depuis le suivi des progressions), suivi, suite donnée, lettre.</li>
 * <li>Enseignant : « Mes interpellations », lecture, réponse, lettre.</li>
 * </ul>
 */
@Component({
  selector: 'app-interpellations',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page large">
      <div class="entete-section">
        <h1>{{ direction ? 'Interpellations des enseignants' : 'Mes interpellations' }}</h1>
        @if (direction) { <a class="bouton secondaire petit" routerLink="/progression/suivi">‹ Suivi des progressions</a> }
      </div>
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      @if (proposition(); as p) {
        <form class="carte sous-formulaire" (ngSubmit)="envoyer(p)">
          <h2>Interpeller {{ p.enseignant }} · {{ p.matiere }} en {{ p.classeCode }}</h2>
          <p class="doux">
            {{ heures(p.retard.heuresPrevuesADate) }} prévues à ce jour · {{ heures(p.retard.heuresRealisees) }} faites
            @if (p.retard.ecart > 0) { · <strong>retard de {{ heures(p.retard.ecart) }}</strong> }
            @if (p.retard.sequencesEnRetard) { · {{ p.retard.sequencesEnRetard }} séquence(s) échue(s) sans séance }
            @if (p.interpellationsEnCours) { · {{ p.interpellationsEnCours }} interpellation(s) déjà en cours }
          </p>
          <div class="bascule" role="radiogroup" aria-label="Type">
            <button type="button" role="radio" [attr.aria-checked]="type() === 'RAPPEL'" [class.actif]="type() === 'RAPPEL'" (click)="choisirType('RAPPEL', p)">Rappel</button>
            <button type="button" role="radio" [attr.aria-checked]="type() === 'DEMANDE_EXPLICATION'" [class.actif]="type() === 'DEMANDE_EXPLICATION'" (click)="choisirType('DEMANDE_EXPLICATION', p)">Demande d'explication</button>
          </div>
          <div class="champ"><label for="iObjet">Objet</label><input id="iObjet" name="iObjet" maxlength="200" required [(ngModel)]="objet" /></div>
          <div class="champ"><label for="iConstat">Constat</label><textarea id="iConstat" name="iConstat" rows="7" maxlength="4000" required [(ngModel)]="constat"></textarea></div>
          <div class="champ"><label for="iDemande">{{ type() === 'RAPPEL' ? 'Rappel' : 'Demande' }}</label><textarea id="iDemande" name="iDemande" rows="3" maxlength="2000" [(ngModel)]="demande"></textarea></div>
          @if (type() === 'DEMANDE_EXPLICATION') {
            <div class="champ"><label for="iDelai">Délai de réponse</label><input id="iDelai" name="iDelai" type="date" required [(ngModel)]="delai" /></div>
            <p class="doux petit-texte">La lettre est numérotée et imprimable avec l'en-tête de l'établissement ; le chef d'établissement en reçoit copie.</p>
          }
          <div class="actions-ligne">
            <button type="submit" class="bouton" [disabled]="action.enCours()">{{ type() === 'RAPPEL' ? 'Envoyer le rappel' : "Envoyer la demande d'explication" }}</button>
            <button type="button" class="bouton secondaire" (click)="annulerRedaction()">Annuler</button>
          </div>
        </form>
      }

      <div class="bascule" role="group" aria-label="Interpellations affichées">
        <button type="button" [class.actif]="filtre() === 'en-cours'" (click)="filtre.set('en-cours')">En cours ({{ nombres().enCours }})</button>
        <button type="button" [class.actif]="filtre() === 'repondues'" (click)="filtre.set('repondues')">Répondues ({{ nombres().repondues }})</button>
        <button type="button" [class.actif]="filtre() === 'closes'" (click)="filtre.set('closes')">Closes ({{ nombres().closes }})</button>
        <button type="button" [class.actif]="filtre() === 'toutes'" (click)="filtre.set('toutes')">Toutes</button>
      </div>

      <section class="carte">
        <ul class="liste">
          @for (i of affichees(); track i.id) {
            <li>
              <button type="button" class="ligne-lien lien-bouton" (click)="ouvrir(i)">
                <span>
                  <strong>{{ libelleType[i.type] }}@if (i.numero) { n° {{ i.numero }} }</strong> · {{ i.matiere }} en {{ i.classeCode }}
                  @if (direction) { · {{ i.enseignant }} }
                  <br />
                  <span class="doux petit-texte">
                    {{ i.objet }} · {{ dateHeure(i.emiseLe) }} · {{ i.qualiteEmetteur }}
                    @if (i.delaiReponse) { · réponse attendue le {{ dateCourte(i.delaiReponse) }} }
                  </span>
                </span>
                <span>
                  @if (i.delaiDepasse) { <span class="pastille absent">Délai dépassé</span> }
                  <span class="pastille {{ classeStatut[i.statut] }}">{{ libelleStatut[i.statut] }}</span>
                </span>
              </button>
            </li>
          } @empty {
            <li class="doux">Aucune interpellation.</li>
          }
        </ul>
      </section>

      @if (detail(); as d) {
        <section class="carte" id="detail">
          <div class="entete-section">
            <h2>{{ libelleType[d.type] }}@if (d.numero) { n° {{ d.numero }} }</h2>
            <span class="pastille {{ classeStatut[d.statut] }}">{{ libelleStatut[d.statut] }}</span>
          </div>
          <p class="doux">
            {{ d.qualiteEmetteur }} ({{ d.emetteur }}) à {{ d.enseignant }} · {{ d.matiere }} en {{ d.classeCode }} · {{ dateHeure(d.emiseLe) }}
            @if (d.lueLe) { · lue le {{ dateHeure(d.lueLe) }} }
          </p>
          <p><strong>Objet :</strong> {{ d.objet }}</p>
          <p class="texte">{{ d.constat }}</p>
          @if (d.demande) { <p class="texte">{{ d.demande }}</p> }
          @if (d.delaiReponse) { <p><strong>Délai de réponse :</strong> {{ dateCourte(d.delaiReponse) }}@if (d.delaiDepasse) { <span class="pastille absent">dépassé</span> }</p> }
          @if (d.reponse) {
            <h3>Réponse de l'enseignant ({{ dateHeure(d.reponduLe!) }})</h3>
            <p class="texte">{{ d.reponse }}</p>
          }
          @if (d.suite) {
            <h3>Suite donnée ({{ dateHeure(d.closeLe!) }}@if (d.closePar) {, {{ d.closePar }} })</h3>
            <p class="texte">{{ d.suite }}</p>
          }
          @if (d.peutRepondre) {
            <form class="sous-formulaire" (ngSubmit)="repondre(d)">
              <div class="champ"><label for="iReponse">Votre réponse</label><textarea id="iReponse" name="iReponse" rows="5" maxlength="4000" required [(ngModel)]="reponse"></textarea></div>
              <button type="submit" class="bouton" [disabled]="action.enCours() || !reponse.trim()">Envoyer ma réponse</button>
            </form>
          }
          @if (d.peutClore) {
            <form class="sous-formulaire" (ngSubmit)="clore(d)">
              <div class="champ"><label for="iSuite">Suite donnée{{ d.type === 'DEMANDE_EXPLICATION' ? '' : ' (facultatif)' }}</label><textarea id="iSuite" name="iSuite" rows="3" maxlength="1000" [(ngModel)]="suite"></textarea></div>
              <button type="submit" class="bouton secondaire" [disabled]="action.enCours()">Clore</button>
            </form>
          }
          <div class="actions-ligne">
            <button type="button" class="bouton secondaire petit" (click)="lettre(d)">Lettre (PDF)</button>
          </div>
        </section>
      }
    </div>
  `,
  styles: `
    .bascule {
      display: inline-flex;
      flex-wrap: wrap;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      padding: 0.2rem;
      margin-bottom: 0.75rem;
      background: var(--surface);
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
    .lien-bouton {
      width: 100%;
      background: none;
      border: none;
      font: inherit;
      text-align: left;
      cursor: pointer;
    }
    .texte {
      white-space: pre-line;
    }
    .petit-texte {
      font-size: 0.85rem;
    }
    textarea {
      width: 100%;
    }
  `,
})
export class InterpellationsPage implements OnInit {
  private readonly api = inject(ProgressionApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly annee = inject(AnneeCourante);
  private readonly session = inject(SessionService);

  /** Direction : censeur, chef des travaux, chef d'établissement ; sinon l'enseignant. */
  protected readonly direction = this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX');
  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly liste = signal<InterpellationVue[]>([]);
  protected readonly detail = signal<InterpellationVue | null>(null);
  protected readonly proposition = signal<PropositionInterpellation | null>(null);
  protected readonly type = signal<TypeInterpellation>('RAPPEL');
  protected readonly filtre = signal<Filtre>('en-cours');
  protected objet = '';
  protected constat = '';
  protected demande = '';
  protected delai = '';
  protected reponse = '';
  protected suite = '';

  protected readonly libelleType = LIBELLE_TYPE_INTERPELLATION;
  protected readonly libelleStatut = LIBELLE_STATUT_INTERPELLATION;
  protected readonly classeStatut = CLASSE_STATUT_INTERPELLATION;
  protected readonly heures = heures;
  protected readonly dateCourte = dateCourte;
  protected readonly dateHeure = dateHeureCourte;

  protected readonly nombres = computed(() => {
    const l = this.liste();
    return {
      enCours: l.filter((i) => i.statut === 'ENVOYEE' || i.statut === 'LUE').length,
      repondues: l.filter((i) => i.statut === 'REPONDUE').length,
      closes: l.filter((i) => i.statut === 'CLOSE').length,
    };
  });

  protected readonly affichees = computed(() => {
    const f = this.filtre();
    return this.liste().filter((i) =>
      f === 'toutes' ? true : f === 'repondues' ? i.statut === 'REPONDUE' : f === 'closes' ? i.statut === 'CLOSE' : i.statut === 'ENVOYEE' || i.statut === 'LUE',
    );
  });

  async ngOnInit(): Promise<void> {
    const q = this.route.snapshot.queryParamMap;
    const classeId = q.get('classeId');
    const matiereId = q.get('matiereId');
    if (this.direction && classeId && matiereId) {
      const p = await this.action.executer(() => this.api.propositionInterpellation(classeId, matiereId));
      if (p) {
        this.proposition.set(p);
        this.choisirType(p.retard.enRetard && p.interpellationsEnCours > 0 ? 'DEMANDE_EXPLICATION' : 'RAPPEL', p);
        this.objet = p.objet;
        this.constat = p.constat;
      }
    }
    await this.charger();
    const id = q.get('id');
    const i = id ? this.liste().find((x) => x.id === id) : undefined;
    if (i) {
      await this.ouvrir(i);
    }
  }

  private async charger(): Promise<void> {
    let anneeId: string | null = null;
    if (this.direction) {
      await this.annee.charger().catch(() => undefined);
      anneeId = this.annee.annee()?.id ?? null;
    }
    const l = await this.action.executer(() => (this.direction ? this.api.interpellations(anneeId) : this.api.mesInterpellations()));
    if (l) {
      this.liste.set(l);
    }
  }

  protected choisirType(t: TypeInterpellation, p: PropositionInterpellation): void {
    this.type.set(t);
    this.demande = t === 'RAPPEL' ? p.demandeRappel : p.demandeExplication;
    this.delai = t === 'DEMANDE_EXPLICATION' ? p.delaiReponse : '';
  }

  protected annulerRedaction(): void {
    this.proposition.set(null);
    void this.router.navigate([], { queryParams: {} });
  }

  protected async envoyer(p: PropositionInterpellation): Promise<void> {
    const v = await this.action.executer(() =>
      this.api.interpeller(p.classeId, p.matiereId, {
        type: this.type(),
        objet: this.objet,
        constat: this.constat,
        demande: this.demande.trim() || null,
        delaiReponse: this.type() === 'DEMANDE_EXPLICATION' ? this.delai || null : null,
      }),
    );
    if (v) {
      this.proposition.set(null);
      this.message.set(
        `${LIBELLE_TYPE_INTERPELLATION[v.type]}${v.numero ? ' n° ' + v.numero : ''} envoyé(e) à ${v.enseignant} (messagerie).`,
      );
      await this.charger();
      this.detail.set(v);
    }
  }

  protected async ouvrir(i: InterpellationVue): Promise<void> {
    const v = await this.action.executer(() => this.api.interpellation(i.id));
    if (v) {
      this.reponse = '';
      this.suite = '';
      this.detail.set(v);
      this.liste.update((l) => l.map((x) => (x.id === v.id ? v : x)));
    }
  }

  protected async repondre(d: InterpellationVue): Promise<void> {
    const v = await this.action.executer(() => this.api.repondreInterpellation(d.id, this.reponse.trim()));
    if (v) {
      this.detail.set(v);
      this.liste.update((l) => l.map((x) => (x.id === v.id ? v : x)));
      this.message.set('Réponse envoyée.');
    }
  }

  protected async clore(d: InterpellationVue): Promise<void> {
    const v = await this.action.executer(() => this.api.cloreInterpellation(d.id, this.suite.trim() || null));
    if (v) {
      this.detail.set(v);
      this.liste.update((l) => l.map((x) => (x.id === v.id ? v : x)));
      this.message.set('Interpellation close.');
    }
  }

  protected async lettre(d: InterpellationVue): Promise<void> {
    const f = await this.action.executer(() => this.api.lettreInterpellation(d.id));
    if (f) {
      enregistrerFichier(f, `${d.numero ?? 'rappel'}-${d.classeCode}.pdf`.replace(/\s+/g, '-'));
    }
  }
}
