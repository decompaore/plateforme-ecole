import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { messageErreur } from '../core/erreurs';
import { Affectation } from '../core/modeles';
import { SessionService } from '../core/session.service';
import { CahierService } from '../hors-ligne/cahier.service';
import { ListesService } from '../hors-ligne/listes.service';

/** Choix de la classe et de la matière pour remplir le cahier de textes (marche sans réseau). */
@Component({
  selector: 'app-cahier-choix',
  imports: [RouterLink],
  template: `
    <div class="page">
      <h1>Cahier de textes</h1>
      @if (cahier.enAttente() > 0) {
        <div class="alerte attention" role="status">{{ cahier.enAttente() }} séance(s) en attente d'envoi : elles partiront dès qu'il y aura du réseau.</div>
      }
      @if (cahier.refusees() > 0) {
        <div class="alerte erreur" role="alert">{{ cahier.refusees() }} séance(s) refusée(s) : ouvrez la matière pour les corriger.</div>
      }
      @if (erreur()) {
        <div class="alerte erreur" role="alert">{{ erreur() }}</div>
      }
      @if (chargement()) {
        <p class="doux">Chargement…</p>
      } @else if (groupes().length === 0) {
        <div class="carte">
          <p>{{ session.horsConnexion() ? 'Aucune liste de classe sur ce téléphone.' : "Vous n'avez aucune classe cette année." }}</p>
        </div>
      } @else {
        <p class="doux">Après chaque cours, notez ce qui a été fait et le travail donné. Cela marche aussi sans réseau.</p>
        @for (g of groupes(); track g.classeId) {
          <section class="carte">
            <h2>{{ g.classeCode }}</h2>
            <ul class="liste">
              @for (a of g.affectations; track a.matiereId) {
                <li>
                  <a class="ligne-lien" [routerLink]="['/cahier', a.classeId, a.matiereId]">
                    <span>
                      {{ a.matiereLibelle }}
                      @if (enAttente(a); as n) { <span class="pastille">{{ n }} en attente</span> }
                    </span>
                    <span aria-hidden="true">›</span>
                  </a>
                </li>
              }
            </ul>
          </section>
        }
      }
    </div>
  `,
  styles: `
    h2 {
      margin-bottom: 0.25rem;
    }
  `,
})
export class CahierChoixPage implements OnInit {
  protected readonly session = inject(SessionService);
  protected readonly cahier = inject(CahierService);
  private readonly listes = inject(ListesService);

  private readonly affectations = signal<Affectation[]>([]);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);

  protected readonly groupes = computed(() => {
    const map = new Map<string, { classeId: string; classeCode: string; affectations: Affectation[] }>();
    for (const a of this.affectations()) {
      const g = map.get(a.classeId) ?? { classeId: a.classeId, classeCode: a.classeCode, affectations: [] };
      g.affectations.push(a);
      map.set(a.classeId, g);
    }
    return [...map.values()];
  });

  async ngOnInit(): Promise<void> {
    try {
      await this.cahier.recharger();
      this.affectations.set((await this.listes.affectations())?.affectations ?? []);
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  protected enAttente(a: Affectation): number {
    return this.cahier.operations().filter((o) => o.donnees.classeId === a.classeId && o.donnees.matiereId === a.matiereId).length;
  }
}
