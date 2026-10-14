import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { messageErreur } from '../../core/erreurs';
import { SessionService } from '../../core/session.service';
import { Action } from '../action';
import { AdminApi } from '../admin-api.service';
import { AdminNavComponent } from '../admin-nav.component';
import { AnneeCourante } from '../annee-courante.service';
import {
  ClasseVue,
  DossierEleveVue,
  DossierInscriptionVue,
  EleveVue,
  LIBELLE_LIEN,
  LIBELLE_MODE_ADMISSION,
  LienParente,
  LigneDossierVue,
  ModeAdmission,
  MODES_ADMISSION,
  Page,
  PieceVue,
  RapportImport,
  ResponsableDeEleveVue,
  Sexe,
  StatutBourse,
} from '../modeles-admin';
import { MotDePasseTemporaireComponent } from '../mot-de-passe-temporaire.component';
import { ChoixPhotoComponent, PhotoIdentiteComponent, VignetteComponent } from '../../partage/photo-identite.component';
import { PhotosApi } from '../../partage/photos-api.service';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';

const TAILLE_PAGE = 20;

/** Fait défiler la page jusqu'au dossier ouvert (il s'affiche sous la liste). */
function montrerDossier(): void {
  setTimeout(() => document.getElementById('dossier-eleve')?.scrollIntoView?.({ behavior: 'smooth', block: 'start' }));
}

/** Fin du message de création selon la photo. */
function suitePhoto(photo: File | null, echec: string | null): string {
  if (!photo) {
    return " Vous pouvez ajouter sa photo dans son dossier, ci-dessous.";
  }
  return echec ? ` Photo non enregistrée : ${echec}` : ' Photo enregistrée.';
}

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
  imports: [FormsModule, RouterLink, AdminNavComponent, MotDePasseTemporaireComponent, PhotoIdentiteComponent, VignetteComponent, ChoixPhotoComponent],
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
    .col-photo {
      width: 48px;
      padding-block: 0.2rem;
    }
    button.photo {
      appearance: none;
      border: 0;
      padding: 0;
      background: none;
      cursor: pointer;
    }
    .identite-eleve {
      display: flex;
      gap: 1rem;
      align-items: flex-start;
      flex-wrap: wrap;
      margin-bottom: 0.5rem;
    }
  `,
})
export class ElevesPage {
  /** Classe proposée par défaut pour l'inscription (?classe=… depuis la fiche d'une classe). */
  readonly classe = input<string | undefined>();
  /** Dossier à ouvrir d'emblée (?eleve=… depuis la liste d'une classe, pour sa photo). */
  readonly eleve = input<string | undefined>();

  private readonly api = inject(AdminApi);
  private readonly session = inject(SessionService);
  private readonly photos = inject(PhotosApi);
  protected readonly anneeCourante = inject(AnneeCourante);

  protected readonly action = new Action();
  protected readonly actionImport = new Action();
  protected readonly annee = this.anneeCourante.annee;
  /** L'intendant reçoit le dossier et inscrit (v0.47). */
  protected readonly peutSaisir = computed(() => this.session.aLeRole('ADMIN_ECOLE', 'INTENDANT'));
  /** Une fois l'inscription enregistrée, seul le chef d'établissement modifie. */
  protected readonly peutModifier = computed(() => this.session.aLeRole('ADMIN_ECOLE'));
  /** Documents remis aux familles : cartes scolaires, comptes parents. */
  protected readonly peutDocuments = computed(() => this.session.aLeRole('ADMIN_ECOLE', 'INTENDANT', 'SECRETARIAT'));
  protected readonly modes = MODES_ADMISSION;
  protected readonly libelleMode = LIBELLE_MODE_ADMISSION;
  protected readonly modeAdmission = signal<ModeAdmission>('AFFECTATION_ETAT');

  // Dossier d'inscription de l'élève ouvert (v0.47)
  protected readonly piecesAttendues = signal<PieceVue[]>([]);
  protected readonly dossierInscription = signal<DossierInscriptionVue | null>(null);
  protected readonly dMode = signal<ModeAdmission | null>(null);
  protected readonly dRecues = signal<Set<string>>(new Set());
  protected readonly recuesInitiales = signal<Set<string>>(new Set());
  protected readonly piecesAffichees = computed(() => {
    const mode = this.dMode();
    const recues = this.dRecues();
    if (!mode) {
      return [];
    }
    return this.piecesAttendues()
      .filter((p) => p.modes.includes(mode) || recues.has(p.id))
      .map((p) => ({ ...p, obligatoire: p.obligatoire && p.modes.includes(mode) }));
  });
  protected readonly manquantesDossier = computed(() => {
    const mode = this.dMode();
    if (!mode) {
      return 1;
    }
    return this.piecesAffichees().filter((p) => p.obligatoire && !this.dRecues().has(p.id)).length;
  });
  protected readonly incomplets = signal<LigneDossierVue[] | null>(null);
  protected readonly reglagePieces = signal<{ id: string | null; libelle: string; modes: ModeAdmission[]; obligatoire: boolean }[] | null>(null);
  protected readonly espaceParent = this.session.moduleActif('ESPACE_PARENT');
  protected readonly classes = signal<ClasseVue[]>([]);

  // Recherche
  protected readonly q = signal('');
  protected readonly page = signal<Page<EleveVue> | null>(null);
  protected readonly dossier = signal<DossierEleveVue | null>(null);

  /** Élèves de la page affichée qui ont une photo (v0.44.1). */
  protected readonly avecPhoto = signal<Set<string>>(new Set());

  // Nouvel élève
  protected readonly formulaire = signal(false);
  /** Photo d'identité choisie dans le formulaire, envoyée une fois le dossier créé. */
  protected readonly photoNouvel = signal<File | null>(null);
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
      const id = this.eleve();
      untracked(() => {
        if (id) {
          void this.ouvrir({ id } as EleveVue);
        }
      });
    });
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
      const ids = p.elements.map((e) => e.id);
      this.avecPhoto.set(new Set(await this.photos.elevesParmi(ids).catch(() => [] as string[])));
    }
  }

  protected async ouvrir(e: Pick<EleveVue, 'id'>): Promise<void> {
    const d = await this.action.executer(() => this.api.dossier(e.id));
    if (d) {
      this.dossier.set(d);
      this.dossierInscription.set(null);
      montrerDossier();
    }
  }

  protected photoChangee(eleveId: string, presente: boolean): void {
    const s = new Set(this.avecPhoto());
    if (presente) {
      s.add(eleveId);
    } else {
      s.delete(eleveId);
    }
    this.avecPhoto.set(s);
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
    // Photo d'identité choisie dans le formulaire (v0.44.1)
    const photo = this.photoNouvel();
    let photoEchec: string | null = null;
    if (photo) {
      try {
        await this.photos.deposerPhoto(`/eleves/${dossier.eleve.id}/photo`, photo);
        this.photoNouvel.set(null);
      } catch (e) {
        photoEchec = messageErreur(e, "La photo n'a pas pu être enregistrée");
      }
    }
    if (this.classeId()) {
      const inscription = await this.action.executer(() =>
        this.api.inscrire({
          eleveId: dossier.eleve.id,
          classeId: this.classeId(),
          redoublant: this.redoublant(),
          statutBourse: this.bourse(),
          modeAdmission: this.modeAdmission(),
        }),
      );
      this.message.set(
        (inscription
          ? `${nomComplet} inscrit(e) en ${inscription.classeCode} (matricule ${dossier.eleve.matricule ?? '—'}).`
          : `Dossier de ${nomComplet} créé, mais l'inscription a échoué : voir le message ci-dessus.`) + suitePhoto(photo, photoEchec),
      );
    } else {
      this.message.set(`Dossier de ${nomComplet} créé.` + suitePhoto(photo, photoEchec));
    }
    // On garde la classe et le parent n'est pas réutilisé : on vide le reste pour l'élève suivant
    for (const s of [this.nom, this.prenoms, this.dateNaissance, this.lieuNaissance, this.parentNom, this.parentPrenoms, this.parentTelephone]) {
      s.set('');
    }
    this.redoublant.set(false);
    await this.rechercher(0);
    // Le dossier s'ouvre : la photo d'identité s'ajoute tout de suite (v0.44)
    const ouvert = await this.action.executer(() => this.api.dossier(dossier.eleve.id));
    if (ouvert) {
      this.dossier.set(ouvert);
      montrerDossier();
    }
  }

  /** Carte d'identité scolaire de l'élève ouvert (v0.44). */
  protected async carteScolaire(): Promise<void> {
    const d = this.dossier();
    if (!d) {
      return;
    }
    const pdf = await this.action.executer(() => this.photos.carteEleve(d.eleve.id));
    if (pdf) {
      enregistrerFichier(pdf, `carte-${d.eleve.matricule ?? d.eleve.nom}.pdf`);
    }
  }

  protected async inscrireDossier(): Promise<void> {
    const d = this.dossier();
    if (!d || !this.classeId()) {
      return;
    }
    if (
      await this.action.executer(() =>
        this.api.inscrire({ eleveId: d.eleve.id, classeId: this.classeId(), redoublant: false, statutBourse: null, modeAdmission: this.modeAdmission() }),
      )
    ) {
      this.dossier.set(await this.api.dossier(d.eleve.id));
    }
  }

  // ---------- Dossier d'inscription (v0.47)

  protected async ouvrirDossierInscription(inscriptionId: string): Promise<void> {
    const r = await this.action.executer(() => Promise.all([this.api.piecesDossier(), this.api.dossierInscription(inscriptionId)]));
    if (r) {
      this.piecesAttendues.set(r[0]);
      this.afficherDossier(r[1]);
    }
  }

  private afficherDossier(d: DossierInscriptionVue): void {
    const recues = new Set(d.pieces.filter((p) => p.recue).map((p) => p.pieceId));
    this.dossierInscription.set(d);
    this.dMode.set(d.modeAdmission);
    this.dRecues.set(recues);
    this.recuesInitiales.set(new Set(recues));
  }

  protected basculerPiece(id: string): void {
    const s = new Set(this.dRecues());
    if (s.has(id)) {
      s.delete(id);
    } else {
      s.add(id);
    }
    this.dRecues.set(s);
  }

  protected async enregistrerDossierInscription(): Promise<void> {
    const d = this.dossierInscription();
    if (!d) {
      return;
    }
    const apres = await this.action.executer(() => this.api.enregistrerDossier(d.inscriptionId, this.dMode(), [...this.dRecues()]));
    if (apres) {
      this.afficherDossier(apres);
      this.message.set(apres.complet ? 'Dossier d’inscription complet.' : `Dossier enregistré : ${apres.manquantes} pièce(s) manquante(s).`);
    }
  }

  protected async chargerIncomplets(anneeId: string): Promise<void> {
    const l = await this.action.executer(() => this.api.dossiersInscription(anneeId, true));
    if (l) {
      this.incomplets.set(l);
    }
  }

  // ---------- Pièces demandées (chef d'établissement)

  protected async ouvrirReglagePieces(): Promise<void> {
    const p = await this.action.executer(() => this.api.piecesDossier());
    if (p) {
      this.reglagePieces.set(p.map((x) => ({ id: x.id, libelle: x.libelle, modes: [...x.modes], obligatoire: x.obligatoire })));
    }
  }

  private modifierPiece(i: number, f: (l: { id: string | null; libelle: string; modes: ModeAdmission[]; obligatoire: boolean }) => void): void {
    const lignes = (this.reglagePieces() ?? []).map((l) => ({ ...l, modes: [...l.modes] }));
    f(lignes[i]);
    this.reglagePieces.set(lignes);
  }

  protected libellePiece(i: number, v: string): void {
    this.modifierPiece(i, (l) => (l.libelle = v));
  }

  protected basculerMode(i: number, m: ModeAdmission): void {
    this.modifierPiece(i, (l) => (l.modes = l.modes.includes(m) ? l.modes.filter((x) => x !== m) : [...l.modes, m]));
  }

  protected basculerObligatoire(i: number): void {
    this.modifierPiece(i, (l) => (l.obligatoire = !l.obligatoire));
  }

  protected retirerPiece(i: number): void {
    this.reglagePieces.set((this.reglagePieces() ?? []).filter((_, j) => j !== i));
  }

  protected ajouterPiece(): void {
    this.reglagePieces.set([...(this.reglagePieces() ?? []), { id: null, libelle: '', modes: [...MODES_ADMISSION].filter((m) => m !== 'REINSCRIPTION'), obligatoire: true }]);
  }

  protected async enregistrerPieces(): Promise<void> {
    const lignes = (this.reglagePieces() ?? []).map((l) => ({ ...l, libelle: l.libelle.trim() })).filter((l) => l.libelle);
    const p = await this.action.executer(() => this.api.definirPieces(lignes));
    if (p) {
      this.reglagePieces.set(null);
      this.message.set(`Liste enregistrée : ${p.length} pièce(s) demandée(s).`);
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
