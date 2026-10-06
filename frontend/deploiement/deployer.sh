#!/usr/bin/env bash
# =====================================================================
# Déploiement progressif sur le serveur : une instance à la fois,
# la suivante seulement si la précédente est saine.
# Usage : ./deployer.sh <tag-de-l-image>     (ex. sha-1a2b3c4 ou v1.2.0)
# Retour arrière : relancer avec le tag précédent.
# =====================================================================
set -euo pipefail

TAG="${1:?Tag de l image requis}"
cd "$(dirname "$0")"

if [[ ! "$TAG" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "Tag invalide : $TAG" >&2
  exit 1
fi

echo "Déploiement de $TAG"
sed -i "s/^TAG=.*/TAG=${TAG}/" .env
docker compose pull api1 api2

for service in api1 api2; do
  docker compose up -d --no-deps "$service"
  etat="starting"
  for _ in $(seq 1 40); do
    conteneur="$(docker compose ps -q "$service")"
    etat="$(docker inspect -f '{{.State.Health.Status}}' "$conteneur" 2>/dev/null || echo "inconnu")"
    [[ "$etat" == "healthy" ]] && break
    sleep 5
  done
  if [[ "$etat" != "healthy" ]]; then
    echo "ÉCHEC : $service n'est pas sain (état : $etat). Relancez avec le tag précédent." >&2
    docker compose logs --tail=100 "$service" >&2
    exit 1
  fi
  echo "$service : sain"
done

docker compose up -d caddy
docker image prune -f >/dev/null
echo "Déploiement de $TAG terminé"
