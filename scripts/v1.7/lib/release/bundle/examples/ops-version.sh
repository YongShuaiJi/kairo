#!/usr/bin/env bash
#
# Kairo V1.7 agent bundle - bounded launch example (roadmap §12.2).
#
# Prints the ops CLI build version. Safe: performs no Platform mutation and uses no token/network.
#
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB_DIR="${SELF_DIR}/../lib"

exec java -jar "${LIB_DIR}/kairo-ops.jar" --version
