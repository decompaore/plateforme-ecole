import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import {
  AfficheVue,
  CandidatVue,
  CertificatVue,
  CourrierVue,
  DemandeVue,
  DocumentVue,
  DossierArchive,
  PassageVue,
  PersonneVue,
  PieceArchiveVue,
  PublicAffichage,
  ReunionVue,
  SaisieCourrier,
  SaisieDocument,
  SaisiePassage,
  SaisieReunion,
  Service,
  StatutCourrier,
  TypeDemande,
} from './modeles-secretariat';

/** Appels de l'API du secrétariat (v0.47.1). */
@Injectable({ providedIn: 'root' })
export class SecretariatApi {
  private readonly http = inject(HttpClient);

  private fichier(url: string, params?: HttpParams): Promise<Blob> {
    return firstValueFrom(this.http.get(url, { responseType: 'blob', params }));
  }

  private envoyer<T>(url: string, fichier: File, champs: Record<string, string | null | undefined> = {}): Promise<T> {
    const d = new FormData();
    d.append('fichier', fichier);
    for (const [k, v] of Object.entries(champs)) {
      if (v) {
        d.append(k, v);
      }
    }
    return firstValueFrom(this.http.post<T>(url, d));
  }

  // ---------- Courrier

  courriers(sens: string | null, annee: number | null, q: string): Promise<CourrierVue[]> {
    let p = new HttpParams();
    if (sens) p = p.set('sens', sens);
    if (annee) p = p.set('annee', annee);
    if (q.trim()) p = p.set('q', q.trim());
    return firstValueFrom(this.http.get<CourrierVue[]>(`${API}/courriers`, { params: p }));
  }

  enregistrerCourrier(s: SaisieCourrier): Promise<CourrierVue> {
    return firstValueFrom(this.http.post<CourrierVue>(`${API}/courriers`, s));
  }

  modifierCourrier(id: string, s: SaisieCourrier): Promise<CourrierVue> {
    return firstValueFrom(this.http.put<CourrierVue>(`${API}/courriers/${id}`, s));
  }

  suivreCourrier(id: string, statut: StatutCourrier, service: Service | null, suivi: string | null): Promise<CourrierVue> {
    return firstValueFrom(this.http.post<CourrierVue>(`${API}/courriers/${id}/suivi`, { statut, service, suivi }));
  }

  joindreCourrier(id: string, fichier: File): Promise<CourrierVue> {
    return this.envoyer(`${API}/courriers/${id}/fichier`, fichier);
  }

  pieceCourrier(id: string): Promise<Blob> {
    return this.fichier(`${API}/courriers/${id}/fichier`);
  }

  registreCourrier(annee: number, format: 'xlsx' | 'pdf'): Promise<Blob> {
    return this.fichier(`${API}/courriers/registre`, new HttpParams().set('annee', annee).set('format', format));
  }

  // ---------- Documents administratifs et affichage

  documents(): Promise<DocumentVue[]> {
    return firstValueFrom(this.http.get<DocumentVue[]>(`${API}/documents-administratifs`));
  }

  rediger(s: SaisieDocument): Promise<DocumentVue> {
    return firstValueFrom(this.http.post<DocumentVue>(`${API}/documents-administratifs`, s));
  }

  modifierDocument(id: string, s: SaisieDocument): Promise<DocumentVue> {
    return firstValueFrom(this.http.put<DocumentVue>(`${API}/documents-administratifs/${id}`, s));
  }

  supprimerDocument(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`${API}/documents-administratifs/${id}`));
  }

  signer(id: string): Promise<DocumentVue> {
    return firstValueFrom(this.http.post<DocumentVue>(`${API}/documents-administratifs/${id}/signature`, null));
  }

  annulerDocument(id: string): Promise<DocumentVue> {
    return firstValueFrom(this.http.post<DocumentVue>(`${API}/documents-administratifs/${id}/annulation`, null));
  }

  afficher(id: string, affichage: PublicAffichage | null, du: string | null, au: string | null): Promise<DocumentVue> {
    return firstValueFrom(this.http.put<DocumentVue>(`${API}/documents-administratifs/${id}/affichage`, { affichage, du, au }));
  }

  pdfDocument(id: string): Promise<Blob> {
    return this.fichier(`${API}/documents-administratifs/${id}/pdf`);
  }

  affichage(): Promise<AfficheVue[]> {
    return firstValueFrom(this.http.get<AfficheVue[]>(`${API}/affichage`));
  }

  // ---------- Actes administratifs du personnel

  demandes(): Promise<DemandeVue[]> {
    return firstValueFrom(this.http.get<DemandeVue[]>(`${API}/demandes-administratives`));
  }

  mesDemandes(): Promise<DemandeVue[]> {
    return firstValueFrom(this.http.get<DemandeVue[]>(`${API}/demandes-administratives/miennes`));
  }

  recevoirDemande(d: {
    type: TypeDemande;
    demandeurId: string | null;
    demandeur: string | null;
    recueLe: string | null;
    objet: string;
    du: string | null;
    au: string | null;
  }): Promise<DemandeVue> {
    return firstValueFrom(this.http.post<DemandeVue>(`${API}/demandes-administratives`, d));
  }

  transmettreDemande(id: string): Promise<DemandeVue> {
    return firstValueFrom(this.http.post<DemandeVue>(`${API}/demandes-administratives/${id}/transmission`, null));
  }

  aviser(id: string, favorable: boolean, avis: string | null): Promise<DemandeVue> {
    return firstValueFrom(this.http.post<DemandeVue>(`${API}/demandes-administratives/${id}/avis`, { favorable, avis }));
  }

  transmettreHierarchie(id: string, destinataire: string, categorie: string): Promise<DemandeVue> {
    return firstValueFrom(
      this.http.post<DemandeVue>(`${API}/demandes-administratives/${id}/transmission-hierarchie`, { destinataire, categorie }),
    );
  }

  cloturer(id: string, suite: string): Promise<DemandeVue> {
    return firstValueFrom(this.http.post<DemandeVue>(`${API}/demandes-administratives/${id}/cloture`, { suite }));
  }

  joindreDemande(id: string, fichier: File): Promise<DemandeVue> {
    return this.envoyer(`${API}/demandes-administratives/${id}/fichier`, fichier);
  }

  pieceDemande(id: string): Promise<Blob> {
    return this.fichier(`${API}/demandes-administratives/${id}/fichier`);
  }

  // ---------- Accueil

  personnel(): Promise<PersonneVue[]> {
    return firstValueFrom(this.http.get<PersonneVue[]>(`${API}/secretariat/personnel`));
  }

  accueil(jour: string): Promise<PassageVue[]> {
    return firstValueFrom(this.http.get<PassageVue[]>(`${API}/accueil`, { params: { jour } }));
  }

  enregistrerPassage(s: SaisiePassage): Promise<PassageVue> {
    return firstValueFrom(this.http.post<PassageVue>(`${API}/accueil`, s));
  }

  transmettrePassage(id: string): Promise<PassageVue> {
    return firstValueFrom(this.http.post<PassageVue>(`${API}/accueil/${id}/transmission`, null));
  }

  depart(id: string): Promise<PassageVue> {
    return firstValueFrom(this.http.post<PassageVue>(`${API}/accueil/${id}/depart`, null));
  }

  // ---------- Réunions

  reunions(): Promise<ReunionVue[]> {
    return firstValueFrom(this.http.get<ReunionVue[]>(`${API}/reunions`));
  }

  creerReunion(s: SaisieReunion): Promise<ReunionVue> {
    return firstValueFrom(this.http.post<ReunionVue>(`${API}/reunions`, s));
  }

  modifierReunion(id: string, s: SaisieReunion): Promise<ReunionVue> {
    return firstValueFrom(this.http.put<ReunionVue>(`${API}/reunions/${id}`, s));
  }

  validerReunion(id: string): Promise<ReunionVue> {
    return firstValueFrom(this.http.post<ReunionVue>(`${API}/reunions/${id}/validation`, null));
  }

  supprimerReunion(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`${API}/reunions/${id}`));
  }

  pdfReunion(id: string): Promise<Blob> {
    return this.fichier(`${API}/reunions/${id}/pdf`);
  }

  // ---------- Archives

  archives(dossier: DossierArchive, cible: string | null): Promise<PieceArchiveVue[]> {
    let p = new HttpParams().set('dossier', dossier);
    if (dossier === 'ELEVE' && cible) p = p.set('eleveId', cible);
    if (dossier === 'PERSONNEL' && cible) p = p.set('utilisateurId', cible);
    return firstValueFrom(this.http.get<PieceArchiveVue[]>(`${API}/archives`, { params: p }));
  }

  archiver(
    fichier: File,
    d: { dossier: DossierArchive; cible: string | null; categorie: string; titre: string; cote: string | null; dateDocument: string | null },
  ): Promise<PieceArchiveVue> {
    return this.envoyer(`${API}/archives`, fichier, {
      dossier: d.dossier,
      eleveId: d.dossier === 'ELEVE' ? d.cible : null,
      utilisateurId: d.dossier === 'PERSONNEL' ? d.cible : null,
      categorie: d.categorie,
      titre: d.titre,
      cote: d.cote,
      dateDocument: d.dateDocument,
    });
  }

  pieceArchive(id: string): Promise<Blob> {
    return this.fichier(`${API}/archives/${id}/fichier`);
  }

  supprimerArchive(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`${API}/archives/${id}`));
  }

  // ---------- Certificats et examens

  certificat(inscriptionId: string): Promise<Blob> {
    return firstValueFrom(this.http.post(`${API}/inscriptions/${inscriptionId}/certificat-scolarite`, null, { responseType: 'blob' }));
  }

  certificats(annee: number): Promise<CertificatVue[]> {
    return firstValueFrom(this.http.get<CertificatVue[]>(`${API}/certificats-scolarite`, { params: { annee } }));
  }

  candidats(classes: string[]): Promise<CandidatVue[]> {
    return firstValueFrom(this.http.get<CandidatVue[]>(`${API}/examens/candidats`, { params: { classes: classes.join(',') } }));
  }

  exporterCandidats(classes: string[], examen: string, format: 'xlsx' | 'pdf'): Promise<Blob> {
    return this.fichier(
      `${API}/examens/candidats/export`,
      new HttpParams().set('classes', classes.join(',')).set('examen', examen).set('format', format),
    );
  }
}
