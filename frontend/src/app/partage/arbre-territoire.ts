/**
 * Regroupement d'éléments (établissements, lignes d'adoption ou de pilotage) par territoire (v0.39) :
 * pays › ministère › direction régionale › direction provinciale… d'après le rattachement en une
 * ligne (« Burkina Faso · MESFPT · DR du Centre · DP du Kadiogo ») fourni par le serveur.
 *
 * Les niveaux communs à tous les éléments (par exemple le pays et le ministère pour une direction
 * régionale) sont retirés de l'arbre et rendus à part (`prefixe`). Les groupes sont triés par nom,
 * les éléments gardent l'ordre reçu. Les éléments sans rattachement forment le groupe « Sans
 * rattachement », en dernier.
 */

export const SEPARATEUR = ' · ';
export const SANS_RATTACHEMENT = 'Sans rattachement';

export interface GroupeArbre<T> {
  /** Clé unique du groupe : son chemin complet */
  cle: string;
  nom: string;
  /** Tous les éléments du groupe et de ses sous-groupes */
  elements: T[];
  ouvert: boolean;
  /** Nombre de sous-groupes directs */
  sousGroupes: number;
}

export interface LigneArbre<T> {
  cle: string;
  profondeur: number;
  groupe?: GroupeArbre<T>;
  element?: T;
}

export interface Arbre<T> {
  /** Niveaux communs à tous les éléments (affichés en tête) */
  prefixe: string[];
  lignes: LigneArbre<T>[];
  /** Clés de tous les groupes (pour « Tout déplier ») */
  cles: string[];
}

interface Noeud<T> {
  cle: string;
  nom: string;
  enfants: Map<string, Noeud<T>>;
  directs: T[];
  tous: T[];
}

function noeud<T>(cle: string, nom: string): Noeud<T> {
  return { cle, nom, enfants: new Map(), directs: [], tous: [] };
}

/** Découpe un rattachement en niveaux (vide s'il n'y en a pas). */
export function niveaux(chemin: string | null | undefined): string[] {
  return (chemin ?? '').split(SEPARATEUR).map((s) => s.trim()).filter((s) => s.length > 0);
}

/**
 * @param ouverts clés des groupes dépliés
 * @param toutOuvert tout déplier (recherche ou filtre en cours, par exemple)
 */
export function arbreTerritoire<T>(
  elements: readonly T[],
  chemin: (e: T) => string | null | undefined,
  ouverts: ReadonlySet<string>,
  toutOuvert = false,
): Arbre<T> {
  const chemins = elements.map((e) => niveaux(chemin(e)));
  // Niveaux communs à tous les éléments rattachés (s'il n'y a aucun élément sans rattachement)
  const rattaches = chemins.filter((c) => c.length > 0);
  let prefixe: string[] = [];
  if (rattaches.length === chemins.length && rattaches.length > 0) {
    prefixe = rattaches[0].slice(0, -1);
    for (const c of rattaches) {
      let i = 0;
      while (i < prefixe.length && i < c.length - 1 && prefixe[i] === c[i]) {
        i++;
      }
      prefixe = prefixe.slice(0, i);
    }
  }

  // Aucun élément rattaché : un groupe « Sans rattachement » unique n'apporterait rien
  if (rattaches.length === 0) {
    return { prefixe: [], lignes: elements.map((e, i) => ({ cle: `e::${i}`, profondeur: 0, element: e })), cles: [] };
  }

  const racine = noeud<T>('', '');
  elements.forEach((e, k) => {
    const c = chemins[k];
    const suite = c.length ? c.slice(prefixe.length) : [SANS_RATTACHEMENT];
    let courant = racine;
    courant.tous.push(e);
    let cle = prefixe.join(SEPARATEUR);
    for (const nom of suite) {
      cle = cle ? cle + SEPARATEUR + nom : nom;
      let suivant = courant.enfants.get(nom);
      if (!suivant) {
        suivant = noeud<T>(c.length ? cle : SANS_RATTACHEMENT, nom);
        courant.enfants.set(nom, suivant);
      }
      suivant.tous.push(e);
      courant = suivant;
    }
    courant.directs.push(e);
  });

  const lignes: LigneArbre<T>[] = [];
  const cles: string[] = [];
  const trier = (n: Noeud<T>) =>
    [...n.enfants.values()].sort((a, b) =>
      a.nom === SANS_RATTACHEMENT ? 1 : b.nom === SANS_RATTACHEMENT ? -1 : a.nom.localeCompare(b.nom, 'fr'),
    );
  const toutes = (n: Noeud<T>) => {
    for (const g of n.enfants.values()) {
      cles.push(g.cle);
      toutes(g);
    }
  };
  toutes(racine);
  const parcourir = (n: Noeud<T>, profondeur: number) => {
    for (const g of trier(n)) {
      const ouvert = toutOuvert || ouverts.has(g.cle);
      lignes.push({
        cle: 'g:' + g.cle,
        profondeur,
        groupe: { cle: g.cle, nom: g.nom, elements: g.tous, ouvert, sousGroupes: g.enfants.size },
      });
      if (ouvert) {
        parcourir(g, profondeur + 1);
        g.directs.forEach((e, i) => lignes.push({ cle: `e:${g.cle}:${i}`, profondeur: profondeur + 1, element: e }));
      }
    }
  };
  parcourir(racine, 0);
  // Éléments rattachés exactement au niveau commun (rare : une direction et ses établissements directs)
  racine.directs.forEach((e, i) => lignes.push({ cle: `e::${i}`, profondeur: 0, element: e }));
  return { prefixe, lignes, cles };
}

/** Groupes dépliés par défaut : le premier niveau (ses sous-groupes sont visibles, repliés). */
export function ouvertsParDefaut<T>(elements: readonly T[], chemin: (e: T) => string | null | undefined): Set<string> {
  const a = arbreTerritoire(elements, chemin, new Set(), true);
  return new Set(a.lignes.filter((l) => l.groupe && l.profondeur < 1).map((l) => l.groupe!.cle));
}

/** Ajoute ou retire un groupe des groupes dépliés. */
export function basculer(ouverts: ReadonlySet<string>, cle: string): Set<string> {
  const s = new Set(ouverts);
  if (s.has(cle)) {
    s.delete(cle);
  } else {
    s.add(cle);
  }
  return s;
}
