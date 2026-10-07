import { LowerCasePipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../action';
import { DirectionVue, DonneesPays, MinistereVue, PaysVue, RapportImportDirections } from '../modeles-territoire';
import { PlateformeNavComponent } from '../plateforme-nav.component';
import { TerritoireApi } from '../territoire-api.service';

/** Direction affichée dans l'arbre (ordre parent → enfants). */
interface Noeud extends DirectionVue {
  profondeur: number;
}

/** Ordonne les directions en arbre : chaque direction suivie de celles qui en dépendent, par nom. */
export function arbre(directions: DirectionVue[]): Noeud[] {
  const enfants = new Map<string | null, DirectionVue[]>();
  for (const d of directions) {
    const cle = d.parentId ?? null;
    enfants.set(cle, [...(enfants.get(cle) ?? []), d]);
  }
  const resultat: Noeud[] = [];
  const parcourir = (parent: string | null, profondeur: number) => {
    for (const d of [...(enfants.get(parent) ?? [])].sort((a, b) => a.nom.localeCompare(b.nom))) {
      resultat.push({ ...d, profondeur });
      parcourir(d.id, profondeur + 1);
    }
  };
  parcourir(null, 0);
  return resultat;
}

const PAYS_VIDE: DonneesPays = {
  code: '',
  nom: '',
  deviseNationale: '',
  indicatifTelephone: '+',
  longueurNumero: 8,
  fuseauHoraire: 'Africa/',
  monnaie: 'XOF',
  langue: 'fr',
};

/**
 * Super administrateur : pays, ministères de tutelle (avec les noms de leurs niveaux de
 * directions) et arbre des directions, saisi à la main ou importé d'un fichier CSV.
 */
@Component({
  selector: 'app-territoire',
  imports: [FormsModule, LowerCasePipe, PlateformeNavComponent],
  template: `
    <div class="page large">
      <h1>Territoire</h1>
      <app-plateforme-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message()) {
        <div class="alerte succes" role="status">{{ message() }}</div>
      }

      <!-- ===================== Pays ===================== -->
      <section class="carte">
        <div class="entete-section">
          <h2>Pays</h2>
          <button type="button" class="bouton secondaire petit" (click)="nouveauPays()">Nouveau pays</button>
        </div>
        @if (pays().length) {
          <div class="choix" role="group" aria-label="Pays">
            @for (p of pays(); track p.id) {
              <button type="button" class="bouton petit" [class.secondaire]="paysId() !== p.id" [attr.aria-pressed]="paysId() === p.id"
                (click)="choisirPays(p.id)">{{ p.nom }}</button>
            }
          </div>
        }
        @if (paysChoisi(); as p) {
          <p class="doux">
            Indicatif {{ p.indicatifTelephone }} ({{ p.longueurNumero }} chiffres) · {{ p.fuseauHoraire }} · {{ p.monnaie }} ·
            {{ p.ministeres }} ministère(s) · {{ p.etablissements }} établissement(s) rattaché(s)
            @if (p.deviseNationale) { · devise : « {{ p.deviseNationale }} » }
            <button type="button" class="bouton discret petit" (click)="modifierPays(p)">Modifier</button>
          </p>
        }
        @if (formPays(); as f) {
          <form class="formulaire" (ngSubmit)="enregistrerPays()">
            <div class="grille-champs">
              <div class="champ"><label for="p-code">Code ISO (2 lettres)</label><input id="p-code" name="code" maxlength="2" required [(ngModel)]="f.code" /></div>
              <div class="champ"><label for="p-nom">Nom</label><input id="p-nom" name="nom" maxlength="100" required [(ngModel)]="f.nom" /></div>
              <div class="champ large"><label for="p-devise">Devise nationale (en-tête des documents)</label><input id="p-devise" name="devise" maxlength="200" [(ngModel)]="f.deviseNationale" /></div>
              <div class="champ"><label for="p-ind">Indicatif téléphonique</label><input id="p-ind" name="indicatif" maxlength="5" required [(ngModel)]="f.indicatifTelephone" /></div>
              <div class="champ"><label for="p-long">Chiffres d'un numéro national</label><input id="p-long" name="longueur" type="number" min="6" max="12" required [(ngModel)]="f.longueurNumero" /></div>
              <div class="champ"><label for="p-fuseau">Fuseau horaire</label><input id="p-fuseau" name="fuseau" required [(ngModel)]="f.fuseauHoraire" /></div>
              <div class="champ"><label for="p-monnaie">Monnaie (ISO, ex. XOF)</label><input id="p-monnaie" name="monnaie" maxlength="3" required [(ngModel)]="f.monnaie" /></div>
              <div class="champ"><label for="p-langue">Langue</label><input id="p-langue" name="langue" maxlength="10" [(ngModel)]="f.langue" /></div>
            </div>
            <div class="actions-ligne">
              <button type="submit" class="bouton petit" [disabled]="action.enCours()">Enregistrer le pays</button>
              <button type="button" class="bouton discret petit" (click)="formPays.set(null)">Annuler</button>
            </div>
          </form>
        }
      </section>

      <!-- ===================== Ministères ===================== -->
      @if (paysChoisi(); as p) {
        <section class="carte">
          <div class="entete-section">
            <h2>Ministères de tutelle — {{ p.nom }}</h2>
            <button type="button" class="bouton secondaire petit" (click)="nouveauMinistere()">Nouveau ministère</button>
          </div>
          <ul class="liste">
            @for (m of ministeres(); track m.id) {
              <li [class.choisi]="ministereId() === m.id">
                <span>
                  <strong>{{ m.sigle }}</strong> — {{ m.nom }}
                  @if (!m.actif) { <span class="pastille absent">désactivé</span> }
                  <br /><span class="doux">{{ m.niveaux.join(' › ') }} · {{ m.directions }} direction(s) · {{ m.etablissements }} établissement(s)</span>
                </span>
                <span class="actions-ligne">
                  <button type="button" class="bouton petit" [class.secondaire]="ministereId() !== m.id" (click)="choisirMinistere(m.id)">Directions</button>
                  <button type="button" class="bouton discret petit" (click)="modifierMinistere(m)">Modifier</button>
                </span>
              </li>
            } @empty {
              <li class="doux">Aucun ministère pour ce pays.</li>
            }
          </ul>
          @if (formMinistere(); as f) {
            <form class="formulaire" (ngSubmit)="enregistrerMinistere()">
              <div class="grille-champs">
                <div class="champ"><label for="m-sigle">Sigle</label><input id="m-sigle" name="sigle" maxlength="30" required [(ngModel)]="f.sigle" /></div>
                <div class="champ large"><label for="m-nom">Nom complet (tel qu'il figure sur les documents)</label><input id="m-nom" name="nom" maxlength="250" required [(ngModel)]="f.nom" /></div>
              </div>
              <fieldset>
                <legend>Niveaux de directions, du plus haut au plus proche des établissements</legend>
                @for (n of f.niveaux; track $index; let i = $index) {
                  <div class="niveau">
                    <label [for]="'m-niv-' + i">Niveau {{ i + 1 }}</label>
                    <input [id]="'m-niv-' + i" [name]="'niveau' + i" maxlength="80" required [(ngModel)]="f.niveaux[i]" />
                    @if (f.niveaux.length > 1 && !niveauxFiges()) {
                      <button type="button" class="bouton discret petit" (click)="retirerNiveau(i)">Retirer</button>
                    }
                  </div>
                }
                @if (niveauxFiges()) {
                  <p class="doux">Ce ministère a déjà des directions : le nombre de niveaux est figé, leurs noms restent modifiables.</p>
                } @else if (f.niveaux.length < 5) {
                  <button type="button" class="bouton discret petit" (click)="ajouterNiveau()">Ajouter un niveau</button>
                }
              </fieldset>
              <label class="case"><input type="checkbox" name="actif" [(ngModel)]="f.actif" /> Actif</label>
              <div class="actions-ligne">
                <button type="submit" class="bouton petit" [disabled]="action.enCours()">Enregistrer le ministère</button>
                <button type="button" class="bouton discret petit" (click)="formMinistere.set(null)">Annuler</button>
              </div>
            </form>
          }
        </section>
      }

      <!-- ===================== Directions ===================== -->
      @if (ministereChoisi(); as m) {
        <section class="carte">
          <div class="entete-section">
            <h2>Directions — {{ m.sigle }}</h2>
            <button type="button" class="bouton secondaire petit" (click)="nouvelleDirection(null)">Nouvelle {{ libelleNiveau(1) | lowercase }}</button>
          </div>
          @if (formDirection(); as f) {
            <form class="formulaire" (ngSubmit)="enregistrerDirection()">
              <div class="grille-champs">
                @if (rangForm() > 1) {
                  <div class="champ large">
                    <label for="d-parent">{{ libelleNiveau(rangForm() - 1) }} de rattachement</label>
                    <select id="d-parent" name="parent" required [(ngModel)]="f.parentId">
                      @for (p of parentsPossibles(); track p.id) { <option [ngValue]="p.id">{{ p.nom }}</option> }
                    </select>
                  </div>
                }
                <div class="champ"><label for="d-code">Code</label><input id="d-code" name="code" maxlength="30" required [(ngModel)]="f.code" /></div>
                <div class="champ large"><label for="d-nom">Nom complet (tel qu'il figure sur les documents)</label><input id="d-nom" name="nom" maxlength="200" required [(ngModel)]="f.nom" /></div>
              </div>
              <label class="case"><input type="checkbox" name="actif" [(ngModel)]="f.actif" /> Active</label>
              <div class="actions-ligne">
                <button type="submit" class="bouton petit" [disabled]="action.enCours()">Enregistrer ({{ libelleNiveau(rangForm()) | lowercase }})</button>
                <button type="button" class="bouton discret petit" (click)="formDirection.set(null)">Annuler</button>
              </div>
            </form>
          }
          <ul class="liste arbre">
            @for (d of noeuds(); track d.id) {
              <li [style.padding-left.rem]="0.5 + d.profondeur * 1.5">
                <span>
                  <span class="doux">{{ libelleNiveau(d.rang) }} ·&nbsp;</span><strong>{{ d.nom }}</strong><span class="doux">&nbsp;({{ d.code }})</span>
                  @if (!d.actif) { <span class="pastille absent">désactivée</span> }
                  @if (d.etablissements) { <span class="pastille">{{ d.etablissements }} établissement(s)</span> }
                </span>
                <span class="actions-ligne">
                  @if (d.rang < m.niveaux.length) {
                    <button type="button" class="bouton discret petit" (click)="nouvelleDirection(d)">Ajouter dessous</button>
                  }
                  <button type="button" class="bouton discret petit" (click)="modifierDirection(d)">Modifier</button>
                </span>
              </li>
            } @empty {
              <li class="doux">Aucune direction : créez-les une à une ou importez un fichier.</li>
            }
          </ul>

          <h3>Importer un fichier</h3>
          <p class="doux">
            Fichier CSV (dans Excel : « Enregistrer sous » → CSV), une direction par ligne :
            <code>code;nom;code_parent</code>. Le code parent est vide pour une {{ libelleNiveau(1) | lowercase }}. Une direction
            déjà présente (même code) est mise à jour. Rien n'est enregistré s'il y a une erreur.
          </p>
          <div class="import">
            <input type="file" accept=".csv,.txt,text/csv,text/plain" aria-label="Fichier CSV des directions" (change)="lireFichier($event)" />
            <label class="visuellement-cache" for="d-csv">Contenu CSV</label>
            <textarea id="d-csv" rows="5" placeholder="DR-CEN;Direction régionale du Centre;&#10;DP-KAD;Direction provinciale du Kadiogo;DR-CEN"
              [ngModel]="csv()" (ngModelChange)="csv.set($event); rapport.set(null)"></textarea>
            <div class="actions-ligne">
              <button type="button" class="bouton secondaire petit" [disabled]="!csv().trim() || action.enCours()" (click)="importer(true)">Vérifier</button>
              <button type="button" class="bouton petit" [disabled]="!rapportValide() || action.enCours()" (click)="importer(false)">Importer</button>
            </div>
          </div>
          @if (rapport(); as r) {
            <div class="alerte" [class.erreur]="r.erreurs.length" [class.succes]="!r.erreurs.length" role="status">
              {{ r.simulation ? 'Vérification' : 'Import' }} : {{ r.lignes }} ligne(s) · {{ r.creees }} à créer · {{ r.modifiees }} à modifier ·
              {{ r.inchangees }} inchangée(s)
              @if (r.erreurs.length) {
                <ul>
                  @for (e of r.erreurs; track $index) { <li>Ligne {{ e.ligne }} : {{ e.message }}</li> }
                </ul>
              }
            </div>
          }
        </section>
      }
    </div>
  `,
  styles: `
    h2 {
      font-size: 1.05rem;
      margin: 0;
    }
    h3 {
      font-size: 1rem;
      margin: 1.25rem 0 0.25rem;
    }
    .choix {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      margin: 0.75rem 0 0.5rem;
    }
    .formulaire {
      border-top: 1px solid var(--bordure);
      margin-top: 0.75rem;
      padding-top: 0.75rem;
    }
    .champ.large {
      grid-column: span 2;
    }
    fieldset {
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      margin: 0 0 0.75rem;
      padding: 0.5rem 0.75rem;
    }
    .niveau {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 0.4rem;
      label {
        min-width: 5rem;
      }
      input {
        flex: 1;
      }
    }
    .case {
      display: inline-flex;
      align-items: center;
      gap: 0.4rem;
      margin-bottom: 0.75rem;
      input {
        width: 1.2rem;
        min-height: 1.2rem;
        height: 1.2rem;
      }
    }
    .liste li {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 0.4rem;
    }
    li.choisi {
      background: var(--surface);
    }
    .import {
      display: grid;
      gap: 0.5rem;
      textarea {
        width: 100%;
        font-family: monospace;
      }
    }
    @media (max-width: 40rem) {
      .champ.large {
        grid-column: auto;
      }
    }
  `,
})
export class TerritoirePage implements OnInit {
  private readonly api = inject(TerritoireApi);

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);

  protected readonly pays = signal<PaysVue[]>([]);
  protected readonly paysId = signal<string | null>(null);
  protected readonly paysChoisi = computed(() => this.pays().find((p) => p.id === this.paysId()) ?? null);
  protected readonly formPays = signal<(DonneesPays & { id?: string }) | null>(null);

  protected readonly ministeres = signal<MinistereVue[]>([]);
  protected readonly ministereId = signal<string | null>(null);
  protected readonly ministereChoisi = computed(() => this.ministeres().find((m) => m.id === this.ministereId()) ?? null);
  protected readonly formMinistere = signal<{ id?: string; sigle: string; nom: string; actif: boolean; niveaux: string[]; directions: number } | null>(null);
  protected readonly niveauxFiges = computed(() => (this.formMinistere()?.directions ?? 0) > 0);

  protected readonly directions = signal<DirectionVue[]>([]);
  protected readonly noeuds = computed(() => arbre(this.directions()));
  protected readonly formDirection = signal<{ id?: string; parentId: string | null; code: string; nom: string; actif: boolean; rang: number } | null>(null);
  protected readonly rangForm = computed(() => this.formDirection()?.rang ?? 1);
  protected readonly parentsPossibles = computed(() =>
    this.directions().filter((d) => d.rang === this.rangForm() - 1).sort((a, b) => a.nom.localeCompare(b.nom)),
  );

  protected readonly csv = signal('');
  protected readonly rapport = signal<RapportImportDirections | null>(null);
  protected readonly rapportValide = computed(() => {
    const r = this.rapport();
    return !!r && r.simulation && !r.erreurs.length && r.creees + r.modifiees > 0;
  });

  async ngOnInit(): Promise<void> {
    await this.chargerPays();
  }

  protected libelleNiveau(rang: number): string {
    return this.ministereChoisi()?.niveaux[rang - 1] ?? `Niveau ${rang}`;
  }

  // ---------- Pays

  private async chargerPays(choisir?: string): Promise<void> {
    const l = await this.action.executer(() => this.api.pays());
    if (l) {
      this.pays.set(l);
      const id = choisir ?? this.paysId() ?? l[0]?.id ?? null;
      if (id && id !== this.paysId()) {
        await this.choisirPays(id);
      }
    }
  }

  protected async choisirPays(id: string): Promise<void> {
    this.paysId.set(id);
    this.ministereId.set(null);
    this.directions.set([]);
    this.formMinistere.set(null);
    await this.chargerMinisteres();
  }

  protected nouveauPays(): void {
    this.formPays.set({ ...PAYS_VIDE });
  }

  protected modifierPays(p: PaysVue): void {
    this.formPays.set({ id: p.id, code: p.code, nom: p.nom, deviseNationale: p.deviseNationale ?? '', indicatifTelephone: p.indicatifTelephone,
      longueurNumero: p.longueurNumero, fuseauHoraire: p.fuseauHoraire, monnaie: p.monnaie, langue: p.langue });
  }

  protected async enregistrerPays(): Promise<void> {
    const f = this.formPays();
    if (!f) {
      return;
    }
    const { id, ...d } = f;
    const donnees: DonneesPays = { ...d, code: d.code.trim().toUpperCase(), monnaie: d.monnaie.trim().toUpperCase(), deviseNationale: d.deviseNationale?.trim() || null };
    const r = await this.action.executer(() => (id ? this.api.modifierPays(id, donnees) : this.api.creerPays(donnees)));
    if (r) {
      this.formPays.set(null);
      this.message.set(`Pays « ${r.nom} » enregistré.`);
      await this.chargerPays(r.id);
      this.pays.update((l) => l.map((p) => (p.id === r.id ? r : p)));
    }
  }

  // ---------- Ministères

  private async chargerMinisteres(): Promise<void> {
    const paysId = this.paysId();
    if (!paysId) {
      return;
    }
    const l = await this.action.executer(() => this.api.ministeres(paysId));
    if (l) {
      this.ministeres.set(l);
    }
  }

  protected async choisirMinistere(id: string): Promise<void> {
    this.ministereId.set(id);
    this.formDirection.set(null);
    this.rapport.set(null);
    await this.chargerDirections();
  }

  protected nouveauMinistere(): void {
    this.formMinistere.set({ sigle: '', nom: '', actif: true, niveaux: ['Direction régionale', 'Direction provinciale'], directions: 0 });
  }

  protected modifierMinistere(m: MinistereVue): void {
    this.formMinistere.set({ id: m.id, sigle: m.sigle, nom: m.nom, actif: m.actif, niveaux: [...m.niveaux], directions: m.directions });
  }

  protected ajouterNiveau(): void {
    this.formMinistere.update((f) => (f ? { ...f, niveaux: [...f.niveaux, ''] } : f));
  }

  protected retirerNiveau(i: number): void {
    this.formMinistere.update((f) => (f ? { ...f, niveaux: f.niveaux.filter((_, k) => k !== i) } : f));
  }

  protected async enregistrerMinistere(): Promise<void> {
    const f = this.formMinistere();
    const paysId = this.paysId();
    if (!f || !paysId) {
      return;
    }
    const d = { sigle: f.sigle.trim(), nom: f.nom.trim(), actif: f.actif, niveaux: f.niveaux.map((n) => n.trim()) };
    const r = await this.action.executer(() => (f.id ? this.api.modifierMinistere(f.id, d) : this.api.creerMinistere(paysId, d)));
    if (r) {
      this.formMinistere.set(null);
      this.message.set(`Ministère « ${r.sigle} » enregistré.`);
      await this.chargerMinisteres();
      await this.choisirMinistere(r.id);
    }
  }

  // ---------- Directions

  private async chargerDirections(): Promise<void> {
    const id = this.ministereId();
    if (!id) {
      return;
    }
    const l = await this.action.executer(() => this.api.directions(id));
    if (l) {
      this.directions.set(l);
    }
  }

  protected nouvelleDirection(parent: DirectionVue | null): void {
    this.formDirection.set({ parentId: parent?.id ?? null, code: '', nom: '', actif: true, rang: parent ? parent.rang + 1 : 1 });
  }

  protected modifierDirection(d: DirectionVue): void {
    this.formDirection.set({ id: d.id, parentId: d.parentId, code: d.code, nom: d.nom, actif: d.actif, rang: d.rang });
  }

  protected async enregistrerDirection(): Promise<void> {
    const f = this.formDirection();
    const ministere = this.ministereId();
    if (!f || !ministere) {
      return;
    }
    const d = { parentId: f.rang > 1 ? f.parentId : null, code: f.code.trim(), nom: f.nom.trim(), actif: f.actif };
    const r = await this.action.executer(() => (f.id ? this.api.modifierDirection(f.id, d) : this.api.creerDirection(ministere, d)));
    if (r) {
      this.formDirection.set(null);
      this.message.set(`« ${r.nom} » enregistrée.`);
      await this.chargerDirections();
      await this.chargerMinisteres();
    }
  }

  protected async lireFichier(e: Event): Promise<void> {
    const fichier = (e.target as HTMLInputElement).files?.[0];
    if (fichier) {
      this.csv.set(await fichier.text());
      this.rapport.set(null);
    }
  }

  protected async importer(simulation: boolean): Promise<void> {
    const ministere = this.ministereId();
    if (!ministere) {
      return;
    }
    this.message.set(null);
    const r = await this.action.executer(() => this.api.importerDirections(ministere, this.csv(), simulation));
    if (r) {
      this.rapport.set(r);
      if (!simulation && !r.erreurs.length) {
        this.message.set(`Import terminé : ${r.creees} créée(s), ${r.modifiees} modifiée(s).`);
        this.csv.set('');
        await this.chargerDirections();
        await this.chargerMinisteres();
      }
    }
  }
}
