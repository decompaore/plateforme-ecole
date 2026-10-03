import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { SessionService } from '../../core/session.service';
import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { AdminNavComponent } from '../admin-nav.component';
import { AnneeCourante } from '../annee-courante.service';
import { ClasseVue, DossierEleveVue, EleveVue, LienParente, Page, RapportImport, Sexe, StatutBourse } from '../modeles-admin';

const TAILLE_PAGE = 20;

/** Élèves : recherche, nouvel élève avec son inscription, import Excel de la rentrée. */
@Component({
  selector: 'app-eleves',
  imports: [FormsModule, RouterLink, AdminNavComponent],
  templateUrl: './eleves.page.html',
  styles: `
    .recherche {
      display: flex;
      gap: 0.5rem;
      margin-bottom: 0.75rem;
    }
    .ligne {
      cursor: pointer;
    }
    .pagination {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-top: 0.75rem;
    }
    h3 {
      font-size: 1rem;
      margin: 1rem 0 0.75rem;
    }
    .rapport li {
      padding: 0.35rem 0;
    }
  `,
})
export class ElevesPage {
  /** Classe proposée par défaut pour l'inscription (?classe=… depuis la fiche d'une classe). */
  readonly classe = input<string | undefined>();

  private readonly api = inject(AdminApi);
  private readonly session = inject(SessionService);
  protected readonly anneeCourante = inject(AnneeCourante);

  protected readonly action = new Action();
  protected readonly actionImport = new Action();
  protected readonly annee = this.anneeCourante.annee;
  protected readonly peutModifier = computed(() => this.session.aLeRole('ADMIN_ECOLE', 'SECRETARIAT'));
  protected readonly classes = signal<ClasseVue[]>([]);

  // Recherche
  protected readonly q = signal('');
  protected readonly page = signal<Page<EleveVue> | null>(null);
  protected readonly dossier = signal<DossierEleveVue | null>(null);

  // Nouvel élève
  protected readonly formulaire = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly nom = signal('');
  protected readonly prenoms = signal('');
  protected readonly sexe = signal<Sexe>('F');
  protected readonly dateNaissance = signal('');
  protected readonly lieuNaissance = signal('');
  protected readonly classeId = signal('');
  protected readonly redoublant = signal(false);
  protected readonly bourse = signal<StatutBourse>('NON_BOURSIER');
  protected readonly parentNom = signal('');
  protected readonly parentPrenoms = signal('');
  protected readonly parentTelephone = signal('');
  protected readonly parentLien = signal<LienParente>('PERE');

  // Import
  protected readonly fichier = signal<File | null>(null);
  protected readonly rapport = signal<RapportImport | null>(null);

  /** Inscription de l'élève affiché dans l'année de travail. */
  protected readonly inscriptionCourante = computed(() => {
    const a = this.annee();
    return this.dossier()?.inscriptions.find((i) => i.anneeId === a?.id) ?? null;
  });

  constructor() {
    void this.rechercher(0);
    effect(() => {
      const a = this.annee();
      untracked(() => {
        if (a) {
          void this.action.executer(async () => {
            const classes = (await this.api.classes(a.id)).sort((x, y) => x.code.localeCompare(y.code));
            this.classes.set(classes);
            const demandee = this.classe();
            this.classeId.set(classes.find((c) => c.id === demandee)?.id ?? classes[0]?.id ?? '');
            if (demandee) {
              this.formulaire.set(true);
            }
          });
        }
      });
    });
  }

  protected async rechercher(numero: number): Promise<void> {
    const p = await this.action.executer(() => this.api.eleves(this.q().trim(), numero, TAILLE_PAGE));
    if (p) {
      this.page.set(p);
    }
  }

  protected async ouvrir(e: EleveVue): Promise<void> {
    const d = await this.action.executer(() => this.api.dossier(e.id));
    if (d) {
      this.dossier.set(d);
    }
  }

  protected nomClasse(id: string): string {
    return this.classes().find((c) => c.id === id)?.code ?? '';
  }

  protected async creer(): Promise<void> {
    this.message.set(null);
    const responsables = this.parentTelephone().trim()
      ? [{ nom: this.parentNom().trim() || this.nom().trim(), prenoms: this.parentPrenoms().trim(), telephone: this.parentTelephone().trim(), lien: this.parentLien() }]
      : [];
    const dossier = await this.action.executer(() =>
      this.api.creerEleve({
        nom: this.nom().trim(),
        prenoms: this.prenoms().trim(),
        sexe: this.sexe(),
        dateNaissance: this.dateNaissance(),
        lieuNaissance: this.lieuNaissance().trim() || null,
        responsables,
      }),
    );
    if (!dossier) {
      return;
    }
    const nomComplet = `${dossier.eleve.nom} ${dossier.eleve.prenoms}`;
    if (this.classeId()) {
      const inscription = await this.action.executer(() =>
        this.api.inscrire({
          eleveId: dossier.eleve.id,
          classeId: this.classeId(),
          redoublant: this.redoublant(),
          statutBourse: this.bourse(),
        }),
      );
      this.message.set(
        inscription
          ? `${nomComplet} inscrit(e) en ${inscription.classeCode} (matricule ${dossier.eleve.matricule ?? '—'}).`
          : `Dossier de ${nomComplet} créé, mais l'inscription a échoué : voir le message ci-dessus.`,
      );
    } else {
      this.message.set(`Dossier de ${nomComplet} créé.`);
    }
    // On garde la classe et le parent n'est pas réutilisé : on vide le reste pour l'élève suivant
    for (const s of [this.nom, this.prenoms, this.dateNaissance, this.lieuNaissance, this.parentNom, this.parentPrenoms, this.parentTelephone]) {
      s.set('');
    }
    this.redoublant.set(false);
    await this.rechercher(0);
  }

  protected async inscrireDossier(): Promise<void> {
    const d = this.dossier();
    if (!d || !this.classeId()) {
      return;
    }
    if (await this.action.executer(() => this.api.inscrire({ eleveId: d.eleve.id, classeId: this.classeId(), redoublant: false, statutBourse: null }))) {
      this.dossier.set(await this.api.dossier(d.eleve.id));
    }
  }

  // ---------- Import Excel

  protected async telechargerModele(): Promise<void> {
    const a = this.annee();
    if (!a) {
      return;
    }
    const blob = await this.actionImport.executer(() => this.api.modeleImport(a.id));
    if (blob) {
      const url = URL.createObjectURL(blob);
      const lien = document.createElement('a');
      lien.href = url;
      lien.download = `eleves-${a.libelle}.xlsx`;
      lien.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
  }

  protected choisirFichier(e: Event): void {
    this.fichier.set((e.target as HTMLInputElement).files?.[0] ?? null);
    this.rapport.set(null);
  }

  protected async importer(simulation: boolean): Promise<void> {
    const a = this.annee();
    const f = this.fichier();
    if (!a || !f) {
      return;
    }
    const r = await this.actionImport.executer(() => this.api.importer(a.id, f, simulation));
    if (r) {
      this.rapport.set(r);
      if (!simulation) {
        await this.rechercher(0);
      }
    }
  }
}
