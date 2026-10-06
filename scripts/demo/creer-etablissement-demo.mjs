#!/usr/bin/env node
/**
 * Crée, par l'API, un établissement de démonstration complet pour essayer l'application :
 * lycée technique, année scolaire ouverte (contenant la date du jour), filière F3,
 * deux classes avec leurs matières, un enseignant affecté et une cinquantaine d'élèves
 * inscrits avec un parent chacun ; frais de scolarité en trois tranches, Mobile Money en
 * simulation, et l'espace parent ouvert pour le parent du premier élève (avec une absence
 * du jour à consulter).
 *
 * DÉVELOPPEMENT UNIQUEMENT : mots de passe connus, numéros de téléphone fictifs.
 *
 * Utilisation (API lancée avec le profil dev) :
 *   node scripts/demo/creer-etablissement-demo.mjs
 *
 * Variables facultatives :
 *   API=http://localhost:8080             adresse de l'API
 *   SUPER_ADMIN_TELEPHONE=70000000
 *   SUPER_ADMIN_MOT_DE_PASSE=...          si vous avez changé le mot de passe du super administrateur
 *   DEMO_MOT_DE_PASSE=Demo2026            mot de passe donné aux comptes créés
 *
 * Chaque exécution crée un nouvel établissement (code et numéros tirés au hasard) :
 * le script peut être relancé autant de fois que nécessaire.
 */

const API = (process.env.API ?? 'http://localhost:8080').replace(/\/$/, '') + '/api/v1';
const SUPER_ADMIN_TELEPHONE = process.env.SUPER_ADMIN_TELEPHONE ?? '70000000';
const SUPER_ADMIN_MOT_DE_PASSE = process.env.SUPER_ADMIN_MOT_DE_PASSE ?? 'ChangezMoi-Dev-2026';
const DEMO_MOT_DE_PASSE = process.env.DEMO_MOT_DE_PASSE ?? 'Demo2026';

if (DEMO_MOT_DE_PASSE.length < 8 || !/[A-Za-z]/.test(DEMO_MOT_DE_PASSE) || !/[0-9]/.test(DEMO_MOT_DE_PASSE)) {
  arreter('DEMO_MOT_DE_PASSE doit faire au moins 8 caractères, avec une lettre et un chiffre.');
}

// ---------------------------------------------------------------- données

const NOMS = [
  'OUEDRAOGO', 'SAWADOGO', 'KABORE', 'COMPAORE', 'ZONGO', 'TRAORE', 'ILBOUDO', 'NIKIEMA', 'KONATE', 'SANOU',
  'OUATTARA', 'DIALLO', 'YAMEOGO', 'ZOUNGRANA', 'KAFANDO', 'BAMBARA', 'TAPSOBA', 'SOME', 'DABIRE', 'BONKOUNGOU',
  'KIEMA', 'ROAMBA', 'BELEM', 'KINDA', 'NANA', 'TIENDREBEOGO', 'GUIGMA', 'ZERBO', 'COULIBALY', 'KY',
];
const PRENOMS_F = [
  'Awa', 'Aïcha', 'Fatimata', 'Rasmata', 'Mariam', 'Salimata', 'Aminata', 'Adjaratou', 'Pauline', 'Estelle',
  'Nafissatou', 'Clarisse', 'Bintou', 'Alimata', 'Safiatou', 'Delphine', 'Rokia', 'Assétou', 'Carine', 'Habibou',
];
const PRENOMS_M = [
  'Ali', 'Moussa', 'Salif', 'Issouf', 'Rasmané', 'Boukary', 'Abdoul Aziz', 'Ousmane', 'Idrissa', 'Seydou',
  'Wendkouni', 'Arouna', 'Hamidou', 'Jean-Baptiste', 'Serge', 'Yacouba', 'Adama', 'Karim', 'Désiré', 'Boureima',
];

const CLASSES = [
  { code: '2nde F3', niveau: '2nde', eleves: 24, naissance: 2010 },
  { code: '1re F3', niveau: '1re', eleves: 22, naissance: 2009 },
];

/** Matières de la filière F3 (électrotechnique) : le profil technique regroupe les matières. */
const MATIERES = [
  { code: 'MATH', libelle: 'Mathématiques', type: 'GENERALE', groupe: 'Matières générales', coefficient: 4, volumeHebdo: 4 },
  { code: 'FR', libelle: 'Français', type: 'GENERALE', groupe: 'Matières générales', coefficient: 2, volumeHebdo: 3 },
  { code: 'PC', libelle: 'Physique-chimie', type: 'GENERALE', groupe: 'Matières générales', coefficient: 3, volumeHebdo: 3 },
  { code: 'ELEC', libelle: 'Électrotechnique', type: 'TECHNIQUE', groupe: 'Matières techniques', coefficient: 5, volumeHebdo: 6 },
  { code: 'DESSIN', libelle: 'Dessin technique', type: 'TECHNIQUE', groupe: 'Matières techniques', coefficient: 3, volumeHebdo: 3 },
  { code: 'TP-ELEC', libelle: 'Travaux pratiques d’électricité', type: 'PRATIQUE', groupe: 'Matières techniques', coefficient: 4, volumeHebdo: 4 },
];

/** Matières assurées par l'enseignant de démonstration, dans chaque classe. */
const MATIERES_ENSEIGNANT = ['ELEC', 'TP-ELEC'];

// ---------------------------------------------------------------- outils

const suffixe = String(Math.floor(1000 + Math.random() * 9000));
const tirage = hasard(Number(suffixe));
let compteurTelephone = Math.floor(tirage() * 900000);

/** Numéro fictif à 8 chiffres, différent à chaque appel (préfixe 6x, rarement attribué). */
function telephone(prefixe) {
  compteurTelephone = (compteurTelephone + 1 + Math.floor(tirage() * 97)) % 1000000;
  return `${prefixe}${String(compteurTelephone).padStart(6, '0')}`;
}

function hasard(graine) {
  let s = graine >>> 0 || 1;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 2 ** 32;
  };
}

function choisir(liste) {
  return liste[Math.floor(tirage() * liste.length)];
}

function iso(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function arreter(message) {
  console.error(`\n✘ ${message}`);
  process.exit(1);
}

/** Appel de l'API. En cas d'erreur, affiche le message du serveur (Problem Details) et s'arrête. */
/** `facultatif` : une erreur du serveur est renvoyée ({ erreur }) au lieu d'arrêter le script. */
async function api(methode, chemin, corps, jeton, { facultatif = false } = {}) {
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
    arreter(`API injoignable à ${API} (${e.cause?.code ?? e.message}).\n  Lancez-la d'abord : cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev`);
  }
  const texte = await reponse.text();
  const donnees = texte ? JSON.parse(texte) : null;
  if (!reponse.ok) {
    const detail = donnees?.detail ?? donnees?.title ?? texte;
    const code = donnees?.code ? ` [${donnees.code}]` : '';
    const champs = donnees?.erreurs ? `\n  ${JSON.stringify(donnees.erreurs)}` : '';
    const conseil = chemin === '/auth/connexion' && reponse.status === 401 && corps?.telephone === SUPER_ADMIN_TELEPHONE
      ? '\n  Si vous avez changé le mot de passe du super administrateur, indiquez-le :' +
        '\n  SUPER_ADMIN_MOT_DE_PASSE=votre-mot-de-passe node scripts/demo/creer-etablissement-demo.mjs'
      : '';
    if (facultatif) {
      return { erreur: `${reponse.status}${code} : ${detail}` };
    }
    arreter(`${methode} ${chemin} → ${reponse.status}${code} : ${detail}${champs}${conseil}`);
  }
  return donnees;
}

async function connexion(telephone, motDePasse) {
  const r = await api('POST', '/auth/connexion', { telephone, motDePasse });
  if (r.selectionRequise) {
    // Le compte de démonstration n'appartient qu'à un établissement ; par sécurité on prend le premier
    const choix = await api('POST', '/auth/etablissement', { etablissementId: r.etablissements[0].id }, r.jetonSelection);
    return choix;
  }
  return r;
}

/** Remplace le mot de passe provisoire par le mot de passe de démonstration, puis rouvre la session. */
async function sessionDemo(telephone, motDePasseTemporaire) {
  const r = await connexion(telephone, motDePasseTemporaire);
  if (r.doitChangerMotDePasse) {
    await api('POST', '/moi/mot-de-passe', {
      motDePasseActuel: motDePasseTemporaire,
      nouveauMotDePasse: DEMO_MOT_DE_PASSE,
    }, r.jetonAcces);
    return (await connexion(telephone, DEMO_MOT_DE_PASSE)).jetonAcces;
  }
  return r.jetonAcces;
}

function etape(texte) {
  process.stdout.write(`• ${texte}… `);
}
function ok(texte = 'ok') {
  console.log(texte);
}

// ---------------------------------------------------------------- scénario

async function principal() {
  console.log(`\nÉtablissement de démonstration — API ${API}\n`);

  // 1. Super administrateur : création de l'établissement et de son administrateur
  etape('Connexion du super administrateur');
  const sa = await api('POST', '/auth/connexion', {
    telephone: SUPER_ADMIN_TELEPHONE,
    motDePasse: SUPER_ADMIN_MOT_DE_PASSE,
  });
  if (sa.doitChangerMotDePasse) {
    arreter(
      'Le mot de passe du super administrateur est encore provisoire.\n' +
        '  Changez-le dans l’application (http://localhost:4200) ou avec Swagger, puis relancez :\n' +
        '  SUPER_ADMIN_MOT_DE_PASSE=votre-mot-de-passe node scripts/demo/creer-etablissement-demo.mjs',
    );
  }
  ok();

  const code = `DEMO-${suffixe}`;
  const telAdmin = telephone('60');
  etape(`Création de l'établissement ${code}`);
  const creation = await api('POST', '/plateforme/etablissements', {
    code,
    nom: `Lycée technique de démonstration ${suffixe}`,
    telephoneAdministrateur: telAdmin,
    nomAdministrateur: 'KABORE',
    prenomsAdministrateur: 'Mathieu',
  }, sa.jetonAcces);
  ok();

  // 2. Administrateur de l'établissement : paramétrage
  etape('Première connexion de l’administrateur');
  const admin = await sessionDemo(telAdmin, creation.motDePasseTemporaire);
  ok();

  etape('Profils pédagogiques');
  const profils = await api('POST', '/profils/initialisation', undefined, admin);
  const technique = profils.find((p) => p.code === 'TECHNIQUE');
  if (!technique) arreter('Profil TECHNIQUE introuvable');
  ok();

  // Année scolaire qui contient la date du jour (sinon l'appel serait refusé : HORS_ANNEE)
  const aujourdhui = new Date();
  const anDebut = aujourdhui.getMonth() >= 8 ? aujourdhui.getFullYear() : aujourdhui.getFullYear() - 1;
  const debut = new Date(anDebut, 8, 1);
  const fin = aujourdhui.getMonth() === 7 && aujourdhui.getFullYear() === anDebut + 1
    ? new Date(anDebut + 1, 7, 31)
    : new Date(anDebut + 1, 6, 31);
  const libelle = `${anDebut}-${anDebut + 1}`;
  etape(`Année ${libelle}`);
  const annee = await api('POST', '/annees', { libelle, debut: iso(debut), fin: iso(fin) }, admin);
  ok();

  etape('Filière F3 et matières');
  const filiere = await api('POST', '/filieres', {
    code: 'F3',
    libelle: 'Électrotechnique',
    cycle: 'Second cycle',
    diplomeVise: 'BAC F3',
    profilId: technique.id,
  }, admin);
  const matieres = {};
  for (const m of MATIERES) {
    matieres[m.code] = await api('POST', '/matieres', { code: m.code, libelle: m.libelle, type: m.type }, admin);
  }
  ok(`${MATIERES.length} matières`);

  etape('Classes et programmes');
  const classes = [];
  for (const c of CLASSES) {
    const classe = await api('POST', `/annees/${annee.id}/classes`, {
      filiereId: filiere.id,
      code: c.code,
      niveau: c.niveau,
      effectifMax: 60,
    }, admin);
    for (const m of MATIERES) {
      await api('PUT', `/classes/${classe.id}/matieres/${matieres[m.code].id}`, {
        coefficient: m.coefficient,
        groupe: m.groupe,
        volumeHebdo: m.volumeHebdo,
      }, admin);
    }
    classes.push({ ...c, id: classe.id });
  }
  ok(classes.map((c) => c.code).join(', '));

  etape('Trimestres');
  await api('POST', `/annees/${annee.id}/periodes/generation`, { profilId: technique.id }, admin);
  ok();

  etape('Ouverture de l’année');
  await api('POST', `/annees/${annee.id}/ouverture`, undefined, admin);
  ok();

  // 3. Enseignant de démonstration
  const telEnseignant = telephone('61');
  etape('Engagement de l’enseignant');
  const engagement = await api('POST', '/enseignants', {
    telephone: telEnseignant,
    nom: 'SANOU',
    prenoms: 'Paul',
    sexe: 'M',
    specialite: 'Électrotechnique',
    type: 'TITULAIRE',
    debut: iso(debut),
  }, admin);
  ok();

  etape('Affectations');
  for (const c of classes) {
    for (const codeMatiere of MATIERES_ENSEIGNANT) {
      await api('PUT', `/classes/${c.id}/matieres/${matieres[codeMatiere].id}/enseignant`, {
        engagementId: engagement.enseignant.engagementId,
      }, admin);
    }
  }
  ok(`${classes.length * MATIERES_ENSEIGNANT.length} affectations`);

  // 4. Élèves, un parent chacun, inscrits dans les classes
  let total = 0;
  /** Premier élève inscrit : son parent reçoit un espace parent de démonstration. */
  let premier = null;
  for (const c of classes) {
    etape(`Élèves de ${c.code}`);
    const nomsPris = new Set();
    for (let i = 0; i < c.eleves; i++) {
      const fille = tirage() < 0.45;
      let nom;
      let prenoms;
      do {
        nom = choisir(NOMS);
        prenoms = choisir(fille ? PRENOMS_F : PRENOMS_M);
      } while (nomsPris.has(nom + prenoms));
      nomsPris.add(nom + prenoms);
      const naissance = new Date(c.naissance - (tirage() < 0.2 ? 1 : 0), Math.floor(tirage() * 12), 1 + Math.floor(tirage() * 28));
      const telParent = telephone('62');
      const dossier = await api('POST', '/eleves', {
        nom,
        prenoms,
        sexe: fille ? 'F' : 'M',
        dateNaissance: iso(naissance),
        lieuNaissance: choisir(['Ouagadougou', 'Koudougou', 'Bobo-Dioulasso', 'Réo', 'Kaya', 'Ouahigouya']),
        responsables: [{
          nom,
          prenoms: choisir(tirage() < 0.5 ? PRENOMS_M : PRENOMS_F),
          telephone: telParent,
          lien: choisir(['PERE', 'MERE', 'TUTEUR']),
          responsableLegal: true,
          contactPrioritaire: true,
        }],
      }, admin);
      const inscription = await api('POST', '/inscriptions', {
        eleveId: dossier.eleve.id,
        classeId: c.id,
        redoublant: tirage() < 0.1,
      }, admin);
      premier ??= { dossier, inscription, classe: c, telParent };
      total++;
    }
    ok(`${c.eleves} inscrits`);
  }

  // 5. Mot de passe de l'enseignant et vérification de ce qu'il verra dans l'application
  etape('Première connexion de l’enseignant');
  const enseignant = await sessionDemo(telEnseignant, engagement.motDePasseTemporaire);
  const fiche = await api('GET', '/espace-enseignant/affectations', undefined, enseignant);
  ok(`${fiche.affectations.length} classes-matières visibles`);

  // 6. Scolarité : frais de l'année en trois tranches (la première déjà échue)
  etape('Frais de scolarité');
  await api('POST', `/annees/${annee.id}/frais`, {
    libelle: 'Scolarité',
    montant: 75000,
    obligatoire: true,
    couvertParBourse: true,
    portee: 'TOUTES',
    tranches: [
      { dateLimite: `${anDebut}-09-15`, montant: 25000 },
      { dateLimite: `${anDebut + 1}-01-15`, montant: 25000 },
      { dateLimite: `${anDebut + 1}-04-15`, montant: 25000 },
    ],
  }, admin);
  ok('75 000 FCFA en 3 tranches');

  // 7. Mobile Money en simulation (profil dev du serveur uniquement)
  etape('Mobile Money (simulateur)');
  const mm = await api('PUT', '/parametres/mobile-money', {
    agregateur: 'SIMULATEUR',
    identifiantMarchand: code,
    cleApi: 'demo-cle-api',
    secretWebhook: 'demo-secret-webhook',
    actif: true,
  }, admin, { facultatif: true });
  if (mm?.erreur) {
    console.log(`ignoré (${mm.erreur}) : lancez l'API avec le profil dev pour payer en simulation`);
  } else {
    ok();
  }

  // 8. Une absence aujourd'hui pour l'enfant du parent de démonstration
  let telParent = null;
  if (premier) {
    const affectation = fiche.affectations.find((a) => a.classeId === premier.classe.id);
    if (affectation) {
      etape('Un appel avec une absence');
      await api('POST', '/appels/lot', {
        appels: [{
          idClient: crypto.randomUUID(),
          classeId: affectation.classeId,
          matiereId: affectation.matiereId,
          date: iso(new Date()),
          heureDebut: '07:00',
          heureFin: '09:00',
          saisiLe: new Date().toISOString(),
          marques: [{ inscriptionId: premier.inscription.id, type: 'ABSENCE' }],
        }],
      }, enseignant);
      ok(`${premier.dossier.eleve.prenoms} ${premier.dossier.eleve.nom}, ${affectation.matiereLibelle}`);
    }

    // 9. Espace parent du premier élève
    etape('Espace parent');
    const responsable = premier.dossier.responsables[0];
    const espace = await api('POST', `/responsables/${responsable.responsableId}/espace-parent`, undefined, admin);
    if (espace.motDePasseTemporaire) {
      await sessionDemo(premier.telParent, espace.motDePasseTemporaire);
    }
    telParent = premier.telParent;
    ok(`parent de ${premier.dossier.eleve.prenoms} ${premier.dossier.eleve.nom}`);
  }

  console.log(`
✔ Établissement de démonstration prêt : ${creation.etablissement.nom} (${code})
  ${classes.length} classes, ${MATIERES.length} matières, ${total} élèves inscrits, année ${libelle} ouverte.

  Application : http://localhost:4200

  Enseignant (fait l'appel)   téléphone ${telEnseignant}   mot de passe ${DEMO_MOT_DE_PASSE}
  Administrateur              téléphone ${telAdmin}   mot de passe ${DEMO_MOT_DE_PASSE}${telParent ? `
  Parent (espace parent)      téléphone ${telParent}   mot de passe ${DEMO_MOT_DE_PASSE}` : ''}

  Pour lui donner quelques semaines d'activité (appels, absences, notes, avertissements, justificatifs,
  convocation) :
    node scripts/demo/activite-enseignant.mjs ${telEnseignant} ${telAdmin}

  Comptes de développement uniquement.
`);
}

principal().catch((e) => arreter(e.stack ?? String(e)));
