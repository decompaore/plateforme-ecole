import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { dateCourte } from '../../core/outils';
import { filtrer } from '../../core/recherche';
import { RechercheComponent } from '../../partage/recherche.component';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import {
  BilanHeuresVue, Cumul, EtatHeures, heuresOuTiret, LIBELLE_ETAT_HEURES, LigneBilan, pourcent,
} from '../modeles-progression';
import { ProgressionApi } from '../progression-api.service';

type Vue = 'enseignants' | 'classes' | 'matieres' | 'detail';

/** Pastille de l'état d'une matière. */
export function pastilleHeures(etat: EtatHeures): string {
  return etat === 'EN_RETARD' || etat === 'SANS_VOLUME' ? 'absent' : etat === 'A_JOUR' || etat === 'TERMINE' ? 'present' : '';
}

/**
 * Bilan des heures dues et réalisées (v0.50) : volume annuel fixé par le référentiel, dû à ce
 * jour au prorata des jours de cours écoulés, réalisé tiré du cahier de textes. La direction voit
 * les cumuls par enseignant, par classe et par matière ; l'enseignant voit ses propres heures.
 */
@Component({
  selector: 'app-bilan-heures',
  imports: [NgTemplateOutlet, RouterLink, RechercheComponent],
  template: `
    <div class="page large">
      <div class="entete-section">
        <h1>{{ mien() ? 'Mes heures' : 'Bilan des heures' }}</h1>
        <span class="actions-ligne">
          @if (!mien()) {
            <a class="bouton secondaire petit" routerLink="/progression/suivi">Progressions</a>
          }
          <button type="button" class="bouton secondaire petit" [disabled]="!bilan() || actionExport.enCours()" (click)="exporter('xlsx')">Excel</button>
          <button type="button" class="bouton secondaire petit" [disabled]="!bilan() || actionExport.enCours()" (click)="exporter('pdf')">PDF</button>
        </span>
      </div>

      @if (action.erreur()) {
        <div class="alerte erreur" role="alert">{{ action.erreur() }}</div>
      }
      @if (actionExport.erreur()) {
        <div class="alerte erreur" role="alert">{{ actionExport.erreur() }}</div>
      }

      @if (bilan(); as b) {
        <p class="doux">
          Année {{ b.annee }} · situation au {{ dateCourte(b.jour) }} · {{ b.avancementAnnee }} % des jours de cours écoulés.
          Le dû à ce jour est le volume annuel du référentiel au prorata des jours de cours écoulés ; le réalisé vient du cahier de textes.
        </p>

        <div class="chiffres">
          <div><strong>{{ heuresOuTiret(b.total.volumeAnnuel) }}</strong><span>dues dans l'année</span></div>
          <div><strong>{{ heuresOuTiret(b.total.duADate) }}</strong><span>dues à ce jour</span></div>
          <div><strong>{{ heuresOuTiret(b.total.realise) }}</strong><span>réalisées</span></div>
          <div [class.alerte-valeur]="(b.total.tauxADate ?? 100) < 90"><strong>{{ pourcent(b.total.tauxADate) }}</strong><span>du dû à ce jour</span></div>
          <div [class.alerte-valeur]="b.enRetard > 0"><strong>{{ b.enRetard }}</strong><span>matière(s) en retard</span></div>
        </div>

        @if (b.sansVolume > 0) {
          <div class="alerte attention">
            {{ b.sansVolume }} matière(s) sans volume annuel ni volume hebdomadaire : leurs heures ne comptent pas dans le dû.
            @if (!mien()) { Saisissez le volume fixé par le référentiel dans le programme de chaque classe (<a routerLink="/admin/classes">Classes</a>). }
          </div>
        }
        @if (estimees() > 0) {
          <p class="doux">{{ estimees() }} matière(s) au volume <em>estimé</em> (heures par semaine × semaines de cours), faute de volume annuel saisi.</p>
        }

        @if (!mien()) {
          <div class="bascule" role="group" aria-label="Regroupement">
            <button type="button" [class.actif]="vue() === 'enseignants'" (click)="vue.set('enseignants')">Par enseignant</button>
            <button type="button" [class.actif]="vue() === 'classes'" (click)="vue.set('classes')">Par classe</button>
            <button type="button" [class.actif]="vue() === 'matieres'" (click)="vue.set('matieres')">Par matière</button>
            <button type="button" [class.actif]="vue() === 'detail'" (click)="vue.set('detail')">Détail</button>
          </div>
          <app-recherche libelle="Classe, matière ou enseignant" [(valeur)]="filtre" [total]="total()" [trouves]="trouves()" />
        }

        <div class="carte tableau-defilant">
          <table class="tableau">
            <thead>
              <tr>
                @switch (vueEffective()) {
                  @case ('enseignants') { <th>Enseignant</th><th class="nombre">Classes</th> }
                  @case ('classes') { <th>Classe</th><th class="nombre">Matières</th> }
                  @case ('matieres') { <th>Matière</th><th class="nombre">Classes</th> }
                  @default { <th>Classe · matière</th>@if (!mien()) { <th>Enseignant</th> } }
                }
                <th class="nombre">Dû annuel</th>
                <th class="nombre">Dû à ce jour</th>
                <th class="nombre">Réalisé</th>
                <th class="nombre">Reste</th>
                <th>Réalisation</th>
                <th class="nombre">Écart</th>
                @if (vueEffective() === 'detail') { <th>État</th> } @else { <th class="nombre">En retard</th> }
              </tr>
            </thead>
            <tbody>
              @switch (vueEffective()) {
                @case ('enseignants') {
                  @for (t of enseignants(); track t.engagementId) {
                    <tr>
                      <td>
                        <button type="button" class="lien" (click)="voirEnseignant(t.enseignant)">{{ t.enseignant ?? '—' }}</button>
                        @if (t.statutEnseignant === 'VACATAIRE') { <span class="pastille">vacataire</span> }
                      </td>
                      <td class="nombre">{{ t.classes }}</td>
                      <ng-container *ngTemplateOutlet="cumul; context: { $implicit: t.cumul }" />
                      <td class="nombre">{{ t.enRetard || '' }}</td>
                    </tr>
                  } @empty { <tr><td colspan="9" class="doux">Aucun enseignant.</td></tr> }
                }
                @case ('classes') {
                  @for (t of classes(); track t.classeId) {
                    <tr>
                      <td><button type="button" class="lien" (click)="voirTexte(t.classeCode)">{{ t.classeCode }}</button></td>
                      <td class="nombre">{{ t.matieres }}</td>
                      <ng-container *ngTemplateOutlet="cumul; context: { $implicit: t.cumul }" />
                      <td class="nombre">{{ t.enRetard || '' }}</td>
                    </tr>
                  } @empty { <tr><td colspan="9" class="doux">Aucune classe.</td></tr> }
                }
                @case ('matieres') {
                  @for (t of matieres(); track t.matiereId) {
                    <tr>
                      <td><button type="button" class="lien" (click)="voirTexte(t.matiereLibelle)">{{ t.matiereLibelle }}</button></td>
                      <td class="nombre">{{ t.classes }}</td>
                      <ng-container *ngTemplateOutlet="cumul; context: { $implicit: t.cumul }" />
                      <td class="nombre">{{ t.enRetard || '' }}</td>
                    </tr>
                  } @empty { <tr><td colspan="9" class="doux">Aucune matière.</td></tr> }
                }
                @default {
                  @for (l of lignes(); track l.classeId + l.matiereId) {
                    <tr>
                      <td>
                        <strong>{{ l.classeCode }}</strong> · {{ l.matiereLibelle }}
                        @if (l.sourceVolume === 'ESTIME') { <span class="doux">(volume estimé)</span> }
                        @if (autres(l); as a) { <br /><span class="doux">{{ a }}</span> }
                      </td>
                      @if (!mien()) { <td>{{ l.enseignant ?? 'sans enseignant' }}</td> }
                      <ng-container *ngTemplateOutlet="cumul; context: { $implicit: l.cumul }" />
                      <td><span class="pastille {{ pastilleHeures(l.etat) }}">{{ libelleEtat[l.etat] }}</span></td>
                    </tr>
                  } @empty { <tr><td colspan="9" class="doux">{{ b.lignes.length ? 'Aucune ligne ne correspond.' : 'Aucune matière pour cette année.' }}</td></tr> }
                }
              }
            </tbody>
          </table>
        </div>

        <ng-template #cumul let-c>
          <td class="nombre">{{ heuresOuTiret(c.volumeAnnuel) }}</td>
          <td class="nombre">{{ heuresOuTiret(c.duADate) }}</td>
          <td class="nombre">{{ heuresOuTiret(c.realise) }}</td>
          <td class="nombre">{{ heuresOuTiret(c.reste) }}</td>
          <td>
            @if (c.tauxRealisation !== null) {
              <span class="jauge" [attr.aria-label]="pourcent(c.tauxRealisation) + ' du volume annuel'">
                <span class="fait" [style.width.%]="largeur(c.tauxRealisation)"></span>
                <span class="repere" [style.left.%]="largeur(repere(c))"></span>
              </span>
              <span class="doux">{{ pourcent(c.tauxRealisation) }}</span>
            } @else { <span class="doux">—</span> }
          </td>
          <td class="nombre" [class.negatif]="(c.ecart ?? 0) < 0">{{ ecart(c.ecart) }}</td>
        </ng-template>
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .chiffres {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(9rem, 1fr));
      gap: 0.75rem;
      margin: 0.5rem 0 1rem;
      div {
        display: flex;
        flex-direction: column;
        padding: 0.75rem 1rem;
        border-radius: var(--rayon);
        background: var(--surface);
        border: 1px solid var(--bordure);
      }
      strong {
        font-size: 1.4rem;
        font-variant-numeric: tabular-nums;
      }
      span {
        font-size: 0.85rem;
        color: var(--texte-doux);
      }
      .alerte-valeur strong {
        color: var(--absent);
      }
    }
    .bascule {
      display: inline-flex;
      flex-wrap: wrap;
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      padding: 0.2rem;
      margin-bottom: 0.75rem;
      background: var(--surface);
      button {
        min-height: 38px;
        padding: 0 0.8rem;
        border: none;
        background: none;
        border-radius: calc(var(--rayon) - 4px);
        font: inherit;
        font-weight: 600;
        color: var(--texte-doux);
        cursor: pointer;
      }
      button.actif {
        background: var(--primaire);
        color: var(--sur-primaire);
      }
    }
    .jauge {
      position: relative;
      display: inline-block;
      width: 5rem;
      height: 0.55rem;
      margin-right: 0.4rem;
      border-radius: 999px;
      background: var(--bordure);
      vertical-align: middle;
      overflow: hidden;
      .fait {
        position: absolute;
        inset: 0 auto 0 0;
        background: var(--primaire);
      }
      .repere {
        position: absolute;
        top: 0;
        bottom: 0;
        width: 2px;
        background: var(--texte);
      }
    }
    .negatif {
      color: var(--absent);
      font-weight: 600;
    }
    .lien {
      border: none;
      background: none;
      padding: 0;
      font: inherit;
      font-weight: 600;
      color: var(--primaire);
      cursor: pointer;
      text-align: left;
    }
  `,
})
export class BilanHeuresPage {
  private readonly api = inject(ProgressionApi);
  private readonly annee = inject(AnneeCourante);

  /** Vrai sur « Mes heures » (donnée de la route) : le bilan de l'enseignant connecté. */
  readonly mien = input(false);

  protected readonly dateCourte = dateCourte;
  protected readonly heuresOuTiret = heuresOuTiret;
  protected readonly pourcent = pourcent;
  protected readonly pastilleHeures = pastilleHeures;
  protected readonly libelleEtat = LIBELLE_ETAT_HEURES;
  protected readonly action = new Action();
  protected readonly actionExport = new Action();
  protected readonly bilan = signal<BilanHeuresVue | null>(null);
  protected readonly vue = signal<Vue>('enseignants');
  protected readonly filtre = signal('');

  protected readonly vueEffective = computed<Vue>(() => (this.mien() ? 'detail' : this.vue()));
  protected readonly estimees = computed(() => this.bilan()?.lignes.filter((l) => l.sourceVolume === 'ESTIME').length ?? 0);

  protected readonly enseignants = computed(() =>
    filtrer(this.bilan()?.enseignants ?? [], this.filtre(), (t) => [t.enseignant]),
  );
  protected readonly classes = computed(() =>
    filtrer(this.bilan()?.classes ?? [], this.filtre(), (t) => [t.classeCode, t.filiereCode, t.niveau]),
  );
  protected readonly matieres = computed(() =>
    filtrer(this.bilan()?.matieres ?? [], this.filtre(), (t) => [t.matiereLibelle, t.matiereCode]),
  );
  protected readonly lignes = computed(() =>
    filtrer(this.bilan()?.lignes ?? [], this.mien() ? '' : this.filtre(), (l) => [
      l.classeCode, l.matiereLibelle, l.matiereCode, l.enseignant,
    ]),
  );

  protected readonly total = computed(() => {
    const b = this.bilan();
    const v = this.vueEffective();
    return !b ? 0 : v === 'enseignants' ? b.enseignants.length : v === 'classes' ? b.classes.length
      : v === 'matieres' ? b.matieres.length : b.lignes.length;
  });
  protected readonly trouves = computed(() => {
    const v = this.vueEffective();
    return v === 'enseignants' ? this.enseignants().length : v === 'classes' ? this.classes().length
      : v === 'matieres' ? this.matieres().length : this.lignes().length;
  });

  constructor() {
    effect(() => {
      const mien = this.mien();
      const a = mien ? null : this.annee.annee();
      untracked(() => {
        if (mien) {
          void this.charger(null);
        } else if (a) {
          void this.charger(a.id);
        } else {
          void this.annee.charger().catch(() => undefined);
        }
      });
    });
  }

  protected largeur(taux: number | null): number {
    return Math.max(0, Math.min(100, taux ?? 0));
  }

  /** Repère du dû à ce jour sur la jauge du volume annuel. */
  protected repere(c: Cumul): number | null {
    return c.volumeAnnuel && c.duADate !== null ? (c.duADate * 100) / c.volumeAnnuel : null;
  }

  protected ecart(e: number | null): string {
    if (e === null) {
      return '—';
    }
    return (e > 0 ? '+' : '') + heuresOuTiret(e);
  }

  /** Heures faites par d'autres enseignants (changement d'enseignant en cours d'année). */
  protected autres(l: LigneBilan): string | null {
    const a = l.intervenants.filter((i) => i.engagementId !== l.engagementId);
    return a.length ? 'Dont ' + a.map((i) => `${heuresOuTiret(i.heures)} par ${i.enseignant ?? 'un autre enseignant'}`).join(', ') : null;
  }

  protected voirEnseignant(nom: string | null): void {
    this.filtre.set(nom ?? '');
    this.vue.set('detail');
  }

  protected voirTexte(texte: string): void {
    this.filtre.set(texte);
    this.vue.set('detail');
  }

  protected async exporter(format: 'xlsx' | 'pdf'): Promise<void> {
    const b = this.bilan();
    if (!b) {
      return;
    }
    const blob = await this.actionExport.executer(
      () => this.api.exportBilanHeures(this.mien() ? null : b.anneeId, format),
      "L'export n'a pas pu être préparé.",
    );
    if (blob) {
      enregistrerFichier(blob, `${this.mien() ? 'mes-heures' : 'bilan-heures'}-${b.jour}.${format}`);
    }
  }

  private async charger(anneeId: string | null): Promise<void> {
    const b = await this.action.executer(() => (anneeId ? this.api.bilanHeures(anneeId) : this.api.mesHeures()));
    if (b && (anneeId === null || anneeId === this.annee.annee()?.id)) {
      this.bilan.set(b);
    }
  }
}
