import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { Role } from '../core/modeles';
import { API } from '../core/session.service';
import {
  AnneeVue,
  ClasseVue,
  DossierEleveVue,
  EleveVue,
  EnseignantVue,
  EtablissementVue,
  FicheEnseignantVue,
  FiliereVue,
  InscriptionVue,
  LienParente,
  MatiereDeClasseVue,
  MatiereVue,
  MembreVue,
  Page,
  PeriodeVue,
  ProfilVue,
  RapportImport,
  ResultatAjoutMembre,
  ResultatCreationEtablissement,
  ResultatEngagement,
  SuiviEvaluationVue,
  Sexe,
  StatutBourse,
  StatutTenant,
  TypeEngagement,
  TypeMatiere,
} from './modeles-admin';

/** Appels de l'API pour l'espace d'administration (en ligne uniquement). */
@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);

  private get<T>(chemin: string, params?: Record<string, string | number | boolean>): Promise<T> {
    return firstValueFrom(this.http.get<T>(API + chemin, { params: params ? new HttpParams({ fromObject: params }) : undefined }));
  }

  private post<T>(chemin: string, corps: unknown = null): Promise<T> {
    return firstValueFrom(this.http.post<T>(API + chemin, corps));
  }

  private put<T>(chemin: string, corps: unknown): Promise<T> {
    return firstValueFrom(this.http.put<T>(API + chemin, corps));
  }

  private patch<T>(chemin: string, corps: unknown): Promise<T> {
    return firstValueFrom(this.http.patch<T>(API + chemin, corps));
  }

  private delete<T>(chemin: string): Promise<T> {
    return firstValueFrom(this.http.delete<T>(API + chemin));
  }

  // ---------- Plateforme (super administrateur)

  etablissements(): Promise<EtablissementVue[]> {
    return this.get('/plateforme/etablissements');
  }

  creerEtablissement(d: {
    code: string;
    nom: string;
    telephoneAdministrateur: string;
    nomAdministrateur: string;
    prenomsAdministrateur: string;
  }): Promise<ResultatCreationEtablissement> {
    return this.post('/plateforme/etablissements', d);
  }

  changerStatutEtablissement(id: string, statut: StatutTenant): Promise<EtablissementVue> {
    return this.patch(`/plateforme/etablissements/${id}/statut`, { statut });
  }

  // ---------- Membres

  membres(): Promise<MembreVue[]> {
    return this.get('/membres');
  }

  ajouterMembre(d: { telephone: string; nom: string; prenoms: string; role: Role }): Promise<ResultatAjoutMembre> {
    return this.post('/membres', d);
  }

  desactiverMembre(id: string): Promise<void> {
    return this.post(`/membres/${id}/desactivation`);
  }

  // ---------- Profils, années, périodes

  profils(): Promise<ProfilVue[]> {
    return this.get('/profils');
  }

  initialiserProfils(): Promise<ProfilVue[]> {
    return this.post('/profils/initialisation');
  }

  annees(): Promise<AnneeVue[]> {
    return this.get('/annees');
  }

  creerAnnee(d: { libelle: string; debut: string; fin: string }): Promise<AnneeVue> {
    return this.post('/annees', d);
  }

  ouvrirAnnee(id: string): Promise<AnneeVue> {
    return this.post(`/annees/${id}/ouverture`);
  }

  periodes(anneeId: string): Promise<PeriodeVue[]> {
    return this.get(`/annees/${anneeId}/periodes`);
  }

  genererPeriodes(anneeId: string, profilId: string): Promise<PeriodeVue[]> {
    return this.post(`/annees/${anneeId}/periodes/generation`, { profilId });
  }

  // ---------- Filières et matières

  filieres(): Promise<FiliereVue[]> {
    return this.get('/filieres');
  }

  creerFiliere(d: { code: string; libelle: string; cycle: string; diplomeVise: string | null; profilId: string }): Promise<FiliereVue> {
    return this.post('/filieres', d);
  }

  matieres(): Promise<MatiereVue[]> {
    return this.get('/matieres');
  }

  creerMatiere(d: { code: string; libelle: string; type: TypeMatiere }): Promise<MatiereVue> {
    return this.post('/matieres', d);
  }

  // ---------- Classes et programmes

  classes(anneeId: string): Promise<ClasseVue[]> {
    return this.get(`/annees/${anneeId}/classes`);
  }

  classe(id: string): Promise<ClasseVue> {
    return this.get(`/classes/${id}`);
  }

  creerClasse(anneeId: string, d: { filiereId: string; code: string; niveau: string; effectifMax: number | null }): Promise<ClasseVue> {
    return this.post(`/annees/${anneeId}/classes`, d);
  }

  supprimerClasse(id: string): Promise<void> {
    return this.delete(`/classes/${id}`);
  }

  matieresDeClasse(classeId: string): Promise<MatiereDeClasseVue[]> {
    return this.get(`/classes/${classeId}/matieres`);
  }

  definirMatiere(
    classeId: string,
    matiereId: string,
    d: { coefficient: number; groupe: string | null; volumeHebdo: number | null; volumeTotal: number | null },
  ): Promise<MatiereDeClasseVue> {
    return this.put(`/classes/${classeId}/matieres/${matiereId}`, d);
  }

  retirerMatiere(classeId: string, matiereId: string): Promise<void> {
    return this.delete(`/classes/${classeId}/matieres/${matiereId}`);
  }

  affecter(classeId: string, matiereId: string, engagementId: string): Promise<MatiereDeClasseVue> {
    return this.put(`/classes/${classeId}/matieres/${matiereId}/enseignant`, { engagementId });
  }

  retirerAffectation(classeId: string, matiereId: string): Promise<MatiereDeClasseVue> {
    return this.delete(`/classes/${classeId}/matieres/${matiereId}/enseignant`);
  }

  // ---------- Élèves et inscriptions

  eleves(q: string, page: number, taille = 20): Promise<Page<EleveVue>> {
    return this.get('/eleves', { q, page, taille });
  }

  dossier(eleveId: string): Promise<DossierEleveVue> {
    return this.get(`/eleves/${eleveId}`);
  }

  creerEleve(d: {
    nom: string;
    prenoms: string;
    sexe: Sexe;
    dateNaissance: string;
    lieuNaissance: string | null;
    responsables: { nom: string; prenoms: string; telephone: string; lien: LienParente }[];
  }): Promise<DossierEleveVue> {
    return this.post('/eleves', d);
  }

  inscrire(d: { eleveId: string; classeId: string; redoublant: boolean; statutBourse: StatutBourse | null }): Promise<InscriptionVue> {
    return this.post('/inscriptions', d);
  }

  inscriptions(classeId: string): Promise<InscriptionVue[]> {
    return this.get(`/classes/${classeId}/inscriptions`);
  }

  /** Modèle Excel d'import, avec la liste des classes de l'année. */
  async modeleImport(anneeId: string): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/annees/${anneeId}/eleves/import/modele`, { responseType: 'blob' }));
  }

  importer(anneeId: string, fichier: File, simulation: boolean): Promise<RapportImport> {
    const donnees = new FormData();
    donnees.append('fichier', fichier);
    return firstValueFrom(
      this.http.post<RapportImport>(`${API}/annees/${anneeId}/eleves/import`, donnees, {
        params: new HttpParams().set('simulation', simulation),
      }),
    );
  }

  // ---------- Enseignants

  enseignants(): Promise<EnseignantVue[]> {
    return this.get('/enseignants');
  }

  engager(d: {
    telephone: string;
    nom: string;
    prenoms: string;
    sexe: Sexe;
    specialite: string | null;
    type: TypeEngagement;
    debut: string;
    fin: string | null;
    tauxHoraire: number | null;
  }): Promise<ResultatEngagement> {
    return this.post('/enseignants', d);
  }

  /** Fiche : matières assurées pendant l'année active et charge hebdomadaire. */
  ficheEnseignant(engagementId: string): Promise<FicheEnseignantVue> {
    return this.get(`/engagements/${engagementId}`);
  }

  /** Date passée : fin immédiate ; aujourd'hui ou plus tard : fin programmée. */
  terminerEngagement(engagementId: string, date: string, motif: string | null): Promise<EnseignantVue> {
    return this.post(`/engagements/${engagementId}/fin`, { date, motif });
  }

  annulerFinEngagement(engagementId: string): Promise<EnseignantVue> {
    return this.delete(`/engagements/${engagementId}/fin`);
  }

  annulerInvitation(engagementId: string): Promise<void> {
    return this.delete(`/engagements/${engagementId}`);
  }

  // ---------- Suivi des évaluations

  /** `ordre` : 1er, 2e… trimestre ou semestre ; absent : toute l'année. */
  suiviEvaluations(anneeId: string, ordre?: number): Promise<SuiviEvaluationVue[]> {
    return this.get(`/annees/${anneeId}/suivi-evaluations`, ordre ? { ordre } : undefined);
  }
}
