#!/usr/bin/env bash
# =====================================================================
# Restauration d'une sauvegarde dans une NOUVELLE base (v0.50).
#
#   restaurer.sh <sauvegarde.dump.age> --identite <identite-age.txt> [--base <nom>]
#
# Ne supprime ni ne remplace jamais une base existante : la sauvegarde est
# restaurée dans une base neuve (par défaut plateforme_restauree_AAAAMMJJ_HHMM).
# Pour remettre la production sur cette base, voir docs/SAUVEGARDES.md
# (arrêt de l'API, renommage des bases, redémarrage).
# =====================================================================
set -euo pipefail
umask 077

CONF="${SAUVEGARDE_CONF:-/etc/plateforme/sauvegarde.conf}"
# shellcheck source=/dev/null
[[ -f "$CONF" ]] && source "$CONF"

FICHIER="${1:?Usage : restaurer.sh <sauvegarde.dump.age> --identite <identite.txt> [--base <nom>]}"
shift
IDENTITE=""
BASE="plateforme_restauree_$(date +%Y%m%d_%H%M)"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --identite) IDENTITE="${2:?}"; shift 2 ;;
    --base) BASE="${2:?}"; shift 2 ;;
    *) echo "Option inconnue : $1" >&2; exit 2 ;;
  esac
done
[[ -f "$FICHIER" ]] || { echo "Sauvegarde introuvable : $FICHIER" >&2; exit 2; }
[[ -f "$IDENTITE" ]] || { echo "Clé privée age requise (--identite)" >&2; exit 2; }
[[ "$BASE" =~ ^[a-z_][a-z0-9_]{0,62}$ ]] || { echo "Nom de base invalide : $BASE" >&2; exit 2; }

ROLE="${SAUVEGARDE_ROLE_RESTAURATION:-${PGUSER:-postgres}}"
PROPRIETAIRE="${SAUVEGARDE_PROPRIETAIRE:-plateforme_owner}"
export PGHOST PGPORT

# Empreinte : la copie n'a pas été altérée
empreintes="${FICHIER%.dump.age}.sha256"
if [[ -f "$empreintes" ]]; then
  attendu="$(grep " $(basename "$FICHIER")\$" "$empreintes" | cut -d' ' -f1)"
  reel="$(sha256sum "$FICHIER" | cut -d' ' -f1)"
  [[ "$attendu" == "$reel" ]] || { echo "ÉCHEC : empreinte différente, fichier altéré" >&2; exit 1; }
  echo "Empreinte vérifiée"
fi

existe="$(psql -U "$ROLE" -d postgres -Atc "select 1 from pg_database where datname = '$BASE'")"
if [[ "$existe" == "1" ]]; then
  echo "La base $BASE existe déjà : choisissez un autre nom (--base). Rien n'a été modifié." >&2
  exit 1
fi

echo "Restauration de $(basename "$FICHIER") dans la nouvelle base $BASE"
createdb -U "$ROLE" -O "$PROPRIETAIRE" "$BASE"
if ! age --decrypt --identity "$IDENTITE" "$FICHIER" \
    | pg_restore -U "$ROLE" --dbname="$BASE" --exit-on-error --no-password; then
  echo "ÉCHEC de la restauration. La base incomplète $BASE est conservée pour analyse ;" >&2
  echo "supprimez-la ensuite : dropdb -U $ROLE $BASE" >&2
  exit 1
fi

psql -U "$ROLE" -d "$BASE" -At <<'SQL'
select 'Version du schéma : ' || version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1;
select 'Établissements : ' || count(*) from tenant;
select 'Élèves : ' || count(*) from eleve;
select 'Inscriptions : ' || count(*) from inscription;
SQL
echo "Restauration terminée : base $BASE"
