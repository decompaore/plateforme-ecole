#!/usr/bin/env bash
# =====================================================================
# Contrôle mensuel d'une sauvegarde (v0.50) : une sauvegarde n'est sûre
# que si elle se restaure. Restaure la sauvegarde la plus récente (ou celle
# donnée) dans une base temporaire, compare les principaux comptages à la
# base en service, écrit un compte rendu, puis supprime la base temporaire.
#
#   verifier-restauration.sh [sauvegarde.dump.age]
#
# Le déchiffrement utilise la clé de contrôle du serveur
# ($SAUVEGARDE_IDENTITE_VERIFICATION) : voir docs/SAUVEGARDES.md.
# =====================================================================
set -euo pipefail
umask 077

CONF="${SAUVEGARDE_CONF:-/etc/plateforme/sauvegarde.conf}"
# shellcheck source=/dev/null
[[ -f "$CONF" ]] && source "$CONF"
: "${SAUVEGARDE_REPERTOIRE:?}"
: "${SAUVEGARDE_IDENTITE_VERIFICATION:?Clé de contrôle (age) requise : SAUVEGARDE_IDENTITE_VERIFICATION}"
BASE="${SAUVEGARDE_BASE_VERIFICATION:-plateforme_verification}"
[[ "$BASE" == *verification* ]] || { echo "La base de contrôle doit contenir « verification » dans son nom" >&2; exit 2; }
ROLE="${SAUVEGARDE_ROLE_RESTAURATION:-${PGUSER:-postgres}}"
export PGHOST PGPORT

FICHIER="${1:-}"
if [[ -z "$FICHIER" ]]; then
  FICHIER="$(find "$SAUVEGARDE_REPERTOIRE/quotidiennes" -maxdepth 1 -name '*.dump.age' -printf '%T@ %p\n' 2>/dev/null \
    | sort -rn | head -1 | cut -d' ' -f2-)" || true
fi
[[ -f "$FICHIER" ]] || { echo "Aucune sauvegarde à contrôler" >&2; exit 1; }
mkdir -p "$SAUVEGARDE_REPERTOIRE/verifications"
rapport="$SAUVEGARDE_REPERTOIRE/verifications/verification-$(date +%Y-%m-%d_%H%M).txt"

nettoyer() { dropdb -U "$ROLE" --if-exists "$BASE" >/dev/null 2>&1 || true; }
nettoyer
trap nettoyer EXIT

debut=$(date +%s)
{
  echo "Contrôle de restauration du $(date -Is)"
  echo "Sauvegarde : $(basename "$FICHIER")"
} > "$rapport"

if ! SAUVEGARDE_ROLE_RESTAURATION="$ROLE" "$(dirname "$0")/restaurer.sh" "$FICHIER" \
     --identite "$SAUVEGARDE_IDENTITE_VERIFICATION" --base "$BASE" >> "$rapport" 2>&1; then
  echo "RÉSULTAT : ÉCHEC de la restauration" | tee -a "$rapport" >&2
  exit 1
fi

tables="tenant utilisateur eleve inscription absence evaluation seance_cahier paiement"
echo "Comptages (sauvegarde / base en service) :" >> "$rapport"
anomalie=0
for t in $tables; do
  a="$(psql -U "$ROLE" -d "$BASE" -Atc "select count(*) from $t" 2>/dev/null || echo "?")"
  b="$(psql -U "$ROLE" -d "${PGDATABASE:-plateforme}" -Atc "select count(*) from $t" 2>/dev/null || echo "?")"
  echo "  $t : $a / $b" >> "$rapport"
  if [[ "$a" == "?" ]] || { [[ "$b" =~ ^[0-9]+$ && "$b" -gt 0 && "$a" == "0" ]]; }; then anomalie=1; fi
done
duree=$(( $(date +%s) - debut ))
echo "Durée de restauration : ${duree} s" >> "$rapport"

if (( anomalie )); then
  echo "RÉSULTAT : ANOMALIE (table vide ou absente dans la sauvegarde)" | tee -a "$rapport" >&2
  exit 1
fi
echo "RÉSULTAT : OK" | tee -a "$rapport"
