#!/usr/bin/env bash
#
# scripts/v1.7/build-release.sh
#
# V1.7 M5-B (roadmap §12.2 / §12.4 / §12.5) official release artifact assembler.
#
# Builds the exact §12.2 inventory for a release version:
#   - kairo-agent-bundle-<version>.tar.gz  (bootstrap API, agent bootstrap, agent core modern,
#                                            attach CLI, ops CLI, LICENSE, bounded launch examples)
#   - kairo-platform-server-<version>.jar
#   - kairo-cli-<version>.jar
#   - kairo-mcp-<version>.jar
#   - kairo-sdk-<version>.jar
#   - kairo-compose-<version>.tar.gz        (Compose, env template, upgrade notes)
#   - local images kairo-platform-server:<version> and kairo-platform-web:<version>
#
# It assembles deterministically (sorted tar entries, fixed uid/gid/mtime/modes, gzip mtime=0),
# writes SHA256SUMS for the six file artifacts, and writes an honest release-manifest.json whose
# SBOM/signature/provenance/evidence/support-date fields carry NOT_AVAILABLE/NOT_RUN/SKIPPED
# statuses owned by M5-C/M5-D/M6. It never publishes, pushes, signs, generates an SBOM, fabricates
# evidence, or invents a registry digest.
#
# Usage:
#   ./scripts/v1.7/build-release.sh --version 1.7.0-rc.1 --output target/v1.7/release-smoke
#   ./scripts/v1.7/build-release.sh --version 1.7.0-rc.1 --output <dir> --allow-dirty
#
# --allow-dirty is a focused development override only. It is recorded truthfully in the manifest
# (allowDirty=true + the dirty file list + a knownLimitations disclosure) and must never be
# presented as final/clean release evidence.
#
set -euo pipefail

# Never leave bytecode caches in the worktree; releaselib is only ever run as a script here.
export PYTHONDONTWRITEBYTECODE=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd -P)"
LIB="${SCRIPT_DIR}/lib/releaselib.py"
RELEASE_LIB="${SCRIPT_DIR}/lib/release"

usage() {
  cat <<'EOF'
Usage: build-release.sh --version <version> --output <dir> [--allow-dirty]
                        [--source-date-epoch <seconds>]

  --version               release version: 1.7.0 or 1.7.0-rc.N (N>=1); no SNAPSHOT.
  --output               output directory (must be within the repo root, must not be the repo
                         root, must not already exist as a non-empty directory).
  --allow-dirty          development override: proceed even if the worktree is dirty. Recorded
                         truthfully in the manifest; never presented as clean release evidence.
  --source-date-epoch    override SOURCE_DATE_EPOCH (defaults to the HEAD commit timestamp).
EOF
}

VERSION=""
OUTPUT_RAW=""
ALLOW_DIRTY=0
SOURCE_DATE_EPOCH_ARG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --version)              VERSION="$2"; shift 2 ;;
    --output)               OUTPUT_RAW="$2"; shift 2 ;;
    --allow-dirty)          ALLOW_DIRTY=1; shift ;;
    --source-date-epoch)    SOURCE_DATE_EPOCH_ARG="$2"; shift 2 ;;
    -h|--help)              usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

fail() { echo "error: $*" >&2; exit 1; }

# --- 1. version contract -------------------------------------------------------------
[ -n "$VERSION" ]    || { usage >&2; fail "--version is required"; }
[ -n "$OUTPUT_RAW" ] || { usage >&2; fail "--output is required"; }
python3 "$LIB" validate-version "$VERSION" >/dev/null \
  || fail "release version rejected: $VERSION"

# --- 2. repository root ---------------------------------------------------------------
[ -f "$REPO_ROOT/pom.xml" ] && [ -e "$REPO_ROOT/.git" ] \
  || fail "not a kairo repository root: $REPO_ROOT"
grep -q '<artifactId>kairo-parent</artifactId>' "$REPO_ROOT/pom.xml" \
  || fail "root pom is not kairo-parent: $REPO_ROOT/pom.xml"
[ -f "$LIB" ] && [ -d "$RELEASE_LIB" ] \
  || fail "release library missing under $SCRIPT_DIR/lib"

# --- 3. output containment -----------------------------------------------------------
# Resolve to an absolute, normalized path (relative paths are anchored at the repo root).
if [[ "$OUTPUT_RAW" != /* ]]; then
  OUTPUT_RAW="$REPO_ROOT/$OUTPUT_RAW"
fi
OUTPUT="$(python3 -c 'import os,sys; print(os.path.realpath(os.path.normpath(sys.argv[1])))' "$OUTPUT_RAW")"
case "$OUTPUT" in
  "$REPO_ROOT")   fail "output must not be the repository root itself: $OUTPUT" ;;
  "$REPO_ROOT"/*) : ;;  # contained within the repo root (realpath already resolved symlinks/..)
  *)              fail "output must be within the repository root (safe containment): $OUTPUT" ;;
esac
if [ -e "$OUTPUT" ] && [ ! -d "$OUTPUT" ]; then
  fail "output exists and is not a directory: $OUTPUT"
fi
if [ -d "$OUTPUT" ] && [ -n "$(ls -A "$OUTPUT" 2>/dev/null || true)" ]; then
  fail "output directory is not empty (refusing to clobber); remove it first: $OUTPUT"
fi

# --- 4. clean tracked state ----------------------------------------------------------
cd "$REPO_ROOT"
DIRTY_FILES_FILE=""
if [ "$ALLOW_DIRTY" -eq 1 ]; then
  DIRTY_FILES_FILE="$(mktemp -t kairo-dirty.XXXXXX)"
  git status --porcelain > "$DIRTY_FILES_FILE" || true
else
  dirty="$(git status --porcelain 2>/dev/null || true)"
  if [ -n "$dirty" ]; then
    echo "error: worktree is dirty; commit/stash first, or pass --allow-dirty for a dev build:" >&2
    echo "$dirty" >&2
    exit 1
  fi
fi

# --- 5. tools -------------------------------------------------------------------------
need_cmd() { command -v "$1" >/dev/null 2>&1 || fail "required tool not found: $1"; }
need_cmd mvn; need_cmd java; need_cmd docker; need_cmd python3; need_cmd unzip; need_cmd tar; need_cmd gzip

MVN_VER="$(mvn -version 2>/dev/null | head -1 | sed -E 's/Apache Maven ([0-9.]+).*/\1/')"
JAVA_VER="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9.]+).*/\1/')"
DOCKER_VER="$(docker --version 2>/dev/null | sed -E 's/Docker version ([0-9.]+).*/\1/')"
OS_NAME="$(uname -s)"
ARCH="$(uname -m)"
[ -n "$MVN_VER" ]   || fail "could not determine Maven version"
[ -n "$JAVA_VER" ] || fail "could not determine Java version"
[ -n "$DOCKER_VER" ] || fail "could not determine Docker version"

# --- 6. SOURCE_DATE_EPOCH ------------------------------------------------------------
if [ -n "$SOURCE_DATE_EPOCH_ARG" ]; then
  SOURCE_DATE_EPOCH="$SOURCE_DATE_EPOCH_ARG"
else
  SOURCE_DATE_EPOCH="$(git log -1 --format=%ct HEAD)"
fi
[[ "$SOURCE_DATE_EPOCH" =~ ^[0-9]+$ ]] || fail "SOURCE_DATE_EPOCH is not a non-negative integer: $SOURCE_DATE_EPOCH"
export SOURCE_DATE_EPOCH

# V1.7 M5-D (roadmap §12.4): Maven reproducible-builds flag. Deriving project.build.outputTimestamp
# from SOURCE_DATE_EPOCH fixes JAR entry mtimes (maven-jar/assembly/shade plugins) and Spring Boot
# build-info build.time, so two clean builds of the same commit produce bit-identical file artifacts.
OUTPUT_TIMESTAMP="$(python3 -c 'import datetime as d,sys; print(d.datetime.fromtimestamp(int(sys.argv[1]),tz=d.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))' "$SOURCE_DATE_EPOCH")"
export OUTPUT_TIMESTAMP

GIT_COMMIT="$(git rev-parse HEAD)"
BUILD_WORKFLOW="./scripts/v1.7/build-release.sh --version $VERSION --output ${OUTPUT_RAW#${REPO_ROOT}/}"
[ "$ALLOW_DIRTY" -eq 1 ] && BUILD_WORKFLOW="$BUILD_WORKFLOW --allow-dirty"
# Real wall-clock build timestamps (volatile by design; §12.5 requires them; not in SHA256SUMS).
BUILD_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "==> kairo release build"
echo "    version        : $VERSION"
echo "    gitCommit      : $GIT_COMMIT"
echo "    output         : $OUTPUT"
echo "    sourceDateEpoch: $SOURCE_DATE_EPOCH"
[ "$ALLOW_DIRTY" -eq 1 ] && echo "    allowDirty     : true (dev override, recorded in manifest)"

# --- staging roots (owned mktemp; cleaned on exit) -----------------------------------
STAGE="$(mktemp -d -t kairo-release-stage.XXXXXX)"
SERVER_CTX="$(mktemp -d -t kairo-platform-ctx.XXXXXX)"
SERVER_INSPECT=""
WEB_INSPECT=""
cleanup() {
  rm -rf "$STAGE" "$SERVER_CTX" 2>/dev/null || true
  [ -z "$DIRTY_FILES_FILE" ] || rm -f "$DIRTY_FILES_FILE" 2>/dev/null || true
  [ -z "$SERVER_INSPECT" ] || rm -f "$SERVER_INSPECT" 2>/dev/null || true
  [ -z "$WEB_INSPECT" ] || rm -f "$WEB_INSPECT" 2>/dev/null || true
  # Defensive: never leave bytecode caches in the worktree.
  rm -rf "$SCRIPT_DIR/lib/__pycache__" 2>/dev/null || true
}
trap cleanup EXIT

# --- 7. Maven package (release revision) ---------------------------------------------
# NOTE: `mvn clean` removes the reactor root build directory (kairo-parent target/), so the output
# directory (commonly target/v1.7/...) is created AFTER the Maven build, not before.
# -Dproject.build.outputTimestamp (M5-D §12.4) fixes JAR entry mtimes + spring-boot build.time to
#   SOURCE_DATE_EPOCH for reproducible file artifacts.
echo "==> mvn clean package -Drevision=$VERSION -DskipTests (-Dproject.build.outputTimestamp; §14 is a separate gate)"
mvn -B -ntp -Drevision="$VERSION" -Dproject.build.outputTimestamp="$OUTPUT_TIMESTAMP" -DskipTests clean package
echo "    maven: ok"

# --- 8. locate + verify build outputs (reject SNAPSHOT identities) --------------------
PLATFORM_JAR="$REPO_ROOT/kairo-platform-server/target/kairo-platform-server-$VERSION.jar"
CLI_SRC="$REPO_ROOT/kairo-cli/target/kairo-cli.jar"
MCP_SRC="$REPO_ROOT/kairo-mcp/target/kairo-mcp.jar"
SDK_JAR="$REPO_ROOT/kairo-sdk/target/kairo-sdk-$VERSION.jar"
BOOTSTRAP_API_JAR="$REPO_ROOT/kairo-bootstrap-api/target/kairo-bootstrap-api-$VERSION.jar"
AGENT_BOOTSTRAP_JAR="$REPO_ROOT/kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar"
AGENT_CORE_JAR="$REPO_ROOT/kairo-agent-core-modern/target/kairo-agent-core-modern.jar"
ATTACH_JAR="$REPO_ROOT/kairo-attach-cli/target/kairo-attach.jar"
OPS_JAR="$REPO_ROOT/kairo-ops/target/kairo-ops.jar"

[ -f "$PLATFORM_JAR" ]      || fail "missing platform-server jar: $PLATFORM_JAR"
[ -f "$CLI_SRC" ]           || fail "missing cli shaded jar: $CLI_SRC"
[ -f "$MCP_SRC" ]           || fail "missing mcp shaded jar: $MCP_SRC"
[ -f "$SDK_JAR" ]           || fail "missing sdk jar: $SDK_JAR"
[ -f "$BOOTSTRAP_API_JAR" ] || fail "missing bootstrap-api jar: $BOOTSTRAP_API_JAR"
[ -f "$AGENT_BOOTSTRAP_JAR" ] || fail "missing agent-bootstrap shaded jar: $AGENT_BOOTSTRAP_JAR"
[ -f "$AGENT_CORE_JAR" ]    || fail "missing agent-core-modern shaded jar: $AGENT_CORE_JAR"
[ -f "$ATTACH_JAR" ]        || fail "missing attach shaded jar: $ATTACH_JAR"
[ -f "$OPS_JAR" ]           || fail "missing ops shaded jar: $OPS_JAR"

# Reject SNAPSHOT in any staged jar filename (defensive: -Drevision must have applied).
for j in "$PLATFORM_JAR" "$CLI_SRC" "$MCP_SRC" "$SDK_JAR" "$BOOTSTRAP_API_JAR" \
         "$AGENT_BOOTSTRAP_JAR" "$AGENT_CORE_JAR" "$ATTACH_JAR" "$OPS_JAR"; do
  case "$(basename "$j")" in *SNAPSHOT*) fail "SNAPSHOT in staged jar (revision not applied): $j" ;; esac
done

# Identity checks: built jars must report the release version, not SNAPSHOT.
check_manifest_impl_version() {  # <jar> <version>
  local jar="$1" ver="$2" got
  got="$(unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null | grep -i '^Implementation-Version:' | tr -d '\r' || true)"
  got="${got#Implementation-Version: }"
  [ "$got" = "$ver" ] || fail "jar $jar Implementation-Version is '$got', expected '$ver'"
}
check_buildinfo_version() {  # <jar> <version>
  local jar="$1" ver="$2" got
  got="$(unzip -p "$jar" META-INF/build-info.properties 2>/dev/null | grep '^build.version=' | tr -d '\r' || true)"
  got="${got#build.version=}"
  [ "$got" = "$ver" ] || fail "jar $jar build.version is '$got', expected '$ver'"
}
check_cli_version() {  # <jar> <label> <version>  (uses the real --version surface)
  local jar="$1" label="$2" ver="$3" out
  out="$(java -jar "$jar" --version 2>&1 | head -1 || true)"
  echo "$out" | grep -Fq "$ver" || fail "$label --version did not report $ver: $out"
  case "$out" in *SNAPSHOT*) fail "$label --version contains SNAPSHOT: $out" ;; esac
}

echo "==> verifying build identities (no SNAPSHOT in RC artifacts)"
check_buildinfo_version "$PLATFORM_JAR" "$VERSION"
check_cli_version "$CLI_SRC" "kairo-cli" "$VERSION"
check_cli_version "$MCP_SRC" "kairo-mcp" "$VERSION"
check_cli_version "$OPS_JAR" "kairo-ops" "$VERSION"
check_manifest_impl_version "$ATTACH_JAR" "$VERSION"
check_manifest_impl_version "$AGENT_BOOTSTRAP_JAR" "$VERSION"
check_manifest_impl_version "$AGENT_CORE_JAR" "$VERSION"

# Create the output directory AFTER Maven clean (which removes the reactor root target/).
mkdir -p "$OUTPUT"

# --- 9. copy/rename the four standalone release jars ---------------------------------
cp "$PLATFORM_JAR" "$OUTPUT/kairo-platform-server-$VERSION.jar"
cp "$CLI_SRC"      "$OUTPUT/kairo-cli-$VERSION.jar"
cp "$MCP_SRC"      "$OUTPUT/kairo-mcp-$VERSION.jar"
cp "$SDK_JAR"      "$OUTPUT/kairo-sdk-$VERSION.jar"

# --- 10. assemble the agent bundle ---------------------------------------------------
BUNDLE_TOP="kairo-agent-bundle-$VERSION"
BUNDLE_STAGE="$STAGE/$BUNDLE_TOP"
mkdir -p "$BUNDLE_STAGE/lib" "$BUNDLE_STAGE/examples"
cp "$BOOTSTRAP_API_JAR"  "$BUNDLE_STAGE/lib/kairo-bootstrap-api-$VERSION.jar"
cp "$AGENT_BOOTSTRAP_JAR" "$BUNDLE_STAGE/lib/kairo-agent-bootstrap.jar"
cp "$AGENT_CORE_JAR"     "$BUNDLE_STAGE/lib/kairo-agent-core-modern.jar"
cp "$ATTACH_JAR"         "$BUNDLE_STAGE/lib/kairo-attach.jar"
cp "$OPS_JAR"            "$BUNDLE_STAGE/lib/kairo-ops.jar"
cp "$REPO_ROOT/LICENSE" "$BUNDLE_STAGE/LICENSE"
cp "$RELEASE_LIB/bundle/README.md"               "$BUNDLE_STAGE/README.md"
cp "$RELEASE_LIB/bundle/examples/attach-list.sh"  "$BUNDLE_STAGE/examples/attach-list.sh"
cp "$RELEASE_LIB/bundle/examples/attach-launch.sh" "$BUNDLE_STAGE/examples/attach-launch.sh"
cp "$RELEASE_LIB/bundle/examples/ops-version.sh" "$BUNDLE_STAGE/examples/ops-version.sh"
chmod 0755 "$BUNDLE_STAGE/examples/"*.sh

BUNDLE_OUT="$OUTPUT/kairo-agent-bundle-$VERSION.tar.gz"
python3 "$LIB" make-tar --staging-root "$BUNDLE_STAGE" --top-dir "$BUNDLE_TOP" \
  --out "$BUNDLE_OUT" --epoch "$SOURCE_DATE_EPOCH" >/dev/null
echo "    bundle: $BUNDLE_OUT"

# --- 11. assemble the compose archive ------------------------------------------------
COMPOSE_TOP="kairo-compose-$VERSION"
COMPOSE_STAGE="$STAGE/$COMPOSE_TOP"
mkdir -p "$COMPOSE_STAGE"
# Substitute the release version into the sanitized compose template.
sed "s/__KAIRO_VERSION__/$VERSION/g" "$RELEASE_LIB/docker-compose.yml.template" \
  > "$COMPOSE_STAGE/docker-compose.yml"
cp "$RELEASE_LIB/kairo.env.template" "$COMPOSE_STAGE/kairo.env.template"
cp "$RELEASE_LIB/UPGRADE.md"         "$COMPOSE_STAGE/UPGRADE.md"
cp "$RELEASE_LIB/README.md"          "$COMPOSE_STAGE/README.md"

COMPOSE_OUT="$OUTPUT/kairo-compose-$VERSION.tar.gz"
python3 "$LIB" make-tar --staging-root "$COMPOSE_STAGE" --top-dir "$COMPOSE_TOP" \
  --out "$COMPOSE_OUT" --epoch "$SOURCE_DATE_EPOCH" >/dev/null
echo "    compose: $COMPOSE_OUT"

# --- 12. build the two local Docker images (no push) ---------------------------------
echo "==> docker build kairo-platform-server:$VERSION (focused context, no source tree)"
mkdir -p "$SERVER_CTX/kairo-platform-server/target" \
         "$SERVER_CTX/kairo-agent-bootstrap/target" \
         "$SERVER_CTX/kairo-agent-core-modern/target" \
         "$SERVER_CTX/kairo-bootstrap-api/target"
cp "$PLATFORM_JAR"      "$SERVER_CTX/kairo-platform-server/target/kairo-platform-server-$VERSION.jar"
cp "$AGENT_BOOTSTRAP_JAR" "$SERVER_CTX/kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar"
cp "$AGENT_CORE_JAR"    "$SERVER_CTX/kairo-agent-core-modern/target/kairo-agent-core-modern.jar"
cp "$BOOTSTRAP_API_JAR" "$SERVER_CTX/kairo-bootstrap-api/target/kairo-bootstrap-api-$VERSION.jar"
docker build -q -f "$REPO_ROOT/kairo-platform-server/Dockerfile" \
  --build-arg "BUILD_VERSION=$VERSION" -t "kairo-platform-server:$VERSION" "$SERVER_CTX"

echo "==> docker build kairo-platform-web:$VERSION (web source context, BUILD_VERSION label only)"
docker build -q -f "$REPO_ROOT/kairo-platform-web/Dockerfile" \
  --build-arg "BUILD_VERSION=$VERSION" -t "kairo-platform-web:$VERSION" "$REPO_ROOT/kairo-platform-web"

SERVER_REF="kairo-platform-server:$VERSION"
WEB_REF="kairo-platform-web:$VERSION"
docker image inspect "$SERVER_REF" >/dev/null || fail "image not found: $SERVER_REF"
docker image inspect "$WEB_REF"    >/dev/null || fail "image not found: $WEB_REF"
SERVER_INSPECT="$(mktemp -t kairo-srv-inspect.XXXXXX)"
WEB_INSPECT="$(mktemp -t kairo-web-inspect.XXXXXX)"
docker image inspect "$SERVER_REF" > "$SERVER_INSPECT"
docker image inspect "$WEB_REF"    > "$WEB_INSPECT"
echo "    images: $SERVER_REF, $WEB_REF"

# --- 13. secret scan all release content --------------------------------------------
echo "==> secret scan"
SCAN_PATHS=()
while IFS= read -r -d '' p; do SCAN_PATHS+=("$p"); done < <(find "$OUTPUT" -type f -print0)
SCAN_PATHS+=("$COMPOSE_STAGE/docker-compose.yml" "$COMPOSE_STAGE/kairo.env.template" \
             "$COMPOSE_STAGE/UPGRADE.md" "$COMPOSE_STAGE/README.md")
python3 "$LIB" scan-secrets "${SCAN_PATHS[@]}" >/dev/null \
  || fail "secret/dev-token pattern found in release content (see stderr above)"
echo "    secrets: none"

# --- 14. SHA256SUMS for the six file artifacts ---------------------------------------
echo "==> SHA256SUMS"
python3 "$LIB" sha256sums --out "$OUTPUT/SHA256SUMS" \
  "kairo-agent-bundle-$VERSION.tar.gz:$OUTPUT/kairo-agent-bundle-$VERSION.tar.gz" \
  "kairo-platform-server-$VERSION.jar:$OUTPUT/kairo-platform-server-$VERSION.jar" \
  "kairo-cli-$VERSION.jar:$OUTPUT/kairo-cli-$VERSION.jar" \
  "kairo-mcp-$VERSION.jar:$OUTPUT/kairo-mcp-$VERSION.jar" \
  "kairo-sdk-$VERSION.jar:$OUTPUT/kairo-sdk-$VERSION.jar" \
  "kairo-compose-$VERSION.tar.gz:$OUTPUT/kairo-compose-$VERSION.tar.gz" >/dev/null
echo "    SHA256SUMS: $OUTPUT/SHA256SUMS"

# --- 15. release-manifest.json -------------------------------------------------------
echo "==> release-manifest.json"
BUILD_ENDED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SPEC="$STAGE/spec.json"
DIRTY_FILES_FILE_ESC="$DIRTY_FILES_FILE"
[ -n "$DIRTY_FILES_FILE_ESC" ] || DIRTY_FILES_FILE_ESC=""
VERSION="$VERSION" \
GIT_COMMIT="$GIT_COMMIT" \
BUILD_WORKFLOW="$BUILD_WORKFLOW" \
BUILD_STARTED_AT="$BUILD_STARTED_AT" \
BUILD_ENDED_AT="$BUILD_ENDED_AT" \
SOURCE_DATE_EPOCH="$SOURCE_DATE_EPOCH" \
ALLOW_DIRTY="$ALLOW_DIRTY" \
DIRTY_FILES_FILE="$DIRTY_FILES_FILE_ESC" \
OUTPUT="$OUTPUT" \
MVN_VER="$MVN_VER" JAVA_VER="$JAVA_VER" DOCKER_VER="$DOCKER_VER" OS_NAME="$OS_NAME" ARCH="$ARCH" \
SERVER_INSPECT="$SERVER_INSPECT" WEB_INSPECT="$WEB_INSPECT" SPEC_OUT="$SPEC" \
python3 - <<'PY'
import json, os
v = os.environ['VERSION']
spec = {
    'version': v,
    'gitCommit': os.environ['GIT_COMMIT'],
    'buildWorkflow': os.environ['BUILD_WORKFLOW'],
    'buildStartedAt': os.environ['BUILD_STARTED_AT'],
    'buildEndedAt': os.environ['BUILD_ENDED_AT'],
    'sourceDateEpoch': int(os.environ['SOURCE_DATE_EPOCH']),
    'allowDirty': os.environ['ALLOW_DIRTY'] == '1',
    'dirtyFiles': (open(os.environ['DIRTY_FILES_FILE']).read().splitlines()
                   if os.environ.get('DIRTY_FILES_FILE') and os.path.getsize(os.environ['DIRTY_FILES_FILE']) else []),
    'toolchain': {
        'mvn': os.environ['MVN_VER'], 'java': os.environ['JAVA_VER'],
        'docker': os.environ['DOCKER_VER'], 'os': os.environ['OS_NAME'], 'arch': os.environ['ARCH'],
    },
    'files': [
        {'name': 'kairo-agent-bundle-%s.tar.gz' % v, 'type': 'tar.gz',
         'path': os.path.join(os.environ['OUTPUT'], 'kairo-agent-bundle-%s.tar.gz' % v)},
        {'name': 'kairo-platform-server-%s.jar' % v, 'type': 'jar',
         'path': os.path.join(os.environ['OUTPUT'], 'kairo-platform-server-%s.jar' % v)},
        {'name': 'kairo-cli-%s.jar' % v, 'type': 'jar',
         'path': os.path.join(os.environ['OUTPUT'], 'kairo-cli-%s.jar' % v)},
        {'name': 'kairo-mcp-%s.jar' % v, 'type': 'jar',
         'path': os.path.join(os.environ['OUTPUT'], 'kairo-mcp-%s.jar' % v)},
        {'name': 'kairo-sdk-%s.jar' % v, 'type': 'jar',
         'path': os.path.join(os.environ['OUTPUT'], 'kairo-sdk-%s.jar' % v)},
        {'name': 'kairo-compose-%s.tar.gz' % v, 'type': 'tar.gz',
         'path': os.path.join(os.environ['OUTPUT'], 'kairo-compose-%s.tar.gz' % v)},
    ],
    'images': [
        {'name': 'kairo-platform-server:%s' % v,
         'inspect': json.load(open(os.environ['SERVER_INSPECT']))},
        {'name': 'kairo-platform-web:%s' % v,
         'inspect': json.load(open(os.environ['WEB_INSPECT']))},
    ],
}
with open(os.environ['SPEC_OUT'], 'w') as f:
    json.dump(spec, f, indent=2)
PY
python3 "$LIB" build-manifest --spec "$SPEC" --out "$OUTPUT/release-manifest.json" >/dev/null \
  || fail "manifest build/validation failed"
# Final defensive re-validation of the written manifest.
python3 "$LIB" validate-manifest "$OUTPUT/release-manifest.json" >/dev/null \
  || fail "written manifest failed validation"
echo "    manifest: $OUTPUT/release-manifest.json"

# --- 16. summary ---------------------------------------------------------------------
echo "==> release assembled: $OUTPUT"
ls -1 "$OUTPUT"
echo "==> done"
