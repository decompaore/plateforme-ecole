#!/usr/bin/env node
/**
 * RECETTE GRANDEUR NATURE : vérifie, par l'API et comme le ferait chaque utilisateur, tous les parcours
 * de l'application sur une base peuplée par scripts/demo/peupler-mvp.mjs (trois établissements qui vivent
 * depuis la rentrée). Elle ajoute ce que le peuplement ne crée pas (territoire, administrateurs pays,
 * directions, moyens de paiement, virements, exports…) et contrôle les droits, l'isolation entre
 * établissements, les règles métier et les cas d'erreur.
 *
 * DÉVELOPPEMENT UNIQUEMENT. Normalement lancée par scripts/recette/recette-grandeur-nature.sh.
 *
 *   node scripts/recette/recette.mjs                  tous les scénarios
 *   node scripts/recette/recette.mjs --domaines=SEC,PAI   seulement les identifiants commençant ainsi
 *   node scripts/recette/recette.mjs --liste          liste les scénarios sans rien exécuter
 *
 * Rapport : scripts/recette/rapport-recette.md (et .json). Code de sortie 1 s'il y a des échecs.
 */
import { readdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import { creerContexte } from './contexte.mjs';
import { API, executer, rapportMarkdown, scenarios } from './lib.mjs';

const ICI = dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const liste = args.includes('--liste');
const domaines = (args.find((a) => a.startsWith('--domaines='))?.split('=')[1] ?? '')
  .split(',')
  .map((x) => x.trim().toUpperCase())
  .filter(Boolean);

// Les fichiers de scénarios s'exécutent dans l'ordre de leur nom (01-…, 02-…) ; la fin d'année en dernier.
for (const f of readdirSync(join(ICI, 'domaines')).filter((x) => x.endsWith('.mjs')).sort()) {
  await import(pathToFileURL(join(ICI, 'domaines', f)).href);
}

const retenus = (s) => !domaines.length || domaines.some((d) => s.id.toUpperCase().startsWith(d));

if (liste) {
  let domaine = null;
  for (const s of scenarios().filter(retenus)) {
    if (s.domaine !== domaine) {
      domaine = s.domaine;
      console.log(`\n${domaine}`);
    }
    console.log(`  ${s.id.padEnd(9)} ${s.titre}`);
  }
  console.log(`\n${scenarios().filter(retenus).length} scénarios.`);
  process.exit(0);
}

const debut = Date.now();
let ctx;
try {
  ctx = await creerContexte();
} catch (e) {
  console.error(`\n✘ ${e.message}\n`);
  process.exit(2);
}
console.log(`\nRecette grandeur nature — API ${API}`);
console.log(`Base peuplée le ${new Date(ctx.etat.genereLe).toLocaleString('fr-FR')} · année ${ctx.etat.annee.libelle} · ${ctx.ecoles.length} établissements`);

const resultats = await executer(ctx, retenus);

const duree = Math.round((Date.now() - debut) / 1000);
const n = (e) => resultats.filter((r) => r.etat === e).length;
const entete = [
  `Exécutée le ${new Date().toLocaleString('fr-FR')} contre ${API} en ${Math.floor(duree / 60)} min ${duree % 60} s.`,
  `Base peuplée le ${new Date(ctx.etat.genereLe).toLocaleString('fr-FR')} (année ${ctx.etat.annee.libelle}) : ` +
    ctx.ecoles.map((e) => `${e.nom} (${e.code})`).join(', ') + '.',
  domaines.length ? `Domaines retenus : ${domaines.join(', ')}.` : 'Tous les domaines.',
];
writeFileSync(join(ICI, 'rapport-recette.md'), rapportMarkdown(resultats, entete));
writeFileSync(join(ICI, 'rapport-recette.json'), JSON.stringify({ entete, resultats }, null, 2));

console.log(`\n━━ Bilan (${Math.floor(duree / 60)} min ${duree % 60} s)`);
console.log(`  ✔ ${n('RÉUSSI')} réussis · ✘ ${n('ÉCHEC')} en échec · – ${n('IGNORÉ')} ignorés sur ${resultats.length} scénarios`);
for (const r of resultats.filter((x) => x.etat === 'ÉCHEC')) {
  console.log(`    ✘ ${r.id} ${r.titre}\n        ${r.detail}`);
}
console.log('\n  Rapport : scripts/recette/rapport-recette.md\n');
process.exit(n('ÉCHEC') ? 1 : 0);
