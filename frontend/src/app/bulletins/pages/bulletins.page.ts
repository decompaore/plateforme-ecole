import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { dateCourte, dateHeureCourte, dateLocale } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { AdminNavComponent } from '../../admin/admin-nav.component';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { ClasseVue, PeriodeVue } from '../../admin/modeles-admin';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import { BulletinsApi } from '../bulletins-api.service';
import { AvisVue, Distinction, DISTINCTIONS, GenerationVue, LIBELLE_DISTINCTION, rang, resultat } from '../modeles-bulletins';

/** Ligne du conseil de classe en cours de saisie. */
interface LigneConseil {
  avis: AvisVue;
  distinction: Distinction | '';
  appreciation: string;
}

type Confirmation = 'verrouiller' | 'deverrouiller' | 'publier' | null;

/** Période proposée par défaut : la dernière commencée (la période en cours ou la précédente). */
export function periodeParDefaut(periodes: PeriodeVue[], aujourdhui = dateLocale()): PeriodeVue | null {
  const triees = [...periodes].sort((a, b) => a.ordre - b.ordre);
  const commencees = triees.filter((p) => p.debut <= aujourdhui);
  return commencees[commencees.length - 1] ?? triees[0] ?? null;
}

/**
 * Bulletins d'une classe pour une période (v0.43) : résultats et conseil de classe, verrouillage de la période,
 * génération, impression et publication aux familles. Le censeur et l'administrateur conduisent le conseil ;
 * le secrétariat consulte et imprime les bulletins générés.
 */
@Component({
  selector: 'app-bulletins',
  imports: [AdminNavComponent, FormsModule, RouterLink],
  template: `
    <div class="page large">
      <h1>Bulletins</h1>
      <app-admin-nav />

      <div class="filtres">
        <div class="champ">
          <label for="classe">Classe</label>
          <select id="classe" [value]="classeId()" (change)="choisirClasse($any($event.target).value)">
            @for (c of classes(); track c.id) {
              <option [value]="c.id">{{ c.code }}</option>
            }
          </select>
        </div>
        <div class="champ">
          <label for="periode">Période</label>
          <select id="periode" [value]="periodeId()" (change)="choisirPeriode($any($event.target).value)">
            @for (p of periodesClasse(); track p.id) {
              <option [value]="p.id">{{ p.libelle }}@if (p.verrouillee) { (verrouillée) }</option>
            }
          </select>
        </div>
      </div>

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message()) {
        <div class="alerte succes" role="status">{{ message() }}</div>
      }

      @if (charge() && classes().length === 0) {
        <div class="carte"><p class="doux">Aucune classe pour cette année.</p></div>
      } @else if (charge() && !periode()) {
        <div class="carte">
          <p class="doux">
            Les périodes de cette classe n'ont pas encore été créées (Année scolaire → Générer les périodes).
          </p>
        </div>
      }

      @if (periode(); as p) {
        <!-- 1. Période -->
        <section class="carte">
          <div class="entete-section">
            <h2>{{ p.libelle }} <span class="doux">du {{ dateCourte(p.debut) }} au {{ dateCourte(p.fin) }}</span></h2>
            @if (p.verrouillee) {
              <span class="pastille present">verrouillée</span>
            } @else {
              <span class="pastille">saisies ouvertes</span>
            }
          </div>
          @if (!p.verrouillee) {
            <p class="doux">
              Avant le conseil, vérifiez que les enseignants ont saisi leurs notes
              (<a routerLink="/admin/evaluations">suivi des évaluations</a>) et leurs appréciations. Le verrouillage
              fige les notes de la période <strong>pour toutes les classes</strong> de ce profil : il est nécessaire
              pour générer les bulletins.
            </p>
          } @else if (!publie()) {
            <p class="doux">Les notes sont figées. Une correction de dernière minute demande de déverrouiller la période.</p>
          }
          @if (direction() && !publie()) {
            @if (confirmation() === 'verrouiller' || confirmation() === 'deverrouiller') {
              <div class="confirmation">
                <span>
                  {{ confirmation() === 'verrouiller'
                    ? 'Verrouiller ' + p.libelle + ' ? Les enseignants ne pourront plus modifier leurs notes.'
                    : 'Déverrouiller ' + p.libelle + ' ? Les notes redeviennent modifiables ; il faudra régénérer les bulletins.' }}
                </span>
                <button type="button" class="bouton secondaire petit" (click)="confirmation.set(null)">Annuler</button>
                <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="basculerVerrou()">Confirmer</button>
              </div>
            } @else if (p.verrouillee) {
              <button type="button" class="bouton secondaire petit" (click)="confirmation.set('deverrouiller')">Déverrouiller la période</button>
            } @else {
              <button type="button" class="bouton petit" (click)="confirmation.set('verrouiller')">Verrouiller la période</button>
            }
          }
        </section>

        <!-- 2. Conseil de classe -->
        @if (direction()) {
          <section class="carte">
            <div class="entete-section">
              <h2>Conseil de classe</h2>
              <span class="doux">{{ lignes().length }} élève(s)</span>
            </div>
            @if (lignes().length === 0) {
              <p class="doux">Aucun élève inscrit dans cette classe.</p>
            } @else {
              <p class="doux">
                La distinction proposée découle des seuils fixés par l'établissement ; le conseil peut en retenir une autre et
                rédiger l'appréciation générale (300 caractères au plus). Elles figurent sur le bulletin.
              </p>
              <div class="tableau-defilant">
                <table class="tableau conseil">
                  <thead>
                    <tr>
                      <th class="nombre">Rang</th>
                      <th>Élève</th>
                      <th class="nombre">{{ competences() ? 'Maîtrise' : 'Moyenne' }}</th>
                      <th>Proposée</th>
                      <th>Distinction retenue</th>
                      <th>Appréciation générale</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (l of lignes(); track l.avis.inscriptionId; let i = $index) {
                      <tr>
                        <td class="nombre">{{ rang(l.avis.rang) }}</td>
                        <td><strong>{{ l.avis.nom }}</strong> {{ l.avis.prenoms }}</td>
                        <td class="nombre">{{ resultat(l.avis.moyenne, l.avis.tauxMaitrise) }}</td>
                        <td class="doux">{{ l.avis.distinctionProposee ? libelles[l.avis.distinctionProposee] : '—' }}</td>
                        <td>
                          <select [attr.aria-label]="'Distinction de ' + l.avis.nom" [name]="'distinction' + i" [(ngModel)]="l.distinction" [disabled]="publie()">
                            <option value="">Proposée</option>
                            @for (d of distinctions; track d) { <option [value]="d">{{ libelles[d] }}</option> }
                          </select>
                        </td>
                        <td>
                          <input
                            [attr.aria-label]="'Appréciation de ' + l.avis.nom"
                            [name]="'appreciation' + i"
                            maxlength="300"
                            [(ngModel)]="l.appreciation"
                            [disabled]="publie()"
                          />
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
              @if (!publie()) {
                <button type="button" class="bouton" [disabled]="action.enCours()" (click)="enregistrerConseil()">Enregistrer le conseil</button>
              }
            }
          </section>
        }

        <!-- 3. Bulletins -->
        <section class="carte">
          <div class="entete-section">
            <h2>Bulletins</h2>
            @if (generation(); as g) {
              <span class="pastille" [class.present]="g.statut === 'PUBLIEE'">{{ g.statut === 'PUBLIEE' ? 'publiés' : 'générés, non publiés' }}</span>
            }
          </div>

          @if (generation(); as g) {
            <p class="doux">
              Générés le {{ dateHeureCourte(g.genereLe) }}@if (g.publieLe) {, publiés le {{ dateHeureCourte(g.publieLe) }} : visibles
              dans l'espace parent, les familles ont été prévenues par SMS}.
              @if (g.moyenneClasse !== null) { Moyenne de la classe : {{ resultat(g.moyenneClasse, null) }}. }
              @if (g.tauxReussite !== null) { Réussite : {{ resultat(null, g.tauxReussite) }}. }
            </p>
            <div class="actions">
              <button type="button" class="bouton secondaire" [disabled]="action.enCours()" (click)="imprimerClasse()">Imprimer toute la classe (PDF)</button>
              @if (direction() && g.statut === 'GENEREE') {
                <button type="button" class="bouton secondaire" [disabled]="action.enCours() || !p.verrouillee" (click)="generer()">Régénérer</button>
                @if (confirmation() === 'publier') {
                  <span class="confirmation">
                    <span>Publier ? Les familles reçoivent un SMS ; les bulletins ne pourront plus être modifiés.</span>
                    <button type="button" class="bouton secondaire petit" (click)="confirmation.set(null)">Annuler</button>
                    <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="publier()">Confirmer la publication</button>
                  </span>
                } @else {
                  <button type="button" class="bouton" [disabled]="action.enCours()" (click)="confirmation.set('publier')">Publier aux familles</button>
                }
              }
            </div>
            <div class="tableau-defilant">
              <table class="tableau">
                <thead>
                  <tr>
                    <th class="nombre">Rang</th>
                    <th>Élève</th>
                    <th class="nombre">{{ competences() ? 'Maîtrise' : 'Moyenne' }}</th>
                    <th>Distinction</th>
                    <th>Code de vérification</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  @for (b of g.bulletins; track b.id) {
                    <tr>
                      <td class="nombre">{{ rang(b.rang) }}</td>
                      <td><strong>{{ b.nom }}</strong> {{ b.prenoms }} <span class="doux">{{ b.matricule }}</span></td>
                      <td class="nombre">{{ resultat(b.moyenne, b.tauxMaitrise) }}</td>
                      <td>{{ b.distinction && b.distinction !== 'AUCUNE' ? libelles[b.distinction] : '' }}</td>
                      <td class="code">{{ b.codeVerification }}</td>
                      <td><button type="button" class="bouton discret petit" [disabled]="action.enCours()" (click)="imprimer(b.id, b.nom)">PDF</button></td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          } @else if (charge()) {
            <p class="doux">Les bulletins de {{ p.libelle }} n'ont pas encore été générés.</p>
            @if (direction()) {
              @if (p.verrouillee) {
                <button type="button" class="bouton" [disabled]="action.enCours() || lignes().length === 0" (click)="generer()">Générer les bulletins</button>
              } @else {
                <p class="doux">Verrouillez d'abord la période : les notes ne doivent plus changer.</p>
              }
            }
          }
        </section>
      }
    </div>
  `,
  styles: `
    .filtres {
      display: flex;
      flex-wrap: wrap;
      gap: 0 1rem;
      .champ {
        flex: 0 1 16rem;
        min-width: 0;
      }
    }
    .entete-section {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: baseline;
      gap: 0 1rem;
      h2 {
        font-size: 1.05rem;
        margin: 0 0 0.5rem;
      }
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
      margin: 0.5rem 0 0.75rem;
    }
    .confirmation {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
      padding: 0.5rem 0.75rem;
      border-radius: var(--rayon);
      background: var(--absent-fond);
    }
    table {
      font-variant-numeric: tabular-nums;
    }
    .conseil select,
    .conseil input {
      min-height: 36px;
      padding: 0.25rem 0.5rem;
    }
    .conseil input {
      min-width: 16rem;
    }
    .code {
      font-family: monospace;
    }
  `,
})
export class BulletinsPage {
  private readonly admin = inject(AdminApi);
  private readonly api = inject(BulletinsApi);
  private readonly session = inject(SessionService);
  protected readonly annee = inject(AnneeCourante);

  protected readonly action = new Action();
  protected readonly libelles = LIBELLE_DISTINCTION;
  protected readonly distinctions = DISTINCTIONS;
  protected readonly dateCourte = dateCourte;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly resultat = resultat;
  protected readonly rang = rang;

  protected readonly classes = signal<ClasseVue[]>([]);
  private readonly periodes = signal<PeriodeVue[]>([]);
  protected readonly classeId = signal('');
  protected readonly periodeId = signal('');
  protected readonly lignes = signal<LigneConseil[]>([]);
  protected readonly generation = signal<GenerationVue | null>(null);
  protected readonly charge = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly confirmation = signal<Confirmation>(null);

  /** Le censeur et l'administrateur conduisent le conseil ; le secrétariat imprime. */
  protected readonly direction = computed(() => this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR'));
  protected readonly classe = computed(() => this.classes().find((c) => c.id === this.classeId()) ?? null);
  protected readonly periodesClasse = computed(() =>
    this.periodes()
      .filter((p) => p.profilId === this.classe()?.profilId)
      .sort((a, b) => a.ordre - b.ordre),
  );
  protected readonly periode = computed(() => this.periodesClasse().find((p) => p.id === this.periodeId()) ?? null);
  protected readonly publie = computed(() => this.generation()?.statut === 'PUBLIEE');
  /** Profil en compétences : taux de maîtrise au lieu de la moyenne. */
  protected readonly competences = computed(() => {
    const l = this.lignes();
    return l.length > 0 && l.every((x) => x.avis.moyenne === null) && l.some((x) => x.avis.tauxMaitrise !== null);
  });

  constructor() {
    effect(() => {
      const a = this.annee.annee();
      untracked(() => {
        if (a) {
          void this.chargerAnnee(a.id);
        }
      });
    });
    void this.annee.charger().catch(() => undefined);
  }

  private async chargerAnnee(anneeId: string): Promise<void> {
    await this.action.executer(async () => {
      const [classes, periodes] = await Promise.all([this.admin.classes(anneeId), this.admin.periodes(anneeId)]);
      this.classes.set([...classes].sort((a, b) => a.niveau.localeCompare(b.niveau, 'fr') || a.code.localeCompare(b.code, 'fr')));
      this.periodes.set(periodes);
      const classe = this.classes().find((c) => c.id === this.classeId()) ?? this.classes()[0];
      if (classe) {
        await this.choisirClasse(classe.id);
      } else {
        this.charge.set(true);
      }
    });
  }

  protected async choisirClasse(id: string): Promise<void> {
    this.classeId.set(id);
    const actuelle = this.periodesClasse().find((p) => p.id === this.periodeId());
    const p = actuelle ?? periodeParDefaut(this.periodesClasse());
    await this.choisirPeriode(p?.id ?? '');
  }

  protected async choisirPeriode(id: string): Promise<void> {
    this.periodeId.set(id);
    this.confirmation.set(null);
    this.message.set(null);
    this.lignes.set([]);
    this.generation.set(null);
    if (!id) {
      this.charge.set(true);
      return;
    }
    await this.recharger();
  }

  private async recharger(): Promise<void> {
    const classeId = this.classeId();
    const periodeId = this.periodeId();
    await this.action.executer(async () => {
      const [avis, generation] = await Promise.all([
        this.direction() ? this.api.conseil(classeId, periodeId) : Promise.resolve([] as AvisVue[]),
        this.api.bulletins(classeId, periodeId),
      ]);
      if (classeId !== this.classeId() || periodeId !== this.periodeId()) {
        return;
      }
      this.lignes.set(avis.map((a) => ({ avis: a, distinction: a.distinction ?? '', appreciation: a.appreciation ?? '' })));
      this.generation.set(generation);
      this.charge.set(true);
    });
  }

  protected async basculerVerrou(): Promise<void> {
    const p = this.periode();
    const quoi = this.confirmation();
    if (!p || (quoi !== 'verrouiller' && quoi !== 'deverrouiller')) {
      return;
    }
    const maj = await this.action.executer(() => (quoi === 'verrouiller' ? this.api.verrouiller(p.id) : this.api.deverrouiller(p.id)));
    this.confirmation.set(null);
    if (maj) {
      this.periodes.set(this.periodes().map((x) => (x.id === maj.id ? maj : x)));
      this.message.set(maj.verrouillee ? `${maj.libelle} est verrouillée : vous pouvez générer les bulletins.` : `${maj.libelle} est de nouveau ouverte aux saisies.`);
    }
  }

  protected async enregistrerConseil(): Promise<void> {
    const avis = this.lignes().map((l) => ({
      inscriptionId: l.avis.inscriptionId,
      distinction: l.distinction || null,
      appreciation: l.appreciation.trim() || null,
    }));
    const r = await this.action.executer(() => this.api.enregistrerConseil(this.classeId(), this.periodeId(), avis));
    if (r) {
      this.lignes.set(r.map((a) => ({ avis: a, distinction: a.distinction ?? '', appreciation: a.appreciation ?? '' })));
      this.message.set('Avis du conseil enregistrés.');
    }
  }

  protected async generer(): Promise<void> {
    const g = await this.action.executer(() => this.api.generer(this.classeId(), this.periodeId()));
    if (g) {
      this.generation.set(g);
      this.message.set(`${g.bulletins.length} bulletin(s) générés : relisez-les (PDF), puis publiez-les aux familles.`);
    }
  }

  protected async publier(): Promise<void> {
    const g = await this.action.executer(() => this.api.publier(this.classeId(), this.periodeId()));
    this.confirmation.set(null);
    if (g) {
      this.generation.set(g);
      this.message.set('Bulletins publiés : les familles les voient dans leur espace et ont été prévenues par SMS.');
    }
  }

  protected async imprimerClasse(): Promise<void> {
    const pdf = await this.action.executer(() => this.api.pdfClasse(this.classeId(), this.periodeId()));
    if (pdf) {
      enregistrerFichier(pdf, `bulletins-${this.classe()?.code ?? 'classe'}-${this.periode()?.libelle ?? ''}.pdf`.replace(/\s+/g, '-'));
    }
  }

  protected async imprimer(bulletinId: string, nom: string): Promise<void> {
    const pdf = await this.action.executer(() => this.api.pdf(bulletinId));
    if (pdf) {
      enregistrerFichier(pdf, `bulletin-${nom}-${this.periode()?.libelle ?? ''}.pdf`.replace(/\s+/g, '-'));
    }
  }
}
