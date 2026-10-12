import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { Role } from '../core/modeles';
import { API } from '../core/session.service';

/** Messagerie de l'établissement (v0.46). */

export type Groupe = 'PERSONNEL' | 'ENSEIGNANTS' | 'ADMINISTRATION' | 'PARENTS';

export const LIBELLE_GROUPE: Record<Groupe, string> = {
  PERSONNEL: 'Tout le personnel',
  ENSEIGNANTS: 'Les enseignants',
  ADMINISTRATION: "L'administration",
  PARENTS: 'Tous les parents',
};

/** Rôles de l'administration qu'on peut viser seuls. */
export const ROLES_CIBLES: Role[] = ['CENSEUR', 'CHEF_TRAVAUX', 'SECRETARIAT', 'INTENDANT', 'SURVEILLANT'];

export const OBJET_MAX = 150;
export const CORPS_MAX = 2000;

export interface DemandeMessage {
  objet: string;
  corps: string;
  sms: boolean;
  groupes: Groupe[];
  roles: Role[];
  classes: string[];
  utilisateurs: string[];
  responsables: string[];
}

export interface Apercu {
  destinataires: number;
  comptes: number;
  smsSeulement: number;
  injoignables: number;
  sms: number;
}

export interface Envoi {
  id: string;
  destinataires: number;
  comptes: number;
  sms: number;
}

export interface MessageResume {
  id: string;
  objet: string;
  extrait: string;
  auteur: string;
  envoyeLe: string;
  lu: boolean;
}

export interface Boite {
  messages: MessageResume[];
  total: number;
  nonLus: number;
}

export interface MessageVue {
  id: string;
  objet: string;
  corps: string;
  auteur: string;
  envoyeLe: string;
  luLe: string | null;
  sms: boolean;
}

export interface MessageEnvoye {
  id: string;
  objet: string;
  cibles: string;
  auteur: string;
  envoyeLe: string;
  sms: boolean;
  destinataires: number;
  comptes: number;
  lus: number;
}

export interface DestinataireVue {
  nom: string;
  qualite: string | null;
  compte: boolean;
  sms: boolean;
  luLe: string | null;
}

export type Categorie = 'ADMINISTRATION' | 'ENSEIGNANT' | 'PARENT' | 'PARENT_SANS_COMPTE';

export interface Personne {
  utilisateurId: string | null;
  responsableId: string | null;
  nom: string;
  qualite: string;
  categorie: Categorie;
}

/** Clé unique d'une personne de l'annuaire. */
export function clePersonne(p: Personne): string {
  return p.utilisateurId ? `U:${p.utilisateurId}` : `R:${p.responsableId}`;
}

/** Nombre de SMS facturés pour un texte (160 caractères le premier, 153 les suivants, 3 au plus). */
export function segmentsSms(texte: string): number {
  const n = Math.min(texte.length, 480);
  return n <= 160 ? 1 : Math.ceil(n / 153);
}

@Injectable({ providedIn: 'root' })
export class MessagerieApi {
  private readonly http = inject(HttpClient);

  /** Messages non lus du compte connecté (pastille du menu). */
  readonly nonLus = signal(0);

  boite(page = 0, taille = 20): Promise<Boite> {
    return firstValueFrom(this.http.get<Boite>(`${API}/messages`, { params: new HttpParams().set('page', page).set('taille', taille) }));
  }

  async actualiserNonLus(): Promise<void> {
    try {
      const r = await firstValueFrom(this.http.get<{ nombre: number }>(`${API}/messages/non-lus`));
      this.nonLus.set(r.nombre);
    } catch {
      // hors connexion ou serveur ancien : la pastille garde sa valeur
    }
  }

  lire(id: string): Promise<MessageVue> {
    return firstValueFrom(this.http.get<MessageVue>(`${API}/messages/${id}`));
  }

  annuaire(): Promise<Personne[]> {
    return firstValueFrom(this.http.get<Personne[]>(`${API}/messages/annuaire`));
  }

  apercu(d: DemandeMessage): Promise<Apercu> {
    return firstValueFrom(this.http.post<Apercu>(`${API}/messages/apercu`, d));
  }

  envoyer(d: DemandeMessage): Promise<Envoi> {
    return firstValueFrom(this.http.post<Envoi>(`${API}/messages`, d));
  }

  envoyes(): Promise<MessageEnvoye[]> {
    return firstValueFrom(this.http.get<MessageEnvoye[]>(`${API}/messages/envoyes`));
  }

  destinataires(id: string): Promise<DestinataireVue[]> {
    return firstValueFrom(this.http.get<DestinataireVue[]>(`${API}/messages/${id}/destinataires`));
  }
}
