import { Component, EventEmitter, Input, Output } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { dateHeureCourte } from '../core/outils';
import {
  CLASSE_STATUT_DOTATION, DotationVue, LIBELLE_CATEGORIE, LIBELLE_STATUT_DOTATION, montant, quantite, Reception,
} from './modeles-moyens';
import { STYLES_MOYENS } from './styles-moyens';

interface Saisie {
  recue: number | null;
  conforme: number | null;
  observation: string;
}

/**
 * Détail d'une dotation (v0.48) : lignes, quantités reçues et conformes, répartitions. Si la personne connectée est
 * le destinataire et que la dotation est envoyée, le formulaire d'accusé de réception s'affiche (par défaut : tout
 * reçu, tout conforme).
 */
@Component({
  selector: 'app-dotation-detail',
  imports: [FormsModule],
  template: `
    @if (d; as d) {
      <div class="entete-section">
        <h2>{{ d.reference }} · {{ d.objet }}</h2>
        <span class="pastille {{ classeStatut[d.statut] }}">{{ libelleStatut[d.statut] }}</span>
      </div>
      <p class="doux">
        De <strong>{{ d.emettrice.nom }}</strong> à <strong>{{ d.destinataire.nom }}</strong> · année {{ d.annee }}
        @if (d.origineReference) { · répartition de {{ d.origineReference }} }
        @if (d.envoyeeLe) { · envoyée le {{ dateHeure(d.envoyeeLe) }}@if (d.envoyeePar) { par {{ d.envoyeePar }} } }
        @if (d.recueLe) { · réception accusée le {{ dateHeure(d.recueLe) }}@if (d.accuseePar) { par {{ d.accuseePar }} } }
        @if (d.valeur) { · valeur {{ montant(d.valeur) }} }
      </p>
      @if (d.observationsReception) {
        <p><strong>Observations à la réception :</strong> {{ d.observationsReception }}</p>
      }
      <div class="tableau-defilant">
        <table class="tableau">
          <thead>
            <tr>
              <th>Catégorie</th><th>Désignation</th><th class="nombre">Quantité</th><th>Unité</th>
              <th class="nombre">Valeur unitaire</th><th class="nombre">Reçue</th><th class="nombre">Conforme</th>
              <th>Observation</th>
              @if (afficherRepartition) { <th class="nombre">Répartie</th><th class="nombre">Disponible</th> }
            </tr>
          </thead>
          <tbody>
            @for (l of d.lignes; track l.id) {
              <tr>
                <td>{{ libelleCategorie[l.categorie] }}</td>
                <td>{{ l.designation }}</td>
                <td class="nombre">{{ quantite(l.quantite) }}</td>
                <td>{{ l.unite }}</td>
                <td class="nombre">{{ l.valeurUnitaire === null ? '—' : montant(l.valeurUnitaire) }}</td>
                @if (d.peutAccuser) {
                  <td class="nombre"><input type="number" min="0" step="any" [name]="'recue' + l.id" [attr.aria-label]="'Quantité reçue : ' + l.designation" [(ngModel)]="saisies[l.id].recue" /></td>
                  <td class="nombre"><input type="number" min="0" step="any" [name]="'conforme' + l.id" [attr.aria-label]="'Quantité conforme : ' + l.designation" [(ngModel)]="saisies[l.id].conforme" /></td>
                  <td><input type="text" maxlength="300" [name]="'obs' + l.id" [attr.aria-label]="'Observation : ' + l.designation" [(ngModel)]="saisies[l.id].observation" /></td>
                } @else {
                  <td class="nombre">{{ quantite(l.quantiteRecue) }}</td>
                  <td class="nombre">{{ quantite(l.quantiteConforme) }}</td>
                  <td>{{ l.observation ?? '' }}</td>
                }
                @if (afficherRepartition) {
                  <td class="nombre">{{ quantite(l.repartie) }}</td>
                  <td class="nombre">{{ quantite(l.disponible) }}</td>
                }
              </tr>
            }
          </tbody>
        </table>
      </div>
      @if (d.peutAccuser) {
        <form class="sous-formulaire" (ngSubmit)="accuserReception()">
          <p class="doux">Saisissez les quantités effectivement reçues et celles conformes aux spécifications. Aucune entrée en stock n'est faite automatiquement.</p>
          <div class="champ">
            <label for="obsReception">Observations</label>
            <textarea id="obsReception" name="obsReception" rows="2" maxlength="1000" [(ngModel)]="observations"></textarea>
          </div>
          <button type="submit" class="bouton" [disabled]="enCours">Accuser réception</button>
        </form>
      }
      @if (d.repartitions.length) {
        <h3>Répartitions</h3>
        <ul class="liste">
          @for (r of d.repartitions; track r.id) {
            <li class="ligne-lien">
              <span><strong>{{ r.reference }}</strong> · {{ r.destinataire.nom }} · {{ r.lignes }} ligne(s)</span>
              <span class="pastille {{ classeStatut[r.statut] }}">{{ libelleStatut[r.statut] }}</span>
            </li>
          }
        </ul>
      }
    }
  `,
  styles: [STYLES_MOYENS],
})
export class DotationDetailComponent {
  @Input() enCours = false;
  @Output() readonly accuser = new EventEmitter<Reception>();

  protected d: DotationVue | null = null;
  protected saisies: Record<string, Saisie> = {};
  protected observations = '';
  protected afficherRepartition = false;
  protected readonly libelleCategorie = LIBELLE_CATEGORIE;
  protected readonly libelleStatut = LIBELLE_STATUT_DOTATION;
  protected readonly classeStatut = CLASSE_STATUT_DOTATION;
  protected readonly quantite = quantite;
  protected readonly montant = montant;
  protected readonly dateHeure = dateHeureCourte;

  @Input({ required: true }) set dotation(d: DotationVue | null) {
    this.d = d;
    this.saisies = {};
    this.observations = '';
    for (const l of d?.lignes ?? []) {
      this.saisies[l.id] = { recue: l.quantite, conforme: l.quantite, observation: '' };
    }
    // La répartition n'est renvoyée qu'au destinataire qui répartit (v0.48.1)
    this.afficherRepartition = !!d && d.statut === 'RECUE' && d.lignes.some((l) => l.repartie !== null);
  }

  protected accuserReception(): void {
    if (!this.d) {
      return;
    }
    this.accuser.emit({
      observations: this.observations.trim() || null,
      lignes: this.d.lignes.map((l) => ({
        ligneId: l.id,
        quantiteRecue: this.saisies[l.id].recue,
        quantiteConforme: this.saisies[l.id].conforme,
        observation: this.saisies[l.id].observation.trim() || null,
      })),
    });
  }
}
