import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { ClasseVue, FiliereVue } from '../../admin/modeles-admin';
import { dateCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import {
  DonneesFrais,
  fcfa,
  FraisVue,
  LIBELLE_PORTEE,
  LIBELLE_TYPE_ORGANISME,
  lireMontant,
  OrganismeVue,
  ParametresScolarite,
  Portee,
  TypeOrganisme,
} from '../modeles-scolarite';
import { GESTION_SCOLARITE, ScoNavComponent } from '../sco-nav.component';
import { ScolariteApi } from '../scolarite-api.service';

interface TrancheSaisie {
  dateLimite: string;
  montant: string;
}

/** Répartit un montant en n tranches entières ; l'arrondi va sur la première. */
export function repartir(montant: number, n: number): number[] {
  const base = Math.floor(montant / n);
  return Array.from({ length: n }, (_, i) => (i === 0 ? montant - base * (n - 1) : base));
}

/** Contrôle d'un frais avant l'envoi (les mêmes règles que le serveur, dites plus tôt). */
export function erreurFrais(d: { libelle: string; montant: number | null; portee: Portee; cibles: number; tranches: { dateLimite: string; montant: number | null }[] }): string | null {
  if (!d.libelle.trim()) {
    return 'Donnez un libellé (Scolarité, Inscription, Cantine…).';
  }
  if (d.montant === null) {
    return 'Montant en francs CFA, sans centimes.';
  }
  if (d.portee !== 'TOUTES' && d.cibles === 0) {
    return 'Choisissez au moins une filière, un niveau ou une classe.';
  }
  if (d.tranches.length) {
    if (d.tranches.some((t) => !t.dateLimite || t.montant === null)) {
      return 'Chaque tranche a une date limite et un montant.';
    }
    for (let i = 1; i < d.tranches.length; i++) {
      if (d.tranches[i].dateLimite <= d.tranches[i - 1].dateLimite) {
        return 'Les dates des tranches doivent se suivre.';
      }
    }
    const somme = d.tranches.reduce((t, x) => t + (x.montant ?? 0), 0);
    if (somme !== d.montant) {
      return `La somme des tranches (${fcfa(somme)}) doit être égale au montant (${fcfa(d.montant)}).`;
    }
  }
  return null;
}

/**
 * Frais de l'année (montant, tranches, à qui ils s'appliquent), organismes qui financent les
 * bourses et paramètres de la scolarité (taux par défaut, délai entre deux relances).
 */
@Component({
  selector: 'app-frais',
  imports: [FormsModule, ScoNavComponent],
  templateUrl: './frais.page.html',
  styleUrl: './frais.page.scss',
})
export class FraisPage {
  private readonly admin = inject(AdminApi);
  private readonly api = inject(ScolariteApi);
  private readonly session = inject(SessionService);
  protected readonly annee = inject(AnneeCourante);

  protected readonly fcfa = fcfa;
  protected readonly dateCourte = dateCourte;
  protected readonly libellePortee = LIBELLE_PORTEE;
  protected readonly libelleTypeOrganisme = LIBELLE_TYPE_ORGANISME;
  protected readonly portees: Portee[] = ['TOUTES', 'FILIERES', 'NIVEAUX', 'CLASSES'];
  protected readonly typesOrganisme: TypeOrganisme[] = ['ETAT', 'COLLECTIVITE', 'ONG', 'ENTREPRISE'];

  protected readonly action = new Action();
  protected readonly enregistrement = new Action();
  protected readonly gestion = computed(() => this.session.aLeRole(...GESTION_SCOLARITE));
  protected readonly directeur = computed(() => this.session.aLeRole('ADMIN_ECOLE'));
  protected readonly anneeClose = computed(() => ['CLOTUREE', 'ARCHIVEE'].includes(this.annee.annee()?.etat ?? ''));

  protected readonly frais = signal<FraisVue[]>([]);
  protected readonly filieres = signal<FiliereVue[]>([]);
  protected readonly classes = signal<ClasseVue[]>([]);
  protected readonly organismes = signal<OrganismeVue[]>([]);
  protected readonly parametres = signal<ParametresScolarite | null>(null);
  protected readonly message = signal<string | null>(null);

  protected readonly niveaux = computed(() => [...new Set(this.classes().map((c) => c.niveau))].sort());
  protected readonly totalObligatoire = computed(() =>
    this.frais().filter((f) => f.obligatoire && f.portee === 'TOUTES').reduce((t, f) => t + f.montant, 0),
  );

  // ---------- Formulaire d'un frais
  protected readonly ouvert = signal(false);
  protected readonly edite = signal<FraisVue | null>(null);
  protected readonly libelle = signal('');
  protected readonly montant = signal('');
  protected readonly obligatoire = signal(true);
  protected readonly couvertParBourse = signal(true);
  protected readonly portee = signal<Portee>('TOUTES');
  protected readonly cibles = signal<string[]>([]);
  protected readonly tranches = signal<TrancheSaisie[]>([]);

  protected readonly sommeTranches = computed(() => this.tranches().reduce((t, x) => t + (lireMontant(x.montant) ?? 0), 0));
  protected readonly erreurSaisie = computed(() =>
    erreurFrais({
      libelle: this.libelle(),
      montant: lireMontant(this.montant()),
      portee: this.portee(),
      cibles: this.cibles().length,
      tranches: this.tranches().map((t) => ({ dateLimite: t.dateLimite, montant: lireMontant(t.montant) })),
    }),
  );

  // ---------- Organisme
  protected readonly orgOuvert = signal(false);
  protected readonly orgNom = signal('');
  protected readonly orgType = signal<TypeOrganisme>('ETAT');
  protected readonly orgTelephone = signal('');

  // ---------- Paramètres
  protected readonly parOuverts = signal(false);
  protected readonly parBoursier = signal('');
  protected readonly parSemi = signal('');
  protected readonly parDelai = signal('');
  protected readonly parAuto = signal(false);

  constructor() {
    effect(() => {
      const a = this.annee.annee();
      untracked(() => {
        if (a) {
          void this.charger(a.id);
        }
      });
    });
    void this.annee.charger().catch(() => undefined);
  }

  private async charger(anneeId: string): Promise<void> {
    await this.action.executer(async () => {
      const [frais, filieres, classes, organismes, parametres] = await Promise.all([
        this.api.frais(anneeId),
        this.filieres().length ? Promise.resolve(this.filieres()) : this.admin.filieres(),
        this.admin.classes(anneeId),
        this.api.organismes(),
        this.api.parametres(),
      ]);
      if (anneeId === this.annee.annee()?.id) {
        this.frais.set([...frais].sort((a, b) => Number(b.obligatoire) - Number(a.obligatoire) || a.libelle.localeCompare(b.libelle)));
        this.filieres.set(filieres);
        this.classes.set([...classes].sort((a, b) => a.code.localeCompare(b.code)));
        this.organismes.set([...organismes].sort((a, b) => a.nom.localeCompare(b.nom)));
        this.parametres.set(parametres);
      }
    });
  }

  /** « 2nde, 1re » ou « F3, F4 » ou « 2nde F3 A, … » selon la portée. */
  protected cibleTexte(f: FraisVue): string {
    switch (f.portee) {
      case 'TOUTES':
        return 'Tous les élèves';
      case 'NIVEAUX':
        return f.niveaux.join(', ');
      case 'FILIERES':
        return f.filieres.map((id) => this.filieres().find((x) => x.id === id)?.code ?? '?').join(', ');
      case 'CLASSES':
        return f.classes.map((id) => this.classes().find((x) => x.id === id)?.code ?? '?').join(', ');
    }
  }

  // ---------- Frais

  protected nouveau(): void {
    this.edite.set(null);
    this.libelle.set('');
    this.montant.set('');
    this.obligatoire.set(true);
    this.couvertParBourse.set(true);
    this.portee.set('TOUTES');
    this.cibles.set([]);
    this.tranches.set([]);
    this.message.set(null);
    this.enregistrement.erreur.set(null);
    this.ouvert.set(true);
  }

  protected modifier(f: FraisVue): void {
    this.edite.set(f);
    this.libelle.set(f.libelle);
    this.montant.set(String(f.montant));
    this.obligatoire.set(f.obligatoire);
    this.couvertParBourse.set(f.couvertParBourse);
    this.portee.set(f.portee);
    this.cibles.set(f.portee === 'FILIERES' ? [...f.filieres] : f.portee === 'NIVEAUX' ? [...f.niveaux] : f.portee === 'CLASSES' ? [...f.classes] : []);
    this.tranches.set(f.tranches.map((t) => ({ dateLimite: t.dateLimite, montant: String(t.montant) })));
    this.message.set(null);
    this.enregistrement.erreur.set(null);
    this.ouvert.set(true);
  }

  protected changerPortee(p: Portee): void {
    this.portee.set(p);
    this.cibles.set([]);
  }

  protected basculerCible(valeur: string): void {
    const l = this.cibles();
    this.cibles.set(l.includes(valeur) ? l.filter((x) => x !== valeur) : [...l, valeur]);
  }

  protected ajouterTranche(): void {
    this.tranches.set([...this.tranches(), { dateLimite: '', montant: '' }]);
  }

  protected retirerTranche(i: number): void {
    this.tranches.set(this.tranches().filter((_, j) => j !== i));
  }

  protected modifierTranche(i: number, champ: keyof TrancheSaisie, valeur: string): void {
    this.tranches.set(this.tranches().map((t, j) => (j === i ? { ...t, [champ]: valeur } : t)));
  }

  /** Répartit le montant à parts égales sur les tranches déjà créées. */
  protected repartirTranches(): void {
    const montant = lireMontant(this.montant());
    const n = this.tranches().length;
    if (montant === null || n === 0) {
      return;
    }
    const parts = repartir(montant, n);
    this.tranches.set(this.tranches().map((t, i) => ({ ...t, montant: String(parts[i]) })));
  }

  protected async enregistrer(): Promise<void> {
    const a = this.annee.annee();
    const montant = lireMontant(this.montant());
    if (!a || this.erreurSaisie() || montant === null) {
      return;
    }
    const p = this.portee();
    const d: DonneesFrais = {
      libelle: this.libelle().trim(),
      montant,
      obligatoire: this.obligatoire(),
      couvertParBourse: this.couvertParBourse(),
      portee: p,
      filieres: p === 'FILIERES' ? this.cibles() : [],
      niveaux: p === 'NIVEAUX' ? this.cibles() : [],
      classes: p === 'CLASSES' ? this.cibles() : [],
      tranches: this.tranches().map((t) => ({ dateLimite: t.dateLimite, montant: lireMontant(t.montant) ?? 0 })),
    };
    const edite = this.edite();
    const f = await this.enregistrement.executer(() => (edite ? this.api.modifierFrais(edite.id, d) : this.api.creerFrais(a.id, d)));
    if (f) {
      this.ouvert.set(false);
      this.message.set(`${f.libelle} : ${fcfa(f.montant)} ${edite ? 'modifié' : 'ajouté'}. Les échéanciers des élèves sont recalculés.`);
      await this.charger(a.id);
    }
  }

  protected async supprimer(f: FraisVue): Promise<void> {
    const a = this.annee.annee();
    if (!a || !window.confirm(`Supprimer le frais « ${f.libelle} » ?`)) {
      return;
    }
    if (await this.enregistrement.reussit(() => this.api.supprimerFrais(f.id))) {
      this.message.set(`${f.libelle} supprimé.`);
      await this.charger(a.id);
    }
  }

  // ---------- Organismes

  protected async ajouterOrganisme(): Promise<void> {
    const nom = this.orgNom().trim();
    if (!nom) {
      return;
    }
    const o = await this.enregistrement.executer(() =>
      this.api.creerOrganisme({ nom, type: this.orgType(), telephone: this.orgTelephone().trim() || null }),
    );
    if (o) {
      this.organismes.set([...this.organismes(), o].sort((a, b) => a.nom.localeCompare(b.nom)));
      this.orgNom.set('');
      this.orgTelephone.set('');
      this.orgOuvert.set(false);
    }
  }

  protected async basculerOrganisme(o: OrganismeVue): Promise<void> {
    const m = await this.enregistrement.executer(() =>
      this.api.modifierOrganisme(o.id, { nom: o.nom, type: o.type, telephone: o.telephone, actif: !o.actif }),
    );
    if (m) {
      this.organismes.set(this.organismes().map((x) => (x.id === m.id ? m : x)));
    }
  }

  // ---------- Paramètres

  protected ouvrirParametres(): void {
    const p = this.parametres();
    this.parBoursier.set(String(p?.tauxBoursier ?? 100));
    this.parSemi.set(String(p?.tauxSemiBoursier ?? 50));
    this.parDelai.set(String(p?.delaiRelanceJours ?? 7));
    this.parAuto.set(!!p?.relancesAutomatiques);
    this.parOuverts.set(true);
  }

  protected async enregistrerParametres(): Promise<void> {
    const b = Number(this.parBoursier().replace(',', '.'));
    const s = Number(this.parSemi().replace(',', '.'));
    const delai = Number(this.parDelai());
    if ([b, s].some((t) => Number.isNaN(t) || t < 0 || t > 100) || !Number.isInteger(delai) || delai < 1) {
      this.enregistrement.erreur.set('Taux entre 0 et 100 ; délai en jours (1 au moins).');
      return;
    }
    const p = await this.enregistrement.executer(() =>
      this.api.modifierParametres({ tauxBoursier: b, tauxSemiBoursier: s, delaiRelanceJours: delai, relancesAutomatiques: this.parAuto() }),
    );
    if (p) {
      this.parametres.set(p);
      this.parOuverts.set(false);
      this.message.set('Paramètres enregistrés.');
    }
  }
}
