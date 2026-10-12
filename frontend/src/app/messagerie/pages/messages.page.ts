import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Action } from '../../admin/action';
import { AdminApi } from '../../admin/admin-api.service';
import { AnneeCourante } from '../../admin/annee-courante.service';
import { ClasseVue, LIBELLE_ROLE } from '../../admin/modeles-admin';
import { Role } from '../../core/modeles';
import { dateHeureCourte } from '../../core/outils';
import { SessionService } from '../../core/session.service';
import {
  Apercu,
  clePersonne,
  CORPS_MAX,
  DemandeMessage,
  DestinataireVue,
  Groupe,
  LIBELLE_GROUPE,
  MessageEnvoye,
  MessageResume,
  MessagerieApi,
  MessageVue,
  OBJET_MAX,
  Personne,
  ROLES_CIBLES,
  segmentsSms,
} from '../messagerie-api.service';

type Onglet = 'recus' | 'ecrire' | 'envoyes';

const LIBELLE_CATEGORIE: Record<Personne['categorie'], string> = {
  ADMINISTRATION: 'Administration',
  ENSEIGNANT: 'Enseignant',
  PARENT: 'Parent',
  PARENT_SANS_COMPTE: 'Parent sans compte (SMS)',
};

/** Recherche dans l'annuaire : nom ou qualité (enfants, rôle), sans tenir compte des accents. */
export function chercherPersonnes(annuaire: Personne[], q: string, dejaChoisies: Set<string>, max = 8): Personne[] {
  const n = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
  const mots = n(q.trim()).split(/\s+/).filter(Boolean);
  if (!mots.length) {
    return [];
  }
  return annuaire
    .filter((p) => !dejaChoisies.has(clePersonne(p)))
    .filter((p) => mots.every((m) => n(`${p.nom} ${p.qualite}`).includes(m)))
    .slice(0, max);
}

/**
 * Messages (v0.46). Chacun lit ici les messages de l'établissement ; le chef d'établissement écrit au
 * personnel, aux parents, à une personne ou à un groupe, avec l'envoi par SMS en option, et suit la lecture.
 */
@Component({
  selector: 'app-messages',
  imports: [FormsModule],
  template: `
    <div class="page large">
      <h1>Messages</h1>

      @if (chef()) {
        <nav class="onglets" aria-label="Messagerie">
          <a href="" [class.actif]="onglet() === 'recus'" (click)="choisir('recus', $event)">Reçus@if (api.nonLus()) { ({{ api.nonLus() }}) }</a>
          <a href="" [class.actif]="onglet() === 'ecrire'" (click)="choisir('ecrire', $event)">Écrire</a>
          <a href="" [class.actif]="onglet() === 'envoyes'" (click)="choisir('envoyes', $event)">Envoyés</a>
        </nav>
      }

      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      <!-- Reçus -->
      @if (onglet() === 'recus') {
        @if (ouvert(); as m) {
          <article class="carte lecture" aria-label="Message">
            <div class="entete-section">
              <h2>{{ m.objet }}</h2>
              <button type="button" class="bouton discret petit" (click)="ouvert.set(null)">Fermer</button>
            </div>
            <p class="doux">{{ m.auteur }} · {{ dateHeure(m.envoyeLe) }}</p>
            <p class="corps">{{ m.corps }}</p>
          </article>
        }
        <section class="carte">
          @if (boite(); as b) {
            <ul class="liste messages">
              @for (r of b.messages; track r.id) {
                <li>
                  <button type="button" class="ligne-message" [class.non-lu]="!r.lu" (click)="ouvrir(r)">
                    <span class="objet">@if (!r.lu) { <span class="point" aria-label="non lu"></span> }{{ r.objet }}</span>
                    <span class="doux">{{ r.auteur }} · {{ dateHeure(r.envoyeLe) }}</span>
                    <span class="doux extrait">{{ r.extrait }}</span>
                  </button>
                </li>
              } @empty {
                <li class="doux">Aucun message.</li>
              }
            </ul>
            @if (b.total > b.messages.length + page() * TAILLE || page() > 0) {
              <div class="pagination">
                <button type="button" class="bouton secondaire petit" [disabled]="page() === 0" (click)="chargerBoite(page() - 1)">‹ Plus récents</button>
                <button type="button" class="bouton secondaire petit" [disabled]="(page() + 1) * TAILLE >= b.total" (click)="chargerBoite(page() + 1)">Plus anciens ›</button>
              </div>
            }
          }
        </section>
      }

      <!-- Écrire -->
      @if (onglet() === 'ecrire' && chef()) {
        <form class="carte" (ngSubmit)="preparerEnvoi()">
          <div class="champ">
            <label for="objet">Objet</label>
            <input id="objet" name="objet" [maxlength]="OBJET_MAX" required [(ngModel)]="objet" (ngModelChange)="confirmation.set(false)" />
          </div>
          <div class="champ">
            <label for="corps">Message <span class="doux">({{ corps().length }} / {{ CORPS_MAX }})</span></label>
            <textarea id="corps" name="corps" rows="6" [maxlength]="CORPS_MAX" required [(ngModel)]="corps" (ngModelChange)="confirmation.set(false)"></textarea>
          </div>

          <fieldset>
            <legend>Destinataires</legend>
            <div class="cases">
              @for (g of groupes; track g) {
                <label class="case"><input type="checkbox" [checked]="groupesChoisis().has(g)" (change)="basculerGroupe(g)" [attr.aria-label]="libelleGroupe[g]" /> {{ libelleGroupe[g] }}</label>
              }
            </div>
            @if (!groupesChoisis().has('PERSONNEL') && !groupesChoisis().has('ADMINISTRATION')) {
              <p class="doux sous-titre">Ou seulement certains rôles :</p>
              <div class="cases">
                @for (r of rolesCibles; track r) {
                  <label class="case"><input type="checkbox" [checked]="rolesChoisis().has(r)" (change)="basculerRole(r)" /> {{ libelleRole[r] }}</label>
                }
              </div>
            }
            @if (!groupesChoisis().has('PARENTS') && classes().length) {
              <p class="doux sous-titre">Ou les parents de certaines classes :</p>
              <div class="cases">
                @for (c of classes(); track c.id) {
                  <label class="case"><input type="checkbox" [checked]="classesChoisies().has(c.id)" (change)="basculerClasse(c.id)" /> {{ c.code }}</label>
                }
              </div>
            }
            <p class="doux sous-titre">Ou des personnes :</p>
            <input type="search" name="q" placeholder="Nom, rôle ou enfant (ex. « Awa 6e A »)" [(ngModel)]="q" aria-label="Chercher une personne" />
            @if (trouvees().length) {
              <ul class="suggestions">
                @for (p of trouvees(); track clePersonne(p)) {
                  <li><button type="button" class="bouton discret petit" (click)="ajouterPersonne(p)">+ {{ p.nom }} <span class="doux">· {{ p.qualite }}</span></button></li>
                }
              </ul>
            }
            @if (personnes().length) {
              <div class="puces">
                @for (p of personnes(); track clePersonne(p)) {
                  <span class="pastille">{{ p.nom }} <span class="doux">({{ categorie[p.categorie] }})</span>
                    <button type="button" class="retirer" [attr.aria-label]="'Retirer ' + p.nom" (click)="retirerPersonne(p)">×</button></span>
                }
              </div>
            }
          </fieldset>

          <label class="case sms">
            <input type="checkbox" name="sms" [(ngModel)]="sms" (ngModelChange)="majApercu()" />
            Envoyer aussi par SMS
            <span class="doux">(payant : environ {{ segments() }} SMS par personne ; seul moyen de joindre un parent sans compte)</span>
          </label>

          @if (apercu(); as a) {
            <p class="apercu" role="status">
              <strong>{{ a.destinataires }} destinataire(s)</strong> : {{ a.comptes }} dans l'application@if (a.smsSeulement) {, {{ a.smsSeulement }} par SMS seulement }.
              @if (a.sms) { {{ a.sms * segments() }} SMS partiront. }
              @if (a.injoignables) { <span class="rouge">{{ a.injoignables }} parent(s) sans compte ne seront pas joints : cochez l'envoi par SMS.</span> }
            </p>
          }

          @if (confirmation(); as a) {
            <div class="confirmation" role="alertdialog" aria-label="Confirmer l'envoi">
              <span>Envoyer « {{ objet().trim() }} » à {{ a.destinataires }} personne(s)@if (a.sms) { ({{ a.sms * segments() }} SMS) } ?</span>
              <button type="button" class="bouton secondaire petit" (click)="confirmation.set(false)">Annuler</button>
              <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="envoyer()">Confirmer l'envoi</button>
            </div>
          } @else {
            <button type="submit" class="bouton" [disabled]="action.enCours() || !objet().trim() || !corps().trim() || !cibleChoisie()">Envoyer</button>
          }
        </form>
      }

      <!-- Envoyés -->
      @if (onglet() === 'envoyes' && chef()) {
        <section class="carte">
          <div class="tableau-defilant">
            <table class="tableau">
              <thead><tr><th>Date</th><th>Objet</th><th>Destinataires</th><th class="nombre">Lus</th><th></th></tr></thead>
              <tbody>
                @for (e of envoyes(); track e.id) {
                  <tr>
                    <td>{{ dateHeure(e.envoyeLe) }}</td>
                    <td><strong>{{ e.objet }}</strong>@if (e.sms) { <span class="pastille">SMS</span> }<br /><span class="doux">{{ e.auteur }}</span></td>
                    <td>{{ e.cibles }}<br /><span class="doux">{{ e.destinataires }} personne(s)</span></td>
                    <td class="nombre">{{ e.lus }} / {{ e.comptes }}</td>
                    <td><button type="button" class="bouton discret petit" (click)="voirDestinataires(e)">{{ detail() === e.id ? 'Masquer' : 'Détail' }}</button></td>
                  </tr>
                  @if (detail() === e.id) {
                    <tr>
                      <td colspan="5">
                        <ul class="liste destinataires">
                          @for (d of destinataires(); track $index) {
                            <li>
                              <span>{{ d.nom }} <span class="doux">{{ d.qualite }}</span></span>
                              <span>
                                @if (d.luLe) { <span class="pastille present">lu le {{ dateHeure(d.luLe) }}</span> }
                                @else if (d.compte) { <span class="pastille">non lu</span> }
                                @if (d.sms) { <span class="pastille">SMS</span> }
                              </span>
                            </li>
                          }
                        </ul>
                      </td>
                    </tr>
                  }
                } @empty {
                  <tr><td colspan="5" class="doux">Aucun message envoyé.</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }
    </div>
  `,
  styles: `
    .onglets a {
      cursor: pointer;
    }
    .messages {
      list-style: none;
      padding: 0;
      margin: 0;
    }
    .ligne-message {
      appearance: none;
      width: 100%;
      text-align: left;
      background: none;
      border: 0;
      border-bottom: 1px solid var(--bordure);
      padding: 0.6rem 0.25rem;
      display: flex;
      flex-direction: column;
      gap: 0.15rem;
      cursor: pointer;
      color: inherit;
      font: inherit;
      &.non-lu .objet {
        font-weight: 700;
      }
    }
    .point {
      display: inline-block;
      width: 0.55rem;
      height: 0.55rem;
      border-radius: 50%;
      background: var(--primaire);
      margin-right: 0.4rem;
    }
    .extrait {
      font-size: 0.85rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .lecture .corps {
      white-space: pre-wrap;
    }
    fieldset {
      border: 1px solid var(--bordure);
      border-radius: 10px;
      padding: 0.75rem;
      margin: 0 0 0.75rem;
    }
    .cases {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem 1rem;
    }
    .case {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      input {
        width: auto;
        min-height: 0;
      }
    }
    .sous-titre {
      margin: 0.6rem 0 0.25rem;
      font-size: 0.85rem;
    }
    .suggestions {
      list-style: none;
      padding: 0;
      margin: 0.25rem 0;
    }
    .puces {
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem;
      margin-top: 0.5rem;
    }
    .retirer {
      border: 0;
      background: none;
      cursor: pointer;
      font-size: 1rem;
      color: inherit;
    }
    .sms {
      margin: 0.5rem 0;
      flex-wrap: wrap;
    }
    .apercu {
      margin: 0.5rem 0 0.75rem;
    }
    .rouge {
      color: var(--absent);
    }
    .confirmation {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
    }
    .destinataires li {
      display: flex;
      justify-content: space-between;
      gap: 0.5rem;
    }
    .pagination {
      display: flex;
      justify-content: space-between;
      margin-top: 0.75rem;
    }
  `,
})
export class MessagesPage implements OnInit {
  protected readonly api = inject(MessagerieApi);
  private readonly admin = inject(AdminApi);
  private readonly session = inject(SessionService);
  private readonly anneeCourante = inject(AnneeCourante);

  protected readonly TAILLE = 20;
  protected readonly OBJET_MAX = OBJET_MAX;
  protected readonly CORPS_MAX = CORPS_MAX;
  protected readonly groupes: Groupe[] = ['PERSONNEL', 'ENSEIGNANTS', 'ADMINISTRATION', 'PARENTS'];
  protected readonly libelleGroupe = LIBELLE_GROUPE;
  protected readonly rolesCibles = ROLES_CIBLES;
  protected readonly libelleRole = LIBELLE_ROLE;
  protected readonly categorie = LIBELLE_CATEGORIE;
  protected readonly clePersonne = clePersonne;
  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly chef = computed(() => this.session.aLeRole('ADMIN_ECOLE'));
  protected readonly onglet = signal<Onglet>('recus');

  // Reçus
  protected readonly boite = signal<{ messages: MessageResume[]; total: number } | null>(null);
  protected readonly page = signal(0);
  protected readonly ouvert = signal<MessageVue | null>(null);

  // Écrire
  protected readonly objet = signal('');
  protected readonly corps = signal('');
  protected sms = false;
  protected readonly groupesChoisis = signal<Set<Groupe>>(new Set());
  protected readonly rolesChoisis = signal<Set<Role>>(new Set());
  protected readonly classesChoisies = signal<Set<string>>(new Set());
  protected readonly personnes = signal<Personne[]>([]);
  protected readonly classes = signal<ClasseVue[]>([]);
  private readonly annuaire = signal<Personne[]>([]);
  protected readonly q = signal('');
  protected readonly apercu = signal<Apercu | null>(null);
  protected readonly confirmation = signal<Apercu | false>(false);
  protected readonly trouvees = computed(() =>
    chercherPersonnes(this.annuaire(), this.q(), new Set(this.personnes().map(clePersonne))),
  );
  protected readonly cibleChoisie = computed(
    () => this.groupesChoisis().size + this.rolesChoisis().size + this.classesChoisies().size + this.personnes().length > 0,
  );
  protected readonly segments = computed(() =>
    segmentsSms(`${this.session.profil()?.etablissement?.nom ?? ''} : ${this.objet().trim()}. ${this.corps().trim()}`),
  );

  // Envoyés
  protected readonly envoyes = signal<MessageEnvoye[]>([]);
  protected readonly detail = signal<string | null>(null);
  protected readonly destinataires = signal<DestinataireVue[]>([]);

  async ngOnInit(): Promise<void> {
    await this.chargerBoite(0);
  }

  protected dateHeure(iso: string): string {
    return dateHeureCourte(iso);
  }

  protected async choisir(o: Onglet, e?: Event): Promise<void> {
    e?.preventDefault();
    this.onglet.set(o);
    this.message.set(null);
    this.action.erreur.set(null);
    if (o === 'recus') {
      await this.chargerBoite(0);
    } else if (o === 'ecrire') {
      await this.preparerEcriture();
    } else {
      const l = await this.action.executer(() => this.api.envoyes());
      if (l) {
        this.envoyes.set(l);
      }
    }
  }

  // ---------- Reçus

  protected async chargerBoite(page: number): Promise<void> {
    const b = await this.action.executer(() => this.api.boite(page, this.TAILLE));
    if (b) {
      this.page.set(page);
      this.boite.set(b);
      this.api.nonLus.set(b.nonLus);
    }
  }

  protected async ouvrir(r: MessageResume): Promise<void> {
    const m = await this.action.executer(() => this.api.lire(r.id));
    if (!m) {
      return;
    }
    this.ouvert.set(m);
    const b = this.boite();
    if (b && !r.lu) {
      this.boite.set({ ...b, messages: b.messages.map((x) => (x.id === r.id ? { ...x, lu: true } : x)) });
      this.api.nonLus.set(Math.max(0, this.api.nonLus() - 1));
    }
    window.scrollTo?.({ top: 0, behavior: 'smooth' });
  }

  // ---------- Écrire

  private async preparerEcriture(): Promise<void> {
    if (this.annuaire().length && this.classes().length) {
      return;
    }
    await this.action.executer(async () => {
      await this.anneeCourante.charger().catch(() => undefined);
      const annee = this.anneeCourante.annees().find((a) => a.etat === 'ACTIVE');
      const [annuaire, classes] = await Promise.all([
        this.api.annuaire(),
        annee ? this.admin.classes(annee.id) : Promise.resolve([] as ClasseVue[]),
      ]);
      this.annuaire.set(annuaire);
      this.classes.set([...classes].sort((a, b) => a.code.localeCompare(b.code, 'fr')));
    });
  }

  protected basculerGroupe(g: Groupe): void {
    this.groupesChoisis.set(basculer(this.groupesChoisis(), g));
    void this.majApercu();
  }

  protected basculerRole(r: Role): void {
    this.rolesChoisis.set(basculer(this.rolesChoisis(), r));
    void this.majApercu();
  }

  protected basculerClasse(id: string): void {
    this.classesChoisies.set(basculer(this.classesChoisies(), id));
    void this.majApercu();
  }

  protected ajouterPersonne(p: Personne): void {
    this.personnes.set([...this.personnes(), p]);
    this.q.set('');
    void this.majApercu();
  }

  protected retirerPersonne(p: Personne): void {
    this.personnes.set(this.personnes().filter((x) => clePersonne(x) !== clePersonne(p)));
    void this.majApercu();
  }

  private demande(): DemandeMessage {
    const groupes = [...this.groupesChoisis()];
    return {
      objet: this.objet().trim(),
      corps: this.corps().trim(),
      sms: this.sms,
      groupes,
      roles: groupes.includes('PERSONNEL') || groupes.includes('ADMINISTRATION') ? [] : [...this.rolesChoisis()],
      classes: groupes.includes('PARENTS') ? [] : [...this.classesChoisies()],
      utilisateurs: this.personnes().filter((p) => p.utilisateurId).map((p) => p.utilisateurId!),
      responsables: this.personnes().filter((p) => !p.utilisateurId && p.responsableId).map((p) => p.responsableId!),
    };
  }

  /** Recompte les destinataires à chaque changement de cible ou de l'option SMS. */
  protected async majApercu(): Promise<void> {
    this.confirmation.set(false);
    if (!this.cibleChoisie()) {
      this.apercu.set(null);
      return;
    }
    const a = await this.action.executer(() => this.api.apercu(this.demande()));
    this.apercu.set(a ?? null);
  }

  protected async preparerEnvoi(): Promise<void> {
    const a = await this.action.executer(() => this.api.apercu(this.demande()));
    if (a) {
      this.apercu.set(a);
      this.confirmation.set(a);
    }
  }

  protected async envoyer(): Promise<void> {
    const r = await this.action.executer(() => this.api.envoyer(this.demande()));
    this.confirmation.set(false);
    if (!r) {
      return;
    }
    this.message.set(
      `Message envoyé à ${r.destinataires} personne(s)` + (r.sms ? `, dont ${r.sms} par SMS` : '') + '. Suivez la lecture dans « Envoyés ».',
    );
    this.objet.set('');
    this.corps.set('');
    this.sms = false;
    this.groupesChoisis.set(new Set());
    this.rolesChoisis.set(new Set());
    this.classesChoisies.set(new Set());
    this.personnes.set([]);
    this.apercu.set(null);
  }

  // ---------- Envoyés

  protected async voirDestinataires(e: MessageEnvoye): Promise<void> {
    if (this.detail() === e.id) {
      this.detail.set(null);
      return;
    }
    const l = await this.action.executer(() => this.api.destinataires(e.id));
    if (l) {
      this.destinataires.set(l);
      this.detail.set(e.id);
    }
  }
}

function basculer<T>(s: Set<T>, v: T): Set<T> {
  const r = new Set(s);
  if (r.has(v)) {
    r.delete(v);
  } else {
    r.add(v);
  }
  return r;
}
