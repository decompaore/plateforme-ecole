import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { estErreurReseau, messageErreur, probleme } from '../core/erreurs';
import { nouvelIdentifiant } from '../core/outils';
import { API, SessionService } from '../core/session.service';
import { dateLocale } from '../core/outils';
import { ListesService } from './listes.service';
import { periodeParDefaut } from './periodes';
import { STOCKAGE } from './stockage';

export type TypeEvaluation = 'DEVOIR' | 'INTERROGATION' | 'COMPOSITION' | 'TP' | 'ATELIER' | 'AUTRE';

export const LIBELLE_TYPE_EVALUATION: Record<TypeEvaluation, string> = {
  DEVOIR: 'Devoir',
  INTERROGATION: 'Interrogation',
  COMPOSITION: 'Composition',
  TP: 'Travaux pratiques',
  ATELIER: 'Atelier',
  AUTRE: 'Autre',
};

/** Évaluation telle que le serveur la décrit (EvaluationVue). */
export interface EvaluationServeur {
  id: string;
  classeId: string;
  matiereId: string;
  periodeId: string;
  libelle: string;
  type: TypeEvaluation;
  date: string;
  bareme: number;
  poids: number;
  notesSaisies: number;
  effectif: number;
}

/** Note d'un élève : une valeur, ou absent, ou rien (null et absent=false : pas de note). */
export interface NoteEleve {
  valeur: number | null;
  absent: boolean;
}

interface LigneFeuille {
  inscriptionId: string;
  valeur: number | null;
  absent: boolean;
}

interface FeuilleServeur {
  evaluation: EvaluationServeur;
  lignes: LigneFeuille[];
  ignorees: string[] | null;
}

/** Copie locale d'une évaluation : ce que le serveur a confirmé, et la date de cette copie. */
export interface EvaluationLocale {
  evaluation: EvaluationServeur;
  /** Notes confirmées par le serveur (absent si la feuille n'a jamais été chargée sur l'appareil). */
  notes?: Record<string, NoteEleve>;
  copieLe: string;
}

export type EtatOperation = 'EN_ATTENTE' | 'REFUSE';

/**
 * Opération à envoyer : création d'une évaluation (identifiant généré sur l'appareil,
 * donc renvoyable sans doublon) ou saisie de notes (idempotente côté serveur).
 */
export interface OperationNotes {
  cle: string;
  type: 'CREATION' | 'NOTES';
  evaluationId: string;
  classeId: string;
  matiereId: string;
  periodeId: string;
  /** Pour CREATION : l'évaluation à créer. */
  evaluation?: Omit<EvaluationServeur, 'id' | 'notesSaisies' | 'effectif'>;
  /** Pour NOTES : seulement les élèves modifiés sur l'appareil (les autres ne sont jamais effacés). */
  notes?: Record<string, NoteEleve>;
  /** Libellés pour l'affichage hors connexion. */
  libelle: string;
  classeCode: string;
  matiereLibelle: string;
  etat: EtatOperation;
  message?: string;
  saisieLe: string;
}

export interface BilanNotes {
  envoyees: number;
  refusees: number;
  restantes: number;
  erreur?: string;
}

const INTERVALLE_MS = 60 * 1000;
/** Nombre maximal de notes par requête accepté par le serveur. */
const NOTES_PAR_ENVOI = 200;

/**
 * Évaluations et notes de l'enseignant, utilisables sans réseau.
 * <ul>
 *   <li>Lecture « réseau d'abord, appareil ensuite » des évaluations et des feuilles de notes.</li>
 *   <li>Création et saisie mises en file sur l'appareil, envoyées dans l'ordre dès que possible.</li>
 *   <li>Seuls les élèves modifiés sont envoyés : une saisie hors connexion n'efface jamais une note
 *       saisie ailleurs (par le censeur, par exemple) pour un autre élève.</li>
 * </ul>
 */
@Injectable({ providedIn: 'root' })
export class NotesService {
  private readonly http = inject(HttpClient);
  private readonly stockage = inject(STOCKAGE);
  private readonly session = inject(SessionService);
  private readonly listes = inject(ListesService);

  private readonly operationsSignal = signal<OperationNotes[]>([]);
  private enCours?: Promise<BilanNotes>;
  private relancer = false;

  readonly operations = this.operationsSignal.asReadonly();
  readonly enAttente = computed(() => this.operationsSignal().filter((o) => o.etat === 'EN_ATTENTE').length);
  readonly refusees = computed(() => this.operationsSignal().filter((o) => o.etat === 'REFUSE').length);
  readonly synchronisation = signal(false);
  readonly dernierBilan = signal<BilanNotes | null>(null);

  demarrer(onDestroy?: (f: () => void) => void): void {
    if (typeof window === 'undefined') {
      return;
    }
    const tenter = () => void this.synchroniser();
    window.addEventListener('online', tenter);
    const minuterie = setInterval(() => {
      if (this.enAttente() > 0) {
        tenter();
      }
    }, INTERVALLE_MS);
    onDestroy?.(() => {
      window.removeEventListener('online', tenter);
      clearInterval(minuterie);
    });
  }

  // ---------------------------------------------------------------- clés

  private base(): string {
    const p = this.session.profil();
    if (!p?.etablissement) {
      throw new Error('Aucun établissement actif');
    }
    return `${p.utilisateurId}:${p.etablissement.id}:`;
  }

  private cleListe(classeId: string, matiereId: string, periodeId: string): string {
    return `cache:${this.base()}evaluations:${classeId}:${matiereId}:${periodeId}`;
  }

  private cleEvaluation(evaluationId: string): string {
    return `cache:${this.base()}evaluation:${evaluationId}`;
  }

  private prefixeOperations(): string | null {
    const p = this.session.profil();
    return p?.etablissement ? `notes:${p.utilisateurId}:${p.etablissement.id}:` : null;
  }

  private enLigne(): boolean {
    return this.session.jeton() !== null && (typeof navigator === 'undefined' || navigator.onLine !== false);
  }

  // ---------------------------------------------------------------- lecture

  async recharger(): Promise<void> {
    const prefixe = this.prefixeOperations();
    this.operationsSignal.set(prefixe ? await this.stockage.lister<OperationNotes>(prefixe) : []);
  }

  /** Évaluations d'une classe, d'une matière et d'une période, y compris celles créées sur l'appareil. */
  async evaluations(classeId: string, matiereId: string, periodeId: string): Promise<EvaluationServeur[]> {
    const cle = this.cleListe(classeId, matiereId, periodeId);
    let serveur: EvaluationServeur[] | undefined;
    if (this.enLigne()) {
      try {
        const toutes = await firstValueFrom(
          this.http.get<EvaluationServeur[]>(`${API}/classes/${classeId}/evaluations`, { params: { periodeId } }),
        );
        serveur = toutes.filter((e) => e.matiereId === matiereId);
        await this.stockage.ecrire(cle, serveur);
      } catch (e) {
        if (!estErreurReseau(e)) {
          throw e;
        }
      }
    }
    serveur ??= (await this.stockage.lire<EvaluationServeur[]>(cle)) ?? [];
    await this.recharger();
    const connues = new Set(serveur.map((e) => e.id));
    const locales = this.operationsSignal()
      .filter((o) => o.type === 'CREATION' && o.classeId === classeId && o.matiereId === matiereId && o.periodeId === periodeId)
      .filter((o) => !connues.has(o.evaluationId))
      .map((o) => ({ ...o.evaluation!, id: o.evaluationId, notesSaisies: 0, effectif: 0 }));
    return [...serveur, ...locales].sort((a, b) => a.date.localeCompare(b.date) || a.libelle.localeCompare(b.libelle));
  }

  /**
   * Feuille de notes : notes confirmées par le serveur, recouvertes par les modifications
   * pas encore envoyées. {@code complete} est faux si la feuille n'a jamais pu être chargée.
   */
  async feuille(evaluationId: string): Promise<{ evaluation?: EvaluationServeur; notes: Record<string, NoteEleve>; complete: boolean }> {
    const cle = this.cleEvaluation(evaluationId);
    let locale = await this.stockage.lire<EvaluationLocale>(cle);
    await this.recharger();
    const creation = this.operationsSignal().find((o) => o.type === 'CREATION' && o.evaluationId === evaluationId);
    if (this.enLigne() && !creation) {
      try {
        const f = await firstValueFrom(this.http.get<FeuilleServeur>(`${API}/evaluations/${evaluationId}/notes`));
        locale = { evaluation: f.evaluation, notes: versNotes(f.lignes), copieLe: new Date().toISOString() };
        await this.stockage.ecrire(cle, locale);
      } catch (e) {
        if (!estErreurReseau(e)) {
          throw e;
        }
      }
    }
    const evaluation =
      locale?.evaluation ?? (creation ? { ...creation.evaluation!, id: evaluationId, notesSaisies: 0, effectif: 0 } : undefined);
    const enAttente = this.operationsSignal().find((o) => o.type === 'NOTES' && o.evaluationId === evaluationId);
    return {
      evaluation,
      notes: { ...(locale?.notes ?? {}), ...(enAttente?.notes ?? {}) },
      complete: !!locale?.notes || !!creation,
    };
  }

  /** Télécharge, pour la préparation hors connexion, les évaluations et feuilles de la période. */
  async preparer(classeId: string, matiereId: string, periodeId: string): Promise<void> {
    if (!this.enLigne()) {
      return;
    }
    for (const e of await this.evaluations(classeId, matiereId, periodeId)) {
      if (!this.operationsSignal().some((o) => o.type === 'CREATION' && o.evaluationId === e.id)) {
        await this.feuille(e.id);
      }
    }
  }

  /**
   * Prépare toutes les matières de l'enseignant pour la période en cours (évaluations et
   * feuilles de notes), à la suite des listes de classes. Silencieux en cas d'échec.
   */
  async preparerTout(): Promise<void> {
    if (!this.enLigne()) {
      return;
    }
    try {
      const affectations = await this.listes.affectations();
      if (!affectations) {
        return;
      }
      const periodes = (await this.listes.periodes(affectations.anneeId))?.periodes ?? [];
      for (const a of affectations.affectations) {
        const classe = await this.listes.eleves(a.classeId);
        const periode = periodeParDefaut(periodes, classe?.profilId, dateLocale());
        if (periode) {
          await this.preparer(a.classeId, a.matiereId, periode.id);
        }
      }
    } catch {
      // Nouvel essai à la prochaine préparation
    }
  }

  // ---------------------------------------------------------------- écriture

  /** Crée l'évaluation sur l'appareil (identifiant généré ici), puis tente l'envoi. */
  async creer(
    d: Omit<EvaluationServeur, 'id' | 'notesSaisies' | 'effectif'>,
    libelles: { classeCode: string; matiereLibelle: string },
  ): Promise<string> {
    const prefixe = this.prefixeOperations();
    if (!prefixe) {
      throw new Error('Aucun établissement actif');
    }
    const evaluationId = nouvelIdentifiant();
    const saisieLe = new Date().toISOString();
    const op: OperationNotes = {
      cle: `${prefixe}${saisieLe}:${evaluationId}:creation`,
      type: 'CREATION',
      evaluationId,
      classeId: d.classeId,
      matiereId: d.matiereId,
      periodeId: d.periodeId,
      evaluation: d,
      libelle: d.libelle,
      ...libelles,
      etat: 'EN_ATTENTE',
      saisieLe,
    };
    await this.stockage.ecrire(op.cle, op);
    await this.recharger();
    void this.synchroniser();
    return evaluationId;
  }

  /**
   * Enregistre les notes modifiées. Une saisie déjà en attente pour la même évaluation est
   * complétée (une seule opération par évaluation, la plus récente valeur l'emporte).
   */
  async enregistrerNotes(
    evaluation: EvaluationServeur,
    modifiees: Record<string, NoteEleve>,
    libelles: { classeCode: string; matiereLibelle: string },
  ): Promise<void> {
    const prefixe = this.prefixeOperations();
    if (!prefixe || Object.keys(modifiees).length === 0) {
      return;
    }
    await this.recharger();
    const existante = this.operationsSignal().find((o) => o.type === 'NOTES' && o.evaluationId === evaluation.id);
    const saisieLe = new Date().toISOString();
    const op: OperationNotes = existante
      ? { ...existante, notes: { ...existante.notes, ...modifiees }, etat: 'EN_ATTENTE', message: undefined }
      : {
          // Après la création éventuelle de l'évaluation : la clé commence par l'instant de saisie
          cle: `${prefixe}${saisieLe}:${evaluation.id}:notes`,
          type: 'NOTES',
          evaluationId: evaluation.id,
          classeId: evaluation.classeId,
          matiereId: evaluation.matiereId,
          periodeId: evaluation.periodeId,
          notes: modifiees,
          libelle: evaluation.libelle,
          ...libelles,
          etat: 'EN_ATTENTE',
          saisieLe,
        };
    await this.stockage.ecrire(op.cle, op);
    await this.recharger();
    void this.synchroniser();
  }

  /** Abandonne une opération refusée (l'enseignant a lu le motif). */
  async abandonner(op: OperationNotes): Promise<void> {
    await this.stockage.supprimer(op.cle);
    if (op.type === 'CREATION') {
      // Les notes d'une évaluation jamais créée n'ont plus d'objet
      for (const o of this.operationsSignal().filter((x) => x.type === 'NOTES' && x.evaluationId === op.evaluationId)) {
        await this.stockage.supprimer(o.cle);
      }
    }
    await this.recharger();
  }

  // ---------------------------------------------------------------- envoi

  synchroniser(): Promise<BilanNotes> {
    if (this.enCours) {
      // Une demande pendant un envoi (retour du réseau, nouvelle saisie) : un nouveau passage suivra
      this.relancer = true;
      return this.enCours;
    }
    this.enCours = this.executer().finally(() => {
      this.enCours = undefined;
      this.synchronisation.set(false);
      if (this.relancer) {
        this.relancer = false;
        void this.synchroniser();
      }
    });
    return this.enCours;
  }

  private executer(): Promise<BilanNotes> {
    // Un seul envoi à la fois, y compris entre l'application installée et un onglet ouvert
    const verrous = typeof navigator !== 'undefined' ? navigator.locks : undefined;
    return verrous ? verrous.request('plateforme-ecoles-notes', () => this.envoyer()) : this.envoyer();
  }

  private async envoyer(): Promise<BilanNotes> {
    const bilan: BilanNotes = { envoyees: 0, refusees: 0, restantes: 0 };
    const prefixe = this.prefixeOperations();
    if (!prefixe) {
      return bilan;
    }
    const toutes = await this.stockage.lister<OperationNotes>(prefixe);
    const attente = toutes.filter((o) => o.etat === 'EN_ATTENTE');
    bilan.restantes = attente.length;
    if (attente.length === 0 || (typeof navigator !== 'undefined' && navigator.onLine === false)) {
      await this.recharger();
      return bilan;
    }
    if (this.session.sessionExpiree()) {
      bilan.erreur = 'Session expirée : reconnectez-vous pour envoyer les notes.';
      await this.recharger();
      return this.terminer(bilan);
    }
    this.synchronisation.set(true);
    if (!this.session.jeton() && !(await this.session.rafraichir())) {
      bilan.erreur = 'Envoi impossible pour le moment. Nouvel essai automatique.';
      await this.recharger();
      return this.terminer(bilan);
    }
    // Évaluations pas encore créées sur le serveur (en attente ou refusées) : leurs notes attendent,
    // quel que soit l'ordre des opérations (l'horloge du téléphone a pu reculer entre deux saisies)
    const nonCreees = new Set(toutes.filter((o) => o.type === 'CREATION').map((o) => o.evaluationId));
    const differees = new Set<string>();
    for (const op of attente) {
      if (this.prefixeOperations() !== prefixe) {
        break; // changement d'établissement en cours d'envoi
      }
      if (op.type === 'NOTES' && nonCreees.has(op.evaluationId)) {
        differees.add(op.evaluationId);
        continue;
      }
      try {
        if (op.type === 'CREATION') {
          const vue = await firstValueFrom(
            this.http.post<EvaluationServeur>(`${API}/classes/${op.classeId}/evaluations`, {
              matiereId: op.matiereId,
              periodeId: op.periodeId,
              libelle: op.evaluation!.libelle,
              type: op.evaluation!.type,
              date: op.evaluation!.date,
              bareme: op.evaluation!.bareme,
              poids: op.evaluation!.poids,
              idClient: op.evaluationId,
            }),
          );
          await this.stockage.ecrire<EvaluationLocale>(this.cleEvaluation(vue.id), {
            evaluation: vue,
            notes: {},
            copieLe: new Date().toISOString(),
          });
          await this.majListe(vue);
          await this.stockage.supprimer(op.cle);
          nonCreees.delete(op.evaluationId);
        } else {
          await this.envoyerNotes(op);
        }
        bilan.envoyees++;
        bilan.restantes--;
      } catch (e) {
        if (op.type === 'CREATION' && e instanceof HttpErrorResponse && e.status === 409 && !probleme(e)?.code) {
          // Conflit technique (deux envois simultanés du même identifiant) : l'évaluation existe sans
          // doute déjà, le prochain envoi la retrouvera. Ce n'est pas un refus.
          bilan.erreur = 'Envoi interrompu, nouvel essai automatique.';
          continue;
        }
        if (e instanceof HttpErrorResponse && [400, 403, 404, 409, 422].includes(e.status) && probleme(e)?.code !== 'MOT_DE_PASSE_A_CHANGER') {
          // Refus du serveur (période verrouillée, note hors barème…) : renvoyer ne changerait rien.
          // On relit l'opération : une saisie faite pendant l'envoi y a peut-être été ajoutée.
          const actuelle = (await this.stockage.lire<OperationNotes>(op.cle)) ?? op;
          await this.stockage.ecrire<OperationNotes>(op.cle, { ...actuelle, etat: 'REFUSE', message: messageErreur(e) });
          bilan.refusees++;
          bilan.restantes--;
          continue;
        }
        bilan.erreur =
          probleme(e)?.code === 'MOT_DE_PASSE_A_CHANGER'
            ? 'Changez votre mot de passe pour envoyer les notes.'
            : messageErreur(e, 'Envoi impossible pour le moment. Nouvel essai automatique.');
        break;
      }
    }
    if ([...differees].some((id) => !nonCreees.has(id))) {
      this.relancer = true; // notes passées avant leur évaluation, créée depuis : on les envoie tout de suite
    }
    await this.recharger();
    return this.terminer(bilan);
  }

  /**
   * Corrige une évaluation dont la création a été refusée (date hors période, barème…)
   * et la remet en file, avec ses notes.
   */
  async corrigerCreation(op: OperationNotes, d: { date: string; bareme: number; poids: number }): Promise<void> {
    if (op.type !== 'CREATION' || !op.evaluation) {
      return;
    }
    await this.stockage.ecrire<OperationNotes>(op.cle, {
      ...op,
      evaluation: { ...op.evaluation, ...d },
      etat: 'EN_ATTENTE',
      message: undefined,
    });
    await this.recharger();
    void this.synchroniser();
  }

  /**
   * Envoie une saisie de notes par paquets de 200 élèves (limite du serveur). Les notes
   * ajoutées pendant l'envoi (nouvel enregistrement sur l'écran) ne sont pas perdues :
   * seules les valeurs effectivement envoyées sont retirées de l'opération.
   */
  private async envoyerNotes(op: OperationNotes): Promise<void> {
    const envoyees = { ...(op.notes ?? {}) };
    const lignes = Object.entries(envoyees);
    let feuille: FeuilleServeur | undefined;
    for (let i = 0; i < lignes.length || i === 0; i += NOTES_PAR_ENVOI) {
      feuille = await firstValueFrom(
        this.http.put<FeuilleServeur>(`${API}/evaluations/${op.evaluationId}/notes`, {
          notes: lignes.slice(i, i + NOTES_PAR_ENVOI).map(([inscriptionId, n]) => ({
            inscriptionId,
            valeur: n.absent ? null : n.valeur,
            absent: n.absent,
          })),
        }),
      );
    }
    if (feuille) {
      await this.stockage.ecrire<EvaluationLocale>(this.cleEvaluation(op.evaluationId), {
        evaluation: feuille.evaluation,
        notes: versNotes(feuille.lignes),
        copieLe: new Date().toISOString(),
      });
      await this.majListe(feuille.evaluation);
    }
    const actuelle = await this.stockage.lire<OperationNotes>(op.cle);
    const restantes = Object.fromEntries(
      Object.entries(actuelle?.notes ?? {}).filter(([id, n]) => !memeNote(n, envoyees[id])),
    );
    if (Object.keys(restantes).length === 0) {
      await this.stockage.supprimer(op.cle);
    } else {
      // Saisie faite pendant l'envoi : elle part au passage suivant
      await this.stockage.ecrire<OperationNotes>(op.cle, { ...actuelle!, notes: restantes, etat: 'EN_ATTENTE' });
      this.relancer = true;
    }
  }

  /** Garde la liste locale à jour pour la consulter ensuite sans réseau. */
  private async majListe(vue: EvaluationServeur): Promise<void> {
    const cle = this.cleListe(vue.classeId, vue.matiereId, vue.periodeId);
    const liste = (await this.stockage.lire<EvaluationServeur[]>(cle)) ?? [];
    await this.stockage.ecrire(cle, [...liste.filter((e) => e.id !== vue.id), vue]);
  }

  private terminer(bilan: BilanNotes): BilanNotes {
    this.dernierBilan.set(bilan);
    return bilan;
  }
}

function memeNote(a: NoteEleve | undefined, b: NoteEleve | undefined): boolean {
  return !!a && !!b && a.absent === b.absent && a.valeur === b.valeur;
}

function versNotes(lignes: LigneFeuille[]): Record<string, NoteEleve> {
  const notes: Record<string, NoteEleve> = {};
  for (const l of lignes) {
    if (l.absent || l.valeur !== null) {
      notes[l.inscriptionId] = { valeur: l.absent ? null : Number(l.valeur), absent: l.absent };
    }
  }
  return notes;
}
