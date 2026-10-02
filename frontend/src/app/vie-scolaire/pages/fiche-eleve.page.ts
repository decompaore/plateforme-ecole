import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { DossierEleveVue, InscriptionVue } from '../../admin/modeles-admin';
import { dateCourte, dateLocale, dateLongue } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import { JustificationComponent } from '../justification.component';
import {
  AbsenceVue,
  ConvocationVue,
  discipline,
  heures,
  HistoriqueVue,
  IncidentVue,
  JustificatifVue,
  heure,
  LIBELLE_CONVOCATION,
  LIBELLE_INCIDENT,
  LIBELLE_JUSTIFICATIF,
  RESERVE_DIRECTION,
  SMS_PAR_DEFAUT,
  StatutConvocation,
  TypeIncident,
} from '../modeles-vs';
import { VieScolaireApi } from '../vie-scolaire-api.service';

const LIENS: Record<string, string> = { PERE: 'Père', MERE: 'Mère', TUTEUR: 'Tuteur', AUTRE: 'Contact' };

/** Une absence par jour, tous créneaux réunis. */
interface JourAbsence {
  date: string;
  absences: AbsenceVue[];
  justifiee: boolean;
}

/**
 * Fiche de vie scolaire d'un élève pour une année : contacts des parents, absences et
 * justificatifs, incidents, convocations. Actions selon le rôle (le serveur vérifie).
 */
@Component({
  selector: 'app-fiche-eleve-vs',
  imports: [FormsModule, RouterLink, JustificationComponent],
  templateUrl: './fiche-eleve.page.html',
  styleUrl: './fiche-eleve.page.scss',
})
export class FicheElevePage implements OnInit {
  readonly eleveId = input.required<string>();

  private readonly api = inject(VieScolaireApi);
  private readonly admin = inject(AdminApi);
  private readonly session = inject(SessionService);

  protected readonly dateCourte = dateCourte;
  protected readonly dateLongue = dateLongue;
  protected readonly heure = heure;
  protected readonly discipline = discipline;
  protected readonly heures = heures;
  protected readonly liens = LIENS;
  protected readonly libelleIncident = LIBELLE_INCIDENT;
  protected readonly libelleJustificatif = LIBELLE_JUSTIFICATIF;
  protected readonly libelleConvocation = LIBELLE_CONVOCATION;
  protected readonly aujourdhui = dateLocale();
  protected readonly action = new Action();

  protected readonly dossier = signal<DossierEleveVue | null>(null);
  protected readonly inscriptionId = signal<string>('');
  protected readonly historique = signal<HistoriqueVue | null>(null);
  protected readonly absences = signal<AbsenceVue[]>([]);
  protected readonly justificatifs = signal<JustificatifVue[]>([]);
  protected readonly message = signal<string | null>(null);

  private readonly roles = computed(() => this.session.roles());
  protected readonly encadrement = computed(() => this.roles().some((r) => ['SURVEILLANT', 'CENSEUR', 'ADMIN_ECOLE'].includes(r)));
  protected readonly direction = computed(() => this.roles().some((r) => ['CENSEUR', 'ADMIN_ECOLE'].includes(r)));
  protected readonly peutJustifier = computed(() =>
    this.roles().some((r) => ['SURVEILLANT', 'CENSEUR', 'ADMIN_ECOLE', 'SECRETARIAT'].includes(r)),
  );

  /** Inscriptions de la plus récente à la plus ancienne. */
  protected readonly inscriptions = computed(() =>
    [...(this.dossier()?.inscriptions ?? [])].sort((a, b) => b.anneeLibelle.localeCompare(a.anneeLibelle)),
  );
  protected readonly inscription = computed<InscriptionVue | undefined>(() =>
    this.inscriptions().find((i) => i.id === this.inscriptionId()),
  );
  protected readonly responsables = computed(() =>
    [...(this.dossier()?.responsables ?? [])].sort((a, b) => Number(b.contactPrioritaire) - Number(a.contactPrioritaire)),
  );

  protected readonly joursAbsence = computed<JourAbsence[]>(() => {
    const parJour = new Map<string, AbsenceVue[]>();
    for (const a of this.absences().filter((x) => x.type === 'ABSENCE')) {
      parJour.set(a.date, [...(parJour.get(a.date) ?? []), a]);
    }
    return [...parJour.entries()]
      .sort(([a], [b]) => b.localeCompare(a))
      .map(([date, absences]) => ({
        date,
        absences: absences.sort((x, y) => x.heureDebut.localeCompare(y.heureDebut)),
        justifiee: absences.every((x) => x.justifiee),
      }));
  });
  /**
   * Heures manquées par discipline sur l'année, les plus nombreuses d'abord : repère un
   * élève qui manque toujours le même cours.
   */
  protected readonly parDiscipline = computed(() => {
    const groupes = new Map<string, { nom: string; cours: number; minutes: number; nonJustifiees: number; retards: number }>();
    for (const a of this.absences()) {
      const cle = a.matiereId ?? '';
      const g = groupes.get(cle) ?? { nom: discipline(a), cours: 0, minutes: 0, nonJustifiees: 0, retards: 0 };
      if (a.type === 'RETARD') {
        g.retards++;
      } else {
        const m = minutes(a.heureFin) - minutes(a.heureDebut);
        g.cours++;
        g.minutes += m;
        g.nonJustifiees += a.justifiee ? 0 : m;
      }
      groupes.set(cle, g);
    }
    return [...groupes.values()]
      .map((g) => ({ ...g, heures: g.minutes / 60, heuresNonJustifiees: g.nonJustifiees / 60 }))
      .sort((a, b) => b.heures - a.heures || b.retards - a.retards || a.nom.localeCompare(b.nom));
  });
  protected readonly retardsEnClasse = computed(() => this.absences().filter((a) => a.type === 'RETARD'));
  protected readonly toutesLesAbsences = signal(false);
  protected readonly joursAffiches = computed(() =>
    this.toutesLesAbsences() ? this.joursAbsence() : this.joursAbsence().slice(0, 8),
  );
  protected readonly bilanAbsences = computed(() => {
    const jours = this.joursAbsence();
    const heures = this.absences()
      .filter((a) => a.type === 'ABSENCE')
      .reduce((total, a) => total + minutes(a.heureFin) - minutes(a.heureDebut), 0);
    const nonJustifiees = this.absences()
      .filter((a) => a.type === 'ABSENCE' && !a.justifiee)
      .reduce((total, a) => total + minutes(a.heureFin) - minutes(a.heureDebut), 0);
    return {
      jours: jours.length,
      heures: Math.round(heures / 6) / 10,
      heuresNonJustifiees: Math.round(nonJustifiees / 6) / 10,
      retards: this.retardsEnClasse().length,
    };
  });

  // Justification
  protected readonly justifierOuvert = signal<string | null>(null);

  // Incident
  protected readonly formIncident = signal(false);
  protected readonly iType = signal<TypeIncident>('AVERTISSEMENT');
  protected readonly iDate = signal(dateLocale());
  protected readonly iMotif = signal('');
  protected readonly iMinutes = signal<number | null>(15);
  protected readonly iJours = signal<number | null>(1);
  protected readonly iDebut = signal('');
  protected readonly iSms = signal(true);
  protected readonly typesIncident = computed<TypeIncident[]>(() =>
    (Object.keys(LIBELLE_INCIDENT) as TypeIncident[]).filter((t) => this.direction() || !RESERVE_DIRECTION.includes(t)),
  );
  protected readonly annulationOuverte = signal<string | null>(null);
  protected readonly motifAnnulation = signal('');

  // Convocation
  protected readonly formConvocation = signal(false);
  protected readonly cRendezVous = signal('');
  protected readonly cMotif = signal('');
  protected readonly cIncident = signal<string>('');
  protected readonly clotureOuverte = signal<string | null>(null);
  protected readonly cStatut = signal<StatutConvocation>('HONOREE');
  protected readonly cCompteRendu = signal('');

  async ngOnInit(): Promise<void> {
    const d = await this.action.executer(() => this.admin.dossier(this.eleveId()));
    if (!d) {
      return;
    }
    this.dossier.set(d);
    const courante =
      this.inscriptions().find((i) => i.statut === 'ACTIVE') ?? this.inscriptions()[0];
    if (courante) {
      await this.choisirInscription(courante.id);
    }
  }

  protected async choisirInscription(id: string): Promise<void> {
    this.inscriptionId.set(id);
    this.fermerFormulaires();
    await this.recharger();
  }

  private async recharger(): Promise<void> {
    const id = this.inscriptionId();
    await this.action.executer(async () => {
      const [h, a, j] = await Promise.all([this.api.historique(id), this.api.absences(id), this.api.justificatifs(id)]);
      if (id === this.inscriptionId()) {
        this.historique.set(h);
        this.absences.set(a);
        this.justificatifs.set(j);
      }
    });
  }

  private fermerFormulaires(): void {
    this.formIncident.set(false);
    this.formConvocation.set(false);
    this.justifierOuvert.set(null);
    this.annulationOuverte.set(null);
    this.clotureOuverte.set(null);
  }

  private async apres(message: string): Promise<void> {
    this.fermerFormulaires();
    this.message.set(message);
    await this.recharger();
  }

  // ---------- Justificatifs

  protected async justifie(): Promise<void> {
    await this.apres('Absences justifiées.');
  }

  protected async supprimerJustificatif(j: JustificatifVue): Promise<void> {
    if (!window.confirm(`Supprimer ce justificatif (${dateCourte(j.du)} – ${dateCourte(j.au)}) ? Les absences redeviennent non justifiées.`)) {
      return;
    }
    if (await this.action.reussit(() => this.api.supprimerJustificatif(j.id))) {
      await this.apres('Justificatif supprimé.');
    }
  }

  // ---------- Incidents

  protected ouvrirIncident(): void {
    this.fermerFormulaires();
    this.message.set(null);
    this.iType.set('AVERTISSEMENT');
    this.iDate.set(this.aujourdhui);
    this.iMotif.set('');
    this.iMinutes.set(15);
    this.iJours.set(1);
    this.iDebut.set('');
    this.iSms.set(SMS_PAR_DEFAUT.AVERTISSEMENT);
    this.formIncident.set(true);
  }

  protected typeIncidentChange(t: TypeIncident): void {
    this.iType.set(t);
    this.iSms.set(SMS_PAR_DEFAUT[t]);
  }

  protected async signaler(): Promise<void> {
    const t = this.iType();
    const r = await this.action.executer(() =>
      this.api.signaler(this.inscriptionId(), {
        type: t,
        date: this.iDate() || null,
        motif: this.iMotif().trim(),
        minutesRetard: t === 'RETARD' ? Number(this.iMinutes()) : null,
        joursExclusion: t === 'EXCLUSION_TEMPORAIRE' ? Number(this.iJours()) : null,
        debutExclusion: t === 'EXCLUSION_TEMPORAIRE' && this.iDebut() ? this.iDebut() : null,
        prevenirFamille: this.iSms(),
      }),
    );
    if (r) {
      await this.apres(
        `${LIBELLE_INCIDENT[r.type]} enregistré${r.famillePrevenue ? ' ; la famille est prévenue par SMS' : ''}.`,
      );
    }
  }

  protected async annulerIncident(i: IncidentVue): Promise<void> {
    const motif = this.motifAnnulation().trim();
    if (!motif) {
      return;
    }
    if (await this.action.reussit(() => this.api.annulerIncident(i.id, motif))) {
      await this.apres(`${LIBELLE_INCIDENT[i.type]} du ${dateCourte(i.date)} annulé.`);
    }
  }

  protected ouvrirAnnulation(i: IncidentVue): void {
    this.motifAnnulation.set('');
    this.annulationOuverte.set(i.id);
  }

  // ---------- Convocations

  protected ouvrirConvocation(incident?: IncidentVue): void {
    this.fermerFormulaires();
    this.message.set(null);
    const demain = new Date();
    demain.setDate(demain.getDate() + 1);
    this.cRendezVous.set(`${dateLocale(demain)}T10:00`);
    this.cMotif.set(incident ? `${LIBELLE_INCIDENT[incident.type]} du ${dateCourte(incident.date)} : ${incident.motif}`.slice(0, 300) : '');
    this.cIncident.set(incident?.id ?? '');
    this.formConvocation.set(true);
  }

  protected async convoquer(): Promise<void> {
    const r = await this.action.executer(() =>
      this.api.convoquer(this.inscriptionId(), {
        rendezVous: this.cRendezVous(),
        motif: this.cMotif().trim(),
        incidentId: this.cIncident() || null,
      }),
    );
    if (r) {
      await this.apres(`Parents convoqués le ${dateHeure(r.rendezVous)} ; ils sont prévenus par SMS.`);
    }
  }

  protected rendezVousPasse(c: ConvocationVue): boolean {
    return new Date(c.rendezVous) <= new Date();
  }

  protected ouvrirCloture(c: ConvocationVue): void {
    this.cStatut.set(this.rendezVousPasse(c) ? 'HONOREE' : 'ANNULEE');
    this.cCompteRendu.set('');
    this.clotureOuverte.set(c.id);
  }

  protected async cloturer(c: ConvocationVue): Promise<void> {
    const r = await this.action.executer(() => this.api.cloturer(c.id, this.cStatut(), this.cCompteRendu().trim() || null));
    if (r) {
      await this.apres(`Convocation du ${dateHeure(c.rendezVous)} : ${LIBELLE_CONVOCATION[r.statut].toLowerCase()}.`);
    }
  }

  protected readonly dateHeure = dateHeure;
}

function minutes(h: string): number {
  const [hh, mm] = h.split(':').map(Number);
  return hh * 60 + mm;
}

/** « 2026-10-15T10:00 » → « 15/10/2026 à 10h00 ». */
export function dateHeure(iso: string): string {
  const [jour, h = '00:00'] = iso.split('T');
  return `${dateCourte(jour)} à ${heure(h)}`;
}
