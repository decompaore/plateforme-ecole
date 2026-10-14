import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { AdminNavComponent } from '../admin-nav.component';
import { FiliereVue, LIBELLE_TYPE_MATIERE, MatiereVue, ProfilVue, TypeMatiere } from '../modeles-admin';

/** Filières (avec leur profil pédagogique) et matières de l'établissement. */
@Component({
  selector: 'app-referentiel',
  imports: [FormsModule, AdminNavComponent],
  template: `
    <div class="page large">
      <h1>Filières et matières</h1>
      <app-admin-nav />

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (profils().length === 0 && charge()) {
        <div class="alerte attention">
          Créez d'abord les profils pédagogiques (onglet « Année scolaire ») : chaque filière en a un.
        </div>
      }

      <section class="carte">
        <h2>Filières</h2>
        <div class="tableau-defilant">
          <table class="tableau">
            <thead><tr><th>Code</th><th>Libellé</th><th>Cycle</th><th>Diplôme</th><th>Profil</th></tr></thead>
            <tbody>
              @for (f of filieres(); track f.id) {
                <tr>
                  <td><strong>{{ f.code }}</strong></td>
                  <td>{{ f.libelle }}</td>
                  <td>{{ f.cycle }}</td>
                  <td>{{ f.diplomeVise }}</td>
                  <td>{{ libelleProfil(f.profilId) }}</td>
                </tr>
              } @empty {
                <tr><td colspan="5" class="doux">Aucune filière.</td></tr>
              }
            </tbody>
          </table>
        </div>
        <form (ngSubmit)="creerFiliere()" class="ajout">
          <h3>Nouvelle filière</h3>
          <div class="grille-champs">
            <div class="champ">
              <label for="fCode">Code</label>
              <input id="fCode" name="fCode" required maxlength="20" placeholder="F3" [(ngModel)]="fCode" />
            </div>
            <div class="champ">
              <label for="fLibelle">Libellé</label>
              <input id="fLibelle" name="fLibelle" required placeholder="Électrotechnique" [(ngModel)]="fLibelle" />
            </div>
            <div class="champ">
              <label for="fCycle">Cycle</label>
              <select id="fCycle" name="fCycle" [(ngModel)]="fCycle">
                <option>Premier cycle</option>
                <option>Second cycle</option>
                <option>Formation professionnelle</option>
              </select>
            </div>
            <div class="champ">
              <label for="fDiplome">Diplôme visé</label>
              <input id="fDiplome" name="fDiplome" placeholder="BAC F3, CAP, BEP…" [(ngModel)]="fDiplome" />
            </div>
            <div class="champ">
              <label for="fProfil">Profil pédagogique</label>
              <select id="fProfil" name="fProfil" required [(ngModel)]="fProfil">
                @for (p of profils(); track p.id) {
                  <option [value]="p.id">{{ p.libelle }}</option>
                }
              </select>
            </div>
          </div>
          <button type="submit" class="bouton secondaire" [disabled]="action.enCours() || !fProfil()">Ajouter la filière</button>
        </form>
      </section>

      <section class="carte">
        <h2>Matières</h2>
        <div class="tableau-defilant">
          <table class="tableau">
            <thead><tr><th>Code</th><th>Libellé</th><th>Type</th></tr></thead>
            <tbody>
              @for (m of matieres(); track m.id) {
                <tr [class.doux]="!m.actif">
                  <td><strong>{{ m.code }}</strong></td>
                  <td>{{ m.libelle }}@if (!m.actif) { (désactivée) }</td>
                  <td>{{ types[m.type] }}</td>
                </tr>
              } @empty {
                <tr><td colspan="3" class="doux">Aucune matière.</td></tr>
              }
            </tbody>
          </table>
        </div>
        <form (ngSubmit)="creerMatiere()" class="ajout">
          <h3>Nouvelle matière</h3>
          <div class="grille-champs">
            <div class="champ">
              <label for="mCode">Code</label>
              <input id="mCode" name="mCode" required maxlength="20" placeholder="MATH" [(ngModel)]="mCode" />
            </div>
            <div class="champ">
              <label for="mLibelle">Libellé</label>
              <input id="mLibelle" name="mLibelle" required placeholder="Mathématiques" [(ngModel)]="mLibelle" />
            </div>
            <div class="champ">
              <label for="mType">Type</label>
              <select id="mType" name="mType" [(ngModel)]="mType">
                @for (t of listeTypes; track t) {
                  <option [value]="t">{{ types[t] }}</option>
                }
              </select>
            </div>
          </div>
          <button type="submit" class="bouton secondaire" [disabled]="action.enCours()">Ajouter la matière</button>
        </form>
      </section>
    </div>
  `,
  styles: `
    .ajout {
      border-top: 1px solid var(--bordure);
      margin-top: 1rem;
      padding-top: 0.5rem;
    }
    h3 {
      font-size: 1rem;
      margin: 0.5rem 0 0.75rem;
    }
  `,
})
export class ReferentielPage implements OnInit {
  private readonly api = inject(AdminApi);

  protected readonly types = LIBELLE_TYPE_MATIERE;
  protected readonly listeTypes = Object.keys(LIBELLE_TYPE_MATIERE) as TypeMatiere[];
  protected readonly action = new Action();
  protected readonly charge = signal(false);
  protected readonly profils = signal<ProfilVue[]>([]);
  protected readonly filieres = signal<FiliereVue[]>([]);
  protected readonly matieres = signal<MatiereVue[]>([]);

  protected readonly fCode = signal('');
  protected readonly fLibelle = signal('');
  protected readonly fCycle = signal('Second cycle');
  protected readonly fDiplome = signal('');
  protected readonly fProfil = signal('');
  protected readonly mCode = signal('');
  protected readonly mLibelle = signal('');
  protected readonly mType = signal<TypeMatiere>('GENERALE');

  async ngOnInit(): Promise<void> {
    await this.action.executer(async () => {
      const [profils, filieres, matieres] = await Promise.all([
        this.api.profils(),
        this.api.filieres(),
        this.api.matieres(),
      ]);
      this.profils.set(profils.filter((p) => p.actif));
      this.filieres.set(trier(filieres));
      this.matieres.set(trier(matieres));
      this.fProfil.set(this.profils()[0]?.id ?? '');
    });
    this.charge.set(true);
  }

  protected libelleProfil(id: string): string {
    return this.profils().find((p) => p.id === id)?.libelle ?? '';
  }

  protected async creerFiliere(): Promise<void> {
    const f = await this.action.executer(() =>
      this.api.creerFiliere({
        code: this.fCode().trim(),
        libelle: this.fLibelle().trim(),
        cycle: this.fCycle(),
        diplomeVise: this.fDiplome().trim() || null,
        profilId: this.fProfil(),
      }),
    );
    if (f) {
      this.filieres.update((l) => trier([...l, f]));
      this.fCode.set('');
      this.fLibelle.set('');
      this.fDiplome.set('');
    }
  }

  protected async creerMatiere(): Promise<void> {
    const m = await this.action.executer(() =>
      this.api.creerMatiere({ code: this.mCode().trim(), libelle: this.mLibelle().trim(), type: this.mType() }),
    );
    if (m) {
      this.matieres.update((l) => trier([...l, m]));
      this.mCode.set('');
      this.mLibelle.set('');
    }
  }
}

function trier<T extends { code: string }>(liste: T[]): T[] {
  return [...liste].sort((a, b) => a.code.localeCompare(b.code));
}
