# Sauvegardes et restauration (v0.50)

Les données des établissements (élèves, notes, absences, paiements, cahiers de textes) ne doivent jamais être
perdues. Le dispositif suit la règle 3-2-1 :
- **trois copies** : la base, la sauvegarde locale et la copie hors site ;
- **deux supports** : le disque de la base et un autre disque ;
- **une copie hors site**, chez un autre hébergeur ou dans un autre pays.

Les sauvegardes sont **chiffrées** avant d'être écrites ; une copie volée est illisible.

Scripts : `scripts/sauvegarde/`. Ils s'installent sur le **serveur de base de données** (VPS 2), par exemple dans
`/opt/plateforme/sauvegarde/`.

| Script | Rôle |
|---|---|
| `sauvegarder.sh` | Sauvegarde du jour : `pg_dump` (format personnalisé), chiffrée avec `age`, empreinte SHA-256, rotation, copie hors site |
| `sauvegarder.sh --annuelle 2026-2027` | Sauvegarde de **clôture d'année**, rangée dans `annuelles/` et jamais effacée par la rotation |
| `restaurer.sh` | Restaure une sauvegarde dans une **nouvelle** base (ne supprime ni ne remplace jamais une base existante) |
| `verifier-restauration.sh` | Contrôle mensuel : restaure la dernière sauvegarde dans une base temporaire, compare les comptages à la base en service, écrit un compte rendu, supprime la base temporaire |

## Ce qui est sauvegardé

- **La base `plateforme` entière**, tous établissements confondus : schéma, données, cloisonnement (politiques de
  Row-Level Security), fonctions, historique Flyway. Le flux passe directement de `pg_dump` à `age` : aucune
  donnée en clair n'est écrite sur le disque.
- **Les rôles PostgreSQL**, sans mots de passe (`pg_dumpall --globals-only --no-role-passwords`).

Ne sont pas dans la base, et se reconstruisent :
- les images Docker, reconstruites par la CI ;
- les archives d'export complet, régénérables, conservées 7 jours ;
- les secrets, conservés dans le gestionnaire de secrets.

## Mise en place (une fois)

1. **Outils** sur le serveur de base de données :
   ```bash
   sudo apt install age rsync
   ```
2. **Rôle de sauvegarde** : lecture seule, avec `BYPASSRLS` pour tout lire.
   ```bash
   sudo -u postgres psql -v mot_de_passe="'<mot de passe fort>'" -f infra/postgres/sauvegarde/role-sauvegarde.sql
   ```
   Placez ensuite le mot de passe dans le `~/.pgpass` (droits 600) de l'utilisateur système `sauvegarde` :
   `localhost:5432:plateforme:plateforme_sauvegarde:<mot de passe>`.
3. **Clés de chiffrement** : deux paires `age`.
   - **Clé d'exploitation.** Générée sur le poste du responsable de l'exploitation, jamais sur le serveur :
     `age-keygen -o exploitation.txt`. La clé privée se garde hors ligne, en deux exemplaires : coffre, et clé USB
     chiffrée remise à la direction. Sans elle, aucune restauration n'est possible depuis les copies hors site.
   - **Clé de contrôle.** Générée sur le serveur, `age-keygen -o /etc/plateforme/sauvegarde-controle.age`
     (droits 600). Elle sert au contrôle mensuel de restauration. Elle ne protège rien de plus que le serveur
     lui-même : qui accède au serveur accède déjà à la base.
   - **Les deux clés publiques** (`age-keygen -y …`) vont dans `/etc/plateforme/sauvegarde-destinataires.txt`,
     une par ligne.
4. **Configuration** : copiez `sauvegarde.conf.exemple` en `/etc/plateforme/sauvegarde.conf` (droits 600) et
   adaptez-la, notamment `SAUVEGARDE_COPIE_DISTANTE`. Pour la copie distante, créez une clé SSH dédiée, sans
   mot de passe, autorisée seulement à `rsync` sur le serveur de stockage.
5. **Planification** : copiez `cron.exemple` en `/etc/cron.d/plateforme-sauvegarde`. Il prévoit :
   - chaque nuit à 1 h 40, la sauvegarde ;
   - le 2 de chaque mois, le contrôle de restauration.

   Renseignez `MAILTO` : un échec doit être vu le jour même.

Rotation par défaut :
- 14 sauvegardes quotidiennes ;
- 8 hebdomadaires (celles du dimanche) ;
- 12 mensuelles (celles du 1er) ;
- les annuelles indéfiniment.

Les copies hebdomadaires et mensuelles sont des liens physiques : elles n'occupent pas d'espace en plus.

## Clôture d'une année scolaire

Avant d'archiver une année (écran « Année scolaire »), lancez :

```bash
sudo -u sauvegarde /opt/plateforme/sauvegarde/sauvegarder.sh --annuelle 2026-2027
```

Cette sauvegarde fige l'année telle qu'elle a été close. Elle n'est jamais effacée. C'est la source de
l'entrepôt de données par année scolaire prévu en v0.52, pour l'exploitation et l'interrogation des années
passées.

## Restaurer

### Vérifier une sauvegarde ou retrouver une donnée

La restauration se fait toujours dans une base neuve. Rien n'est écrasé.

```bash
sudo -u postgres SAUVEGARDE_CONF=/etc/plateforme/sauvegarde.conf \
  /opt/plateforme/sauvegarde/restaurer.sh /var/backups/plateforme/quotidiennes/plateforme-2026-11-03_0140.dump.age \
  --identite /chemin/vers/exploitation.txt
```

Le script :
- vérifie l'empreinte (fichier altéré : arrêt) ;
- crée la base `plateforme_restauree_AAAAMMJJ_HHMM` ;
- y restaure la sauvegarde ;
- affiche la version du schéma et quelques comptages.

On peut alors y lire la donnée perdue, puis supprimer la base : `dropdb plateforme_restauree_…`.

### Remettre la production sur une sauvegarde (sinistre)

À faire avec la direction, après avoir noté l'heure de la sauvegarde choisie : ce qui a été saisi après est perdu.

1. Restaurer dans une base neuve, comme ci-dessus.
2. Arrêter l'API sur le serveur d'application : `docker compose stop api1 api2`.
3. Échanger les bases, en superutilisateur. L'ancienne base est gardée, pas supprimée.
   ```sql
   ALTER DATABASE plateforme RENAME TO plateforme_avant_sinistre_20261103;
   ALTER DATABASE plateforme_restauree_20261103_0915 RENAME TO plateforme;
   ```
4. Si les rôles ont été perdus, les recréer avec `infra/postgres`, ou depuis `*.roles.sql.age`, puis redonner
   leurs mots de passe depuis le gestionnaire de secrets.
5. Redémarrer l'API : `docker compose up -d api1 api2`. Flyway constate que le schéma est à jour.
6. Prévenir les établissements de la période à ressaisir.

## Contrôle mensuel

Chaque mois, `verifier-restauration.sh` écrit un compte rendu dans `/var/backups/plateforme/verifications/`. Il
contient :
- la sauvegarde contrôlée ;
- la version du schéma ;
- les comptages des principales tables, dans la sauvegarde et dans la base en service ;
- la durée de restauration.

Le contrôle échoue (code de retour 1, courriel du cron) dans trois cas :
- le fichier est altéré ;
- la restauration échoue ;
- une table est vide dans la sauvegarde alors qu'elle ne l'est pas en service.

La durée de restauration mesurée donne le temps réel de remise en service en cas de sinistre.

## Essai réalisé (v0.50)

Le dispositif a été essayé sur la base de test PostgreSQL 16 :
- trois sauvegardes successives : la rotation a gardé les deux plus récentes ;
- une sauvegarde annuelle ;
- un contrôle de restauration : OK, comptages identiques, 3 s ;
- une restauration manuelle dans une base neuve ;
- le refus de restaurer dans une base existante ;
- un fichier altéré : détecté, contrôle en échec, base temporaire supprimée.
