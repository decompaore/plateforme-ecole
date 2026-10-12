import { NavigationError } from '@angular/router';

/** Clé de session : dernier rechargement provoqué par un écran introuvable (évite une boucle). */
const CLE = 'rechargement-ecran';
const DELAI_MS = 30_000;

/**
 * Vrai si l'erreur vient du chargement d'un écran chargé à la demande (fichier JavaScript introuvable) :
 * application mise à jour pendant que l'onglet du navigateur était ouvert, serveur de développement
 * relancé, cache du navigateur ou du service worker périmé.
 */
export function estEchecDeChargement(erreur: unknown): boolean {
  const message = erreur instanceof Error ? `${erreur.name} ${erreur.message}` : String(erreur ?? '');
  return /dynamically imported module|Importing a module script failed|error loading dynamically imported|ChunkLoadError|Loading chunk|Outdated Optimize Dep/i.test(
    message,
  );
}

/**
 * Erreur de navigation (v0.44.1). Sans ce traitement, un clic sur un onglet dont l'écran ne se charge pas
 * ne fait rien, sans message. On recharge alors la page une fois, à l'adresse demandée : le navigateur
 * récupère la version à jour et la session reprend grâce au cookie de rafraîchissement.
 */
export function traiterErreurNavigation(e: NavigationError, fenetre: Window = window): void {
  console.error(`Navigation vers ${e.url} impossible`, e.error);
  if (!estEchecDeChargement(e.error)) {
    return;
  }
  let dernier = 0;
  try {
    dernier = Number(fenetre.sessionStorage.getItem(CLE) ?? 0);
    fenetre.sessionStorage.setItem(CLE, String(Date.now()));
  } catch {
    // stockage indisponible : on recharge quand même
  }
  if (Date.now() - dernier > DELAI_MS) {
    fenetre.location.assign(e.url);
  }
}
