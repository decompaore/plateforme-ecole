#!/usr/bin/env node
/**
 * Vérifie que chaque appel de l'application (frontend) correspond à une route de l'API.
 *
 *   node scripts/verifier-routes.mjs                 # le code source seulement
 *   node scripts/verifier-routes.mjs --api           # + l'API qui tourne sur http://localhost:8080
 *   node scripts/verifier-routes.mjs --api http://localhost:8080
 *
 * 1. Code source : chaque appel `${API}/…` du frontend doit avoir une route @GetMapping,
 *    @PostMapping… dans le backend.
 * 2. API en marche (option --api, profil dev : /v3/api-docs ouvert) : les routes du code
 *    source doivent toutes exister dans l'API qui tourne. Sinon, l'API a été lancée avec une
 *    ancienne version compilée (par exemple depuis Eclipse) : arrêtez-la et relancez
 *    `mvn clean spring-boot:run -Dspring-boot.run.profiles=dev`.
 *
 * Code de sortie 1 s'il manque quelque chose : utilisable en intégration continue.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const RACINE = new URL('..', import.meta.url).pathname;
const BACKEND = join(RACINE, 'backend/src/main/java');
const FRONTEND = join(RACINE, 'frontend/src/app');
const indexApi = process.argv.indexOf('--api');
const API = indexApi < 0 ? null : (process.argv[indexApi + 1]?.startsWith('http') ? process.argv[indexApi + 1] : 'http://localhost:8080');

function fichiers(dossier, extension) {
  return readdirSync(dossier).flatMap((nom) => {
    const chemin = join(dossier, nom);
    return statSync(chemin).isDirectory() ? fichiers(chemin, extension) : chemin.endsWith(extension) ? [chemin] : [];
  });
}

/** Routes déclarées dans le backend : { methode, chemin, fichier }. */
function routesDuCode() {
  const routes = [];
  for (const f of fichiers(BACKEND, '.java')) {
    const s = readFileSync(f, 'utf8');
    if (!s.includes('Mapping')) continue;
    const base = s.match(/@RequestMapping\("([^"]+)"\)/)?.[1] ?? '';
    // Constantes de chemin : private static final String BASE = "/api/v1/…";
    const constantes = Object.fromEntries([...s.matchAll(/static\s+final\s+String\s+(\w+)\s*=\s*"([^"]*)"\s*;/g)].map((c) => [c[1], c[2]]));
    const evaluer = (expr) =>
      expr.split('+').map((t) => t.trim()).map((t) => (t.startsWith('"') ? t.slice(1, -1) : (constantes[t] ?? `{${t}}`))).join('');
    for (const m of s.matchAll(/@(Get|Post|Put|Delete|Patch)Mapping(?:\(\s*(?:(?:value|path)\s*=\s*)?((?:"[^"]*"|\w+)(?:\s*\+\s*(?:"[^"]*"|\w+))*)[^)]*\))?/g)) {
      routes.push({ methode: m[1].toUpperCase(), chemin: base + (m[2] ? evaluer(m[2]) : ''), fichier: relative(RACINE, f) });
    }
  }
  return routes;
}

/** Appels du frontend : { methode, chemin, fichier } (les ${…} deviennent des segments variables). */
function appelsDuFrontend() {
  const appels = [];
  const ajouter = (methode, brut, f) => {
    // ${a ? 'x' : 'y'} : on garde les deux possibilités
    const alternatives = brut.match(/\$\{[^}]*\?\s*'([^']+)'\s*:\s*'([^']+)'\s*\}/);
    const variantes = alternatives ? [brut.replace(alternatives[0], alternatives[1]), brut.replace(alternatives[0], alternatives[2])] : [brut];
    for (const v of variantes) {
      appels.push({ methode: methode.toUpperCase(), chemin: '/api/v1' + v.replace(/\$\{[^}]+\}/g, '{x}').split('?')[0], fichier: relative(RACINE, f) });
    }
  };
  for (const f of fichiers(FRONTEND, '.ts').filter((x) => !x.endsWith('.spec.ts'))) {
    const s = readFileSync(f, 'utf8');
    for (const m of s.matchAll(/\.(get|post|put|delete|patch)(?:<[^(]*>)?\(\s*`\$\{API\}([^`]+)`/g)) ajouter(m[1], m[2], f);
    for (const m of s.matchAll(/this\.(get|post|put|delete|patch)(?:<[^>]*>)?\(\s*[`'](\/[^`']+)[`']/g)) ajouter(m[1], m[2], f);
    for (const m of s.matchAll(/this\.(?:lire|fichier)(?:<[^>]*>)?\(\s*[`'](\/[^`']+)[`']/g)) ajouter('get', m[1], f);
  }
  const vus = new Set();
  return appels.filter((a) => !vus.has(a.methode + a.chemin) && vus.add(a.methode + a.chemin));
}

function motif(chemin) {
  return new RegExp('^' + chemin.replace(/\{[^}]+\}/g, '[^/]+') + '$');
}

function correspond(appel, routes) {
  return routes.some((r) => r.methode === appel.methode && motif(r.chemin).test(appel.chemin.replace(/\{x\}/g, 'x')));
}

let erreurs = 0;
const routes = routesDuCode();
const appels = appelsDuFrontend();

console.log(`\nCode source : ${appels.length} appels de l'application, ${routes.length} routes dans l'API.`);
const sansRoute = appels.filter((a) => !correspond(a, routes));
for (const a of sansRoute) {
  console.log(`  ✘ ${a.methode} ${a.chemin}  (appelé dans ${a.fichier}) : aucune route dans le backend`);
}
erreurs += sansRoute.length;
if (!sansRoute.length) console.log('  ✔ chaque appel de l’application a sa route.');

if (API) {
  let docs;
  try {
    const r = await fetch(API.replace(/\/$/, '') + '/v3/api-docs');
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    docs = await r.json();
  } catch (e) {
    console.log(`\nAPI en marche (${API}) : documentation injoignable (${e.message}). L'API tourne-t-elle avec le profil dev ?`);
    process.exit(1);
  }
  const enMarche = Object.entries(docs.paths ?? {}).flatMap(([chemin, methodes]) =>
    Object.keys(methodes).map((m) => ({ methode: m.toUpperCase(), chemin })),
  );
  console.log(`\nAPI en marche (${API}) : ${enMarche.length} routes.`);
  const absentes = routes.filter((r) => !enMarche.some((e) => e.methode === r.methode && motif(e.chemin).test(r.chemin.replace(/\{[^}]+\}/g, 'x'))));
  // Regroupées par contrôleur : un contrôleur entièrement absent = classe non compilée ou ancienne
  const parFichier = Map.groupBy(absentes, (r) => r.fichier);
  for (const [fichier, liste] of parFichier) {
    const total = routes.filter((r) => r.fichier === fichier).length;
    const nom = fichier.split('/').slice(-2).join('/');
    console.log(`  ✘ ${nom} : ${liste.length === total ? `les ${total} routes manquent` : `${liste.length} route(s) sur ${total} manquent`}`);
    if (liste.length !== total || process.argv.includes('--detail')) {
      for (const r of liste) console.log(`      ${r.methode} ${r.chemin}`);
    }
  }
  erreurs += absentes.length;
  if (absentes.length) {
    console.log(
      `\n  ${absentes.length} route(s) du code manquent dans l'API qui tourne : elle exécute des classes compilées qui ne` +
        '\n  correspondent pas au code (souvent Eclipse, qui garde les anciens .class quand sa compilation s’arrête).' +
        '\n  Arrêtez-la (Ctrl+C, ou Stop dans Eclipse), puis relancez-la avec Maven depuis ce dossier :' +
        '\n    cd backend && mvn clean spring-boot:run -Dspring-boot.run.profiles=dev' +
        '\n  (--detail : toutes les routes manquantes)',
    );
  } else {
    console.log('  ✔ l’API qui tourne a toutes les routes du code.');
  }
}

console.log('');
process.exit(erreurs ? 1 : 0);
