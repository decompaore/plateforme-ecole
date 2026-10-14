#!/usr/bin/env bash
# =====================================================================
# TEST GRANDEUR NATURE DU MVP — POSTE DE DÉVELOPPEMENT UNIQUEMENT
#
#   1. EFFACE la base locale « plateforme » (conteneur Docker plateforme-postgres) ;
#      la base des tests (plateforme_test) n'est pas touchée.
#   2. Démarre l'API avec le profil dev (Flyway recrée le schéma, le super
#      administrateur est réamorcé), ou attend que vous la lanciez depuis Eclipse.
#   3. Peuple trois établissements complets : scripts/demo/peupler-mvp.mjs
#
# Utilisation (depuis la racine du dépôt) :
#   scripts/demo/mvp-grandeur-nature.sh                 demande confirmation (taper EFFACER)
#   scripts/demo/mvp-grandeur-nature.sh --oui           sans confirmation
#   scripts/demo/mvp-grandeur-nature.sh --api-eclipse   vous lancez l'API vous-même (Eclipse)
#   scripts/demo/mvp-grandeur-nature.sh --arreter-api   arrête l'API lancée par ce script
#
# Variables transmises au peuplement : SEMAINES, ELEVES, PARALLELE, DEMO_MOT_DE_PASSE…
# (voir l'en-tête de peupler-mvp.mjs).
# =====================================================================
set -euo pipefail

RACINE="$(cd "$(dirname "$0")/../.." && pwd)"
CONTENEUR="plateforme-postgres"
BASE="plateforme"
SANTE="http://localhost:8080/actuator/health"
JOURNAL="$RACINE/scripts/demo/api-demo.log"

CONFIRME=non
API_ECLIPSE=non
for arg in "$@"; do
  case "$arg" in
    --oui) CONFIRME=oui ;;
    --api-eclipse) API_ECLIPSE=oui ;;
    --arreter-api)
      pids="$(lsof -ti tcp:8080 -sTCP:LISTEN 2>/dev/null || true)"
      if [ -z "$pids" ]; then echo "Aucune API n'écoute sur le port 8080."; exit 0; fi
      echo "Arrêt de l'API (PID $pids)…"
      # shellcheck disable=SC2086 # plusieurs PID possibles
      kill $pids; exit 0 ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
    *) echo "Option inconnue : $arg (voir --help)"; exit 1 ;;
  esac
done

echec() { echo; echo "✘ $*"; exit 1; }
info() { echo "• $*"; }

cd "$RACINE"
command -v docker >/dev/null || echec "Docker est introuvable."
command -v node >/dev/null || echec "Node.js est introuvable."
command -v curl >/dev/null || echec "curl est introuvable."

# Sécurité : uniquement le Docker local, jamais un hôte distant
case "${DOCKER_HOST:-}" in
  ""|unix://*) ;;
  *) echec "DOCKER_HOST pointe vers $DOCKER_HOST : ce script n'efface que la base d'un Docker LOCAL." ;;
esac

if curl -s -o /dev/null --max-time 2 "$SANTE"; then
  echec "Une API tourne déjà sur le port 8080. Arrêtez-la (Eclipse : bouton Stop, ou $0 --arreter-api), puis relancez."
fi

echo
echo "  ⚠  Ce script EFFACE toutes les données de la base locale « $BASE » (conteneur $CONTENEUR),"
echo "     puis crée trois établissements de démonstration. Développement uniquement."
echo
if [ "$CONFIRME" != oui ]; then
  printf "  Tapez EFFACER pour continuer : "
  read -r reponse
  [ "$reponse" = "EFFACER" ] || echec "Abandon : rien n'a été effacé."
fi

info "Démarrage de PostgreSQL (docker compose)"
docker compose up -d postgres >/dev/null
for i in $(seq 1 60); do
  etat="$(docker inspect -f '{{.State.Health.Status}}' "$CONTENEUR" 2>/dev/null || echo absent)"
  [ "$etat" = healthy ] && break
  [ "$i" = 60 ] && echec "PostgreSQL ne répond pas (état : $etat)."
  sleep 2
done
# Les rôles sont créés par les scripts d'initialisation au tout premier démarrage du volume
for i in $(seq 1 30); do
  if docker exec "$CONTENEUR" psql -U postgres -tAc "select 1 from pg_roles where rolname = 'plateforme_owner'" 2>/dev/null | grep -q 1; then break; fi
  [ "$i" = 30 ] && echec "Le rôle plateforme_owner est absent : vérifiez infra/postgres/init."
  sleep 2
done

info "Effacement de la base « $BASE »"
docker exec "$CONTENEUR" psql -U postgres -v ON_ERROR_STOP=1 -q \
  -c "DROP DATABASE IF EXISTS $BASE WITH (FORCE);" \
  -c "CREATE DATABASE $BASE OWNER plateforme_owner;" >/dev/null

if [ "$API_ECLIPSE" = oui ]; then
  info "Lancez maintenant l'API depuis Eclipse (profil dev) ; j'attends qu'elle réponde…"
else
  command -v mvn >/dev/null || echec "Maven (mvn) est introuvable : relancez avec --api-eclipse et démarrez l'API depuis Eclipse."
  info "Démarrage de l'API (profil dev) — journal : scripts/demo/api-demo.log"
  ( cd backend && nohup mvn -q spring-boot:run -Dspring-boot.run.profiles=dev >"$JOURNAL" 2>&1 & )
fi

for i in $(seq 1 120); do
  if curl -s --max-time 2 "$SANTE" | grep -q '"UP"'; then break; fi
  if [ "$i" = 120 ]; then
    [ "$API_ECLIPSE" = oui ] || tail -n 30 "$JOURNAL"
    echec "L'API ne répond pas après 4 minutes."
  fi
  sleep 2
done
info "API prête"

node "$RACINE/scripts/demo/peupler-mvp.mjs"

echo
if [ "$API_ECLIPSE" != oui ]; then
  echo "  L'API continue de tourner (journal : scripts/demo/api-demo.log)."
  echo "  Pour l'arrêter : $0 --arreter-api"
fi
echo "  Interface : cd frontend && npm start, puis http://localhost:4200"
