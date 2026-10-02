import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { estErreurReseau } from '../core/erreurs';
import { API, SessionService } from '../core/session.service';
import { STOCKAGE } from '../hors-ligne/stockage';
import { AbsenceVue, HistoriqueVue } from '../vie-scolaire/modeles-vs';
import { BulletinEleveVue, EnfantVue, Lecture, Operateur, SituationVue, TransactionVue } from './modeles-parent';

interface EnCache<T> {
  donnees: T;
  le: string;
}

/**
 * API de l'espace parent. Chaque lecture est gardée sur le téléphone : sur un réseau
 * faible ou coupé, le parent revoit la dernière situation connue, datée. Le cache est
 * effacé à la déconnexion, comme les listes de l'enseignant.
 */
@Injectable({ providedIn: 'root' })
export class ParentApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly stockage = inject(STOCKAGE);

  private cle(nom: string): string {
    const p = this.session.profil();
    return `cache:${p?.utilisateurId ?? 'anonyme'}:${p?.etablissement?.id ?? 'aucun'}:parent:${nom}`;
  }

  /** Réseau d'abord ; sans réseau, la dernière copie gardée (sinon l'erreur). */
  private async lire<T>(chemin: string, nom: string): Promise<Lecture<T>> {
    const cle = this.cle(nom);
    try {
      const donnees = await firstValueFrom(this.http.get<T>(API + chemin));
      const le = new Date().toISOString();
      try {
        await this.stockage.ecrire<EnCache<T>>(cle, { donnees, le });
      } catch {
        // Le cache n'est qu'un secours
      }
      return { donnees, le, horsLigne: false };
    } catch (e) {
      if (estErreurReseau(e)) {
        const copie = await this.stockage.lire<EnCache<T>>(cle).catch(() => undefined);
        if (copie) {
          return { donnees: copie.donnees, le: copie.le, horsLigne: true };
        }
      }
      throw e;
    }
  }

  enfants(): Promise<Lecture<EnfantVue[]>> {
    return this.lire('/espace-parent/enfants', 'enfants');
  }

  absences(eleveId: string): Promise<Lecture<AbsenceVue[]>> {
    return this.lire(`/espace-parent/enfants/${eleveId}/absences`, `absences:${eleveId}`);
  }

  vieScolaire(eleveId: string): Promise<Lecture<HistoriqueVue[]>> {
    return this.lire(`/espace-parent/enfants/${eleveId}/vie-scolaire`, `vie-scolaire:${eleveId}`);
  }

  scolarite(eleveId: string): Promise<Lecture<SituationVue[]>> {
    return this.lire(`/espace-parent/enfants/${eleveId}/scolarite`, `scolarite:${eleveId}`);
  }

  bulletins(eleveId: string): Promise<Lecture<BulletinEleveVue[]>> {
    return this.lire(`/espace-parent/enfants/${eleveId}/bulletins`, `bulletins:${eleveId}`);
  }

  // ---------- En ligne uniquement

  bulletinPdf(id: string): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/espace-parent/bulletins/${id}/pdf`, { responseType: 'blob' }));
  }

  recuPdf(paiementId: string): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/espace-parent/paiements/${paiementId}/recu`, { responseType: 'blob' }));
  }

  payer(inscriptionId: string, d: { montant: number; operateur: Operateur; telephone: string; cleIdempotence: string }): Promise<TransactionVue> {
    return firstValueFrom(this.http.post<TransactionVue>(`${API}/espace-parent/inscriptions/${inscriptionId}/mobile-money`, d));
  }

  transaction(id: string): Promise<TransactionVue> {
    return firstValueFrom(this.http.get<TransactionVue>(`${API}/espace-parent/mobile-money/${id}`));
  }

  /** Développement uniquement (profil dev du serveur) : joue la confirmation du parent sur son téléphone. */
  simulerConfirmation(id: string, accepter: boolean): Promise<TransactionVue> {
    return firstValueFrom(
      this.http.post<TransactionVue>(`${API}/simulateur/mobile-money/${id}`, null, {
        params: new HttpParams().set('accepter', accepter),
      }),
    );
  }
}

/** Enregistre un fichier reçu du serveur sur le téléphone (bulletin, reçu). */
export function enregistrer(fichier: Blob, nom: string): void {
  const url = URL.createObjectURL(fichier);
  const lien = document.createElement('a');
  lien.href = url;
  lien.download = nom;
  document.body.appendChild(lien);
  lien.click();
  lien.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
