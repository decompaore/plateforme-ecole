import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte } from '../../core/outils';
import { filtrer } from '../../core/recherche';
import { SessionService } from '../../core/session.service';
import { RechercheComponent } from '../../partage/recherche.component';
import { AgentResume, CATEGORIES, CategorieAgent, SITUATIONS, vide } from '../modeles-personnel';
import { PersonnelApi } from '../personnel-api.service';

/**
 * Personnel de l'établissement (v0.51) : un dossier par agent (enseignants, administratifs, agents de soutien),
 * reprise des comptes existants, création d'un agent sans compte, ce qui manque à chaque dossier.
 */
@Component({
  selector: 'app-personnel',
  imports: [FormsModule, RouterLink, RechercheComponent],
  template: `
    <div class="page large">
      <div class="entete-section">
        <h1>Dossiers du personnel</h1>
        <span class="actions-ligne">
          <a class="bouton secondaire petit" routerLink="/personnel/absences">Congés et absences</a>
          @if (gestion()) {
            <button type="button" class="bouton secondaire petit" (click)="creation.set(!creation())">Ajouter un agent</button>
          }
        </span>
      </div>

      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      @if (sansDossier() > 0 && gestion()) {
        <div class="alerte attention">
          {{ sansDossier() }} compte(s) du personnel sans dossier.
          <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="reprendre()">Ouvrir leurs dossiers</button>
          <span class="doux">Identité, matricule et date d'engagement sont repris ; le reste se complète ensuite.</span>
        </div>
      }

      @if (creation()) {
        <form class="carte" (ngSubmit)="creer()">
          <h2>Nouvel agent</h2>
          <p class="doux">Pour un agent sans compte (gardien, planton, agent d'entretien…), ou un membre du personnel absent de la plateforme.</p>
          <div class="grille-champs">
            <div class="champ">
              <label for="nCategorie">Catégorie</label>
              <select id="nCategorie" name="nCategorie" [(ngModel)]="nCategorie">
                @for (c of categories; track c[0]) { <option [value]="c[0]">{{ c[1] }}</option> }
              </select>
            </div>
            <div class="champ"><label for="nNom">Nom</label><input id="nNom" name="nNom" required maxlength="80" [(ngModel)]="nNom" /></div>
            <div class="champ"><label for="nPrenoms">Prénoms</label><input id="nPrenoms" name="nPrenoms" required maxlength="120" [(ngModel)]="nPrenoms" /></div>
            <div class="champ">
              <label for="nSexe">Sexe</label>
              <select id="nSexe" name="nSexe" [(ngModel)]="nSexe"><option value="M">Homme</option><option value="F">Femme</option></select>
            </div>
            <div class="champ"><label for="nFonction">Fonction dans l'établissement</label><input id="nFonction" name="nFonction" maxlength="120" placeholder="Gardien, planton…" [(ngModel)]="nFonction" /></div>
            <div class="champ"><label for="nPrise">Prise de service</label><input id="nPrise" name="nPrise" type="date" [(ngModel)]="nPrise" /></div>
          </div>
          <button type="submit" class="bouton" [disabled]="action.enCours() || !nNom().trim() || !nPrenoms().trim()">Créer le dossier</button>
        </form>
      }

      <div class="bascule" role="group" aria-label="Catégorie">
        <button type="button" [class.actif]="categorie() === null" (click)="categorie.set(null)">Tous ({{ enService().length }})</button>
        @for (c of categories; track c[0]) {
          <button type="button" [class.actif]="categorie() === c[0]" (click)="categorie.set(c[0])">{{ c[1] }}</button>
        }
        <button type="button" [class.actif]="categorie() === 'PARTIS'" (click)="categorie.set('PARTIS')">Partis</button>
      </div>
      <app-recherche libelle="Nom, matricule ou fonction" [(valeur)]="filtre" [total]="selection().length" [trouves]="affiches().length" />

      <div class="carte tableau-defilant">
        <table class="tableau">
          <thead>
            <tr><th>Agent</th><th>Matricule</th><th>Situation</th><th>Fonction</th><th>Prise de service</th><th>Ancienneté</th><th>Dossier</th></tr>
          </thead>
          <tbody>
            @for (a of affiches(); track a.id) {
              <tr>
                <td>
                  <a [routerLink]="['/personnel', a.id]"><strong>{{ a.nom }}</strong> {{ a.prenoms }}</a>
                  @if (a.absentAujourdhui) { <span class="pastille absent">absent(e) aujourd'hui</span> }
                  @if (!a.compte) { <br /><span class="doux">sans compte</span> }
                </td>
                <td>{{ a.matricule ?? '—' }}</td>
                <td>{{ a.situation ? situations[a.situation] : '—' }}</td>
                <td>{{ a.fonction ?? a.emploi ?? '—' }}</td>
                <td>{{ a.datePriseService ? dateCourte(a.datePriseService) : '—' }}@if (a.dateCessation) { <br /><span class="doux">parti(e) le {{ dateCourte(a.dateCessation) }}</span> }</td>
                <td>{{ a.ancienneteEtablissement?.texte ?? '—' }}</td>
                <td>
                  @if (a.signalements > 0) { <span class="pastille absent">{{ a.signalements }} signalement(s)</span> }
                  @if (a.manquants.length) { <span class="doux">À compléter : {{ a.manquants.join(', ') }}</span> }
                  @else { <span class="pastille present">complet</span> }
                </td>
              </tr>
            } @empty {
              <tr><td colspan="7" class="doux">{{ chargee() ? 'Aucun agent.' : 'Chargement…' }}</td></tr>
            }
          </tbody>
        </table>
      </div>
    </div>
  `,
})
export class PersonnelPage implements OnInit {
  private readonly api = inject(PersonnelApi);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);

  protected readonly dateCourte = dateCourte;
  protected readonly situations = SITUATIONS;
  protected readonly categories = Object.entries(CATEGORIES) as [CategorieAgent, string][];
  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly agents = signal<AgentResume[]>([]);
  protected readonly sansDossier = signal(0);
  protected readonly chargee = signal(false);
  protected readonly categorie = signal<CategorieAgent | 'PARTIS' | null>(null);
  protected readonly filtre = signal('');
  protected readonly creation = signal(false);
  protected readonly nCategorie = signal<CategorieAgent>('SOUTIEN');
  protected readonly nNom = signal('');
  protected readonly nPrenoms = signal('');
  protected readonly nSexe = signal<'M' | 'F'>('M');
  protected readonly nFonction = signal('');
  protected readonly nPrise = signal('');

  protected readonly gestion = computed(() => this.session.aLeRole('ADMIN_ECOLE', 'SECRETARIAT'));
  protected readonly enService = computed(() => this.agents().filter((a) => !a.dateCessation));
  protected readonly selection = computed(() => {
    const c = this.categorie();
    return c === 'PARTIS' ? this.agents().filter((a) => a.dateCessation)
      : this.enService().filter((a) => c === null || a.categorie === c);
  });
  protected readonly affiches = computed(() =>
    filtrer(this.selection(), this.filtre(), (a) => [a.nom, a.prenoms, a.matricule, a.fonction, a.emploi]),
  );

  async ngOnInit(): Promise<void> {
    await this.charger();
  }

  protected async reprendre(): Promise<void> {
    const r = await this.action.executer(() => this.api.reprendre());
    if (r) {
      this.message.set(`${r.crees} dossier(s) ouvert(s). Complétez-les : situation, dates de service, diplômes.`);
      await this.charger();
    }
  }

  protected async creer(): Promise<void> {
    const d = await this.action.executer(() => this.api.creer({
      ...vide(), categorie: this.nCategorie(), nom: this.nNom().trim(), prenoms: this.nPrenoms().trim(), sexe: this.nSexe(),
      fonction: this.nFonction().trim() || null, datePriseService: this.nPrise() || null,
    }));
    if (d) {
      await this.router.navigate(['/personnel', d.id]);
    }
  }

  private async charger(): Promise<void> {
    const l = await this.action.executer(() => this.api.lister());
    if (l) {
      this.agents.set(l.agents);
      this.sansDossier.set(l.comptesSansDossier);
    }
    this.chargee.set(true);
  }
}
