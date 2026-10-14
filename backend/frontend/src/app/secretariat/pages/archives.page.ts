import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { EleveVue } from '../../admin/modeles-admin';
import { dateCourte } from '../../core/outils';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import { CATEGORIES_ARCHIVE, DossierArchive, PersonneVue, PieceArchiveVue, taille } from '../modeles-secretariat';
import { SecretariatApi } from '../secretariat-api.service';
import { SecretariatNavComponent } from '../secretariat-nav.component';

/**
 * Archives et classement (v0.47.1) : pièces scannées du dossier d'un élève, d'un membre du personnel ou de
 * l'établissement, avec catégorie et cote.
 */
@Component({
  selector: 'app-archives',
  imports: [FormsModule, SecretariatNavComponent],
  template: `
    <div class="page large">
      <h1>Archives</h1>
      <app-secretariat-nav />
      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      <section class="carte">
        <div class="bascule" role="group" aria-label="Dossier">
          <button type="button" [class.actif]="dossier() === 'ELEVE'" (click)="choisirDossier('ELEVE')">Élèves</button>
          <button type="button" [class.actif]="dossier() === 'PERSONNEL'" (click)="choisirDossier('PERSONNEL')">Personnel</button>
          <button type="button" [class.actif]="dossier() === 'ETABLISSEMENT'" (click)="choisirDossier('ETABLISSEMENT')">Établissement</button>
        </div>

        @if (dossier() === 'ELEVE') {
          <form class="actions-ligne" (ngSubmit)="chercher()">
            <input name="q" type="search" placeholder="Nom ou matricule de l'élève" [(ngModel)]="q" />
            <button type="submit" class="bouton secondaire petit">Chercher</button>
          </form>
          <ul class="liste">
            @for (e of eleves(); track e.id) {
              <li><button type="button" class="bouton discret petit" (click)="ouvrir(e.id, e.nom + ' ' + e.prenoms)">{{ e.nom }} {{ e.prenoms }} · {{ e.matricule }}</button></li>
            }
          </ul>
        }
        @if (dossier() === 'PERSONNEL') {
          <select aria-label="Membre du personnel" [ngModel]="cible() ?? ''" (ngModelChange)="ouvrirPersonne($event)">
            <option value="">— choisir —</option>
            @for (p of personnel(); track p.utilisateurId) { <option [value]="p.utilisateurId">{{ p.nom }} {{ p.prenoms }} ({{ p.fonctions }})</option> }
          </select>
        }
      </section>

      @if (dossier() === 'ETABLISSEMENT' || cible()) {
        <section class="carte">
          <h2>{{ titre() }}</h2>
          <ul class="liste">
            @for (p of pieces(); track p.id) {
              <li class="ligne-lien">
                <span>
                  <strong>{{ p.titre }}</strong> · {{ p.categorie }}@if (p.cote) { · cote {{ p.cote }} }
                  <br /><span class="doux">{{ p.fichierNom }} ({{ taille(p.taille) }})@if (p.dateDocument) { · du {{ dateCourte(p.dateDocument) }} }</span>
                </span>
                <span class="actions-ligne">
                  <button type="button" class="bouton discret petit" (click)="telecharger(p)">Ouvrir</button>
                  <button type="button" class="bouton discret petit" (click)="supprimer(p)">Retirer</button>
                </span>
              </li>
            } @empty {
              <li class="doux">Aucune pièce archivée.</li>
            }
          </ul>
          <form class="sous-formulaire" (ngSubmit)="archiver()">
            <div class="grille-champs">
              <div class="champ">
                <label for="pCategorie">Catégorie</label>
                <select id="pCategorie" name="pCategorie" [(ngModel)]="fCategorie">
                  @for (c of categories(); track c) { <option [value]="c">{{ c }}</option> }
                </select>
              </div>
              <div class="champ"><label for="pTitre">Titre</label><input id="pTitre" name="pTitre" maxlength="200" [(ngModel)]="fTitre" /></div>
              <div class="champ"><label for="pCote">Cote</label><input id="pCote" name="pCote" maxlength="40" [(ngModel)]="fCote" /></div>
              <div class="champ"><label for="pDate">Date du document</label><input id="pDate" name="pDate" type="date" [(ngModel)]="fDate" /></div>
              <div class="champ"><label for="pFichier">Fichier (PDF, JPEG, PNG ; 5 Mo)</label><input id="pFichier" type="file" accept=".pdf,image/jpeg,image/png" (change)="choisir($event)" /></div>
            </div>
            <button type="submit" class="bouton" [disabled]="action.enCours() || !fTitre().trim() || !fichier()">Archiver</button>
          </form>
        </section>
      }
    </div>
  `,
})
export class ArchivesPage implements OnInit {
  private readonly api = inject(SecretariatApi);
  private readonly admin = inject(AdminApi);

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly dateCourte = dateCourte;
  protected readonly taille = taille;

  protected readonly dossier = signal<DossierArchive>('ELEVE');
  protected readonly cible = signal<string | null>(null);
  protected readonly titre = signal('Dossier de l’établissement');
  protected readonly q = signal('');
  protected readonly eleves = signal<EleveVue[]>([]);
  protected readonly personnel = signal<PersonneVue[]>([]);
  protected readonly pieces = signal<PieceArchiveVue[]>([]);
  protected readonly categories = signal<string[]>(CATEGORIES_ARCHIVE.ELEVE);
  protected readonly fCategorie = signal(CATEGORIES_ARCHIVE.ELEVE[0]);
  protected readonly fTitre = signal('');
  protected readonly fCote = signal('');
  protected readonly fDate = signal('');
  protected readonly fichier = signal<File | null>(null);

  async ngOnInit(): Promise<void> {
    const p = await this.action.executer(() => this.api.personnel());
    if (p) {
      this.personnel.set(p);
    }
  }

  protected async choisirDossier(d: DossierArchive): Promise<void> {
    this.dossier.set(d);
    this.cible.set(null);
    this.pieces.set([]);
    this.categories.set(CATEGORIES_ARCHIVE[d]);
    this.fCategorie.set(CATEGORIES_ARCHIVE[d][0]);
    if (d === 'ETABLISSEMENT') {
      this.titre.set('Dossier de l’établissement');
      await this.charger();
    }
  }

  protected async chercher(): Promise<void> {
    const p = await this.action.executer(() => this.admin.eleves(this.q().trim(), 0, 10));
    if (p) {
      this.eleves.set(p.elements);
    }
  }

  protected async ouvrir(id: string, nom: string): Promise<void> {
    this.cible.set(id);
    this.titre.set(`Dossier de ${nom}`);
    await this.charger();
  }

  protected async ouvrirPersonne(id: string): Promise<void> {
    const p = this.personnel().find((x) => x.utilisateurId === id);
    if (!p) {
      this.cible.set(null);
      return;
    }
    await this.ouvrir(id, `${p.nom} ${p.prenoms}`);
  }

  private async charger(): Promise<void> {
    const l = await this.action.executer(() => this.api.archives(this.dossier(), this.cible()));
    if (l) {
      this.pieces.set(l);
    }
  }

  protected choisir(e: Event): void {
    this.fichier.set((e.target as HTMLInputElement).files?.[0] ?? null);
  }

  protected async archiver(): Promise<void> {
    const f = this.fichier();
    if (!f) {
      return;
    }
    const p = await this.action.executer(() =>
      this.api.archiver(f, {
        dossier: this.dossier(),
        cible: this.cible(),
        categorie: this.fCategorie(),
        titre: this.fTitre().trim(),
        cote: this.fCote().trim() || null,
        dateDocument: this.fDate() || null,
      }),
    );
    if (p) {
      this.pieces.set([...this.pieces(), p]);
      this.message.set(`« ${p.titre} » archivé.`);
      this.fTitre.set('');
      this.fCote.set('');
      this.fDate.set('');
    }
  }

  protected async telecharger(p: PieceArchiveVue): Promise<void> {
    const b = await this.action.executer(() => this.api.pieceArchive(p.id));
    if (b) {
      enregistrerFichier(b, p.fichierNom);
    }
  }

  protected async supprimer(p: PieceArchiveVue): Promise<void> {
    if (!window.confirm(`Retirer « ${p.titre} » des archives ?`)) {
      return;
    }
    if (await this.action.reussit(() => this.api.supprimerArchive(p.id))) {
      this.pieces.set(this.pieces().filter((x) => x.id !== p.id));
    }
  }
}
