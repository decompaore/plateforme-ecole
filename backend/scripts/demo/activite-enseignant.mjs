#!/usr/bin/env node
/**
 * Simule plusieurs semaines de travail d'un enseignant dans ses classes, par l'API, pour
 * essayer l'application avec des données réalistes :
 *   - un emploi du temps (deux séances par semaine pour chaque classe et matière) ;
 *   - l'appel de chaque séance passée : quelques absents (certains élèves bien plus que
 *     d'autres) et quelques retards ;
 *   - une interrogation et un devoir notés par classe et matière dans la période en cours ;
 *   - deux avertissements donnés en classe ;
 *   - avec le compte de l'administration (facultatif) : une partie des absences justifiées et
 *     les parents de l'élève le plus absent convoqués.
 *
 * DÉVELOPPEMENT UNIQUEMENT. À lancer après creer-etablissement-demo.mjs (API lancée) :
 *   node scripts/demo/activite-enseignant.mjs <téléphone enseignant> [téléphone administrateur]
 *
 * Variables facultatives :
 *   API=http://localhost:8080        adresse de l'API
 *   MOT_DE_PASSE=Demo2026            mot de passe des comptes (celui du script de démonstration)
 *   SEMAINES=4                       nombre de semaines d'historique (au plus depuis la rentrée)
 *
 * Relancer le script ne crée pas de doublons : les appels et les évaluations portent des
 * identifiants calculés à partir de la classe, de la matière et de la date (le serveur
 * répond « déjà reçu ») ; seules les notes sont réécrites à l'identique.
 */
import { createHash } from 'node:crypto';

const API = (process.env.API ?? 'http://localhost:8080').replace(/\/$/, '') + '/api/v1';
const MOT_DE_PASSE = process.env.MOT_DE_PASSE ?? 'Demo2026';
const SEMAINES = Math.max(1, Number(process.env.SEMAINES ?? 4));
const [telEnseignant, telAdmin] = process.argv.slice(2);

/** Créneaux de la semaine (1 = lundi) ; chaque classe-matière en reçoit deux, sans chevauchement. */
const CRENEAUX = [
  { jour: 1, debut: '07:00', fin: '09:00' },
  { jour: 3, debut: '10:00', fin: '12:00' },
  { jour: 2, debut: '07:00', fin: '09:00' },
  { jour: 4, debut: '15:00', fin: '17:00' },
  { jour: 1, debut: '10:00', fin: '12:00' },
  { jour: 5, debut: '07:00', fin: '09:00' },
  { jour: 2, debut: '15:00', fin: '17:00' },
  { jour: 4, debut: '07:00', fin: '09:00' },
  { jour: 3, debut: '07:00', fin: '09:00' },
  { jour: 5, debut: '10:00', fin: '12:00' },
];
const MINUTES_RETARD = [5, 10, 10, 15, 15, 20, 30];
const MOTIFS_JUSTIFICATION = [
  ['MALADIE', 'Certificat du CSPS'],
  ['MALADIE', 'Paludisme, mot du père'],
  ['FAMILLE', 'Funérailles au village'],
  ['FAMILLE', 'Mot de la mère'],
];

// ---------------------------------------------------------------- outils

if (!telEnseignant) {
  arreter(
    'Indiquez le téléphone de l’enseignant (affiché par creer-etablissement-demo.mjs) :\n' +
      '  node scripts/demo/activite-enseignant.mjs 61xxxxxx [60xxxxxx]\n' +
      '  (le second numéro, facultatif, est celui de l’administrateur : justificatifs et convocation)',
  );
}

for (const tel of [telEnseignant, telAdmin].filter(Boolean)) {
  if (!/^\+?[0-9 ]{8,15}$/.test(tel)) {
    arreter(
      `« ${tel} » n’est pas un numéro : remplacez-le par le vrai téléphone affiché à la fin de\n` +
        '  creer-etablissement-demo.mjs (8 chiffres, par exemple 61234567).',
    );
  }
}

/** Tirage pseudo-aléatoire reproductible : relancer le script donne les mêmes absences et notes. */
function hasard(graine) {
  let etat = graine >>> 0 || 1;
  return () => {
    etat = (etat * 1664525 + 1013904223) >>> 0;
    return etat / 4294967296;
  };
}

function graineDe(texte) {
  return createHash('sha256').update(texte).digest().readUInt32BE(0);
}

/** UUID stable calculé à partir d'un texte (identifiants d'appareil pour l'idempotence). */
function uuidStable(texte) {
  const h = createHash('sha256').update(texte).digest('hex');
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-4${h.slice(13, 16)}-a${h.slice(17, 20)}-${h.slice(20, 32)}`;
}

/** Loi normale (Box-Muller) à partir d'un tirage uniforme. */
function normale(tirage, moyenne, ecart) {
  const u = Math.max(tirage(), 1e-9);
  const v = tirage();
  return moyenne + ecart * Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
}

function iso(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function jourDe(isoDate) {
  const [a, m, j] = isoDate.split('-').map(Number);
  return new Date(a, m - 1, j);
}

function ajouterJours(isoDate, n) {
  const d = jourDe(isoDate);
  d.setDate(d.getDate() + n);
  return iso(d);
}

function arreter(message) {
  console.error(`\n✘ ${message}\n`);
  process.exit(1);
}

function etape(texte) {
  process.stdout.write(`• ${texte}… `);
}

function ok(texte = 'ok') {
  console.log(texte);
}

async function api(methode, chemin, corps, jeton) {
  let reponse;
  try {
    reponse = await fetch(API + chemin, {
      method: methode,
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        ...(jeton ? { Authorization: `Bearer ${jeton}` } : {}),
      },
      body: corps === undefined ? undefined : JSON.stringify(corps),
    });
  } catch (e) {
    arreter(`API injoignable à ${API} (${e.cause?.code ?? e.message}).`);
  }
  const texte = await reponse.text();
  const donnees = texte ? JSON.parse(texte) : null;
  if (!reponse.ok) {
    const detail = donnees?.detail ?? donnees?.title ?? texte;
    const code = donnees?.code ? ` [${donnees.code}]` : '';
    arreter(`${methode} ${chemin} → ${reponse.status}${code} : ${detail}`);
  }
  return donnees;
}

async function session(telephone, role) {
  const r = await api('POST', '/auth/connexion', { telephone, motDePasse: MOT_DE_PASSE });
  let s = r;
  if (r.selectionRequise) {
    const choix = r.etablissements.find((e) => e.roles.includes(role)) ?? r.etablissements[0];
    s = await api('POST', '/auth/etablissement', { etablissementId: choix.id }, r.jetonSelection);
  }
  if (s.doitChangerMotDePasse) {
    arreter(`Le compte ${telephone} a encore son mot de passe provisoire : connectez-vous une fois dans l'application.`);
  }
  const roles = s.etablissementActif?.roles ?? [];
  const attendus = role === 'ENSEIGNANT' ? ['ENSEIGNANT'] : ['ADMIN_ECOLE', 'CENSEUR', 'SURVEILLANT'];
  if (!roles.some((r) => attendus.includes(r))) {
    const qui = role === 'ENSEIGNANT' ? "l'enseignant (1er numéro)" : "l'administration (2e numéro)";
    arreter(
      `Le ${telephone} n'a pas le bon rôle pour ${qui} : ses rôles sont ${roles.join(', ') || 'aucun'}.\n` +
        '  L’ordre est : téléphone de l’ENSEIGNANT d’abord, puis celui de l’ADMINISTRATEUR.\n' +
        '  node scripts/demo/activite-enseignant.mjs <enseignant> <administrateur>',
    );
  }
  return s;
}

// ---------------------------------------------------------------- scénario

async function principal() {
  console.log(`\nActivité d'un enseignant — API ${API}\n`);

  etape('Connexion de l’enseignant');
  const ens = await session(telEnseignant, 'ENSEIGNANT');
  const jeton = ens.jetonAcces;
  ok(ens.etablissementActif?.nom ?? '');

  etape('Classes et matières');
  const fiche = await api('GET', '/espace-enseignant/affectations', undefined, jeton);
  if (!fiche.affectations.length) {
    arreter("Cet enseignant n'a aucune matière affectée : confiez-lui des matières dans le programme d'une classe.");
  }
  const annee = await api('GET', `/annees/${fiche.anneeId}`, undefined, jeton);
  const periodes = await api('GET', `/annees/${fiche.anneeId}/periodes`, undefined, jeton);
  const affectations = [...fiche.affectations].sort(
    (a, b) => a.classeCode.localeCompare(b.classeCode) || a.matiereLibelle.localeCompare(b.matiereLibelle),
  );
  ok(affectations.map((a) => `${a.classeCode} · ${a.matiereLibelle}`).join(', '));

  // Élèves de chaque classe, avec leur « profil » d'assiduité et leur niveau, stables d'une exécution à l'autre
  const classes = new Map();
  for (const a of affectations) {
    if (classes.has(a.classeId)) continue;
    const [inscrits, classe] = await Promise.all([
      api('GET', `/classes/${a.classeId}/inscriptions`, undefined, jeton),
      api('GET', `/classes/${a.classeId}`, undefined, jeton),
    ]);
    const eleves = inscrits
      .filter((i) => i.statut === 'ACTIVE')
      .map((i) => {
        const t = hasard(graineDe(i.id));
        const profil = t();
        return {
          ...i,
          // 70 % viennent presque toujours, 20 % manquent parfois, 10 % manquent souvent
          tauxAbsence: profil < 0.7 ? 0.02 : profil < 0.9 ? 0.08 : 0.25,
          tauxRetard: 0.005 + t() * 0.02,
          niveau: Math.min(17, Math.max(5, normale(t, 11, 2.6))),
        };
      });
    classes.set(a.classeId, { code: a.classeCode, profilId: classe.profilId, eleves });
  }

  // ---------------------------------------------------------------- appels
  const aujourdhui = iso(new Date());
  const maintenant = `${String(new Date().getHours()).padStart(2, '0')}:${String(new Date().getMinutes()).padStart(2, '0')}`;
  const debut = [annee.debut, ajouterJours(aujourdhui, -7 * SEMAINES)].sort().at(-1);
  const emploi = affectations.map((a, i) => ({
    ...a,
    creneaux: [CRENEAUX[(2 * i) % CRENEAUX.length], CRENEAUX[(2 * i + 1) % CRENEAUX.length]],
  }));

  const appels = [];
  /** Absences de chaque élève, pour les justificatifs et la convocation. */
  const absencesParEleve = new Map();
  for (let jour = debut; jour <= aujourdhui; jour = ajouterJours(jour, 1)) {
    const jourSemaine = jourDe(jour).getDay();
    for (const a of emploi) {
      for (const c of a.creneaux.filter((x) => x.jour === jourSemaine)) {
        if (jour === aujourdhui && c.fin > maintenant) continue; // séance pas encore finie
        const t = hasard(graineDe(`${a.classeId}|${a.matiereId}|${jour}|${c.debut}`));
        const marques = [];
        for (const e of classes.get(a.classeId).eleves) {
          const x = t();
          if (x < e.tauxAbsence) {
            marques.push({ inscriptionId: e.id, type: 'ABSENCE' });
            const l = absencesParEleve.get(e.id) ?? [];
            l.push(jour);
            absencesParEleve.set(e.id, l);
          } else if (x < e.tauxAbsence + e.tauxRetard) {
            marques.push({ inscriptionId: e.id, type: 'RETARD', minutesRetard: MINUTES_RETARD[Math.floor(t() * MINUTES_RETARD.length)] });
          }
        }
        appels.push({
          idClient: uuidStable(`appel|${a.classeId}|${a.matiereId}|${jour}|${c.debut}`),
          classeId: a.classeId,
          matiereId: a.matiereId,
          date: jour,
          heureDebut: c.debut,
          heureFin: c.fin,
          saisiLe: new Date(`${jour}T${c.debut}:00`).toISOString(),
          marques,
        });
      }
    }
  }

  etape(`Appels du ${debut} au ${aujourdhui}`);
  const bilan = { ENREGISTRE: 0, DEJA_RECU: 0, MODIFIE: 0, REFUSE: 0 };
  let absences = 0;
  let retards = 0;
  for (let i = 0; i < appels.length; i += 50) {
    const accuses = await api('POST', '/appels/lot', { appels: appels.slice(i, i + 50) }, jeton);
    for (const r of accuses) {
      bilan[r.statut] = (bilan[r.statut] ?? 0) + 1;
      absences += r.absents ?? 0;
      retards += r.retards ?? 0;
      if (r.statut === 'REFUSE') {
        console.log(`\n    refusé : ${r.code} ${r.message}`);
      }
    }
  }
  ok(`${appels.length} séances (${bilan.ENREGISTRE} nouvelles, ${bilan.DEJA_RECU} déjà reçues), ${absences} absences, ${retards} retards`);

  // ---------------------------------------------------------------- notes
  etape('Évaluations et notes');
  let nbEvaluations = 0;
  let nbNotes = 0;
  for (const a of emploi) {
    const classe = classes.get(a.classeId);
    const periode =
      periodes.find((p) => p.profilId === classe.profilId && p.debut <= aujourdhui && aujourdhui <= p.fin) ??
      periodes.filter((p) => p.profilId === classe.profilId).sort((x, y) => x.ordre - y.ordre)[0];
    if (!periode || periode.verrouillee) continue;
    const prevues = [
      { libelle: 'Interrogation 1', type: 'INTERROGATION', decalage: 12, bareme: 10, poids: 1 },
      { libelle: 'Devoir 1', type: 'DEVOIR', decalage: 26, bareme: 20, poids: 2 },
    ];
    for (const ev of prevues) {
      const date = ajouterJours(periode.debut, ev.decalage);
      if (date > aujourdhui || date > periode.fin) continue;
      const evaluation = await api('POST', `/classes/${a.classeId}/evaluations`, {
        idClient: uuidStable(`evaluation|${a.classeId}|${a.matiereId}|${periode.id}|${ev.libelle}`),
        matiereId: a.matiereId,
        periodeId: periode.id,
        libelle: ev.libelle,
        type: ev.type,
        date,
        bareme: ev.bareme,
        poids: ev.poids,
      }, jeton);
      const t = hasard(graineDe(`notes|${evaluation.id}`));
      const notes = classe.eleves.map((e) => {
        if (t() < e.tauxAbsence) {
          return { inscriptionId: e.id, absent: true };
        }
        const sur20 = Math.min(20, Math.max(0, normale(t, e.niveau, 2.4)));
        const valeur = Math.round(((sur20 * ev.bareme) / 20) * 4) / 4; // au quart de point
        return { inscriptionId: e.id, valeur };
      });
      await api('PUT', `/evaluations/${evaluation.id}/notes`, { notes }, jeton);
      nbEvaluations++;
      nbNotes += notes.filter((n) => !n.absent).length;
    }
  }
  ok(`${nbEvaluations} évaluations, ${nbNotes} notes`);

  // ---------------------------------------------------------------- vie de classe
  etape('Avertissements en classe');
  const tousLesEleves = [...classes.values()].flatMap((c) => c.eleves.map((e) => ({ ...e, classe: c.code })));
  if (bilan.ENREGISTRE === 0) {
    // Les appels existaient déjà : le script a déjà tourné, on ne redonne pas les avertissements
    ok('déjà donnés lors d’une exécution précédente');
  } else {
    const bavards = [...tousLesEleves].sort((x, y) => graineDe(`bavard|${x.id}`) - graineDe(`bavard|${y.id}`)).slice(0, 2);
    const date = ajouterJours(aujourdhui, -2) >= debut ? ajouterJours(aujourdhui, -2) : aujourdhui;
    for (const e of bavards) {
      await api('POST', `/inscriptions/${e.id}/incidents`, {
        type: 'AVERTISSEMENT',
        date,
        motif: 'Bavardages répétés pendant les travaux pratiques',
      }, jeton);
    }
    ok(`${bavards.map((e) => `${e.prenoms} ${e.nom} (${e.classe})`).join(', ')} ; familles prévenues par SMS`);
  }

  // ---------------------------------------------------------------- administration (facultatif)
  if (telAdmin) {
    etape('Connexion de l’administration');
    const adm = (await session(telAdmin, 'ADMIN_ECOLE')).jetonAcces;
    ok();

    etape('Justificatifs');
    let justifies = 0;
    for (const [inscriptionId, jours] of absencesParEleve) {
      const deja = await api('GET', `/inscriptions/${inscriptionId}/justificatifs`, undefined, adm);
      for (const jour of [...new Set(jours)]) {
        const t = hasard(graineDe(`justif|${inscriptionId}|${jour}`));
        if (t() > 0.55 || deja.some((j) => j.du <= jour && jour <= j.au)) continue;
        const [type, motif] = MOTIFS_JUSTIFICATION[Math.floor(t() * MOTIFS_JUSTIFICATION.length)];
        await api('POST', `/inscriptions/${inscriptionId}/justificatifs`, { du: jour, au: jour, type, motif }, adm);
        justifies++;
      }
    }
    ok(`${justifies} journée(s) justifiée(s), les autres restent à justifier`);

    etape('Convocation');
    const plusAbsent = [...absencesParEleve.entries()].sort((x, y) => y[1].length - x[1].length)[0];
    if (plusAbsent && plusAbsent[1].length >= 3) {
      const eleve = tousLesEleves.find((e) => e.id === plusAbsent[0]);
      const historique = await api('GET', `/inscriptions/${plusAbsent[0]}/vie-scolaire`, undefined, adm);
      if (historique.convocations.some((c) => c.statut === 'PREVUE')) {
        ok('déjà prévue');
      } else {
        let rdv = ajouterJours(aujourdhui, 3);
        while ([0, 6].includes(jourDe(rdv).getDay())) rdv = ajouterJours(rdv, 1);
        await api('POST', `/inscriptions/${plusAbsent[0]}/convocations`, {
          rendezVous: `${rdv}T10:00`,
          motif: `Absences répétées (${plusAbsent[1].length} séances manquées)`,
        }, adm);
        ok(`parents de ${eleve.prenoms} ${eleve.nom} (${eleve.classe}) le ${rdv} à 10h00`);
      }
    } else {
      ok('aucun élève assez absent');
    }
  }

  console.log(`
✔ Historique prêt.

  Enseignant : « Mes envois », « Saisie des notes » (les évaluations et leurs notes), « Faire l'appel ».
  Vie scolaire (administrateur ou surveillant) : « Absences du jour » (changez de jour), « Classes »
  (absences par discipline et par élève), fiche d'un élève.
  Parent : le parent de démonstration voit les absences, les avertissements et la convocation de son enfant
  s'il est concerné.
`);
}

principal().catch((e) => arreter(e.stack ?? String(e)));
