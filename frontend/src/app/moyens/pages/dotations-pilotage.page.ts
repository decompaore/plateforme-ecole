import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';

import { Action } from '../../admin/action';
import { PaysVue } from '../../admin/modeles-territoire';
import { PlateformeNavComponent } from '../../admin/plateforme-nav.component';
import { TerritoireApi } from '../../admin/territoire-api.service';
import { SessionService } from '../../core/session.service';
import { DotationDetailComponent } from '../dotation-detail.component';
import {
  Categorie, CATEGORIES, CLASSE_STATUT_DOTATION, Dotations, DotationResume, DotationVue, LIBELLE_CATEGORIE,
  LIBELLE_STATUT_DOTATION, LigneSaisie, montant, quantite, Reception, UNITE_PAR_DEFAUT,
} from '../modeles-moyens';
import { MoyensApi } from '../moyens-api.service';
import { STYLES_MOYENS } from '../styles-moyens';

/** Année scolaire en cours : « 2026-2027 » à partir de septembre. */
export function anneeScolaireCourante(d: Date = new Date()): string {
  const debut = d.getMonth() >= 8 ? d.getFullYear() : d.getFullYear() - 1;
  return `${debut}-${debut + 1}`;
}

interface LigneLibre {
  categorie: Categorie;
  designation: string;
  unite: string;
  quantite: number | null;
  valeurUnitaire: number | null;
}

interface LigneRepartie {
  ligneOrigineId: string;
  libelle: string;
  disponible: number;
  quantite: number | null;
}

/**
 * Dotations (v0.48) : le niveau central dote les directions régionales, chaque direction dote les directions qui en
 * dépendent et les établissements qui lui sont rattachés, en répartissant ce qu'elle a reçu ou sur ses moyens
 * propres. Chaque destinataire accuse réception.
 */
@Component({
  selector: 'app-dotations-pilotage',
  imports: [FormsModule, PlateformeNavComponent, DotationDetailComponent],
  template: `
    <div class="page large">
      <h1>Dotations</h1>
      <app-plateforme-nav />
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      <div class="barre-outils">
        @if (superAdmin && pays().length > 1) {
          <label class="visuellement-cache" for="dotationsPays">Pays</label>
          <select id="dotationsPays" (change)="choisirPays($any($event.target).value)">
            @for (p of pays(); track p.id) { <option [value]="p.id" [selected]="p.id === paysId()">{{ p.nom }}</option> }
          </select>
        }
        @if (liste(); as l) {
          <label class="visuellement-cache" for="dotationsAnnee">Année scolaire</label>
          <select id="dotationsAnnee" (change)="choisirAnnee($any($event.target).value)">
            <option value="" [selected]="!l.annee">Toutes les années</option>
            @for (a of l.annees; track a) { <option [value]="a" [selected]="a === l.annee">{{ a }}</option> }
          </select>
          <span class="espace"></span>
          @if (l.peutDoter && !form()) {
            <button type="button" class="bouton petit" (click)="nouvelle(null)">Nouvelle dotation</button>
          }
        }
      </div>

      @if (liste(); as l) {
        <h2>{{ l.instance.nom }}</h2>

        @if (form()) {
          <form class="sous-formulaire carte" (ngSubmit)="creer()">
            <h2>{{ origine() ? 'Répartir ' + origine()!.reference : 'Nouvelle dotation' }}</h2>
            <div class="grille-champs">
              <div class="champ">
                <label for="dDestinataire">Destinataire</label>
                <select id="dDestinataire" name="dDestinataire" required [(ngModel)]="destinataire">
                  @for (d of l.destinataires; track d.id) {
                    <option [value]="d.type + ':' + d.id">{{ d.nom }}{{ d.type === 'ETABLISSEMENT' ? ' (établissement)' : '' }}</option>
                  }
                </select>
              </div>
              @if (!origine()) {
                <div class="champ"><label for="dAnnee">Année scolaire</label><input id="dAnnee" name="dAnnee" maxlength="20" required [(ngModel)]="anneeDotation" /></div>
              }
              <div class="champ"><label for="dObjet">Objet</label><input id="dObjet" name="dObjet" maxlength="200" required [(ngModel)]="objet" placeholder="Ex. dotation de rentrée en matière d'œuvre" /></div>
            </div>
            @if (!origine() && recuesRepartissables().length) {
              <p class="doux petit-texte">Pour répartir une dotation reçue, ouvrez-la dans « Reçues » puis « Répartir ».</p>
            }

            @if (origine()) {
              <div class="tableau-defilant">
                <table class="tableau">
                  <thead><tr><th>Article reçu</th><th class="nombre">Disponible</th><th class="nombre">À doter</th></tr></thead>
                  <tbody>
                    @for (r of repartition; track r.ligneOrigineId) {
                      <tr>
                        <td>{{ r.libelle }}</td>
                        <td class="nombre">{{ quantite(r.disponible) }}</td>
                        <td class="nombre"><input type="number" min="0" step="any" [max]="r.disponible" [name]="'rep' + r.ligneOrigineId" [attr.aria-label]="'Quantité : ' + r.libelle" [(ngModel)]="r.quantite" /></td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            } @else {
              <div class="tableau-defilant">
                <table class="tableau">
                  <thead><tr><th>Catégorie</th><th>Désignation</th><th class="nombre">Quantité</th><th>Unité</th><th class="nombre">Valeur unitaire (F)</th><th></th></tr></thead>
                  <tbody>
                    @for (r of libres; track $index) {
                      <tr>
                        <td>
                          <select [name]="'cat' + $index" aria-label="Catégorie" [(ngModel)]="r.categorie" (ngModelChange)="changerCategorie(r, $event)">
                            @for (c of categories; track c) { <option [value]="c">{{ libelleCategorie[c] }}</option> }
                          </select>
                        </td>
                        <td><input type="text" [name]="'des' + $index" aria-label="Désignation" maxlength="200" [(ngModel)]="r.designation" /></td>
                        <td class="nombre"><input type="number" min="0" step="any" [name]="'qte' + $index" aria-label="Quantité" [(ngModel)]="r.quantite" /></td>
                        <td><input type="text" [name]="'uni' + $index" aria-label="Unité" maxlength="30" [(ngModel)]="r.unite" /></td>
                        <td class="nombre"><input type="number" min="0" step="1" [name]="'val' + $index" aria-label="Valeur unitaire" [(ngModel)]="r.valeurUnitaire" /></td>
                        <td><button type="button" class="bouton discret petit" (click)="retirerLigne($index)" [disabled]="libres.length === 1">Retirer</button></td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
              <button type="button" class="bouton secondaire petit" (click)="ajouterLigne()">Ajouter une ligne</button>
            }
            <div class="actions-ligne">
              <button type="submit" class="bouton" [disabled]="action.enCours()">Préparer la dotation</button>
              <button type="button" class="bouton secondaire" (click)="form.set(false)">Annuler</button>
            </div>
            <p class="doux petit-texte">La dotation reste en préparation jusqu'à son envoi ; le destinataire ne la voit qu'une fois envoyée.</p>
          </form>
        }

        <div class="bascule" role="group" aria-label="Dotations">
          <button type="button" [class.actif]="onglet() === 'recues'" (click)="onglet.set('recues')">Reçues ({{ l.recues.length }})</button>
          <button type="button" [class.actif]="onglet() === 'emises'" (click)="onglet.set('emises')">Émises ({{ l.emises.length }})</button>
        </div>
        <section class="carte">
          <ul class="liste">
            @for (d of onglet() === 'recues' ? l.recues : l.emises; track d.id) {
              <li>
                <button type="button" class="ligne-lien lien-bouton" (click)="ouvrir(d)">
                  <span>
                    <strong>{{ d.reference }}</strong> · {{ d.objet }}<br />
                    <span class="doux petit-texte">
                      {{ onglet() === 'recues' ? 'De ' + d.emettrice.nom : 'À ' + d.destinataire.nom }} · {{ d.annee }} · {{ d.lignes }} ligne(s)
                      @if (d.valeur) { · {{ montant(d.valeur) }} }
                      @if (d.origineReference) { · répartition de {{ d.origineReference }} }
                    </span>
                  </span>
                  <span class="pastille {{ classeStatut[d.statut] }}">{{ libelleStatut[d.statut] }}</span>
                </button>
              </li>
            } @empty {
              <li class="doux">{{ onglet() === 'recues' ? 'Aucune dotation reçue.' : 'Aucune dotation émise.' }}</li>
            }
          </ul>
        </section>
      }

      @if (dotation(); as d) {
        <section class="carte">
          <app-dotation-detail [dotation]="d" [enCours]="action.enCours()" (accuser)="accuser(d, $event)" />
          <div class="actions-ligne">
            @if (d.peutModifier) {
              <button type="button" class="bouton" [disabled]="action.enCours()" (click)="envoyer(d)">Envoyer à {{ d.destinataire.nom }}</button>
              <button type="button" class="bouton danger" [disabled]="action.enCours()" (click)="supprimer(d)">Supprimer</button>
            }
            @if (d.peutRepartir) {
              <button type="button" class="bouton secondaire" (click)="nouvelle(d)">Répartir</button>
            }
          </div>
        </section>
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
export class DotationsPilotagePage implements OnInit {
  private readonly api = inject(MoyensApi);
  private readonly territoire = inject(TerritoireApi);
  private readonly route = inject(ActivatedRoute);
  private readonly session = inject(SessionService);
  protected readonly superAdmin = this.session.profil()?.superAdmin === true;

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly pays = signal<PaysVue[]>([]);
  protected readonly paysId = signal<string | null>(null);
  protected readonly directionId = signal<string | null>(null);
  protected readonly annee = signal<string | null>(null);
  protected readonly liste = signal<Dotations | null>(null);
  protected readonly onglet = signal<'recues' | 'emises'>('recues');
  protected readonly dotation = signal<DotationVue | null>(null);
  protected readonly form = signal(false);
  protected readonly origine = signal<DotationVue | null>(null);
  protected destinataire = '';
  protected anneeDotation = anneeScolaireCourante();
  protected objet = '';
  protected libres: LigneLibre[] = [];
  protected repartition: LigneRepartie[] = [];

  protected readonly categories = CATEGORIES;
  protected readonly libelleCategorie = LIBELLE_CATEGORIE;
  protected readonly libelleStatut = LIBELLE_STATUT_DOTATION;
  protected readonly classeStatut = CLASSE_STATUT_DOTATION;
  protected readonly quantite = quantite;
  protected readonly montant = montant;
  protected readonly recuesRepartissables = computed(() => (this.liste()?.recues ?? []).filter((d) => d.statut === 'RECUE'));

  async ngOnInit(): Promise<void> {
    const q = this.route.snapshot.queryParamMap;
    this.directionId.set(q.get('direction'));
    this.paysId.set(q.get('pays'));
    if (this.superAdmin) {
      const l = await this.action.executer(() => this.territoire.pays());
      if (l) {
        this.pays.set(l);
        if (!this.paysId() && !this.directionId() && l.length) {
          this.paysId.set((l.find((p) => p.code === 'BF') ?? l[0]).id);
        }
      }
    }
    await this.charger();
  }

  protected async charger(): Promise<void> {
    const l = await this.action.executer(() =>
      this.api.dotations({ direction: this.directionId(), pays: this.directionId() ? null : this.paysId(), annee: this.annee() }),
    );
    if (l) {
      this.liste.set(l);
      if (!l.recues.length && l.emises.length) {
        this.onglet.set('emises');
      }
    }
  }

  protected async choisirPays(id: string): Promise<void> {
    this.paysId.set(id || null);
    this.dotation.set(null);
    await this.charger();
  }

  protected async choisirAnnee(annee: string): Promise<void> {
    this.annee.set(annee || null);
    this.dotation.set(null);
    await this.charger();
  }

  protected async ouvrir(d: DotationResume): Promise<void> {
    const v = await this.action.executer(() => this.api.dotation(d.id));
    if (v) {
      this.dotation.set(v);
    }
  }

  /** Nouvelle dotation, libre ou répartition d'une dotation reçue. */
  protected nouvelle(origine: DotationVue | null): void {
    const l = this.liste();
    this.origine.set(origine);
    this.destinataire = l?.destinataires.length ? `${l.destinataires[0].type}:${l.destinataires[0].id}` : '';
    this.objet = origine ? origine.objet : '';
    this.anneeDotation = origine?.annee ?? this.annee() ?? anneeScolaireCourante();
    this.libres = [{ categorie: 'MATIERE', designation: '', unite: UNITE_PAR_DEFAUT.MATIERE, quantite: null, valeurUnitaire: null }];
    this.repartition = (origine?.lignes ?? [])
      .filter((x) => x.disponible > 0)
      .map((x) => ({ ligneOrigineId: x.id, libelle: `${x.designation} (${x.unite})`, disponible: x.disponible, quantite: null }));
    this.message.set(null);
    this.form.set(true);
  }

  protected changerCategorie(r: LigneLibre, c: Categorie): void {
    if (!r.unite.trim() || Object.values(UNITE_PAR_DEFAUT).includes(r.unite)) {
      r.unite = UNITE_PAR_DEFAUT[c];
    }
  }

  protected ajouterLigne(): void {
    this.libres = [...this.libres, { categorie: 'MATIERE', designation: '', unite: UNITE_PAR_DEFAUT.MATIERE, quantite: null, valeurUnitaire: null }];
  }

  protected retirerLigne(i: number): void {
    this.libres = this.libres.filter((_, j) => j !== i);
  }

  protected async creer(): Promise<void> {
    const [type, id] = this.destinataire.split(':');
    const origine = this.origine();
    const lignes: LigneSaisie[] = origine
      ? this.repartition
          .filter((r) => r.quantite !== null && Number(r.quantite) > 0)
          .map((r) => ({ ligneOrigineId: r.ligneOrigineId, categorie: null, designation: null, unite: null, quantite: Number(r.quantite), valeurUnitaire: null }))
      : this.libres
          .filter((r) => r.designation.trim() && r.quantite !== null && Number(r.quantite) > 0)
          .map((r) => ({
            ligneOrigineId: null, categorie: r.categorie, designation: r.designation, unite: r.unite, quantite: Number(r.quantite),
            valeurUnitaire: r.valeurUnitaire === null || (r.valeurUnitaire as unknown) === '' ? null : Number(r.valeurUnitaire),
          }));
    const v = await this.action.executer(() =>
      this.api.creerDotation({
        annee: this.anneeDotation, destinataireType: type as 'DIRECTION' | 'ETABLISSEMENT', destinataireId: id,
        origineId: origine?.id ?? null, objet: this.objet, lignes,
      }),
    );
    if (v) {
      this.form.set(false);
      this.dotation.set(v);
      this.onglet.set('emises');
      this.message.set(`${v.reference} préparée : vérifiez-la puis envoyez-la.`);
      await this.charger();
    }
  }

  protected async envoyer(d: DotationVue): Promise<void> {
    const v = await this.action.executer(() => this.api.envoyerDotation(d.id));
    if (v) {
      this.dotation.set(v);
      this.message.set(`${v.reference} envoyée à ${v.destinataire.nom}.`);
      await this.charger();
    }
  }

  protected async supprimer(d: DotationVue): Promise<void> {
    if (await this.action.reussit(() => this.api.supprimerDotation(d.id))) {
      this.dotation.set(null);
      this.message.set(`${d.reference} supprimée.`);
      await this.charger();
    }
  }

  protected async accuser(d: DotationVue, r: Reception): Promise<void> {
    const v = await this.action.executer(() => this.api.accuserReceptionDirection(d.id, r));
    if (v) {
      this.dotation.set(v);
      this.message.set(`Réception de ${v.reference} accusée.`);
      await this.charger();
    }
  }
}
