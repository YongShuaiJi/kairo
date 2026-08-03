#!/usr/bin/env bash
#
# scripts/v1.7/check-reactor-version.sh
#
# V1.7 M5-A (roadmap §12.1) automated reactor version-consistency check. Verifies at runtime that:
#
#   1. the root Maven project resolves to the single reactor version;
#   2. representative child modules resolve to the SAME version (CI-friendly ${revision});
#   3. no reactor pom.xml retains a hard-coded 0.1.0-SNAPSHOT parent version;
#   4. the dev default <revision> is 1.7.0-SNAPSHOT;
#   5. an explicit release/RC revision overrides the default without editing child POMs.
#
# This complements the JUnit structural guard (ReactorVersionConsistencyTest) with the runtime
# resolution that help:evaluate provides. It is a verification helper, not a release assembler.
#
# Usage: ./scripts/v1.7/check-reactor-version.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${REPO_ROOT}"

DEFAULT_REVISION="1.7.0-SNAPSHOT"
STATUS=0

# --- 1. root resolved version ---
ROOT_VERSION="$(mvn -B -ntp -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -n 1)"
if [ "${ROOT_VERSION}" != "${DEFAULT_REVISION}" ]; then
  echo "FAIL: root project.version resolved to '${ROOT_VERSION}', expected '${DEFAULT_REVISION}'"
  STATUS=1
else
  echo "ok: root project.version = ${ROOT_VERSION}"
fi

# --- 2. representative children resolve to the same version ---
REVISION_PROP="$(mvn -B -ntp -q help:evaluate -Dexpression=revision -DforceStdout 2>/dev/null | tail -n 1)"
for child in kairo-api kairo-cli kairo-mcp kairo-ops kairo-platform-server kairo-agent-core; do
  child_version="$(mvn -B -ntp -pl "${child}" -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -n 1)"
  if [ "${child_version}" != "${ROOT_VERSION}" ]; then
    echo "FAIL: ${child} resolved to '${child_version}', expected '${ROOT_VERSION}'"
    STATUS=1
  else
    echo "ok: ${child} project.version = ${child_version}"
  fi
done

# --- 3. no reactor pom.xml hard-codes 0.1.0-SNAPSHOT ---
legacy="$(grep -rl "0.1.0-SNAPSHOT" --include=pom.xml . 2>/dev/null || true)"
if [ -n "${legacy}" ]; then
  echo "FAIL: hard-coded 0.1.0-SNAPSHOT remains in: ${legacy}"
  STATUS=1
else
  echo "ok: no reactor pom.xml contains 0.1.0-SNAPSHOT"
fi

# --- 4. dev default revision property ---
if [ "${REVISION_PROP}" != "${DEFAULT_REVISION}" ]; then
  echo "FAIL: <revision> property resolved to '${REVISION_PROP}', expected '${DEFAULT_REVISION}'"
  STATUS=1
else
  echo "ok: <revision> = ${REVISION_PROP}"
fi

# --- 5. release/RC override resolves without editing child POMs ---
rc_version="$(mvn -B -ntp -q -Drevision=1.7.0-rc.1 help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -n 1)"
rel_version="$(mvn -B -ntp -q -Drevision=1.7.0 help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -n 1)"
if [ "${rc_version}" != "1.7.0-rc.1" ]; then
  echo "FAIL: -Drevision=1.7.0-rc.1 resolved to '${rc_version}'"
  STATUS=1
else
  echo "ok: -Drevision=1.7.0-rc.1 -> ${rc_version}"
fi
if [ "${rel_version}" != "1.7.0" ]; then
  echo "FAIL: -Drevision=1.7.0 resolved to '${rel_version}'"
  STATUS=1
else
  echo "ok: -Drevision=1.7.0 -> ${rel_version}"
fi

if [ "${STATUS}" -eq 0 ]; then
  echo "check-reactor-version: PASS"
else
  echo "check-reactor-version: FAIL"
fi
exit "${STATUS}"
