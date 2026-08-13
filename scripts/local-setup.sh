#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

echo "Building and starting the local stack (Postgres, Redis, Kafka, app)..."
docker compose -f docker/docker-compose.yml up --build -d

echo "Waiting for the app to become healthy..."
until curl -fs http://localhost:8080/actuator/health | grep -q '"status":"UP"'; do
  sleep 2
  printf '.'
done

echo
echo "Ready. Try:"
echo "  curl -s http://localhost:8080/v1/swagger-ui.html"
echo "  curl -s -X POST http://localhost:8080/v1/orders -H 'Content-Type: application/json' -d '{\"clientId\":\"client-1\",\"symbol\":\"AAPL\",\"side\":\"BUY\",\"quantity\":10,\"price\":150.00}'"
