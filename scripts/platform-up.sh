#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mvn -pl runtime-mock-platform-server -am -DskipTests package
docker compose up -d postgres kafka redis minio keycloak
docker compose build platform
docker compose up -d platform

echo "Runtime Mock platform: http://127.0.0.1:18280/api/v1/control/health"
