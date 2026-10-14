import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte, dateHeureCourte, dateLocale } from '../../core/outils';
import { AteliersApi } from '../ateliers-api.service';
import { AteliersNavComponent } from '../ateliers-nav.component';
import { ArticleVue, AtelierVue, EquipementVue } from '../modeles-ateliers';
import {
  ajouterJours,
  CATEGORIES_CONSIGNE,
  CategorieConsigne,
  CLASSE_STATUT_TP,
  ConsignesVue,
  EvenementVue,
  Gravite,
  heure,
  LectureVue,
  LIBELLE_CONSIGNE,
  LIBELLE_GRAVITE,
  LIBELLE_STATUT_PRODUCTION,
  LIBELLE_STATUT_TP,
  LIBELLE_TYPE_CLIENT,
  lundi,
  OptionSeance,
  ProductionVue,
  SeanceTpVue,
  StatutProduction,
  TypeClient,
} from '../modeles-tp';
import { TravauxPratiquesApi } from '../travaux-pratiques-api.service';

type Onglet = 'planning' | 'securite' | 'production';
type Saisie = 'valider' | 'refuser' | 'preparer' | 'compte-rendu' | 'annuler';

interface LigneSaisie {
  articleId: string;
  quantite: number | null;
}

/**
 * Travaux pratiques d'un atelier (v0.47.2) : planning de la semaine (demandes des enseignants, validation et
 * préparation par le chef d'atelier, compte rendu, bilan), consignes de sécurité et journal HSE, commandes de
 * production.
 */
@Component({
  selector: 'app-travaux-pratiques',
  imports: [FormsModule, RouterLink, AteliersNavComponent],
  templateUrl: './travaux-pratiques.page.html',
  styles: `
    .semaine {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      flex-wrap: wrap;
      margin-bottom: 0.75rem;
    }
    .jour h3 {
      font-size: 1rem;
      margin: 1rem 0 0.5rem;
      text-transform: capitalize;
    }
    .seance {
      border: 1px solid var(--bordure);
      border-radius: var(--rayon);
      padding: 0.75rem;
      margin-bottom: 0.75rem;
    }
    .seance.demandee {
      border-left: 4px solid var(--attention, #d99a00);
    }
    .seance .tete {
      display: flex;
      justify-content: space-between;
      gap: 0.5rem;
      flex-wrap: wrap;
    }
    .seance p {
      margin: 0.25rem 0;
    }
    .besoins {
      margin-top: 0.5rem;
      font-size: 0.9rem;
    }
    .besoins input {
      width: 6rem;
    }
    .rouge {
      color: var(--absent);
    }
    .case {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      margin: 0.25rem 0;
    }
    .case input {
      width: auto;
      min-height: 0;
    }
    .ligne-besoin {
      display: flex;
      gap: 0.5rem;
      align-items: center;
      margin: 0.25rem 0;
    }
    .ligne-besoin select {
      flex: 1;
    }
    .ligne-besoin input {
      width: 7rem;
    }
    .consigne {
      display: grid;
      grid-template-columns: minmax(10rem, 14rem) minmax(8rem, 12rem) 1fr auto;
      gap: 0.5rem;
      align-items: center;
      margin: 0.35rem 0;
    }
    @media (max-width: 700px) {
      .consigne {
        grid-template-columns: 1fr;
      }
    }
    .progression {
      height: 0.5rem;
      background: var(--bordure);
      border-radius: 1rem;
      overflow: hidden;
      margin-top: 0.3rem;
      div {
        height: 100%;
        background: var(--primaire);
      }
    }
  `,
})
export class TravauxPratiquesPage implements OnInit {
  readonly atelierId = input.required<string>();
  /** Semaine à afficher (?semaine=AAAA-MM-JJ, depuis « Mes TP » ou le planning général). */
  readonly semaine = input<string | undefined>();

  private readonly api = inject(TravauxPratiquesApi);
  private readonly ateliersApi = inject(AteliersApi);

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly onglet = signal<Onglet>('planning');
  protected readonly atelier = signal<AtelierVue | null>(null);
  protected readonly debut = signal(lundi(dateLocale()));
  protected readonly seances = signal<SeanceTpVue[]>([]);
  protected readonly options = signal<OptionSeance[]>([]);
  protected readonly articles = signal<ArticleVue[]>([]);
  protected readonly equipements = signal<EquipementVue[]>([]);
  protected readonly consignes = signal<ConsignesVue | null>(null);
  protected readonly lectures = signal<LectureVue[] | null>(null);
  protected readonly journal = signal<EvenementVue[]>([]);
  protected readonly productions = signal<ProductionVue[]>([]);

  protected readonly libelleStatut = LIBELLE_STATUT_TP;
  protected readonly classeStatut = CLASSE_STATUT_TP;
  protected readonly libelleConsigne = LIBELLE_CONSIGNE;
  protected readonly categories = CATEGORIES_CONSIGNE;
  protected readonly libelleGravite = LIBELLE_GRAVITE;
  protected readonly gravites: Gravite[] = ['OBSERVATION', 'DANGER_IMMINENT', 'ACCIDENT'];
  protected readonly libelleTypeClient = LIBELLE_TYPE_CLIENT;
  protected readonly typesClient: TypeClient[] = ['PUBLIC', 'PRIVE', 'INTERNE'];
  protected readonly libelleProduction = LIBELLE_STATUT_PRODUCTION;
  protected readonly heure = heure;
  protected readonly dateCourte = dateCourte;
  protected readonly dateHeureCourte = dateHeureCourte;

  protected readonly chef = computed(() => !!this.atelier()?.droits.gerer || !!this.atelier()?.droits.responsable);
  protected readonly fin = computed(() => ajouterJours(this.debut(), 6));
  protected readonly matieres = computed(() => this.articles().filter((a) => a.nature === 'MATIERE_OEUVRE' && a.actif));
  protected readonly productionsEnCours = computed(() => this.productions().filter((p) => p.statut === 'EN_COURS'));
  protected readonly jours = computed(() => {
    const parJour = new Map<string, SeanceTpVue[]>();
    for (let i = 0; i < 7; i++) {
      parJour.set(ajouterJours(this.debut(), i), []);
    }
    for (const s of this.seances()) {
      parJour.get(s.date)?.push(s);
    }
    return [...parJour.entries()].filter(([, l]) => l.length).map(([date, liste]) => ({ date, liste }));
  });
  protected readonly aValider = computed(() => this.seances().filter((s) => s.droits.valider).length);

  // Saisie en cours sur une séance
  protected readonly saisie = signal<{ id: string; type: Saisie } | null>(null);
  protected readonly quantites = signal<Record<string, number | null>>({});
  protected readonly motif = signal('');
  protected readonly crNettoyage = signal(true);
  protected readonly crRangement = signal(true);
  protected readonly crEpi = signal(true);
  protected readonly crProduite = signal<number | null>(null);
  protected readonly crObservations = signal('');
  protected readonly crPanneEquipement = signal('');
  protected readonly crPanneDescription = signal('');

  // Nouvelle séance
  protected readonly formSeance = signal(false);
  protected readonly nOption = signal('');
  protected readonly nDate = signal(dateLocale());
  protected readonly nDebut = signal('08:00');
  protected readonly nFin = signal('10:00');
  protected readonly nGroupe = signal('');
  protected readonly nTitre = signal('');
  protected readonly nEffectif = signal<number | null>(null);
  protected readonly nProduction = signal('');
  protected readonly nBesoins = signal<LigneSaisie[]>([]);

  // Consignes en édition
  protected readonly edition = signal<{ categorie: CategorieConsigne; equipementId: string; texte: string }[] | null>(null);
  // Événement HSE
  protected readonly hGravite = signal<Gravite>('OBSERVATION');
  protected readonly hSeance = signal('');
  protected readonly hDescription = signal('');
  protected readonly hMesures = signal('');
  // Production
  protected readonly formProduction = signal(false);
  protected readonly pClient = signal('');
  protected readonly pType = signal<TypeClient>('PUBLIC');
  protected readonly pObjet = signal('');
  protected readonly pQuantite = signal<number | null>(null);
  protected readonly pUnite = signal('pièce');
  protected readonly pMontant = signal<number | null>(null);
  protected readonly pEcheance = signal('');

  async ngOnInit(): Promise<void> {
    const s = this.semaine();
    if (s) {
      this.debut.set(lundi(s));
    }
    await this.action.executer(async () => {
      const id = this.atelierId();
      const [atelier, consignes, options, articles, productions] = await Promise.all([
        this.ateliersApi.atelier(id),
        this.api.consignes(id),
        this.api.options(id),
        this.ateliersApi.catalogue(),
        this.api.productions(id),
      ]);
      this.atelier.set(atelier);
      this.consignes.set(consignes);
      this.options.set(options);
      this.articles.set(articles);
      this.productions.set(productions);
      this.nOption.set(options[0] ? cle(options[0]) : '');
      await this.chargerSemaine();
    });
  }

  // ---------------------------------------------------------------- planning

  protected async changerSemaine(decalage: number): Promise<void> {
    this.debut.set(ajouterJours(this.debut(), decalage * 7));
    await this.action.executer(() => this.chargerSemaine());
  }

  private async chargerSemaine(): Promise<void> {
    this.seances.set(await this.api.planning(this.atelierId(), this.debut(), this.fin()));
  }

  protected cle = cle;

  protected ouvrir(s: SeanceTpVue, type: Saisie): void {
    this.message.set(null);
    this.saisie.set({ id: s.id, type });
    this.motif.set('');
    const q: Record<string, number | null> = {};
    for (const b of s.besoins) {
      q[b.articleId] = type === 'compte-rendu' ? (b.servie ?? 0) : (b.accordee ?? b.demandee);
    }
    this.quantites.set(q);
    this.crNettoyage.set(true);
    this.crRangement.set(true);
    this.crEpi.set(true);
    this.crProduite.set(null);
    this.crObservations.set('');
    this.crPanneEquipement.set('');
    this.crPanneDescription.set('');
    if (type === 'compte-rendu' && !this.equipements().length) {
      void this.ateliersApi.equipements(this.atelierId()).then((e) => this.equipements.set(e)).catch(() => undefined);
    }
  }

  protected enSaisie(s: SeanceTpVue, type: Saisie): boolean {
    const x = this.saisie();
    return !!x && x.id === s.id && x.type === type;
  }

  protected changerQuantite(articleId: string, valeur: string): void {
    this.quantites.set({ ...this.quantites(), [articleId]: valeur === '' ? null : Number(valeur) });
  }

  private remplacer(s: SeanceTpVue): void {
    this.seances.set(this.seances().map((x) => (x.id === s.id ? s : x)));
    this.saisie.set(null);
  }

  protected async valider(s: SeanceTpVue): Promise<void> {
    const accordees = Object.entries(this.quantites())
      .filter(([, q]) => q !== null)
      .map(([articleId, q]) => ({ articleId, quantite: q as number }));
    const r = await this.action.executer(() => this.api.valider(s.id, accordees));
    if (r) {
      this.remplacer(r);
      this.message.set(`Séance de ${r.classeCode} planifiée le ${dateCourte(r.date)}.`);
    }
  }

  protected async refuser(s: SeanceTpVue): Promise<void> {
    const r = await this.action.executer(() => this.api.refuser(s.id, this.motif().trim()));
    if (r) {
      this.remplacer(r);
      this.message.set("Demande refusée : l'enseignant est prévenu dans sa messagerie.");
    }
  }

  protected async annuler(s: SeanceTpVue): Promise<void> {
    const r = await this.action.executer(() => this.api.annuler(s.id, this.motif().trim()));
    if (r) {
      this.remplacer(r);
      this.message.set('Séance annulée.');
    }
  }

  protected async preparer(s: SeanceTpVue): Promise<void> {
    const r = await this.action.executer(() => this.api.preparer(s.id, this.motif().trim() || null));
    if (r) {
      this.remplacer(r);
      const manque = r.besoins.filter((b) => (b.aAcheter ?? 0) > 0);
      this.message.set(
        'Séance préparée : matière servie depuis le stock.' +
          (manque.length ? ` À acheter : ${manque.map((b) => `${b.aAcheter} ${b.unite} ${b.designation}`).join(', ')}.` : ''),
      );
    }
  }

  protected async compteRendu(s: SeanceTpVue): Promise<void> {
    const pannes = this.crPanneEquipement() && this.crPanneDescription().trim()
      ? [{ equipementId: this.crPanneEquipement(), description: this.crPanneDescription().trim() }]
      : [];
    const r = await this.action.executer(() =>
      this.api.compteRendu(s.id, {
        nettoyage: this.crNettoyage(),
        rangement: this.crRangement(),
        epiRespectes: this.crEpi(),
        quantiteProduite: s.productionId ? this.crProduite() : null,
        observations: this.crObservations().trim() || null,
        consommation: Object.entries(this.quantites())
          .filter(([, q]) => q !== null)
          .map(([articleId, q]) => ({ articleId, quantite: q as number })),
        pannes,
      }),
    );
    if (r) {
      this.remplacer(r);
      this.message.set('Compte rendu enregistré' + (pannes.length ? ' ; la panne est signalée au chef d’atelier.' : '.'));
    }
  }

  protected async bilan(s: SeanceTpVue): Promise<void> {
    const r = await this.action.executer(() => this.api.bilan(s.id));
    if (r) {
      this.remplacer(r);
      const rendu = r.besoins.filter((b) => (b.rendue ?? 0) > 0);
      this.message.set(
        'Bilan fait.' + (rendu.length ? ` Retour au stock : ${rendu.map((b) => `${b.rendue} ${b.unite} ${b.designation}`).join(', ')}.` : ''),
      );
    }
  }

  protected async generer(): Promise<void> {
    const r = await this.action.executer(() => this.api.generer(this.atelierId(), this.debut(), this.fin()));
    if (r) {
      await this.action.executer(() => this.chargerSemaine());
      this.message.set(
        `${r.creees} séance(s) créée(s) depuis l'emploi du temps` +
          (r.dejaPresentes ? `, ${r.dejaPresentes} déjà présente(s)` : '') +
          (r.sansEnseignant ? `, ${r.sansEnseignant} sans enseignant` : '') +
          '.',
      );
    }
  }

  // ---------------------------------------------------------------- nouvelle séance

  protected ajouterBesoin(): void {
    this.nBesoins.set([...this.nBesoins(), { articleId: this.matieres()[0]?.id ?? '', quantite: null }]);
  }

  protected changerBesoin(i: number, champ: 'articleId' | 'quantite', valeur: string): void {
    this.nBesoins.set(
      this.nBesoins().map((b, j) => (j !== i ? b : champ === 'articleId' ? { ...b, articleId: valeur } : { ...b, quantite: valeur === '' ? null : Number(valeur) })),
    );
  }

  protected retirerBesoin(i: number): void {
    this.nBesoins.set(this.nBesoins().filter((_, j) => j !== i));
  }

  protected async demander(): Promise<void> {
    const o = this.options().find((x) => cle(x) === this.nOption());
    if (!o) {
      return;
    }
    const r = await this.action.executer(() =>
      this.api.creer(this.atelierId(), {
        classeId: o.classeId,
        matiereId: o.matiereId,
        date: this.nDate(),
        heureDebut: this.nDebut(),
        heureFin: this.nFin(),
        groupe: this.nGroupe().trim() || null,
        titre: this.nTitre().trim(),
        effectif: this.nEffectif(),
        productionId: this.nProduction() || null,
        besoins: this.nBesoins()
          .filter((b) => b.articleId && b.quantite)
          .map((b) => ({ articleId: b.articleId, quantite: b.quantite as number })),
      }),
    );
    if (r) {
      this.formSeance.set(false);
      this.nTitre.set('');
      this.nBesoins.set([]);
      this.debut.set(lundi(r.date));
      await this.action.executer(() => this.chargerSemaine());
      this.message.set(
        r.statut === 'DEMANDEE'
          ? `Demande envoyée au chef d'atelier pour le ${dateCourte(r.date)}.` + (r.conflits.length ? ` Attention : ${r.conflits.join(' ; ')}.` : '')
          : `Séance planifiée le ${dateCourte(r.date)}.`,
      );
    }
  }

  // ---------------------------------------------------------------- sécurité

  protected async afficherSecurite(): Promise<void> {
    this.onglet.set('securite');
    await this.action.executer(async () => {
      this.journal.set(await this.api.journal(this.atelierId()));
      if (this.chef()) {
        this.lectures.set(await this.api.lectures(this.atelierId()));
        if (!this.equipements().length) {
          this.equipements.set(await this.ateliersApi.equipements(this.atelierId()));
        }
      }
    });
  }

  protected async lireConsignes(): Promise<void> {
    const c = await this.action.executer(() => this.api.lireConsignes(this.atelierId()));
    if (c) {
      this.consignes.set(c);
      this.message.set('Prise de connaissance des consignes enregistrée : vous pouvez demander vos séances.');
    }
  }

  protected modifierConsignes(): void {
    this.edition.set(
      (this.consignes()?.consignes ?? []).map((c) => ({ categorie: c.categorie, equipementId: c.equipementId ?? '', texte: c.texte })),
    );
  }

  protected changerConsigne(i: number, champ: 'categorie' | 'equipementId' | 'texte', valeur: string): void {
    this.edition.set((this.edition() ?? []).map((c, j) => (j === i ? { ...c, [champ]: valeur } : c)));
  }

  protected ajouterConsigne(): void {
    this.edition.set([...(this.edition() ?? []), { categorie: 'EPI', equipementId: '', texte: '' }]);
  }

  protected retirerConsigne(i: number): void {
    this.edition.set((this.edition() ?? []).filter((_, j) => j !== i));
  }

  protected async enregistrerConsignes(): Promise<void> {
    const lignes = (this.edition() ?? [])
      .filter((c) => c.texte.trim())
      .map((c) => ({ categorie: c.categorie, equipementId: c.equipementId || null, texte: c.texte.trim() }));
    const r = await this.action.executer(() => this.api.definirConsignes(this.atelierId(), lignes));
    if (r) {
      this.consignes.set(r);
      this.edition.set(null);
      this.lectures.set(await this.api.lectures(this.atelierId()).catch(() => null));
      this.message.set('Consignes enregistrées : chaque enseignant de l’atelier devra en reprendre connaissance.');
    }
  }

  protected async consigner(): Promise<void> {
    const e = await this.action.executer(() =>
      this.api.consigner(this.atelierId(), {
        seanceId: this.hSeance() || null,
        gravite: this.hGravite(),
        description: this.hDescription().trim(),
        mesures: this.hMesures().trim() || null,
      }),
    );
    if (e) {
      this.journal.set([e, ...this.journal()]);
      this.hDescription.set('');
      this.hMesures.set('');
      this.message.set(e.gravite === 'OBSERVATION' ? 'Observation consignée.' : 'Événement consigné : le chef des travaux et l’enseignant sont alertés.');
    }
  }

  // ---------------------------------------------------------------- production

  protected avancement(p: ProductionVue): number {
    return Math.min(100, Math.round((100 * (p.quantiteProduite ?? 0)) / p.quantite));
  }

  protected async creerProduction(): Promise<void> {
    const p = await this.action.executer(() =>
      this.api.creerProduction({
        atelierId: this.atelierId(),
        client: this.pClient().trim(),
        typeClient: this.pType(),
        objet: this.pObjet().trim(),
        quantite: this.pQuantite() ?? 0,
        unite: this.pUnite().trim(),
        montant: this.pMontant(),
        dateCommande: null,
        echeance: this.pEcheance() || null,
        observations: null,
      }),
    );
    if (p) {
      this.productions.set([p, ...this.productions()]);
      this.formProduction.set(false);
      this.pClient.set('');
      this.pObjet.set('');
      this.pQuantite.set(null);
      this.message.set(`Commande ${p.numero} enregistrée : les enseignants peuvent y rattacher leurs séances.`);
    }
  }

  protected async statut(p: ProductionVue, statut: StatutProduction): Promise<void> {
    const r = await this.action.executer(() => this.api.statutProduction(p.id, statut));
    if (r) {
      this.productions.set(this.productions().map((x) => (x.id === r.id ? r : x)));
    }
  }
}

function cle(o: OptionSeance): string {
  return `${o.classeId}|${o.matiereId}`;
}
