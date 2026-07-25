#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Modules required to build the attach-executor image and its runtime dependencies.
# kairo-attach-cli now hosts the demo attach executor (merged from the former kairo-sidecar).
ATTACH_MODULES="kairo-platform-server,kairo-demo,kairo-attach-cli,kairo-agent-bootstrap,kairo-agent-core-modern,kairo-bootstrap-api"

if [[ "${1:-}" == "--verify-only" ]]; then
  # Build the artifacts and validate the Compose topology, entrypoints and environment
  # without starting long-running processes. The real attach closed loop is exercised by
  # the M1 overall acceptance, not by this verification mode.
  mvn -pl "$ATTACH_MODULES" -am -DskipTests package
  docker compose -f docker-compose.yml -f docker-compose.attach.yml config >/dev/null
  echo "Kairo demo attach: verify-only OK (build + compose config validated)"
  exit 0
fi

mvn -pl "$ATTACH_MODULES" -am -DskipTests package
docker compose -f docker-compose.yml -f docker-compose.attach.yml up -d --build --remove-orphans \
  platform platform-web demo-app demo-attach-executor

echo "Kairo demo mode: attach"
echo "Kairo platform: http://127.0.0.1:18280/api/v1/control/health"
echo "Kairo web console: http://127.0.0.1:18380"
echo "Kairo demo application: http://127.0.0.1:18092/demo/stats"
