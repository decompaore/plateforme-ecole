import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { messageErreur } from '../core/erreurs';
import { Affectation } from '../core/modeles';
import { dateHeureCourte } from '../core/outils';
import { SessionService } from '../core/session.service';
import { ListesService } from '../hors-ligne/listes.service';
import { NotesService } from '../hors-ligne/notes.service';

interface Groupe {
  classeId: string;
  classeCode: string;
  affectations: Affectation[];
}

@Component({
  selector: 'app-appel-choix',
  imports: [RouterLink],
  template: `
    <div class="page">
      <h1>Faire l'appel</h1>
      @if (erreur()) {
        <div class="alerte erreur" role="alert">{{ erreur() }}</div>
      }

      @if (chargement()) {
        <p class="doux">Chargement…</p>
      } @else if (groupes().length === 0) {
        <div class="carte">
          @if (session.horsConnexion()) {
            <p>Aucune liste de classe sur ce téléphone.</p>
            <p class="doux">Ouvrez l'application une fois avec du réseau pour préparer vos listes.</p>
          } @else {
            <p>Vous n'avez aucune classe cette année.</p>
            <p class="doux">Les affectations sont saisies par le censeur ou l'administration.</p>
          }
        </div>
      } @else {
        <p class="doux">Choisissez la classe et la matière.</p>
        @for (g of groupes(); track g.classeId) {
          <section class="carte groupe">
            <h2>{{ g.classeCode }}</h2>
            <ul class="liste">
              @for (a of g.affectations; track a.matiereId) {
                <li>
                  <a class="ligne-lien" [routerLink]="['/appel', a.classeId, a.matiereId]">
                    <span>{{ a.matiereLibelle }}</span>
                    <span aria-hidden="true">›</span>
                  </a>
                </li>
              }
            </ul>
          </section>
        }
      }

      <section class="carte">
        <h2>Sans réseau</h2>
        <p class="doux">
          @if (prepareLe(); as d) {
            Listes enregistrées sur ce téléphone le {{ d }}. Elles se mettent à jour toutes seules quand il y a du réseau.
          } @else {
            Enregistrez vos listes de classe et vos évaluations sur ce téléphone pour faire l'appel et saisir les notes sans réseau.
          }
        </p>
        @if (listes.preparation(); as p) {
          <p role="status">Téléchargement des listes : {{ p.faites }} / {{ p.total }}</p>
        }
        <button
          type="button"
          class="bouton secondaire plein"
          [disabled]="session.horsConnexion() || listes.preparation() !== null"
          (click)="preparer()"
        >
          Mettre à jour les listes maintenant
        </button>
        @if (preparationReussie()) {
          <p class="alerte succes" role="status">Listes à jour sur ce téléphone.</p>
        }
      </section>
    </div>
  `,
  styles: `
    .groupe h2 {
      margin-bottom: 0.25rem;
    }
  `,
})
export class AppelChoixPage implements OnInit {
  protected readonly session = inject(SessionService);
  protected readonly listes = inject(ListesService);
  private readonly notes = inject(NotesService);

  private readonly affectations = signal<Affectation[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly prepareLe = signal<string | null>(null);
  protected readonly preparationReussie = signal(false);

  protected readonly groupes = computed<Groupe[]>(() => {
    const groupes = new Map<string, Groupe>();
    for (const a of this.affectations()) {
      const g = groupes.get(a.classeId) ?? { classeId: a.classeId, classeCode: a.classeCode, affectations: [] };
      g.affectations.push(a);
      groupes.set(a.classeId, g);
    }
    return [...groupes.values()];
  });

  async ngOnInit(): Promise<void> {
    try {
      this.affectations.set((await this.listes.affectations())?.affectations ?? []);
      await this.majDate();
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  protected async preparer(): Promise<void> {
    this.erreur.set(null);
    this.preparationReussie.set(false);
    try {
      const resultat = await this.listes.toutPreparer();
      this.affectations.set(resultat?.affectations ?? []);
      await this.notes.preparerTout();
      await this.majDate();
      this.preparationReussie.set(true);
    } catch (e) {
      this.erreur.set(messageErreur(e, 'Téléchargement interrompu. Réessayez.'));
    }
  }

  private async majDate(): Promise<void> {
    const d = await this.listes.prepareLe();
    this.prepareLe.set(d ? dateHeureCourte(d) : null);
  }
}
