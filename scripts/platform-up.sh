#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mvn -pl runtime-mock-platform-server,runtime-mock-demo,runtime-mock-agent-bootstrap,runtime-mock-agent-core-modern -am -DskipTests package
docker compose up -d postgres kafka redis minio
docker compose build platform platform-web demo-app
docker compose up -d platform worker platform-web demo-app

echo "Runtime Mock platform: http://127.0.0.1:18280/api/v1/control/health"
echo "Runtime Mock worker health: http://127.0.0.1:18281/actuator/health"
echo "Runtime Mock web console: http://127.0.0.1:18380"
echo "Runtime Mock demo application: http://127.0.0.1:18092/demo/stats"
