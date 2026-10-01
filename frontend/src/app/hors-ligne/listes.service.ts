import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { estErreurReseau } from '../core/erreurs';
import { Affectation, FicheEnseignant, Inscription, TypeEngagementEnseignant } from '../core/modeles';
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

/** Fin de l'engagement dans l'établissement (mutation, départ, fin de contrat), gardée pour l'affichage hors connexion. */
export interface FinEngagementLocale {
  type: TypeEngagementEnseignant;
  /** Dernier jour de travail dans l'établissement. */
  fin: string;
  motif: string | null;
  /** Fin décidée par l'établissement (sinon : fin de contrat prévue dès l'engagement). */
  programmee: boolean;
}

export interface ClasseLocale {
  classeId: string;
  /** Profil pédagogique de la classe : il détermine ses périodes (trimestres, semestres). */
  profilId?: string;
  eleves: EleveLocal[];
  prepareLe: string;
}

export interface PeriodeLocale {
  id: string;
  profilId: string;
  libelle: string;
  ordre: number;
  debut: string;
  fin: string;
  verrouillee: boolean;
}

export interface PeriodesLocales {
  anneeId: string;
  periodes: PeriodeLocale[];
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
        await this.memoriserFin(fiche);
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

  /** Périodes de l'année (tous profils), réseau d'abord. */
  async periodes(anneeId: string): Promise<PeriodesLocales | undefined> {
    const cle = this.prefixe() + 'periodes';
    if (this.enLigne()) {
      try {
        const periodes = await firstValueFrom(this.http.get<PeriodeLocale[]>(`${API}/annees/${anneeId}/periodes`));
        const locales: PeriodesLocales = { anneeId, periodes, prepareLe: new Date().toISOString() };
        await this.stockage.ecrire(cle, locales);
        return locales;
      } catch (e) {
        if (!estErreurReseau(e)) {
          throw e;
        }
      }
    }
    return this.stockage.lire<PeriodesLocales>(cle);
  }

  /** Fin d'engagement connue sur l'appareil (sans réseau). */
  finEngagement(): Promise<FinEngagementLocale | undefined> {
    return this.stockage.lire<FinEngagementLocale>(this.prefixe() + 'fin-engagement');
  }

  /**
   * Fin d'engagement, réseau d'abord : seule la fiche est relue (les listes de classe
   * gardent leur date de préparation). Sans réseau, la dernière information connue.
   */
  async actualiserFinEngagement(): Promise<FinEngagementLocale | undefined> {
    if (this.enLigne()) {
      try {
        await this.memoriserFin(await firstValueFrom(this.http.get<FicheEnseignant>(`${API}/espace-enseignant/affectations`)));
      } catch (e) {
        if (!estErreurReseau(e)) {
          // Plus d'engagement actif ici (déjà terminé) : plus rien à annoncer
          await this.stockage.supprimer(this.prefixe() + 'fin-engagement');
          return undefined;
        }
      }
    }
    return this.finEngagement();
  }

  private async memoriserFin(fiche: FicheEnseignant): Promise<void> {
    const cle = this.prefixe() + 'fin-engagement';
    const e = fiche.enseignant;
    if (e?.fin) {
      await this.stockage.ecrire<FinEngagementLocale>(cle, {
        type: e.type,
        fin: e.fin,
        motif: e.motifFin,
        programmee: e.finProgrammee === true,
      });
    } else {
      await this.stockage.supprimer(cle);
    }
  }

  /** Télécharge les listes de toutes les classes de l'enseignant (et les périodes de l'année). */
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
    // Périodes (pour les notes) après les listes : leur échec ne doit jamais priver l'appel de ses listes
    try {
      await this.periodes(affectations.anneeId);
    } catch {
      // Nouvel essai à la prochaine préparation
    }
    return affectations;
  }

  /**
   * Recharge tout si les listes de l'appareil datent de plus de 12 heures. Silencieux en cas d'échec.
   * Renvoie vrai si une préparation a eu lieu.
   */
  async preparerSiAncien(): Promise<boolean> {
    if (!this.enLigne() || !this.session.aLeRole('ENSEIGNANT')) {
      return false;
    }
    const actuel = await this.stockage.lire<AffectationsLocales>(this.prefixe() + 'affectations');
    if (actuel && Date.now() - Date.parse(actuel.prepareLe) < FRAICHEUR_MS) {
      return false;
    }
    try {
      await this.toutPreparer();
      return true;
    } catch {
      return false; // Nouvel essai au prochain lancement
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
    const [inscriptions, infos] = await Promise.all([
      firstValueFrom(this.http.get<Inscription[]>(`${API}/classes/${classeId}/inscriptions`)),
      firstValueFrom(this.http.get<{ profilId: string }>(`${API}/classes/${classeId}`)),
    ]);
    const classe: ClasseLocale = {
      classeId,
      profilId: infos.profilId,
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
