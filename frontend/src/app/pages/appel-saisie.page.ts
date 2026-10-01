import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { messageErreur } from '../core/erreurs';
import { Affectation } from '../core/modeles';
import { ajouterMinutes, dateLocale, dateLongue } from '../core/outils';
import { filtrer } from '../core/recherche';
import { EnvoisService } from '../hors-ligne/envois.service';
import { ListesService } from '../hors-ligne/listes.service';
import { RechercheComponent } from '../partage/recherche.component';
import { CHOIX_MINUTES, FeuilleAppel } from './feuille-appel';

@Component({
  selector: 'app-appel-saisie',
  imports: [FormsModule, RouterLink, RechercheComponent],
  templateUrl: './appel-saisie.page.html',
  styleUrl: './appel-saisie.page.scss',
})
export class AppelSaisiePage implements OnInit {
  /** Paramètres de l'adresse /appel/:classeId/:matiereId */
  readonly classeId = input.required<string>();
  readonly matiereId = input.required<string>();

  private readonly listes = inject(ListesService);
  private readonly envois = inject(EnvoisService);
  private readonly router = inject(Router);

  protected readonly choixMinutes = CHOIX_MINUTES;
  protected readonly aujourdhui = dateLocale();
  protected readonly dateLongue = dateLongue;

  protected readonly affectation = signal<Affectation | null>(null);
  protected readonly feuille = signal<FeuilleAppel | null>(null);
  protected readonly chargement = signal(true);
  protected readonly erreur = signal<string | null>(null);
  protected readonly recapitulatif = signal(false);
  protected readonly enregistrement = signal(false);
  protected readonly filtre = signal('');

  protected readonly date = signal(dateLocale());
  protected readonly heureDebut = signal(heureRonde());
  protected readonly heureFin = signal(ajouterMinutes(heureRonde(), 60));

  protected readonly elevesAffiches = computed(() => {
    const f = this.feuille();
    const eleves = f?.eleves.map((e, i) => ({ ...e, rang: i + 1 })) ?? [];
    return filtrer(eleves, this.filtre(), (e) => [e.nom, e.prenoms, e.matricule, e.rang]);
  });

  protected readonly horaireInvalide = computed(() => !(this.heureFin() > this.heureDebut()));
  protected readonly dateInvalide = computed(() => !this.date() || this.date() > this.aujourdhui);

  /** Appel déjà saisi sur ce téléphone pour la même classe, la même matière et un créneau qui chevauche. */
  protected readonly doublon = computed(() =>
    this.envois
      .envois()
      .find(
        (e) =>
          e.etat !== 'REFUSE' &&
          e.appel.classeId === this.classeId() &&
          e.appel.matiereId === this.matiereId() &&
          e.appel.date === this.date() &&
          e.appel.heureDebut < this.heureFin() &&
          this.heureDebut() < e.appel.heureFin,
      ),
  );

  async ngOnInit(): Promise<void> {
    try {
      const affectations = await this.listes.affectations();
      const a = affectations?.affectations.find(
        (x) => x.classeId === this.classeId() && x.matiereId === this.matiereId(),
      );
      if (!a) {
        this.erreur.set("Cette classe et cette matière ne font pas partie de vos affectations sur ce téléphone.");
        return;
      }
      this.affectation.set(a);
      const classe = await this.listes.eleves(this.classeId());
      if (!classe) {
        this.erreur.set(
          "La liste de cette classe n'est pas sur ce téléphone. Connectez-vous au réseau puis mettez à jour les listes.",
        );
        return;
      }
      this.feuille.set(new FeuilleAppel(classe.eleves));
    } catch (e) {
      this.erreur.set(messageErreur(e));
    } finally {
      this.chargement.set(false);
    }
  }

  protected duree(minutes: number): void {
    this.heureFin.set(ajouterMinutes(this.heureDebut(), minutes));
  }

  protected async enregistrer(): Promise<void> {
    const f = this.feuille();
    const a = this.affectation();
    if (!f || !a || this.horaireInvalide() || this.dateInvalide()) {
      return;
    }
    this.enregistrement.set(true);
    this.erreur.set(null);
    try {
      await this.envois.ajouter(
        {
          classeId: a.classeId,
          matiereId: a.matiereId,
          date: this.date(),
          heureDebut: this.heureDebut(),
          heureFin: this.heureFin(),
          marques: f.marques(),
        },
        { classeCode: a.classeCode, matiereLibelle: a.matiereLibelle },
      );
      await this.router.navigate(['/envois'], { state: { vientDeSaisir: true } });
    } catch (e) {
      this.erreur.set(messageErreur(e, "L'appel n'a pas pu être enregistré sur le téléphone."));
    } finally {
      this.enregistrement.set(false);
    }
  }
}

/** Heure pleine en cours (08:37 → 08:00) : les séances commencent en général à l'heure. */
function heureRonde(): string {
  const d = new Date();
  return `${String(d.getHours()).padStart(2, '0')}:00`;
}
