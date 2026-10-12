# Messagerie de l'établissement (v0.46)

Module `messagerie` : le chef d'établissement écrit au personnel (enseignants, administration), aux parents, à une
personne ou à un groupe. Chacun lit ses messages dans l'application (lu / non lu) ; l'envoi peut aussi partir par
**SMS**, seul moyen de joindre un parent qui n'a pas d'espace parent. Migration `V33__messagerie_examens.sql`
(tables `message`, `message_destinataire`, cloisonnées par établissement).

## Boîte de réception (tout compte de l'établissement : personnel, enseignants, parents)

| Appel | Effet |
|---|---|
| `GET /api/v1/messages?page=0&taille=20` | `{messages:[{id, objet, extrait, auteur, envoyeLe, lu}], total, nonLus}`, les plus récents d'abord |
| `GET /api/v1/messages/non-lus` | `{nombre}` (pastille ✉ du menu, relue toutes les 5 minutes) |
| `GET /api/v1/messages/{id}` | Le message complet ; il passe à « lu » (`luLe`). `404` pour un message qui ne vous est pas adressé |

## Envoi (ADMIN_ECOLE)

| Appel | Effet |
|---|---|
| `GET /api/v1/messages/annuaire` | Personnel actif et parents des élèves de l'année en cours : `{utilisateurId, responsableId, nom, qualite, categorie}` ; `categorie` = `ADMINISTRATION`, `ENSEIGNANT`, `PARENT`, `PARENT_SANS_COMPTE` |
| `POST /api/v1/messages/apercu` (même corps que l'envoi) | `{destinataires, comptes, smsSeulement, injoignables, sms}` sans rien envoyer |
| `POST /api/v1/messages` | `{id, destinataires, comptes, sms}` |
| `GET /api/v1/messages/envoyes` | Messages envoyés (200 derniers) : cibles, destinataires, comptes, lus |
| `GET /api/v1/messages/{id}/destinataires` | Destinataires, compte ou non, SMS, `luLe` |

```json
{"objet":"Réunion des parents","corps":"Samedi à 9 h, salle des fêtes.","sms":true,
 "groupes":["PARENTS","ENSEIGNANTS"],"roles":["SURVEILLANT"],"classes":["…"],
 "utilisateurs":["…"],"responsables":["…"]}
```

- **Groupes** : `PERSONNEL` (enseignants + administration), `ENSEIGNANTS`, `ADMINISTRATION` (chef d'établissement,
  censeur, chef des travaux, secrétariat, intendant, surveillant), `PARENTS` (tous les parents de l'année en cours).
- `roles` : seulement certains rôles de l'administration ; `classes` : les parents des élèves de ces classes ;
  `utilisateurs` : des comptes choisis (personnel ou parents) ; `responsables` : des parents sans compte (SMS).
- **Parents** visés : responsables légaux et contacts prioritaires des élèves inscrits (actifs) dans l'année en cours ;
  un parent de plusieurs enfants n'est joint qu'une fois. L'auteur ne s'écrit pas à lui-même.
- **SMS** (`sms:true`) : un SMS par destinataire qui a un téléphone, par la file d'envoi (`notification`), texte
  « Établissement : objet. message » coupé à 480 caractères (3 à 4 SMS au plus). Sans SMS, un parent sans compte est
  compté dans `injoignables` et n'est pas joint.
- `sms` est facultatif (absent : pas de SMS). Objet : 150 caractères ; message : 2 000 ; 5 000 destinataires au plus. Aucun destinataire joignable :
  `409 AUCUN_DESTINATAIRE`.
- Journal d'audit : `MESSAGE_ENVOYE` (comptes et SMS).
- Les messages ne se suppriment pas (trace de ce qui a été dit aux familles) ; ils sont dans l'export de réversibilité.
