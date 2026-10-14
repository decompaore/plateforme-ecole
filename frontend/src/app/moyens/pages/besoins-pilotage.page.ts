import { LowerCasePipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';

import { Action } from '../../admin/action';
import { PaysVue } from '../../admin/modeles-territoire';
import { PlateformeNavComponent } from '../../admin/plateforme-nav.component';
import { TerritoireApi } from '../../admin/territoire-api.service';
import { dateHeureCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import {
  CATEGORIES, CLASSE_STATUT_ETABLISSEMENT, DirectionBesoins, EtablissementBesoins, LIBELLE_CATEGORIE,
  LIBELLE_PRIORITE, LIBELLE_STATUT_ETABLISSEMENT, LIBELLE_STATUT_TRANSMISSION, quantite, RemonteeVue, VueBesoins,
} from '../modeles-moyens';
import { MoyensApi } from '../moyens-api.service';
import { STYLES_MOYENS } from '../styles-moyens';

interface Arbitrage {
  quantite: number | null;
  observation: string;
}

/**
 * Besoins remontés (v0.48), vus d'une direction ou du niveau central : état de chaque établissement du ressort,
 * transmissions des directions inférieures, besoins consolidés. La direction arbitre (quantités retenues), renvoie
 * pour correction et transmet à la direction supérieure ; le niveau central arbitre en dernier.
 */
@Component({
  selector: 'app-besoins-pilotage',
  imports: [FormsModule, LowerCasePipe, PlateformeNavComponent],
  template: `
    <div class="page large">
      <h1>Besoins des établissements</h1>
      <app-plateforme-nav />
      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      <div class="barre-outils">
        @if (superAdmin && pays().length > 1) {
          <label class="visuellement-cache" for="besoinsPays">Pays</label>
          <select id="besoinsPays" (change)="choisirPays($any($event.target).value)">
            @for (p of pays(); track p.id) { <option [value]="p.id" [selected]="p.id === paysId()">{{ p.nom }}</option> }
          </select>
        }
        @if (vue(); as v) {
          <label class="visuellement-cache" for="besoinsAnnee">Année scolaire</label>
          <select id="besoinsAnnee" (change)="choisirAnnee($any($event.target).value)">
            @for (a of v.annees; track a) { <option [value]="a" [selected]="a === v.annee">{{ a }}</option> }
          </select>
          <span class="espace"></span>
          <button type="button" class="bouton secondaire petit" [disabled]="export.enCours()" (click)="exporter('xlsx')">Excel</button>
          <button type="button" class="bouton secondaire petit" [disabled]="export.enCours()" (click)="exporter('pdf')">PDF</button>
        }
      </div>
      @if (export.erreur()) {
        <div class="alerte erreur" role="alert">{{ export.erreur() }}</div>
      }

      @if (vue(); as v) {
        <section class="perimetre">
          @if (directionId()) {
            <button type="button" class="bouton discret petit" (click)="ouvrirDirection(null)">↑ Mon ressort</button>
          }
          <h2>{{ v.perimetre.nom }}</h2>
          @if (v.perimetre.chemin && v.perimetre.type === 'DIRECTION') { <p class="doux">{{ v.perimetre.chemin }}</p> }
        </section>

        <div class="chiffres">
          <div><strong>{{ recus() }} / {{ v.etablissements.length }}</strong><span>états reçus</span></div>
          <div><strong>{{ v.consolidation.length }}</strong><span>besoins distincts</span></div>
          @if (v.directions.length) {
            <div><strong>{{ transmises() }} / {{ v.directions.length }}</strong><span>directions ont transmis</span></div>
          }
        </div>

        @if (v.transmission; as t) {
          @if (t.statut === 'TRANSMISE') {
            <div class="alerte succes" role="status">Besoins {{ v.annee }} transmis à {{ v.destination }} le {{ dateHeure(t.transmiseLe!) }}@if (t.transmisePar) { par {{ t.transmisePar }} }.</div>
          } @else if (t.statut === 'RENVOYEE') {
            <div class="alerte attention" role="status"><strong>Transmission renvoyée par {{ v.destination }}</strong>@if (t.motifRenvoi) { : {{ t.motifRenvoi }} }</div>
          }
        }

        <div class="bascule" role="group" aria-label="Rubriques">
          <button type="button" [class.actif]="onglet() === 'etablissements'" (click)="onglet.set('etablissements')">Établissements</button>
          @if (v.directions.length) {
            <button type="button" [class.actif]="onglet() === 'directions'" (click)="onglet.set('directions')">Directions</button>
          }
          <button type="button" [class.actif]="onglet() === 'consolidation'" (click)="onglet.set('consolidation')">Besoins consolidés</button>
        </div>

        @if (onglet() === 'etablissements') {
          <div class="tableau-defilant">
            <table class="tableau">
              <thead><tr><th>Établissement</th><th>Direction</th><th>État</th><th class="nombre">Lignes</th><th></th></tr></thead>
              <tbody>
                @for (e of v.etablissements; track e.id) {
                  <tr>
                    <td>{{ e.nom }}</td>
                    <td class="petit-texte">{{ e.direction }}</td>
                    <td>
                      <span class="pastille {{ classeEtab[e.statut] }}">{{ libelleEtab[e.statut] }}</span>
                      @if (e.etape) { <br /><span class="doux petit-texte">chez {{ e.etape }}</span> }
                      @if (e.motifRenvoi && e.statut === 'RENVOYE') { <br /><span class="doux petit-texte">{{ e.motifRenvoi }}</span> }
                    </td>
                    <td class="nombre">{{ e.lignes || '' }}</td>
                    <td>
                      @if (e.remonteeId && e.statut !== 'RENVOYE') {
                        <button type="button" class="bouton discret petit" (click)="ouvrirRemontee(e)">Voir</button>
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr><td colspan="5" class="doux">Aucun établissement dans ce ressort.</td></tr>
                }
              </tbody>
            </table>
          </div>
        }

        @if (onglet() === 'directions') {
          <div class="tableau-defilant">
            <table class="tableau">
              <thead><tr><th>Direction</th><th>Transmission</th><th class="nombre">États reçus</th><th></th></tr></thead>
              <tbody>
                @for (d of v.directions; track d.id) {
                  <tr>
                    <td><button type="button" class="bouton discret petit" (click)="ouvrirDirection(d.id)">{{ d.nom }}</button></td>
                    <td>
                      <span class="pastille {{ d.statut === 'TRANSMISE' ? 'present' : d.statut === 'RENVOYEE' ? 'absent' : '' }}">{{ libelleTransmission[d.statut] }}</span>
                      @if (d.transmiseLe && d.statut === 'TRANSMISE') { <span class="doux petit-texte"> le {{ dateHeure(d.transmiseLe) }}</span> }
                    </td>
                    <td class="nombre">{{ d.recues }} / {{ d.etablissements }}</td>
                    <td>
                      @if (d.peutRenvoyer) {
                        <button type="button" class="bouton danger petit" (click)="renvoiDirection.set(d)">Renvoyer</button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (renvoiDirection(); as d) {
            <form class="sous-formulaire carte" (ngSubmit)="renvoyerDirection(d)">
              <h3>Renvoyer à {{ d.nom }}</h3>
              <div class="champ"><label for="motifDirection">Motif</label><textarea id="motifDirection" name="motifDirection" rows="2" maxlength="500" required [(ngModel)]="motif"></textarea></div>
              <div class="actions-ligne">
                <button type="submit" class="bouton" [disabled]="action.enCours() || !motif.trim()">Renvoyer</button>
                <button type="button" class="bouton secondaire" (click)="renvoiDirection.set(null)">Annuler</button>
              </div>
            </form>
          }
        }

        @if (onglet() === 'consolidation') {
          <div class="tableau-defilant">
            <table class="tableau">
              <thead><tr><th>Désignation</th><th class="nombre">Demandé</th><th class="nombre">Retenu</th><th>Unité</th><th class="nombre">Établissements</th><th>Priorité</th></tr></thead>
              <tbody>
                @for (g of consolidation(); track g.categorie) {
                  <tr class="intertitre"><td colspan="6">{{ libelleCategorie[g.categorie] }}</td></tr>
                  @for (l of g.lignes; track l.designation + l.unite) {
                    <tr>
                      <td>{{ l.designation }}</td>
                      <td class="nombre">{{ quantite(l.demandee) }}</td>
                      <td class="nombre"><strong>{{ quantite(l.retenue) }}</strong></td>
                      <td>{{ l.unite }}</td>
                      <td class="nombre">{{ l.etablissements }}</td>
                      <td>{{ libellePriorite[l.priorite] }}</td>
                    </tr>
                  }
                } @empty {
                  <tr><td colspan="6" class="doux">Aucun état des besoins n'est encore arrivé à ce niveau pour {{ v.annee }}.</td></tr>
                }
              </tbody>
            </table>
          </div>
          <p class="doux petit-texte">Retenu : votre arbitrage, sinon celui du niveau inférieur, sinon la quantité demandée par l'établissement.</p>
        }

        @if (v.peutTransmettre) {
          <section class="carte">
            <h2>Transmettre à {{ v.destination }}</h2>
            <p class="doux">Les états reçus et vos arbitrages partent ensemble ; ensuite, vous ne pouvez plus arbitrer ni renvoyer, sauf si {{ v.destination }} vous renvoie la transmission.</p>
            <div class="champ"><label for="obsDirection">Observations</label><textarea id="obsDirection" name="obsDirection" rows="2" maxlength="1000" [(ngModel)]="observations"></textarea></div>
            <button type="button" class="bouton" [disabled]="action.enCours() || !recus()" (click)="transmettre()">Transmettre les besoins {{ v.annee }}</button>
          </section>
        }
      }

      @if (remontee(); as r) {
        <section class="carte" id="remontee">
          <div class="entete-section">
            <h2>{{ r.etablissement }} · {{ r.annee }}</h2>
            <button type="button" class="bouton discret petit" (click)="remontee.set(null)">Fermer</button>
          </div>
          <p class="doux">
            {{ r.direction }} · transmis le {{ dateHeure(r.transmiseLe!) }}@if (r.transmisePar) { par {{ r.transmisePar }} }
            · <span class="pastille {{ classeEtab[r.statut] }}">{{ libelleEtab[r.statut] }}</span>
          </p>
          @if (r.observations) { <p><strong>Observations de l'établissement :</strong> {{ r.observations }}</p> }
          <div class="tableau-defilant">
            <table class="tableau">
              <thead>
                <tr><th>Désignation</th><th class="nombre">Demandé</th><th>Arbitrages</th><th class="nombre">Retenu ({{ r.niveau }})</th><th>Observation</th></tr>
              </thead>
              <tbody>
                @for (l of r.lignes; track l.id) {
                  <tr>
                    <td>
                      {{ l.designation }} <span class="doux petit-texte">({{ libelleCategorie[l.categorie] }}, {{ libellePriorite[l.priorite] | lowercase }})</span>
                      @if (l.justification) { <br /><span class="doux petit-texte">{{ l.justification }}</span> }
                    </td>
                    <td class="nombre">{{ quantite(l.quantite) }} {{ l.unite }}</td>
                    <td class="petit-texte">
                      @for (a of l.arbitrages; track a.instance) { <div>{{ a.instance }} : {{ quantite(a.quantite) }}@if (a.observation) { — {{ a.observation }} }</div> }
                    </td>
                    @if (r.peutArbitrer) {
                      <td class="nombre"><input type="number" min="0" step="any" [max]="l.maximum" [attr.aria-label]="'Quantité retenue : ' + l.designation" [(ngModel)]="arbitrages[l.id].quantite" /> <span class="doux petit-texte">≤ {{ quantite(l.maximum) }}</span></td>
                      <td><input type="text" maxlength="300" [attr.aria-label]="'Observation : ' + l.designation" [(ngModel)]="arbitrages[l.id].observation" /></td>
                    } @else {
                      <td class="nombre"><strong>{{ quantite(l.retenue) }}</strong></td>
                      <td class="petit-texte">{{ l.observation ?? '' }}</td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <div class="actions-ligne">
            @if (r.peutArbitrer) {
              <button type="button" class="bouton" [disabled]="action.enCours()" (click)="arbitrer(r)">Enregistrer l'arbitrage</button>
            }
            @if (r.peutRenvoyer && !renvoiRemontee()) {
              <button type="button" class="bouton danger" (click)="renvoiRemontee.set(true)">Renvoyer à l'établissement</button>
            }
          </div>
          @if (renvoiRemontee()) {
            <form class="sous-formulaire" (ngSubmit)="renvoyerRemontee(r)">
              <div class="champ"><label for="motifRemontee">Motif du renvoi</label><textarea id="motifRemontee" name="motifRemontee" rows="2" maxlength="500" required [(ngModel)]="motif"></textarea></div>
              <div class="actions-ligne">
                <button type="submit" class="bouton" [disabled]="action.enCours() || !motif.trim()">Renvoyer pour correction</button>
                <button type="button" class="bouton secondaire" (click)="renvoiRemontee.set(false)">Annuler</button>
              </div>
            </form>
          }
        </section>
      }
    </div>
  `,
  styles: [STYLES_MOYENS],
})
export class BesoinsPilotagePage implements OnInit {
  private readonly api = inject(MoyensApi);
  private readonly territoire = inject(TerritoireApi);
  private readonly route = inject(ActivatedRoute);
  private readonly session = inject(SessionService);
  protected readonly superAdmin = this.session.profil()?.superAdmin === true;

  protected readonly action = new Action();
  protected readonly export = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly pays = signal<PaysVue[]>([]);
  protected readonly paysId = signal<string | null>(null);
  protected readonly directionId = signal<string | null>(null);
  protected readonly annee = signal<string | null>(null);
  protected readonly vue = signal<VueBesoins | null>(null);
  protected readonly onglet = signal<'etablissements' | 'directions' | 'consolidation'>('etablissements');
  protected readonly remontee = signal<RemonteeVue | null>(null);
  protected readonly renvoiRemontee = signal(false);
  protected readonly renvoiDirection = signal<DirectionBesoins | null>(null);
  protected arbitrages: Record<string, Arbitrage> = {};
  protected motif = '';
  protected observations = '';

  protected readonly libelleCategorie = LIBELLE_CATEGORIE;
  protected readonly libellePriorite = LIBELLE_PRIORITE;
  protected readonly libelleEtab = LIBELLE_STATUT_ETABLISSEMENT;
  protected readonly classeEtab = CLASSE_STATUT_ETABLISSEMENT;
  protected readonly libelleTransmission = LIBELLE_STATUT_TRANSMISSION;
  protected readonly quantite = quantite;
  protected readonly dateHeure = dateHeureCourte;

  protected readonly recus = computed(() => (this.vue()?.etablissements ?? []).filter((e) => e.statut === 'RECU').length);
  protected readonly transmises = computed(() => (this.vue()?.directions ?? []).filter((d) => d.statut === 'TRANSMISE').length);
  protected readonly consolidation = computed(() => {
    const lignes = this.vue()?.consolidation ?? [];
    return CATEGORIES.map((categorie) => ({ categorie, lignes: lignes.filter((l) => l.categorie === categorie) })).filter(
      (g) => g.lignes.length,
    );
  });

  async ngOnInit(): Promise<void> {
    const q = this.route.snapshot.queryParamMap;
    this.directionId.set(q.get('direction'));
    this.annee.set(q.get('annee'));
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
    const v = await this.action.executer(() =>
      this.api.besoins({ direction: this.directionId(), pays: this.directionId() ? null : this.paysId(), annee: this.annee() }),
    );
    if (v) {
      this.vue.set(v);
      this.annee.set(v.annee);
      if (this.onglet() === 'directions' && !v.directions.length) {
        this.onglet.set('etablissements');
      }
    }
  }

  protected async choisirPays(id: string): Promise<void> {
    this.paysId.set(id || null);
    this.directionId.set(null);
    this.remontee.set(null);
    await this.charger();
  }

  protected async choisirAnnee(annee: string): Promise<void> {
    this.annee.set(annee || null);
    this.remontee.set(null);
    await this.charger();
  }

  protected async ouvrirDirection(id: string | null): Promise<void> {
    this.directionId.set(id);
    this.remontee.set(null);
    this.renvoiDirection.set(null);
    await this.charger();
  }

  protected async ouvrirRemontee(e: EtablissementBesoins): Promise<void> {
    const r = await this.action.executer(() => this.api.remontee(e.remonteeId!, this.directionId()));
    if (r) {
      this.afficherRemontee(r);
    }
  }

  private afficherRemontee(r: RemonteeVue): void {
    this.arbitrages = {};
    for (const l of r.lignes) {
      this.arbitrages[l.id] = { quantite: l.retenue, observation: l.observation ?? '' };
    }
    this.renvoiRemontee.set(false);
    this.motif = '';
    this.remontee.set(r);
  }

  protected async arbitrer(r: RemonteeVue): Promise<void> {
    const lignes = r.lignes.map((l) => {
      const a = this.arbitrages[l.id];
      const q = a.quantite === null || (a.quantite as unknown) === '' ? null : Number(a.quantite);
      return { ligneId: l.id, quantiteRetenue: q, observation: a.observation.trim() || null };
    });
    const v = await this.action.executer(() => this.api.arbitrer(r.id, lignes));
    if (v) {
      this.afficherRemontee(v);
      this.message.set(`Arbitrage de ${v.etablissement} enregistré.`);
      await this.charger();
    }
  }

  protected async renvoyerRemontee(r: RemonteeVue): Promise<void> {
    const v = await this.action.executer(() => this.api.renvoyerRemontee(r.id, this.motif.trim()));
    if (v) {
      this.remontee.set(null);
      this.message.set(`État de ${v.etablissement} renvoyé pour correction.`);
      await this.charger();
    }
  }

  protected async renvoyerDirection(d: DirectionBesoins): Promise<void> {
    const v = await this.action.executer(() => this.api.renvoyerDirection(d.id, this.vue()!.annee, this.motif.trim()));
    if (v) {
      this.vue.set(v);
      this.renvoiDirection.set(null);
      this.motif = '';
      this.message.set(`Transmission de ${d.nom} renvoyée.`);
    }
  }

  protected async transmettre(): Promise<void> {
    const v0 = this.vue();
    if (!v0) {
      return;
    }
    const v = await this.action.executer(() => this.api.transmettreBesoins(v0.annee, this.observations.trim() || null));
    if (v) {
      this.vue.set(v);
      this.observations = '';
      this.message.set(`Besoins ${v.annee} transmis à ${v.destination}.`);
    }
  }

  protected async exporter(format: 'xlsx' | 'pdf'): Promise<void> {
    const v = this.vue();
    const f = await this.export.executer(() =>
      this.api.exporterBesoins({ direction: this.directionId(), pays: this.directionId() ? null : this.paysId(), annee: v?.annee }, format),
    );
    if (f) {
      enregistrerFichier(f, `besoins-${v?.annee ?? ''}.${format}`);
    }
  }
}
