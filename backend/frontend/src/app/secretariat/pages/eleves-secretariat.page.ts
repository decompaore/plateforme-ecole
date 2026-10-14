import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { ClasseVue, DossierEleveVue, EleveVue } from '../../admin/modeles-admin';
import { dateCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import { CandidatVue, CertificatVue, GESTION_SECRETARIAT } from '../modeles-secretariat';
import { SecretariatApi } from '../secretariat-api.service';
import { SecretariatNavComponent } from '../secretariat-nav.component';

/**
 * Certificats de scolarité (numérotés, inscrits au registre) et dossiers des examens officiels (BEPC, BAC, CAP) :
 * liste des candidats des classes choisies et ce qui manque à leur dossier (v0.47.1).
 */
@Component({
  selector: 'app-eleves-secretariat',
  imports: [FormsModule, SecretariatNavComponent],
  template: `
    <div class="page large">
      <h1>Certificats et examens</h1>
      <app-secretariat-nav />
      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      @if (gestion()) {
        <section class="carte">
          <h2>Certificat de scolarité</h2>
          <form class="actions-ligne" (ngSubmit)="chercher()">
            <input name="q" type="search" placeholder="Nom ou matricule de l'élève" [(ngModel)]="q" />
            <button type="submit" class="bouton secondaire petit">Chercher</button>
          </form>
          <ul class="liste">
            @for (e of eleves(); track e.id) {
              <li><button type="button" class="bouton discret petit" (click)="ouvrir(e)">{{ e.nom }} {{ e.prenoms }} · {{ e.matricule }}</button></li>
            }
          </ul>
          @if (dossier(); as d) {
            <div class="sous-formulaire">
              <strong>{{ d.eleve.nom }} {{ d.eleve.prenoms }}</strong>
              <ul class="liste">
                @for (i of d.inscriptions; track i.id) {
                  <li class="ligne-lien">
                    <span>{{ i.anneeLibelle }} · {{ i.classeCode }} · {{ i.statut === 'ACTIVE' ? 'inscrit(e)' : i.statut.toLowerCase() }}</span>
                    <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="certificat(d, i.id)">Délivrer le certificat</button>
                  </li>
                } @empty {
                  <li class="doux">Aucune inscription.</li>
                }
              </ul>
            </div>
          }
          <h3>Certificats délivrés en {{ anneeCivile }}</h3>
          <ul class="liste">
            @for (c of certificats(); track c.id) {
              <li><strong>{{ c.numero }}</strong> · {{ c.eleve }} · {{ c.classe }} ({{ c.annee }}) · {{ dateCourte(c.delivreLe.slice(0, 10)) }}</li>
            } @empty {
              <li class="doux">Aucun certificat cette année.</li>
            }
          </ul>
        </section>
      }

      <section class="carte">
        <h2>Dossiers d'examen</h2>
        <div class="grille-champs">
          <div class="champ">
            <label for="examen">Examen</label>
            <input id="examen" name="examen" maxlength="40" placeholder="BEPC, BAC, CAP…" [(ngModel)]="examen" />
          </div>
        </div>
        <div class="classes">
          @for (c of classes(); track c.id) {
            <label class="case"><input type="checkbox" [checked]="choisies().has(c.id)" (change)="basculer(c.id)" /> {{ c.code }}</label>
          }
        </div>
        <div class="actions-ligne">
          <button type="button" class="bouton" [disabled]="!choisies().size || action.enCours()" (click)="verifier()">Vérifier les dossiers</button>
          @if (candidats()) {
            <button type="button" class="bouton secondaire petit" (click)="exporter('xlsx')">Liste (Excel)</button>
            <button type="button" class="bouton secondaire petit" (click)="exporter('pdf')">… (PDF)</button>
          }
        </div>
        @if (candidats(); as l) {
          <p class="doux">{{ l.length }} candidat(s) · {{ incomplets() }} dossier(s) incomplet(s)</p>
          <div class="tableau-defilant">
            <table class="tableau">
              <thead><tr><th>Classe</th><th>Nom et prénoms</th><th>Né(e) le</th><th>À</th><th>Manque</th></tr></thead>
              <tbody>
                @for (c of l; track c.inscriptionId) {
                  <tr>
                    <td>{{ c.classe }}</td>
                    <td><strong>{{ c.nom }}</strong> {{ c.prenoms }}</td>
                    <td>{{ dateCourte(c.dateNaissance) }}</td>
                    <td>{{ c.lieuNaissance ?? '—' }}</td>
                    <td [class.rouge]="c.manquants.length">{{ c.manquants.join(', ') || 'complet' }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    </div>
  `,
  styles: `
    .classes {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem 1rem;
      margin: 0.5rem 0;
    }
    .rouge {
      color: var(--absent);
    }
  `,
})
export class ElevesSecretariatPage {
  private readonly api = inject(SecretariatApi);
  private readonly admin = inject(AdminApi);
  private readonly session = inject(SessionService);
  private readonly annee = inject(AnneeCourante);

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly gestion = computed(() => this.session.aLeRole(...GESTION_SECRETARIAT));
  protected readonly dateCourte = dateCourte;
  protected readonly anneeCivile = new Date().getFullYear();

  protected readonly q = signal('');
  protected readonly eleves = signal<EleveVue[]>([]);
  protected readonly dossier = signal<DossierEleveVue | null>(null);
  protected readonly certificats = signal<CertificatVue[]>([]);
  protected readonly classes = signal<ClasseVue[]>([]);
  protected readonly choisies = signal<Set<string>>(new Set());
  protected readonly examen = signal('');
  protected readonly candidats = signal<CandidatVue[] | null>(null);
  protected readonly incomplets = computed(() => (this.candidats() ?? []).filter((c) => c.manquants.length).length);

  constructor() {
    effect(() => {
      const a = this.annee.annee();
      untracked(() => {
        if (a) {
          void this.action.executer(async () => {
            this.classes.set((await this.admin.classes(a.id)).sort((x, y) => x.code.localeCompare(y.code)));
          });
        }
      });
    });
    void this.annee.charger().catch(() => undefined);
    if (this.gestion()) {
      void this.action.executer(async () => this.certificats.set(await this.api.certificats(this.anneeCivile)));
    }
  }

  protected async chercher(): Promise<void> {
    const p = await this.action.executer(() => this.admin.eleves(this.q().trim(), 0, 10));
    if (p) {
      this.eleves.set(p.elements);
      this.dossier.set(null);
    }
  }

  protected async ouvrir(e: EleveVue): Promise<void> {
    const d = await this.action.executer(() => this.admin.dossier(e.id));
    if (d) {
      this.dossier.set(d);
    }
  }

  protected async certificat(d: DossierEleveVue, inscriptionId: string): Promise<void> {
    this.message.set(null);
    const b = await this.action.executer(() => this.api.certificat(inscriptionId));
    if (b) {
      enregistrerFichier(b, `certificat-scolarite-${d.eleve.matricule ?? d.eleve.nom}.pdf`);
      const l = await this.action.executer(() => this.api.certificats(this.anneeCivile));
      if (l) {
        this.certificats.set(l);
        this.message.set(`Certificat ${l[0]?.numero ?? ''} délivré à ${d.eleve.prenoms} ${d.eleve.nom}.`);
      }
    }
  }

  protected basculer(id: string): void {
    const s = new Set(this.choisies());
    if (s.has(id)) {
      s.delete(id);
    } else {
      s.add(id);
    }
    this.choisies.set(s);
  }

  protected async verifier(): Promise<void> {
    const l = await this.action.executer(() => this.api.candidats([...this.choisies()]));
    if (l) {
      this.candidats.set(l);
    }
  }

  protected async exporter(format: 'xlsx' | 'pdf'): Promise<void> {
    const b = await this.action.executer(() => this.api.exporterCandidats([...this.choisies()], this.examen().trim(), format));
    if (b) {
      enregistrerFichier(b, `candidats-${this.examen().trim().toLowerCase() || 'examen'}.${format}`);
    }
  }
}
