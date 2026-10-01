import { HttpClient } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { messageErreur } from '../core/erreurs';
import { dateLongue } from '../core/outils';
import { API, SessionService } from '../core/session.service';

interface Invitation {
  engagementId: string;
  etablissementId: string;
  etablissementNom: string;
  type: 'TITULAIRE' | 'VACATAIRE';
  debut: string;
  fin: string | null;
  tauxHoraire: number | null;
}

interface ReponseInvitation {
  etablissementId: string;
  etablissementNom: string;
  statut: string;
}

/**
 * Invitations d'autres établissements : un enseignant titulaire dans un établissement
 * peut être vacataire dans d'autres. Il accepte (ou refuse) ici, puis passe de l'un à l'autre.
 */
@Component({
  selector: 'app-invitations',
  template: `
    @if (erreur()) {
      <div class="alerte erreur" role="alert">{{ erreur() }}</div>
    }
    @for (i of invitations(); track i.engagementId) {
      <section class="carte invitation">
        <h2>Invitation : {{ i.etablissementNom }}</h2>
        <p>
          Cet établissement vous propose un poste de <strong>{{ i.type === 'VACATAIRE' ? 'vacataire' : 'titulaire' }}</strong>
          à partir du {{ dateLongue(i.debut) }}@if (i.fin) { jusqu'au {{ dateLongue(i.fin) }} }@if (i.tauxHoraire) {, à
          {{ i.tauxHoraire }} FCFA de l'heure }.
        </p>
        <p class="doux">Vos autres établissements ne sont pas informés de votre réponse.</p>
        <div class="actions-ligne">
          <button type="button" class="bouton" [disabled]="enCours()" (click)="repondre(i, true)">Accepter</button>
          <button type="button" class="bouton secondaire" [disabled]="enCours()" (click)="repondre(i, false)">Refuser</button>
        </div>
      </section>
    }
    @if (acceptee(); as a) {
      <div class="alerte succes" role="status">
        Vous enseignez maintenant aussi à {{ a.etablissementNom }}.
        <button type="button" class="bouton discret petit" (click)="allerA(a.etablissementId)">Y aller</button>
      </div>
    }
  `,
  styles: `
    .invitation {
      border-left: 4px solid var(--primaire);
    }
  `,
})
export class InvitationsComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);

  protected readonly dateLongue = dateLongue;
  protected readonly invitations = signal<Invitation[]>([]);
  protected readonly acceptee = signal<ReponseInvitation | null>(null);
  protected readonly enCours = signal(false);
  protected readonly erreur = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    const profil = this.session.profil();
    // Hors connexion, super administrateur ou mot de passe provisoire : rien à demander au serveur
    if (!this.session.jeton() || !profil || profil.superAdmin || profil.doitChangerMotDePasse) {
      return;
    }
    try {
      this.invitations.set(await firstValueFrom(this.http.get<Invitation[]>(`${API}/moi/invitations`)));
    } catch {
      // Les invitations ne sont qu'un complément de l'accueil
    }
  }

  protected async repondre(i: Invitation, accepter: boolean): Promise<void> {
    if (!accepter && !window.confirm(`Refuser l'invitation de ${i.etablissementNom} ?`)) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set(null);
    try {
      const r = await firstValueFrom(
        this.http.post<ReponseInvitation>(`${API}/moi/invitations/${i.engagementId}/${accepter ? 'acceptation' : 'refus'}`, null),
      );
      this.invitations.update((l) => l.filter((x) => x.engagementId !== i.engagementId));
      if (accepter) {
        this.acceptee.set(r);
        await this.session.actualiserEtablissements();
      }
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.enCours.set(false);
    }
  }

  protected async allerA(etablissementId: string): Promise<void> {
    try {
      await this.session.choisirEtablissement(etablissementId);
      this.acceptee.set(null);
      await this.router.navigate(['/']);
    } catch (e) {
      this.erreur.set(messageErreur(e));
    }
  }
}
