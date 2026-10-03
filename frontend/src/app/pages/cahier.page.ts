import { Component, computed, effect, inject, input, OnInit, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { messageErreur } from '../core/erreurs';
import { Affectation } from '../core/modeles';
import { dateCourte, dateLocale, nouvelIdentifiant } from '../core/outils';
import { SessionService } from '../core/session.service';
import { CahierService, SeanceLocale } from '../hors-ligne/cahier.service';
import { ListesService } from '../hors-ligne/listes.service';
import { dureeHeures, erreurSeance, FicheVue, heures, SeanceVue } from '../progression/modeles-progression';

/** Une ligne de la liste : séance envoyée, ou saisie sur le téléphone en attente (ou refusée). */
interface Ligne {
  id: string;
  date: string;
  heureDebut: string;
  heureFin: string;
  heures: number;
  sequence: string | null;
  contenu: string;
  travail: string | null;
  locale?: SeanceLocale;
  serveur?: SeanceVue;
}

/** « 08:00:00 » → « 08:00 » */
function hm(h: string): string {
  return h.slice(0, 5);
}

/**
 * Cahier de textes d'une matière dans une classe : saisie de la séance (date, horaire, séquence
 * de la progression, contenu, travail à faire), liste des séances et avancement face au prévu.
 * La saisie marche sans réseau ; l'envoi se fait dès que le réseau revient.
 */
@Component({
  selector: 'app-cahier',
  imports: [FormsModule, RouterLink],
  templateUrl: './cahier.page.html',
  styleUrl: './cahier.page.scss',
})
export class CahierPage implements OnInit {
  protected readonly session = inject(SessionService);
  protected readonly cahier = inject(CahierService);
  private readonly listes = inject(ListesService);

  readonly classeId = input.required<string>();
  readonly matiereId = input.required<string>();
  /** Venant de l'appel : le créneau du cours est prérempli. */
  readonly date = input<string>();
  readonly debut = input<string>();
  readonly fin = input<string>();

  protected readonly heures = heures;
  protected readonly Math = Math;
  protected readonly dateCourte = dateCourte;
  protected readonly aujourdhui = dateLocale();

  protected readonly affectation = signal<Affectation | null>(null);
  protected readonly fiche = signal<FicheVue | null>(null);
  protected readonly seancesServeur = signal<SeanceVue[]>([]);
  protected readonly horsLigne = signal(false);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly enregistrement = signal(false);

  // Formulaire
  protected readonly editeId = signal<string | null>(null);
  protected readonly fDate = signal(dateLocale());
  protected readonly fDebut = signal('');
  protected readonly fFin = signal('');
  protected readonly fSequence = signal<string>('');
  protected readonly fContenu = signal('');
  protected readonly fTravail = signal('');

  protected readonly erreurSaisie = computed(() =>
    erreurSeance({ date: this.fDate(), heureDebut: this.fDebut(), heureFin: this.fFin(), contenu: this.fContenu(), travailAFaire: this.fTravail() }, this.aujourdhui),
  );
  protected readonly duree = computed(() => dureeHeures(this.fDebut(), this.fFin()));

  protected readonly locales = computed(() =>
    this.cahier.operations().filter((o) => o.donnees.classeId === this.classeId() && o.donnees.matiereId === this.matiereId()),
  );

  protected readonly lignes = computed<Ligne[]>(() => {
    const titre = (ordre: number | null) => (ordre === null ? null : (this.fiche()?.sequences.find((s) => s.ordre === ordre)?.titre ?? null));
    const enAttente = new Set(this.locales().map((o) => o.id));
    const locales: Ligne[] = this.locales().map((o) => ({
      id: o.id,
      date: o.donnees.date,
      heureDebut: o.donnees.heureDebut,
      heureFin: o.donnees.heureFin,
      heures: dureeHeures(o.donnees.heureDebut, o.donnees.heureFin) ?? 0,
      sequence: titre(o.donnees.sequenceOrdre),
      contenu: o.donnees.contenu,
      travail: o.donnees.travailAFaire,
      locale: o,
    }));
    // Séances lues sur le serveur, complétées par celles envoyées depuis (retour du réseau pendant l'écran)
    const recentes = this.cahier.envoyees().filter((s) => s.classeId === this.classeId() && s.matiereId === this.matiereId());
    const parId = new Map([...this.seancesServeur(), ...recentes].map((s) => [s.id, s]));
    const serveur: Ligne[] = [...parId.values()]
      .filter((s) => !enAttente.has(s.id))
      .map((s) => ({
        id: s.id,
        date: s.date,
        heureDebut: hm(s.heureDebut),
        heureFin: hm(s.heureFin),
        heures: s.heures,
        sequence: s.sequenceTitre,
        contenu: s.contenu,
        travail: s.travailAFaire,
        serveur: s,
      }));
    return [...locales, ...serveur].sort((a, b) => (b.date + b.heureDebut).localeCompare(a.date + a.heureDebut));
  });

  /** Réalisé par séquence : séances envoyées plus celles qui attendent sur le téléphone. */
  protected readonly realise = computed(() => {
    const parOrdre = new Map<number, number>();
    let total = 0;
    for (const l of this.lignes()) {
      total += l.heures;
      const ordre = l.locale ? l.locale.donnees.sequenceOrdre : (l.serveur?.sequenceOrdre ?? null);
      if (ordre !== null) {
        parOrdre.set(ordre, (parOrdre.get(ordre) ?? 0) + l.heures);
      }
    }
    return { parOrdre, total: Math.round(total * 10) / 10 };
  });

  /** Dernière séance gardée sur le téléphone faute de réseau : son envoi est annoncé dès qu'il a lieu. */
  private readonly attendue = signal<string | null>(null);

  constructor() {
    effect(() => {
      const envoyees = this.cahier.envoyees();
      untracked(() => {
        const id = this.attendue();
        if (id && envoyees.some((s) => s.id === id)) {
          this.attendue.set(null);
          this.message.set('Séance envoyée.');
        }
      });
    });
  }

  async ngOnInit(): Promise<void> {
    if (this.date()) {
      this.fDate.set(this.date()!);
    }
    this.fDebut.set(this.debut() ?? '');
    this.fFin.set(this.fin() ?? '');
    try {
      await this.cahier.recharger();
      const affectations = (await this.listes.affectations())?.affectations ?? [];
      this.affectation.set(affectations.find((a) => a.classeId === this.classeId() && a.matiereId === this.matiereId()) ?? null);
      await this.charger();
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  private async charger(): Promise<void> {
    const [f, s] = await Promise.all([
      this.cahier.fiche(this.classeId(), this.matiereId()).catch(() => ({ fiche: undefined, horsLigne: true })),
      this.cahier.seances(this.classeId(), this.matiereId()),
    ]);
    this.fiche.set(f.fiche ?? null);
    this.seancesServeur.set(s.seances);
    this.horsLigne.set(s.horsLigne);
    // Une seule séquence en cours : on la propose
    if (!this.editeId() && !this.fSequence()) {
      const enCours = f.fiche?.sequences.find((q) => q.heuresRealisees < q.heuresPrevues);
      this.fSequence.set(enCours ? String(enCours.ordre) : '');
    }
  }

  protected modifier(l: Ligne): void {
    this.editeId.set(l.id);
    this.fDate.set(l.date);
    this.fDebut.set(l.heureDebut);
    this.fFin.set(l.heureFin);
    const ordre = l.locale ? l.locale.donnees.sequenceOrdre : (l.serveur?.sequenceOrdre ?? null);
    this.fSequence.set(ordre === null ? '' : String(ordre));
    this.fContenu.set(l.contenu);
    this.fTravail.set(l.travail ?? '');
    this.message.set(null);
    if (typeof window !== 'undefined') {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }
  }

  protected annulerModification(): void {
    this.editeId.set(null);
    this.fContenu.set('');
    this.fTravail.set('');
  }

  protected async enregistrer(): Promise<void> {
    if (this.erreurSaisie()) {
      return;
    }
    const a = this.affectation();
    const id = this.editeId() ?? nouvelIdentifiant();
    this.enregistrement.set(true);
    this.erreur.set(null);
    try {
      await this.cahier.enregistrer(
        id,
        {
          classeId: this.classeId(),
          matiereId: this.matiereId(),
          date: this.fDate(),
          heureDebut: this.fDebut(),
          heureFin: this.fFin(),
          sequenceOrdre: this.fSequence() ? Number(this.fSequence()) : null,
          contenu: this.fContenu().trim(),
          travailAFaire: this.fTravail().trim() || null,
        },
        { classeCode: a?.classeCode ?? '', matiereLibelle: a?.matiereLibelle ?? '' },
      );
      const bilan = await this.cahier.synchroniser();
      const enAttente = this.locales().some((o) => o.id === id && o.etat === 'EN_ATTENTE');
      this.attendue.set(enAttente ? id : null);
      this.message.set(
        enAttente
          ? 'Séance enregistrée sur le téléphone : elle partira dès qu’il y aura du réseau.'
          : this.locales().some((o) => o.id === id && o.etat === 'REFUSE')
            ? 'Séance refusée par le serveur : voir le message dans la liste.'
            : 'Séance enregistrée.',
      );
      if (bilan.envoyees > 0) {
        await this.charger().catch(() => undefined);
      }
      this.editeId.set(null);
      this.fContenu.set('');
      this.fTravail.set('');
      this.fDebut.set('');
      this.fFin.set('');
    } catch (e) {
      this.erreur.set(messageErreur(e, "La séance n'a pas pu être enregistrée sur le téléphone."));
    } finally {
      this.enregistrement.set(false);
    }
  }

  protected async supprimer(l: Ligne): Promise<void> {
    if (l.locale) {
      await this.cahier.abandonner(l.locale);
      return;
    }
    if (!l.serveur || !window.confirm(`Supprimer la séance du ${dateCourte(l.date)} ?`)) {
      return;
    }
    try {
      await this.cahier.supprimer(l.serveur);
      this.seancesServeur.set(this.seancesServeur().filter((s) => s.id !== l.id));
      this.cahier.envoyees.set(this.cahier.envoyees().filter((s) => s.id !== l.id));
    } catch (e) {
      this.erreur.set(messageErreur(e));
    }
  }
}
