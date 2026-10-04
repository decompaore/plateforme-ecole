import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { FiliereVue } from '../../admin/modeles-admin';
import { dateCourte, dateHeureCourte, dateLocale } from '../../core/outils';
import { fcfa, lireMontant } from '../../scolarite/modeles-scolarite';
import { AteliersNavComponent } from '../ateliers-nav.component';
import { AteliersApi } from '../ateliers-api.service';
import {
  alertesEnTexte,
  ArticleVue,
  AtelierVue,
  CandidatVue,
  CLASSE_ETAT,
  EquipementVue,
  EtatEquipement,
  InventaireResumeVue,
  LIBELLE_ETAT,
  LIBELLE_MOUVEMENT,
  LigneStockVue,
  lireQuantite,
  MouvementVue,
  quantite,
  StatutPanne,
  TypeMouvement,
} from '../modeles-ateliers';
import { erreurAtelier } from './ateliers.page';

type Onglet = 'responsable' | 'equipements' | 'matiere' | 'inventaires';

/**
 * Fiche d'un atelier : responsable et historique des mandats, équipements et pannes, stock de
 * matière d'œuvre, inventaires. Ce qui est permis dépend des droits renvoyés par le serveur
 * (direction, responsable, enseignant de l'atelier, intendance en lecture).
 */
@Component({
  selector: 'app-atelier',
  imports: [FormsModule, RouterLink, AteliersNavComponent],
  templateUrl: './atelier.page.html',
  styleUrl: './atelier.page.scss',
})
export class AtelierPage {
  private readonly api = inject(AteliersApi);
  private readonly admin = inject(AdminApi);
  private readonly router = inject(Router);

  readonly atelierId = input.required<string>();

  protected readonly dateCourte = dateCourte;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly quantite = quantite;
  protected readonly fcfa = fcfa;
  protected readonly libelleEtat = LIBELLE_ETAT;
  protected readonly classeEtat = CLASSE_ETAT;
  protected readonly libelleMouvement = LIBELLE_MOUVEMENT;
  protected readonly aujourdhui = dateLocale();

  protected readonly action = new Action();
  protected readonly enregistrement = new Action();
  protected readonly message = signal<string | null>(null);

  protected readonly atelier = signal<AtelierVue | null>(null);
  protected readonly onglet = signal<Onglet>('responsable');
  protected readonly alertes = computed(() => {
    const a = this.atelier();
    return a ? alertesEnTexte(a.alertes) : [];
  });
  protected readonly gerer = computed(() => !!this.atelier()?.droits.gerer);
  protected readonly tenir = computed(() => {
    const d = this.atelier()?.droits;
    return !!d && (d.gerer || d.responsable);
  });

  // ---------------- modification de l'atelier (direction)
  protected readonly edition = signal(false);
  protected readonly filieres = signal<FiliereVue[]>([]);
  protected readonly edCode = signal('');
  protected readonly edNom = signal('');
  protected readonly edLieu = signal('');
  protected readonly edPostes = signal('');
  protected readonly edOuvert = signal(true);
  protected readonly edObservations = signal('');
  protected readonly edFilieres = signal<string[]>([]);
  protected readonly erreurEdition = computed(() =>
    erreurAtelier({ code: this.edCode(), nom: this.edNom(), postes: this.edPostes(), filieres: this.edFilieres() }),
  );

  // ---------------- responsable
  protected readonly candidats = signal<CandidatVue[] | null>(null);
  protected readonly designation = signal(false);
  protected readonly candidat = signal('');
  protected readonly debut = signal(dateLocale());
  protected readonly finPrevue = signal('');
  protected readonly finMandat = signal(false);
  protected readonly dateFin = signal(dateLocale());
  protected readonly motifFin = signal('');

  // ---------------- équipements
  protected readonly equipements = signal<EquipementVue[] | null>(null);
  protected readonly catalogue = signal<ArticleVue[]>([]);
  protected readonly articlesEquipement = computed(() => this.catalogue().filter((a) => a.nature === 'EQUIPEMENT' && a.actif));
  protected readonly articlesMatiere = computed(() => this.catalogue().filter((a) => a.nature === 'MATIERE_OEUVRE' && a.actif));
  protected readonly ajoutEquipement = signal(false);
  protected readonly eqArticle = signal('');
  protected readonly eqDesignation = signal('');
  protected readonly eqNumero = signal('');
  protected readonly eqMarque = signal('');
  protected readonly eqSerie = signal('');
  protected readonly eqDate = signal('');
  protected readonly eqValeur = signal('');
  protected readonly panneOuverte = signal<string | null>(null);
  protected readonly panneTexte = signal('');
  protected readonly clotureOuverte = signal<string | null>(null);
  protected readonly clotureStatut = signal<StatutPanne>('REPAREE');
  protected readonly clotureIntervention = signal('');
  protected readonly clotureCout = signal('');
  protected readonly filtreEquipements = signal<'service' | 'tous'>('service');
  protected readonly equipementsAffiches = computed(() => {
    const l = this.equipements() ?? [];
    return this.filtreEquipements() === 'tous' ? l : l.filter((e) => e.etat !== 'REFORME');
  });

  // ---------------- matière d'œuvre
  protected readonly stock = signal<LigneStockVue[] | null>(null);
  protected readonly mouvements = signal<MouvementVue[]>([]);
  protected readonly mvOuvert = signal(false);
  protected readonly mvArticle = signal('');
  protected readonly mvType = signal<TypeMouvement>('ENTREE');
  protected readonly mvQuantite = signal('');
  protected readonly mvDate = signal(dateLocale());
  protected readonly mvMotif = signal('');
  protected readonly seuilOuvert = signal<string | null>(null);
  protected readonly seuilTexte = signal('');
  protected readonly valeurStock = computed(() =>
    (this.stock() ?? []).reduce((t, l) => t + (l.prixReference ?? 0) * l.quantite, 0),
  );
  protected readonly uniteChoisie = computed(() => this.catalogue().find((a) => a.id === this.mvArticle())?.unite ?? '');
  protected readonly erreurMouvement = computed(() => {
    if (!this.mvArticle()) {
      return 'Choisissez la matière d’œuvre.';
    }
    if (lireQuantite(this.mvQuantite()) === null) {
      return 'Quantité positive, deux décimales au plus.';
    }
    if (this.mvDate() > this.aujourdhui) {
      return 'La date ne peut pas être dans le futur.';
    }
    return null;
  });

  // ---------------- inventaires
  protected readonly inventaires = signal<InventaireResumeVue[] | null>(null);
  protected readonly libelleInventaire = signal('');
  protected readonly inventaireEnCours = computed(() => (this.inventaires() ?? []).find((i) => i.statut === 'EN_COURS') ?? null);

  constructor() {
    effect(() => {
      const id = this.atelierId();
      untracked(() => void this.charger(id));
    });
  }

  protected filieresTexte(a: AtelierVue): string {
    return a.filieres.length ? a.filieres.map((f) => `${f.code} ${f.libelle}`).join(' · ') : 'Aucune filière';
  }

  protected detailsEquipement(e: EquipementVue): string {
    return [
      e.marque,
      e.numeroSerie ? `série ${e.numeroSerie}` : null,
      e.dateAcquisition ? `acquis le ${dateCourte(e.dateAcquisition)}` : null,
      e.valeur !== null ? fcfa(e.valeur) : null,
    ].filter((x) => !!x).join(' · ');
  }

  protected choisirOnglet(o: Onglet): void {
    this.onglet.set(o);
    this.message.set(null);
    this.enregistrement.erreur.set(null);
    void this.chargerOnglet(o);
  }

  // ---------------- atelier

  protected async ouvrirEdition(): Promise<void> {
    const a = this.atelier();
    if (!a) {
      return;
    }
    this.edCode.set(a.code);
    this.edNom.set(a.nom);
    this.edLieu.set(a.emplacement ?? '');
    this.edPostes.set(a.postes ? String(a.postes) : '');
    this.edOuvert.set(a.ouvert);
    this.edObservations.set(a.observations ?? '');
    this.edFilieres.set(a.filieres.map((f) => f.id));
    this.edition.set(true);
    if (!this.filieres().length) {
      this.filieres.set((await this.admin.filieres().catch(() => null)) ?? []);
    }
  }

  protected basculerFiliere(id: string): void {
    const c = this.edFilieres();
    this.edFilieres.set(c.includes(id) ? c.filter((x) => x !== id) : [...c, id]);
  }

  protected async enregistrerAtelier(): Promise<void> {
    if (this.erreurEdition()) {
      return;
    }
    const a = await this.enregistrement.executer(() =>
      this.api.modifierAtelier(this.atelierId(), {
        code: this.edCode().trim(),
        nom: this.edNom().trim(),
        emplacement: this.edLieu().trim() || null,
        postes: this.edPostes().trim() ? Number(this.edPostes()) : null,
        ouvert: this.edOuvert(),
        observations: this.edObservations().trim() || null,
        filieres: this.edFilieres(),
      }),
    );
    if (a) {
      this.atelier.set(a);
      this.edition.set(false);
      this.message.set('Atelier enregistré.');
    }
  }

  // ---------------- responsable

  protected async ouvrirDesignation(): Promise<void> {
    this.designation.set(true);
    this.candidat.set('');
    this.debut.set(dateLocale());
    this.finPrevue.set('');
    if (this.candidats() === null) {
      this.candidats.set((await this.enregistrement.executer(() => this.api.candidats(this.atelierId()))) ?? []);
    }
  }

  protected async designer(): Promise<void> {
    if (!this.candidat()) {
      return;
    }
    const a = await this.enregistrement.executer(() =>
      this.api.designer(this.atelierId(), this.candidat(), this.debut() || null, this.finPrevue() || null),
    );
    if (a) {
      this.atelier.set(a);
      this.designation.set(false);
      this.message.set(`${a.responsable?.enseignant ?? 'Le responsable'} est désigné responsable de l'atelier.`);
    }
  }

  protected async terminerMandat(): Promise<void> {
    const a = await this.enregistrement.executer(() =>
      this.api.terminerMandat(this.atelierId(), this.dateFin() || null, this.motifFin().trim() || null),
    );
    if (a) {
      this.atelier.set(a);
      this.finMandat.set(false);
      this.message.set('Mandat terminé : l’atelier attend un nouveau responsable.');
    }
  }

  // ---------------- équipements

  protected ouvrirAjoutEquipement(): void {
    this.eqArticle.set('');
    this.eqDesignation.set('');
    this.eqNumero.set('');
    this.eqMarque.set('');
    this.eqSerie.set('');
    this.eqDate.set('');
    this.eqValeur.set('');
    this.ajoutEquipement.set(true);
  }

  protected readonly erreurEquipement = computed(() => {
    if (!this.eqArticle() && !this.eqDesignation().trim()) {
      return 'Choisissez un article du catalogue ou donnez une désignation.';
    }
    if (this.eqValeur().trim() && lireMontant(this.eqValeur()) === null) {
      return 'Valeur en francs CFA, sans centimes.';
    }
    if (this.eqDate() && this.eqDate() > this.aujourdhui) {
      return 'La date d’acquisition ne peut pas être dans le futur.';
    }
    return null;
  });

  protected async ajouterEquipement(): Promise<void> {
    if (this.erreurEquipement()) {
      return;
    }
    const e = await this.enregistrement.executer(() =>
      this.api.creerEquipement(this.atelierId(), {
        articleId: this.eqArticle() || null,
        designation: this.eqDesignation().trim() || null,
        numeroInventaire: this.eqNumero().trim() || null,
        marque: this.eqMarque().trim() || null,
        numeroSerie: this.eqSerie().trim() || null,
        dateAcquisition: this.eqDate() || null,
        valeur: this.eqValeur().trim() ? lireMontant(this.eqValeur()) : null,
        observations: null,
      }),
    );
    if (e) {
      this.equipements.set([...(this.equipements() ?? []), e].sort((a, b) => a.designation.localeCompare(b.designation)));
      this.ajoutEquipement.set(false);
      this.message.set(`Équipement ajouté sous le numéro ${e.numeroInventaire}.`);
      void this.rafraichirAtelier();
    }
  }

  protected ouvrirPanne(e: EquipementVue): void {
    this.panneOuverte.set(e.id);
    this.panneTexte.set('');
    this.clotureOuverte.set(null);
  }

  protected async signalerPanne(e: EquipementVue): Promise<void> {
    if (!this.panneTexte().trim()) {
      return;
    }
    const p = await this.enregistrement.executer(() => this.api.signalerPanne(e.id, this.panneTexte().trim()));
    if (p) {
      this.remplacerEquipement({ ...e, etat: 'EN_PANNE', panneOuverte: p });
      this.panneOuverte.set(null);
      this.message.set(`Panne signalée : ${e.designation} (${e.numeroInventaire}).`);
      void this.rafraichirAtelier();
    }
  }

  protected ouvrirCloture(e: EquipementVue): void {
    this.clotureOuverte.set(e.id);
    this.clotureStatut.set('REPAREE');
    this.clotureIntervention.set('');
    this.clotureCout.set('');
    this.panneOuverte.set(null);
  }

  protected async cloturerPanne(e: EquipementVue): Promise<void> {
    const panne = e.panneOuverte;
    if (!panne || (this.clotureCout().trim() && lireMontant(this.clotureCout()) === null)) {
      return;
    }
    const p = await this.enregistrement.executer(() =>
      this.api.cloturerPanne(panne.id, this.clotureStatut(), this.clotureIntervention().trim() || null,
        this.clotureCout().trim() ? lireMontant(this.clotureCout()) : null),
    );
    if (p) {
      this.remplacerEquipement({ ...e, etat: p.statut === 'REPAREE' ? 'BON' : 'REFORME', panneOuverte: null });
      this.clotureOuverte.set(null);
      this.message.set(p.statut === 'REPAREE' ? 'Équipement remis en service.' : 'Équipement irréparable : il est réformé.');
      void this.rafraichirAtelier();
    }
  }

  protected async changerEtat(e: EquipementVue, etat: EtatEquipement): Promise<void> {
    const r = await this.enregistrement.executer(() =>
      this.api.modifierEquipement(e.id, {
        articleId: e.articleId,
        designation: e.designation,
        numeroInventaire: e.numeroInventaire,
        marque: e.marque,
        numeroSerie: e.numeroSerie,
        dateAcquisition: e.dateAcquisition,
        valeur: e.valeur,
        observations: e.observations,
        etat,
      }),
    );
    if (r) {
      this.remplacerEquipement(r);
      this.message.set(`${e.designation} : ${LIBELLE_ETAT[etat].toLowerCase()}.`);
      void this.rafraichirAtelier();
    }
  }

  private remplacerEquipement(e: EquipementVue): void {
    this.equipements.set((this.equipements() ?? []).map((x) => (x.id === e.id ? e : x)));
  }

  // ---------------- matière d'œuvre

  protected ouvrirMouvement(type: TypeMouvement, articleId = ''): void {
    this.mvType.set(type);
    this.mvArticle.set(articleId);
    this.mvQuantite.set('');
    this.mvDate.set(dateLocale());
    this.mvMotif.set('');
    this.mvOuvert.set(true);
  }

  protected async enregistrerMouvement(): Promise<void> {
    const q = lireQuantite(this.mvQuantite());
    if (this.erreurMouvement() || q === null) {
      return;
    }
    const m = await this.enregistrement.executer(() =>
      this.api.mouvement(this.atelierId(), {
        articleId: this.mvArticle(),
        type: this.mvType(),
        quantite: q,
        date: this.mvDate() || null,
        motif: this.mvMotif().trim() || null,
      }),
    );
    if (m) {
      this.mvOuvert.set(false);
      this.message.set(`${LIBELLE_MOUVEMENT[m.type]} enregistrée : ${m.article}, stock ${quantite(m.stockApres)} ${m.unite ?? ''}.`);
      await this.chargerOnglet('matiere', true);
      void this.rafraichirAtelier();
    }
  }

  protected ouvrirSeuil(l: LigneStockVue): void {
    this.seuilOuvert.set(l.articleId);
    this.seuilTexte.set(l.seuilAlerte === null ? '' : String(l.seuilAlerte).replace('.', ','));
  }

  protected async enregistrerSeuil(l: LigneStockVue): Promise<void> {
    const texte = this.seuilTexte().trim();
    const seuil = texte ? lireQuantite(texte, true) : null;
    if (texte && seuil === null) {
      this.enregistrement.erreur.set('Seuil : un nombre positif, deux décimales au plus.');
      return;
    }
    const r = await this.enregistrement.executer(() => this.api.seuil(this.atelierId(), l.articleId, seuil));
    if (r) {
      this.stock.set((this.stock() ?? []).map((x) => (x.articleId === r.articleId ? r : x)));
      this.seuilOuvert.set(null);
      void this.rafraichirAtelier();
    }
  }

  // ---------------- inventaires

  protected async ouvrirInventaire(): Promise<void> {
    const i = await this.enregistrement.executer(() =>
      this.api.ouvrirInventaire(this.atelierId(), this.libelleInventaire().trim() || null),
    );
    if (i) {
      void this.router.navigate(['/ateliers/inventaires', i.id]);
    }
  }

  // ---------------- chargement

  private async charger(id: string): Promise<void> {
    const a = await this.action.executer(() => this.api.atelier(id));
    if (a && id === this.atelierId()) {
      this.atelier.set(a);
      if (!a.responsable && a.droits.gerer) {
        this.onglet.set('responsable');
      }
      void this.chargerOnglet(this.onglet());
    }
  }

  private async rafraichirAtelier(): Promise<void> {
    const a = await this.api.atelier(this.atelierId()).catch(() => null);
    if (a) {
      this.atelier.set(a);
    }
  }

  private async chargerOnglet(o: Onglet, forcer = false): Promise<void> {
    const id = this.atelierId();
    if (o === 'equipements' && (forcer || this.equipements() === null)) {
      const [eq, cat] = await Promise.all([
        this.action.executer(() => this.api.equipements(id)),
        this.tenir() && !this.catalogue().length ? this.api.catalogue().catch(() => []) : Promise.resolve(this.catalogue()),
      ]);
      this.equipements.set(eq ?? []);
      this.catalogue.set(cat);
    }
    if (o === 'matiere' && (forcer || this.stock() === null)) {
      const [st, mv, cat] = await Promise.all([
        this.action.executer(() => this.api.stock(id)),
        this.api.mouvements(id).catch(() => []),
        this.tenir() && !this.catalogue().length ? this.api.catalogue().catch(() => []) : Promise.resolve(this.catalogue()),
      ]);
      this.stock.set(st ?? []);
      this.mouvements.set(mv);
      this.catalogue.set(cat);
    }
    if (o === 'inventaires' && (forcer || this.inventaires() === null)) {
      this.inventaires.set((await this.action.executer(() => this.api.inventaires(id))) ?? []);
    }
  }
}
