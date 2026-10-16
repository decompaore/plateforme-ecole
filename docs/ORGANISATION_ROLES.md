# Organisation des rôles dans l'établissement (v0.47, v0.47.1)

Revue faite avec des acteurs de l'administration des lycées (Burkina Faso) et les textes sur les attributions du
censeur. Les droits de la plateforme suivent désormais cette organisation ; le serveur les vérifie à chaque appel.

## Qui fait quoi

| Domaine | Saisit | Modifie après enregistrement | Consulte |
|---|---|---|---|
| Inscription (dossier, inscription, photo, pièces reçues, import) | Intendant, chef d'établissement | Chef d'établissement seul (identité, parents, bourse, sortie, mode d'admission, pièce retirée) | Censeur, chef des travaux, secrétariat, CPE |
| Répartition dans les classes, réinscriptions, plan de passage | Censeur (classes d'un même niveau), chef d'établissement | — | Secrétariat |
| Vie scolaire (incidents, sanctions, convocations, justificatifs, appel de toute classe) | **CPE** seul (rôle `SURVEILLANT`) | CPE | Chef d'établissement, censeur, chef des travaux, secrétariat |
| Absences et retards en classe | Enseignant, par l'appel | CPE | Direction |
| Cahier de textes | Enseignant | Enseignant, tant que le mois n'est pas visé | Direction du domaine, qui **vise chaque mois** |
| Progressions | Enseignant | Enseignant | Censeur (matières générales), chef des travaux (techniques et pratiques), qui visent |
| Cartes scolaires, accès des parents | Secrétariat, intendant, chef d'établissement | — | — |
| Courrier arrivée et départ, suivi, classement (v0.47.1) | Secrétariat | Secrétariat | Service destinataire (le marque traité) |
| Documents administratifs (notes de service, décisions, communiqués, attestations) et tableau d'affichage | Secrétariat (rédige) | Chef d'établissement (signe, annule) | Personnel ; parents pour ce qui est « pour tous » |
| Actes du personnel (mutation, affectation, autorisation d'absence…) | Secrétariat (recueille, transmet) | Chef d'établissement (avis) | Le demandeur (« Mes demandes ») |
| Accueil du public, permanence téléphonique | Secrétariat (message transmis par la messagerie) | — | — |
| Comptes rendus des réunions statutaires | Secrétariat | Chef d'établissement (valide) | Censeur, chef des travaux |
| Archives des dossiers, certificats de scolarité, dossiers d'examens | Secrétariat | — | Censeur (candidats aux examens) |
| Planning des ateliers (séances de TP) (v0.47.2) | Enseignant (demande) | Chef d'atelier (valide, refuse, prépare, fait le bilan) ; chef des travaux (supervise) | Enseignants de l'atelier |
| Matière d'œuvre des TP | Enseignant (fiche de préparation, consommation réelle) | Chef d'atelier (accorde, sert, remet le surplus en stock) | Direction et intendance (achats à prévoir) |
| Consignes de sécurité, journal HSE | Chef d'atelier | Chef d'atelier | Enseignants (prise de connaissance obligatoire) ; chef des travaux alerté en cas de danger |
| Commandes de production | Chef d'atelier, direction | Chef d'atelier, direction | Enseignants de l'atelier, intendance |
| État annuel des besoins (v0.48) : infrastructures, personnel, équipements et outils, matières | Censeur (domaine général), chef des travaux (domaine technique) | Chef d'établissement (complète, transmet à la DP) | Intendant ; chaque niveau territorial (DP, DR, administrateur pays) une fois l'état arrivé chez lui, jamais le super administrateur de la plateforme (v0.48.1) |
| Dotations reçues (v0.48) | Direction (DR ou DP) | Chef d'établissement ou intendant (accusé de réception) | Censeur, chef des travaux |
| Commission de recrutement (v0.49) : places, candidatures, classement, procès-verbal | Secrétariat, censeur, CPE (candidatures) ; censeur (places, recevabilité) | Chef d'établissement, président (membres, délibération, clôture) ; intendant (inscription des retenus, désistements) | Membres désignés (enseignants, chef des travaux) |
| Interpellation d'un enseignant en retard sur le programme (v0.48.1) : rappel, demande d'explication | Censeur (matières générales), chef des travaux (techniques et pratiques), chef d'établissement | La direction du domaine (suite donnée, clôture) | L'enseignant (lit, répond) ; le chef d'établissement (copie des demandes d'explication) |
| Évaluations, notes, bulletins | Enseignants, censeur | Censeur, chef d'établissement | **Plus le secrétariat** (v0.47.1) |
| Suppléance du chef d'établissement | Le chef d'établissement désigne un **intérim** (censeur ou chef des travaux) | — | Censeur, chef des travaux |

Le Conseiller principal d'éducation (CPE) remplace le surveillant général ; le nom technique du rôle (`SURVEILLANT`)
est conservé pour ne pas migrer les comptes existants, l'application affiche « Conseiller principal d'éducation ».

## Dossier d'inscription

Pièces usuelles : demande de place adressée au chef d'établissement, extrait d'acte de naissance légalisé, bulletins
de l'année écoulée, certificat de scolarité, copie légalisée du CEP ou du BEPC, certificat de non-redevance, photos
d'identité. Pour un élève **affecté par l'État** (entrée en 6e ou en 2nde après le CEP ou le BEPC), la décision
d'orientation remplace l'étude du dossier. La liste se règle par établissement et par mode d'admission
(`AFFECTATION_ETAT`, `COMPLEMENT_EFFECTIF`, `TRANSFERT`, `REINSCRIPTION`, `PRIVE`, `AUTRE`). Détails :
[API_ELEVES.md](API_ELEVES.md).

## Prochaines étapes (v0.48)

- **Besoins et dotations** : besoins en infrastructures, personnel, équipement et outils, matières, recueillis par le
  censeur et le chef des travaux, transmis par le chef d'établissement à la direction provinciale, puis régionale,
  puis centrale ; dotations décidées au niveau régional selon les besoins et les moyens, réparties par la province
  aux établissements, avec accusé de réception.
- **Commission de recrutement** (complément d'effectif) : livrée en v0.49, voir [API_RECRUTEMENT.md](API_RECRUTEMENT.md).
