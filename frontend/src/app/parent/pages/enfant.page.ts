import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { messageErreur } from '../../core/erreurs';
import { dateCourte, dateHeureCourte, dateLongue } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { AbsenceVue, dateHeure, discipline, heure, heures, HistoriqueVue, LIBELLE_INCIDENT } from '../../vie-scolaire/modeles-vs';
import {
  BulletinEleveVue,
  EnfantVue,
  fcfa,
  LIBELLE_DISTINCTION,
  LIBELLE_MOYEN,
  Lecture,
  PaiementVue,
  SituationVue,
  sur20,
} from '../modeles-parent';
import { PaiementComponent } from '../paiement.component';
import { enregistrer, ParentApi } from '../parent-api.service';
import { situationCourante } from '../resume';

type Onglet = 'absences' | 'bulletins' | 'vie' | 'scolarite';

interface JourAbsence {
  date: string;
  absences: AbsenceVue[];
  justifiee: boolean;
}

/** Suivi d'un enfant : absences, bulletins, vie scolaire, scolarité et paiement. */
@Component({
  selector: 'app-enfant',
  imports: [RouterLink, PaiementComponent],
  templateUrl: './enfant.page.html',
  styleUrl: './enfant.page.scss',
})
export class EnfantPage implements OnInit {
  readonly eleveId = input.required<string>();

  private readonly api = inject(ParentApi);
  private readonly session = inject(SessionService);

  protected readonly fcfa = fcfa;
  protected readonly sur20 = sur20;
  protected readonly dateCourte = dateCourte;
  protected readonly dateLongue = dateLongue;
  protected readonly dateHeure = dateHeure;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly heure = heure;
  protected readonly heures = heures;
  protected readonly discipline = discipline;
  protected readonly libelleIncident = LIBELLE_INCIDENT;
  protected readonly libelleDistinction = LIBELLE_DISTINCTION;
  protected readonly libelleMoyen = LIBELLE_MOYEN;

  /** Rubriques ; vie scolaire et scolarité seulement si l'établissement utilise ces modules (v0.31). */
  protected readonly onglets: { id: Onglet; titre: string }[] = [
    { id: 'absences' as Onglet, titre: 'Absences' },
    { id: 'bulletins' as Onglet, titre: 'Bulletins' },
    ...(this.session.moduleActif('VIE_SCOLAIRE') ? [{ id: 'vie' as Onglet, titre: 'Vie scolaire' }] : []),
    ...(this.session.moduleActif('SCOLARITE') ? [{ id: 'scolarite' as Onglet, titre: 'Scolarité' }] : []),
  ];
  protected readonly onglet = signal<Onglet>('absences');

  protected readonly enfant = signal<EnfantVue | null>(null);
  protected readonly absences = signal<Lecture<AbsenceVue[]> | null>(null);
  protected readonly bulletins = signal<Lecture<BulletinEleveVue[]> | null>(null);
  protected readonly vie = signal<Lecture<HistoriqueVue[]> | null>(null);
  protected readonly scolarite = signal<Lecture<SituationVue[]> | null>(null);
  protected readonly erreurs = signal<Partial<Record<Onglet | 'enfant', string>>>({});
  protected readonly message = signal<string | null>(null);
  protected readonly paiementOuvert = signal(false);
  protected readonly telechargement = signal<string | null>(null);

  /** Date de la plus ancienne copie affichée quand le serveur est injoignable. */
  protected readonly horsLigne = computed(() => {
    const lectures: (Lecture<unknown> | null)[] = [this.absences(), this.bulletins(), this.vie(), this.scolarite()];
    const dates = lectures
      .filter((l): l is Lecture<unknown> => !!l && l.horsLigne)
      .map((l) => l.le)
      .sort();
    return dates[0] ?? null;
  });

  // ---------- Absences

  protected readonly joursAbsence = computed<JourAbsence[]>(() => {
    const parJour = new Map<string, AbsenceVue[]>();
    for (const a of this.absences()?.donnees ?? []) {
      parJour.set(a.date, [...(parJour.get(a.date) ?? []), a]);
    }
    return [...parJour.entries()]
      .sort(([a], [b]) => b.localeCompare(a))
      .map(([date, absences]) => ({
        date,
        absences: absences.sort((x, y) => x.heureDebut.localeCompare(y.heureDebut)),
        justifiee: absences.filter((x) => x.type === 'ABSENCE').every((x) => x.justifiee),
      }));
  });
  protected readonly toutesLesAbsences = signal(false);
  protected readonly joursAffiches = computed(() =>
    this.toutesLesAbsences() ? this.joursAbsence() : this.joursAbsence().slice(0, 10),
  );
  protected readonly bilanAbsences = computed(() => {
    const l = (this.absences()?.donnees ?? []).filter((a) => a.type === 'ABSENCE');
    const duree = (a: AbsenceVue) => minutes(a.heureFin) - minutes(a.heureDebut);
    return {
      heures: l.reduce((t, a) => t + duree(a), 0) / 60,
      nonJustifiees: l.filter((a) => !a.justifiee).reduce((t, a) => t + duree(a), 0) / 60,
      retards: (this.absences()?.donnees ?? []).filter((a) => a.type === 'RETARD').length,
    };
  });

  // ---------- Vie scolaire (année la plus récente d'abord)

  protected readonly incidents = computed(() =>
    (this.vie()?.donnees ?? []).flatMap((h) => h.incidents.filter((i) => !i.annule)).sort((a, b) => b.date.localeCompare(a.date)),
  );
  protected readonly convocations = computed(() =>
    (this.vie()?.donnees ?? []).flatMap((h) => h.convocations).sort((a, b) => b.rendezVous.localeCompare(a.rendezVous)),
  );

  // ---------- Scolarité

  protected readonly situation = computed(() => {
    const e = this.enfant();
    const l = this.scolarite()?.donnees ?? [];
    return e ? situationCourante(e, l) : l[0];
  });
  protected readonly anciennes = computed(() => (this.scolarite()?.donnees ?? []).filter((s) => s !== this.situation() && s.resteFamille > 0));

  async ngOnInit(): Promise<void> {
    await this.chargerTout();
  }

  private async chargerTout(): Promise<void> {
    const id = this.eleveId();
    await Promise.all([
      this.charger('enfant', async () => {
        const l = await this.api.enfants();
        const e = l.donnees.find((x) => x.eleveId === id);
        if (!e) {
          throw new Error("Cet enfant n'est pas rattaché à votre compte.");
        }
        this.enfant.set(e);
      }),
      this.charger('absences', async () => this.absences.set(await this.api.absences(id))),
      this.charger('bulletins', async () => this.bulletins.set(await this.api.bulletins(id))),
      ...(this.session.moduleActif('VIE_SCOLAIRE') ? [this.charger('vie', async () => this.vie.set(await this.api.vieScolaire(id)))] : []),
      ...(this.session.moduleActif('SCOLARITE') ? [this.charger('scolarite', async () => this.scolarite.set(await this.api.scolarite(id)))] : []),
    ]);
  }

  private async charger(quoi: Onglet | 'enfant', action: () => Promise<void>): Promise<void> {
    try {
      await action();
    } catch (e) {
      this.erreurs.update((x) => ({ ...x, [quoi]: messageErreur(e) }));
    }
  }

  protected async telecharger(type: 'bulletin' | 'recu', id: string, nom: string): Promise<void> {
    this.telechargement.set(id);
    this.message.set(null);
    try {
      const fichier = type === 'bulletin' ? await this.api.bulletinPdf(id) : await this.api.recuPdf(id);
      enregistrer(fichier, nom);
    } catch (e) {
      this.message.set(messageErreur(e, 'Le document ne peut être téléchargé qu’avec du réseau.'));
    } finally {
      this.telechargement.set(null);
    }
  }

  protected nomFichier(prefixe: string, suite: string): string {
    const e = this.enfant();
    const nom = e ? `${e.nom}-${e.prenoms}` : 'eleve';
    return `${prefixe}-${nom}-${suite}.pdf`.replace(/\s+/g, '-').normalize('NFD').replace(/[̀-ͯ]/g, '');
  }

  protected paiementsVisibles(s: SituationVue): PaiementVue[] {
    return s.paiements.filter((p) => p.payeur === 'FAMILLE').sort((a, b) => b.datePaiement.localeCompare(a.datePaiement));
  }

  protected async paye(): Promise<void> {
    await this.charger('scolarite', async () => this.scolarite.set(await this.api.scolarite(this.eleveId())));
  }

  protected fermerPaiement(): void {
    this.paiementOuvert.set(false);
  }

  protected peutPayer(s: SituationVue | undefined): boolean {
    return !!s && s.resteFamille > 0 && this.session.moduleActif('MOBILE_MONEY') && !this.session.horsConnexion() && !this.horsLigne();
  }
}

function minutes(h: string): number {
  const [hh, mm] = h.split(':').map(Number);
  return hh * 60 + mm;
}
