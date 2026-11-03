#!/usr/bin/env node
// Génère la paire de clés VAPID des notifications « push » (Web Push, RFC 8292).
//
//   node scripts/push/generer-cles-vapid.mjs
//
// Copier les deux valeurs dans l'environnement du serveur (jamais dans le dépôt) :
//   PUSH_CLE_PUBLIQUE=...   PUSH_CLE_PRIVEE=...   PUSH_SUJET=mailto:exploitation@exemple.bf
// Changer les clés invalide les abonnements existants : les parents devront réactiver
// les notifications sur leur téléphone. Gardez-les donc d'une version à l'autre.
import { generateKeyPairSync } from 'node:crypto';

const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const jwk = privateKey.export({ format: 'jwk' });
const publique = Buffer.concat([
  Buffer.from([0x04]),
  Buffer.from(jwk.x, 'base64url'),
  Buffer.from(jwk.y, 'base64url'),
]).toString('base64url');

console.log(`PUSH_CLE_PUBLIQUE=${publique}`);
console.log(`PUSH_CLE_PRIVEE=${jwk.d}`);
