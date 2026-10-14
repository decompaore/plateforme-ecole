import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { LIBELLE_TYPE_MATIERE } from '../../admin/modeles-admin';
import { ExportBoutonsComponent } from '../../ateliers/export-boutons.component';
import { dateHeureCourte, nouvelIdentifiant } from '../../core/outils';
import { EmploiApi } from '../emploi-api.service';
import { EmploiNavComponent } from '../emploi-nav.component';
import {
  chevauche,
  ClasseEmploiVue,
  CreneauVue,
  Domaine,
  duree,
  EmploiDuTempsVue,
  heure,
  JOURS,
  LIBELLE_DOMAINE,
  MatiereClasseVue,
  plage,
  RESPONSABLE,
  ResultatGeneration,
  SeanceVue,
} from '../modeles-emploi';

type Vue = 'classe' | 'enseignant' | 'atelier';

/** Séance en cours de placement (seanceId null : nouvelle). */
interface Edition {
  seanceId: string | null;
  jour: number;
  creneauId: string;
  matiereId: string;
  groupe: string;
  atelierId: string;
  salle: string;
}

/** Texte d'une case de la grille. */
interface Carte {
  seance: SeanceVue | null;
  titre: string;
  lignes: string[];
  domaine: Domaine | null;
  conflit: boolean;
  groupe: string | null;
}

/** Heures placées / prévues d'une matière, pour la couverture et les listes. */
export function etatCouverture(m: MatiereClasseVue): 'complet' | 'manque' | 'trop' {
  if (m.minutesPlacees < m.minutesPrevues) {
    return 'manque';
  }
  return m.minutesPlacees > m.minutesPrevues ? 'trop' : 'complet';
}

/**
 * Emplois du temps de l'année : grille par classe, par enseignant ou par atelier. Le censeur
 * place les matières générales, le chef des travaux les matières techniques et pratiques ;
 * chacun peut lancer une génération automatique pour ses matières puis ajuster à la main.
 */
@Component({
  selector: 'app-emploi-du-temps',
  imports: [RouterLink, EmploiNavComponent, ExportBoutonsComponent],
  templateUrl: './emploi-du-temps.page.html',
  styleUrl: './emploi-du-temps.page.scss',
})
export class EmploiDuTempsPage {
  private readonly api = inject(EmploiApi);
  protected readonly annees = inject(AnneeCourante);

  protected readonly jours = JOURS;
  protected readonly heure = heure;
  protected readonly plage = plage;
  protected readonly duree = duree;
  protected readonly libelleDomaine = LIBELLE_DOMAINE;
  protected readonly responsable = RESPONSABLE;
  protected readonly libelleType = LIBELLE_TYPE_MATIERE;
  protected readonly etatCouverture = etatCouverture;
  protected readonly dateHeureCourte = dateHeureCourte;

  protected readonly action = new Action();
  protected readonly enregistrement = new Action();
  protected readonly emploi = signal<EmploiDuTempsVue | null>(null);
  protected readonly message = signal<string | null>(null);
  protected readonly vue = signal<Vue>('classe');
  private readonly choix = signal<Record<Vue, string | null>>({ classe: null, enseignant: null, atelier: null });
  protected readonly edition = signal<Edition | null>(null);

  // Génération
  protected readonly toutesLesClasses = signal(true);
  protected readonly remplacer = signal(false);
  protected readonly domainesGeneration = signal<Domaine[]>([]);
  protected readonly resultat = signal<ResultatGeneration | null>(null);

  protected readonly droits = computed(() => this.emploi()?.droits ?? null);
  protected readonly placeur = computed(() => !!this.droits()?.modifiable && (this.droits()?.domaines.length ?? 0) > 0);
  protected readonly mesDomaines = computed(() => (this.droits()?.domaines ?? []).map((d) => this.libelleDomaine[d]).join(' et '));

  protected readonly selection = computed(() => {
    const e = this.emploi();
    if (!e) {
      return null;
    }
    const v = this.vue();
    const choisi = this.choix()[v];
    const ids = v === 'classe' ? e.classes.map((c) => c.id) : v === 'enseignant' ? e.enseignants.map((x) => x.engagementId) : e.ateliers.map((a) => a.id);
    return choisi && ids.includes(choisi) ? choisi : (ids[0] ?? null);
  });

  protected readonly classe = computed<ClasseEmploiVue | null>(() =>
    this.vue() === 'classe' ? (this.emploi()?.classes.find((c) => c.id === this.selection()) ?? null) : null,
  );

  protected readonly titreSelection = computed(() => {
    const e = this.emploi();
    const id = this.selection();
    if (!e || !id) {
      return '';
    }
    switch (this.vue()) {
      case 'classe':
        return e.classes.find((c) => c.id === id)?.code ?? '';
      case 'enseignant':
        return e.enseignants.find((x) => x.engagementId === id)?.nom ?? '';
      default: {
        const a = e.ateliers.find((x) => x.id === id);
        return a ? `Atelier ${a.code} – ${a.nom}` : '';
      }
    }
  });

  protected readonly parametresExport = computed<Record<string, string>>(() => {
    const id = this.selection();
    if (!id) {
      return {};
    }
    return { [this.vue()]: id };
  });

  private readonly enConflit = computed(() => new Set((this.emploi()?.conflits ?? []).flatMap((c) => c.seances)));

  private readonly noms = computed(() => new Map((this.emploi()?.enseignants ?? []).map((x) => [x.engagementId, x.nom])));

  protected readonly bilan = computed(() => {
    const e = this.emploi();
    let prevu = 0;
    let place = 0;
    let completes = 0;
    for (const c of e?.classes ?? []) {
      let complete = true;
      for (const m of c.matieres) {
        prevu += m.minutesPrevues;
        place += Math.min(m.minutesPlacees, m.minutesPrevues);
        if (m.minutesPlacees < m.minutesPrevues) {
          complete = false;
        }
      }
      if (complete) {
        completes++;
      }
    }
    return { prevu, place, completes, classes: e?.classes.length ?? 0, conflits: e?.conflits.length ?? 0 };
  });

  /** Matières que la personne peut placer dans la classe choisie. */
  protected readonly matieresPlacables = computed(() => {
    const c = this.classe();
    const domaines = this.droits()?.domaines ?? [];
    return (c?.matieres ?? []).filter((m) => domaines.includes(m.domaine));
  });

  protected readonly matiereEditee = computed(() => {
    const ed = this.edition();
    return ed ? (this.classe()?.matieres.find((m) => m.matiereId === ed.matiereId) ?? null) : null;
  });

  /** Ateliers proposés : ceux de la filière de la classe d'abord. */
  protected readonly ateliersProposes = computed(() => {
    const e = this.emploi();
    const filiere = this.classe()?.filiereId;
    return [...(e?.ateliers ?? [])].sort((a, b) => Number(b.filieres.includes(filiere ?? '')) - Number(a.filieres.includes(filiere ?? '')) || a.code.localeCompare(b.code));
  });

  /** Alerte avant l'envoi : enseignant pris ailleurs à ce moment (le serveur refusera). */
  protected readonly alerteEdition = computed(() => {
    const ed = this.edition();
    const m = this.matiereEditee();
    const e = this.emploi();
    if (!ed || !m || !e || !m.engagementId) {
      return null;
    }
    const c = e.creneaux.find((x) => x.id === ed.creneauId);
    if (!c) {
      return null;
    }
    const pris = e.occupationsAilleurs.some((o) => o.engagementId === m.engagementId && o.jour === ed.jour && chevauche(o.heureDebut, o.heureFin, c.heureDebut, c.heureFin));
    return pris ? `${m.enseignant ?? 'L’enseignant'} a déjà cours dans un autre établissement à ce moment.` : null;
  });

  protected readonly erreurEdition = computed(() => {
    const ed = this.edition();
    if (!ed) {
      return null;
    }
    if (!ed.matiereId) {
      return 'Choisissez la matière.';
    }
    if (ed.groupe.trim().length > 20) {
      return 'Groupe : 20 caractères au plus.';
    }
    if (ed.salle.trim().length > 40) {
      return 'Salle : 40 caractères au plus.';
    }
    const c = this.emploi()?.creneaux.find((x) => x.id === ed.creneauId);
    if (c && !c.jours.includes(ed.jour)) {
      return `Pas de cours le ${JOURS[ed.jour].toLowerCase()} à ${heure(c.heureDebut)}.`;
    }
    return null;
  });

  constructor() {
    void this.annees.charger();
    effect(() => {
      const annee = this.annees.annee();
      if (annee) {
        untracked(() => void this.charger(annee.id));
      }
    });
  }

  // ------------------------------------------------------------------ grille

  protected existe(c: CreneauVue, jour: number): boolean {
    return c.jours.includes(jour);
  }

  protected cartes(jour: number, creneau: CreneauVue): Carte[] {
    const e = this.emploi();
    const id = this.selection();
    if (!e || !id) {
      return [];
    }
    const v = this.vue();
    const cartes: Carte[] = [];
    for (const s of e.seances) {
      if (s.jour !== jour || s.creneauId !== creneau.id) {
        continue;
      }
      if ((v === 'classe' && s.classeId !== id) || (v === 'enseignant' && s.engagementId !== id) || (v === 'atelier' && s.atelierId !== id)) {
        continue;
      }
      const classe = e.classes.find((c) => c.id === s.classeId);
      const matiere = classe?.matieres.find((m) => m.matiereId === s.matiereId);
      const atelier = s.atelierId ? e.ateliers.find((a) => a.id === s.atelierId) : null;
      const lieu = atelier ? `Atelier ${atelier.code}` : s.salle;
      const enseignant = s.engagementId ? (this.noms().get(s.engagementId) ?? '') : 'Sans enseignant';
      const lignes = v === 'classe' ? [enseignant, lieu] : v === 'enseignant' ? [matiere?.libelle ?? '', lieu] : [matiere?.libelle ?? '', enseignant];
      cartes.push({
        seance: s,
        titre: v === 'classe' ? (matiere?.libelle ?? '?') : (classe?.code ?? '?'),
        lignes: lignes.filter((l): l is string => !!l),
        domaine: s.domaine,
        conflit: this.enConflit().has(s.id),
        groupe: s.groupe,
      });
    }
    if (v === 'enseignant' && !cartes.length) {
      const pris = e.occupationsAilleurs.some((o) => o.engagementId === id && o.jour === jour && chevauche(o.heureDebut, o.heureFin, creneau.heureDebut, creneau.heureFin));
      if (pris) {
        cartes.push({ seance: null, titre: 'Autre établissement', lignes: [], domaine: null, conflit: false, groupe: null });
      }
    }
    return cartes;
  }

  protected peutPlacerIci(creneau: CreneauVue, jour: number): boolean {
    return this.vue() === 'classe' && this.placeur() && this.existe(creneau, jour) && this.matieresPlacables().length > 0;
  }

  protected choisirVue(v: Vue): void {
    this.vue.set(v);
    this.edition.set(null);
  }

  protected choisir(e: Event): void {
    const id = (e.target as HTMLSelectElement).value;
    this.choix.update((c) => ({ ...c, [this.vue()]: id }));
    this.edition.set(null);
  }

  protected choisirAnnee(e: Event): void {
    this.annees.choisir((e.target as HTMLSelectElement).value);
  }

  // ------------------------------------------------------------------ placement

  protected nouvelle(jour: number, creneau: CreneauVue): void {
    const reste = this.matieresPlacables().find((m) => m.minutesPlacees < m.minutesPrevues) ?? this.matieresPlacables()[0];
    this.message.set(null);
    this.enregistrement.erreur.set(null);
    this.edition.set({ seanceId: null, jour, creneauId: creneau.id, matiereId: reste?.matiereId ?? '', groupe: '', atelierId: '', salle: '' });
    this.proposerAtelier();
  }

  protected ouvrir(carte: Carte): void {
    const s = carte.seance;
    if (!s || this.vue() !== 'classe' || !s.modifiable) {
      return;
    }
    this.message.set(null);
    this.enregistrement.erreur.set(null);
    this.edition.set({ seanceId: s.id, jour: s.jour, creneauId: s.creneauId, matiereId: s.matiereId, groupe: s.groupe ?? '', atelierId: s.atelierId ?? '', salle: s.salle ?? '' });
  }

  protected modifierEdition(champ: keyof Edition, e: Event): void {
    const brut = (e.target as HTMLInputElement | HTMLSelectElement).value;
    const valeur = champ === 'jour' ? Number(brut) : brut;
    this.edition.update((ed) => (ed ? { ...ed, [champ]: valeur } : ed));
    if (champ === 'matiereId') {
      this.proposerAtelier();
    }
  }

  /** Séance pratique : l'atelier de la filière est proposé d'office. */
  private proposerAtelier(): void {
    const ed = this.edition();
    const m = this.matiereEditee();
    if (!ed || !m || ed.atelierId) {
      return;
    }
    if (m.type === 'PRATIQUE' || m.type === 'MODULE_COMPETENCES') {
      const a = this.ateliersProposes().find((x) => x.filieres.includes(this.classe()?.filiereId ?? ''));
      if (a) {
        this.edition.set({ ...ed, atelierId: a.id });
      }
    }
  }

  protected async enregistrer(): Promise<void> {
    const ed = this.edition();
    const e = this.emploi();
    const c = this.classe();
    if (!ed || !e || !c || this.erreurEdition()) {
      return;
    }
    const r = await this.enregistrement.executer(() =>
      this.api.placer(ed.seanceId ?? nouvelIdentifiant(), {
        anneeId: e.anneeId,
        classeId: c.id,
        matiereId: ed.matiereId,
        jour: ed.jour,
        creneauId: ed.creneauId,
        groupe: ed.groupe.trim() || null,
        atelierId: ed.atelierId || null,
        salle: ed.salle.trim() || null,
      }),
    );
    if (r) {
      this.emploi.set(r);
      this.edition.set(null);
      const creneau = r.creneaux.find((x) => x.id === ed.creneauId);
      this.message.set(`Séance ${ed.seanceId ? 'déplacée' : 'placée'} : ${JOURS[ed.jour].toLowerCase()}${creneau ? ' à ' + heure(creneau.heureDebut) : ''}.`);
    }
  }

  protected async retirer(): Promise<void> {
    const ed = this.edition();
    if (!ed?.seanceId) {
      return;
    }
    const r = await this.enregistrement.executer(() => this.api.retirer(ed.seanceId!));
    if (r) {
      this.emploi.set(r);
      this.edition.set(null);
      this.message.set('Séance retirée.');
    }
  }

  // ------------------------------------------------------------------ génération et publication

  protected basculerDomaine(d: Domaine): void {
    const tous = this.droits()?.domaines ?? [];
    this.domainesGeneration.update((l) => {
      const actuels = l.length ? l : tous;
      const suivants = actuels.includes(d) ? actuels.filter((x) => x !== d) : [...actuels, d];
      return suivants.length === tous.length ? [] : suivants;
    });
  }

  protected async generer(): Promise<void> {
    const e = this.emploi();
    if (!e) {
      return;
    }
    const classes = this.toutesLesClasses() || !this.classe() ? [] : [this.classe()!.id];
    if (this.remplacer() && !window.confirm('Vos séances déjà placées dans ces classes seront retirées puis recalculées. Continuer ?')) {
      return;
    }
    this.message.set(null);
    this.edition.set(null);
    const r = await this.enregistrement.executer(() =>
      this.api.generer(e.anneeId, { classes, domaines: this.domainesGeneration(), remplacer: this.remplacer() }),
    );
    if (r) {
      this.resultat.set(r);
      this.emploi.set(r.emploi);
      this.remplacer.set(false);
    }
  }

  protected async publier(): Promise<void> {
    const e = this.emploi();
    if (!e) {
      return;
    }
    const r = await this.enregistrement.executer(() => this.api.publier(e.anneeId));
    if (r) {
      this.emploi.set(r);
      this.message.set('Emploi du temps publié : chaque enseignant le trouve dans son espace.');
    }
  }

  protected async retirerPublication(): Promise<void> {
    const e = this.emploi();
    if (!e || !window.confirm('Les enseignants ne verront plus l’emploi du temps jusqu’à la prochaine publication. Continuer ?')) {
      return;
    }
    const r = await this.enregistrement.executer(() => this.api.retirerPublication(e.anneeId));
    if (r) {
      this.emploi.set(r);
      this.message.set('Publication retirée.');
    }
  }

  private async charger(anneeId: string): Promise<void> {
    this.edition.set(null);
    this.resultat.set(null);
    const r = await this.action.executer(() => this.api.emploi(anneeId));
    if (r) {
      this.emploi.set(r);
    }
  }
}
