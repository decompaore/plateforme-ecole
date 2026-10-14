import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { EleveVue, Page } from '../../admin/modeles-admin';
import { VsNavComponent } from '../vs-nav.component';

/** Retrouver un élève (nom, prénoms ou matricule) pour ouvrir sa fiche de vie scolaire. */
@Component({
  selector: 'app-eleves-vs',
  imports: [FormsModule, RouterLink, VsNavComponent],
  template: `
    <div class="page large">
      <h1>Vie scolaire</h1>
      <app-vs-nav />

      <form class="recherche-serveur" role="search" (ngSubmit)="rechercher(0)">
        <label for="q" class="visuellement-cache">Nom, prénoms ou matricule</label>
        <input id="q" name="q" type="search" placeholder="Nom, prénoms ou matricule" autocomplete="off" [(ngModel)]="q" />
        <button type="submit" class="bouton" [disabled]="action.enCours() || !q().trim()">Chercher</button>
      </form>

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }

      @if (page(); as p) {
        <section class="carte">
          <p class="doux">{{ p.total }} élève(s) trouvé(s)</p>
          <ul class="liste">
            @for (e of p.elements; track e.id) {
              <li>
                <a class="ligne-lien" [routerLink]="['/vie-scolaire/eleves', e.id]">
                  <span>
                    <strong>{{ e.nom }}</strong> {{ e.prenoms }}
                    @if (e.matricule) { <span class="doux"> · {{ e.matricule }}</span> }
                  </span>
                  <span aria-hidden="true">›</span>
                </a>
              </li>
            } @empty {
              <li class="doux">Aucun élève ne correspond à « {{ cherche() }} ».</li>
            }
          </ul>
          @if (p.nombrePages > 1) {
            <div class="actions-ligne">
              <button type="button" class="bouton secondaire petit" [disabled]="p.page === 0" (click)="rechercher(p.page - 1)">‹ Précédents</button>
              <span class="doux">Page {{ p.page + 1 }} / {{ p.nombrePages }}</span>
              <button type="button" class="bouton secondaire petit" [disabled]="p.page + 1 >= p.nombrePages" (click)="rechercher(p.page + 1)">Suivants ›</button>
            </div>
          }
        </section>
      } @else {
        <p class="doux">Tapez le nom, les prénoms ou le matricule, puis « Chercher ».</p>
      }
    </div>
  `,
  styles: `
    .recherche-serveur {
      display: flex;
      gap: 0.5rem;
      margin-bottom: 1rem;
      input {
        flex: 1;
        min-width: 0;
      }
    }
  `,
})
export class ElevesVsPage {
  private readonly api = inject(AdminApi);

  protected readonly action = new Action();
  protected readonly q = signal('');
  protected readonly cherche = signal('');
  protected readonly page = signal<Page<EleveVue> | null>(null);

  protected async rechercher(numero: number): Promise<void> {
    const texte = this.q().trim();
    if (!texte) {
      return;
    }
    const p = await this.action.executer(() => this.api.eleves(texte, numero));
    if (p) {
      this.cherche.set(texte);
      this.page.set(p);
    }
  }
}
