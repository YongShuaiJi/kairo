#!/usr/bin/env bash
#
# scripts/v1.7/run-compatibility.sh
#
# V1.7 compatibility row-evidence runner (section 10.3). Produces one row-evidence
# JSON file for a fixed C01-C10 scenario. M3-B implements C01/C02/C09 (plain Java);
# M3-C implements C03/C04 (Spring Boot 3 executable jar); M3-D implements C05/C06/C07
# (ClassLoader / proxy / lambda-bridge on a real independent target JVM); M3-E
# implements C08 (redefine/retransform/hot-update drift) and C10 (controlled Byte
# Buddy Agent coexistence) on a real independent target JVM. Later work packages
# add the remaining fixtures. Unavailable formal rows fail closed and C09 may
# be EXPERIMENTAL only when no truthful macOS runner/JDK is available.
#
# Fixed interface (section 10.3):
#   ./scripts/v1.7/run-compatibility.sh \
#     --scenario C01 --output target/v1.7/compatibility-rows/C01.json
#
# Exit codes (preserved exactly from the row runner):
#   0  row produced non-blocking truthful evidence (PASSED or EXPERIMENTAL)
#   1  usage / validation error (incl. dirty PR tree)
#   2  build failed
#   3  row runner unusable
#   4  blocking non-passed evidence (FAILED / SKIPPED / NOT_RUN) - fail-closed
#   5  row-write error
#   6  schema-validation failure
#
# The runner NEVER switches or dirties the active V1.7 worktree. It builds the runner
# in place (HEAD), runs it in a fresh JVM, preserves the exact exit code, and cleans
# only this runner's prior row file. It never modifies the workflow, the acceptance
# manifest, or the support conclusions, and performs no network downloads.

set -euo pipefail
# Fail-fast is on (`-e`). The ONLY intentional non-fatal command is the runner
# invocation: it returns 3/4/5/6 on unusable/blocked/write/schema failure, and we
# capture that status explicitly and exit with the EXACT code (see the `set +e`
# block below) rather than abort early. Every other failing command (build /
# classpath) aborts immediately so it can never silently continue.

SCENARIO=""
OUTPUT=""
ALLOW_DIRTY="false"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
MVN="${MVN:-mvn}"
ORIGINAL_ARGS=("$@")

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNNER_MAIN="com.example.kairo.compatmatrix.CompatibilityRowRunner"
MODULES=(kairo-bootstrap-api kairo-api kairo-groovy kairo-core kairo-agent-core
         kairo-agent-server kairo-agent-core-modern kairo-agent-bootstrap
         kairo-attach-cli kairo-ops kairo-platform-server kairo-sdk kairo-cli
         kairo-mcp kairo-demo kairo-integration-tests)

usage() {
  cat <<'EOF'
Usage: run-compatibility.sh --scenario <C01-C10> --output <row.json>
                            [--allow-dirty] [--help]

Required:
  --scenario    one of C01..C10 (the frozen M3-A catalog; section 10.1)
  --output      output file path for the row-evidence JSON
                (e.g. target/v1.7/compatibility-rows/C01.json)

Optional:
  --allow-dirty DEVELOPMENT ONLY - allow a dirty working tree (records mode=dev).
                Never use for PR/RC/RELEASE evidence; the fixed command refuses a
                dirty tree.
  --help        show this help

Behavior:
  - Builds the runner in place at HEAD (kairo-integration-tests -am test-compile);
    the active V1.7 worktree is never switched or dirtied.
  - Resolves and records the current 40-hex HEAD commit as the build ID.
  - PR evidence (default) refuses a dirty tracked/untracked working tree; use
    --allow-dirty only for local development (mode=dev).
  - Cleans only this runner's prior output file before running.
  - Runs the runner in a fresh JVM; the exact exit code is preserved.
  - C01/C02/C09 run their M3-B real independent-JVM fixtures (plain Java),
    C03/C04 run their M3-C real independent-JVM fixtures (Spring Boot 3
    executable jar), C05/C06/C07 run their M3-D real independent-JVM fixtures
    (parent/child loaders, JDK Proxy/CGLIB/Byte Buddy, lambda/bridge/synthetic),
    and C08/C10 run their M3-E real independent-JVM fixtures (redefine/retransform/
    hot-update drift, controlled Byte Buddy Agent coexistence) when the catalog
    platform and target JDK are available. C07 requires both JDK 17 and JDK 21.
    C06 requires the byte-buddy and spring-core jars; C10 requires the byte-buddy
    jar. Other rows remain fail-closed until their bounded M3 work package lands.
  - Never modifies the workflow, the acceptance manifest, or support conclusions.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) SCENARIO="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY="true"; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

if [[ -z "$SCENARIO" || -z "$OUTPUT" ]]; then
  echo "error: --scenario and --output are required" >&2
  usage
  exit 1
fi
if ! [[ "$SCENARIO" =~ ^C(0[1-9]|10)$ ]]; then
  echo "error: --scenario must be one of C01..C10 (got: $SCENARIO)" >&2
  exit 1
fi

# Resolve the current 40-hex HEAD commit (peeled to the commit object).
HEAD_ID="$(git -C "$REPO_ROOT" rev-parse "HEAD^{commit}")"
if ! [[ "$HEAD_ID" =~ ^[0-9a-f]{40}$ ]]; then
  echo "error: could not resolve a 40-hex HEAD commit (got: $HEAD_ID)" >&2
  exit 1
fi

# Dirty-tree detection. PR/RC/RELEASE evidence (default) refuses a dirty tree;
# --allow-dirty is a clearly-marked development-only escape that records mode=dev.
DIRTY="false"
if [[ -n "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then
  DIRTY="true"
fi
if [[ "$DIRTY" == "true" && "$ALLOW_DIRTY" != "true" ]]; then
  echo "error: evidence refuses a dirty working tree." >&2
  echo "       Commit the runner first, or use --allow-dirty (DEVELOPMENT ONLY, mode=dev)." >&2
  exit 1
fi
MODE="pr"
if [[ "$ALLOW_DIRTY" == "true" ]]; then
  MODE="dev"
fi

# Contamination guard: clear ONLY this runner's prior output file, then recreate parent.
rm -f "$OUTPUT"
PARENT_DIR="$(dirname "$OUTPUT")"
mkdir -p "$PARENT_DIR"

printf -v EXACT_CMD '%q ' "$0" "${ORIGINAL_ARGS[@]}"
EXACT_CMD="${EXACT_CMD% }"

echo "==> scenario=$SCENARIO mode=$MODE working-tree-dirty=$DIRTY"
echo "==> head=$HEAD_ID"

# -----------------------------------------------------------------------------
# Build the runner in place at HEAD. Single side; no worktree, no dirty.
# The classpath goal runs in the same reactor invocation after upstream modules
# have been packaged. A separate single-module invocation cannot resolve this
# project's CI-friendly ${revision} reactor POMs from a fresh Maven repository.
# -----------------------------------------------------------------------------
EXTDEPS_FILE="$(mktemp -t kairo-compat-deps-XXXXXX)"
echo "==> building runner at $REPO_ROOT"
if ! (cd "$REPO_ROOT" && $MVN -B -ntp -pl kairo-integration-tests -am \
      package -DskipTests dependency:build-classpath \
      -Dmdep.outputFile="$EXTDEPS_FILE" -DincludeScope=test -q); then
  echo "error: runner build failed" >&2
  rm -f "$EXTDEPS_FILE"
  exit 2
fi

# -----------------------------------------------------------------------------
# M3-B/M3-C/M3-D/M3-E: for the implemented scenarios (C01/C02/C09 plain Java, C03/C04
# Spring Boot, C05/C06/C07 ClassLoader/proxy/lambda-bridge, C08 redefine/retransform/
# hot-update drift, C10 controlled Byte Buddy Agent coexistence) provision the
# real-execution environment - build the existing agent/bootstrap/core/attach
# artifacts in place, resolve the target JDK homes without any network download,
# and (for C03/C04) build the Spring Boot 3 executable-jar fixture (kairo-demo).
# C06 additionally needs the real byte-buddy and spring-core (repackaged CGLIB)
# jars and C10 needs the real byte-buddy jar resolved onto the target classpath
# (after the dependency classpath is built, below). Non-implemented scenarios are
# left unprovisioned so the runner fails closed per M3-A.
# -----------------------------------------------------------------------------
REAL_PROPS=()
case "$SCENARIO" in
  C01|C02|C03|C04|C05|C06|C07|C08|C09|C10)
    BOOTSTRAP_JAR="$REPO_ROOT/kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar"
    CORE_JAR="$REPO_ROOT/kairo-agent-core-modern/target/kairo-agent-core-modern.jar"
    ATTACH_JAR="$REPO_ROOT/kairo-attach-cli/target/kairo-attach.jar"
    # Always package from the current worktree. Reusing any merely-existing jar
    # could attach stale bytecode while the row claims the current HEAD buildId.
    echo "==> building agent artifacts (bootstrap/core-modern/attach) at $REPO_ROOT"
    if ! (cd "$REPO_ROOT" && $MVN -B -ntp \
          -pl kairo-agent-bootstrap,kairo-agent-core-modern,kairo-attach-cli \
          -am package -DskipTests -q); then
      echo "error: agent artifact build failed" >&2
      exit 2
    fi
    BOOTSTRAP_API_JAR="$(find "$REPO_ROOT/kairo-bootstrap-api/target" -maxdepth 1 -type f \
      -name 'kairo-bootstrap-api-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
      -print -quit 2>/dev/null || true)"
    if [[ ! -f "$BOOTSTRAP_JAR" || ! -f "$BOOTSTRAP_API_JAR" \
          || ! -f "$CORE_JAR" || ! -f "$ATTACH_JAR" ]]; then
      echo "error: agent artifacts still missing after build" >&2
      exit 2
    fi
    # Keep stdout/stderr and auxiliary evidence beside the requested row output.
    # A /tmp directory inside an ephemeral CI container would disappear as soon as
    # the row completes and leave a formally PASSED row pointing at dead evidence.
    WORK_DIR="$(mktemp -d "${PARENT_DIR}/${SCENARIO}-artifacts.XXXXXX")"
    # Resolve a target JDK home for a wanted major. Precedence: explicit
    # KAIRO_JDK<major>_HOME env var, macOS /usr/libexec/java_home -v <major>,
    # then the current JAVA_HOME only if its major matches. The actual major is
    # verified with `java -version` because macOS java_home -v 17 can return a
    # JDK 21 home when no JDK 17 is installed. Never downloads.
    jdk_major_of() {
      local home="$1"
      [[ -x "$home/bin/java" ]] || { printf ''; return; }
      local ver quoted major
      ver="$("$home/bin/java" -version 2>&1 | head -1 || true)"
      quoted="${ver#*\"}"
      quoted="${quoted%%\"*}"
      if [[ "$quoted" == 1.* ]]; then
        major="${quoted#1.}"
        major="${major%%.*}"
      else
        major="${quoted%%.*}"
      fi
      printf '%s' "$major"
    }
    resolve_jdk() {
      local want="$1" home
      local envname="KAIRO_JDK${want}_HOME"
      home="${!envname:-}"
      if [[ -n "$home" && "$(jdk_major_of "$home")" == "$want" ]]; then printf '%s' "$home"; return; fi
      if [[ -x /usr/libexec/java_home ]]; then
        home="$(/usr/libexec/java_home -v "$want" 2>/dev/null || true)"
        if [[ -n "$home" && "$(jdk_major_of "$home")" == "$want" ]]; then printf '%s' "$home"; return; fi
      fi
      if [[ -n "${JAVA_HOME:-}" && "$(jdk_major_of "$JAVA_HOME")" == "$want" ]]; then printf '%s' "$JAVA_HOME"; return; fi
      printf ''
    }
    JDK17_HOME_RESOLVED="$(resolve_jdk 17)"
    JDK21_HOME_RESOLVED="$(resolve_jdk 21)"
    # M3-C: C03/C04 need the Spring Boot 3 executable-jar fixture (kairo-demo), a real
    # spring-boot-maven-plugin:repackage artifact. Built in place from the current
    # worktree so the row's buildId matches the exact bytecode exercised. Reusing any
    # merely-existing jar could attach stale bytecode while the row claims current HEAD.
    SPRINGBOOT_EXEC_JAR=""
    if [[ "$SCENARIO" == "C03" || "$SCENARIO" == "C04" ]]; then
      echo "==> building Spring Boot executable-jar fixture (kairo-demo) at $REPO_ROOT"
      if ! (cd "$REPO_ROOT" && $MVN -B -ntp -pl kairo-demo -am package -DskipTests -q); then
        echo "error: kairo-demo fixture build failed" >&2
        exit 2
      fi
      SPRINGBOOT_EXEC_JAR="$(find "$REPO_ROOT/kairo-demo/target" -maxdepth 1 -type f \
        -name 'kairo-demo-*-exec.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
        -print -quit 2>/dev/null || true)"
      if [[ ! -f "$SPRINGBOOT_EXEC_JAR" ]]; then
        echo "error: kairo-demo executable jar not found after build" >&2
        exit 2
      fi
    fi
    REAL_PROPS+=(
      "-Dkairo.compat.real.exec=true"
      "-Dkairo.compat.repo.root=$REPO_ROOT"
      "-Dkairo.compat.work.dir=$WORK_DIR"
      "-Dkairo.compat.artifacts.bootstrapJar=$BOOTSTRAP_JAR"
      "-Dkairo.compat.artifacts.bootstrapApiJar=$BOOTSTRAP_API_JAR"
      "-Dkairo.compat.artifacts.coreJar=$CORE_JAR"
      "-Dkairo.compat.artifacts.attachJar=$ATTACH_JAR"
      "-Dkairo.compat.timeout.startupMillis=60000"
      "-Dkairo.compat.timeout.operationMillis=30000"
    )
    [[ -n "$JDK17_HOME_RESOLVED" ]] && REAL_PROPS+=("-Dkairo.compat.target.jdk.17=$JDK17_HOME_RESOLVED")
    [[ -n "$JDK21_HOME_RESOLVED" ]] && REAL_PROPS+=("-Dkairo.compat.target.jdk.21=$JDK21_HOME_RESOLVED")
    [[ -n "$SPRINGBOOT_EXEC_JAR" ]] && REAL_PROPS+=("-Dkairo.compat.artifacts.springBootExecJar=$SPRINGBOOT_EXEC_JAR")
    echo "==> real-exec provisioned: jdk17=${JDK17_HOME_RESOLVED:-<none>} jdk21=${JDK21_HOME_RESOLVED:-<none>} springboot-exec=${SPRINGBOOT_EXEC_JAR:-<none>}"
    ;;
esac


# -----------------------------------------------------------------------------
# Assemble the classpath: runner test-classes + reactor target/classes + ext deps.
# -----------------------------------------------------------------------------
reactor_classes() {
  local out=""
  for m in "${MODULES[@]}"; do
    local d="$REPO_ROOT/$m/target/classes"
    if [[ -d "$d" ]]; then out="$out:$d"; fi
  done
  echo "${out#:}"
}
EXTDEPS="$(cat "$EXTDEPS_FILE")"
rm -f "$EXTDEPS_FILE"
CP="$REPO_ROOT/kairo-integration-tests/target/test-classes:$(reactor_classes):$EXTDEPS"

# M3-D C06: resolve the real byte-buddy and spring-core (repackaged CGLIB) jars
# from the dependency classpath so the genuine CGLIB Enhancer and genuine Byte Buddy
# runtime subclass generation are on the independent target JVM classpath. They are
# NOT faked by a class-name marker; a missing jar makes C06 fail closed.
if [[ "$SCENARIO" == "C06" ]]; then
  BYTE_BUDDY_JAR_RESOLVED="$(echo "$EXTDEPS" | tr ':' '\n' | grep -E '/byte-buddy-[0-9].*\.jar$' | grep -v sources | grep -v javadoc | head -1 || true)"
  SPRING_CORE_JAR_RESOLVED="$(echo "$EXTDEPS" | tr ':' '\n' | grep -E '/spring-core-[0-9].*\.jar$' | grep -v sources | grep -v javadoc | head -1 || true)"
  if [[ -z "$BYTE_BUDDY_JAR_RESOLVED" || -z "$SPRING_CORE_JAR_RESOLVED" ]]; then
    echo "error: C06 requires byte-buddy and spring-core jars; resolved byteBuddy=${BYTE_BUDDY_JAR_RESOLVED:-<none>} springCore=${SPRING_CORE_JAR_RESOLVED:-<none>}" >&2
    exit 2
  fi
  REAL_PROPS+=("-Dkairo.compat.artifacts.byteBuddyJar=$BYTE_BUDDY_JAR_RESOLVED")
  REAL_PROPS+=("-Dkairo.compat.artifacts.springCoreJar=$SPRING_CORE_JAR_RESOLVED")
  echo "==> C06 aux jars: byteBuddy=$BYTE_BUDDY_JAR_RESOLVED springCore=$SPRING_CORE_JAR_RESOLVED"
fi

# M3-E C10: resolve the real byte-buddy jar from the dependency classpath so the
# single controlled Byte Buddy Agent fixture (genuine net.bytebuddy AgentBuilder +
# Advice) runs on the independent target JVM classpath. NOT faked by a class-name
# marker; a missing jar makes C10 fail closed. C08 (redefine/retransform/hot-update
# drift) is pure Java and needs no auxiliary jar: its harness agent captures the real
# java.lang.instrument.Instrumentation directly.
if [[ "$SCENARIO" == "C10" ]]; then
  BYTE_BUDDY_JAR_RESOLVED="$(echo "$EXTDEPS" | tr ':' '\n' | grep -E '/byte-buddy-[0-9].*\.jar$' | grep -v sources | grep -v javadoc | head -1 || true)"
  if [[ -z "$BYTE_BUDDY_JAR_RESOLVED" ]]; then
    echo "error: C10 requires the byte-buddy jar; resolved byteBuddy=${BYTE_BUDDY_JAR_RESOLVED:-<none>}" >&2
    exit 2
  fi
  REAL_PROPS+=("-Dkairo.compat.artifacts.byteBuddyJar=$BYTE_BUDDY_JAR_RESOLVED")
  echo "==> C10 aux jar: byteBuddy=$BYTE_BUDDY_JAR_RESOLVED"
fi

echo "==> running row runner (scenario=$SCENARIO)"
# The runner returns 3/4/5/6 on unusable/blocked/write/schema failure. Temporarily
# disable `-e` so those codes are captured (not turned into an early abort), then
# exit with the EXACT code. The row file is always written (best effort).
RUNNER_STATUS=0
set +e
# ${REAL_PROPS[@]+...} is the bash-3.2-safe empty-array expansion under `set -u`.
"$JAVA_BIN" -cp "$CP" ${REAL_PROPS[@]+"${REAL_PROPS[@]}"} "$RUNNER_MAIN" \
  --scenario "$SCENARIO" \
  --output "$OUTPUT" \
  --build-id "$HEAD_ID" \
  --command "$EXACT_CMD" \
  --mode "$MODE" \
  --working-tree-dirty "$DIRTY"
RUNNER_STATUS=$?
set -e

echo "==> done (runner exit=$RUNNER_STATUS). row: $OUTPUT"
exit "$RUNNER_STATUS"
