import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { fcfa, LIBELLE_STATUT_VIREMENT, lireMontant, StatutVirement, VirementVue } from '../modeles-scolarite';
import { ScolariteApi } from '../scolarite-api.service';
import { ScoNavComponent } from '../sco-nav.component';

/** Date courte « 07/10/2026 ». */
function jour(iso: string): string {
  const [a, m, j] = iso.slice(0, 10).split('-');
  return `${j}/${m}/${a}`;
}

/**
 * Virements déclarés par les parents (v0.38) : l'intendance les retrouve sur le relevé du compte,
 * puis les valide avec le montant reçu (paiement et reçu, SMS au parent) ou les refuse avec un
 * motif (SMS au parent).
 */
@Component({
  selector: 'app-virements',
  imports: [FormsModule, RouterLink, ScoNavComponent],
  template: `
    <div class="page large">
      <h1>Scolarité et paiements</h1>
      <app-sco-nav />

      <div class="periode" role="group" aria-label="Virements">
        @for (s of statuts; track s.code) {
          <button type="button" class="bouton petit" [class.secondaire]="statut() !== s.code" [attr.aria-pressed]="statut() === s.code"
            (click)="choisir(s.code)">{{ s.libelle }}</button>
        }
      </div>

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (message()) {
        <div class="alerte succes" role="status">{{ message() }}</div>
      }

      <section class="carte">
        @if (statut() === 'EN_ATTENTE') {
          <p class="doux">Retrouvez chaque virement sur le relevé du compte avant de le valider ; validez le montant réellement reçu.</p>
        }
        <ul class="liste">
          @for (v of virements(); track v.id) {
            <li class="virement">
              <div class="entete">
                <span>
                  <strong>{{ fcfa(v.montant) }}</strong> · {{ v.eleve }} <span class="doux">({{ v.classe }} · {{ v.matricule }})</span>
                  <span class="pastille" [class.present]="v.statut === 'VALIDE'" [class.absent]="v.statut === 'REFUSE'">{{ libelleStatut[v.statut] }}</span>
                </span>
                <a class="bouton discret petit" [routerLink]="['/scolarite/inscriptions', v.inscriptionId]">Fiche</a>
              </div>
              <p class="doux">
                Virement du {{ jour(v.dateVirement) }} · réf. <strong>{{ v.reference }}</strong> · {{ v.compte }}
                @if (v.donneurOrdre) { · donneur d'ordre : {{ v.donneurOrdre }} }
                · déclaré le {{ jour(v.declareLe) }}
              </p>
              @if (v.statut === 'VALIDE') {
                <p class="doux">{{ fcfa(v.montantValide ?? v.montant) }} validés · reçu {{ v.recuNumero }}</p>
              }
              @if (v.statut === 'REFUSE') {
                <p class="doux">Motif : {{ v.motifRefus }}</p>
              }
              <div class="actions-ligne">
                @if (v.preuve) {
                  <button type="button" class="bouton discret petit" [disabled]="action.enCours()" (click)="voirPreuve(v)">Voir la preuve</button>
                }
                @if (v.statut === 'EN_ATTENTE') {
                  @if (traitement()?.id === v.id) {
                    @if (traitement()?.action === 'valider') {
                      <label class="visuellement-cache" [for]="'recu-' + v.id">Montant reçu</label>
                      <input class="montant" [id]="'recu-' + v.id" name="recu" inputmode="numeric" [(ngModel)]="montantRecu" />
                      <button type="button" class="bouton petit" [disabled]="action.enCours() || !montantValide()" (click)="valider(v)">Confirmer : {{ fcfa(montantLu() ?? 0) }} reçus</button>
                    } @else {
                      <label class="visuellement-cache" [for]="'motif-' + v.id">Motif du refus</label>
                      <input class="motif" [id]="'motif-' + v.id" name="motif" maxlength="300" placeholder="Motif (envoyé au parent par SMS)" [(ngModel)]="motif" />
                      <button type="button" class="bouton danger petit" [disabled]="action.enCours() || !motif().trim()" (click)="refuser(v)">Confirmer le refus</button>
                    }
                    <button type="button" class="bouton discret petit" (click)="traitement.set(null)">Annuler</button>
                  } @else {
                    <button type="button" class="bouton petit" (click)="ouvrir(v, 'valider')">Valider</button>
                    <button type="button" class="bouton secondaire petit" (click)="ouvrir(v, 'refuser')">Refuser</button>
                  }
                }
              </div>
            </li>
          } @empty {
            <li class="doux">{{ statut() === 'EN_ATTENTE' ? 'Aucun virement à vérifier.' : 'Aucun virement.' }}</li>
          }
        </ul>
      </section>
    </div>
  `,
  styles: `
    .periode {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      margin-bottom: 1rem;
    }
    .virement {
      display: block;
      p {
        margin: 0.25rem 0;
      }
    }
    .entete {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 0.4rem;
    }
    .montant {
      width: 9rem;
    }
    .motif {
      flex: 1 1 14rem;
      min-width: 0;
    }
  `,
})
export class VirementsPage implements OnInit {
  private readonly api = inject(ScolariteApi);

  protected readonly fcfa = fcfa;
  protected readonly jour = jour;
  protected readonly libelleStatut = LIBELLE_STATUT_VIREMENT;
  protected readonly statuts: { code: StatutVirement | 'TOUS'; libelle: string }[] = [
    { code: 'EN_ATTENTE', libelle: 'À vérifier' },
    { code: 'VALIDE', libelle: 'Validés' },
    { code: 'REFUSE', libelle: 'Refusés' },
    { code: 'TOUS', libelle: 'Tous' },
  ];

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly statut = signal<StatutVirement | 'TOUS'>('EN_ATTENTE');
  protected readonly virements = signal<VirementVue[]>([]);
  protected readonly traitement = signal<{ id: string; action: 'valider' | 'refuser' } | null>(null);
  protected readonly montantRecu = signal('');
  protected readonly motif = signal('');

  async ngOnInit(): Promise<void> {
    await this.charger();
  }

  protected montantLu(): number | null {
    return lireMontant(this.montantRecu());
  }

  protected montantValide(): boolean {
    const m = this.montantLu();
    return m !== null && m > 0;
  }

  protected async choisir(s: StatutVirement | 'TOUS'): Promise<void> {
    this.statut.set(s);
    this.message.set(null);
    await this.charger();
  }

  protected ouvrir(v: VirementVue, action: 'valider' | 'refuser'): void {
    this.traitement.set({ id: v.id, action });
    this.montantRecu.set(String(v.montant));
    this.motif.set('');
  }

  protected async valider(v: VirementVue): Promise<void> {
    const r = await this.action.executer(() => this.api.validerVirement(v.id, this.montantLu()));
    if (r) {
      this.traitement.set(null);
      this.message.set(`Virement validé : reçu ${r.recuNumero} (${fcfa(r.montantValide ?? r.montant)}), envoyé au parent par SMS.`);
      await this.charger();
    }
  }

  protected async refuser(v: VirementVue): Promise<void> {
    const r = await this.action.executer(() => this.api.refuserVirement(v.id, this.motif().trim()));
    if (r) {
      this.traitement.set(null);
      this.message.set('Virement refusé : le parent est prévenu par SMS.');
      await this.charger();
    }
  }

  protected async voirPreuve(v: VirementVue): Promise<void> {
    const blob = await this.action.executer(() => this.api.preuveVirement(v.id));
    if (blob) {
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank', 'noopener');
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    }
  }

  private async charger(): Promise<void> {
    const l = await this.action.executer(() => this.api.virements(this.statut()));
    if (l) {
      this.virements.set(l);
    }
  }
}
