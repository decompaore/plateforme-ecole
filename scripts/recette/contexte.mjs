/**
 * Contexte partagé par tous les scénarios : l'état laissé par le peuplement (scripts/demo/etat-demo.json),
 * les sessions des comptes, et `partage`, où un scénario dépose ce qu'un scénario suivant réutilise.
 *
 *   ctx.etat                 contenu de etat-demo.json
 *   ctx.motDePasse           mot de passe de tous les comptes de démonstration
 *   ctx.aujourdhui           date du jour (AAAA-MM-JJ, UTC comme le serveur)
 *   ctx.sa                   session du super administrateur
 *   ctx.ecoles               les établissements (voir Ecole ci-dessous)
 *   ctx.ecole(cle)           un établissement par numéro (1, 2, 3) ou code (« lt-batisseurs »)
 *   ctx.compte(tel, etabId)  session d'un compte existant (mot de passe de démonstration), mise en cache
 *   ctx.activer(tel, provisoire, etabId)  premier usage d'un compte créé par la recette : remplace le mot de passe
 *                            provisoire par ctx.motDePasse et renvoie sa session
 *   ctx.partage              objet libre : territoire, comptes créés, identifiants…
 */
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { appelBrut, Echec, Session } from './lib.mjs';

export const FICHIER_ETAT = join(dirname(fileURLToPath(import.meta.url)), '..', 'demo', 'etat-demo.json');

/**
 * Un établissement de démonstration et ses comptes.
 *   e.n, e.code, e.nom, e.id, e.profil (TECHNIQUE, PROFESSIONNEL, GENERAL), e.anneeId, e.chefTravaux
 *   e.classes[]       { id, code, niveau, filiere, diplome, matieres: [{ id, code, type, enseignant (téléphone) }] }
 *   e.enseignants[]   { matiere, telephone, nom, engagementId, type, partage }
 *   e.familles[]      { telephone, nom, enfants: [{ ecole, eleveId, inscriptionId, classe, nom, prenoms, sexe }] }
 *   e.periodes[]      { id, libelle, debut, fin }
 *   e.ateliers[]      { id, code, nom, filiere }
 *   e.session(role)   ADMIN_ECOLE, CENSEUR, CHEF_TRAVAUX, SECRETARIAT, INTENDANT, SURVEILLANT (null si absent)
 *   e.enseignant(code) session de l'enseignant d'une matière (code du catalogue, ex. MATH)
 *   e.parent(i)       session du i-ème parent qui n'a d'enfants que dans cet établissement
 *   e.famille(i)      la famille correspondante
 */
class Ecole {
  constructor(ctx, donnees) {
    Object.assign(this, donnees);
    this.ctx = ctx;
  }

  session(role) {
    const tel = role === 'ADMIN_ECOLE' ? this.admin : this.personnel?.[role];
    return tel ? this.ctx.compte(tel, this.id, `${role} ${this.code}`) : null;
  }

  enseignant(matiere) {
    const p = this.enseignants.find((x) => x.matiere === matiere);
    return p ? this.ctx.compte(p.telephone, this.id, `enseignant ${matiere} ${this.code}`) : null;
  }

  /** Familles dont tous les enfants sont dans cet établissement (pas de choix d'établissement). */
  famillesLocales() {
    return this.familles.filter((f) => f.enfants.length && f.enfants.every((x) => x.ecole === this.n));
  }

  famille(i = 0) {
    return this.famillesLocales()[i] ?? null;
  }

  parent(i = 0) {
    const f = this.famille(i);
    return f ? this.ctx.compte(f.telephone, this.id, `parent ${this.code}`) : null;
  }

  classe(code) {
    return this.classes.find((c) => c.code === code) ?? null;
  }
}

export async function creerContexte() {
  if (!existsSync(FICHIER_ETAT)) {
    throw new Error(
      `${FICHIER_ETAT} est absent : lancez d'abord le peuplement (scripts/recette/recette-grandeur-nature.sh, ` +
        'ou scripts/demo/mvp-grandeur-nature.sh).',
    );
  }
  const etat = JSON.parse(readFileSync(FICHIER_ETAT, 'utf8'));
  const sessions = new Map();
  const ctx = {
    etat,
    motDePasse: etat.motDePasse,
    aujourdhui: new Date().toISOString().slice(0, 10),
    partage: {},
    compte(telephone, etablissementId = null, libelle = telephone) {
      const cle = `${telephone}|${etablissementId ?? ''}`;
      if (!sessions.has(cle)) sessions.set(cle, new Session(telephone, etat.motDePasse, etablissementId, libelle));
      return sessions.get(cle);
    },
    async activer(telephone, provisoire, etablissementId = null) {
      if (provisoire) {
        const r = await appelBrut('POST', '/auth/connexion', { telephone, motDePasse: provisoire });
        if (!r.ok) throw new Echec(`Première connexion impossible (${telephone}) : ${r.resume()}`);
        let s = r.donnees;
        if (s.selectionRequise) {
          const choix = s.etablissements.find((e) => e.id === etablissementId) ?? s.etablissements[0];
          const r2 = await appelBrut('POST', '/auth/etablissement', { etablissementId: choix.id }, { jeton: s.jetonSelection });
          if (!r2.ok) throw new Echec(`Choix de l'établissement impossible (${telephone}) : ${r2.resume()}`);
          s = r2.donnees;
        }
        if (s.doitChangerMotDePasse) {
          const m = await appelBrut('POST', '/moi/mot-de-passe', { motDePasseActuel: provisoire, nouveauMotDePasse: etat.motDePasse },
            { jeton: s.jetonAcces });
          if (!m.ok) throw new Echec(`Changement du mot de passe provisoire impossible (${telephone}) : ${m.resume()}`);
        }
      }
      sessions.delete(`${telephone}|${etablissementId ?? ''}`);
      return ctx.compte(telephone, etablissementId);
    },
  };
  ctx.sa = new Session(etat.superAdmin.telephone, etat.superAdmin.motDePasse, null, 'super administrateur');
  ctx.ecoles = etat.ecoles.map((e) => new Ecole(ctx, e));
  ctx.ecole = (cle) => ctx.ecoles.find((e) => e.n === cle || e.code === cle) ?? null;
  return ctx;
}
