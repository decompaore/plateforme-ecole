import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { API } from '../core/session.service';
import {
  ArticleVue,
  AtelierResumeVue,
  AtelierVue,
  CandidatVue,
  DonneesArticle,
  DonneesAtelier,
  DonneesEquipement,
  EquipementVue,
  InventaireResumeVue,
  InventaireVue,
  LigneStockVue,
  MouvementVue,
  PanneVue,
  ParametresAteliers,
  SaisieInventaire,
  StatutPanne,
  TypeMouvement,
} from './modeles-ateliers';
import {
  BesoinAtelierResumeVue,
  BesoinVue,
  CampagneResumeVue,
  CampagneVue,
  CommandeVue,
  DemandeCampagne,
  DemandeCommande,
  DemandeLivraison,
  LivraisonVue,
  SaisieRepartition,
} from './modeles-besoins';

/** Appels de l'API des ateliers (en ligne). */
@Injectable({ providedIn: 'root' })
export class AteliersApi {
  private readonly http = inject(HttpClient);

  private get<T>(chemin: string): Promise<T> {
    return firstValueFrom(this.http.get<T>(API + chemin));
  }

  private post<T>(chemin: string, corps: unknown): Promise<T> {
    return firstValueFrom(this.http.post<T>(API + chemin, corps));
  }

  private put<T>(chemin: string, corps: unknown): Promise<T> {
    return firstValueFrom(this.http.put<T>(API + chemin, corps));
  }

  // ---------------- Paramètres

  parametres(): Promise<ParametresAteliers> {
    return this.get('/parametres/ateliers');
  }

  modifierParametres(p: ParametresAteliers): Promise<ParametresAteliers> {
    return this.put('/parametres/ateliers', p);
  }

  // ---------------- Catalogue

  catalogue(): Promise<ArticleVue[]> {
    return this.get('/catalogue');
  }

  creerArticle(d: DonneesArticle): Promise<ArticleVue> {
    return this.post('/catalogue', d);
  }

  modifierArticle(id: string, d: DonneesArticle): Promise<ArticleVue> {
    return this.put(`/catalogue/${id}`, d);
  }

  photo(id: string): Promise<Blob> {
    return firstValueFrom(this.http.get(`${API}/catalogue/${id}/photo`, { responseType: 'blob' }));
  }

  envoyerPhoto(id: string, fichier: File): Promise<ArticleVue> {
    const formulaire = new FormData();
    formulaire.append('fichier', fichier);
    return this.post(`/catalogue/${id}/photo`, formulaire);
  }

  supprimerPhoto(id: string): Promise<void> {
    return firstValueFrom(this.http.delete<void>(`${API}/catalogue/${id}/photo`));
  }

  // ---------------- Ateliers

  ateliers(): Promise<AtelierResumeVue[]> {
    return this.get('/ateliers');
  }

  atelier(id: string): Promise<AtelierVue> {
    return this.get(`/ateliers/${id}`);
  }

  creerAtelier(d: DonneesAtelier): Promise<AtelierVue> {
    return this.post('/ateliers', d);
  }

  modifierAtelier(id: string, d: DonneesAtelier): Promise<AtelierVue> {
    return this.put(`/ateliers/${id}`, d);
  }

  candidats(id: string): Promise<CandidatVue[]> {
    return this.get(`/ateliers/${id}/candidats`);
  }

  designer(id: string, engagementId: string, debut: string | null, finPrevue: string | null): Promise<AtelierVue> {
    return this.post(`/ateliers/${id}/responsable`, { engagementId, debut, finPrevue });
  }

  terminerMandat(id: string, date: string | null, motif: string | null): Promise<AtelierVue> {
    return this.post(`/ateliers/${id}/responsable/fin`, { date, motif });
  }

  // ---------------- Équipements

  equipements(atelierId: string): Promise<EquipementVue[]> {
    return this.get(`/ateliers/${atelierId}/equipements`);
  }

  creerEquipement(atelierId: string, d: DonneesEquipement): Promise<EquipementVue> {
    return this.post(`/ateliers/${atelierId}/equipements`, d);
  }

  modifierEquipement(id: string, d: DonneesEquipement): Promise<EquipementVue> {
    return this.put(`/equipements/${id}`, d);
  }

  pannes(equipementId: string): Promise<PanneVue[]> {
    return this.get(`/equipements/${equipementId}/pannes`);
  }

  signalerPanne(equipementId: string, description: string): Promise<PanneVue> {
    return this.post(`/equipements/${equipementId}/pannes`, { description });
  }

  cloturerPanne(panneId: string, statut: StatutPanne, intervention: string | null, cout: number | null): Promise<PanneVue> {
    return this.post(`/pannes/${panneId}/cloture`, { statut, intervention, cout });
  }

  // ---------------- Matière d'œuvre

  stock(atelierId: string): Promise<LigneStockVue[]> {
    return this.get(`/ateliers/${atelierId}/stock`);
  }

  mouvements(atelierId: string): Promise<MouvementVue[]> {
    return this.get(`/ateliers/${atelierId}/mouvements`);
  }

  mouvement(atelierId: string, d: { articleId: string; type: TypeMouvement; quantite: number; date: string | null; motif: string | null }): Promise<MouvementVue> {
    return this.post(`/ateliers/${atelierId}/mouvements`, d);
  }

  seuil(atelierId: string, articleId: string, seuil: number | null): Promise<LigneStockVue> {
    return this.put(`/ateliers/${atelierId}/stock/${articleId}/seuil`, { seuil });
  }

  // ---------------- Inventaires

  inventaires(atelierId: string): Promise<InventaireResumeVue[]> {
    return this.get(`/ateliers/${atelierId}/inventaires`);
  }

  ouvrirInventaire(atelierId: string, libelle: string | null): Promise<InventaireVue> {
    return this.post(`/ateliers/${atelierId}/inventaires`, { libelle });
  }

  inventaire(id: string): Promise<InventaireVue> {
    return this.get(`/inventaires/${id}`);
  }

  saisirInventaire(id: string, d: SaisieInventaire): Promise<InventaireVue> {
    return this.put(`/inventaires/${id}/lignes`, d);
  }

  cloreInventaire(id: string): Promise<InventaireVue> {
    return this.post(`/inventaires/${id}/cloture`, null);
  }

  // ---------------- Campagnes de besoins

  campagnes(): Promise<CampagneResumeVue[]> {
    return this.get('/campagnes-besoins');
  }

  ouvrirCampagne(d: DemandeCampagne): Promise<CampagneVue> {
    return this.post('/campagnes-besoins', d);
  }

  campagne(id: string): Promise<CampagneVue> {
    return this.get(`/campagnes-besoins/${id}`);
  }

  modifierCampagne(id: string, d: DemandeCampagne): Promise<CampagneVue> {
    return this.put(`/campagnes-besoins/${id}`, d);
  }

  transmettreCampagne(id: string): Promise<CampagneVue> {
    return this.post(`/campagnes-besoins/${id}/transmission`, null);
  }

  cloreCampagne(id: string): Promise<CampagneVue> {
    return this.post(`/campagnes-besoins/${id}/cloture`, null);
  }

  // ---------------- Besoins d'un atelier

  besoinsDeLAtelier(atelierId: string): Promise<BesoinAtelierResumeVue[]> {
    return this.get(`/ateliers/${atelierId}/besoins`);
  }

  besoin(id: string): Promise<BesoinVue> {
    return this.get(`/besoins-ateliers/${id}`);
  }

  proposerLigne(besoinId: string, articleId: string, quantite: number, justification: string | null): Promise<BesoinVue> {
    return this.put(`/besoins-ateliers/${besoinId}/lignes/${articleId}`, { quantite, justification });
  }

  retirerLigne(besoinId: string, articleId: string): Promise<BesoinVue> {
    return firstValueFrom(this.http.delete<BesoinVue>(`${API}/besoins-ateliers/${besoinId}/lignes/${articleId}`));
  }

  transmettreBesoin(id: string): Promise<BesoinVue> {
    return this.post(`/besoins-ateliers/${id}/transmission`, null);
  }

  renvoyerBesoin(id: string, commentaire: string): Promise<BesoinVue> {
    return this.post(`/besoins-ateliers/${id}/renvoi`, { commentaire });
  }

  arbitrer(id: string, lignes: { articleId: string; quantiteRetenue: number | null }[]): Promise<BesoinVue> {
    return this.put(`/besoins-ateliers/${id}/arbitrage`, { lignes });
  }

  validerBesoin(id: string): Promise<BesoinVue> {
    return this.post(`/besoins-ateliers/${id}/validation`, null);
  }

  // ---------------- Commandes, réception, répartition

  creerCommande(campagneId: string, d: DemandeCommande): Promise<CommandeVue> {
    return this.post(`/campagnes-besoins/${campagneId}/commandes`, d);
  }

  commande(id: string): Promise<CommandeVue> {
    return this.get(`/commandes/${id}`);
  }

  annulerCommande(id: string, motif: string): Promise<CommandeVue> {
    return this.post(`/commandes/${id}/annulation`, { motif });
  }

  recevoir(commandeId: string, d: DemandeLivraison): Promise<LivraisonVue> {
    return this.post(`/commandes/${commandeId}/livraisons`, d);
  }

  livraison(id: string): Promise<LivraisonVue> {
    return this.get(`/livraisons/${id}`);
  }

  enregistrerRepartition(id: string, lignes: SaisieRepartition[]): Promise<LivraisonVue> {
    return this.put(`/livraisons/${id}/repartition`, { lignes });
  }

  validerRepartition(id: string): Promise<LivraisonVue> {
    return this.post(`/livraisons/${id}/repartition/validation`, null);
  }
}
