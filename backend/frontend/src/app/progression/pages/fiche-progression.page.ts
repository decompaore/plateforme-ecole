import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte, dateHeureCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import {
  erreurFiche,
  FicheVue,
  heures,
  lireHeures,
  SequenceEditee,
  SUPERVISION,
  versEdition,
  versSaisie,
} from '../modeles-progression';
import { ProgressionApi } from '../progression-api.service';
import { pastille } from './mes-progressions.page';

function vide(): SequenceEditee {
  return { titre: '', contenu: '', competences: '', heures: '', semaineDebut: '' };
}

/**
 * Fiche de progression d'une matière dans une classe. L'enseignant y prépare ses séquences
 * et la soumet ; le censeur ou le chef des travaux la lit et la vise ou la renvoie.
 */
@Component({
  selector: 'app-fiche-progression',
  imports: [FormsModule, RouterLink],
  templateUrl: './fiche-progression.page.html',
  styleUrl: './fiche-progression.page.scss',
})
export class FicheProgressionPage implements OnInit {
  private readonly api = inject(ProgressionApi);
  private readonly session = inject(SessionService);

  readonly classeId = input.required<string>();
  readonly matiereId = input.required<string>();

  protected readonly heures = heures;
  protected readonly Math = Math;
  protected readonly pastille = pastille;
  protected readonly dateCourte = dateCourte;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly action = new Action();
  protected readonly envoi = new Action();

  protected readonly fiche = signal<FicheVue | null>(null);
  protected readonly sequences = signal<SequenceEditee[]>([]);
  protected readonly modifiee = signal(false);
  protected readonly message = signal<string | null>(null);

  protected readonly retour = computed(() => (this.session.aLeRole(...SUPERVISION) && !this.fiche()?.modifiable ? '/progression/suivi' : '/progression'));
  protected readonly erreurSaisie = computed(() => erreurFiche(this.sequences()));
  protected readonly totalHeures = computed(() => this.sequences().reduce((t, s) => t + (lireHeures(s.heures) ?? 0), 0));

  // Visa
  protected readonly renvoiOuvert = signal(false);
  protected readonly commentaire = signal('');

  async ngOnInit(): Promise<void> {
    const f = await this.action.executer(() => this.api.fiche(this.classeId(), this.matiereId()));
    if (f) {
      this.afficher(f);
    }
  }

  private afficher(f: FicheVue): void {
    this.fiche.set(f);
    this.sequences.set(f.sequences.length ? f.sequences.map(versEdition) : f.modifiable ? [vide()] : []);
    this.modifiee.set(false);
  }

  /** Les champs ngModel d'une séquence passent par ici pour garder la fiche « modifiée ». */
  protected modifier(i: number, champ: keyof SequenceEditee, valeur: string): void {
    this.sequences.set(this.sequences().map((s, j) => (j === i ? { ...s, [champ]: valeur } : s)));
    this.modifiee.set(true);
    this.message.set(null);
  }

  protected ajouter(): void {
    this.sequences.set([...this.sequences(), vide()]);
    this.modifiee.set(true);
  }

  protected retirer(i: number): void {
    this.sequences.set(this.sequences().filter((_, j) => j !== i));
    this.modifiee.set(true);
  }

  protected deplacer(i: number, sens: -1 | 1): void {
    const l = [...this.sequences()];
    const j = i + sens;
    if (j < 0 || j >= l.length) {
      return;
    }
    [l[i], l[j]] = [l[j], l[i]];
    this.sequences.set(l);
    this.modifiee.set(true);
  }

  protected async enregistrer(): Promise<boolean> {
    const f = this.fiche();
    if (!f || this.erreurSaisie()) {
      return false;
    }
    const r = await this.envoi.executer(() => this.api.enregistrer(f.classeId, f.matiereId, this.sequences().map(versSaisie)));
    if (r) {
      const etaitVisee = f.statut === 'VISEE';
      this.afficher(r);
      this.message.set(etaitVisee ? 'Enregistré. La fiche visée repasse en brouillon : soumettez-la à nouveau.' : 'Progression enregistrée.');
      return true;
    }
    return false;
  }

  /** Enregistre d'abord les modifications en cours, puis soumet au visa. */
  protected async soumettre(): Promise<void> {
    const f = this.fiche();
    if (!f || this.erreurSaisie()) {
      return;
    }
    if ((this.modifiee() || !f.id) && !(await this.enregistrer())) {
      return;
    }
    const r = await this.envoi.executer(() => this.api.soumettre(f.classeId, f.matiereId));
    if (r) {
      this.afficher(r);
      this.message.set(
        r.domaine === 'TECHNIQUE' ? 'Progression soumise au visa du chef des travaux.' : 'Progression soumise au visa du censeur.',
      );
    }
  }

  protected async viser(accepte: boolean): Promise<void> {
    const f = this.fiche();
    if (!f) {
      return;
    }
    const commentaire = this.commentaire().trim() || null;
    if (!accepte && !commentaire) {
      this.envoi.erreur.set("Dites à l'enseignant ce qu'il faut revoir.");
      return;
    }
    const r = await this.envoi.executer(() => this.api.viser(f.classeId, f.matiereId, accepte, commentaire));
    if (r) {
      this.afficher(r);
      this.renvoiOuvert.set(false);
      this.commentaire.set('');
      this.message.set(accepte ? 'Progression visée.' : "Progression renvoyée à l'enseignant avec votre commentaire.");
    }
  }
}
