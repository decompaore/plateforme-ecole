/**
 * Outils de la recette grandeur nature : client de l'API, sessions, assertions, scénarios et rapport.
 *
 * Un scénario ne lève jamais d'erreur vers l'extérieur : il est noté RÉUSSI, ÉCHEC (assertion fausse ou
 * réponse inattendue, avec le détail du serveur) ou IGNORÉ (prérequis absent, par exemple un module
 * désactivé ou un scénario précédent en échec). La suite continue toujours.
 */
import { randomUUID } from 'node:crypto';

export const API = (process.env.API ?? 'http://localhost:8080').replace(/\/$/, '') + '/api/v1';

// ===================================================================== erreurs

/** Assertion fausse : le scénario échoue. */
export class Echec extends Error {}

/** Prérequis absent : le scénario est ignoré (ce n'est pas un défaut de l'application). */
export class Ignore extends Error {}

export function ignorer(raison) {
  throw new Ignore(raison);
}

// ===================================================================== HTTP

/**
 * Réponse d'un appel : statut, données (JSON décodé, texte, ou octets pour un fichier), en-têtes.
 * `code` et `detail` reprennent l'erreur ProblemDetail du serveur.
 */
export class Reponse {
  constructor(methode, chemin, statut, donnees, entetes, octets) {
    this.methode = methode;
    this.chemin = chemin;
    this.statut = statut;
    this.donnees = donnees;
    this.entetes = entetes;
    this.octets = octets;
  }

  get ok() {
    return this.statut >= 200 && this.statut < 300;
  }

  get code() {
    return this.donnees && typeof this.donnees === 'object' ? (this.donnees.code ?? null) : null;
  }

  get detail() {
    if (this.donnees && typeof this.donnees === 'object') {
      return this.donnees.detail ?? this.donnees.title ?? null;
    }
    return typeof this.donnees === 'string' ? this.donnees.slice(0, 200) : null;
  }

  get type() {
    return this.entetes.get('content-type') ?? '';
  }

  /** « POST /virements/… → 409 [CODE] détail », pour les messages d'échec. */
  resume() {
    const chemin = this.chemin.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, '…');
    return `${this.methode} ${chemin} → ${this.statut}${this.code ? ` [${this.code}]` : ''}${this.detail ? ` : ${this.detail}` : ''}`;
  }
}

/**
 * Appel brut de l'API. `corps` : objet (JSON), FormData (multipart), ou undefined.
 * Options : `jeton`, `cookie`, `params` (objet), `fichier` (true : lire des octets).
 */
export async function appelBrut(methode, chemin, corps, { jeton, cookie, params, fichier } = {}) {
  let url = API + chemin;
  if (params) {
    const q = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null) q.set(k, String(v));
    }
    const s = q.toString();
    if (s) url += (url.includes('?') ? '&' : '?') + s;
  }
  const entetes = { Accept: fichier ? '*/*' : 'application/json' };
  let body;
  if (corps instanceof FormData) {
    body = corps;
  } else if (corps !== undefined) {
    entetes['Content-Type'] = 'application/json';
    body = JSON.stringify(corps);
  }
  if (jeton) entetes.Authorization = `Bearer ${jeton}`;
  if (cookie) entetes.Cookie = cookie;
  let r;
  try {
    r = await fetch(url, { method: methode, headers: entetes, body, redirect: 'manual' });
  } catch (e) {
    throw new Error(`API injoignable à ${API} (${e.cause?.code ?? e.message}). Lancez-la avec le profil dev.`);
  }
  const type = r.headers.get('content-type') ?? '';
  let donnees = null;
  let octets = null;
  if (fichier && r.ok) {
    octets = Buffer.from(await r.arrayBuffer());
  } else {
    const texte = await r.text();
    if (texte && type.includes('json')) {
      try {
        donnees = JSON.parse(texte);
      } catch {
        donnees = texte;
      }
    } else {
      donnees = texte || null;
    }
  }
  return new Reponse(methode, chemin, r.status, donnees, r.headers, octets);
}

// ===================================================================== sessions

/**
 * Session d'un compte (dans un établissement s'il en a plusieurs). Se reconnecte seule à l'expiration
 * du jeton. `cookie` garde le jeton de rafraîchissement (HttpOnly) pour les scénarios de session.
 */
export class Session {
  constructor(telephone, motDePasse, etablissementId = null, libelle = telephone) {
    this.telephone = telephone;
    this.motDePasse = motDePasse;
    this.etablissementId = etablissementId;
    this.libelle = libelle;
    this.valeur = null;
    this.expire = 0;
    this.cookie = null;
    this.connexion = null;
    this.enCours = null;
  }

  async jeton() {
    if (!this.valeur || Date.now() > this.expire) {
      this.enCours ??= this.ouvrir().finally(() => {
        this.enCours = null;
      });
      await this.enCours;
    }
    return this.valeur;
  }

  oublier() {
    this.valeur = null;
  }

  async ouvrir() {
    const r = await appelBrut('POST', '/auth/connexion', { telephone: this.telephone, motDePasse: this.motDePasse });
    if (!r.ok) throw new Echec(`Connexion impossible pour ${this.libelle} (${this.telephone}) : ${r.resume()}`);
    let s = r;
    this.cookie = cookieDe(r) ?? this.cookie;
    if (r.donnees.selectionRequise) {
      const choix = r.donnees.etablissements.find((e) => e.id === this.etablissementId) ?? r.donnees.etablissements[0];
      s = await appelBrut('POST', '/auth/etablissement', { etablissementId: choix.id }, { jeton: r.donnees.jetonSelection });
      if (!s.ok) throw new Echec(`Choix de l'établissement impossible pour ${this.libelle} : ${s.resume()}`);
      this.cookie = cookieDe(s) ?? this.cookie;
    }
    this.connexion = s.donnees;
    this.valeur = s.donnees.jetonAcces;
    this.expire = Date.now() + Math.max(60, (s.donnees.expireDansSecondes ?? 900) - 90) * 1000;
  }
}

/** Jeton de rafraîchissement posé en cookie par le serveur (« nom=valeur »), s'il y en a un. */
export function cookieDe(r) {
  const brut = typeof r.entetes.getSetCookie === 'function' ? r.entetes.getSetCookie() : [r.entetes.get('set-cookie')].filter(Boolean);
  for (const c of brut) {
    const premier = c.split(';')[0];
    if (premier.includes('=') && !premier.endsWith('=')) return premier;
  }
  return null;
}

/**
 * Appel au nom d'une session (ou d'un jeton, ou anonyme si `qui` est null). Ne lève pas d'erreur sur un
 * statut HTTP : c'est au scénario de vérifier. Un 401 d'une session déclenche une reconnexion.
 */
export async function appel(qui, methode, chemin, corps, options = {}) {
  const jeton = qui instanceof Session ? await qui.jeton() : qui ?? undefined;
  let r = await appelBrut(methode, chemin, corps, { ...options, jeton });
  if (r.statut === 401 && qui instanceof Session && !options.sansReconnexion) {
    qui.oublier();
    r = await appelBrut(methode, chemin, corps, { ...options, jeton: await qui.jeton() });
  }
  return r;
}

/** Appel qui doit réussir (2xx) : renvoie les données, sinon le scénario échoue. */
export async function exiger(qui, methode, chemin, corps, options = {}) {
  const r = await appel(qui, methode, chemin, corps, options);
  if (!r.ok) throw new Echec(`Réponse inattendue : ${r.resume()}`);
  return options.fichier ? r : r.donnees;
}

// ===================================================================== assertions

export function vrai(condition, message) {
  if (!condition) throw new Echec(message);
}

export function egal(obtenu, attendu, message) {
  if (JSON.stringify(obtenu) !== JSON.stringify(attendu)) {
    throw new Echec(`${message} : attendu ${JSON.stringify(attendu)}, obtenu ${JSON.stringify(obtenu)}`);
  }
}

/** Le statut attendu (un nombre ou une liste), et le code d'erreur s'il est précisé. */
export function statut(r, attendus, code) {
  const liste = Array.isArray(attendus) ? attendus : [attendus];
  if (!liste.includes(r.statut)) {
    throw new Echec(`Statut ${liste.join(' ou ')} attendu : ${r.resume()}`);
  }
  if (code && r.code !== code) {
    throw new Echec(`Code ${code} attendu : ${r.resume()}`);
  }
  return r;
}

/** Refus d'accès : 403 (ou 404 quand le serveur cache l'existence de la ressource). */
export function refuse(r, message = 'Accès refusé attendu') {
  if (![401, 403, 404].includes(r.statut)) {
    throw new Echec(`${message} : ${r.resume()}`);
  }
  return r;
}

/** Aucune trace des textes interdits dans la réponse (données personnelles, secrets). */
export function sansTexte(r, interdits, message) {
  const texte = typeof r.donnees === 'string' ? r.donnees : JSON.stringify(r.donnees ?? '');
  for (const t of interdits.filter(Boolean)) {
    if (texte.includes(t)) throw new Echec(`${message} : « ${t} » trouvé dans ${r.methode} ${r.chemin}`);
  }
}

// ===================================================================== données

export function telephoneUnique(prefixe = '7') {
  return prefixe + String(Math.floor(Math.random() * 10 ** (8 - prefixe.length))).padStart(8 - prefixe.length, '0');
}

export function suffixe() {
  return randomUUID().replace(/-/g, '').slice(0, 6);
}

export function cle() {
  return randomUUID();
}

/** PNG valide (1 × 1 pixel, transparent) : logo, preuve de virement, photo. */
export const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
);

export function fichierPng(nom = 'image.png') {
  return new Blob([PNG], { type: 'image/png' });
}

export function iso(d) {
  return d.toISOString().slice(0, 10);
}

export function ajouterJours(isoDate, n) {
  const d = new Date(`${isoDate}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return iso(d);
}

export async function pause(ms) {
  await new Promise((r) => setTimeout(r, ms));
}

/** Attend qu'une condition asynchrone soit vraie (export en préparation, calcul…). */
export async function attendreQue(fn, { delai = 60_000, pas = 1000, message = 'condition non atteinte' } = {}) {
  const fin = Date.now() + delai;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > fin) throw new Echec(`${message} (après ${Math.round(delai / 1000)} s)`);
    await pause(pas);
  }
}

// ===================================================================== scénarios et rapport

const SCENARIOS = [];

/**
 * Déclare un scénario. `id` : identifiant stable (ex. SEC-03) ; `domaine` : rubrique du rapport ;
 * `titre` : ce qui est vérifié, en une phrase ; `fn(ctx)` : le scénario.
 */
export function scenario(id, domaine, titre, fn) {
  if (SCENARIOS.some((s) => s.id === id)) throw new Error(`Scénario ${id} déclaré deux fois`);
  SCENARIOS.push({ id, domaine, titre, fn });
}

export function scenarios() {
  return SCENARIOS;
}

/** Exécute les scénarios retenus dans l'ordre de déclaration ; renvoie les résultats. */
export async function executer(ctx, filtre = () => true) {
  const resultats = [];
  let domaineCourant = null;
  for (const s of SCENARIOS.filter(filtre)) {
    if (s.domaine !== domaineCourant) {
      domaineCourant = s.domaine;
      console.log(`\n━━ ${domaineCourant}`);
    }
    const debut = Date.now();
    let etat = 'RÉUSSI';
    let detail = '';
    try {
      const note = await s.fn(ctx);
      if (typeof note === 'string') detail = note;
    } catch (e) {
      if (e instanceof Ignore) {
        etat = 'IGNORÉ';
        detail = e.message;
      } else if (e instanceof Echec) {
        etat = 'ÉCHEC';
        detail = e.message;
      } else {
        etat = 'ÉCHEC';
        detail = `Erreur du script : ${e.stack?.split('\n').slice(0, 3).join(' | ') ?? e.message}`;
      }
    }
    const duree = Date.now() - debut;
    const symbole = etat === 'RÉUSSI' ? '✔' : etat === 'IGNORÉ' ? '–' : '✘';
    console.log(`  ${symbole} ${s.id.padEnd(9)} ${s.titre}${detail ? `\n              ${etat === 'RÉUSSI' ? '' : etat + ' : '}${detail}` : ''}`);
    resultats.push({ id: s.id, domaine: s.domaine, titre: s.titre, etat, detail, duree });
  }
  return resultats;
}

/** Rapport Markdown : synthèse par domaine, puis chaque scénario. */
export function rapportMarkdown(resultats, entete) {
  const compte = (l, e) => l.filter((r) => r.etat === e).length;
  const lignes = [
    '# Rapport de recette grandeur nature',
    '',
    ...entete,
    '',
    `**${compte(resultats, 'RÉUSSI')} réussis · ${compte(resultats, 'ÉCHEC')} en échec · ${compte(resultats, 'IGNORÉ')} ignorés** sur ${resultats.length} scénarios.`,
    '',
    '## Synthèse par domaine',
    '',
    '| Domaine | Réussis | Échecs | Ignorés |',
    '|---|---:|---:|---:|',
  ];
  const domaines = [...new Set(resultats.map((r) => r.domaine))];
  for (const d of domaines) {
    const l = resultats.filter((r) => r.domaine === d);
    lignes.push(`| ${d} | ${compte(l, 'RÉUSSI')} | ${compte(l, 'ÉCHEC')} | ${compte(l, 'IGNORÉ')} |`);
  }
  const echecs = resultats.filter((r) => r.etat === 'ÉCHEC');
  if (echecs.length) {
    lignes.push('', '## Échecs à analyser', '');
    for (const r of echecs) lignes.push(`- **${r.id}** ${r.titre}  \n  ${r.detail.replace(/\|/g, '\\|')}`);
  }
  lignes.push('', '## Détail', '');
  for (const d of domaines) {
    lignes.push(`### ${d}`, '', '| Scénario | Vérifie | Résultat | Détail |', '|---|---|---|---|');
    for (const r of resultats.filter((x) => x.domaine === d)) {
      const icone = r.etat === 'RÉUSSI' ? '✔ réussi' : r.etat === 'ÉCHEC' ? '✘ **échec**' : '– ignoré';
      lignes.push(`| ${r.id} | ${r.titre.replace(/\|/g, '/')} | ${icone} | ${(r.detail ?? '').replace(/\|/g, '/').replace(/\n/g, ' ')} |`);
    }
    lignes.push('');
  }
  return lignes.join('\n');
}
