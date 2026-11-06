#!/usr/bin/env bash
# =====================================================================
# Sauvegarde chiffrée de la base de la plateforme (v0.50).
#
#   sauvegarder.sh                      sauvegarde du jour (rotation)
#   sauvegarder.sh --annuelle 2026-2027 sauvegarde de clôture d'année, jamais effacée
#
# Produit, dans $SAUVEGARDE_REPERTOIRE :
#   quotidiennes/plateforme-AAAA-MM-JJ_HHMM.dump.age   (pg_dump, format personnalisé, chiffré age)
#   quotidiennes/plateforme-AAAA-MM-JJ_HHMM.roles.sql.age (rôles, sans mots de passe)
#   …sha256                                            (empreintes, pour vérifier les copies)
# et des copies dans hebdomadaires/ (dimanche), mensuelles/ (le 1er) et annuelles/.
# Code de retour non nul en cas d'échec : à surveiller (cron + courriel, ou supervision).
# =====================================================================
set -euo pipefail
umask 077

CONF="${SAUVEGARDE_CONF:-/etc/plateforme/sauvegarde.conf}"
# shellcheck source=/dev/null
[[ -f "$CONF" ]] && source "$CONF"

: "${PGDATABASE:?PGDATABASE manquant}"
: "${SAUVEGARDE_REPERTOIRE:?SAUVEGARDE_REPERTOIRE manquant}"
: "${SAUVEGARDE_DESTINATAIRES:?SAUVEGARDE_DESTINATAIRES manquant (clés publiques age)}"
export PGHOST PGPORT PGDATABASE PGUSER

ANNUELLE=""
if [[ "${1:-}" == "--annuelle" ]]; then
  ANNUELLE="${2:?Libellé de l année requis, ex. 2026-2027}"
  [[ "$ANNUELLE" =~ ^[0-9]{4}-[0-9]{4}$ ]] || { echo "Libellé d'année invalide : $ANNUELLE" >&2; exit 2; }
fi

command -v age >/dev/null || { echo "age n'est pas installé (apt install age)" >&2; exit 2; }
[[ -s "$SAUVEGARDE_DESTINATAIRES" ]] || { echo "Aucune clé publique dans $SAUVEGARDE_DESTINATAIRES" >&2; exit 2; }

horodatage="$(date +%Y-%m-%d_%H%M)"
nom="plateforme-${horodatage}"
[[ -n "$ANNUELLE" ]] && nom="plateforme-annee-${ANNUELLE}-${horodatage}"
quotidiennes="$SAUVEGARDE_REPERTOIRE/quotidiennes"
mkdir -p "$quotidiennes" "$SAUVEGARDE_REPERTOIRE/hebdomadaires" "$SAUVEGARDE_REPERTOIRE/mensuelles" \
  "$SAUVEGARDE_REPERTOIRE/annuelles"
travail="$(mktemp -d "$SAUVEGARDE_REPERTOIRE/.en-cours.XXXXXX")"
trap 'rm -rf "$travail"' EXIT

echo "$(date -Is) Sauvegarde de $PGDATABASE ($nom)"
# Le flux en clair ne touche jamais le disque : pg_dump | age
pg_dump --format=custom --compress=6 --no-password "$PGDATABASE" \
  | age --encrypt --recipients-file "$SAUVEGARDE_DESTINATAIRES" > "$travail/$nom.dump.age"
pg_dumpall --globals-only --no-role-passwords --no-password 2>/dev/null \
  | age --encrypt --recipients-file "$SAUVEGARDE_DESTINATAIRES" > "$travail/$nom.roles.sql.age" \
  || echo "Avertissement : rôles non sauvegardés (droits insuffisants) ; ils se recréent avec infra/postgres" >&2

taille="$(stat -c %s "$travail/$nom.dump.age")"
if (( taille < 1024 )); then
  echo "ÉCHEC : sauvegarde anormalement petite ($taille octets)" >&2
  exit 1
fi
( cd "$travail" && sha256sum -- *.age > "$nom.sha256" )

if [[ -n "$ANNUELLE" ]]; then
  destination="$SAUVEGARDE_REPERTOIRE/annuelles"
else
  destination="$quotidiennes"
fi
mv "$travail"/* "$destination"/

if [[ -z "$ANNUELLE" ]]; then
  # Copies hebdomadaire et mensuelle par liens physiques (pas d'espace en plus)
  if [[ "$(date +%u)" == "7" ]]; then ln -f "$destination/$nom".* "$SAUVEGARDE_REPERTOIRE/hebdomadaires/"; fi
  if [[ "$(date +%d)" == "01" ]]; then ln -f "$destination/$nom".* "$SAUVEGARDE_REPERTOIRE/mensuelles/"; fi

  # Rotation : on garde les N plus récentes de chaque série (jamais les annuelles)
  garder() {
    local rep="$1" n="$2" f base
    local -a fichiers
    mapfile -t fichiers < <(find "$rep" -maxdepth 1 -name '*.dump.age' -printf '%T@ %p\n' | sort -rn | cut -d' ' -f2-)
    for f in "${fichiers[@]:$n}"; do
      base="${f%.dump.age}"
      rm -f -- "$base.dump.age" "$base.roles.sql.age" "$base.sha256"
    done
  }
  garder "$quotidiennes" "${SAUVEGARDE_GARDER_QUOTIDIENNES:-14}"
  garder "$SAUVEGARDE_REPERTOIRE/hebdomadaires" "${SAUVEGARDE_GARDER_HEBDOMADAIRES:-8}"
  garder "$SAUVEGARDE_REPERTOIRE/mensuelles" "${SAUVEGARDE_GARDER_MENSUELLES:-12}"
fi

if [[ -n "${SAUVEGARDE_COPIE_DISTANTE:-}" ]]; then
  # Les fichiers sont chiffrés : la copie distante ne voit jamais de données en clair
  rsync -a --delete-after "$SAUVEGARDE_REPERTOIRE/" "$SAUVEGARDE_COPIE_DISTANTE/" \
    --exclude '.en-cours.*'
  echo "Copie hors site : $SAUVEGARDE_COPIE_DISTANTE"
fi

echo "$(date -Is) Terminé : $destination/$nom.dump.age ($(numfmt --to=iec "$taille"))"
