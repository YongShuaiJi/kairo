#!/usr/bin/env bash
#
# Kairo V1.7 agent bundle - bounded launch example (roadmap §12.2).
#
# Attaches the Kairo agent to one target JVM. The target PID and local Agent API token are
# operator-supplied via required environment variables; this script ships no default token or secret.
#
# Required:
#   KAIRO_ATTACH_PID    PID of the target JVM to attach to.
#   KAIRO_AGENT_TOKEN  token protecting the attached Agent's loopback API.
# Optional:
#   KAIRO_AGENT_HOST   loopback bind host; default: 127.0.0.1.
#   KAIRO_AGENT_PORT   local Agent API port; default: 18080.
#
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="${SELF_DIR}/../lib"

: "${KAIRO_ATTACH_PID:?set KAIRO_ATTACH_PID to the target JVM pid}"
: "${KAIRO_AGENT_TOKEN:?set KAIRO_AGENT_TOKEN for the attached Agent loopback API}"
HOST="${KAIRO_AGENT_HOST:-127.0.0.1}"
PORT="${KAIRO_AGENT_PORT:-18080}"

# The bootstrap API jar is the only versioned jar in lib/; resolve it by glob.
BOOTSTRAP_JAR="$(ls "${LIB_DIR}"/kairo-bootstrap-api-*.jar 2>/dev/null | head -n1 || true)"
if [ -z "${BOOTSTRAP_JAR}" ]; then
  echo "error: kairo-bootstrap-api jar not found in ${LIB_DIR}" >&2
  exit 1
fi

exec java -jar "${LIB_DIR}/kairo-attach.jar" \
  --pid "${KAIRO_ATTACH_PID}" \
  --agent "${LIB_DIR}/kairo-agent-bootstrap.jar" \
  --core-jar "${LIB_DIR}/kairo-agent-core-modern.jar" \
  --bootstrap-jar "${BOOTSTRAP_JAR}" \
  --host "${HOST}" \
  --port "${PORT}" \
  --token "${KAIRO_AGENT_TOKEN}"
