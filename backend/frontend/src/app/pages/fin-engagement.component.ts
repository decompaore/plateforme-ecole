import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { dateLocale, dateLongue } from '../core/outils';
import { SessionService } from '../core/session.service';
import { EnvoisService } from '../hors-ligne/envois.service';
import { FinEngagementLocale, ListesService } from '../hors-ligne/listes.service';
import { NotesService } from '../hors-ligne/notes.service';

/** Le bandeau apparaît dans les 30 jours qui précèdent la fin. */
export const PREAVIS_JOURS = 30;

/** Nombre de jours entre deux dates AAAA-MM-JJ (fuseau de l'appareil, sans effet d'heure d'été). */
export function joursEntre(de: string, a: string): number {
  const utc = (iso: string) => {
    const [y, m, d] = iso.split('-').map(Number);
    return Date.UTC(y, m - 1, d);
  };
  return Math.round((utc(a) - utc(de)) / 86_400_000);
}

/**
 * Fin prochaine de l'engagement de l'enseignant dans l'établissement (mutation,
 * départ, fin de contrat). Après la date de fin, l'établissement n'accepte plus
 * ses appels ni ses notes : tout ce qui attend sur le téléphone doit partir avant.
 * L'information est gardée sur l'appareil pour s'afficher aussi sans réseau.
 */
@Component({
  selector: 'app-fin-engagement',
  imports: [RouterLink],
  template: `
    @if (annonce(); as a) {
      <section class="alerte attention fin" role="status">
        <p><strong>{{ a.titre }}</strong>{{ a.motif }}.</p>
        @if (a.attente > 0) {
          <p>
            Vos appels et notes en attente doivent partir avant cette date : ensuite, l'établissement ne les
            acceptera plus. <a routerLink="/envois">{{ a.attente }} envoi(s) en attente</a>.
          </p>
          <button
            type="button"
            class="bouton petit"
            [disabled]="envoiEnCours() || session.horsConnexion()"
            (click)="envoyer()"
          >
            {{ envoiEnCours() ? 'Envoi…' : 'Envoyer maintenant' }}
          </button>
          @if (session.horsConnexion()) {
            <span class="doux"> Pas de réseau : réessayez dès que possible.</span>
          }
        } @else {
          <p>Tous vos appels et notes sont envoyés ✓</p>
        }
      </section>
    }
  `,
  styles: `
    .fin {
      display: block;
      p {
        margin: 0 0 0.5rem;
      }
      p:last-child {
        margin-bottom: 0;
      }
    }
  `,
})
export class FinEngagementComponent implements OnInit {
  protected readonly session = inject(SessionService);
  private readonly listes = inject(ListesService);
  private readonly envois = inject(EnvoisService);
  private readonly notes = inject(NotesService);

  protected readonly fin = signal<FinEngagementLocale | undefined>(undefined);
  protected readonly etablissement = computed(() => this.session.profil()?.etablissement?.nom ?? null);
  protected readonly envoiEnCours = computed(() => this.envois.synchronisation() || this.notes.synchronisation());

  protected readonly annonce = computed(() => {
    const fin = this.fin();
    if (!fin) {
      return null;
    }
    const jours = joursEntre(dateLocale(), fin.fin);
    if (jours < 0 || jours > PREAVIS_JOURS) {
      return null;
    }
    const etablissement = this.etablissement();
    const titre =
      (etablissement ? `${etablissement} : v` : 'V') +
      (fin.programmee ? 'otre poste' : 'otre contrat') +
      ' se termine ' +
      (jours === 0 ? "aujourd'hui" : `le ${dateLongue(fin.fin)}`);
    const motif = fin.programmee && fin.motif ? ` (${fin.motif.toLowerCase()})` : '';
    return { fin, jours, titre, motif, attente: this.envois.enAttente() + this.notes.enAttente() };
  });

  async ngOnInit(): Promise<void> {
    if (!this.session.profil()?.etablissement || !this.session.aLeRole('ENSEIGNANT')) {
      return;
    }
    try {
      // Tout de suite ce que sait l'appareil, puis la version du serveur
      this.fin.set(await this.listes.finEngagement());
      this.fin.set(await this.listes.actualiserFinEngagement());
    } catch {
      // Simple information : jamais bloquant pour l'accueil
    }
  }

  protected async envoyer(): Promise<void> {
    await this.envois.synchroniser();
    await this.notes.synchroniser();
  }
}
