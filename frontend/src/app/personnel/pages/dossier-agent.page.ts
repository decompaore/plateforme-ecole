import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Action } from '../../admin/action';
import { dateCourte, dateHeureCourte } from '../../core/outils';
import { enregistrerFichier } from '../../scolarite/scolarite-api.service';
import {
  AbsenceVue, ActeVue, CATEGORIES, CategorieAgent, DonneesAgent, donnees, DossierVue, NatureDiplome, NaturePiece,
  NATURES_PIECE, PieceVue, SITUATIONS, Situation, STATUTS_ABSENCE, TypeAbsence, TypeActe, TYPES_ABSENCE, TYPES_ACTE,
} from '../modeles-personnel';
import { PersonnelApi } from '../personnel-api.service';

type Section = 'identite' | 'diplomes' | 'pieces' | 'actes' | 'absences' | 'signalements';

/**
 * Dossier d'un agent (v0.51). Pour la direction et le secrétariat : identité et carrière, ancienneté, diplômes,
 * pièces classées, actes (préparés par le secrétariat, signés par le chef d'établissement), congés et absences,
 * signalements. Pour l'agent (« Mon dossier », donnée de route {@code mien}) : consultation, téléchargement de
 * ses actes et pièces, demande d'absence, signalement d'une erreur.
 */
@Component({
  selector: 'app-dossier-agent',
  imports: [FormsModule, RouterLink],
  template: `
    <div class="page large">
      @if (!mien()) { <a routerLink="/personnel" class="bouton discret">‹ Dossiers du personnel</a> }

      @if (action.erreur(); as e) {
        <div class="alerte erreur" role="alert">{{ e }}</div>
      }
      @if (message(); as m) {
        <div class="alerte succes" role="status">{{ m }}</div>
      }

      @if (dossier(); as d) {
        <div class="entete-section">
          <h1>{{ mien() ? 'Mon dossier' : d.nom + ' ' + d.prenoms }}</h1>
          <span class="doux">{{ categories[d.categorie] }}@if (d.fonction) { · {{ d.fonction }} }@if (d.dateCessation) { · parti(e) le {{ dateCourte(d.dateCessation) }} }</span>
        </div>

        <div class="chiffres">
          <div><strong>{{ d.anciennete.etablissement?.texte ?? '—' }}</strong><span>dans l'établissement</span></div>
          <div><strong>{{ d.anciennete.fonction?.texte ?? '—' }}</strong><span>dans la fonction</span></div>
          <div><strong>{{ d.anciennete.grade?.texte ?? '—' }}</strong><span>dans le grade</span></div>
        </div>
        @if (d.manquants.length && !mien()) {
          <div class="alerte attention">À compléter : {{ d.manquants.join(', ') }}.</div>
        }

        <div class="bascule" role="group" aria-label="Rubrique">
          <button type="button" [class.actif]="section() === 'identite'" (click)="section.set('identite')">Identité et carrière</button>
          <button type="button" [class.actif]="section() === 'diplomes'" (click)="section.set('diplomes')">Diplômes ({{ d.diplomes.length }})</button>
          <button type="button" [class.actif]="section() === 'pieces'" (click)="section.set('pieces')">Pièces ({{ d.pieces.length }})</button>
          <button type="button" [class.actif]="section() === 'actes'" (click)="section.set('actes')">Actes ({{ d.actes.length }})</button>
          <button type="button" [class.actif]="section() === 'absences'" (click)="section.set('absences')">Congés et absences ({{ d.absences.length }})</button>
          <button type="button" [class.actif]="section() === 'signalements'" (click)="section.set('signalements')">Signalements ({{ signalementsOuverts() }})</button>
        </div>

        @switch (section()) {
          @case ('identite') {
            @if (edition(); as f) {
              <form class="carte" (ngSubmit)="enregistrer()">
                <h2>Identité</h2>
                <div class="grille-champs">
                  <div class="champ"><label for="fCat">Catégorie</label>
                    <select id="fCat" name="fCat" [(ngModel)]="f.categorie">@for (c of listeCategories; track c[0]) { <option [value]="c[0]">{{ c[1] }}</option> }</select></div>
                  <div class="champ"><label for="fNom">Nom</label><input id="fNom" name="fNom" required maxlength="80" [(ngModel)]="f.nom" /></div>
                  <div class="champ"><label for="fPrenoms">Prénoms</label><input id="fPrenoms" name="fPrenoms" required maxlength="120" [(ngModel)]="f.prenoms" /></div>
                  <div class="champ"><label for="fSexe">Sexe</label>
                    <select id="fSexe" name="fSexe" [(ngModel)]="f.sexe"><option value="M">Homme</option><option value="F">Femme</option></select></div>
                  <div class="champ"><label for="fNaiss">Date de naissance</label><input id="fNaiss" name="fNaiss" type="date" [(ngModel)]="f.dateNaissance" /></div>
                  <div class="champ"><label for="fLieu">Lieu de naissance</label><input id="fLieu" name="fLieu" maxlength="100" [(ngModel)]="f.lieuNaissance" /></div>
                  <div class="champ"><label for="fTel">Téléphone</label><input id="fTel" name="fTel" maxlength="20" [(ngModel)]="f.telephone" /></div>
                  <div class="champ"><label for="fAdr">Adresse</label><input id="fAdr" name="fAdr" maxlength="200" [(ngModel)]="f.adresse" /></div>
                </div>
                <h2>Situation administrative</h2>
                <div class="grille-champs">
                  <div class="champ"><label for="fSit">Situation</label>
                    <select id="fSit" name="fSit" [(ngModel)]="f.situation"><option [ngValue]="null">—</option>@for (s of listeSituations; track s[0]) { <option [ngValue]="s[0]">{{ s[1] }}</option> }</select></div>
                  <div class="champ"><label for="fMat">Matricule</label><input id="fMat" name="fMat" maxlength="30" [(ngModel)]="f.matricule" /></div>
                  <div class="champ"><label for="fEmploi">Emploi ou corps</label><input id="fEmploi" name="fEmploi" maxlength="120" placeholder="Professeur certifié des lycées et collèges" [(ngModel)]="f.emploi" /></div>
                  <div class="champ"><label for="fGrade">Grade</label><input id="fGrade" name="fGrade" maxlength="60" [(ngModel)]="f.grade" /></div>
                  <div class="champ"><label for="fCatEch">Catégorie, échelle</label><input id="fCatEch" name="fCatEch" maxlength="20" placeholder="A1" [(ngModel)]="f.categorieEchelle" /></div>
                  <div class="champ"><label for="fEch">Échelon</label><input id="fEch" name="fEch" maxlength="10" [(ngModel)]="f.echelon" /></div>
                  <div class="champ"><label for="fFonction">Fonction dans l'établissement</label><input id="fFonction" name="fFonction" maxlength="120" [(ngModel)]="f.fonction" /></div>
                </div>
                <h2>Service</h2>
                <div class="grille-champs">
                  <div class="champ"><label for="fPremiere">Première prise de service (entrée dans la fonction)</label><input id="fPremiere" name="fPremiere" type="date" [(ngModel)]="f.datePremierePriseService" /></div>
                  <div class="champ"><label for="fGradeDate">Entrée dans le grade</label><input id="fGradeDate" name="fGradeDate" type="date" [(ngModel)]="f.dateEntreeGrade" /></div>
                  <div class="champ"><label for="fPrise">Prise de service dans l'établissement</label><input id="fPrise" name="fPrise" type="date" [(ngModel)]="f.datePriseService" /></div>
                  <div class="champ"><label for="fCess">Cessation de service</label><input id="fCess" name="fCess" type="date" [(ngModel)]="f.dateCessation" /></div>
                  <div class="champ"><label for="fMotif">Motif de la cessation</label><input id="fMotif" name="fMotif" maxlength="200" placeholder="Mutation, retraite, fin de contrat…" [(ngModel)]="f.motifCessation" /></div>
                </div>
                <div class="champ"><label for="fObs">Observations (internes, non visibles par l'agent)</label><textarea id="fObs" name="fObs" rows="3" maxlength="1000" [(ngModel)]="f.observations"></textarea></div>
                <span class="actions-ligne">
                  <button type="submit" class="bouton" [disabled]="action.enCours()">Enregistrer</button>
                  <button type="button" class="bouton discret" (click)="edition.set(null)">Annuler</button>
                </span>
              </form>
            } @else {
              <section class="carte">
                <dl class="fiche">
                  <dt>Nom et prénoms</dt><dd>{{ d.nom }} {{ d.prenoms }} ({{ d.sexe === 'F' ? 'femme' : 'homme' }})</dd>
                  <dt>Naissance</dt><dd>{{ d.dateNaissance ? dateCourte(d.dateNaissance) : '—' }}@if (d.lieuNaissance) { à {{ d.lieuNaissance }} }</dd>
                  <dt>Téléphone</dt><dd>{{ d.telephone ?? '—' }}</dd>
                  <dt>Matricule</dt><dd>{{ d.matricule ?? '—' }}</dd>
                  <dt>Situation</dt><dd>{{ d.situation ? situations[d.situation] : '—' }}</dd>
                  <dt>Emploi, grade</dt><dd>{{ [d.emploi, d.grade].filter(estRempli).join(' · ') || '—' }}</dd>
                  <dt>Catégorie, échelon</dt><dd>{{ d.categorieEchelle ?? '—' }}@if (d.echelon) { · échelon {{ d.echelon }} }</dd>
                  <dt>Fonction</dt><dd>{{ d.fonction ?? '—' }}</dd>
                  <dt>Entrée dans la fonction</dt><dd>{{ d.datePremierePriseService ? dateCourte(d.datePremierePriseService) : '—' }}</dd>
                  <dt>Entrée dans le grade</dt><dd>{{ d.dateEntreeGrade ? dateCourte(d.dateEntreeGrade) : '—' }}</dd>
                  <dt>Prise de service ici</dt><dd>{{ d.datePriseService ? dateCourte(d.datePriseService) : '—' }}</dd>
                  @if (d.dateCessation) { <dt>Cessation</dt><dd>{{ dateCourte(d.dateCessation) }}@if (d.motifCessation) { · {{ d.motifCessation }} }</dd> }
                  @if (d.observations) { <dt>Observations</dt><dd>{{ d.observations }}</dd> }
                </dl>
                @if (d.droits.modifier) {
                  <button type="button" class="bouton secondaire petit" (click)="modifier(d)">Modifier</button>
                }
                @if (mien()) {
                  <p class="doux">Une information est inexacte ? Signalez-la dans « Signalements » : le secrétariat la corrige au vu des pièces.</p>
                }
              </section>
            }
          }

          @case ('diplomes') {
            <section class="carte">
              <ul class="liste">
                @for (x of d.diplomes; track x.id) {
                  <li>
                    <span><strong>{{ x.intitule }}</strong>@if (x.specialite) { · {{ x.specialite }} }<br />
                      <span class="doux">{{ x.nature === 'ACADEMIQUE' ? 'Académique' : 'Professionnel' }}@if (x.delivrePar) { · {{ x.delivrePar }} }@if (x.annee) { · {{ x.annee }} }</span></span>
                    <span class="actions-ligne">
                      @if (x.pieceId) { <button type="button" class="bouton discret petit" (click)="ouvrirPiece(x.pieceId, x.intitule)">Copie</button> }
                      @if (d.droits.modifier) { <button type="button" class="bouton discret petit" (click)="retirerDiplome(x.id)">Retirer</button> }
                    </span>
                  </li>
                } @empty { <li class="doux">Aucun diplôme enregistré.</li> }
              </ul>
              @if (d.droits.modifier) {
                <form class="sous-formulaire" (ngSubmit)="ajouterDiplome()">
                  <div class="grille-champs">
                    <div class="champ"><label for="dNature">Nature</label>
                      <select id="dNature" name="dNature" [(ngModel)]="dNature"><option value="ACADEMIQUE">Académique</option><option value="PROFESSIONNEL">Professionnel</option></select></div>
                    <div class="champ"><label for="dIntitule">Intitulé</label><input id="dIntitule" name="dIntitule" maxlength="150" placeholder="CAPES, Licence, BEP…" [(ngModel)]="dIntitule" /></div>
                    <div class="champ"><label for="dSpec">Spécialité</label><input id="dSpec" name="dSpec" maxlength="120" [(ngModel)]="dSpecialite" /></div>
                    <div class="champ"><label for="dPar">Délivré par</label><input id="dPar" name="dPar" maxlength="150" [(ngModel)]="dDelivrePar" /></div>
                    <div class="champ"><label for="dAnnee">Année</label><input id="dAnnee" name="dAnnee" type="number" min="1950" max="2100" [(ngModel)]="dAnnee" /></div>
                    <div class="champ"><label for="dPiece">Copie classée</label>
                      <select id="dPiece" name="dPiece" [(ngModel)]="dPiece"><option [ngValue]="null">—</option>@for (p of d.pieces; track p.id) { <option [ngValue]="p.id">{{ p.objet }}</option> }</select></div>
                  </div>
                  <button type="submit" class="bouton secondaire" [disabled]="action.enCours() || !dIntitule().trim()">Ajouter le diplôme</button>
                </form>
              }
            </section>
          }

          @case ('pieces') {
            <section class="carte">
              <ul class="liste">
                @for (p of d.pieces; track p.id) {
                  <li>
                    <span><strong>{{ p.objet }}</strong> · {{ naturesPiece[p.nature] }}@if (p.reference) { · {{ p.reference }} }<br />
                      <span class="doux">@if (p.datePiece) { du {{ dateCourte(p.datePiece) }} · }{{ p.nomFichier }} ({{ ko(p.taille) }})</span></span>
                    <span class="actions-ligne">
                      <button type="button" class="bouton discret petit" (click)="ouvrirPiece(p.id, p.nomFichier)">Ouvrir</button>
                      @if (d.droits.pieces) { <button type="button" class="bouton discret petit" (click)="retirerPiece(p)">Retirer</button> }
                    </span>
                  </li>
                } @empty { <li class="doux">Aucune pièce classée.</li> }
              </ul>
              @if (d.droits.pieces) {
                <form class="sous-formulaire" (ngSubmit)="deposer()">
                  <div class="grille-champs">
                    <div class="champ"><label for="pNature">Nature</label>
                      <select id="pNature" name="pNature" [(ngModel)]="pNature">@for (n of listeNatures; track n[0]) { <option [value]="n[0]">{{ n[1] }}</option> }</select></div>
                    <div class="champ"><label for="pObjet">Objet</label><input id="pObjet" name="pObjet" maxlength="200" placeholder="Arrêté d'affectation, CNIB…" [(ngModel)]="pObjet" /></div>
                    <div class="champ"><label for="pRef">Référence</label><input id="pRef" name="pRef" maxlength="80" placeholder="N° 2024-0457/MENAPLN/…" [(ngModel)]="pReference" /></div>
                    <div class="champ"><label for="pDate">Date de la pièce</label><input id="pDate" name="pDate" type="date" [(ngModel)]="pDate" /></div>
                    <div class="champ"><label for="pFichier">Fichier (PDF, JPEG, PNG ; 5 Mo)</label><input id="pFichier" type="file" accept=".pdf,image/jpeg,image/png" (change)="choisir($event)" /></div>
                  </div>
                  <button type="submit" class="bouton secondaire" [disabled]="action.enCours() || !pObjet().trim() || !fichier()">Classer la pièce</button>
                </form>
              }
            </section>
          }

          @case ('actes') {
            <section class="carte">
              <ul class="liste">
                @for (a of d.actes; track a.id) {
                  <li>
                    <span><strong>{{ a.titre }}</strong> · {{ a.numero ?? 'projet' }}<br />
                      <span class="doux">
                        @if (a.signeLe) { signé le {{ dateCourte(a.signeLe) }}@if (a.signataire) { par {{ a.signataire }} } } @else { préparé le {{ dateCourte(a.etabliLe) }}@if (a.etabliPar) { par {{ a.etabliPar }} }, à signer }
                      </span>
                      @if (a.annuleLe) { <br /><span class="pastille absent">Annulé : {{ a.motifAnnulation }}</span> }
                    </span>
                    <span class="actions-ligne">
                      <button type="button" class="bouton discret petit" (click)="pdf(a)">PDF</button>
                      @if (d.droits.signer && !a.signeLe && !a.annuleLe) { <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="signer(a)">Signer</button> }
                      @if (d.droits.signer && a.signeLe && !a.annuleLe) { <button type="button" class="bouton discret petit" (click)="annulerActe(a)">Annuler</button> }
                    </span>
                  </li>
                } @empty { <li class="doux">Aucun acte.</li> }
              </ul>
              @if (d.droits.actes) {
                <form class="sous-formulaire" (ngSubmit)="etablir()">
                  <div class="grille-champs">
                    <div class="champ"><label for="aType">Acte</label>
                      <select id="aType" name="aType" [(ngModel)]="aType">@for (t of typesEtablis; track t[0]) { <option [value]="t[0]">{{ t[1] }}</option> }</select></div>
                    @if (aType() === 'PRISE_SERVICE' || aType() === 'CESSATION_SERVICE') {
                      <div class="champ"><label for="aDate">Date d'effet</label><input id="aDate" name="aDate" type="date" [(ngModel)]="aDate" /></div>
                      <div class="champ"><label for="aComp">{{ aType() === 'PRISE_SERVICE' ? "Acte d'affectation (facultatif)" : 'Motif de la cessation' }}</label>
                        <input id="aComp" name="aComp" maxlength="300" [placeholder]="aType() === 'PRISE_SERVICE' ? 'arrêté n° …' : 'mutation, retraite…'" [(ngModel)]="aComplement" /></div>
                    }
                  </div>
                  <p class="doux">{{ d.droits.signer ? 'Établi par vous, l\\'acte est signé et numéroté aussitôt.' : 'L\\'acte est préparé ; le chef d\\'établissement le signe, ce qui lui donne son numéro.' }}</p>
                  <button type="submit" class="bouton secondaire" [disabled]="action.enCours()">{{ d.droits.signer ? 'Établir et signer' : 'Préparer' }}</button>
                </form>
              }
            </section>
          }

          @case ('absences') {
            <section class="carte">
              <ul class="liste">
                @for (b of d.absences; track b.id) {
                  <li>
                    <span><strong>{{ typesAbsence[b.type] }}</strong> · du {{ dateCourte(b.debut) }} au {{ dateCourte(b.fin) }} ({{ b.jours }} j)<br />
                      <span class="doux">{{ b.motif }}@if (b.reponse) { · {{ b.reponse }} }</span></span>
                    <span class="actions-ligne">
                      <span class="pastille" [class.present]="b.statut === 'ACCORDEE'" [class.absent]="b.statut === 'REFUSEE' || b.statut === 'CONSTATEE'">{{ statutsAbsence[b.statut] }}</span>
                      @if (d.droits.decider && b.statut === 'DEMANDEE') {
                        <button type="button" class="bouton petit" [disabled]="action.enCours()" (click)="decider(b, true)">Accorder</button>
                        <button type="button" class="bouton discret petit" [disabled]="action.enCours()" (click)="decider(b, false)">Refuser</button>
                      }
                      @if (b.statut === 'DEMANDEE' && (mien() || d.droits.modifier)) { <button type="button" class="bouton discret petit" (click)="retirerDemande(b)">Retirer</button> }
                      @if (b.acteId) { <button type="button" class="bouton discret petit" (click)="pdfId(b.acteId, 'autorisation')">Autorisation</button> }
                    </span>
                  </li>
                } @empty { <li class="doux">Aucune absence.</li> }
              </ul>
              @if (mien() || d.droits.modifier) {
                <form class="sous-formulaire" (ngSubmit)="demander()">
                  <h2>{{ mien() ? 'Demander une absence' : 'Enregistrer une absence' }}</h2>
                  <div class="grille-champs">
                    <div class="champ"><label for="bType">Type</label>
                      <select id="bType" name="bType" [(ngModel)]="bType">@for (t of typesDemandables(); track t[0]) { <option [value]="t[0]">{{ t[1] }}</option> }</select></div>
                    <div class="champ"><label for="bDebut">Du</label><input id="bDebut" name="bDebut" type="date" [(ngModel)]="bDebut" /></div>
                    <div class="champ"><label for="bFin">Au (inclus)</label><input id="bFin" name="bFin" type="date" [(ngModel)]="bFin" /></div>
                    <div class="champ"><label for="bMotif">Motif</label><input id="bMotif" name="bMotif" maxlength="400" [(ngModel)]="bMotif" /></div>
                  </div>
                  <button type="submit" class="bouton secondaire" [disabled]="action.enCours() || !bDebut() || !bFin() || !bMotif().trim()">
                    {{ mien() ? 'Envoyer la demande au chef d\\'établissement' : 'Enregistrer' }}</button>
                </form>
              }
            </section>
          }

          @case ('signalements') {
            <section class="carte">
              <ul class="liste">
                @for (s of d.signalements; track s.id) {
                  <li>
                    <span>{{ s.texte }}<br /><span class="doux">{{ s.signalePar ?? '' }} · {{ dateHeureCourte(s.signaleLe) }}</span>
                      @if (s.traiteLe) { <br /><span class="pastille present">Traité</span> {{ s.reponse }} }</span>
                    @if (!s.traiteLe && d.droits.modifier) {
                      <span class="actions-ligne">
                        <input [attr.aria-label]="'Réponse au signalement'" maxlength="600" placeholder="Réponse" [(ngModel)]="reponses[s.id]" [ngModelOptions]="{ standalone: true }" />
                        <button type="button" class="bouton petit" [disabled]="action.enCours() || !reponses[s.id]?.trim()" (click)="traiter(s.id)">Traité</button>
                      </span>
                    }
                  </li>
                } @empty { <li class="doux">Aucun signalement.</li> }
              </ul>
              @if (mien()) {
                <form class="sous-formulaire" (ngSubmit)="signaler()">
                  <div class="champ"><label for="sTexte">Signaler une erreur dans mon dossier</label>
                    <textarea id="sTexte" name="sTexte" rows="3" maxlength="1000" [(ngModel)]="sTexte"></textarea></div>
                  <button type="submit" class="bouton secondaire" [disabled]="action.enCours() || !sTexte().trim()">Signaler</button>
                </form>
              }
            </section>
          }
        }
      } @else if (action.enCours()) {
        <p class="doux">Chargement…</p>
      }
    </div>
  `,
  styles: `
    .chiffres {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
      gap: 0.75rem;
      margin: 0.5rem 0 1rem;
      div {
        display: flex;
        flex-direction: column;
        padding: 0.75rem 1rem;
        border-radius: var(--rayon);
        background: var(--surface);
        border: 1px solid var(--bordure);
      }
      strong { font-size: 1.1rem; }
      span { font-size: 0.85rem; color: var(--texte-doux); }
    }
    .bascule {
      display: inline-flex;
      flex-wrap: wrap;
      gap: 0.2rem;
      margin-bottom: 0.75rem;
      button {
        min-height: 38px;
        padding: 0 0.8rem;
        border: 1px solid var(--bordure);
        background: var(--surface);
        border-radius: var(--rayon);
        font: inherit;
        font-weight: 600;
        color: var(--texte-doux);
        cursor: pointer;
      }
      button.actif { background: var(--primaire); color: var(--sur-primaire); border-color: var(--primaire); }
    }
    .fiche {
      display: grid;
      grid-template-columns: minmax(9rem, 14rem) 1fr;
      gap: 0.35rem 1rem;
      margin: 0 0 1rem;
      dt { color: var(--texte-doux); }
      dd { margin: 0; }
    }
    @media (max-width: 560px) {
      .fiche { grid-template-columns: 1fr; dd { margin-bottom: 0.4rem; } }
    }
    h2 { font-size: 1.05rem; margin: 0.75rem 0 0.5rem; }
  `,
})
export class DossierAgentPage {
  private readonly api = inject(PersonnelApi);

  /** Identifiant du dossier (route /personnel/:id). */
  readonly id = input<string>();
  /** Vrai sur « Mon dossier » (donnée de la route). */
  readonly mien = input(false);

  protected readonly dateCourte = dateCourte;
  protected readonly dateHeureCourte = dateHeureCourte;
  protected readonly categories = CATEGORIES;
  protected readonly situations = SITUATIONS;
  protected readonly naturesPiece = NATURES_PIECE;
  protected readonly typesAbsence = TYPES_ABSENCE;
  protected readonly statutsAbsence = STATUTS_ABSENCE;
  protected readonly listeCategories = Object.entries(CATEGORIES) as [CategorieAgent, string][];
  protected readonly listeSituations = Object.entries(SITUATIONS) as [Situation, string][];
  protected readonly listeNatures = Object.entries(NATURES_PIECE) as [NaturePiece, string][];
  protected readonly typesEtablis = (Object.entries(TYPES_ACTE) as [TypeActe, string][]).filter((t) => t[0] !== 'AUTORISATION_ABSENCE');
  protected readonly estRempli = (s: string | null): boolean => !!s;

  protected readonly action = new Action();
  protected readonly message = signal<string | null>(null);
  protected readonly dossier = signal<DossierVue | null>(null);
  protected readonly section = signal<Section>('identite');
  protected readonly edition = signal<DonneesAgent | null>(null);

  protected readonly dNature = signal<NatureDiplome>('ACADEMIQUE');
  protected readonly dIntitule = signal('');
  protected readonly dSpecialite = signal('');
  protected readonly dDelivrePar = signal('');
  protected readonly dAnnee = signal<number | null>(null);
  protected readonly dPiece = signal<string | null>(null);

  protected readonly pNature = signal<NaturePiece>('ARRETE');
  protected readonly pObjet = signal('');
  protected readonly pReference = signal('');
  protected readonly pDate = signal('');
  protected readonly fichier = signal<File | null>(null);

  protected readonly aType = signal<TypeActe>('ATTESTATION_TRAVAIL');
  protected readonly aDate = signal('');
  protected readonly aComplement = signal('');

  protected readonly bType = signal<TypeAbsence>('AUTORISATION_ABSENCE');
  protected readonly bDebut = signal('');
  protected readonly bFin = signal('');
  protected readonly bMotif = signal('');

  protected readonly sTexte = signal('');
  protected reponses: Record<string, string> = {};

  protected readonly signalementsOuverts = computed(() => this.dossier()?.signalements.filter((s) => !s.traiteLe).length ?? 0);
  protected readonly typesDemandables = computed(() =>
    (Object.entries(TYPES_ABSENCE) as [TypeAbsence, string][]).filter((t) => !this.mien() || t[0] !== 'ABSENCE_IRREGULIERE'),
  );

  constructor() {
    effect(() => {
      const mien = this.mien();
      const id = this.id();
      untracked(() => {
        if (mien || id) {
          void this.charger();
        }
      });
    });
  }

  protected ko(octets: number): string {
    return octets < 1024 * 1024 ? `${Math.max(1, Math.round(octets / 1024))} Ko` : `${(octets / 1024 / 1024).toFixed(1).replace('.', ',')} Mo`;
  }

  protected modifier(d: DossierVue): void {
    this.edition.set(donnees(d));
  }

  protected async enregistrer(): Promise<void> {
    const f = this.edition();
    const d = this.dossier();
    if (!f || !d) return;
    const propre = Object.fromEntries(Object.entries(f).map(([k, v]) => [k, typeof v === 'string' && !v.trim() ? null : v])) as unknown as DonneesAgent;
    if (await this.maj(() => this.api.modifier(d.id, propre), 'Dossier enregistré.')) {
      this.edition.set(null);
    }
  }

  protected async ajouterDiplome(): Promise<void> {
    const d = this.dossier();
    if (!d) return;
    if (await this.maj(() => this.api.ajouterDiplome(d.id, {
      nature: this.dNature(), intitule: this.dIntitule().trim(), specialite: this.dSpecialite().trim() || null,
      delivrePar: this.dDelivrePar().trim() || null, annee: this.dAnnee() ? Number(this.dAnnee()) : null, pieceId: this.dPiece(),
    }), 'Diplôme ajouté.')) {
      this.dIntitule.set('');
      this.dSpecialite.set('');
      this.dDelivrePar.set('');
      this.dAnnee.set(null);
      this.dPiece.set(null);
    }
  }

  protected async retirerDiplome(id: string): Promise<void> {
    await this.maj(() => this.api.retirerDiplome(id), 'Diplôme retiré.');
  }

  protected choisir(e: Event): void {
    this.fichier.set((e.target as HTMLInputElement).files?.[0] ?? null);
  }

  protected async deposer(): Promise<void> {
    const d = this.dossier();
    const f = this.fichier();
    if (!d || !f) return;
    if (await this.maj(() => this.api.deposerPiece(d.id, f, this.pNature(), this.pObjet().trim(), this.pReference(), this.pDate()), 'Pièce classée.')) {
      this.pObjet.set('');
      this.pReference.set('');
      this.pDate.set('');
      this.fichier.set(null);
    }
  }

  protected async retirerPiece(p: PieceVue): Promise<void> {
    await this.maj(() => this.api.retirerPiece(p.id), `« ${p.objet} » retirée du dossier.`);
  }

  protected async ouvrirPiece(id: string, nom: string): Promise<void> {
    const b = await this.action.executer(() => this.api.piece(id));
    if (b) enregistrerFichier(b, nom);
  }

  protected async etablir(): Promise<void> {
    const d = this.dossier();
    if (!d) return;
    const t = this.aType();
    const a = await this.action.executer(() => this.api.etablirActe(d.id, t,
      t === 'PRISE_SERVICE' || t === 'CESSATION_SERVICE' ? this.aDate() || null : null, this.aComplement().trim() || null));
    if (a) {
      this.message.set(a.numero ? `${a.titre} n° ${a.numero} établi.` : `${a.titre} préparé : il attend la signature du chef d'établissement.`);
      this.aComplement.set('');
      await this.charger();
    }
  }

  protected async signer(a: ActeVue): Promise<void> {
    const r = await this.action.executer(() => this.api.signerActe(a.id));
    if (r) {
      this.message.set(`${r.titre} signé : n° ${r.numero}.`);
      await this.charger();
    }
  }

  protected async annulerActe(a: ActeVue): Promise<void> {
    const motif = globalThis.prompt?.(`Motif de l'annulation de l'acte ${a.numero} :`)?.trim();
    if (!motif) return;
    if (await this.action.executer(() => this.api.annulerActe(a.id, motif))) {
      this.message.set(`Acte ${a.numero} annulé.`);
      await this.charger();
    }
  }

  protected async pdf(a: ActeVue): Promise<void> {
    await this.pdfId(a.id, a.numero ?? 'projet');
  }

  protected async pdfId(id: string, nom: string): Promise<void> {
    const b = await this.action.executer(() => this.api.actePdf(id));
    if (b) enregistrerFichier(b, `${nom}.pdf`);
  }

  protected async demander(): Promise<void> {
    const d = this.dossier();
    if (!d) return;
    const donneesAbsence = { type: this.bType(), debut: this.bDebut(), fin: this.bFin(), motif: this.bMotif().trim() };
    const b = await this.action.executer(() =>
      this.mien() ? this.api.demanderAbsence(donneesAbsence) : this.api.saisirAbsence(d.id, donneesAbsence));
    if (b) {
      this.message.set(b.statut === 'DEMANDEE' ? 'Demande envoyée au chef d\'établissement.' : 'Absence enregistrée.');
      this.bDebut.set('');
      this.bFin.set('');
      this.bMotif.set('');
      await this.charger();
    }
  }

  protected async decider(b: AbsenceVue, accorder: boolean): Promise<void> {
    const reponse = accorder ? null : globalThis.prompt?.('Motif du refus :')?.trim();
    if (!accorder && !reponse) return;
    const r = await this.action.executer(() => this.api.decider(b.id, accorder, reponse ?? null));
    if (r) {
      this.message.set(accorder ? "Absence accordée : l'autorisation est signée et transmise à l'agent." : 'Demande refusée.');
      await this.charger();
    }
  }

  protected async retirerDemande(b: AbsenceVue): Promise<void> {
    if (await this.action.executer(() => this.api.annulerAbsence(b.id, null))) {
      this.message.set('Demande retirée.');
      await this.charger();
    }
  }

  protected async signaler(): Promise<void> {
    if (await this.maj(() => this.api.signaler(this.sTexte().trim()), 'Signalement envoyé au secrétariat.')) {
      this.sTexte.set('');
    }
  }

  protected async traiter(id: string): Promise<void> {
    await this.maj(() => this.api.traiterSignalement(id, this.reponses[id].trim()), 'Signalement traité.');
  }

  private async maj(action: () => Promise<DossierVue>, texte: string): Promise<boolean> {
    this.message.set(null);
    const d = await this.action.executer(action);
    if (d) {
      this.dossier.set(d);
      this.message.set(texte);
      return true;
    }
    return false;
  }

  private async charger(): Promise<void> {
    const id = this.id();
    const d = await this.action.executer(() => (this.mien() || !id ? this.api.monDossier() : this.api.dossier(id)));
    if (d) this.dossier.set(d);
  }
}
