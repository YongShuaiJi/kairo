#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mvn -pl runtime-mock-platform-server,runtime-mock-demo,runtime-mock-agent-bootstrap,runtime-mock-agent-core-modern,runtime-mock-bootstrap-api -am -DskipTests package
docker compose -f docker-compose.yml -f docker-compose.premain.yml up -d --build --remove-orphans \
  platform platform-web demo-app

echo "Runtime Mock demo mode: premain"
echo "Runtime Mock platform: http://127.0.0.1:18280/api/v1/control/health"
echo "Runtime Mock web console: http://127.0.0.1:18380"
echo "Runtime Mock demo application: http://127.0.0.1:18092/demo/stats"
