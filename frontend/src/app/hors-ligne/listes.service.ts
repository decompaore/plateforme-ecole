import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { estErreurReseau } from '../core/erreurs';
import { Affectation, FicheEnseignant, Inscription } from '../core/modeles';
import { API, SessionService } from '../core/session.service';
import { STOCKAGE } from './stockage';

/** Élève tel qu'il est gardé sur l'appareil (le strict nécessaire pour l'appel). */
export interface EleveLocal {
  inscriptionId: string;
  nom: string;
  prenoms: string;
  matricule: string | null;
  sexe: 'M' | 'F' | null;
}

export interface AffectationsLocales {
  anneeId: string;
  affectations: Affectation[];
  prepareLe: string;
}

export interface ClasseLocale {
  classeId: string;
  eleves: EleveLocal[];
  prepareLe: string;
}

/** Au-delà, les listes sont rechargées automatiquement dès que le réseau le permet. */
const FRAICHEUR_MS = 12 * 3600 * 1000;

/**
 * Affectations de l'enseignant et listes de ses classes, gardées sur l'appareil
 * pour faire l'appel sans réseau. Lecture « réseau d'abord, appareil ensuite ».
 * Les listes sont effacées à la déconnexion.
 */
@Injectable({ providedIn: 'root' })
export class ListesService {
  private readonly http = inject(HttpClient);
  private readonly stockage = inject(STOCKAGE);
  private readonly session = inject(SessionService);

  readonly preparation = signal<{ faites: number; total: number } | null>(null);

  private prefixe(): string {
    const p = this.session.profil();
    if (!p?.etablissement) {
      throw new Error('Aucun établissement actif');
    }
    return `cache:${p.utilisateurId}:${p.etablissement.id}:`;
  }

  private enLigne(): boolean {
    return this.session.jeton() !== null && (typeof navigator === 'undefined' || navigator.onLine !== false);
  }

  async affectations(): Promise<AffectationsLocales | undefined> {
    const cle = this.prefixe() + 'affectations';
    if (this.enLigne()) {
      try {
        const fiche = await firstValueFrom(this.http.get<FicheEnseignant>(`${API}/espace-enseignant/affectations`));
        const locales: AffectationsLocales = {
          anneeId: fiche.anneeId,
          affectations: [...fiche.affectations].sort(
            (a, b) => a.classeCode.localeCompare(b.classeCode) || a.matiereLibelle.localeCompare(b.matiereLibelle),
          ),
          prepareLe: new Date().toISOString(),
        };
        await this.stockage.ecrire(cle, locales);
        return locales;
      } catch (e) {
        if (!estErreurReseau(e)) {
          throw e;
        }
      }
    }
    return this.stockage.lire<AffectationsLocales>(cle);
  }

  async eleves(classeId: string): Promise<ClasseLocale | undefined> {
    const cle = this.prefixe() + 'classe:' + classeId;
    if (this.enLigne()) {
      try {
        return await this.telechargerClasse(classeId, cle);
      } catch (e) {
        if (!estErreurReseau(e)) {
          throw e;
        }
      }
    }
    return this.stockage.lire<ClasseLocale>(cle);
  }

  /** Télécharge les listes de toutes les classes de l'enseignant. */
  async toutPreparer(): Promise<AffectationsLocales | undefined> {
    const affectations = await this.affectations();
    if (!affectations) {
      return undefined;
    }
    const classes = [...new Set(affectations.affectations.map((a) => a.classeId))];
    this.preparation.set({ faites: 0, total: classes.length });
    try {
      for (const [i, classeId] of classes.entries()) {
        await this.telechargerClasse(classeId, this.prefixe() + 'classe:' + classeId);
        this.preparation.set({ faites: i + 1, total: classes.length });
      }
    } finally {
      this.preparation.set(null);
    }
    return affectations;
  }

  /** Recharge tout si les listes de l'appareil datent de plus de 12 heures. Silencieux en cas d'échec. */
  async preparerSiAncien(): Promise<void> {
    if (!this.enLigne() || !this.session.aLeRole('ENSEIGNANT')) {
      return;
    }
    const actuel = await this.stockage.lire<AffectationsLocales>(this.prefixe() + 'affectations');
    if (actuel && Date.now() - Date.parse(actuel.prepareLe) < FRAICHEUR_MS) {
      return;
    }
    try {
      await this.toutPreparer();
    } catch {
      // Nouvel essai au prochain lancement
    }
  }

  /** Date de préparation la plus ancienne des listes présentes sur l'appareil. */
  async prepareLe(): Promise<string | undefined> {
    const affectations = await this.stockage.lire<AffectationsLocales>(this.prefixe() + 'affectations');
    if (!affectations) {
      return undefined;
    }
    const classes = await this.stockage.lister<ClasseLocale>(this.prefixe() + 'classe:');
    return [affectations.prepareLe, ...classes.map((c) => c.prepareLe)].sort()[0];
  }

  private async telechargerClasse(classeId: string, cle: string): Promise<ClasseLocale> {
    const inscriptions = await firstValueFrom(this.http.get<Inscription[]>(`${API}/classes/${classeId}/inscriptions`));
    const classe: ClasseLocale = {
      classeId,
      eleves: inscriptions.map((i) => ({
        inscriptionId: i.id,
        nom: i.nom,
        prenoms: i.prenoms,
        matricule: i.matricule,
        sexe: i.sexe,
      })),
      prepareLe: new Date().toISOString(),
    };
    await this.stockage.ecrire(cle, classe);
    return classe;
  }
}
