#!/usr/bin/env bash
#
# scripts/v1.7/verify-compatibility.sh
#
# V1.7 M3-A/M3-F compatibility verifier (section 10.3 / 10.4.1 / 10.4.6). Validates an
# existing compatibility-result.json: aggregate schema, catalog completeness (all C01-C10
# present exactly once), a single candidate build id, formal-row status semantics,
# summary/count consistency, and evidence/provenance fields. It does NOT rerun scenarios.
#
# M3-F strengthening (section 10.4.6): when --doc and/or --manifest are supplied, the
# verifier additionally cross-checks that docs/compatibility/v1.7.md was generated from
# this aggregate (source hash/overall/buildId/catalog version, provenance marker, no
# release overclaim) and that v1.7-acceptance-manifest.json has a valid PR -> RC -> RELEASE
# lifecycle, complete evidence for passed gates and no overclaim - so aggregate, document
# and release-manifest conclusions cannot diverge.
#
# Fixed interface (section 10.3):
#   ./scripts/v1.7/verify-compatibility.sh target/v1.7/compatibility-result.json \
#       [--doc docs/compatibility/v1.7.md] \
#       [--manifest v1.7-acceptance-manifest.json]
#
# Exit codes (preserved exactly from the verifier):
#   0  result valid and complete (overall=PASSED, all formal rows PASSED)
#   1  usage error / file not found
#   2  build failed
#   3  verifier unusable
#   4  result invalid or incomplete (overall=FAILED, or semantic/cross-check violation)
#   6  malformed JSON (unparseable)
#
# The runner NEVER switches or dirties the active V1.7 worktree. It builds the
# verifier in place (HEAD), runs it in a fresh JVM, and preserves the exact exit
# code. It performs no network downloads and never modifies the workflow, the
# acceptance manifest, or support conclusions.

set -euo pipefail
# Fail-fast is on (`-e`). The ONLY intentional non-fatal command is the verifier
# invocation: it returns 1/4/6 on usage/invalid/malformed, and we capture that
# status explicitly and exit with the EXACT code (see the `set +e` block below).

RESULT_FILE=""
DOC_FILE=""
MANIFEST_FILE=""
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
MVN="${MVN:-mvn}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERIFIER_MAIN="com.example.kairo.compatmatrix.CompatibilityVerifierMain"
MODULES=(kairo-bootstrap-api kairo-api kairo-groovy kairo-core kairo-agent-core
         kairo-agent-server kairo-agent-core-modern kairo-agent-bootstrap
         kairo-attach-cli kairo-ops kairo-platform-server kairo-sdk kairo-cli
         kairo-mcp kairo-demo kairo-integration-tests)

usage() {
  cat <<'EOF'
Usage: verify-compatibility.sh <result.json>
       [--doc <docs/compatibility/v1.7.md>]
       [--manifest <v1.7-acceptance-manifest.json>] [--help]

Required:
  result.json   the compatibility-result.json produced by aggregate-compatibility.sh

Optional:
  --doc         generated compatibility document to cross-check against the result
  --manifest    v1.7-acceptance-manifest.json to cross-check V17-COMPAT gate conclusions
  --help        show this help

Behavior:
  - Builds the verifier in place at HEAD (kairo-integration-tests -am test-compile);
    the active V1.7 worktree is never switched or dirtied.
  - Validates the aggregate schema, catalog completeness, single candidate build id,
    formal-row status semantics, summary/count consistency and evidence/provenance.
  - With --doc: rejects a document not generated from this result (hash/overall/buildId)
    or that overclaims release readiness.
  - With --manifest: validates the V17-COMPAT PR -> RC -> RELEASE lifecycle, complete
    evidence for passed gates, and rejects unsupported gate overclaims.
  - Does NOT rerun scenarios; never modifies the workflow, the acceptance manifest,
    or support conclusions.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --doc)
      if [[ $# -lt 2 ]]; then echo "error: --doc requires a value" >&2; exit 1; fi
      DOC_FILE="$2"; shift 2 ;;
    --manifest)
      if [[ $# -lt 2 ]]; then echo "error: --manifest requires a value" >&2; exit 1; fi
      MANIFEST_FILE="$2"; shift 2 ;;
    --*) echo "unknown argument: $1" >&2; usage; exit 1 ;;
    *)
      if [[ -n "$RESULT_FILE" ]]; then
        echo "error: only one result file argument is allowed" >&2
        exit 1
      fi
      RESULT_FILE="$1"
      shift
      ;;
  esac
done

if [[ -z "$RESULT_FILE" ]]; then
  echo "error: a result.json path is required" >&2
  usage
  exit 1
fi
if [[ ! -f "$RESULT_FILE" ]]; then
  echo "error: result file not found: $RESULT_FILE" >&2
  exit 1
fi

echo "==> verifying $RESULT_FILE"

# -----------------------------------------------------------------------------
# Build the verifier in place at HEAD. Single side; no worktree, no dirty.
# -----------------------------------------------------------------------------
echo "==> building verifier at $REPO_ROOT"
if ! (cd "$REPO_ROOT" && $MVN -B -ntp -pl kairo-integration-tests -am test-compile -q); then
  echo "error: verifier build failed" >&2
  exit 2
fi

# -----------------------------------------------------------------------------
# Assemble the classpath: verifier test-classes + reactor target/classes + ext deps.
# -----------------------------------------------------------------------------
reactor_classes() {
  local out=""
  for m in "${MODULES[@]}"; do
    local d="$REPO_ROOT/$m/target/classes"
    if [[ -d "$d" ]]; then out="$out:$d"; fi
  done
  echo "${out#:}"
}
EXTDEPS_FILE="$(mktemp -t kairo-compatver-deps-XXXXXX)"
if ! (cd "$REPO_ROOT" && $MVN -B -ntp -pl kairo-integration-tests -am \
      package -DskipTests dependency:build-classpath \
      -Dmdep.outputFile="$EXTDEPS_FILE" -DincludeScope=test -q); then
  echo "error: dependency:build-classpath failed" >&2
  rm -f "$EXTDEPS_FILE"
  exit 2
fi
EXTDEPS="$(cat "$EXTDEPS_FILE")"
rm -f "$EXTDEPS_FILE"
CP="$REPO_ROOT/kairo-integration-tests/target/test-classes:$(reactor_classes):$EXTDEPS"

echo "==> running verifier"
# The verifier returns 1/4/6 on usage/invalid/malformed. Temporarily disable `-e`
# so those codes are captured (not turned into an early abort), then exit with the
# EXACT code.
VERIFIER_ARGS=("$RESULT_FILE")
[[ -n "$DOC_FILE" ]] && VERIFIER_ARGS+=("--doc" "$DOC_FILE")
[[ -n "$MANIFEST_FILE" ]] && VERIFIER_ARGS+=("--manifest" "$MANIFEST_FILE")
VER_STATUS=0
set +e
"$JAVA_BIN" -cp "$CP" "$VERIFIER_MAIN" "${VERIFIER_ARGS[@]}"
VER_STATUS=$?
set -e

echo "==> done (verifier exit=$VER_STATUS)."
exit "$VER_STATUS"
