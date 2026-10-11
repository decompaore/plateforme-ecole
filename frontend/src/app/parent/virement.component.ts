import { Component, computed, inject, input, OnInit, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { messageErreur } from '../core/erreurs';
import { dateLocale, nouvelIdentifiant } from '../core/outils';
import { fcfa, MoyensParentVue, SituationVue } from './modeles-parent';
import { ParentApi } from './parent-api.service';

/** Taille maximale de la preuve (photo ou PDF de l'avis de virement), comme le serveur. */
export const PREUVE_MAX = 2 * 1024 * 1024;

/** Contrôles avant l'envoi d'une déclaration de virement ; null si tout va bien. */
export function erreurVirement(montant: number | null, reste: number, date: string, aujourdhui: string, reference: string,
  fichier: File | null): string | null {
  if (montant === null || !Number.isInteger(montant) || montant <= 0) {
    return 'Indiquez le montant viré, sans centimes.';
  }
  if (montant > reste) {
    return `Le reste à payer est de ${fcfa(reste)}.`;
  }
  if (!date || date > aujourdhui) {
    return 'La date du virement ne peut pas être dans le futur.';
  }
  if (!reference.trim()) {
    return 'Recopiez la référence du virement figurant sur l’avis de votre banque.';
  }
  if (fichier && fichier.size > PREUVE_MAX) {
    return 'La preuve dépasse 2 Mo : prenez une photo moins lourde.';
  }
  if (fichier && !['image/png', 'image/jpeg', 'application/pdf'].includes(fichier.type)) {
    return 'La preuve est une photo (PNG ou JPEG) ou un PDF.';
  }
  return null;
}

/**
 * Paiement par virement bancaire (v0.38) : le parent voit le compte de l'établissement et la
 * référence à indiquer, fait le virement depuis sa banque, puis le déclare ici (preuve facultative).
 * L'intendance le vérifie sur le relevé et émet le reçu.
 */
@Component({
  selector: 'app-virement',
  imports: [FormsModule],
  template: `
    @if (!envoye()) {
      <form class="virement" (ngSubmit)="declarer()" aria-label="Payer par virement">
        <h3>Payer par virement bancaire</h3>
        <ol class="etapes">
          <li>Depuis votre banque, faites un virement vers {{ moyens().comptes.length > 1 ? 'l’un de ces comptes' : 'ce compte' }} :</li>
        </ol>
        <ul class="comptes">
          @for (c of moyens().comptes; track c.id) {
            <li>
              <strong>{{ c.banque }}</strong> · {{ c.titulaire }}
              <br /><span class="numero">{{ c.numero }}</span>
              @if (c.codeSwift) { <span class="doux">&nbsp;· SWIFT {{ c.codeSwift }}</span> }
              @if (c.agence) { <span class="doux">&nbsp;· {{ c.agence }}</span> }
            </li>
          }
        </ul>
        <p>Motif du virement à indiquer : <strong class="numero">{{ moyens().referenceAIndiquer }}</strong></p>
        @if (moyens().consigneVirement) { <p class="doux">{{ moyens().consigneVirement }}</p> }
        <ol class="etapes" start="2">
          <li>Puis déclarez-le ici : l'intendance le vérifie et vous recevez le reçu par SMS.</li>
        </ol>

        @if (moyens().comptes.length > 1) {
          <div class="champ">
            <label for="vir-compte">Compte crédité</label>
            <select id="vir-compte" name="compte" [(ngModel)]="compteId">
              @for (c of moyens().comptes; track c.id) { <option [value]="c.id">{{ c.banque }} · {{ c.numero }}</option> }
            </select>
          </div>
        }
        <div class="champ">
          <label for="vir-montant">Montant viré (FCFA)</label>
          <input id="vir-montant" name="montant" type="number" inputmode="numeric" min="1" [max]="situation().resteFamille" step="1" required [(ngModel)]="montant" />
        </div>
        <div class="champ">
          <label for="vir-date">Date du virement</label>
          <input id="vir-date" name="date" type="date" [max]="aujourdhui" required [(ngModel)]="date" />
        </div>
        <div class="champ">
          <label for="vir-reference">Référence de l'opération (sur l'avis de la banque)</label>
          <input id="vir-reference" name="reference" maxlength="60" required [(ngModel)]="reference" />
        </div>
        <div class="champ">
          <label for="vir-donneur">Nom du titulaire du compte qui a payé (facultatif)</label>
          <input id="vir-donneur" name="donneur" maxlength="120" [(ngModel)]="donneur" />
        </div>
        <div class="champ">
          <label for="vir-preuve">Preuve : photo ou PDF de l'avis de virement (facultatif, 2 Mo au plus)</label>
          <input id="vir-preuve" name="preuve" type="file" accept="image/png,image/jpeg,application/pdf" (change)="choisirFichier($event)" />
        </div>
        @if (invalide(); as m) {
          <p class="alerte erreur">{{ m }}</p>
        }
        @if (erreur()) {
          <p class="alerte erreur" role="alert">{{ erreur() }}</p>
        }
        <div class="actions-ligne">
          <button type="submit" class="bouton" [disabled]="envoi() || !!invalide()">{{ envoi() ? 'Envoi…' : 'Déclarer le virement' }}</button>
          <button type="button" class="bouton secondaire" (click)="ferme.emit()">Annuler</button>
        </div>
      </form>
    } @else {
      <section class="virement" aria-live="polite">
        <p class="alerte succes">
          Virement de {{ fcfa(envoye()!) }} déclaré. L'intendance le vérifie sur le relevé de la banque ; vous recevrez le reçu par SMS.
        </p>
        <button type="button" class="bouton" (click)="ferme.emit()">Terminer</button>
      </section>
    }
  `,
  styles: `
    .virement {
      margin-top: 1rem;
      padding-top: 1rem;
      border-top: 1px solid var(--bordure);
    }
    h3 {
      font-size: 1rem;
      margin: 0 0 0.75rem;
    }
    .etapes {
      padding-left: 1.25rem;
      margin: 0.5rem 0;
    }
    .comptes {
      list-style: none;
      padding: 0.5rem 0.75rem;
      margin: 0 0 0.5rem;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      background: var(--surface);
      li + li {
        margin-top: 0.5rem;
      }
    }
    .numero {
      font-family: monospace;
      overflow-wrap: anywhere;
    }
  `,
})
export class VirementComponent implements OnInit {
  private readonly api = inject(ParentApi);

  readonly situation = input.required<SituationVue>();
  readonly moyens = input.required<MoyensParentVue>();
  /** Déclaration enregistrée : la liste des virements est à relire. */
  readonly declare = output<void>();
  readonly ferme = output<void>();

  protected readonly fcfa = fcfa;
  protected readonly aujourdhui = dateLocale();

  protected readonly compteId = signal('');
  protected readonly montant = signal<number | null>(null);
  protected readonly date = signal(dateLocale());
  protected readonly reference = signal('');
  protected readonly donneur = signal('');
  protected readonly fichier = signal<File | null>(null);
  protected readonly envoi = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly envoye = signal<number | null>(null);
  /** Une clé par déclaration : renvoyer la même déclaration ne la crée pas deux fois. */
  private readonly cle = nouvelIdentifiant();

  protected readonly invalide = computed(() =>
    this.montant() === null
      ? null
      : erreurVirement(Number(this.montant()), this.situation().resteFamille, this.date(), this.aujourdhui, this.reference(), this.fichier()),
  );

  ngOnInit(): void {
    this.compteId.set(this.moyens().comptes[0]?.id ?? '');
    const s = this.situation();
    this.montant.set(s.retardFamille > 0 ? Math.min(s.retardFamille, s.resteFamille) : s.resteFamille);
  }

  protected choisirFichier(e: Event): void {
    this.fichier.set((e.target as HTMLInputElement).files?.[0] ?? null);
  }

  protected async declarer(): Promise<void> {
    const montant = Number(this.montant());
    if (this.invalide() || !this.compteId()) {
      return;
    }
    this.envoi.set(true);
    this.erreur.set(null);
    try {
      await this.api.declarerVirement(this.situation().inscriptionId, {
        compteId: this.compteId(),
        montant,
        dateVirement: this.date(),
        reference: this.reference().trim(),
        donneurOrdre: this.donneur().trim(),
        cleIdempotence: this.cle,
        fichier: this.fichier(),
      });
      this.envoye.set(montant);
      this.declare.emit();
    } catch (e) {
      this.erreur.set(messageErreur(e, "La déclaration n'a pas pu être envoyée. Réessayez."));
    } finally {
      this.envoi.set(false);
    }
  }
}
