import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';

import { Action } from '../../admin/action';
import { dateHeureCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { DotationDetailComponent } from '../dotation-detail.component';
import {
  Categorie, CATEGORIES, CLASSE_STATUT_DOTATION, DonneesLigne, Domaine, DotationResume, DotationVue, EtatVue,
  LIBELLE_CATEGORIE, LIBELLE_DOMAINE, LIBELLE_PRIORITE, LIBELLE_STATUT_DOTATION, LIBELLE_STATUT_ETAT, LigneEtatVue,
  quantite, Reception, UNITE_PAR_DEFAUT,
} from '../modeles-moyens';
import { MoyensApi } from '../moyens-api.service';
import { STYLES_MOYENS } from '../styles-moyens';

/** Saisie d'une ligne de l'état des besoins. */
interface Formulaire {
  id: string | null;
  categorie: Categorie;
  domaine: Domaine;
  designation: string;
  unite: string;
  quantite: number | null;
  priorite: number;
  justification: string;
}

/**
 * État annuel des besoins de l'établissement (v0.48) : le censeur exprime les besoins du domaine général, le chef
 * des travaux ceux du domaine technique, le proviseur complète et transmet à la direction. Dotations reçues : le
 * proviseur ou l'intendant en accuse réception.
 */
@Component({
  selector: 'app-besoins',
  imports: [FormsModule, DotationDetailComponent],
  template: `
    <div class="page large">
      <h1>Besoins et dotations</h1>
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      <div class="bascule" role="group" aria-label="Rubriques">
        <button type="button" [class.actif]="onglet() === 'etat'" (click)="onglet.set('etat')">État des besoins</button>
        <button type="button" [class.actif]="onglet() === 'dotations'" (click)="afficherDotations()">
          Dotations reçues @if (aAccuser()) { ({{ aAccuser() }} à accuser) }
        </button>
      </div>

      @if (onglet() === 'etat') {
        @if (etat(); as e) {
          <div class="barre-outils">
            @if (e.annees.length > 1) {
              <label for="anneeBesoins">Année scolaire</label>
              <select id="anneeBesoins" (change)="charger($any($event.target).value)">
                @for (a of e.annees; track a.id) { <option [value]="a.id" [selected]="a.id === e.anneeId">{{ a.libelle }}</option> }
              </select>
            } @else {
              <strong>Année scolaire {{ e.annee }}</strong>
            }
            <span class="pastille {{ e.statut === 'TRANSMIS' ? 'present' : e.statut === 'RENVOYE' ? 'absent' : '' }}">{{ libelleStatut[e.statut] }}</span>
            @if (e.transmisLe) { <span class="doux petit-texte">le {{ dateHeure(e.transmisLe) }}@if (e.transmisPar) { par {{ e.transmisPar }} }</span> }
          </div>
          @if (e.statut === 'RENVOYE') {
            <div class="alerte attention" role="status">
              <strong>Renvoyé par la direction pour correction</strong>@if (e.motifRenvoi) { : {{ e.motifRenvoi }} }
            </div>
          }
          @if (!e.direction) {
            <div class="alerte attention">L'établissement n'est rattaché à aucune direction : l'état ne pourra pas être transmis. Contactez l'administrateur du pays.</div>
          }
          <p class="doux">
            Un état par année scolaire. Le censeur exprime les besoins du domaine général, le chef des travaux ceux du
            domaine technique ; le chef d'établissement complète et transmet à {{ e.direction ?? 'la direction de rattachement' }}.
          </p>

          @if (e.modifiable && e.domaines.length && !form()) {
            <button type="button" class="bouton petit" (click)="nouvelle()">Ajouter un besoin</button>
          }
          @if (form(); as f) {
            <form class="sous-formulaire carte" (ngSubmit)="enregistrer()">
              <h2>{{ f.id ? 'Modifier le besoin' : 'Nouveau besoin' }}</h2>
              <div class="grille-champs">
                <div class="champ">
                  <label for="fCategorie">Catégorie</label>
                  <select id="fCategorie" name="fCategorie" [(ngModel)]="f.categorie" (ngModelChange)="changerCategorie($event)">
                    @for (c of categories; track c) { <option [value]="c">{{ libelleCategorie[c] }}</option> }
                  </select>
                </div>
                <div class="champ">
                  <label for="fDomaine">Domaine</label>
                  <select id="fDomaine" name="fDomaine" [(ngModel)]="f.domaine">
                    @for (d of e.domaines; track d) { <option [value]="d">{{ libelleDomaine[d] }}</option> }
                  </select>
                </div>
                <div class="champ">
                  <label for="fDesignation">Désignation</label>
                  <input id="fDesignation" name="fDesignation" maxlength="200" required [(ngModel)]="f.designation"
                    [placeholder]="exemple(f.categorie)" />
                </div>
                <div class="champ"><label for="fQuantite">Quantité</label><input id="fQuantite" name="fQuantite" type="number" min="0" step="any" required [(ngModel)]="f.quantite" /></div>
                <div class="champ"><label for="fUnite">Unité</label><input id="fUnite" name="fUnite" maxlength="30" required [(ngModel)]="f.unite" /></div>
                <div class="champ">
                  <label for="fPriorite">Priorité</label>
                  <select id="fPriorite" name="fPriorite" [(ngModel)]="f.priorite">
                    @for (p of [1, 2, 3]; track p) { <option [ngValue]="p">{{ libellePriorite[p] }}</option> }
                  </select>
                </div>
              </div>
              <div class="champ">
                <label for="fJustification">Justification</label>
                <textarea id="fJustification" name="fJustification" rows="2" maxlength="500" [(ngModel)]="f.justification"></textarea>
              </div>
              <div class="actions-ligne">
                <button type="submit" class="bouton" [disabled]="action.enCours()">Enregistrer</button>
                <button type="button" class="bouton secondaire" (click)="form.set(null)">Annuler</button>
              </div>
            </form>
          }

          <div class="tableau-defilant">
            <table class="tableau">
              <thead>
                <tr><th>Désignation</th><th>Domaine</th><th class="nombre">Quantité</th><th>Priorité</th><th>Justification</th><th></th></tr>
              </thead>
              <tbody>
                @for (g of groupes(); track g.categorie) {
                  <tr class="intertitre"><td colspan="6">{{ libelleCategorie[g.categorie] }} ({{ g.lignes.length }})</td></tr>
                  @for (l of g.lignes; track l.id) {
                    <tr>
                      <td>{{ l.designation }}@if (l.proposePar) { <br /><span class="doux petit-texte">{{ l.proposePar }}</span> }</td>
                      <td>{{ l.domaine === 'GENERAL' ? 'Général' : 'Technique' }}</td>
                      <td class="nombre">{{ quantite(l.quantite) }} {{ l.unite }}</td>
                      <td>{{ libellePriorite[l.priorite] }}</td>
                      <td class="petit-texte">{{ l.justification ?? '' }}</td>
                      <td>
                        @if (l.peutModifier) {
                          <div class="actions-ligne">
                            <button type="button" class="bouton discret petit" (click)="modifier(l)">Modifier</button>
                            <button type="button" class="bouton danger petit" [disabled]="action.enCours()" (click)="supprimer(l)">Supprimer</button>
                          </div>
                        }
                      </td>
                    </tr>
                  }
                } @empty {
                  <tr><td colspan="6" class="doux">Aucun besoin exprimé pour {{ e.annee }}.</td></tr>
                }
              </tbody>
            </table>
          </div>

          @if (e.peutTransmettre) {
            <section class="carte">
              <h2>Transmettre à {{ e.direction }}</h2>
              <p class="doux">Une fois transmis, l'état n'est plus modifiable, sauf si la direction le renvoie pour correction.</p>
              <div class="champ">
                <label for="obsTransmission">Observations du chef d'établissement</label>
                <textarea id="obsTransmission" name="obsTransmission" rows="2" maxlength="1000" [(ngModel)]="observations"></textarea>
              </div>
              <button type="button" class="bouton" [disabled]="action.enCours()" (click)="transmettre()">Transmettre l'état des besoins</button>
            </section>
          } @else if (e.observations) {
            <p><strong>Observations :</strong> {{ e.observations }}</p>
          }
        }
      }

      @if (onglet() === 'dotations') {
        <section class="carte">
          <ul class="liste">
            @for (d of dotations(); track d.id) {
              <li>
                <button type="button" class="ligne-lien lien-bouton" (click)="ouvrir(d)">
                  <span><strong>{{ d.reference }}</strong> · {{ d.objet }}<br /><span class="doux petit-texte">De {{ d.emettrice.nom }} · {{ d.annee }} · {{ d.lignes }} ligne(s)</span></span>
                  <span class="pastille {{ classeDotation[d.statut] }}">{{ libelleDotation[d.statut] }}</span>
                </button>
              </li>
            } @empty {
              <li class="doux">Aucune dotation reçue.</li>
            }
          </ul>
        </section>
        @if (dotation(); as d) {
          <section class="carte">
            <app-dotation-detail [dotation]="d" [enCours]="action.enCours()" (accuser)="accuser(d, $event)" />
          </section>
        }
      }
    </div>
  `,
  styles: [
    STYLES_MOYENS,
    `
      .lien-bouton {
        width: 100%;
        background: none;
        border: none;
        font: inherit;
        text-align: left;
        cursor: pointer;
      }
    `,
  ],
})
export class BesoinsPage implements OnInit {
  private readonly api = inject(MoyensApi);
  private readonly route = inject(ActivatedRoute);
  private readonly session = inject(SessionService);

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly onglet = signal<'etat' | 'dotations'>('etat');
  protected readonly etat = signal<EtatVue | null>(null);
  protected readonly form = signal<Formulaire | null>(null);
  protected readonly dotations = signal<DotationResume[]>([]);
  protected readonly dotation = signal<DotationVue | null>(null);
  protected observations = '';

  protected readonly categories = CATEGORIES;
  protected readonly libelleCategorie = LIBELLE_CATEGORIE;
  protected readonly libelleDomaine = LIBELLE_DOMAINE;
  protected readonly libellePriorite = LIBELLE_PRIORITE;
  protected readonly libelleStatut = LIBELLE_STATUT_ETAT;
  protected readonly libelleDotation = LIBELLE_STATUT_DOTATION;
  protected readonly classeDotation = CLASSE_STATUT_DOTATION;
  protected readonly quantite = quantite;
  protected readonly dateHeure = dateHeureCourte;

  protected readonly groupes = computed(() => {
    const lignes = this.etat()?.lignes ?? [];
    return CATEGORIES.map((categorie) => ({ categorie, lignes: lignes.filter((l) => l.categorie === categorie) })).filter(
      (g) => g.lignes.length,
    );
  });
  protected readonly aAccuser = computed(() => this.dotations().filter((d) => d.statut === 'ENVOYEE').length);

  async ngOnInit(): Promise<void> {
    if (this.route.snapshot.queryParamMap.get('onglet') === 'dotations') {
      await this.afficherDotations();
    }
    await this.charger();
    if (this.onglet() === 'etat') {
      const liste = await this.action.executer(() => this.api.dotationsRecues());
      if (liste) {
        this.dotations.set(liste);
      }
    }
  }

  protected async charger(anneeId?: string): Promise<void> {
    const e = await this.action.executer(() => this.api.etat(anneeId ?? this.etat()?.anneeId));
    if (e) {
      this.etat.set(e);
      this.form.set(null);
    }
  }

  protected exemple(c: Categorie): string {
    return {
      INFRASTRUCTURE: 'Ex. salle de classe, atelier, latrines',
      PERSONNEL: 'Ex. professeur de mathématiques',
      EQUIPEMENT_OUTILS: 'Ex. tour parallèle, poste à souder',
      MATIERE: "Ex. rond d'acier Ø 20, craie",
    }[c];
  }

  protected nouvelle(): void {
    const e = this.etat();
    if (!e) {
      return;
    }
    const domaine: Domaine = e.domaines.includes('GENERAL') && !this.session.aLeRole('CHEF_TRAVAUX') ? 'GENERAL' : e.domaines[0];
    this.form.set({ id: null, categorie: 'MATIERE', domaine, designation: '', unite: UNITE_PAR_DEFAUT.MATIERE, quantite: null, priorite: 2, justification: '' });
  }

  protected changerCategorie(c: Categorie): void {
    const f = this.form();
    if (f && !f.id && Object.values(UNITE_PAR_DEFAUT).includes(f.unite)) {
      f.unite = UNITE_PAR_DEFAUT[c];
    }
  }

  protected modifier(l: LigneEtatVue): void {
    this.form.set({
      id: l.id, categorie: l.categorie, domaine: l.domaine, designation: l.designation, unite: l.unite,
      quantite: l.quantite, priorite: l.priorite, justification: l.justification ?? '',
    });
  }

  protected async enregistrer(): Promise<void> {
    const f = this.form();
    const e = this.etat();
    if (!f || !e) {
      return;
    }
    const d: DonneesLigne = {
      categorie: f.categorie, domaine: f.domaine, designation: f.designation, unite: f.unite,
      quantite: Number(f.quantite), priorite: Number(f.priorite), justification: f.justification.trim() || null,
    };
    const r = await this.action.executer(() => (f.id ? this.api.modifierLigne(f.id, d) : this.api.ajouterLigne(e.anneeId, d)));
    if (r) {
      this.etat.set(r);
      this.form.set(null);
      this.message.set(null);
    }
  }

  protected async supprimer(l: LigneEtatVue): Promise<void> {
    const r = await this.action.executer(() => this.api.supprimerLigne(l.id));
    if (r) {
      this.etat.set(r);
    }
  }

  protected async transmettre(): Promise<void> {
    const e = this.etat();
    if (!e) {
      return;
    }
    const r = await this.action.executer(() => this.api.transmettreEtat(e.anneeId, this.observations.trim() || null));
    if (r) {
      this.etat.set(r);
      this.observations = '';
      this.message.set(`État des besoins ${r.annee} transmis à ${r.direction}.`);
    }
  }

  protected async afficherDotations(): Promise<void> {
    this.onglet.set('dotations');
    const liste = await this.action.executer(() => this.api.dotationsRecues());
    if (liste) {
      this.dotations.set(liste);
    }
  }

  protected async ouvrir(d: DotationResume): Promise<void> {
    const v = await this.action.executer(() => this.api.dotationRecue(d.id));
    if (v) {
      this.dotation.set(v);
    }
  }

  protected async accuser(d: DotationVue, r: Reception): Promise<void> {
    const v = await this.action.executer(() => this.api.accuserReceptionEtablissement(d.id, r));
    if (v) {
      this.dotation.set(v);
      this.message.set(`Réception de ${v.reference} accusée.`);
      const liste = await this.action.executer(() => this.api.dotationsRecues());
      if (liste) {
        this.dotations.set(liste);
      }
    }
  }
}
