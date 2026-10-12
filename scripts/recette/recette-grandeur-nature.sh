#!/usr/bin/env bash
# =====================================================================
# RECETTE GRANDEUR NATURE — POSTE DE DÉVELOPPEMENT UNIQUEMENT
#
# Vérifie par l'API tous les parcours de l'application (environ 290 scénarios) sur une base
# peuplée de trois établissements, puis écrit le rapport scripts/recette/rapport-recette.md.
#
#   1. Si la base n'a jamais été peuplée (scripts/demo/etat-demo.json absent) ou si --repeupler
#      est demandé : EFFACE la base locale « plateforme » et la repeuple
#      (scripts/demo/mvp-grandeur-nature.sh, qui démarre aussi l'API avec le profil dev).
#   2. Sinon, l'API doit déjà tourner avec le profil dev (simulateurs Mobile Money et SMS).
#   3. Lance les scénarios : scripts/recette/recette.mjs
#
# Utilisation (depuis la racine du dépôt) :
#   scripts/recette/recette-grandeur-nature.sh                    recette sur la base existante
#   scripts/recette/recette-grandeur-nature.sh --repeupler        efface, repeuple, puis recette
#   scripts/recette/recette-grandeur-nature.sh --repeupler --oui  idem, sans confirmation
#   scripts/recette/recette-grandeur-nature.sh --api-eclipse      vous lancez l'API depuis Eclipse
#   scripts/recette/recette-grandeur-nature.sh --domaines=SEC,PAI seulement ces préfixes de scénarios
#   scripts/recette/recette-grandeur-nature.sh --liste            liste les scénarios sans rien exécuter
#
# La recette est rejouable sur la même base : elle crée ses propres élèves, comptes et établissement
# de test (collège de recette, résilié à la fin) et remet en état ce qu'elle modifie.
# Code de sortie : 0 si tout est réussi, 1 s'il y a des échecs, 2 si la recette n'a pas pu démarrer.
# =====================================================================
set -euo pipefail

RACINE="$(cd "$(dirname "$0")/../.." && pwd)"
SANTE="${API:-http://localhost:8080}/actuator/health"
ETAT="$RACINE/scripts/demo/etat-demo.json"

REPEUPLER=non
OPTIONS_PEUPLEMENT=()
OPTIONS_RECETTE=()
for arg in "$@"; do
  case "$arg" in
    --repeupler) REPEUPLER=oui ;;
    --oui|--api-eclipse) OPTIONS_PEUPLEMENT+=("$arg") ;;
    --domaines=*|--liste) OPTIONS_RECETTE+=("$arg") ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) echo "Option inconnue : $arg (voir --help)"; exit 2 ;;
  esac
done

echec() { echo; echo "✘ $*"; exit 2; }
info() { echo "• $*"; }

cd "$RACINE"
command -v node >/dev/null || echec "Node.js (version 20 ou plus) est introuvable."
version="$(node -p 'process.versions.node.split(".")[0]')"
[ "$version" -ge 20 ] || echec "Node.js $version est trop ancien : la recette demande la version 20 ou plus."

if [ "${OPTIONS_RECETTE[*]+${OPTIONS_RECETTE[*]}}" = "--liste" ]; then
  exec node "$RACINE/scripts/recette/recette.mjs" --liste
fi

if [ "$REPEUPLER" = oui ] || [ ! -f "$ETAT" ]; then
  [ -f "$ETAT" ] || info "Aucune base peuplée : peuplement préalable"
  if curl -s -o /dev/null --max-time 2 "$SANTE"; then
    echec "Une API tourne déjà : arrêtez-la avant de repeupler (scripts/demo/mvp-grandeur-nature.sh --arreter-api)."
  fi
  "$RACINE/scripts/demo/mvp-grandeur-nature.sh" ${OPTIONS_PEUPLEMENT[@]+"${OPTIONS_PEUPLEMENT[@]}"}
else
  command -v curl >/dev/null || echec "curl est introuvable."
  curl -s --max-time 3 "$SANTE" | grep -q '"UP"' \
    || echec "L'API ne répond pas sur ${API:-http://localhost:8080}. Lancez-la avec le profil dev, puis relancez."
  info "API prête — base peuplée le $(node -p "new Date(require('$ETAT').genereLe).toLocaleString('fr-FR')")"
fi

set +e
node "$RACINE/scripts/recette/recette.mjs" ${OPTIONS_RECETTE[@]+"${OPTIONS_RECETTE[@]}"}
code=$?
set -e
echo "  Rapport détaillé : scripts/recette/rapport-recette.md (et .json)"
echo "  Recette manuelle (interface, hors connexion, impression, SMS) : docs/RECETTE.md"
exit $code
