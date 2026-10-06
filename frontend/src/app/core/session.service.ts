import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom, Observable, timeout } from 'rxjs';

import { STOCKAGE } from '../hors-ligne/stockage';
import { estErreurReseau } from './erreurs';
import { EtablissementAccessible, Module, MotifChangementMotDePasse, ProfilConnecte, ReponseConnexion, Role } from './modeles';

export const API = '/api/v1';

/** Au-delà, un serveur qui ne répond pas est traité comme une absence de réseau. */
export const DELAI_RESEAU_MS = 8000;

/**
 * Ce que l'application retient de l'utilisateur connecté. Rien de secret :
 * il est enregistré sur l'appareil pour ouvrir l'application hors connexion.
 */
export interface ProfilLocal {
  utilisateurId: string;
  nom: string;
  prenoms: string;
  superAdmin: boolean;
  etablissement: EtablissementAccessible | null;
  doitChangerMotDePasse: boolean;
  /** Pourquoi un nouveau mot de passe est demandé (message de la page « Mot de passe »). */
  motifChangementMotDePasse?: MotifChangementMotDePasse | null;
  /** Établissements accessibles à ce compte : le choix d'établissement n'a de sens qu'à partir de deux. */
  nombreEtablissements?: number;
}

interface SelectionEnCours {
  jeton: string;
  etablissements: EtablissementAccessible[];
}

const CLE_PROFIL = 'session:profil';
/** Déconnexion demandée sans réseau : le cookie reste valide tant que le serveur ne l'a pas révoqué. */
const CLE_DECONNEXION = 'session:deconnexion-en-attente';
const VERROU = 'plateforme-ecoles-rafraichir';
/** L'appareil a été effacé à distance : message affiché sur la page de connexion (v0.32). */
const CLE_EFFACE = 'session:efface-a-distance';

/** Réponse du serveur : appareil déconnecté à distance avec effacement (téléphone perdu ou volé). */
export function appareilAEffacer(e: unknown): boolean {
  return e instanceof HttpErrorResponse && e.status === 401 && (e.error as { code?: string } | null)?.code === 'APPAREIL_A_EFFACER';
}

/**
 * Session de l'utilisateur.
 * <ul>
 *   <li>Le jeton d'accès reste en mémoire : il n'est jamais écrit sur l'appareil.</li>
 *   <li>Le jeton de rafraîchissement est un cookie HttpOnly posé par le serveur :
 *       le code de l'application ne le voit jamais.</li>
 *   <li>Hors connexion, le profil enregistré permet d'ouvrir l'appel ; les envois
 *       attendent le retour du réseau et une session valide.</li>
 * </ul>
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly stockage = inject(STOCKAGE);

  private readonly jetonSignal = signal<string | null>(null);
  private readonly profilSignal = signal<ProfilLocal | null>(null);
  private readonly selectionSignal = signal<SelectionEnCours | null>(null);
  private readonly expireeSignal = signal(false);
  private readonly effaceSignal = signal(false);
  private rafraichissement?: Promise<boolean>;

  /** Cet appareil vient d'être effacé à distance (message sur la page de connexion). */
  readonly effaceADistance = this.effaceSignal.asReadonly();

  readonly jeton = this.jetonSignal.asReadonly();
  readonly profil = this.profilSignal.asReadonly();
  readonly selection = this.selectionSignal.asReadonly();
  /**
   * Session expirée pendant l'utilisation : le profil est gardé pour ne pas
   * perdre un appel en cours de saisie ; il faut se reconnecter pour envoyer.
   */
  readonly sessionExpiree = this.expireeSignal.asReadonly();
  /** Profil connu mais pas de jeton : serveur injoignable, ou session à rouvrir. */
  readonly horsConnexion = computed(() => this.profilSignal() !== null && this.jetonSignal() === null);
  readonly roles = computed<Role[]>(() => this.profilSignal()?.etablissement?.roles ?? []);
  /** Vrai si le compte appartient à plusieurs établissements (enseignant vacataire, parent, réseau d'écoles). */
  readonly plusieursEtablissements = computed(() => (this.profilSignal()?.nombreEtablissements ?? 0) > 1);

  /** Le module est-il utilisé par l'établissement actif ? */
  moduleActif(module: Module): boolean {
    return !(this.profilSignal()?.etablissement?.modulesDesactives ?? []).includes(module);
  }

  aLeRole(...roles: Role[]): boolean {
    const miens = this.roles();
    return roles.some((r) => miens.includes(r));
  }

  /**
   * Au lancement : reprend la session (cookie) ou, sans réseau, le profil enregistré.
   * Ne bloque jamais le démarrage : délai limité, erreurs de stockage ignorées.
   */
  async demarrer(): Promise<void> {
    if (await this.sansEchec(() => this.stockage.lire<boolean>(CLE_EFFACE))) {
      this.effaceSignal.set(true);
    }
    if (await this.sansEchec(() => this.stockage.lire<boolean>(CLE_DECONNEXION))) {
      // Déconnexion faite hors réseau : on la termine avant tout, sans rouvrir la session
      try {
        await firstValueFrom(this.limite(this.http.post(`${API}/auth/deconnexion`, null)));
        await this.sansEchec(() => this.stockage.supprimer(CLE_DECONNEXION));
      } catch {
        // Nouvel essai au prochain lancement ; d'ici là, pas de session
      }
      return;
    }
    try {
      const reponse = await firstValueFrom(
        this.limite(this.http.post<ReponseConnexion>(`${API}/auth/rafraichir`, null)),
      );
      await this.ouvrir(reponse);
    } catch (e) {
      if (appareilAEffacer(e)) {
        await this.effacerAppareil();
      } else if (e instanceof HttpErrorResponse && e.status === 401) {
        // Session expirée ou révoquée : il faudra se reconnecter. Les appels non envoyés sont
        // conservés ; les listes de classes, elles, ne restent pas sur l'appareil.
        const ancien = await this.sansEchec(() => this.stockage.lire<ProfilLocal>(CLE_PROFIL));
        await this.sansEchec(() => this.stockage.supprimer(CLE_PROFIL));
        if (ancien?.utilisateurId) {
          await this.sansEchec(() => this.stockage.purger(`cache:${ancien.utilisateurId}:`));
        }
      } else {
        // Pas de réseau, serveur indisponible ou en erreur : on travaille avec le profil enregistré
        const enregistre = await this.sansEchec(() => this.stockage.lire<ProfilLocal>(CLE_PROFIL));
        this.profilSignal.set(enregistre ?? null);
      }
    }
  }

  /** Première étape. Renvoie 'selection' si l'utilisateur doit choisir un établissement. */
  async connexion(telephone: string, motDePasse: string): Promise<'selection' | 'ouverte'> {
    const reponse = await firstValueFrom(
      this.http.post<ReponseConnexion>(`${API}/auth/connexion`, { telephone, motDePasse }),
    );
    if (reponse.selectionRequise && reponse.jetonSelection) {
      this.selectionSignal.set({ jeton: reponse.jetonSelection, etablissements: reponse.etablissements });
      return 'selection';
    }
    await this.ouvrir(reponse);
    return 'ouverte';
  }

  /** Après la connexion (jeton de sélection) ou pour changer d'établissement (jeton d'accès). */
  async choisirEtablissement(etablissementId: string): Promise<void> {
    const envoyer = () => {
      const jeton = this.selectionSignal()?.jeton ?? this.jetonSignal();
      const headers = jeton ? new HttpHeaders({ Authorization: `Bearer ${jeton}` }) : undefined;
      return firstValueFrom(
        this.http.post<ReponseConnexion>(`${API}/auth/etablissement`, { etablissementId }, { headers }),
      );
    };
    let reponse: ReponseConnexion;
    try {
      reponse = await envoyer();
    } catch (e) {
      // Jeton d'accès expiré (changement d'établissement en cours de journée) : on le renouvelle une fois
      if (!(e instanceof HttpErrorResponse && e.status === 401) || this.selectionSignal() || !(await this.rafraichir())) {
        throw e;
      }
      reponse = await envoyer();
    }
    this.selectionSignal.set(null);
    await this.ouvrir(reponse);
  }

  async mesEtablissements(): Promise<EtablissementAccessible[]> {
    return firstValueFrom(this.http.get<EtablissementAccessible[]>(`${API}/moi/etablissements`));
  }

  /**
   * Renouvelle le jeton d'accès grâce au cookie. Un seul appel à la fois, y compris
   * entre plusieurs onglets (verrou du navigateur) : le serveur fait tourner le
   * cookie et considère la réutilisation d'un ancien comme un vol.
   */
  rafraichir(): Promise<boolean> {
    this.rafraichissement ??= this.sousVerrou(async () => {
      try {
        const reponse = await firstValueFrom(
          this.limite(this.http.post<ReponseConnexion>(`${API}/auth/rafraichir`, null)),
        );
        await this.ouvrir(reponse);
        return true;
      } catch (e) {
        if (appareilAEffacer(e)) {
          await this.effacerAppareil();
        } else if (e instanceof HttpErrorResponse && e.status === 401) {
          this.jetonSignal.set(null);
          this.expireeSignal.set(this.profilSignal() !== null);
        }
        return false;
      }
    }).finally(() => {
      this.rafraichissement = undefined;
    });
    return this.rafraichissement;
  }

  async changerMotDePasse(motDePasseActuel: string, nouveauMotDePasse: string): Promise<void> {
    await firstValueFrom(this.http.post(`${API}/moi/mot-de-passe`, { motDePasseActuel, nouveauMotDePasse }));
    // Nouveau jeton sans la mention « mot de passe à changer »
    if (!(await this.rafraichir())) {
      throw new Error('Mot de passe changé. Reconnectez-vous avec le nouveau mot de passe.');
    }
  }

  /** Déconnexion : révoque la session et efface les listes de classes de l'appareil. */
  async deconnexion(): Promise<void> {
    try {
      await firstValueFrom(this.limite(this.http.post(`${API}/auth/deconnexion`, null)));
    } catch (e) {
      // Sans réseau, le cookie resterait valide : la révocation sera faite au prochain lancement.
      // Une réponse du serveur (même une erreur) veut dire qu'il a traité la demande.
      if (estErreurReseau(e)) {
        await this.sansEchec(() => this.stockage.ecrire(CLE_DECONNEXION, true));
      }
    }
    const profil = this.profilSignal();
    this.jetonSignal.set(null);
    this.profilSignal.set(null);
    this.selectionSignal.set(null);
    this.expireeSignal.set(false);
    await this.sansEchec(() => this.stockage.supprimer(CLE_PROFIL));
    if (profil) {
      const uid = profil.utilisateurId;
      await this.sansEchec(() => this.stockage.purger(`cache:${uid}:`));
      // Plus rien en attente d'envoi : la clé de chiffrement de ce compte est détruite
      const enAttente = await this.sansEchec(async () =>
        (await Promise.all(['envoi', 'notes', 'cahier'].map((t) => this.stockage.cles(`${t}:${uid}:`)))).flat().length,
      );
      if (enAttente === 0) {
        await this.sansEchec(() => this.stockage.oublierCle?.(uid) ?? Promise.resolve());
      }
    }
  }

  /**
   * Téléphone perdu ou volé, déconnecté à distance avec effacement : toutes les données de
   * l'appareil sont effacées, y compris les saisies non envoyées, et les clés de chiffrement.
   */
  private async effacerAppareil(): Promise<void> {
    this.jetonSignal.set(null);
    this.profilSignal.set(null);
    this.selectionSignal.set(null);
    this.expireeSignal.set(false);
    await this.sansEchec(() => this.stockage.purger(''));
    await this.sansEchec(() => this.stockage.ecrire(CLE_EFFACE, true));
    this.effaceSignal.set(true);
    // Le cookie de la session fermée ne sert plus : on le fait retirer
    await this.sansEchec(() => firstValueFrom(this.limite(this.http.post(`${API}/auth/deconnexion`, null))));
  }

  private async ouvrir(reponse: ReponseConnexion): Promise<void> {
    if (!reponse.jetonAcces) {
      throw new Error('Réponse de connexion sans jeton d’accès');
    }
    const precedent = this.profilSignal();
    const utilisateurId = sujet(reponse.jetonAcces);
    const memeUtilisateur = precedent?.utilisateurId === utilisateurId;
    this.jetonSignal.set(reponse.jetonAcces);
    this.expireeSignal.set(false);
    const profil: ProfilLocal = {
      utilisateurId,
      nom: memeUtilisateur ? precedent.nom : '',
      prenoms: memeUtilisateur ? precedent.prenoms : '',
      superAdmin: reponse.superAdmin,
      etablissement: reponse.etablissementActif,
      doitChangerMotDePasse: reponse.doitChangerMotDePasse,
      motifChangementMotDePasse: reponse.motifChangementMotDePasse ?? null,
      // La connexion fournit la liste ; le renouvellement non (liste vide) : on garde ce qu'on sait
      nombreEtablissements: reponse.etablissements?.length
        ? reponse.etablissements.length
        : memeUtilisateur
          ? precedent.nombreEtablissements
          : undefined,
    };
    this.profilSignal.set(profil);
    await this.sansEchec(() => this.stockage.supprimer(CLE_DECONNEXION));
    if (this.effaceSignal()) {
      this.effaceSignal.set(false);
      await this.sansEchec(() => this.stockage.supprimer(CLE_EFFACE));
    }
    await this.sansEchec(() => this.stockage.chiffrerAnciennes?.(utilisateurId) ?? Promise.resolve());
    if (!memeUtilisateur) {
      await this.sansEchec(() => this.effacerListesDesAutres(utilisateurId));
    }
    if (!profil.nom) {
      try {
        const moi = await firstValueFrom(this.limite(this.http.get<ProfilConnecte>(`${API}/moi`)));
        profil.nom = moi.nom;
        profil.prenoms = moi.prenoms;
        this.profilSignal.set({ ...profil });
      } catch {
        // Le nom n'est qu'un affichage : on continue sans
      }
    }
    if (profil.nombreEtablissements === undefined && !profil.superAdmin) {
      try {
        const etablissements = await firstValueFrom(
          this.limite(this.http.get<EtablissementAccessible[]>(`${API}/moi/etablissements`)),
        );
        this.profilSignal.set({ ...this.profilSignal()!, nombreEtablissements: etablissements.length });
      } catch {
        // Inconnu : le lien « Changer d'établissement » reste masqué
      }
    }
    await this.sansEchec(() => this.stockage.ecrire(CLE_PROFIL, this.profilSignal()));
  }

  /** Relit le nombre d'établissements du compte (après l'acceptation d'une invitation, par exemple). */
  async actualiserEtablissements(): Promise<void> {
    const profil = this.profilSignal();
    if (!profil) {
      return;
    }
    try {
      const etablissements = await this.mesEtablissements();
      this.profilSignal.set({ ...this.profilSignal()!, nombreEtablissements: etablissements.length });
      await this.sansEchec(() => this.stockage.ecrire(CLE_PROFIL, this.profilSignal()));
    } catch {
      // Sans réseau : le nombre sera relu à la prochaine ouverture de session
    }
  }

  /** Téléphone partagé : les listes de classes d'un autre utilisateur ne restent pas sur l'appareil. */
  private async effacerListesDesAutres(utilisateurId: string): Promise<void> {
    const miennes = `cache:${utilisateurId}:`;
    for (const cle of await this.stockage.cles('cache:')) {
      if (!cle.startsWith(miennes)) {
        await this.stockage.supprimer(cle);
      }
    }
  }

  private limite<T>(requete: Observable<T>): Observable<T> {
    return requete.pipe(timeout(DELAI_RESEAU_MS));
  }

  private async sansEchec<T>(action: () => Promise<T>): Promise<T | undefined> {
    try {
      return await action();
    } catch {
      return undefined;
    }
  }

  private sousVerrou<T>(action: () => Promise<T>): Promise<T> {
    const verrous = typeof navigator !== 'undefined' ? navigator.locks : undefined;
    return verrous ? verrous.request(VERROU, action) : action();
  }
}

/** Identifiant de l'utilisateur (« sub ») lu dans le jeton. Lecture seule : la signature est vérifiée par le serveur. */
export function sujet(jeton: string): string {
  const partie = jeton.split('.')[1] ?? '';
  const base64 = partie.replace(/-/g, '+').replace(/_/g, '/');
  const json = decodeURIComponent(
    Array.from(atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=')))
      .map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0'))
      .join(''),
  );
  const sub = (JSON.parse(json) as { sub?: string }).sub;
  if (!sub) {
    throw new Error('Jeton sans identifiant d’utilisateur');
  }
  return sub;
}
