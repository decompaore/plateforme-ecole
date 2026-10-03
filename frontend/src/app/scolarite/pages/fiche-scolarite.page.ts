import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { DossierEleveVue, InscriptionVue } from '../../admin/modeles-admin';
import { dateCourte, dateHeureCourte, dateLocale, nouvelIdentifiant } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import {
  fcfa,
  FraisVue,
  LIBELLE_BOURSE,
  LIBELLE_MOYEN,
  lireMontant,
  MOYENS,
  MoyenPaiement,
  OrganismeVue,
  PaiementVue,
  Payeur,
  PriseEnChargeVue,
  SituationVue,
} from '../modeles-scolarite';
import { GESTION_SCOLARITE, ScoNavComponent } from '../sco-nav.component';
import { enregistrerFichier, ScolariteApi } from '../scolarite-api.service';

/** Contrôle d'un encaissement avant l'envoi : message à afficher, ou null si tout va bien. */
export function erreurEncaissement(
  montant: number | null,
  reste: number,
  moyen: MoyenPaiement,
  reference: string,
  date: string,
  aujourdhui: string,
): string | null {
  if (reste <= 0) {
    return 'Rien à payer pour ce payeur.';
  }
  if (montant === null) {
    return 'Montant en francs CFA, sans centimes (par exemple 15000).';
  }
  if (montant > reste) {
    return `Le reste à payer est de ${fcfa(reste)}.`;
  }
  if (moyen !== 'ESPECES' && !reference.trim()) {
    return `Référence de la transaction ${LIBELLE_MOYEN[moyen]} obligatoire.`;
  }
  if (date > aujourdhui) {
    return 'La date du paiement ne peut pas être dans le futur.';
  }
  return null;
}

/**
 * Fiche de scolarité d'un élève au guichet : ce qui est dû, payé, en retard, l'échéancier,
 * l'encaissement avec son reçu, l'annulation d'un paiement, la prise en charge d'un boursier,
 * les exonérations et les frais facultatifs.
 */
@Component({
  selector: 'app-fiche-scolarite',
  imports: [FormsModule, RouterLink, ScoNavComponent],
  templateUrl: './fiche-scolarite.page.html',
  styleUrl: './fiche-scolarite.page.scss',
})
export class FicheScolaritePage implements OnInit {
  private readonly admin = inject(AdminApi);
  private readonly api = inject(ScolariteApi);
  private readonly session = inject(SessionService);

  /** Depuis le guichet (élève) ou depuis l'état d'une classe (inscription). */
  readonly eleveId = input<string>();
  readonly depuisInscription = input<string | undefined>(undefined, { alias: 'inscription' });

  protected readonly fcfa = fcfa;
  protected readonly dateCourte = dateCourte;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly moyens = MOYENS;
  protected readonly libelleMoyen = LIBELLE_MOYEN;
  protected readonly libelleBourse = LIBELLE_BOURSE;
  protected readonly aujourdhui = dateLocale();

  protected readonly action = new Action();
  protected readonly envoi = new Action();
  protected readonly modification = new Action();

  protected readonly dossier = signal<DossierEleveVue | null>(null);
  protected readonly inscriptionId = signal('');
  protected readonly situation = signal<SituationVue | null>(null);
  protected readonly priseEnCharge = signal<PriseEnChargeVue | null>(null);
  protected readonly fraisAnnee = signal<FraisVue[]>([]);
  protected readonly organismes = signal<OrganismeVue[]>([]);
  protected readonly message = signal<string | null>(null);
  /** Dernier paiement enregistré depuis cet écran : son reçu est proposé tout de suite. */
  protected readonly dernier = signal<PaiementVue | null>(null);

  protected readonly gestion = computed(() => this.session.aLeRole(...GESTION_SCOLARITE));

  protected readonly inscriptions = computed(() =>
    [...(this.dossier()?.inscriptions ?? [])].sort((a, b) => b.anneeLibelle.localeCompare(a.anneeLibelle)),
  );
  protected readonly inscription = computed<InscriptionVue | undefined>(() =>
    this.inscriptions().find((i) => i.id === this.inscriptionId()),
  );
  protected readonly active = computed(() => this.inscription()?.statut === 'ACTIVE');

  // ---------- Encaissement
  protected readonly payeur = signal<Payeur>('FAMILLE');
  protected readonly montant = signal('');
  protected readonly moyen = signal<MoyenPaiement>('ESPECES');
  protected readonly reference = signal('');
  protected readonly deposant = signal('');
  protected readonly datePaiement = signal(dateLocale());
  private cle = nouvelIdentifiant();

  protected readonly reste = computed(() => {
    const s = this.situation();
    return !s ? 0 : this.payeur() === 'FAMILLE' ? s.resteFamille : s.resteOrganisme;
  });
  protected readonly montantLu = computed(() => lireMontant(this.montant()));
  protected readonly erreurSaisie = computed(() =>
    erreurEncaissement(this.montantLu(), this.reste(), this.moyen(), this.reference(), this.datePaiement(), this.aujourdhui),
  );

  // ---------- Annulation
  protected readonly aAnnuler = signal<string | null>(null);
  protected readonly motifAnnulation = signal('');

  // ---------- Bourse, exonérations, frais facultatifs
  protected readonly boursier = computed(() => (this.situation()?.statutBourse ?? 'NON_BOURSIER') !== 'NON_BOURSIER');
  protected readonly pecOuverte = signal(false);
  protected readonly pecOrganisme = signal('');
  protected readonly pecTaux = signal('');
  protected readonly pecReference = signal('');
  protected readonly pecDate = signal('');

  protected readonly exoOuverte = signal(false);
  protected readonly exoFrais = signal('');
  protected readonly exoMontant = signal('');
  protected readonly exoMotif = signal('');

  /** Frais de l'élève (ceux de son échéancier), pour les exonérations. */
  protected readonly fraisEleve = computed(() => {
    const vus = new Map<string, string>();
    for (const e of this.situation()?.echeances ?? []) {
      vus.set(e.fraisId, e.libelle);
    }
    return [...vus.entries()].map(([id, libelle]) => ({ id, libelle }));
  });

  /** Frais facultatifs de l'année : souscrits quand ils figurent dans l'échéancier. */
  protected readonly facultatifs = computed(() => {
    const souscrits = new Set((this.situation()?.echeances ?? []).map((e) => e.fraisId));
    return this.fraisAnnee()
      .filter((f) => !f.obligatoire)
      .map((f) => ({ frais: f, souscrit: souscrits.has(f.id) }));
  });

  protected readonly paiements = computed(() =>
    [...(this.situation()?.paiements ?? [])].sort((a, b) => b.enregistreLe.localeCompare(a.enregistreLe)),
  );

  async ngOnInit(): Promise<void> {
    const demandee = this.depuisInscription();
    const d = await this.action.executer(async () => {
      const eleveId = this.eleveId() ?? (demandee ? (await this.api.situation(demandee)).eleveId : undefined);
      if (!eleveId) {
        throw new Error('Élève introuvable');
      }
      return this.admin.dossier(eleveId);
    });
    if (!d) {
      return;
    }
    this.dossier.set(d);
    const courante =
      this.inscriptions().find((i) => i.id === demandee) ??
      this.inscriptions().find((i) => i.statut === 'ACTIVE') ??
      this.inscriptions()[0];
    if (courante) {
      await this.choisirInscription(courante.id);
    }
  }

  protected async choisirInscription(id: string): Promise<void> {
    this.inscriptionId.set(id);
    this.dernier.set(null);
    this.message.set(null);
    this.reinitialiserSaisie();
    await this.recharger();
  }

  private async recharger(): Promise<void> {
    const id = this.inscriptionId();
    const anneeId = this.inscription()?.anneeId;
    await this.action.executer(async () => {
      const [s, frais, organismes] = await Promise.all([
        this.api.situation(id),
        anneeId ? this.api.frais(anneeId) : Promise.resolve([]),
        this.gestion() && this.organismes().length === 0 ? this.api.organismes() : Promise.resolve(this.organismes()),
      ]);
      const pec = s.statutBourse !== 'NON_BOURSIER' ? await this.api.priseEnCharge(id) : null;
      if (id === this.inscriptionId()) {
        this.situation.set(s);
        this.fraisAnnee.set(frais);
        this.organismes.set(organismes);
        this.priseEnCharge.set(pec);
        if (this.payeur() === 'ORGANISME' && s.resteOrganisme <= 0) {
          this.payeur.set('FAMILLE');
        }
      }
    });
  }

  private reinitialiserSaisie(): void {
    this.montant.set('');
    this.reference.set('');
    this.deposant.set('');
    this.moyen.set('ESPECES');
    this.datePaiement.set(dateLocale());
    this.cle = nouvelIdentifiant();
  }

  protected proposer(montant: number): void {
    this.montant.set(String(montant));
  }

  protected async encaisser(): Promise<void> {
    const s = this.situation();
    const montant = lireMontant(this.montant());
    if (!s || this.erreurSaisie() || montant === null) {
      return;
    }
    const payeur = this.payeur();
    const p = await this.envoi.executer(() =>
      this.api.encaisser(s.inscriptionId, {
        montant,
        moyen: this.moyen(),
        payeur,
        organismeId: payeur === 'ORGANISME' ? (this.priseEnCharge()?.organismeId ?? null) : null,
        referenceExterne: this.reference().trim() || null,
        deposant: this.deposant().trim() || null,
        datePaiement: this.datePaiement() === this.aujourdhui ? null : this.datePaiement(),
        // Même clé si l'on renvoie après une coupure : le serveur ne compte le paiement qu'une fois
        cleIdempotence: this.cle,
      }),
    );
    if (p) {
      this.dernier.set(p);
      this.reinitialiserSaisie();
      await this.recharger();
    }
  }

  protected async telechargerRecu(p: PaiementVue): Promise<void> {
    const blob = await this.modification.executer(() => this.api.recu(p.id));
    if (blob) {
      enregistrerFichier(blob, `recu-${p.recuNumero ?? p.id}.pdf`);
    }
  }

  protected ouvrirAnnulation(p: PaiementVue): void {
    this.aAnnuler.set(p.id);
    this.motifAnnulation.set('');
  }

  protected async annuler(): Promise<void> {
    const id = this.aAnnuler();
    const motif = this.motifAnnulation().trim();
    if (!id || !motif) {
      return;
    }
    const p = await this.modification.executer(() => this.api.annuler(id, motif));
    if (p) {
      this.aAnnuler.set(null);
      this.message.set(`Reçu n° ${p.recuNumero} annulé : ${fcfa(p.montant)} ne comptent plus.`);
      if (this.dernier()?.id === p.id) {
        this.dernier.set(null);
      }
      await this.recharger();
    }
  }

  // ---------- Prise en charge

  protected ouvrirPriseEnCharge(): void {
    const p = this.priseEnCharge();
    this.pecOrganisme.set(p?.organismeId ?? this.organismes().find((o) => o.actif)?.id ?? '');
    this.pecTaux.set(p?.taux !== null && p?.taux !== undefined ? String(p.taux) : '');
    this.pecReference.set(p?.referenceDecision ?? '');
    this.pecDate.set(p?.dateDecision ?? '');
    this.pecOuverte.set(true);
  }

  protected async enregistrerPriseEnCharge(): Promise<void> {
    const taux = this.pecTaux().trim() ? Number(this.pecTaux().replace(',', '.')) : null;
    if (!this.pecOrganisme() || (taux !== null && (Number.isNaN(taux) || taux <= 0 || taux > 100))) {
      this.modification.erreur.set('Choisissez l’organisme ; le taux, s’il est saisi, est compris entre 1 et 100.');
      return;
    }
    const id = this.inscriptionId();
    const ok = await this.modification.reussit(() =>
      this.api.definirPriseEnCharge(id, {
        organismeId: this.pecOrganisme(),
        taux,
        referenceDecision: this.pecReference().trim() || null,
        dateDecision: this.pecDate() || null,
      }),
    );
    if (ok) {
      this.pecOuverte.set(false);
      this.message.set('Prise en charge enregistrée : l’échéancier est recalculé.');
      await this.recharger();
    }
  }

  protected async supprimerPriseEnCharge(): Promise<void> {
    const id = this.inscriptionId();
    if (await this.modification.reussit(() => this.api.supprimerPriseEnCharge(id))) {
      this.message.set('Prise en charge retirée : le taux par défaut du statut de bourse s’applique.');
      await this.recharger();
    }
  }

  // ---------- Exonérations

  protected ouvrirExoneration(): void {
    this.exoFrais.set(this.fraisEleve()[0]?.id ?? '');
    this.exoMontant.set('');
    this.exoMotif.set('');
    this.exoOuverte.set(true);
  }

  protected async exonerer(): Promise<void> {
    const montant = lireMontant(this.exoMontant());
    if (!this.exoFrais() || montant === null || !this.exoMotif().trim()) {
      this.modification.erreur.set('Choisissez le frais, le montant de la réduction et son motif.');
      return;
    }
    const id = this.inscriptionId();
    const ok = await this.modification.reussit(() =>
      this.api.exonerer(id, this.exoFrais(), { montant, motif: this.exoMotif().trim() }),
    );
    if (ok) {
      this.exoOuverte.set(false);
      this.message.set(`Exonération de ${fcfa(montant)} enregistrée.`);
      await this.recharger();
    }
  }

  protected async retirerExoneration(fraisId: string): Promise<void> {
    const id = this.inscriptionId();
    if (await this.modification.reussit(() => this.api.retirerExoneration(id, fraisId))) {
      this.message.set('Exonération retirée.');
      await this.recharger();
    }
  }

  // ---------- Frais facultatifs

  protected async basculerFacultatif(f: FraisVue, souscrit: boolean): Promise<void> {
    const id = this.inscriptionId();
    const ok = await this.modification.reussit(() => (souscrit ? this.api.resilier(id, f.id) : this.api.souscrire(id, f.id)));
    if (ok) {
      this.message.set(souscrit ? `${f.libelle} : résilié.` : `${f.libelle} : souscrit, ajouté à l’échéancier.`);
      await this.recharger();
    }
  }
}
