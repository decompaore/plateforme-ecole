import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, DestroyRef, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { messageErreur, probleme } from '../core/erreurs';
import { AccuseAppel, DonneesAppel } from '../core/modeles';
import { API, SessionService } from '../core/session.service';
import { nouvelIdentifiant } from '../core/outils';
import { STOCKAGE } from './stockage';

export type EtatEnvoi = 'EN_ATTENTE' | 'ENVOYE' | 'REFUSE';

/** Appel saisi sur l'appareil, en attente d'envoi ou déjà traité par le serveur. */
export interface Envoi {
  cle: string;
  utilisateurId: string;
  etablissementId: string;
  appel: DonneesAppel;
  /** Pour l'affichage hors connexion. */
  classeCode: string;
  matiereLibelle: string;
  absents: number;
  retards: number;
  etat: EtatEnvoi;
  accuse?: AccuseAppel;
  message?: string;
  tentatives: number;
  traiteLe?: string;
}

export interface Bilan {
  envoyes: number;
  refuses: number;
  restants: number;
  erreur?: string;
}

/** Taille maximale d'un lot accepté par le serveur. */
export const LOT_MAX = 50;
/** Les appels envoyés restent visibles une semaine, pour rassurer l'enseignant. */
const CONSERVATION_MS = 7 * 24 * 3600 * 1000;
const INTERVALLE_MS = 60 * 1000;

/**
 * File d'envoi des appels. Chaque appel reçoit un identifiant généré sur
 * l'appareil (idClient) : le serveur reconnaît un renvoi et ne crée jamais de
 * doublon, on peut donc renvoyer sans crainte après une coupure.
 */
@Injectable({ providedIn: 'root' })
export class EnvoisService {
  private readonly http = inject(HttpClient);
  private readonly stockage = inject(STOCKAGE);
  private readonly session = inject(SessionService);

  private readonly envoisSignal = signal<Envoi[]>([]);
  private enCours?: Promise<Bilan>;

  readonly envois = this.envoisSignal.asReadonly();
  readonly enAttente = computed(() => this.envoisSignal().filter((e) => e.etat === 'EN_ATTENTE').length);
  readonly refuses = computed(() => this.envoisSignal().filter((e) => e.etat === 'REFUSE').length);
  readonly synchronisation = signal(false);
  readonly dernierBilan = signal<Bilan | null>(null);

  /** Envoie automatiquement au retour du réseau et toutes les minutes. */
  demarrer(destroyRef?: DestroyRef): void {
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
    destroyRef?.onDestroy(() => {
      window.removeEventListener('online', tenter);
      clearInterval(minuterie);
    });
    void this.recharger().then(tenter);
  }

  private prefixe(): string | null {
    const p = this.session.profil();
    return p?.etablissement ? `envoi:${p.utilisateurId}:${p.etablissement.id}:` : null;
  }

  /** Relit la file de l'utilisateur et de l'établissement actifs. */
  async recharger(): Promise<void> {
    const prefixe = this.prefixe();
    this.envoisSignal.set(prefixe ? (await this.stockage.lister<Envoi>(prefixe)).reverse() : []);
  }

  /** Enregistre l'appel sur l'appareil, puis tente de l'envoyer. */
  async ajouter(
    appel: Omit<DonneesAppel, 'idClient' | 'saisiLe'>,
    libelles: { classeCode: string; matiereLibelle: string },
  ): Promise<Envoi> {
    const profil = this.session.profil();
    const prefixe = this.prefixe();
    if (!profil?.etablissement || !prefixe) {
      throw new Error('Aucun établissement actif');
    }
    const saisiLe = new Date().toISOString();
    const idClient = nouvelIdentifiant();
    const envoi: Envoi = {
      // La clé commence par l'instant de saisie : la file est envoyée dans l'ordre
      cle: `${prefixe}${saisiLe}:${idClient}`,
      utilisateurId: profil.utilisateurId,
      etablissementId: profil.etablissement.id,
      appel: { ...appel, idClient, saisiLe },
      classeCode: libelles.classeCode,
      matiereLibelle: libelles.matiereLibelle,
      absents: appel.marques.filter((m) => m.type === 'ABSENCE').length,
      retards: appel.marques.filter((m) => m.type === 'RETARD').length,
      etat: 'EN_ATTENTE',
      tentatives: 0,
    };
    await this.stockage.ecrire(envoi.cle, envoi);
    await this.recharger();
    void this.synchroniser();
    return envoi;
  }

  /** Retire de l'appareil un appel refusé (ou déjà envoyé). Un appel en attente ne peut pas être retiré. */
  async retirer(envoi: Envoi): Promise<void> {
    if (envoi.etat === 'EN_ATTENTE') {
      return;
    }
    await this.stockage.supprimer(envoi.cle);
    await this.recharger();
  }

  /** Envoie la file par lots. Un seul envoi à la fois ; les appels suivants attendent le même. */
  synchroniser(): Promise<Bilan> {
    this.enCours ??= this.executer().finally(() => {
      this.enCours = undefined;
      this.synchronisation.set(false);
    });
    return this.enCours;
  }

  private async executer(): Promise<Bilan> {
    const bilan: Bilan = { envoyes: 0, refuses: 0, restants: 0 };
    const prefixe = this.prefixe();
    if (!prefixe) {
      return bilan;
    }
    const tous = await this.stockage.lister<Envoi>(prefixe);
    await this.purgerAnciens(tous);
    const attente = tous.filter((e) => e.etat === 'EN_ATTENTE');
    bilan.restants = attente.length;
    if (attente.length === 0 || (typeof navigator !== 'undefined' && navigator.onLine === false)) {
      await this.recharger();
      return bilan;
    }
    if (this.session.sessionExpiree()) {
      bilan.erreur = 'Session expirée : reconnectez-vous pour envoyer les appels.';
      await this.recharger();
      return this.terminer(bilan);
    }
    this.synchronisation.set(true);
    if (!this.session.jeton() && !(await this.session.rafraichir())) {
      bilan.erreur = 'Envoi impossible pour le moment. Nouvel essai automatique.';
      await this.recharger();
      return this.terminer(bilan);
    }
    const lots: Envoi[][] = [];
    for (let i = 0; i < attente.length; i += LOT_MAX) {
      lots.push(attente.slice(i, i + LOT_MAX));
    }
    while (lots.length > 0) {
      // Le jeton est celui de l'établissement actif : si l'utilisateur en a changé, on s'arrête
      if (this.prefixe() !== prefixe) {
        break;
      }
      const lot = lots.shift()!;
      try {
        const accuses = await firstValueFrom(
          this.http.post<AccuseAppel[]>(`${API}/appels/lot`, { appels: lot.map((e) => e.appel) }),
        );
        const parId = new Map(accuses.map((a) => [a.idClient, a]));
        for (const envoi of lot) {
          const accuse = parId.get(envoi.appel.idClient);
          if (!accuse) {
            continue;
          }
          if (accuse.statut === 'REFUSE') {
            await this.marquer(envoi, 'REFUSE', accuse.message ?? accuse.code ?? 'Appel refusé', accuse);
            bilan.refuses++;
          } else {
            await this.marquer(envoi, 'ENVOYE', undefined, accuse);
            bilan.envoyes++;
          }
          bilan.restants--;
        }
      } catch (e) {
        if (e instanceof HttpErrorResponse && e.status === 400) {
          if (lot.length > 1) {
            // Un appel illisible fait rejeter tout le lot : on renvoie un par un pour ne refuser que lui
            lots.unshift(...lot.map((envoi) => [envoi]));
            continue;
          }
          await this.marquer(lot[0], 'REFUSE', messageErreur(e, 'Appel refusé par le serveur'));
          bilan.refuses++;
          bilan.restants--;
          continue;
        }
        // Réseau coupé, serveur indisponible, session ou mot de passe à régler : on réessaiera
        bilan.erreur =
          probleme(e)?.code === 'MOT_DE_PASSE_A_CHANGER'
            ? 'Changez votre mot de passe pour envoyer les appels.'
            : this.session.sessionExpiree()
              ? 'Session expirée : reconnectez-vous pour envoyer les appels.'
              : messageErreur(e, 'Envoi impossible pour le moment. Nouvel essai automatique.');
        break;
      }
    }
    await this.recharger();
    return this.terminer(bilan);
  }

  private async marquer(envoi: Envoi, etat: EtatEnvoi, message?: string, accuse?: AccuseAppel): Promise<void> {
    envoi.tentatives++;
    envoi.etat = etat;
    envoi.message = message;
    envoi.accuse = accuse;
    envoi.traiteLe = new Date().toISOString();
    await this.stockage.ecrire(envoi.cle, envoi);
  }

  /** Appels en attente de l'utilisateur, tous établissements confondus (avertissement à la déconnexion). */
  async enAttenteTousEtablissements(): Promise<number> {
    const p = this.session.profil();
    if (!p) {
      return 0;
    }
    const tous = await this.stockage.lister<Envoi>(`envoi:${p.utilisateurId}:`);
    return tous.filter((e) => e.etat === 'EN_ATTENTE').length;
  }

  private terminer(bilan: Bilan): Bilan {
    this.dernierBilan.set(bilan);
    return bilan;
  }

  private async purgerAnciens(envois: Envoi[]): Promise<void> {
    const limite = Date.now() - CONSERVATION_MS;
    for (const e of envois) {
      if (e.etat === 'ENVOYE' && e.traiteLe && Date.parse(e.traiteLe) < limite) {
        await this.stockage.supprimer(e.cle);
      }
    }
  }
}
