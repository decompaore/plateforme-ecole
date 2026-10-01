import { Component, computed, DestroyRef, ElementRef, inject, input, OnInit, signal, viewChildren } from '@angular/core';
import { RouterLink } from '@angular/router';

import { messageErreur } from '../core/erreurs';
import { Affectation } from '../core/modeles';
import { dateLongue } from '../core/outils';
import { EleveLocal, ListesService } from '../hors-ligne/listes.service';
import { EvaluationServeur, LIBELLE_TYPE_EVALUATION, NoteEleve, NotesService } from '../hors-ligne/notes.service';

interface Saisie {
  texte: string;
  absent: boolean;
}

/**
 * Lit une note saisie au clavier : « 14,5 », « 14.25 ». Vide : pas de note.
 * NaN : illisible ou plus de deux décimales (le serveur garde deux décimales).
 */
export function lireNote(texte: string): number | null {
  const t = texte.trim().replace(',', '.');
  if (t === '') {
    return null;
  }
  return /^\d+(\.\d{1,2})?$/.test(t) ? Number(t) : NaN;
}

/** Feuille de notes d'une évaluation, utilisable sans réseau. */
@Component({
  selector: 'app-notes-saisie',
  imports: [RouterLink],
  templateUrl: './notes-saisie.page.html',
  styleUrl: './notes-saisie.page.scss',
})
export class NotesSaisiePage implements OnInit {
  readonly classeId = input.required<string>();
  readonly matiereId = input.required<string>();
  readonly evaluationId = input.required<string>();

  private readonly listes = inject(ListesService);
  protected readonly notes = inject(NotesService);
  private readonly champs = viewChildren<ElementRef<HTMLInputElement>>('champ');

  protected readonly types = LIBELLE_TYPE_EVALUATION;
  protected readonly dateLongue = dateLongue;
  protected readonly affectation = signal<Affectation | null>(null);
  protected readonly evaluation = signal<EvaluationServeur | null>(null);
  protected readonly eleves = signal<EleveLocal[]>([]);
  protected readonly saisies = signal<Record<string, Saisie>>({});
  private readonly initiales = signal<Record<string, Saisie>>({});
  protected readonly complete = signal(true);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);

  /** Élèves dont la note a changé depuis l'ouverture ou le dernier enregistrement. */
  protected readonly modifiees = computed(() => {
    const avant = this.initiales();
    return Object.entries(this.saisies())
      .filter(([id, s]) => s.texte.trim() !== (avant[id]?.texte ?? '').trim() || s.absent !== (avant[id]?.absent ?? false))
      .map(([id]) => id);
  });

  protected readonly erreurs = computed(() => {
    const bareme = this.evaluation()?.bareme ?? 20;
    const fautes = new Set<string>();
    for (const [id, s] of Object.entries(this.saisies())) {
      const v = lireNote(s.texte);
      if (!s.absent && v !== null && (Number.isNaN(v) || v < 0 || v > bareme)) {
        fautes.add(id);
      }
    }
    return fautes;
  });

  protected readonly statistiques = computed(() => {
    const valeurs = Object.values(this.saisies())
      .filter((s) => !s.absent)
      .map((s) => lireNote(s.texte))
      .filter((v): v is number => v !== null && !Number.isNaN(v));
    const absents = Object.values(this.saisies()).filter((s) => s.absent).length;
    const moyenne = valeurs.length ? valeurs.reduce((a, b) => a + b, 0) / valeurs.length : null;
    return { notees: valeurs.length, absents, moyenne };
  });

  /** Opération en attente ou refusée pour cette évaluation (la création passe avant les notes). */
  protected readonly operation = computed(() => {
    const ops = this.notes.operations().filter((o) => o.evaluationId === this.evaluationId());
    return ops.find((o) => o.type === 'CREATION') ?? ops[0];
  });

  // Correction d'une création refusée
  protected readonly cDate = signal('');
  protected readonly cBareme = signal('');
  protected readonly cPoids = signal('');

  constructor() {
    // Fermeture de l'onglet ou de l'application avec des notes non enregistrées
    const avertir = (e: BeforeUnloadEvent) => {
      if (this.aDesModifications()) {
        e.preventDefault();
      }
    };
    window.addEventListener('beforeunload', avertir);
    inject(DestroyRef).onDestroy(() => window.removeEventListener('beforeunload', avertir));
  }

  protected async corrigerCreation(): Promise<void> {
    const op = this.operation();
    const bareme = Number(this.cBareme().replace(',', '.'));
    const poids = Number(this.cPoids().replace(',', '.'));
    if (!op || !this.cDate() || !(bareme > 0 && bareme <= 100) || !(poids > 0 && poids <= 10)) {
      return;
    }
    await this.notes.corrigerCreation(op, { date: this.cDate(), bareme, poids });
    this.evaluation.update((e) => (e ? { ...e, date: this.cDate(), bareme, poids } : e));
  }

  protected preparerCorrection(): void {
    const ev = this.evaluation();
    this.cDate.set(ev?.date ?? '');
    this.cBareme.set(String(ev?.bareme ?? 20));
    this.cPoids.set(String(ev?.poids ?? 1));
  }

  async ngOnInit(): Promise<void> {
    try {
      const affectations = await this.listes.affectations();
      this.affectation.set(
        affectations?.affectations.find((a) => a.classeId === this.classeId() && a.matiereId === this.matiereId()) ?? null,
      );
      const [classe, feuille] = await Promise.all([this.listes.eleves(this.classeId()), this.notes.feuille(this.evaluationId())]);
      if (!classe) {
        this.erreur.set("La liste de cette classe n'est pas sur ce téléphone. Mettez à jour les listes avec du réseau.");
        return;
      }
      if (!feuille.evaluation) {
        this.erreur.set("Cette évaluation n'est pas sur ce téléphone. Ouvrez-la une fois avec du réseau.");
        return;
      }
      this.evaluation.set(feuille.evaluation);
      this.complete.set(feuille.complete);
      this.eleves.set(classe.eleves);
      const saisies = Object.fromEntries(classe.eleves.map((e) => [e.inscriptionId, versSaisie(feuille.notes[e.inscriptionId])]));
      this.saisies.set(saisies);
      this.initiales.set(structuredClone(saisies));
      this.preparerCorrection();
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  protected saisir(id: string, texte: string): void {
    this.message.set(null);
    this.saisies.update((s) => ({ ...s, [id]: { texte, absent: false } }));
  }

  protected basculerAbsent(id: string): void {
    this.message.set(null);
    this.saisies.update((s) => {
      const absent = !s[id]?.absent;
      // Absence retirée : on retrouve la note d'origine (sinon le champ vide effacerait la note du serveur)
      const initiale = this.initiales()[id];
      const texte = absent ? '' : initiale && !initiale.absent ? initiale.texte : '';
      return { ...s, [id]: { texte, absent } };
    });
  }

  /** Entrée : passe à l'élève suivant, comme sur une feuille papier. */
  protected suivant(index: number, e: Event): void {
    e.preventDefault();
    const champs = this.champs();
    champs[index + 1]?.nativeElement.focus();
    champs[index + 1]?.nativeElement.select();
  }

  async enregistrer(): Promise<void> {
    const ev = this.evaluation();
    const a = this.affectation();
    if (!ev || this.erreurs().size > 0 || this.modifiees().length === 0) {
      return;
    }
    const modifiees: Record<string, NoteEleve> = {};
    for (const id of this.modifiees()) {
      const s = this.saisies()[id];
      modifiees[id] = { valeur: s.absent ? null : lireNote(s.texte), absent: s.absent };
    }
    try {
      await this.notes.enregistrerNotes(ev, modifiees, {
        classeCode: a?.classeCode ?? '',
        matiereLibelle: a?.matiereLibelle ?? '',
      });
      this.initiales.set(structuredClone(this.saisies()));
      this.message.set(
        `${Object.keys(modifiees).length} note(s) enregistrée(s) sur le téléphone. Elles partent dès qu'il y a du réseau.`,
      );
    } catch (e) {
      this.erreur.set(messageErreur(e, "Les notes n'ont pas pu être enregistrées sur le téléphone."));
    }
  }

  /** Utilisé par la garde de sortie : prévenir avant de perdre une saisie non enregistrée. */
  aDesModifications(): boolean {
    return this.modifiees().length > 0;
  }
}

function versSaisie(n: NoteEleve | undefined): Saisie {
  if (!n) {
    return { texte: '', absent: false };
  }
  return { texte: n.absent || n.valeur === null ? '' : String(n.valeur).replace('.', ','), absent: n.absent };
}
