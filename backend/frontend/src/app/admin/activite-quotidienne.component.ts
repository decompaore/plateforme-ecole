import { Component, computed, input } from '@angular/core';

import { JourActif, Taux } from './modeles-admin';

/** « lun. 5 oct. » pour une date AAAA-MM-JJ. */
export function jourCourt(jour: string): string {
  if (!jour) {
    return '';
  }
  return new Date(jour + 'T12:00:00Z').toLocaleDateString('fr-FR', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });
}

/** « 42 % » des actifs, ou un tiret s'il n'y a personne. */
export function pourcentage(t: Taux): string {
  return t.total ? `${Math.round((100 * t.actifs) / t.total)} %` : '—';
}

/**
 * Personnes actives chaque jour : une barre par jour (une seule série, une seule couleur),
 * le nombre au survol de chaque jour et le maximum indiqué. Les week-ends sont plus clairs.
 */
@Component({
  selector: 'app-activite-quotidienne',
  template: `
    <figure class="activite">
      <figcaption>
        <span>Personnes actives par jour</span>
        <span class="doux">max. {{ max() }} · {{ jourCourt(jours()[0]?.jour ?? '') }} → {{ jourCourt(jours().at(-1)?.jour ?? '') }}</span>
      </figcaption>
      <svg [attr.viewBox]="'0 0 ' + largeur() + ' ' + HAUTEUR" preserveAspectRatio="none" role="img"
        [attr.aria-label]="resume()">
        <line x1="0" [attr.x2]="largeur()" [attr.y1]="HAUTEUR - 0.5" [attr.y2]="HAUTEUR - 0.5" class="base" />
        @for (b of barres(); track b.jour) {
          <g class="jour" [class.week-end]="b.weekEnd">
            <title>{{ jourCourt(b.jour) }} : {{ b.actifs }} personne(s) active(s)</title>
            <rect class="cible" [attr.x]="b.x - 1" y="0" [attr.width]="pas()" [attr.height]="HAUTEUR" />
            @if (b.actifs > 0) {
              <path class="barre" [attr.d]="b.chemin" />
            }
          </g>
        }
      </svg>
    </figure>
  `,
  styles: `
    .activite {
      margin: 0 0 1rem;
    }
    figcaption {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 0.25rem 1rem;
      font-size: 0.9rem;
      margin-bottom: 0.35rem;
    }
    svg {
      display: block;
      width: 100%;
      height: 90px;
    }
    .base {
      stroke: var(--bordure);
      stroke-width: 1;
    }
    .cible {
      fill: transparent;
    }
    .barre {
      fill: var(--primaire);
    }
    .week-end .barre {
      opacity: 0.55;
    }
    .jour:hover .cible {
      fill: var(--fond);
    }
  `,
})
export class ActiviteQuotidienneComponent {
  readonly jours = input.required<JourActif[]>();

  protected readonly HAUTEUR = 90;
  protected readonly jourCourt = jourCourt;
  /** Largeur d'un jour dans le repère du dessin (barre + espace de 2). */
  protected readonly pas = computed(() => (this.jours().length > 60 ? 4 : this.jours().length > 31 ? 8 : 14));
  protected readonly largeur = computed(() => Math.max(1, this.jours().length * this.pas()));
  protected readonly max = computed(() => Math.max(0, ...this.jours().map((j) => j.actifs)));
  protected readonly resume = computed(() => {
    const total = this.jours().reduce((s, j) => s + j.actifs, 0);
    const jours = this.jours().length;
    return `Personnes actives par jour sur ${jours} jours : maximum ${this.max()}, moyenne ${jours ? Math.round(total / jours) : 0}.`;
  });

  protected readonly barres = computed(() => {
    const max = this.max() || 1;
    const pas = this.pas();
    const largeurBarre = pas - 2;
    const rayon = Math.min(2, largeurBarre / 2);
    return this.jours().map((j, i) => {
      const h = Math.max(2, Math.round(((this.HAUTEUR - 4) * j.actifs) / max));
      const x = i * pas + 1;
      const y = this.HAUTEUR - h;
      // Barre ancrée sur la ligne de base, coins arrondis en haut seulement
      const chemin = `M${x},${this.HAUTEUR} V${y + rayon} Q${x},${y} ${x + rayon},${y} H${x + largeurBarre - rayon} `
        + `Q${x + largeurBarre},${y} ${x + largeurBarre},${y + rayon} V${this.HAUTEUR} Z`;
      const jourSemaine = new Date(j.jour + 'T12:00:00Z').getUTCDay();
      return { jour: j.jour, actifs: j.actifs, x, chemin, weekEnd: jourSemaine === 0 || jourSemaine === 6 };
    });
  });
}
