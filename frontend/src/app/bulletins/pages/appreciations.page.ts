import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { messageErreur } from '../../core/erreurs';
import { Affectation } from '../../core/modeles';
import { dateLocale } from '../../core/outils';
import { ListesService, PeriodeLocale } from '../../hors-ligne/listes.service';
import { periodeParDefaut } from '../../hors-ligne/periodes';
import { BulletinsApi } from '../bulletins-api.service';

interface Ligne {
  inscriptionId: string;
  nom: string;
  prenoms: string;
  texte: string;
  initial: string;
}

/** Longueur maximale d'une appréciation (ConseilService). */
export const LONGUEUR_APPRECIATION = 200;

/**
 * Appréciations de l'enseignant sur le bulletin (v0.43) : une phrase par élève, pour sa matière et la période.
 * En ligne uniquement ; elles ne se modifient plus une fois les bulletins publiés.
 */
@Component({
  selector: 'app-appreciations',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page">
      <a [routerLink]="['/notes', classeId(), matiereId()]" class="bouton discret retour">‹ Évaluations</a>
      <h1>Appréciations · {{ affectation()?.classeCode }} · {{ affectation()?.matiereLibelle }}</h1>
      @if (erreur()) {
        <div class="alerte erreur" role="alert">{{ erreur() }}</div>
      }
      @if (message()) {
        <div class="alerte succes" role="status">{{ message() }}</div>
      }

      @if (periodes().length > 0) {
        <div class="champ">
          <label for="periode">Période</label>
          <select id="periode" [value]="periodeId()" (change)="changerPeriode($any($event.target).value)">
            @for (p of periodes(); track p.id) {
              <option [value]="p.id">{{ p.libelle }}</option>
            }
          </select>
        </div>
      }

      @if (lignes().length > 0) {
        <p class="doux">Une phrase par élève ({{ longueur }} caractères au plus) : elle figure sur son bulletin. Laissez vide pour ne rien écrire.</p>
        <form class="carte" (ngSubmit)="enregistrer()">
          @for (l of lignes(); track l.inscriptionId; let i = $index) {
            <div class="champ">
              <label [for]="'app' + i"><strong>{{ l.nom }}</strong> {{ l.prenoms }}</label>
              <input [id]="'app' + i" [name]="'app' + i" [maxlength]="longueur" [(ngModel)]="l.texte" [disabled]="envoi()" />
            </div>
          }
          <button type="submit" class="bouton plein" [disabled]="envoi()">Enregistrer les appréciations</button>
        </form>
      } @else if (!chargement() && periodeId()) {
        <p class="doux">Aucun élève dans cette classe.</p>
      } @else if (chargement()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .retour {
      margin-left: -0.5rem;
    }
  `,
})
export class AppreciationsPage implements OnInit {
  readonly classeId = input.required<string>();
  readonly matiereId = input.required<string>();

  private readonly listes = inject(ListesService);
  private readonly api = inject(BulletinsApi);

  protected readonly longueur = LONGUEUR_APPRECIATION;
  protected readonly affectation = signal<Affectation | null>(null);
  protected readonly periodes = signal<PeriodeLocale[]>([]);
  protected readonly periodeId = signal('');
  protected readonly lignes = signal<Ligne[]>([]);
  protected readonly chargement = signal(true);
  protected readonly envoi = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly periode = computed(() => this.periodes().find((p) => p.id === this.periodeId()));

  async ngOnInit(): Promise<void> {
    try {
      const affectations = await this.listes.affectations();
      const a = affectations?.affectations.find((x) => x.classeId === this.classeId() && x.matiereId === this.matiereId());
      if (!a || !affectations) {
        this.erreur.set('Cette classe et cette matière ne font pas partie de vos affectations.');
        return;
      }
      this.affectation.set(a);
      const [classe, periodes] = await Promise.all([this.listes.eleves(a.classeId), this.listes.periodes(affectations.anneeId)]);
      const duProfil = (periodes?.periodes ?? [])
        .filter((p) => !classe?.profilId || p.profilId === classe.profilId)
        .sort((x, y) => x.ordre - y.ordre);
      this.periodes.set(duProfil);
      const choisie = periodeParDefaut(duProfil, classe?.profilId, dateLocale());
      if (choisie) {
        await this.changerPeriode(choisie.id);
      }
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  protected async changerPeriode(id: string): Promise<void> {
    this.periodeId.set(id);
    this.message.set(null);
    this.erreur.set(null);
    this.chargement.set(true);
    try {
      const liste = await this.api.appreciations(this.classeId(), id, this.matiereId());
      this.lignes.set(liste.map((x) => ({ inscriptionId: x.inscriptionId, nom: x.nom, prenoms: x.prenoms, texte: x.texte ?? '', initial: x.texte ?? '' })));
    } catch (e) {
      this.lignes.set([]);
      this.erreur.set(messageErreur(e, 'Les appréciations demandent une connexion.'));
    } finally {
      this.chargement.set(false);
    }
  }

  protected async enregistrer(): Promise<void> {
    // Seules les lignes modifiées partent (un texte vide efface l'appréciation)
    const modifiees = this.lignes().filter((l) => l.texte.trim() !== l.initial.trim());
    if (modifiees.length === 0) {
      this.message.set('Rien à enregistrer.');
      return;
    }
    this.envoi.set(true);
    this.erreur.set(null);
    try {
      const r = await this.api.enregistrerAppreciations(
        this.classeId(),
        this.periodeId(),
        this.matiereId(),
        modifiees.map((l) => ({ inscriptionId: l.inscriptionId, texte: l.texte.trim() })),
      );
      this.lignes.set(r.map((x) => ({ inscriptionId: x.inscriptionId, nom: x.nom, prenoms: x.prenoms, texte: x.texte ?? '', initial: x.texte ?? '' })));
      this.message.set(`${modifiees.length} appréciation(s) enregistrée(s).`);
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.envoi.set(false);
    }
  }
}
