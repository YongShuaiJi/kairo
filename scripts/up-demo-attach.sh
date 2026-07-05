#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mvn -pl kairo-platform-server,kairo-demo,kairo-sidecar,kairo-agent-bootstrap,kairo-agent-core-modern,kairo-bootstrap-api -am -DskipTests package
docker compose -f docker-compose.yml -f docker-compose.attach.yml up -d --build --remove-orphans \
  platform platform-web demo-app demo-attach-executor

echo "Kairo demo mode: attach"
echo "Kairo platform: http://127.0.0.1:18280/api/v1/control/health"
echo "Kairo web console: http://127.0.0.1:18380"
echo "Kairo demo application: http://127.0.0.1:18092/demo/stats"
