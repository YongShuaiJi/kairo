#!/usr/bin/env bash
#
# Kairo V1.7 agent bundle - bounded launch example (roadmap §12.2).
#
# Lists the target JVMs attachable on this host. Safe: performs no attach and uses no token.
#
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="${SELF_DIR}/../lib"

exec java -jar "${LIB_DIR}/kairo-attach.jar" --list
