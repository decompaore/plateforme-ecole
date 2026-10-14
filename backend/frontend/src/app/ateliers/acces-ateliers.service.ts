import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API, SessionService } from '../core/session.service';

/**
 * L'onglet Ateliers a-t-il un sens pour l'enseignant connecté (v0.40) ? Oui seulement pour un
 * enseignant du technique et professionnel rattaché à un atelier (responsable, ou matière technique
 * d'une filière de l'atelier). La direction des ateliers et l'intendance n'ont pas besoin de le
 * demander. Inconnu (null) hors connexion : l'onglet reste caché, les ateliers demandant le réseau.
 */
@Injectable({ providedIn: 'root' })
export class AccesAteliersService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  readonly visible = signal<boolean | null>(null);
  private pour: string | null = null;

  /** Enseignant sans rôle de direction des ateliers : la question se pose. */
  enseignantSeulement(): boolean {
    return this.session.aLeRole('ENSEIGNANT') && !this.session.aLeRole('ADMIN_ECOLE', 'CHEF_TRAVAUX', 'INTENDANT');
  }

  async charger(): Promise<void> {
    const p = this.session.profil();
    const cle = p ? `${p.utilisateurId}:${p.etablissement?.id ?? ''}` : null;
    if (!cle || cle === this.pour || !this.enseignantSeulement() || !this.session.moduleActif('ATELIERS') || this.session.horsConnexion()) {
      return;
    }
    this.pour = cle;
    try {
      const r = await firstValueFrom(this.http.get<{ visible: boolean }>(`${API}/ateliers/acces`));
      this.visible.set(r.visible);
    } catch {
      this.pour = null;
      this.visible.set(null);
    }
  }
}
