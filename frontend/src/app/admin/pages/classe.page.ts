import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { SessionService } from '../../core/session.service';
import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { AdminNavComponent } from '../admin-nav.component';
import {
  ClasseVue,
  EnseignantVue,
  InscriptionVue,
  LIBELLE_TYPE_MATIERE,
  MatiereDeClasseVue,
  MatiereVue,
  ProfilVue,
} from '../modeles-admin';

/** Brouillon d'une ligne du programme, modifiée avant enregistrement. */
interface Brouillon {
  coefficient: number;
  groupe: string;
  volumeHebdo: number | null;
  volumeTotal: number | null;
}

const GROUPES_PROPOSES = ['Matières générales', 'Matières techniques', 'Matières professionnelles'];

/** Une classe : son programme (matières, coefficients, enseignants) et ses élèves. */
@Component({
  selector: 'app-classe',
  imports: [FormsModule, RouterLink, AdminNavComponent],
  templateUrl: './classe.page.html',
  styles: `
    .ajout {
      border-top: 1px solid var(--bordure);
      margin-top: 1rem;
      padding-top: 0.5rem;
    }
    h3 {
      font-size: 1rem;
      margin: 0.5rem 0 0.75rem;
    }
    .court {
      width: 5.5rem;
    }
    .moyen {
      width: 12rem;
    }
  `,
})
export class ClassePage implements OnInit {
  readonly id = input.required<string>();

  private readonly api = inject(AdminApi);
  private readonly router = inject(Router);
  private readonly session = inject(SessionService);

  protected readonly types = LIBELLE_TYPE_MATIERE;
  protected readonly groupesProposes = GROUPES_PROPOSES;
  protected readonly action = new Action();
  protected readonly classe = signal<ClasseVue | null>(null);
  protected readonly profil = signal<ProfilVue | null>(null);
  protected readonly programme = signal<MatiereDeClasseVue[]>([]);
  protected readonly matieres = signal<MatiereVue[]>([]);
  protected readonly enseignants = signal<EnseignantVue[]>([]);
  protected readonly eleves = signal<InscriptionVue[]>([]);
  protected readonly brouillons = signal<Record<string, Brouillon>>({});
  protected readonly enregistre = signal<string | null>(null);

  protected readonly peutModifier = computed(() => this.session.aLeRole('ADMIN_ECOLE', 'CENSEUR'));
  protected readonly groupesObligatoires = computed(() => this.profil()?.modele === 'NOTES_PAR_GROUPES');
  protected readonly volumeTotalObligatoire = computed(() => this.profil()?.decoupage === 'MODULE');
  protected readonly matieresDisponibles = computed(() => {
    const dans = new Set(this.programme().map((m) => m.matiereId));
    return this.matieres().filter((m) => m.actif && !dans.has(m.id));
  });
  protected readonly enseignantsActifs = computed(() =>
    this.enseignants()
      .filter((e) => e.statut === 'ACTIF')
      .sort((a, b) => a.nom.localeCompare(b.nom)),
  );
  protected readonly chargeHebdo = computed(() =>
    this.programme().reduce((total, m) => total + (Number(m.volumeHebdo) || 0), 0),
  );
  protected readonly sansEnseignant = computed(() => this.programme().filter((m) => !m.engagementId).length);

  // Nouvelle matière
  protected readonly nMatiere = signal('');
  protected readonly nCoefficient = signal<number>(1);
  protected readonly nGroupe = signal('');
  protected readonly nVolumeHebdo = signal<number | null>(null);
  protected readonly nVolumeTotal = signal<number | null>(null);

  async ngOnInit(): Promise<void> {
    await this.action.executer(async () => {
      const classe = await this.api.classe(this.id());
      this.classe.set(classe);
      const [profils, programme, matieres, enseignants, eleves] = await Promise.all([
        this.api.profils(),
        this.api.matieresDeClasse(classe.id),
        this.api.matieres(),
        this.api.enseignants().catch(() => [] as EnseignantVue[]),
        this.api.inscriptions(classe.id),
      ]);
      this.profil.set(profils.find((p) => p.id === classe.profilId) ?? null);
      this.majProgramme(programme);
      this.matieres.set([...matieres].sort((a, b) => a.libelle.localeCompare(b.libelle)));
      this.enseignants.set(enseignants);
      this.eleves.set(eleves);
    });
  }

  private majProgramme(programme: MatiereDeClasseVue[]): void {
    const tries = [...programme].sort(
      (a, b) => (a.groupe ?? '').localeCompare(b.groupe ?? '') || a.matiereLibelle.localeCompare(b.matiereLibelle),
    );
    this.programme.set(tries);
    this.brouillons.set(
      Object.fromEntries(
        tries.map((m) => [
          m.matiereId,
          { coefficient: Number(m.coefficient), groupe: m.groupe ?? '', volumeHebdo: m.volumeHebdo, volumeTotal: m.volumeTotal },
        ]),
      ),
    );
  }

  protected brouillon(m: MatiereDeClasseVue): Brouillon {
    return this.brouillons()[m.matiereId];
  }

  protected modifier(m: MatiereDeClasseVue, champ: keyof Brouillon, valeur: string): void {
    const nombre = valeur === '' ? null : Number(valeur);
    this.brouillons.update((b) => ({
      ...b,
      [m.matiereId]: { ...b[m.matiereId], [champ]: champ === 'groupe' ? valeur : nombre },
    }));
  }

  protected modifiee(m: MatiereDeClasseVue): boolean {
    const b = this.brouillon(m);
    return (
      b.coefficient !== Number(m.coefficient) ||
      b.groupe !== (m.groupe ?? '') ||
      b.volumeHebdo !== (m.volumeHebdo === null ? null : Number(m.volumeHebdo)) ||
      b.volumeTotal !== (m.volumeTotal === null ? null : Number(m.volumeTotal))
    );
  }

  protected async enregistrer(m: MatiereDeClasseVue): Promise<void> {
    const b = this.brouillon(m);
    const r = await this.action.executer(() =>
      this.api.definirMatiere(this.id(), m.matiereId, {
        coefficient: b.coefficient,
        groupe: b.groupe.trim() || null,
        volumeHebdo: b.volumeHebdo,
        volumeTotal: b.volumeTotal,
      }),
    );
    if (r) {
      this.majProgramme(this.programme().map((x) => (x.matiereId === r.matiereId ? r : x)));
      this.enregistre.set(m.matiereLibelle);
    }
  }

  protected async affecter(m: MatiereDeClasseVue, engagementId: string): Promise<void> {
    const r = await this.action.executer(() =>
      engagementId ? this.api.affecter(this.id(), m.matiereId, engagementId) : this.api.retirerAffectation(this.id(), m.matiereId),
    );
    if (r) {
      this.majProgramme(this.programme().map((x) => (x.matiereId === r.matiereId ? r : x)));
    } else {
      // Refus du serveur : on remet la liste comme avant
      this.majProgramme([...this.programme()]);
    }
  }

  protected async retirer(m: MatiereDeClasseVue): Promise<void> {
    if (!window.confirm(`Retirer ${m.matiereLibelle} du programme de la classe ?`)) {
      return;
    }
    if (await this.action.reussit(() => this.api.retirerMatiere(this.id(), m.matiereId))) {
      this.majProgramme(this.programme().filter((x) => x.matiereId !== m.matiereId));
    }
  }

  protected async ajouter(): Promise<void> {
    const r = await this.action.executer(() =>
      this.api.definirMatiere(this.id(), this.nMatiere(), {
        coefficient: this.nCoefficient(),
        groupe: this.nGroupe().trim() || null,
        volumeHebdo: this.nVolumeHebdo(),
        volumeTotal: this.nVolumeTotal(),
      }),
    );
    if (r) {
      this.majProgramme([...this.programme(), r]);
      this.nMatiere.set('');
      this.nVolumeHebdo.set(null);
      this.nVolumeTotal.set(null);
    }
  }

  protected nomEnseignant(engagementId: string | null): string {
    const e = this.enseignants().find((x) => x.engagementId === engagementId);
    return e ? `${e.nom} ${e.prenoms}` : '';
  }

  protected async supprimer(): Promise<void> {
    const c = this.classe();
    if (!c || !window.confirm(`Supprimer la classe ${c.code} ?`)) {
      return;
    }
    if (await this.action.reussit(() => this.api.supprimerClasse(c.id))) {
      await this.router.navigate(['/admin/classes']);
    }
  }
}
