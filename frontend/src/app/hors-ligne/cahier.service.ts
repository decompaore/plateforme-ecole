import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { estErreurReseau, messageErreur, probleme } from '../core/erreurs';
import { API, SessionService } from '../core/session.service';
import { DonneesSeance, FicheVue, SeanceVue } from '../progression/modeles-progression';
import { STOCKAGE } from './stockage';

/** Séance du cahier de textes saisie sur le téléphone, en attente d'envoi (ou refusée). */
export interface SeanceLocale {
  cle: string;
  id: string;
  donnees: DonneesSeance;
  classeCode: string;
  matiereLibelle: string;
  saisieLe: string;
  etat: 'EN_ATTENTE' | 'REFUSE';
  message?: string;
}

export interface BilanCahier {
  envoyees: number;
  refusees: number;
  restantes: number;
  erreur?: string;
}

const INTERVALLE_MS = 60 * 1000;

/**
 * Cahier de textes sans réseau, comme l'appel et les notes : chaque séance est d'abord
 * enregistrée sur le téléphone, puis envoyée dès que le réseau revient. L'identifiant est
 * choisi par le téléphone : un renvoi après une coupure ne crée pas de doublon.
 * La fiche de progression et les séances déjà envoyées sont gardées pour être lues sans réseau.
 */
@Injectable({ providedIn: 'root' })
export class CahierService {
  private readonly http = inject(HttpClient);
  private readonly stockage = inject(STOCKAGE);
  private readonly session = inject(SessionService);

  private readonly operationsSignal = signal<SeanceLocale[]>([]);
  private enCours?: Promise<BilanCahier>;
  private relancer = false;

  readonly operations = this.operationsSignal.asReadonly();
  readonly enAttente = computed(() => this.operationsSignal().filter((o) => o.etat === 'EN_ATTENTE').length);
  readonly refusees = computed(() => this.operationsSignal().filter((o) => o.etat === 'REFUSE').length);
  readonly synchronisation = signal(false);
  readonly dernierBilan = signal<BilanCahier | null>(null);
  /** Séances envoyées depuis l'ouverture de l'application : les écrans ouverts les affichent aussitôt. */
  readonly envoyees = signal<SeanceVue[]>([]);

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

  private prefixe(): string | null {
    const p = this.session.profil();
    return p?.etablissement ? `cahier:${p.utilisateurId}:${p.etablissement.id}:` : null;
  }

  private cleCache(quoi: 'fiche' | 'seances', classeId: string, matiereId: string): string | null {
    const p = this.session.profil();
    return p?.etablissement ? `cache:${p.utilisateurId}:${p.etablissement.id}:cahier-${quoi}:${classeId}:${matiereId}` : null;
  }

  async recharger(): Promise<void> {
    const prefixe = this.prefixe();
    this.operationsSignal.set(prefixe ? await this.stockage.lister<SeanceLocale>(prefixe) : []);
  }

  // ---------------------------------------------------------------- lecture (avec copie sur l'appareil)

  /** Fiche de progression de la matière : du serveur si possible, sinon la dernière copie. */
  async fiche(classeId: string, matiereId: string): Promise<{ fiche: FicheVue | undefined; horsLigne: boolean }> {
    return this.lire(this.cleCache('fiche', classeId, matiereId), () =>
      firstValueFrom(this.http.get<FicheVue>(`${API}/classes/${classeId}/matieres/${matiereId}/progression`)),
    ).then((r) => ({ fiche: r.valeur, horsLigne: r.horsLigne }));
  }

  /** Séances déjà envoyées : du serveur si possible, sinon la dernière copie. */
  async seances(classeId: string, matiereId: string): Promise<{ seances: SeanceVue[]; horsLigne: boolean }> {
    return this.lire(this.cleCache('seances', classeId, matiereId), () =>
      firstValueFrom(this.http.get<SeanceVue[]>(`${API}/classes/${classeId}/matieres/${matiereId}/cahier-textes`)),
    ).then((r) => ({ seances: r.valeur ?? [], horsLigne: r.horsLigne }));
  }

  private async lire<T>(cle: string | null, charger: () => Promise<T>): Promise<{ valeur: T | undefined; horsLigne: boolean }> {
    try {
      const valeur = await charger();
      if (cle) {
        await this.stockage.ecrire(cle, valeur);
      }
      return { valeur, horsLigne: false };
    } catch (e) {
      if (!estErreurReseau(e) || !cle) {
        throw e;
      }
      return { valeur: await this.stockage.lire<T>(cle), horsLigne: true };
    }
  }

  // ---------------------------------------------------------------- saisie

  /** Enregistre la séance sur le téléphone (une modification remplace la version en attente), puis tente l'envoi. */
  async enregistrer(id: string, donnees: DonneesSeance, libelles: { classeCode: string; matiereLibelle: string }): Promise<void> {
    const prefixe = this.prefixe();
    if (!prefixe) {
      throw new Error('Aucun établissement actif');
    }
    await this.stockage.ecrire<SeanceLocale>(`${prefixe}${id}`, {
      cle: `${prefixe}${id}`,
      id,
      donnees,
      ...libelles,
      saisieLe: new Date().toISOString(),
      etat: 'EN_ATTENTE',
    });
    await this.recharger();
    void this.synchroniser();
  }

  async abandonner(op: SeanceLocale): Promise<void> {
    await this.stockage.supprimer(op.cle);
    await this.recharger();
  }

  /** Supprime une séance déjà envoyée (en ligne seulement). */
  async supprimer(seance: SeanceVue): Promise<void> {
    await firstValueFrom(this.http.delete<void>(`${API}/cahier-textes/${seance.id}`));
    const cle = this.cleCache('seances', seance.classeId, seance.matiereId);
    if (cle) {
      const liste = (await this.stockage.lire<SeanceVue[]>(cle)) ?? [];
      await this.stockage.ecrire(cle, liste.filter((s) => s.id !== seance.id));
    }
  }

  // ---------------------------------------------------------------- envoi

  synchroniser(): Promise<BilanCahier> {
    if (this.enCours) {
      this.relancer = true;
      return this.enCours;
    }
    this.enCours = this.envoyer().finally(() => {
      this.enCours = undefined;
      this.synchronisation.set(false);
      if (this.relancer) {
        this.relancer = false;
        void this.synchroniser();
      }
    });
    return this.enCours;
  }

  private async envoyer(): Promise<BilanCahier> {
    const bilan: BilanCahier = { envoyees: 0, refusees: 0, restantes: 0 };
    const prefixe = this.prefixe();
    if (!prefixe) {
      return bilan;
    }
    const attente = (await this.stockage.lister<SeanceLocale>(prefixe)).filter((o) => o.etat === 'EN_ATTENTE');
    bilan.restantes = attente.length;
    if (attente.length === 0 || (typeof navigator !== 'undefined' && navigator.onLine === false)) {
      await this.recharger();
      return bilan;
    }
    if (this.session.sessionExpiree()) {
      bilan.erreur = 'Session expirée : reconnectez-vous pour envoyer le cahier de textes.';
      await this.recharger();
      return this.terminer(bilan);
    }
    this.synchronisation.set(true);
    if (!this.session.jeton() && !(await this.session.rafraichir())) {
      bilan.erreur = 'Envoi impossible pour le moment. Nouvel essai automatique.';
      await this.recharger();
      return this.terminer(bilan);
    }
    for (const op of attente) {
      if (this.prefixe() !== prefixe) {
        break; // changement d'établissement pendant l'envoi
      }
      try {
        const vue = await firstValueFrom(this.http.put<SeanceVue>(`${API}/cahier-textes/${op.id}`, op.donnees));
        // Une nouvelle modification a pu être faite pendant l'envoi : on ne retire que la version envoyée
        const actuelle = await this.stockage.lire<SeanceLocale>(op.cle);
        if (!actuelle || actuelle.saisieLe === op.saisieLe) {
          await this.stockage.supprimer(op.cle);
        } else {
          this.relancer = true;
        }
        await this.majCache(vue);
        this.envoyees.set([...this.envoyees().filter((s) => s.id !== vue.id), vue]);
        bilan.envoyees++;
        bilan.restantes--;
      } catch (e) {
        if (e instanceof HttpErrorResponse && [400, 403, 404, 409, 422].includes(e.status) && probleme(e)?.code !== 'MOT_DE_PASSE_A_CHANGER') {
          // Refus du serveur (date future, doublon, matière qui n'est plus la sienne) : renvoyer ne changerait rien
          await this.stockage.ecrire<SeanceLocale>(op.cle, { ...op, etat: 'REFUSE', message: messageErreur(e) });
          bilan.refusees++;
          bilan.restantes--;
          continue;
        }
        bilan.erreur = messageErreur(e, 'Envoi impossible pour le moment. Nouvel essai automatique.');
        break;
      }
    }
    await this.recharger();
    return this.terminer(bilan);
  }

  private async majCache(vue: SeanceVue): Promise<void> {
    const cle = this.cleCache('seances', vue.classeId, vue.matiereId);
    if (!cle) {
      return;
    }
    const liste = ((await this.stockage.lire<SeanceVue[]>(cle)) ?? []).filter((s) => s.id !== vue.id);
    liste.push(vue);
    liste.sort((a, b) => (b.date + b.heureDebut).localeCompare(a.date + a.heureDebut));
    await this.stockage.ecrire(cle, liste);
  }

  private terminer(bilan: BilanCahier): BilanCahier {
    this.dernierBilan.set(bilan);
    return bilan;
  }
}
