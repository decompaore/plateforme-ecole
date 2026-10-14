import { Component, computed, inject, OnInit, signal } from '@angular/core';

import { Action } from '../admin/action';
import { dateCourte } from '../core/outils';
import { SessionService } from '../core/session.service';
import { enregistrerFichier } from '../scolarite/scolarite-api.service';
import { AfficheVue, DemandeVue, LIBELLE_STATUT_DEMANDE } from '../secretariat/modeles-secretariat';
import { SecretariatApi } from '../secretariat/secretariat-api.service';

/**
 * Tableau d'affichage (v0.47.1) : notes de service, communiqués, convocations signés par le chef d'établissement et
 * affichés par le secrétariat — le personnel voit tout, les parents ce qui est affiché pour tous. Le personnel y
 * suit aussi ses demandes administratives (mutation, autorisation d'absence…).
 */
@Component({
  selector: 'app-affichage',
  template: `
    <div class="page">
      <h1>Tableau d'affichage</h1>
      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      <section class="carte">
        <ul class="liste">
          @for (a of affiches(); track a.id) {
            <li class="ligne-lien">
              <span>
                <strong>{{ a.titre }}</strong>
                <br /><span class="doux">{{ a.typeLibelle }} n° {{ a.numero }} · jusqu'au {{ dateCourte(a.afficheAu) }}</span>
              </span>
              <button type="button" class="bouton secondaire petit" (click)="ouvrir(a)">Lire (PDF)</button>
            </li>
          } @empty {
            <li class="doux">Rien n'est affiché pour l'instant.</li>
          }
        </ul>
      </section>

      @if (personnel()) {
        <section class="carte">
          <h2>Mes demandes administratives</h2>
          <ul class="liste">
            @for (d of demandes(); track d.id) {
              <li>
                <strong>{{ d.numero }}</strong> · {{ d.typeLibelle }} · {{ d.objet }}
                <br /><span class="pastille" [class.present]="d.statut === 'AVIS_FAVORABLE' || d.statut === 'CLOTUREE'">{{ libelleStatut[d.statut] }}</span>
                @if (d.avis) { <span class="doux"> Avis : {{ d.avis }}</span> }
                @if (d.suite) { <span class="doux"> · Suite : {{ d.suite }}</span> }
              </li>
            } @empty {
              <li class="doux">Aucune demande enregistrée à votre nom. Déposez-la au secrétariat.</li>
            }
          </ul>
        </section>
      }
    </div>
  `,
})
export class AffichagePage implements OnInit {
  private readonly api = inject(SecretariatApi);
  private readonly session = inject(SessionService);

  protected readonly action = new Action();
  protected readonly dateCourte = dateCourte;
  protected readonly libelleStatut = LIBELLE_STATUT_DEMANDE;
  protected readonly personnel = computed(() =>
    this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR', 'CHEF_TRAVAUX', 'SECRETARIAT', 'INTENDANT', 'SURVEILLANT', 'ENSEIGNANT'),
  );
  protected readonly affiches = signal<AfficheVue[]>([]);
  protected readonly demandes = signal<DemandeVue[]>([]);

  async ngOnInit(): Promise<void> {
    const a = await this.action.executer(() => this.api.affichage());
    if (a) {
      this.affiches.set(a);
    }
    if (this.personnel()) {
      const d = await this.action.executer(() => this.api.mesDemandes());
      if (d) {
        this.demandes.set(d);
      }
    }
  }

  protected async ouvrir(a: AfficheVue): Promise<void> {
    const b = await this.action.executer(() => this.api.pdfDocument(a.id));
    if (b) {
      enregistrerFichier(b, `${a.numero.replace(/\//g, '-')}.pdf`);
    }
  }
}
