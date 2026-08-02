#!/usr/bin/env bash
#
# scripts/v1.7/generate-compatibility-doc.sh
#
# V1.7 M3-F compatibility document generator (section 10.4.6). Reads the single
# compatibility-result.json produced by aggregate-compatibility.sh and writes the
# deterministic docs/compatibility/v1.7.md via CompatibilityDocumentGenerator.
#
# The document is reproducible from the same input and embeds a SHA-256 of the canonical
# source result; verify-compatibility.sh --doc rejects any divergence. The generator never
# judges whether the matrix passed and never modifies the workflow, the acceptance
# manifest, or support conclusions.
#
# Fixed interface (section 10.4.6):
#   ./scripts/v1.7/generate-compatibility-doc.sh \
#     --input target/v1.7/compatibility-result.json \
#     --output docs/compatibility/v1.7.md
#
# Exit codes (preserved exactly from the generator):
#   0  document generated
#   1  usage / validation error
#   2  build failed
#   3  input not found / unreadable
#   5  write error
#   6  input is not parseable JSON
#
# The runner NEVER switches or dirties the active V1.7 worktree. It builds the generator
# in place (HEAD), runs it in a fresh JVM, preserves the exact exit code, and performs no
# network downloads.

set -euo pipefail
# Fail-fast is on (`-e`). The ONLY intentional non-fatal command is the generator
# invocation: it returns 3/5/6 on unusable/write/parse failure, and we capture that
# status explicitly and exit with the EXACT code (see the `set +e` block below).

INPUT=""
OUTPUT=""
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
MVN="${MVN:-mvn}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GENERATOR_MAIN="com.example.kairo.compatmatrix.CompatibilityDocumentGeneratorMain"
MODULES=(kairo-bootstrap-api kairo-api kairo-groovy kairo-core kairo-agent-core
         kairo-agent-server kairo-agent-core-modern kairo-agent-bootstrap
         kairo-attach-cli kairo-ops kairo-platform-server kairo-sdk kairo-cli
         kairo-mcp kairo-demo kairo-integration-tests)

usage() {
  cat <<'EOF'
Usage: generate-compatibility-doc.sh --input <result.json> --output <doc.md> [--help]

Required:
  --input    the compatibility-result.json produced by aggregate-compatibility.sh
  --output   output path for docs/compatibility/v1.7.md

Optional:
  --help     show this help

Behavior:
  - Builds the generator in place at HEAD (kairo-integration-tests -am test-compile);
    the active V1.7 worktree is never switched or dirtied.
  - Renders the deterministic compatibility document from exactly the input JSON.
  - The document is reproducible and embeds a SHA-256 of the source result; it never
    embeds a generation timestamp of its own (only the aggregate's generatedAt).
  - Never modifies the workflow, the acceptance manifest, or support conclusions.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input) INPUT="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

if [[ -z "$INPUT" || -z "$OUTPUT" ]]; then
  echo "error: --input and --output are required" >&2
  usage
  exit 1
fi

# Contamination guard: clear ONLY this runner's prior output file, then recreate parent.
rm -f "$OUTPUT"
PARENT_DIR="$(dirname "$OUTPUT")"
mkdir -p "$PARENT_DIR"

echo "==> input=$INPUT output=$OUTPUT"

# -----------------------------------------------------------------------------
# Build the generator in place at HEAD. Single side; no worktree, no dirty.
# -----------------------------------------------------------------------------
echo "==> building generator at $REPO_ROOT"
if ! (cd "$REPO_ROOT" && $MVN -B -ntp -pl kairo-integration-tests -am test-compile -q); then
  echo "error: generator build failed" >&2
  exit 2
fi

# -----------------------------------------------------------------------------
# Assemble the classpath: generator test-classes + reactor target/classes + ext deps.
# -----------------------------------------------------------------------------
reactor_classes() {
  local out=""
  for m in "${MODULES[@]}"; do
    local d="$REPO_ROOT/$m/target/classes"
    if [[ -d "$d" ]]; then out="$out:$d"; fi
  done
  echo "${out#:}"
}
EXTDEPS_FILE="$(mktemp -t kairo-compatdoc-deps-XXXXXX)"
if ! (cd "$REPO_ROOT" && $MVN -B -ntp -pl kairo-integration-tests \
      dependency:build-classpath -Dmdep.outputFile="$EXTDEPS_FILE" -q >/dev/null 2>&1); then
  echo "error: dependency:build-classpath failed" >&2
  rm -f "$EXTDEPS_FILE"
  exit 2
fi
EXTDEPS="$(cat "$EXTDEPS_FILE")"
rm -f "$EXTDEPS_FILE"
CP="$REPO_ROOT/kairo-integration-tests/target/test-classes:$(reactor_classes):$EXTDEPS"

echo "==> running generator"
# The generator returns 3/5/6 on unusable/write/parse failure. Temporarily disable
# `-e` so those codes are captured (not turned into an early abort), then exit with
# the EXACT code. The document is always written (best effort).
GEN_STATUS=0
set +e
"$JAVA_BIN" -cp "$CP" "$GENERATOR_MAIN" \
  --input "$INPUT" \
  --output "$OUTPUT"
GEN_STATUS=$?
set -e

echo "==> done (generator exit=$GEN_STATUS). document: $OUTPUT"
exit "$GEN_STATUS"
