import { Component, input, model, output } from '@angular/core';

let suivant = 0;

/**
 * Zone de recherche commune à toutes les listes : filtre instantané, sans accents ni
 * majuscules, qui marche aussi sans réseau (la liste est déjà sur l'appareil).
 * Affiche « n sur N » pendant une recherche.
 */
@Component({
  selector: 'app-recherche',
  template: `
    <div class="recherche" role="search">
      <label [for]="id" class="visuellement-cache">{{ libelle() }}</label>
      <input
        [id]="id"
        type="search"
        autocomplete="off"
        enterkeyhint="search"
        [placeholder]="libelle()"
        [value]="valeur()"
        (input)="valeur.set($any($event.target).value)"
        (keydown.enter)="$event.preventDefault(); valider.emit()"
        (keydown.escape)="valeur.set('')"
      />
      @if (valeur().trim()) {
        <span class="compte doux" aria-live="polite">{{ trouves() }} sur {{ total() }}</span>
      }
    </div>
  `,
  styles: `
    .recherche {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 0.75rem;
    }
    input {
      flex: 1;
      min-width: 0;
    }
    .compte {
      white-space: nowrap;
      font-size: 0.85rem;
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class RechercheComponent {
  readonly valeur = model('');
  readonly libelle = input('Rechercher');
  readonly total = input(0);
  readonly trouves = input(0);
  /** Entrée dans la zone : par exemple, aller au premier élève trouvé. */
  readonly valider = output<void>();
  protected readonly id = `recherche-${++suivant}`;
}
