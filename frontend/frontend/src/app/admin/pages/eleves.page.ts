import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { SessionService } from '../../core/session.service';
import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { AdminNavComponent } from '../admin-nav.component';
import { AnneeCourante } from '../annee-courante.service';
import {
  ClasseVue,
  DossierEleveVue,
  EleveVue,
  LIBELLE_LIEN,
  LienParente,
  Page,
  RapportImport,
  ResponsableDeEleveVue,
  Sexe,
  StatutBourse,
} from '../modeles-admin';
import { MotDePasseTemporaireComponent } from '../mot-de-passe-temporaire.component';

const TAILLE_PAGE = 20;

/** Contrôle d'un parent ou tuteur avant l'envoi. */
export function erreurResponsable(nom: string, prenoms: string, telephone: string): string | null {
  if (!nom.trim() || !prenoms.trim()) {
    return 'Indiquez le nom et les prénoms.';
  }
  if (!/^\+?[0-9 ]{8,16}$/.test(telephone.trim())) {
    return 'Téléphone : 8 chiffres (ou numéro international).';
  }
  return null;
}

/** Élèves : recherche, nouvel élève avec son inscription, import Excel de la rentrée. */
@Component({
  selector: 'app-eleves',
  imports: [FormsModule, RouterLink, AdminNavComponent, MotDePasseTemporaireComponent],
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
    .responsable {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 0.5rem;
      .pastille {
        margin: 0.3rem 0.3rem 0 0;
      }
    }
    .sous-formulaire {
      margin-top: 0.75rem;
      padding-top: 0.75rem;
      border-top: 1px solid var(--bordure);
    }
    .case {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      margin: 0.3rem 0;
    }
    .rouge {
      color: var(--absent);
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
  protected readonly espaceParent = this.session.moduleActif('ESPACE_PARENT');
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

  // Parents et tuteurs du dossier ouvert (v0.29)
  protected readonly libelleLien = LIBELLE_LIEN;
  protected readonly liens: LienParente[] = ['PERE', 'MERE', 'TUTEUR', 'AUTRE'];
  protected readonly secret = signal<{ titre: string; telephone: string; motDePasse: string } | null>(null);
  protected readonly formResponsable = signal(false);
  protected readonly rNom = signal('');
  protected readonly rPrenoms = signal('');
  protected readonly rTelephone = signal('');
  protected readonly rLien = signal<LienParente>('PERE');
  protected readonly rProfession = signal('');
  protected readonly rLegal = signal(true);
  protected readonly rPrioritaire = signal(false);
  protected readonly rCompte = signal(true);
  protected readonly erreurResponsable = computed(() => erreurResponsable(this.rNom(), this.rPrenoms(), this.rTelephone()));

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

  // ---------------- Parents et tuteurs

  protected ouvrirFormResponsable(): void {
    for (const x of [this.rNom, this.rPrenoms, this.rTelephone, this.rProfession]) {
      x.set('');
    }
    this.rLien.set('PERE');
    this.rLegal.set(true);
    this.rPrioritaire.set(false);
    this.rCompte.set(true);
    this.formResponsable.set(true);
  }

  protected async ajouterResponsable(): Promise<void> {
    const d = this.dossier();
    if (!d || this.erreurResponsable()) {
      return;
    }
    const telephone = this.rTelephone().trim();
    const apres = await this.action.executer(() =>
      this.api.ajouterResponsable(d.eleve.id, {
        nom: this.rNom().trim(),
        prenoms: this.rPrenoms().trim(),
        telephone,
        lien: this.rLien(),
        profession: this.rProfession().trim() || null,
        langueSms: null,
        responsableLegal: this.rLegal(),
        contactPrioritaire: this.rPrioritaire(),
      }),
    );
    if (!apres) {
      return;
    }
    this.dossier.set(apres);
    this.formResponsable.set(false);
    this.message.set(`${this.rPrenoms().trim()} ${this.rNom().trim().toUpperCase()} ajouté(e) au dossier.`);
    const nouveau = apres.responsables.find((r) => r.telephone.replace(/\D/g, '').endsWith(telephone.replace(/\D/g, '').slice(-8)));
    if (this.rCompte() && this.espaceParent && nouveau && !nouveau.espaceParentOuvert) {
      await this.ouvrirEspaceParent(nouveau);
    }
  }

  protected async retirerResponsable(r: ResponsableDeEleveVue): Promise<void> {
    const d = this.dossier();
    if (!d || !window.confirm(`Retirer ${r.prenoms} ${r.nom} des responsables de ${d.eleve.prenoms} ${d.eleve.nom} ?`)) {
      return;
    }
    const apres = await this.action.executer(() => this.api.retirerResponsable(d.eleve.id, r.responsableId));
    if (apres) {
      this.dossier.set(apres);
    }
  }

  /** Crée (ou rouvre) le compte parent : mot de passe provisoire affiché une seule fois. */
  protected async ouvrirEspaceParent(r: ResponsableDeEleveVue): Promise<void> {
    const d = this.dossier();
    const v = await this.action.executer(() => this.api.ouvrirEspaceParent(r.responsableId));
    if (!v || !d) {
      return;
    }
    if (v.motDePasseTemporaire) {
      this.message.set(null);
      this.secret.set({
        titre: `Compte parent de ${r.prenoms} ${r.nom} créé.`,
        telephone: v.telephone,
        motDePasse: v.motDePasseTemporaire,
      });
    } else {
      this.message.set(`${r.prenoms} ${r.nom} avait déjà un compte : il ou elle accède à l'espace parent avec son mot de passe habituel.`);
    }
    this.dossier.set({ ...d, responsables: d.responsables.map((x) => (x.responsableId === r.responsableId ? { ...x, espaceParentOuvert: true } : x)) });
  }
}
